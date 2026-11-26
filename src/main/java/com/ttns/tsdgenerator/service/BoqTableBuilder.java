package com.ttns.tsdgenerator.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the first sheet of an Excel file (e.g. an Azure Pricing Calculator export) into a Word table (OOXML),
 * copying it as is: every row and column that has content, cell text as Excel displays it, bold text,
 * solid fill colours and horizontal merges. The table is scaled to the given width.
 */
public final class BoqTableBuilder {

    private BoqTableBuilder() {}

    /** Font size in half-points (16 = 8 pt), small enough for wide calculator exports. */
    private static final int FONT_HALF_POINTS = 16;

    public static String toTableXml(InputStream xlsx, int tableWidthTwips) throws IOException {
        try (Workbook wb = new XSSFWorkbook(xlsx)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            // ---- used range: last row and column that actually hold text (exports claim e.g. A1:Z1000)
            int lastRow = -1, lastCol = -1;
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (!formatter.formatCellValue(cell, evaluator).isBlank()) {
                        lastRow = Math.max(lastRow, row.getRowNum());
                        lastCol = Math.max(lastCol, cell.getColumnIndex());
                    }
                }
            }
            if (lastRow < 0) throw new IllegalArgumentException("The BOQ file is empty.");

            // ---- merged regions: top-left cell -> column span; other covered cells are skipped
            Map<String, Integer> spanAt = new HashMap<>();
            Set<String> covered = new HashSet<>();
            for (CellRangeAddress r : sheet.getMergedRegions()) {
                if (r.getFirstRow() > lastRow || r.getFirstColumn() > lastCol) continue;
                int span = Math.min(r.getLastColumn(), lastCol) - r.getFirstColumn() + 1;
                spanAt.put(r.getFirstRow() + ":" + r.getFirstColumn(), span);
                for (int rr = r.getFirstRow(); rr <= Math.min(r.getLastRow(), lastRow); rr++) {
                    for (int cc = r.getFirstColumn(); cc <= Math.min(r.getLastColumn(), lastCol); cc++) {
                        if (rr != r.getFirstRow() || cc != r.getFirstColumn()) covered.add(rr + ":" + cc);
                    }
                }
            }

            // ---- column widths proportional to the Excel widths
            int cols = lastCol + 1;
            int[] grid = new int[cols];
            long totalExcel = 0;
            for (int c = 0; c < cols; c++) totalExcel += Math.max(sheet.getColumnWidth(c), 256);
            int used = 0;
            for (int c = 0; c < cols; c++) {
                grid[c] = (int) (tableWidthTwips * (long) Math.max(sheet.getColumnWidth(c), 256) / totalExcel);
                used += grid[c];
            }
            grid[cols - 1] += tableWidthTwips - used;

            StringBuilder x = new StringBuilder();
            x.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>")
             .append("<w:tblW w:w=\"").append(tableWidthTwips).append("\" w:type=\"dxa\"/>")
             .append("<w:tblBorders>");
            for (String side : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
                x.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>");
            }
            x.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/>")
             .append("<w:tblCellMar><w:left w:w=\"57\" w:type=\"dxa\"/><w:right w:w=\"57\" w:type=\"dxa\"/></w:tblCellMar>")
             .append("</w:tblPr><w:tblGrid>");
            for (int w : grid) x.append("<w:gridCol w:w=\"").append(w).append("\"/>");
            x.append("</w:tblGrid>");

            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                x.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>");
                for (int c = 0; c < cols; c++) {
                    String key = r + ":" + c;
                    if (covered.contains(key)) continue;
                    int span = spanAt.getOrDefault(key, 1);
                    int width = 0;
                    for (int k = c; k < c + span; k++) width += grid[k];

                    Cell cell = row == null ? null : row.getCell(c);
                    String text = cell == null ? "" : cleanCurrency(formatter.formatCellValue(cell, evaluator));
                    boolean bold = false, right = false;
                    String fill = null;
                    if (cell != null) {
                        CellStyle style = cell.getCellStyle();
                        bold = wb.getFontAt(style.getFontIndex()).getBold();
                        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
                        right = type == CellType.NUMERIC;
                        fill = solidFill(style);
                    }

                    x.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>");
                    if (span > 1) x.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
                    if (fill != null) x.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
                    x.append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"20\" w:line=\"240\" w:lineRule=\"auto\"/>");
                    // Explicit alignment: the document's Normal style is justified, which spreads words apart
                    x.append("<w:jc w:val=\"").append(right ? "right" : "left").append("\"/>");
                    x.append("</w:pPr>");
                    if (!text.isEmpty()) {
                        x.append("<w:r><w:rPr>");
                        if (bold) x.append("<w:b/><w:bCs/>");
                        x.append("<w:sz w:val=\"").append(FONT_HALF_POINTS).append("\"/><w:szCs w:val=\"")
                         .append(FONT_HALF_POINTS).append("\"/></w:rPr>");
                        String[] lines = text.split("\\r?\\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (i > 0) x.append("<w:br/>");
                            x.append("<w:t xml:space=\"preserve\">").append(escapeXml(lines[i])).append("</w:t>");
                        }
                        x.append("</w:r>");
                    }
                    x.append("</w:p></w:tc>");
                    c += span - 1;
                }
                x.append("</w:tr>");
            }
            x.append("</w:tbl>");
            return x.toString();
        }
    }

    /**
     * POI leaves Excel currency tokens in formatted numbers: "[$$]137.24" or "[$₹-4009]1,234.00".
     * Keep only the symbol, as Excel shows it.
     */
    static String cleanCurrency(String text) {
        return text.replaceAll("\\[\\$([^\\]\\-]*)(?:-[0-9A-Fa-f]+)?\\]", "$1");
    }

    /** RRGGBB of a solid cell fill, or null when the cell has no fill (or a white one). */
    private static String solidFill(CellStyle style) {
        if (style.getFillPattern() != FillPatternType.SOLID_FOREGROUND) return null;
        if (!(style instanceof XSSFCellStyle xs)) return null;
        XSSFColor color = xs.getFillForegroundXSSFColor();
        if (color == null) return null;
        String argb = color.getARGBHex();
        if (argb == null || argb.length() < 6) return null;
        String rgb = argb.substring(argb.length() - 6).toUpperCase(Locale.ROOT);
        return "FFFFFF".equals(rgb) ? null : rgb;
    }

    static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
