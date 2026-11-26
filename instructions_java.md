# TSD Generator — Java Spring Boot Rebuild Instructions
# For Claude Code (claude-sonnet-4-6)

Read this entire file completely before doing anything.
Delete all existing project files first, then rebuild everything from scratch in Java.
Do not ask any questions. Execute everything, then follow the final steps at the end.

---

## STEP 0 — DELETE ALL EXISTING FILES

Delete everything in the current project folder except this instructions.md file,
the AVD_TSD-V1_0.docx template file, and the avd_system_prompt.txt file.
This includes all Python files, HTML files, requirements.txt, .env, .gitignore,
any __pycache__ folders, and any other files created previously.
Keep only:
- instructions.md
- AVD_TSD-V1_0.docx (if present)
- avd_system_prompt.txt (if present)

---

## PROJECT OVERVIEW

TSD Generator is an intelligent document automation tool for solution architects
at Tata Tele NXTGEN Solutions Limited (TTNS). Architects fill a web form with
customer details. The Spring Boot backend receives the form data, calls Azure OpenAI
to generate narrative sections, then uses Apache POI to fill a Word template with
both the structured field data and the LLM generated content. The completed document
is returned as a downloadable .docx file.

---

## COMPLETE FOLDER STRUCTURE TO CREATE

```
TSD-Generator/
├── frontend/
│   └── index.html
├── templates/
│   └── (user places AVD_TSD-V1_0.docx here)
├── prompts/
│   └── archive/
│   └── (user places avd_system_prompt.txt here)
├── temp/
│   └── (auto-managed — created and cleared by application)
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── ttns/
│       │           └── tsdgenerator/
│       │               ├── TsdGeneratorApplication.java
│       │               ├── controller/
│       │               │   └── TsdController.java
│       │               ├── service/
│       │               │   ├── LlmService.java
│       │               │   └── DocumentService.java
│       │               └── model/
│       │                   ├── FormData.java
│       │                   └── LlmContent.java
│       └── resources/
│           └── application.properties
├── pom.xml
├── .gitignore
└── README.md
```

---

## TECH STACK

- Java 17
- Spring Boot 3.2
- Apache POI 5.3 (Word document manipulation)
- OkHttp 4.12 (HTTP client for Azure OpenAI API calls)
- Gson 2.10 (JSON parsing)
- Maven (build tool)

---

## APPLICATION FLOW

```
1. Architect fills index.html form and clicks Generate TSD
2. JavaScript collects all form fields into a JSON object
3. fetch() sends POST request to http://localhost:8080/api/generate
4. TsdController receives the request and validates the FormData
5. TsdController calls LlmService.buildPrompt() to prepare the prompt
6. TsdController calls LlmService.generateContent() to get LLM output
7. TsdController calls DocumentService.generateDocument() to fill the template
8. DocumentService.createTempCopy() copies the master template to /temp
9. DocumentService.fillDocument() fills all placeholders using Apache POI
10. The filled .docx file bytes are returned as a download response
11. cleanup() deletes the temp file after response is sent
12. Browser receives the file and auto-downloads it
```

---

## pom.xml DEPENDENCIES

Create pom.xml with these exact dependencies:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.ttns</groupId>
    <artifactId>tsd-generator</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
    </parent>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
            <version>5.3.0</version>
        </dependency>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp</artifactId>
            <version>4.12.0</version>
        </dependency>
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.10.1</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## application.properties

```properties
server.port=8080
gemini.api.key=${GEMINI_API_KEY}
gemini.model=gemini-1.5-flash
template.path=templates/AVD_TSD-V1_0.docx
prompt.path=prompts/avd_system_prompt.txt
temp.path=temp/
```

---

## MODEL CLASSES

### FormData.java

Fields with their Java types:

