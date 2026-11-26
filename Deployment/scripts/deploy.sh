#!/usr/bin/env bash
# Deploys the TSD Generator to Azure App Service.
#
# Run it from Azure Cloud Shell (Bash):
#   cd ~ && (git clone https://github.com/TTNS-Deployments/TSD-generator.git 2>/dev/null || true) \
#     && cd ~/TSD-generator && git pull && bash Deployment/scripts/deploy.sh -e demo
#
# How it works
# - Cloud Shell can only get tokens for the Azure management API, so zip deploy, Kudu and publish profiles
#   don't work. This script only uses management calls.
# - GitHub Actions (.github/workflows/build.yml) builds the app on every push to main and publishes a GitHub
#   Release "build-<commit>" holding tsd-generator.tar.gz (app.jar + templates + prompts).
# - The web app downloads that package when it starts, pinned to a release through the TSD_PACKAGE_URL app
#   setting, unpacks it and runs the jar. A deployment is just: point the setting at the newest release, restart.
# - Names follow the same convention as the other demo apps: <prefix>-<env>-<suffix>-web, with
#   suffix = take(uniqueString(resourceGroup().id, prefix, env), 6), computed by ARM itself.
# - The web app runs on the existing B1 Linux plan shared with the demo apps. This script never changes
#   that plan or any other app.
# - D1 (AI) needs a Gemini API key. Pass it once with GEMINI_API_KEY=... in front of the command, or type it
#   when asked. It is stored as an app setting and never printed. D2 works without it.
#
# Usage: deploy.sh [-e <env>] [--app-name <name>] [--branch <branch>]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="$SCRIPT_DIR/../environments"

say()  { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# Options (command-line flags win over the environment file)
ENV="demo"
CLI_APP_NAME=""
CLI_BRANCH=""
while [ $# -gt 0 ]; do
  case "$1" in
    -e|--env)      ENV="$2"; shift 2 ;;
    --app-name)    CLI_APP_NAME="$2"; shift 2 ;;
    --branch)      CLI_BRANCH="$2"; shift 2 ;;
    -h|--help)     sed -n '2,24p' "$0"; exit 0 ;;
    *)             fail "Unknown option: $1" ;;
  esac
done

ENV_FILE="$ENV_DIR/$ENV.env"
[ -f "$ENV_FILE" ] || fail "No environment file $ENV_FILE"
# shellcheck disable=SC1090
source "$ENV_FILE"
[ -n "$CLI_BRANCH" ] && BRANCH="$CLI_BRANCH"
: "${RESOURCE_GROUP:?set in $ENV_FILE}" "${SOLUTION_PREFIX:?}" "${ENVIRONMENT_NAME:?}" "${EXISTING_PLAN_NAME:?}" "${REPO:?}" "${BRANCH:?}"
EXISTING_PLAN_RESOURCE_GROUP="${EXISTING_PLAN_RESOURCE_GROUP:-$RESOURCE_GROUP}"
SOLUTION_TAG="${SOLUTION_TAG:-$SOLUTION_PREFIX}"
JAVA_MAX_HEAP="${JAVA_MAX_HEAP:-384m}"

command -v az >/dev/null || fail "Azure CLI not found. Run this in Azure Cloud Shell (Bash)."
if [ -n "${AZURE_SUBSCRIPTION:-}" ]; then az account set --subscription "$AZURE_SUBSCRIPTION"; fi
az account show --query id -o tsv >/dev/null || fail "Not signed in to Azure. Run: az login"
say "Environment: $ENV | Subscription: $(az account show --query name -o tsv) | Resource group: $RESOURCE_GROUP"

[ "$(az group exists -n "$RESOURCE_GROUP")" = "true" ] || fail "Resource group $RESOURCE_GROUP does not exist."

