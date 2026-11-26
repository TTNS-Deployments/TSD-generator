# Project progress: TSD Generator

## Context
- Generates TTNS Technical Solution Documents (`.docx`) from one web form.
- **D1** (AVD, TTNS does solutioning and deployment): form + Gemini-written sections → `templates/AVD_TSD-V1_0.docx`. Works, but the output quality needs improving later.
- **D2** (partner/customer deploys, Disti2): no AI, the form fills placeholders. Three documents:
  - Azure Subscription: discount on usage; BOQ = Azure Pricing Calculator export pasted as is.
  - Self-Deployment RI: no discount (total = usage); BOQ as above.
  - CSP to CSP billing migration: optional Prerequisites table; calculations from the last 3 invoices (RI never discounted, discount only on Azure/non-reserved); currency USD/INR; commercials A − B + C. Row B is removed when there's no discount. No BOQ.
- Repo: https://github.com/TTNS-Deployments/TSD-generator (public, `main`).

## Decisions
- D2 templates are generated from the yellow-highlighted samples in `templates/D2 samples/` by `tools/make_d2_template.py`. Non-highlighted inputs are placed by table cell.
- Money: Indian digit grouping (₹ 1,23,456 / INR 1,45,062/-). Cover date "Sep 09, 2025". Region in capitals.
- The form is served by Spring Boot (`src/main/resources/static/index.html`), with one D2 form for all D2 types (`data-d2-types`).
- The Gemini key is never in code: `GEMINI_API_KEY` environment variable / app setting.
- Hosting: same pattern as the portal and the demos. A web app on the shared B1 plan `ccai-demo-gqausd-web-plan` (resource group `Vaibhav.Chaudhary`), named `tsdgen-demo-<suffix>-web`. Cloud Shell only has management-API tokens, so GitHub Actions builds a release package and the app downloads it on start (`TSD_PACKAGE_URL`).

## Status (2026-11-26)
- [x] D2 Azure Subscription, Self-Deployment RI, CSP to CSP (tested locally, opened in Word)
- [x] Build workflow and Cloud Shell deploy script (only syntax-checked locally; the package was tested by running it locally)
- [ ] First GitHub Actions build
- [ ] First deployment from Cloud Shell
- [ ] Add the TSD Generator to the portal (D1/D2 tiles)

## Next steps
- Improve D1 output quality (move to Azure OpenAI gpt-5-mini like the other demos?).
- Rotate the Gemini key that used to be hard-coded in `application.properties` (never committed).
