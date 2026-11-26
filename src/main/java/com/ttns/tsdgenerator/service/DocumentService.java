package com.ttns.tsdgenerator.service;

import com.ttns.tsdgenerator.model.FormData;
import com.ttns.tsdgenerator.model.LlmContent;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DocumentService {

    @Value("${template.path}")
    private String templatePath;

    @Value("${temp.path}")
    private String tempPath;

    public byte[] generateDocument(FormData formData, LlmContent llmContent) throws IOException {
        String tempFilePath = createTempCopy();
        fillDocument(tempFilePath, formData, llmContent);
        byte[] docBytes = Files.readAllBytes(Paths.get(tempFilePath));
        cleanup(tempFilePath);
        return docBytes;
    }

    public String createTempCopy() throws IOException {
        java.nio.file.Path tempDir = Paths.get(tempPath);
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String tempFileName = "AVD_TSD_" + timestamp + ".docx";
        java.nio.file.Path tempFile = tempDir.resolve(tempFileName);
        Files.copy(Paths.get(templatePath), tempFile, StandardCopyOption.REPLACE_EXISTING);
        return tempFile.toString();
    }

    public void fillDocument(String tempFilePath, FormData formData, LlmContent llmContent) throws IOException {
        try (FileInputStream fis = new FileInputStream(tempFilePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            String numUsers    = String.valueOf(formData.getNumUsers());
            String fslogixSize = String.valueOf(formData.getFslogixSize());

            // PART A — All body paragraphs
            for (XWPFParagraph para : doc.getParagraphs()) {
                replaceInParagraphRuns(para, "CUSTOMERNAME", formData.getCustomerName());
                replaceInParagraphRuns(para, "{{customer_name}}", formData.getCustomerName()); // fallback
                replaceInParagraphRuns(para, "{{date}}",          formData.getDate());
                replaceInParagraphRuns(para, "{{quote_id}}",      formData.getQuoteId());
                replaceInParagraphRuns(para, "{{am_name}}",       formData.getAmName());
                replaceInParagraphRuns(para, "{{am_email}}",      formData.getAmEmail());
                replaceInParagraphRuns(para, "{{pss_name}}",      formData.getPssName());
                replaceInParagraphRuns(para, "{{pss_email}}",     formData.getPssEmail());
                replaceInParagraphRuns(para, "{{sa_name}}",       formData.getSaName());
                replaceInParagraphRuns(para, "{{sa_email}}",      formData.getSaEmail());
                replaceInParagraphRuns(para, "{{spoc_name}}",     formData.getSpocName());
                replaceInParagraphRuns(para, "{{spoc_role}}",     formData.getSpocRole());
                replaceInParagraphRuns(para, "{{spoc_email}}",    formData.getSpocEmail());
                replaceInParagraphRuns(para, "{{region}}",        formData.getRegion());
                replaceInParagraphRuns(para, "{{tenant_id}}",     formData.getTenantId());
                replaceInParagraphRuns(para, "{{domain_name}}",   formData.getDomainName());
                replaceInParagraphRuns(para, "{{num_users}}",     numUsers);
                replaceInParagraphRuns(para, "{{fslogix_size}}", fslogixSize);
            }

            // PART B — All table cells (scan every row and column — no hardcoded indices)
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph para : cell.getParagraphs()) {
                            replaceInParagraphRuns(para, "CUSTOMERNAME", formData.getCustomerName());
                            replaceInParagraphRuns(para, "{{customer_name}}", formData.getCustomerName()); // fallback
                            replaceInParagraphRuns(para, "{{date}}",          formData.getDate());
                            replaceInParagraphRuns(para, "{{quote_id}}",      formData.getQuoteId());
                            replaceInParagraphRuns(para, "{{am_name}}",       formData.getAmName());
                            replaceInParagraphRuns(para, "{{am_email}}",      formData.getAmEmail());
                            replaceInParagraphRuns(para, "{{pss_name}}",      formData.getPssName());
                            replaceInParagraphRuns(para, "{{pss_email}}",     formData.getPssEmail());
                            replaceInParagraphRuns(para, "{{sa_name}}",       formData.getSaName());
                            replaceInParagraphRuns(para, "{{sa_email}}",      formData.getSaEmail());
                            replaceInParagraphRuns(para, "{{spoc_name}}",     formData.getSpocName());
                            replaceInParagraphRuns(para, "{{spoc_role}}",     formData.getSpocRole());
                            replaceInParagraphRuns(para, "{{spoc_email}}",    formData.getSpocEmail());
                            replaceInParagraphRuns(para, "{{region}}",        formData.getRegion());
                            replaceInParagraphRuns(para, "{{tenant_id}}",     formData.getTenantId());
                            replaceInParagraphRuns(para, "{{domain_name}}",   formData.getDomainName());
                            replaceInParagraphRuns(para, "{{num_users}}",     numUsers);
                            replaceInParagraphRuns(para, "{{fslogix_size}}", fslogixSize);
                        }
                    }
                }
            }

            // PART C — LLM: problem_statement
            String problemStatement = llmContent.getProblemStatement() != null ? llmContent.getProblemStatement() : "";
            for (XWPFParagraph para : doc.getParagraphs()) {
                if (para.getText().contains("problem_statement")) {
                    para.getRuns().forEach(run -> run.setText("", 0));
                    if (para.getRuns().isEmpty()) {
                        para.createRun().setText(problemStatement);
                    } else {
                        para.getRuns().get(0).setText(problemStatement, 0);
                    }
                    break;
                }
            }

            // PART F — XML-level pass before saving.
            // Catches text boxes and drawing shapes in the body that POI's paragraph API cannot reach.
            // Also handles bullet list expansion without cursor/list sync issues.
            String existingArch = llmContent.getExistingArchitecture() != null ? llmContent.getExistingArchitecture() : "";
            try {
                CTBody bodyObj = doc.getDocument().getBody();
                String bodyXml = bodyObj.xmlText();
                // Simple single-word placeholders are never split by Word's XML serialiser
                bodyXml = bodyXml
                    .replace("CUSTOMERNAME",   escapeXml(formData.getCustomerName()))
                    .replace("{{customer_name}}", escapeXml(formData.getCustomerName())) // fallback for old template
                    .replace("{{date}}",       escapeXml(formData.getDate()))
                    .replace("{{quote_id}}",   escapeXml(formData.getQuoteId()))
                    .replace("{{num_users}}",  escapeXml(numUsers))
                    .replace("{{fslogix_size}}", escapeXml(fslogixSize));
                bodyXml = expandBulletsInXml(bodyXml, "current_infra", llmContent.getCurrentInfra());
                // Section 2.2: EXISTINGARCHTEXT placeholder → existing architecture plain text
                bodyXml = replaceParaWithText(bodyXml, "EXISTINGARCHTEXT", existingArch);
                // Section 4: SUCCESSCRITLIST placeholder → success criteria bullet list
                bodyXml = expandBulletsInXml(bodyXml, "SUCCESSCRITLIST", llmContent.getSuccessCriteria());
                bodyObj.set(CTBody.Factory.parse(bodyXml));
            } catch (Exception e) {
                System.err.println("Body XML pass failed: " + e.getMessage());
            }

            // Header pass — cover page content may live in a first-page header
            for (XWPFHeader header : doc.getHeaderList()) {
                try {
                    java.lang.reflect.Field hdrField =
                        org.apache.poi.xwpf.usermodel.XWPFHeaderFooter.class.getDeclaredField("headerFooter");
                    hdrField.setAccessible(true);
                    CTHdrFtr hdr = (CTHdrFtr) hdrField.get(header);
                    String hdrXml = hdr.xmlText();
                    hdrXml = hdrXml
                        .replace("CUSTOMERNAME",      escapeXml(formData.getCustomerName()))
                        .replace("{{customer_name}}", escapeXml(formData.getCustomerName())) // fallback
                        .replace("{{date}}",          escapeXml(formData.getDate()))
                        .replace("{{quote_id}}",      escapeXml(formData.getQuoteId()))
                        .replace("{{num_users}}",     escapeXml(numUsers))
                        .replace("{{fslogix_size}}", escapeXml(fslogixSize));
                    hdr.set(CTHdrFtr.Factory.parse(hdrXml));
                } catch (Exception e) {
                    System.err.println("Header XML pass failed: " + e.getMessage());
                }
            }

            // Save
            try (FileOutputStream fos = new FileOutputStream(tempFilePath)) {
                doc.write(fos);
            }
        }
    }

    private void replaceInParagraphRuns(XWPFParagraph para, String placeholder, String value) {
        if (value == null) value = "";
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) return;

        StringBuilder fullText = new StringBuilder();
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            fullText.append(text != null ? text : "");
        }

        String fullStr = fullText.toString();
        if (!fullStr.contains(placeholder)) return;

        String replaced = fullStr.replace(placeholder, value);

        // Set first run to full replaced text, clear the rest
        runs.get(0).setText(replaced, 0);
        for (int i = 1; i < runs.size(); i++) {
            runs.get(i).setText("", 0);
        }
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }

    // Replaces the first paragraph containing searchKey with a single plain-text paragraph.
    private String replaceParaWithText(String xml, String searchKey, String text) {
        int keyPos = xml.indexOf(searchKey);
        if (keyPos == -1) return xml;

        int paraStart = -1;
        int searchFrom = keyPos - 1;
        while (searchFrom >= 0) {
            int candidate = xml.lastIndexOf("<w:p", searchFrom);
            if (candidate == -1) break;
            int afterPrefix = candidate + 4;
            if (afterPrefix < xml.length()) {
                char next = xml.charAt(afterPrefix);
                if (next == '>' || next == ' ') { paraStart = candidate; break; }
            }
            searchFrom = candidate - 1;
        }
        if (paraStart == -1) return xml;

        int paraEnd = xml.indexOf("</w:p>", keyPos);
        if (paraEnd == -1) return xml;
        paraEnd += "</w:p>".length();

        String newPara = "<w:p><w:r><w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r></w:p>";
        return xml.substring(0, paraStart) + newPara + xml.substring(paraEnd);
    }

    // Replaces ALL paragraphs in raw XML that contain searchKey with bullet paragraphs.
    private String expandBulletsInXml(String xml, String searchKey, List<String> items) {
        if (items == null || items.isEmpty()) return xml;

        StringBuilder bullets = new StringBuilder();
        for (String item : items) {
            bullets.append("<w:p>")
                   .append("<w:pPr><w:pStyle w:val=\"ListParagraph\"/></w:pPr>")
                   .append("<w:r><w:t xml:space=\"preserve\">")
                   .append(escapeXml(item))
                   .append("</w:t></w:r>")
                   .append("</w:p>");
        }
        String bulletsStr = bullets.toString();

        // Loop to replace every occurrence of searchKey (e.g. Jinja template AND simple placeholder)
        while (true) {
            int keyPos = xml.indexOf(searchKey);
            if (keyPos == -1) break;

            // Scan backward for real paragraph start: <w:p> or <w:p<space>
            // Must NOT match <w:pPr>, <w:pStyle>, etc.
            int paraStart = -1;
            int searchFrom = keyPos - 1;
            while (searchFrom >= 0) {
                int candidate = xml.lastIndexOf("<w:p", searchFrom);
                if (candidate == -1) break;
                int afterPrefix = candidate + 4;
                if (afterPrefix < xml.length()) {
                    char next = xml.charAt(afterPrefix);
                    if (next == '>' || next == ' ') {
                        paraStart = candidate;
                        break;
                    }
                }
                searchFrom = candidate - 1;
            }
            if (paraStart == -1) break;

            int paraEnd = xml.indexOf("</w:p>", keyPos);
            if (paraEnd == -1) break;
            paraEnd += "</w:p>".length();

            xml = xml.substring(0, paraStart) + bulletsStr + xml.substring(paraEnd);
        }
        return xml;
    }

    public void cleanup(String tempFilePath) {
        try {
            Files.deleteIfExists(Paths.get(tempFilePath));
        } catch (IOException e) {
            // silently ignore
        }
    }
}