# ---------------------------------------------------------------------------------------------
say "Finding the shared App Service plan $EXISTING_PLAN_NAME"
PLAN_ID=$(az appservice plan show -g "$EXISTING_PLAN_RESOURCE_GROUP" -n "$EXISTING_PLAN_NAME" --query id -o tsv 2>/dev/null || true)
[ -n "$PLAN_ID" ] || fail "App Service plan $EXISTING_PLAN_NAME not found in $EXISTING_PLAN_RESOURCE_GROUP."
echo "    $(az appservice plan show --ids "$PLAN_ID" --query "join(' | ', [name, sku.name, location, kind])" -o tsv)"

# ---------------------------------------------------------------------------------------------
if [ -n "$CLI_APP_NAME" ]; then
  APP_NAME="$CLI_APP_NAME"
else
  say "Computing the resource name suffix (same formula as the demo templates)"
  NAMING_TEMPLATE=$(mktemp --suffix .json)
  cat > "$NAMING_TEMPLATE" <<'JSON'
{
  "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#",
  "contentVersion": "1.0.0.0",
  "parameters": { "solutionPrefix": { "type": "string" }, "environmentName": { "type": "string" } },
  "resources": [],
  "outputs": {
    "suffix": {
      "type": "string",
      "value": "[take(uniqueString(resourceGroup().id, parameters('solutionPrefix'), parameters('environmentName')), 6)]"
    }
  }
}
JSON
  SUFFIX=$(az deployment group create -g "$RESOURCE_GROUP" -n "${SOLUTION_PREFIX}-${ENVIRONMENT_NAME}-naming" \
             --template-file "$NAMING_TEMPLATE" \
             --parameters solutionPrefix="$SOLUTION_PREFIX" environmentName="$ENVIRONMENT_NAME" \
             --query properties.outputs.suffix.value -o tsv)
  rm -f "$NAMING_TEMPLATE"
  [ -n "$SUFFIX" ] || fail "Could not compute the name suffix."
  APP_NAME="$(printf '%s' "$SOLUTION_PREFIX" | tr '[:upper:]' '[:lower:]')-${ENVIRONMENT_NAME}-${SUFFIX}-web"
fi
echo "    Web app name: $APP_NAME"

# ---------------------------------------------------------------------------------------------
say "Finding the build of the latest commit on $REPO@$BRANCH"
SHA=$(curl -fsSL "https://api.github.com/repos/$REPO/commits/$BRANCH" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])' 2>/dev/null || true)
[ -n "$SHA" ] || fail "Could not find the latest commit of $REPO."
TAG="build-${SHA:0:7}"
PACKAGE_URL="https://github.com/$REPO/releases/download/$TAG/tsd-generator.tar.gz"
echo "    Commit: $SHA (release $TAG)"
# GitHub Actions takes a few minutes after a push; wait for it (up to 15 minutes)
for i in $(seq 1 90); do
  # 1-byte ranged GET (release assets redirect to signed storage URLs that don't always answer HEAD)
  if curl -fsL -r 0-0 -o /dev/null "$PACKAGE_URL"; then break; fi
  [ "$i" = 1 ] && echo "    The build for this commit isn't published yet. Waiting (see the Actions tab on GitHub)..."
  [ "$i" = 90 ] && fail "No release $TAG after 15 minutes. Check the Actions tab: https://github.com/$REPO/actions"
  sleep 10
done
echo "    Package: $PACKAGE_URL"

# ---------------------------------------------------------------------------------------------
TAGS=(solution="$SOLUTION_TAG" environment="$ENVIRONMENT_NAME")
if az webapp show -g "$RESOURCE_GROUP" -n "$APP_NAME" >/dev/null 2>&1; then
  say "Web app $APP_NAME already exists. Updating it."
else
  # Java 17 SE (the app is built for 17); fall back to the newest Java SE App Service offers on Linux
  RUNTIMES=$(az webapp list-runtimes --os linux -o tsv 2>/dev/null || true)
  RUNTIME=$(printf '%s\n' "$RUNTIMES" | grep -E '^JAVA:17-java17$' || true)
  [ -n "$RUNTIME" ] || RUNTIME=$(printf '%s\n' "$RUNTIMES" | grep -E '^JAVA:[0-9]+-java[0-9]+$' | sort -t: -k2 -V | tail -1 || true)
  RUNTIME="${RUNTIME:-JAVA:17-java17}"
  say "Creating web app $APP_NAME ($RUNTIME) on the shared plan"
  az webapp create -g "$RESOURCE_GROUP" -p "$PLAN_ID" -n "$APP_NAME" --runtime "$RUNTIME" --tags "${TAGS[@]}" -o none \
    || fail "Could not create $APP_NAME. If the name is taken, rerun with --app-name <another-name>."