```java
// Required fields
private String customerName;
private String date;
private String quoteId;
private String amName;
private String amEmail;
private String saName;
private String saEmail;
private String spocName;
private String spocRole;
private String spocEmail;
private String region;
private int numUsers;
private int fslogixSize;

// Optional fields - default to empty string if not provided
private String pssName = "";
private String pssEmail = "";
private String tenantId = "";
private String domainName = "";
private String painPoint = "";
private String workloadType = "";
private String applications = "";
private String adSetupType = "";
private String complianceReq = "";
```

Include standard getters and setters for all fields.
Use @JsonProperty annotations to map camelCase Java fields
to snake_case JSON keys from the form:
customer_name → customerName, am_name → amName, etc.

### LlmContent.java

```java
private String problemStatement;
private List<String> currentInfra;
private String existingArchitecture;
private List<String> successCriteria;
```

Include standard getters and setters.

---

## CONTROLLER — TsdController.java

Package: com.ttns.tsdgenerator.controller

This class has ONE endpoint and ONE startup method.

### Annotations
- @RestController
- @RequestMapping("/api")
- @CrossOrigin(origins = "*") — allows browser requests from local HTML file

### Method 1: init()
- Annotation: @PostConstruct
- Purpose: Runs once when the application starts
- Behaviour: Creates the /temp directory if it does not exist,
  then deletes all files inside /temp to clear any leftover
  files from a previous crash
- Uses: Java NIO Files and Paths

### Method 2: generateTsd(@RequestBody FormData formData)
- Annotation: @PostMapping("/generate")
- Returns: ResponseEntity<byte[]>
- Purpose: Main endpoint that orchestrates the entire generation flow
- Steps in exact order:
  1. Call llmService.buildPrompt(formData) to get the prompt dict
  2. Call llmService.generateContent(promptDict) to get LlmContent
  3. Call documentService.generateDocument(formData, llmContent) to get byte[]
  4. Set response headers:
     Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document
     Content-Disposition: attachment; filename="AVD_TSD_{customerName}_{date}.docx"
     (sanitise customerName by replacing spaces with underscores)
  5. Return ResponseEntity.ok() with the byte array
- Wrap everything in try/catch
- On error return ResponseEntity.internalServerError() with error message

---

## SERVICE — LlmService.java

Package: com.ttns.tsdgenerator.service
Annotation: @Service

This service has TWO methods.

### Method 1: buildPrompt(FormData formData) → Map<String, String>

Purpose: Reads the system prompt file and builds the user message

Steps:
1. Read the entire contents of the file at prompt.path as a UTF-8 string
   This becomes the value for key "systemPrompt" in the returned map
2. Build the userMessage string:
   - Always include:
     "Generate TSD sections for the following customer:\n"
     "customer_name: " + formData.getCustomerName() + "\n"
     "num_users: " + formData.getNumUsers() + "\n"
     "existing_licenses: " + formData.getNumUsers() + "\n"
   - Only append each optional field if it is NOT empty:
     "pain_point: " + formData.getPainPoint() + "\n"
     "workload_type: " + formData.getWorkloadType() + "\n"
     "applications: " + formData.getApplications() + "\n"
     "ad_setup_type: " + formData.getAdSetupType() + "\n"
     "compliance_req: " + formData.getComplianceReq() + "\n"
3. Return Map with keys "systemPrompt" and "userMessage"

### Method 2: generateContent(Map<String, String> promptDict) → LlmContent

Purpose: Calls Gemini API and parses the JSON response

Steps:
1. Load GEMINI_API_KEY from application.properties using @Value annotation
2. Load gemini.model from application.properties using @Value annotation
3. Build the Gemini API request body as JSON string:
   Use the Gemini REST API endpoint:
   https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}

   Request body structure:
   ```json
   {
     "system_instruction": {
       "parts": [{"text": "{systemPrompt}"}]
     },
     "contents": [
       {
         "role": "user",
         "parts": [{"text": "{userMessage}"}]
       }
     ],
     "generationConfig": {
       "temperature": 0.3,
       "responseMimeType": "application/json"
     }
   }
   ```

