# TSD Generator

A document automation tool for Solution Architects at Tata Tele NXTGEN Solutions Limited (TTNS). Fill in one web form and download a finished Technical Solution Document (`.docx`).

| TSD type | Who deploys | How it's built |
|---|---|---|
| **D1** – Azure Virtual Desktop | TTNS (solutioning and deployment) | Form values, plus narrative sections written by Google Gemini, filled into `templates/AVD_TSD-V1_0.docx` |
| **D2** – Azure Subscription (Disti2) | Partner / customer (self-deployment) | **No AI.** Form values are filled into `templates/D2/azure-subscription.docx`, the commercials are calculated, and the Azure Pricing Calculator export is pasted as the BOQ table |
| **D2** – Self-Deployment RI (Disti2) | Partner / customer | Same as Azure Subscription, but **no discount**: total monthly billing = expected usage. Template `templates/D2/self-deployment-ri.docx` |
| **D2** – CSP to CSP billing migration (Disti2) | TTBS + customer (billing transfer only) | **No BOQ.** Prerequisites table (optional answers) + calculations from the last 3 invoices → commercials. Template `templates/D2/csp-to-csp.docx` |

---

## Prerequisites

- Java 17+ JDK (Eclipse Adoptium)
- Maven

## Configuration

| Setting | Where | Needed for |
|---|---|---|
| `GEMINI_API_KEY` | Environment variable (on Azure: app setting) | D1 only. **Never put the key in `application.properties`.** |
| `PORT` | Environment variable, optional (default 8080) | Hosting |

Windows, permanent: System Properties → Advanced → Environment Variables → New → `GEMINI_API_KEY`.

## Run

```
mvn spring-boot:run
```

Then open **http://localhost:8080**. The form is served by the app (`src/main/resources/static/index.html`).

1. Choose **TSD Type**: D1 or D2.
2. For D2, choose the **D2 Document** (for example, Azure Subscription).
3. Fill in the fields and click **Generate TSD**.

## Deploy (Azure Cloud Shell)

1. Push to `main`. GitHub Actions (`.github/workflows/build.yml`) builds the app and publishes a release `build-<commit>` with `tsd-generator.tar.gz` (app.jar + templates + prompts). This takes about 2 minutes. Watch it in the repo's **Actions** tab.
2. Open Azure Cloud Shell in **Bash** mode and paste:

```bash
cd ~ && (git clone https://github.com/TTNS-Deployments/TSD-generator.git 2>/dev/null || true) && cd ~/TSD-generator && git pull && bash Deployment/scripts/deploy.sh -e demo
```

On the first run, the script asks for the **Gemini API key** (hidden input). D1 needs it; press Enter to skip, and D2 still works. The key is stored as the app setting `GEMINI_API_KEY` and kept on later runs.

What the script does (management API calls only, because Cloud Shell can't use zip deploy or Kudu):
- Names the app with the same convention as the other demos: `tsdgen-demo-<suffix>-web`, suffix = `take(uniqueString(resourceGroup().id, prefix, env), 6)`.
- Creates it on the shared B1 Linux plan `ccai-demo-gqausd-web-plan` (Java 17 SE) if it doesn't exist. Tags: `solution=tsd-generator`, `environment=demo`. The plan itself is never changed.
- Waits for the GitHub build of the latest commit, then points the app setting `TSD_PACKAGE_URL` at that release.
- On start, the app downloads and unpacks the package into `/home/site/wwwroot/app` and runs `java -Xmx384m -jar app.jar --server.port=8080`.
- Restarts the app and waits until it answers, then prints the URL.

Settings: `Deployment/environments/demo.env`. Options: `-e <env>`, `--app-name <name>`, `--branch <branch>`.

## API

| Endpoint | Body | Returns |
|---|---|---|
| `POST /api/generate` | JSON (D1 form) | `.docx` |
| `POST /api/generate-d2` | multipart: `data` = JSON `{"d2_type": "azure-subscription", "fields": {...}}`, `boq` = `.xlsx` | `.docx` |

## D2 templates

D2 templates are built from the filled-in samples in `templates/D2 samples/`. In the samples, every value that changes per customer is **highlighted yellow**. The script below replaces each highlighted value with a `{{placeholder}}`, removes the highlight, and replaces the BOQ picture with a `{{boq_table}}` marker:

```
python tools/make_d2_template.py "templates/D2 samples/01-TTNS_Azure Subscription-TSD-Disti2-v2.0.docx" templates/D2/azure-subscription.docx azure-subscription
python tools/make_d2_template.py "templates/D2 samples/03-TTNS-SelfDeployement-RI-TSD-Disti2-v2.0.docx" templates/D2/self-deployment-ri.docx self-deployment-ri
python tools/make_d2_template.py "templates/D2 samples/02-TTNS-CSP toCSP-TSD-Disti2-v2.0.docx" templates/D2/csp-to-csp.docx csp-to-csp
```

Values that aren't highlighted (empty answer cells, calculation cells) are placed by table cell. See `cells` in `tools/make_d2_template.py`. The script also removes yellow cell fills, which some samples use to mark input cells.

### CSP to CSP: calculations

From the last 3 invoices, split into **RI** (reserved instances, never discounted) and **Azure** (non-reserved/PayG, the only part the discount applies to):

| Value | Formula |
|---|---|
| Average | (month 1 + month 2 + month 3) / 3 |
| INR | average × exchange rate (USD), or the average itself (INR). Whole rupees |
| Discount | Azure INR × discount % (RI: always 0) |
| Total | INR − discount |
| Commercials | A = Azure INR, B = discount, C = RI INR, Total = A − B + C. With no discount, row B is removed and the label becomes "=A+C" |

The currency (USD/INR) sets the symbol over the table. The exchange rate is only needed for USD. A discount is rejected when there is no Azure (non-reserved) usage. Months are chosen as month + year (`m1`–`m3`, e.g. `2025-05` → "May-25").

All D2 documents share one form. Fields that only apply to some D2 types, such as Discount %, have `data-d2-types="..."` in `index.html` and are hidden for the other types.

**Self-Deployment RI** uses the same fields as Azure Subscription without `discount_pct`. `{{monthly_usage}}` and `{{total_monthly_billing}}` both receive the expected monthly usage.

To change a D2 document, edit the sample, keep the customer-specific values highlighted, and rerun the script. If the script reports highlighted text it doesn't recognise, add that text to the mapping in `tools/make_d2_template.py`.

### Azure Subscription: what the form fills in

| Form field | Placeholder | Notes |
|---|---|---|
| Customer / Partner name | `{{customer_name}}` | Cover, Presented To, Document Objective, signatures |
| End customer | `{{end_customer}}` | Cover, Mandatory Account Details |
| Date | `{{date}}` | Cover, formatted like "Sep 09, 2025" |
| DBCH Quote ID | `{{quote_id}}` | |
| AM name / email, SA name / email | `{{am_name}}` … `{{sa_email}}` | |
| Client SPOC name / role / email | `{{spoc_name}}` … | Role defaults to "Cloud Delivery & Support" |
| Region | `{{region}}` | NORTH / SOUTH / WEST / MUMBAI / DGIS |
| Expected monthly usage (₹) | `{{monthly_usage}}` | Indian digit grouping, e.g. ₹ 1,23,456 |
| Discount % | `{{discount_pct}}` | |
| (calculated) | `{{discount_amount}}`, `{{post_discount_usage}}`, `{{total_monthly_billing}}` | discount = usage × %; post discount = total = usage − discount |
| BOQ (.xlsx) | `{{boq_table}}` | First sheet pasted as is under 4.2. Set the calculator currency to INR before exporting |
