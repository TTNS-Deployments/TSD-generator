package com.ttns.tsdgenerator.service;

import com.ttns.tsdgenerator.model.D2Request;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Builds D2 TSDs. No AI: the form values are written into the template's {{placeholders}},
 * a few commercial values are calculated, and the BOQ (Azure Pricing Calculator export) is pasted as a table.
 */
@Service
public class D2DocumentService {

    /** Template file per D2 type, inside d2.template.dir */
    private static final Map<String, String> TEMPLATES = Map.of(
            "azure-subscription", "azure-subscription.docx",
            "self-deployment-ri", "self-deployment-ri.docx",
            "csp-to-csp", "csp-to-csp.docx"
    );

    /** Fields every D2 document needs */
    private static final List<String> COMMON_REQUIRED = List.of("customer_name", "end_customer", "date", "quote_id",
            "am_name", "am_email", "sa_name", "sa_email",
            "spoc_name", "spoc_role", "spoc_email", "region");

    /** Fields the user must fill in, per D2 type */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "azure-subscription", concat(COMMON_REQUIRED, "monthly_usage", "discount_pct"),
            "self-deployment-ri", concat(COMMON_REQUIRED, "monthly_usage"),   // reserved instances: no discount
            // Prerequisites (pre_1..pre_11) are optional; usage amounts are checked in addCspToCspCalculations
            "csp-to-csp", concat(COMMON_REQUIRED, "currency", "m1", "m2", "m3")
    );

    /** D2 types whose document includes the BOQ (Azure Pricing Calculator export) */
    private static final Set<String> NEEDS_BOQ = Set.of("azure-subscription", "self-deployment-ri");

    /** Prerequisite answers in the CSP to CSP table (rows 1-11) */
    private static final int CSP_PREREQUISITES = 11;

    private static List<String> concat(List<String> base, String... more) {
        List<String> all = new ArrayList<>(base);
        all.addAll(List.of(more));
        return List.copyOf(all);
    }

    private static final String BOQ_MARKER = "{{boq_table}}";
    private static final String RUPEE = "₹ ";

    @Value("${d2.template.dir}")
    private String templateDir;

    public static boolean isKnownType(String type) {
        return type != null && TEMPLATES.containsKey(type);
    }

    public String fileName(D2Request req) {
        String customer = req.getFields().getOrDefault("customer_name", "Customer").trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return switch (req.getD2Type()) {
            case "azure-subscription" -> "TTNS_Azure_Subscription_TSD_Disti2_" + customer + ".docx";
            case "self-deployment-ri" -> "TTNS_SelfDeployment_RI_TSD_Disti2_" + customer + ".docx";
            case "csp-to-csp" -> "TTNS_CSP_to_CSP_TSD_Disti2_" + customer + ".docx";
            default -> "TTNS_D2_TSD_" + customer + ".docx";
        };
    }

    public byte[] generate(D2Request req, MultipartFile boq) throws IOException {
        String type = req.getD2Type();
        if (!isKnownType(type)) throw new IllegalArgumentException("Unknown D2 type: " + type);

        Map<String, String> in = new HashMap<>();
        req.getFields().forEach((k, v) -> in.put(k, v == null ? "" : v.trim()));
        List<String> missing = REQUIRED.get(type).stream().filter(k -> in.getOrDefault(k, "").isEmpty()).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing required fields: " + String.join(", ", missing));
        boolean needsBoq = NEEDS_BOQ.contains(type);
        if (needsBoq && (boq == null || boq.isEmpty()))
            throw new IllegalArgumentException("Upload the BOQ (Azure Pricing Calculator export, .xlsx).");

        Map<String, String> values = new LinkedHashMap<>(in);
        values.put("date", coverDate(in.get("date")));
        switch (type) {
            case "azure-subscription" -> addAzureSubscriptionCommercials(in, values);
            case "self-deployment-ri" -> addReservedInstanceCommercials(in, values);
            case "csp-to-csp" -> addCspToCspCalculations(in, values);
            default -> { }
        }

        Path template = Paths.get(templateDir, TEMPLATES.get(type));
        try (InputStream tis = Files.newInputStream(template);
             XWPFDocument doc = new XWPFDocument(tis)) {

            // 0. CSP to CSP without a discount: drop commercials row B (before its placeholder is filled in)
            boolean dropDiscountRow = "csp-to-csp".equals(type)
                    && optionalNumber(in.get("discount_pct"), "Discount %").signum() == 0;
            if (dropDiscountRow) removeRowContaining(doc, "{{discount_pct_text}}");

            // 1. Paragraphs and table cells (handles placeholders even if Word splits them across runs)
            for (XWPFParagraph p : doc.getParagraphs()) replaceAll(p, values);
            for (XWPFTable t : doc.getTables())
                for (XWPFTableRow r : t.getRows())
                    for (XWPFTableCell c : r.getTableCells())
                        for (XWPFParagraph p : c.getParagraphs()) replaceAll(p, values);

            // 2. Raw body XML: text boxes (cover page, stored twice by Word) and the BOQ table
            String boqXml = null;
            if (needsBoq) {
                try (InputStream bis = boq.getInputStream()) {
                    boqXml = BoqTableBuilder.toTableXml(bis, usableWidthTwips(doc));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Could not read the BOQ file. Upload the .xlsx exported from the Azure Pricing Calculator. (" + e.getMessage() + ")");
                }
            }

            CTBody body = doc.getDocument().getBody();
            String xml = body.xmlText();

            if (dropDiscountRow) xml = xml.replace("=A-B+C", "=A+C");

            for (Map.Entry<String, String> e : values.entrySet()) {
                xml = xml.replace("{{" + e.getKey() + "}}", BoqTableBuilder.escapeXml(e.getValue()));
            }
            if (boqXml != null) xml = replaceParagraph(xml, BOQ_MARKER, boqXml);
            try {
                body.set(CTBody.Factory.parse(xml));
            } catch (Exception e) {
                throw new IOException("Could not assemble the document: " + e.getMessage(), e);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------------------------------
    private void addAzureSubscriptionCommercials(Map<String, String> in, Map<String, String> values) {
        BigDecimal usage = number(in.get("monthly_usage"), "Expected monthly usage");
        BigDecimal pct = number(in.get("discount_pct"), "Discount %");
        if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Discount % must be between 0 and 100.");
        BigDecimal discount = usage.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal post = usage.subtract(discount);

        values.put("monthly_usage", RUPEE + indian(usage));
        values.put("discount_pct", plain(pct));
        values.put("discount_amount", RUPEE + indian(discount));
        values.put("post_discount_usage", RUPEE + indian(post));
        values.put("total_monthly_billing", RUPEE + indian(post));
    }

    /** Reserved instances get no discount: total monthly billing equals the expected usage. */
    private void addReservedInstanceCommercials(Map<String, String> in, Map<String, String> values) {
        BigDecimal usage = number(in.get("monthly_usage"), "Expected monthly usage");
        values.put("monthly_usage", RUPEE + indian(usage));
        values.put("total_monthly_billing", RUPEE + indian(usage));
    }

    /**
     * CSP to CSP: last 3 months of invoices, split into RI (reserved, never discounted) and
     * Azure (non-reserved, PayG; the only part the discount applies to).
     * Calculations table: average of the 3 months -> INR (x exchange rate when in USD) -> discount -> total.
     * Commercials: A = Azure INR, B = discount on A, C = RI INR, Total = A - B + C.
     */
    private void addCspToCspCalculations(Map<String, String> in, Map<String, String> values) {
        boolean usd = "USD".equalsIgnoreCase(in.get("currency"));
        if (!usd && !"INR".equalsIgnoreCase(in.get("currency")))
            throw new IllegalArgumentException("Currency must be USD or INR.");
        BigDecimal rate = BigDecimal.ONE;
        if (usd) {
            if (in.getOrDefault("fx_rate", "").isEmpty())
                throw new IllegalArgumentException("Enter the USD to INR exchange rate.");
            rate = number(in.get("fx_rate"), "Exchange rate");
            if (rate.signum() <= 0) throw new IllegalArgumentException("Exchange rate must be more than 0.");
        }

        BigDecimal[] ri = new BigDecimal[3], az = new BigDecimal[3];
        for (int i = 0; i < 3; i++) {
            ri[i] = optionalNumber(in.get("ri_m" + (i + 1)), "RI amount");
            az[i] = optionalNumber(in.get("az_m" + (i + 1)), "Azure amount");
            values.put("m" + (i + 1), monthLabel(in.get("m" + (i + 1))));
        }
        BigDecimal riSum = ri[0].add(ri[1]).add(ri[2]);
        BigDecimal azSum = az[0].add(az[1]).add(az[2]);
        if (riSum.signum() == 0 && azSum.signum() == 0)
            throw new IllegalArgumentException("Enter the monthly usage for RI and/or Azure.");

        BigDecimal pct = optionalNumber(in.get("discount_pct"), "Discount %");
        if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Discount % must be between 0 and 100.");
        if (pct.signum() > 0 && azSum.signum() == 0)
            throw new IllegalArgumentException("The discount applies only to non-reserved (Azure) resources. Enter Azure usage or clear the discount.");

        BigDecimal three = new BigDecimal("3");
        BigDecimal riAvg = riSum.divide(three, 2, RoundingMode.HALF_UP);
        BigDecimal azAvg = azSum.divide(three, 2, RoundingMode.HALF_UP);
        BigDecimal riInr = riAvg.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        BigDecimal azInr = azAvg.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        BigDecimal azDisc = azInr.multiply(pct).divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
        BigDecimal azTotal = azInr.subtract(azDisc);
        BigDecimal total = azTotal.add(riInr);

        values.put("currency_symbol", usd ? "$" : "₹");
        values.put("fx_rate", usd ? plain(rate) : "");
        for (int i = 0; i < 3; i++) {
            values.put("ri_m" + (i + 1), indian(ri[i]));
            values.put("az_m" + (i + 1), indian(az[i]));
            values.put("tot_m" + (i + 1), indian(ri[i].add(az[i])));
        }
        values.put("ri_avg", indian(riAvg));
        values.put("az_avg", indian(azAvg));
        values.put("tot_avg", indian(riAvg.add(azAvg)));
        values.put("ri_inr", indian(riInr));
        values.put("az_inr", indian(azInr));
        values.put("tot_inr", indian(riInr.add(azInr)));
        values.put("ri_disc_pct", "0%");
        values.put("ri_disc", "0");
        values.put("ri_total", indian(riInr));
        values.put("az_disc_pct", percent(pct));
        values.put("az_disc", indian(azDisc));
        values.put("az_total", indian(azTotal));
        values.put("tot_total", indian(total));

        // Commercials
        values.put("payg_inr", indian(azInr));
        values.put("discount_pct_text", percent(pct));
        values.put("discount_inr", indian(azDisc));
        values.put("total_inr", indian(total));

        for (int i = 1; i <= CSP_PREREQUISITES; i++) values.putIfAbsent("pre_" + i, "");
    }

    /** "2025-05" -> "May-25" */
    private static String monthLabel(String yearMonth) {
        try {
            return java.time.YearMonth.parse(yearMonth).format(DateTimeFormatter.ofPattern("MMM-yy", Locale.US));
        } catch (Exception e) {
            throw new IllegalArgumentException("Select the month and year for each of the 3 months.");
        }
    }

    /** 16 -> "16%", 14.5 -> "14.50%" (as in the sample) */
    private static String percent(BigDecimal p) {
        return p.stripTrailingZeros().scale() <= 0
                ? p.setScale(0, RoundingMode.HALF_UP).toPlainString() + "%"
                : p.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static BigDecimal optionalNumber(String s, String label) {
        return s == null || s.isBlank() ? BigDecimal.ZERO : number(s, label);
    }

    private static BigDecimal number(String s, String label) {
        try {
            return new BigDecimal(s.replace(",", "").replace("₹", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    /** "2025-09-09" -> "Sep 09, 2025" (cover page format) */
    private static String coverDate(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US));
        } catch (DateTimeParseException e) {
            return iso; // already formatted text
        }
    }

    /** Indian digit grouping: 1234567.5 -> "12,34,567.50"; whole numbers without decimals. */
    static String indian(BigDecimal v) {
        v = v.setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        v = v.abs();
        String whole = v.toBigInteger().toString();
        String frac = v.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0 ? "" : "." + v.toPlainString().split("\\.")[1];
        StringBuilder g = new StringBuilder();
        int n = whole.length();
        if (n <= 3) g.append(whole);
        else {
            String head = whole.substring(0, n - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) g.append(',');
                g.append(head.charAt(i));
            }
            g.append(',').append(whole.substring(n - 3));
        }
        return (negative ? "-" : "") + g + frac;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static int usableWidthTwips(XWPFDocument doc) {
        try {
            var sect = doc.getDocument().getBody().getSectPr();
            long w = ((java.math.BigInteger) sect.getPgSz().getW()).longValue();
            long l = ((java.math.BigInteger) sect.getPgMar().getLeft()).longValue();
            long r = ((java.math.BigInteger) sect.getPgMar().getRight()).longValue();
            return (int) (w - l - r);
        } catch (Exception e) {
            return 10260; // A4 with the sample's margins
        }
    }

    /** Placeholder replacement inside one paragraph, joining runs first (Word may split a placeholder). */
    private static void replaceAll(XWPFParagraph para, Map<String, String> values) {
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : runs) {
            String t = run.getText(0);
            sb.append(t != null ? t : "");
        }
        String full = sb.toString();
        if (!full.contains("{{")) return;
        String replaced = full;
        for (Map.Entry<String, String> e : values.entrySet()) replaced = replaced.replace("{{" + e.getKey() + "}}", e.getValue());
        if (replaced.equals(full)) return;
        runs.get(0).setText(replaced, 0);
        for (int i = 1; i < runs.size(); i++) runs.get(i).setText("", 0);
    }

    /** Removes the first table row whose text contains key. */
    private static void removeRowContaining(XWPFDocument doc, String key) {
        for (XWPFTable t : doc.getTables()) {
            List<XWPFTableRow> rows = t.getRows();
            for (int i = 0; i < rows.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (XWPFTableCell c : rows.get(i).getTableCells()) sb.append(c.getText());
                if (sb.toString().contains(key)) {
                    t.removeRow(i);
                    return;
                }
            }
        }
    }

    /** Replaces the whole paragraph containing key with the given XML. */
    private static String replaceParagraph(String xml, String key, String replacement) {
        int keyPos = xml.indexOf(key);
        if (keyPos == -1) return xml;
        int paraStart = -1;
        for (int from = keyPos - 1; from >= 0; ) {
            int cand = xml.lastIndexOf("<w:p", from);
            if (cand == -1) break;
            char next = xml.charAt(cand + 4);
            if (next == '>' || next == ' ') { paraStart = cand; break; }
            from = cand - 1;
        }
        int paraEnd = xml.indexOf("</w:p>", keyPos);
        if (paraStart == -1 || paraEnd == -1) return xml;
        return xml.substring(0, paraStart) + replacement + xml.substring(paraEnd + "</w:p>".length());
    }
}