4. Use OkHttp to make the POST request
5. Parse the response:
   - Extract the text from response: candidates[0].content.parts[0].text
   - Parse that text as JSON using Gson
   - Map to LlmContent object with fields:
     problem_statement → problemStatement
     current_infra → currentInfra (List<String>)
     existing_architecture → existingArchitecture
     success_criteria → successCriteria (List<String>)

6. Retry logic:
   - If parsing fails on attempt 1: clean the response text by removing
     markdown code fences (```json and ```) then try parsing again
   - If still fails: make a second API call with this added to userMessage:
     "\nIMPORTANT: Return ONLY a valid JSON object. No markdown. No extra text."
   - Maximum 2 API call attempts total
   - If both fail: throw RuntimeException with clear message

7. Validate that all 4 keys are present and non-null in parsed result
8. Return the LlmContent object

---

## SERVICE — DocumentService.java

Package: com.ttns.tsdgenerator.service
Annotation: @Service

This service has FOUR methods.

### Method 1: generateDocument(FormData formData, LlmContent llmContent) → byte[]

Purpose: Orchestrates document creation and returns the file as bytes

Steps:
1. Call createTempCopy() to get tempFilePath
2. Call fillDocument(tempFilePath, formData, llmContent)
3. Read the filled temp file into a byte array
4. Call cleanup(tempFilePath)
5. Return the byte array

### Method 2: createTempCopy() → String

Purpose: Creates a working copy of the master template

Steps:
1. Read template.path from application.properties using @Value
2. Read temp.path from application.properties using @Value
3. Create the temp directory if it does not exist
4. Generate unique filename: "AVD_TSD_" + timestamp + ".docx"
   Use: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
5. Copy the template file to temp folder using Files.copy()
6. Return the full path of the temp copy as String

### Method 3: fillDocument(String tempFilePath, FormData formData, LlmContent llmContent)

Purpose: The core method — fills ALL placeholders in the document

This is the most important method. It uses Apache POI XWPFDocument.

Open the document:
```java
try (FileInputStream fis = new FileInputStream(tempFilePath);
     XWPFDocument doc = new XWPFDocument(fis)) {
    // all replacements happen here
    // save at the end
}
```

Perform replacements in this EXACT order:

---

#### PART A — Cover Page Text Boxes

The cover page has two text boxes (drawing shapes) that contain
{{customer_name}}. These are NOT in the main body paragraphs.
Access them through the document's body XML element.

Use this approach to handle text boxes:
```java
for (XWPFParagraph para : doc.getParagraphs()) {
    replaceInParagraphRuns(para, "{{customer_name}}", formData.getCustomerName());
}
// Also iterate through all headers if needed
```

NOTE: Apache POI does not natively expose text box content through
standard paragraph iteration. Use the CTBody XML approach:
```java
CTBody body = doc.getDocument().getBody();
String bodyXml = body.xmlText();
// Replace in XML then set back — only for text boxes
// For all other replacements use the POI API directly
```

Actually for text boxes specifically, use this pattern:
```java
XmlObject[] textBoxes = body.selectPath(
    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
    "declare namespace wps='http://schemas.microsoft.com/office/word/2010/wordprocessingShape' " +
    ".//wps:txbx//w:t"
);
for (XmlObject textObj : textBoxes) {
    if (textObj.xmlText().contains("{{customer_name}}")) {
        // replace the text content
        String newXml = textObj.xmlText().replace("{{customer_name}}", 
            escapeXml(formData.getCustomerName()));
        textObj.set(XmlObject.Factory.parse(newXml));
    }
}
```

---

#### PART B — Table 0 Replacements (Proposal + Contacts + Account Details)

Table 0 is the first table in the document (index 0).
Get it with: XWPFTable table0 = doc.getTables().get(0);

Use a helper method replaceInCell(table, rowIndex, colIndex, placeholder, value)
that gets the cell, iterates its paragraphs and runs, and replaces the text.

Make these exact replacements with hardcoded row and column indices:

PROPOSAL DETAILS section:
- Row 2, Col 1  → {{customer_name}}  → formData.getCustomerName()
- Row 3, Col 1  → {{date}}           → formData.getDate()
- Row 4, Col 1  → {{quote_id}}       → formData.getQuoteId()