fi
WEB_ID=$(az webapp show -g "$RESOURCE_GROUP" -n "$APP_NAME" --query id -o tsv)
az tag update --resource-id "$WEB_ID" --operation Merge --tags "${TAGS[@]}" -o none

say "Configuring the web app"
az webapp update -g "$RESOURCE_GROUP" -n "$APP_NAME" --https-only true -o none
# On start: download the pinned package, unpack it into /home/site/wwwroot/app (keeps the last copy if the
# download fails), then run the jar on port 8080 with a capped heap.
az webapp config set -g "$RESOURCE_GROUP" -n "$APP_NAME" -o none \
  --always-on true \
  --ftps-state Disabled \
  --startup-file 'APP=/home/site/wwwroot/app; curl -fsSL "$TSD_PACKAGE_URL" -o /tmp/tsd.tar.gz && rm -rf $APP && mkdir -p $APP && tar -xzf /tmp/tsd.tar.gz -C $APP || echo "Package download failed, starting the last copy"; cd $APP && exec java -Xmx$JAVA_MAX_HEAP -jar app.jar --server.port=8080'

SETTINGS=(
  TSD_PACKAGE_URL="$PACKAGE_URL"
  TSD_COMMIT="$SHA"
  JAVA_MAX_HEAP="$JAVA_MAX_HEAP"
  WEBSITES_PORT=8080
  WEBSITES_CONTAINER_START_TIME_LIMIT=600
  SCM_DO_BUILD_DURING_DEPLOYMENT=false
)

# Gemini key for D1: from the environment, or asked once (hidden input). Kept if already set.
HAS_KEY=$(az webapp config appsettings list -g "$RESOURCE_GROUP" -n "$APP_NAME" --query "length([?name=='GEMINI_API_KEY' && value!=''])" -o tsv)
if [ -n "${GEMINI_API_KEY:-}" ]; then
  SETTINGS+=(GEMINI_API_KEY="$GEMINI_API_KEY")
  echo "    Gemini API key: taken from GEMINI_API_KEY"
elif [ "$HAS_KEY" = "1" ]; then
  echo "    Gemini API key: already set (unchanged)"
elif [ -t 0 ]; then
  read -rsp "    Gemini API key for D1 (input hidden; press Enter to skip, D2 works without it): " KEY_IN; echo
  if [ -n "$KEY_IN" ]; then SETTINGS+=(GEMINI_API_KEY="$KEY_IN"); fi
  unset KEY_IN
fi

say "Pointing the app at $TAG"
az webapp config appsettings set -g "$RESOURCE_GROUP" -n "$APP_NAME" -o none --settings "${SETTINGS[@]}"

say "Restarting"
az webapp restart -g "$RESOURCE_GROUP" -n "$APP_NAME" -o none

# ---------------------------------------------------------------------------------------------
HOST=$(az webapp show -g "$RESOURCE_GROUP" -n "$APP_NAME" --query defaultHostName -o tsv)
URL="https://$HOST"
say "Waiting for $URL to start (download + Java startup on B1 can take 2-4 minutes)"
for i in $(seq 1 40); do
  if curl -fsS --max-time 30 "$URL/" 2>/dev/null | grep -q "TSD Generator"; then
    say "Done. The TSD Generator is live at: $URL"
    exit 0
  fi
  sleep 15
done

cat <<EOF

The deployment finished, but $URL is not answering yet.
- Wait a minute and open it in the browser.
- If it still fails, open $APP_NAME in the Azure portal, go to Monitoring > Log stream, and run this script again.
EOF
exit 1
