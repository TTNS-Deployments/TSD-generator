# TSD Generator — Session Summary

## What Was Built

Java Spring Boot 3.2 app replacing a Python/FastAPI version. Collects AVD customer details via a web form, calls Gemini API to generate narrative sections, fills a Word template using Apache POI, returns the completed `.docx` as a download.

## Stack

- Java 17 (Eclipse Adoptium JDK 25.0.2)
- Spring Boot 3.2, Maven
- Apache POI 5.3.0 (`poi-ooxml-full`)
- OkHttp 4.12, Gson 2.10.1
- Gemini API (`gemini-2.0-flash` currently; switch to `gemini-2.5-flash` when quota resets)

## How to Start the Server

```bash
cd "d:/TSD Generator"
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.2.10-hotspot" "/c/Program Files/Maven/apache-maven-3.9.14/bin/mvn" spring-boot:run
```

## Issues Fixed

| Issue | Fix |
|-------|-----|
| Gemini 404 (deprecated model) | Changed to `gemini-2.5-flash` / `gemini-2.0-flash` |
| `XmlAnyTypeImpl cannot be cast to CTP` | Added `poi-ooxml-full`, excluded `poi-ooxml-lite` in pom.xml |
| Split-run placeholder problem | `replaceInParagraphRuns()` concatenates all run text before replacing |
| Table cells not replacing | Switched from hardcoded row/col indices to scan-all-cells approach |
| `XmlValueDisconnectedException` | Replaced cursor-based bullet insertion with CTBody XML string approach |
| DOCX file corruption | Abandoned OPCPackage/ZIP post-processing; do all replacements in-memory before `doc.write()` |
| `expandBulletsInXml` silent failure | `lastIndexOf("<w:p")` was matching `<w:pPr>` etc. — fixed to only match `<w:p>` or `<w:p ` |
| Cover page text boxes not replacing | CTBody XML pass + reflection-based header pass |
| Success criteria printing in Existing Architecture section | `replaceParaWithText()` fills section 2.2 with `existingArchitecture` text; `expandBulletsInXml("{{success_criteria}}")` fills section 4 with bullets |

## How DocumentService Fills the Template

| Part | What it does |
|------|--------------|
| **Part A** | POI paragraph scan — replaces all `{{placeholder}}` tokens in body paragraphs |
| **Part B** | POI table cell scan — same replacements across all tables, rows, cells |
| **Part C** | Finds paragraph with `problem_statement` → sets LLM problem statement text |
| **Part D** | Finds paragraph with `existing_architecture` → sets LLM existing architecture text |
| **Part F (CTBody XML)** | Raw XML string replacements on body (catches text boxes); bullet expansion via `expandBulletsInXml` and `replaceParaWithText`; applied via `CTBody.Factory.parse()` before save |
| **Header pass** | Reflection on `XWPFHeaderFooter.headerFooter` field to access `CTHdrFtr`; replaces all placeholders in header XML |

## Template Placeholder Formats

| Placeholder | Location | Handler |
|-------------|----------|---------|
| `{{customer_name}}`, `{{date}}`, etc. | Body paragraphs, tables, text boxes, headers | Parts A, B, F, Header pass |
| `{{problem_statement}}` | Body paragraph | Part C |
| `{{existing_architecture}}` | Body paragraph | Part D |
| `{% for item in current_infra %}{{item}}{% endfor %}` | Section 2.1 | `expandBulletsInXml("current_infra", ...)` |
| `{% for item in success_criteria %}{{item}}{% endfor %}` | Section 2.2 | `replaceParaWithText("for item in success_criteria", existingArch)` |
| `{{success_criteria}}` | Section 4 | `expandBulletsInXml("{{success_criteria}}", ...)` |

## Pending Verification

Cover page `{{customer_name}}` + `{{date}}`, section 2.1 bullets, section 2.2 existing architecture text, and section 4 success criteria bullets — all fixes are in place but visual verification was blocked by Gemini API quota exhaustion (20 req/day free tier). Ready to test once quota resets or billing is enabled.