TTNS CONTACTS section:
- Row 8,  Col 0 → {{am_name}}        → formData.getAmName()
- Row 8,  Col 2 → {{am_email}}       → formData.getAmEmail()
- Row 9,  Col 0 → {{pss_name}}       → formData.getPssName()
- Row 9,  Col 2 → {{pss_email}}      → formData.getPssEmail()
- Row 11, Col 0 → {{sa_name}}        → formData.getSaName()
- Row 11, Col 2 → {{sa_email}}       → formData.getSaEmail()

CUSTOMER CONTACT section:
- Row 17, Col 0 → {{spoc_name}}      → formData.getSpocName()
- Row 18, Col 1 → {{spoc_role}}      → formData.getSpocRole()
- Row 19, Col 2 → {{spoc_email}}     → formData.getSpocEmail()

ACCOUNT DETAILS section:
- Row 21, Col 1 → {{region}}         → formData.getRegion()
- Row 23, Col 1 → {{domain_name}}    → formData.getDomainName()
- Row 26, Col 1 → {{tenant_id}}      → formData.getTenantId()

---

#### PART C — Body Paragraph Replacements

Iterate through all body paragraphs using doc.getParagraphs()
and apply these replacements using replaceInParagraphRuns():

- {{customer_name}}  → formData.getCustomerName()
  (appears in Document Objective line and Signatories section)
- {{num_users}}      → String.valueOf(formData.getNumUsers())
  (appears in Proposed Architecture bullet point)
- {{fslogix_size}}   → String.valueOf(formData.getFslogixSize())
  (appears in Proposed Architecture bullet point)

---

#### PART D — Table 1 Replacements (Project Plan Table)

Table 1 is the second table in the document (index 1).
Get it with: XWPFTable table1 = doc.getTables().get(1);

- Row 3, Col 1 → {{num_users}}    → String.valueOf(formData.getNumUsers())
- Row 5, Col 1 → {{fslogix_size}} → String.valueOf(formData.getFslogixSize())

---

#### PART E — LLM Content Replacements

These require special handling.

For {{problem_statement}} and {{existing_architecture}}:
- Iterate doc.getParagraphs()
- Find the paragraph whose text equals "{{problem_statement}}"
- Clear all existing runs in that paragraph
- Add a single new run with the LLM generated text
- Same approach for {{existing_architecture}}

For {{current_infra}} and {{success_criteria}}:
These need to expand from ONE placeholder paragraph into
MULTIPLE bullet paragraphs — one per item in the list.

Use this approach:
1. Find the paragraph index containing "{{current_infra}}"
   by iterating doc.getParagraphs() with an index counter
2. Remove that paragraph from the document body using
   the underlying CTBody XML manipulation:
   ```java
   CTBody body = doc.getDocument().getBody();
   // find and remove the placeholder paragraph CTp
   // insert new CTp elements for each bullet item
   ```
3. For each item in llmContent.getCurrentInfra():
   Create a new XWPFParagraph with bullet list style
   Use the same paragraph style as other bullet paragraphs
   in the document: pStyle "ListParagraph" with numPr
4. Insert all new paragraphs at the same position
5. Repeat same process for {{success_criteria}}

For bullet paragraph creation use:
```java
XWPFParagraph newPara = doc.createParagraph();
newPara.setStyle("ListParagraph");
XWPFRun run = newPara.createRun();
run.setText(bulletItem);
```
Then move the paragraph to the correct position in the document body.

---

#### SAVE THE DOCUMENT

After all replacements:
```java
try (FileOutputStream fos = new FileOutputStream(tempFilePath)) {
    doc.write(fos);
}
```

---

### Method 4: cleanup(String tempFilePath)

Purpose: Deletes the temp file after it has been read into bytes

Steps:
1. Delete the file at tempFilePath using Files.deleteIfExists()
2. Wrap in try/catch — silently ignore if file is already gone

---

## HELPER METHODS IN DocumentService

Create these private helper methods:

### replaceInParagraphRuns(XWPFParagraph para, String placeholder, String value)

Purpose: Handles run-level replacement within a single paragraph.
Apache POI splits paragraph text across multiple runs.
This method merges the runs first then replaces.

Steps:
1. Get all runs in the paragraph
2. Concatenate all run texts to get the full paragraph text
3. Check if the full text contains the placeholder
4. If yes:
   - Find which run(s) contain parts of the placeholder
   - Set the first run that contains any part of the placeholder
     to the full replaced text
   - Set all subsequent runs that were part of the split placeholder
     to empty string ""
5. If no: do nothing

This solves the split-run problem by working with the
concatenated text rather than individual runs.

### replaceInCell(XWPFTable table, int rowIndex, int colIndex,
                  String placeholder, String value)

Purpose: Replaces a placeholder in a specific table cell

Steps:
1. Get row: table.getRow(rowIndex)
2. Get cell: row.getCell(colIndex)
3. If cell is null: return without error (handles optional fields)
4. For each paragraph in cell.getParagraphs():
   Call replaceInParagraphRuns(paragraph, placeholder, value)

### escapeXml(String value) → String

Purpose: Escapes special XML characters in replacement values

Replace:
- & with &amp;
- < with &lt;
- > with &gt;

---

## FRONTEND — frontend/index.html

Keep the exact same dark theme professional form from before.
Use Google Fonts: Syne for headings, DM Sans for body.

### Form Fields (exactly as before)

SECTION 1 — Proposal Details:
- customer_name : text, REQUIRED
- date : date picker, REQUIRED, default today
- quote_id : text, REQUIRED

SECTION 2 — TTNS Contact Details:
Sub Account Manager:
- am_name : text, REQUIRED
- am_email : email, REQUIRED
Sub TSS/PSS:
- pss_name : text, OPTIONAL
- pss_email : email, OPTIONAL
Sub Solution Architect:
- sa_name : text, REQUIRED
- sa_email : email, REQUIRED

SECTION 3 — Customer Contact Details:
- spoc_name : text, REQUIRED
- spoc_role : text, REQUIRED
- spoc_email : email, REQUIRED

SECTION 4 — Mandatory Account Details:
- region : dropdown, REQUIRED, options: North, South, West, Mumbai, DGIS
- tenant_id : text, OPTIONAL
- domain_name : text, OPTIONAL

SECTION 5 — AVD Requirements:
Required:
- num_users : number, REQUIRED, min 1
- fslogix_size : number, REQUIRED, min 1, label "FsLogix Storage Size (GB)"

Optional LLM context (clearly marked Optional in UI):
- pain_point : textarea, OPTIONAL
- workload_type : dropdown, OPTIONAL, options: Heavy, Standard, Light
- applications : text, OPTIONAL
- ad_setup_type : dropdown, OPTIONAL,
  options: Entra ID Only, On-premises AD, Hybrid AD
- compliance_req : textarea, OPTIONAL

### Form Behaviour

- Progress bar tracking completed sections (0/5 to 5/5)
- Validate required fields before submission
- Highlight invalid fields in red on failed submission
- Button shows loading spinner during generation
- On success: trigger .docx file download automatically
- On error: show error message below the button
- Optional fields clearly marked with "Optional" label

### JavaScript fetch() call

```javascript
const response = await fetch('http://localhost:8080/api/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        customer_name: document.getElementById('customerName').value,
        date: formatDate(document.getElementById('date').value),
        quote_id: document.getElementById('quoteId').value,
        am_name: document.getElementById('amName').value,
        am_email: document.getElementById('amEmail').value,
        pss_name: document.getElementById('pssName').value,
        pss_email: document.getElementById('pssEmail').value,
        sa_name: document.getElementById('saName').value,
        sa_email: document.getElementById('saEmail').value,
        spoc_name: document.getElementById('spocName').value,
        spoc_role: document.getElementById('spocRole').value,
        spoc_email: document.getElementById('spocEmail').value,
        region: document.getElementById('region').value,
        tenant_id: document.getElementById('tenantId').value,
        domain_name: document.getElementById('domainName').value,
        num_users: parseInt(document.getElementById('numUsers').value),
        fslogix_size: parseInt(document.getElementById('fslogixSize').value),
        pain_point: document.getElementById('painPoint').value,
        workload_type: document.getElementById('workloadType').value,
        applications: document.getElementById('applications').value,
        ad_setup_type: document.getElementById('adSetupType').value,
        compliance_req: document.getElementById('complianceReq').value
    })
});

if (response.ok) {
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'AVD_TSD_' + document.getElementById('customerName').value + '.docx';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}
```

Date must be formatted as "DD Month YYYY" before sending.
Example: 2026-03-26 → "26 March 2026"

---

## .gitignore

```
*.class
target/
.env
temp/
.DS_Store
*.iml
.idea/
```

---

## README.md

Write a clear README with:

1. Project name and description
2. Prerequisites:
   - Java 17 JDK (adoptium.net)
   - Maven (bundled with VS Code Java Extension Pack)
3. Setup steps:
   - Set environment variable GEMINI_API_KEY
   - Place AVD_TSD-V1_0.docx in /templates folder
   - Place avd_system_prompt.txt in /prompts folder
4. How to run: mvn spring-boot:run
5. How to open the form: open frontend/index.html in browser
6. API endpoint: POST http://localhost:8080/api/generate

---

## ENVIRONMENT VARIABLE

The Gemini API key is loaded from the system environment variable GEMINI_API_KEY.
Do NOT create a .env file for Java — Spring Boot reads it directly from the
system environment or can be passed at run time:

```
GEMINI_API_KEY=your_key_here mvn spring-boot:run
```

Or set it permanently in system environment variables on Windows:
System Properties → Environment Variables → New → GEMINI_API_KEY

In application.properties reference it as:
```
gemini.api.key=${GEMINI_API_KEY}
```

---

## CORS CONFIGURATION

In TsdController.java the @CrossOrigin(origins = "*") annotation
on the class handles CORS for all endpoints.

Alternatively add a global CORS config bean in TsdGeneratorApplication.java:

```java
@Bean
public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**")
                    .allowedOrigins("*")
                    .allowedMethods("POST", "GET", "OPTIONS")
                    .allowedHeaders("*");
        }
    };
}
```

---

## IMPORTANT NOTES FOR IMPLEMENTATION

1. The replaceInParagraphRuns() helper is the key to solving the
   split-run problem. Every paragraph replacement must go through
   this method — never replace text directly on a single run.

2. For table cell replacements, always check if the cell exists
   before accessing it. Some rows may have merged cells which
   can cause index out of bounds errors.

3. For LLM bullet expansion (current_infra and success_criteria),
   the cleanest approach in Apache POI is to use the XmlCursor:
   ```java
   XmlCursor cursor = placeholderParagraph.getCTP().newCursor();
   // use cursor to insert new paragraphs at exact position
   ```
   Use XmlCursor.insertElement() or cursor.toEndToken() and
   cursor.insertNewElement() to add paragraphs at the right location.

4. When saving the document back, always use FileOutputStream
   not the same FileInputStream — open a new stream for writing.

5. The temp folder path must be relative to where the application
   is run from (project root) not relative to the src folder.

6. For the Gemini API call, the responseMimeType: "application/json"
   in generationConfig forces Gemini to return pure JSON without
   markdown fences — this eliminates the need for most cleanup logic.

---

## AFTER WRITING ALL CODE

Once every file is created and complete, do the following
in this exact order:

1. Tell me the EXACT command to set my GEMINI_API_KEY
   environment variable on Windows and then run the application

2. Tell me exactly where to place these two files:
   - AVD_TSD-V1_0.docx → exact folder path
   - avd_system_prompt.txt → exact folder path

3. Tell me the exact command to build and run the application

4. Tell me how to open the form in the browser

5. Tell me if Java 17 JDK or Maven need to be installed separately
   and where to download them from if they are not already installed
