package com.tridev.familyhub.feature.insights;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Native, dependency-free PDF and valid OpenXML XLSX report exports. */
public final class FamilyLocationReportExporter {

    private static final int PDF_WIDTH = 595;
    private static final int PDF_HEIGHT = 842;
    private static final float PDF_MARGIN = 42F;

    private FamilyLocationReportExporter() {
    }

    public static void writePdf(
            @NonNull Context context,
            @NonNull Uri destination,
            @NonNull FamilyLocationReport report
    ) throws IOException {
        PdfDocument document = new PdfDocument();
        try (OutputStream output = requireOutput(context, destination)) {
            PdfWriter writer = new PdfWriter(document);
            writer.title("Family Hub — Location Insights & Reports");
            writer.body("Period: " + report.range.displayLabel());
            writer.body("Generated: " + DateFormat.getDateTimeInstance()
                    .format(new Date(report.generatedAt)));
            writer.space();
            writer.heading("Summary");
            writer.body("Total distance: "
                    + FamilyLocationReportAnalyzer.formatDistance(
                    report.totalDistanceMeters));
            writer.body("Moving time: "
                    + FamilyLocationReportAnalyzer.formatDuration(
                    report.totalMovingDurationMs));
            writer.body("Safe Place time: "
                    + FamilyLocationReportAnalyzer.formatDuration(
                    report.totalSafePlaceDurationMs));
            writer.body("Active member-days: " + report.activeMemberDays);

            writer.heading("Member reports");
            for (FamilyLocationReport.MemberReport member : report.members) {
                writer.subheading(member.displayName + " — " + member.role);
                writer.body("Distance: "
                        + FamilyLocationReportAnalyzer.formatDistance(
                        member.totalDistanceMeters)
                        + " | Moving: "
                        + FamilyLocationReportAnalyzer.formatDuration(
                        member.movingDurationMs)
                        + " | Active days: " + member.activeDays
                        + " | Points: " + member.routePointCount);
                writer.body(member.mostVisitedPlace.isEmpty()
                        ? "Most visited Safe Place: Not detected"
                        : "Most visited Safe Place: " + member.mostVisitedPlace);
            }

            writer.heading("Movement duration");
            for (Map.Entry<String, Long> entry
                    : report.movementDurationMs.entrySet()) {
                writer.body(titleCase(entry.getKey()) + ": "
                        + FamilyLocationReportAnalyzer.formatDuration(
                        entry.getValue()));
            }

            writer.heading("Safe Places");
            if (report.familySafePlaces.isEmpty()) {
                writer.body("No Safe Place visits detected.");
            } else {
                for (FamilyLocationReport.PlaceStat place
                        : report.familySafePlaces) {
                    writer.body(place.name + " — " + place.visitCount
                            + " visits — "
                            + FamilyLocationReportAnalyzer.formatDuration(
                            place.durationMs));
                }
            }

            writer.heading("Routine & attention insights");
            boolean hasInsights = false;
            for (FamilyLocationReport.Insight insight : report.familyInsights) {
                hasInsights = true;
                writer.subheading(insight.title);
                writer.body(insight.detail);
            }
            for (FamilyLocationReport.MemberReport member : report.members) {
                for (FamilyLocationReport.Insight insight : member.insights) {
                    hasInsights = true;
                    writer.subheading(member.displayName + ": " + insight.title);
                    writer.body(insight.detail);
                }
            }
            if (!hasInsights) {
                writer.body("No insights are available for this selection.");
            }

            writer.space();
            writer.small("Privacy note: This export contains only Journey History "
                    + "that the signed-in user could access when the report was generated. "
                    + "Store and share the exported file carefully.");
            writer.finish();
            document.writeTo(output);
        } finally {
            document.close();
        }
    }

    public static void writeXlsx(
            @NonNull Context context,
            @NonNull Uri destination,
            @NonNull FamilyLocationReport report
    ) throws IOException {
        try (OutputStream output = requireOutput(context, destination);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRelationships());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            put(zip, "xl/styles.xml", styles());
            put(zip, "xl/worksheets/sheet1.xml", worksheet(report));
            zip.finish();
        }
    }

    @NonNull
    private static OutputStream requireOutput(
            @NonNull Context context,
            @NonNull Uri destination
    ) throws IOException {
        OutputStream output = context.getContentResolver()
                .openOutputStream(destination, "w");
        if (output == null) {
            throw new IOException("Unable to open export destination");
        }
        return output;
    }

    private static void put(
            @NonNull ZipOutputStream zip,
            @NonNull String path,
            @NonNull String content
    ) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @NonNull
    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
    }

    @NonNull
    private static String rootRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    @NonNull
    private static String workbook() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView/></bookViews>"
                + "<sheets><sheet name=\"Location Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    @NonNull
    private static String workbookRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    @NonNull
    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"16\"/><color rgb=\"FF0F6CBD\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF0F6CBD\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    @NonNull
    private static String worksheet(@NonNull FamilyLocationReport report) {
        List<Row> rows = reportRows(report);
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"15\"/>")
                .append("<cols><col min=\"1\" max=\"1\" width=\"28\" customWidth=\"1\"/>")
                .append("<col min=\"2\" max=\"7\" width=\"22\" customWidth=\"1\"/></cols>")
                .append("<sheetData>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            xml.append("<row r=\"").append(rowIndex + 1).append("\">");
            for (int column = 0; column < row.cells.length; column++) {
                Cell cell = row.cells[column];
                String reference = columnName(column + 1) + (rowIndex + 1);
                if (cell.numeric) {
                    xml.append("<c r=\"").append(reference).append("\" s=\"")
                            .append(row.style).append("\"><v>")
                            .append(cell.value).append("</v></c>");
                } else {
                    xml.append("<c r=\"").append(reference)
                            .append("\" t=\"inlineStr\" s=\"").append(row.style)
                            .append("\"><is><t xml:space=\"preserve\">")
                            .append(escapeXml(cell.value))
                            .append("</t></is></c>");
                }
            }
            xml.append("</row>");
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    @NonNull
    private static List<Row> reportRows(@NonNull FamilyLocationReport report) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.title("Family Hub — Location Insights & Reports"));
        rows.add(Row.of("Period", report.range.displayLabel()));
        rows.add(Row.of("Generated", DateFormat.getDateTimeInstance()
                .format(new Date(report.generatedAt))));
        rows.add(Row.blank());
        rows.add(Row.header("Summary", "Value"));
        rows.add(Row.of("Total distance",
                FamilyLocationReportAnalyzer.formatDistance(report.totalDistanceMeters)));
        rows.add(Row.of("Moving time",
                FamilyLocationReportAnalyzer.formatDuration(report.totalMovingDurationMs)));
        rows.add(Row.of("Safe Place time",
                FamilyLocationReportAnalyzer.formatDuration(report.totalSafePlaceDurationMs)));
        rows.add(Row.numeric("Active member-days", report.activeMemberDays));
        rows.add(Row.blank());
        rows.add(Row.header("Member", "Role", "Distance km", "Moving minutes",
                "Active days", "Route points", "Most visited Safe Place"));
        for (FamilyLocationReport.MemberReport member : report.members) {
            rows.add(Row.mixed(new Cell[]{
                    Cell.text(member.displayName),
                    Cell.text(member.role),
                    Cell.number(member.totalDistanceMeters / 1_000D),
                    Cell.number(member.movingDurationMs / 60_000D),
                    Cell.number(member.activeDays),
                    Cell.number(member.routePointCount),
                    Cell.text(member.mostVisitedPlace)
            }));
        }
        rows.add(Row.blank());
        rows.add(Row.header("Movement type", "Duration minutes"));
        for (Map.Entry<String, Long> movement
                : report.movementDurationMs.entrySet()) {
            rows.add(Row.mixed(new Cell[]{
                    Cell.text(titleCase(movement.getKey())),
                    Cell.number(movement.getValue() / 60_000D)
            }));
        }
        rows.add(Row.blank());
        rows.add(Row.header("Safe Place", "Visits", "Duration minutes"));
        for (FamilyLocationReport.PlaceStat place : report.familySafePlaces) {
            rows.add(Row.mixed(new Cell[]{
                    Cell.text(place.name),
                    Cell.number(place.visitCount),
                    Cell.number(place.durationMs / 60_000D)
            }));
        }
        rows.add(Row.blank());
        rows.add(Row.header("Member", "Insight type", "Title", "Detail"));
        for (FamilyLocationReport.Insight insight : report.familyInsights) {
            rows.add(Row.mixed(new Cell[]{
                    Cell.text("Family"), Cell.text(insight.type),
                    Cell.text(insight.title), Cell.text(insight.detail)
            }));
        }
        for (FamilyLocationReport.MemberReport member : report.members) {
            for (FamilyLocationReport.Insight insight : member.insights) {
                rows.add(Row.mixed(new Cell[]{
                        Cell.text(member.displayName), Cell.text(insight.type),
                        Cell.text(insight.title), Cell.text(insight.detail)
                }));
            }
        }
        return rows;
    }

    @NonNull
    private static String columnName(int column) {
        StringBuilder name = new StringBuilder();
        int value = column;
        while (value > 0) {
            value--;
            name.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return name.toString();
    }

    @NonNull
    private static String escapeXml(@NonNull String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @NonNull
    private static String titleCase(@NonNull String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty()
                ? lower
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static final class Cell {
        @NonNull final String value;
        final boolean numeric;

        private Cell(@NonNull String value, boolean numeric) {
            this.value = value;
            this.numeric = numeric;
        }

        static Cell text(@NonNull String value) {
            return new Cell(value, false);
        }

        static Cell number(double value) {
            return new Cell(String.format(Locale.US, "%.4f", value), true);
        }
    }

    private static final class Row {
        @NonNull final Cell[] cells;
        final int style;

        private Row(int style, @NonNull Cell[] cells) {
            this.style = style;
            this.cells = cells;
        }

        static Row title(@NonNull String title) {
            return new Row(1, new Cell[]{Cell.text(title)});
        }

        static Row header(@NonNull String... values) {
            Cell[] cells = new Cell[values.length];
            for (int index = 0; index < values.length; index++) {
                cells[index] = Cell.text(values[index]);
            }
            return new Row(2, cells);
        }

        static Row of(@NonNull String first, @NonNull String second) {
            return new Row(0, new Cell[]{Cell.text(first), Cell.text(second)});
        }

        static Row numeric(@NonNull String first, double second) {
            return new Row(0, new Cell[]{Cell.text(first), Cell.number(second)});
        }

        static Row mixed(@NonNull Cell[] cells) {
            return new Row(0, cells);
        }

        static Row blank() {
            return new Row(0, new Cell[]{Cell.text("")});
        }
    }

    private static final class PdfWriter {
        @NonNull private final PdfDocument document;
        @NonNull private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private PdfDocument.Page page;
        private Canvas canvas;
        private int pageNumber;
        private float y;

        PdfWriter(@NonNull PdfDocument document) {
            this.document = document;
            newPage();
        }

        void title(@NonNull String text) {
            paint.setTextSize(20F);
            paint.setFakeBoldText(true);
            paint.setColor(0xFF0F6CBD);
            drawWrapped(text, 25F);
            paint.setFakeBoldText(false);
        }

        void heading(@NonNull String text) {
            ensure(42F);
            y += 12F;
            paint.setTextSize(15F);
            paint.setFakeBoldText(true);
            paint.setColor(0xFF0F6CBD);
            drawWrapped(text, 20F);
            paint.setFakeBoldText(false);
        }

        void subheading(@NonNull String text) {
            ensure(34F);
            y += 7F;
            paint.setTextSize(11.5F);
            paint.setFakeBoldText(true);
            paint.setColor(0xFF242424);
            drawWrapped(text, 16F);
            paint.setFakeBoldText(false);
        }

        void body(@NonNull String text) {
            paint.setTextSize(10.5F);
            paint.setColor(0xFF424242);
            drawWrapped(text, 15F);
        }

        void small(@NonNull String text) {
            paint.setTextSize(8.5F);
            paint.setColor(0xFF666666);
            drawWrapped(text, 12F);
        }

        void space() {
            y += 8F;
        }

        void finish() {
            if (page != null) {
                document.finishPage(page);
                page = null;
            }
        }

        private void drawWrapped(@NonNull String text, float lineHeight) {
            float width = PDF_WIDTH - PDF_MARGIN * 2F;
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.length() == 0
                        ? word
                        : line + " " + word;
                if (paint.measureText(candidate) > width && line.length() > 0) {
                    drawLine(line.toString(), lineHeight);
                    line.setLength(0);
                    line.append(word);
                } else {
                    if (line.length() > 0) {
                        line.append(' ');
                    }
                    line.append(word);
                }
            }
            if (line.length() > 0) {
                drawLine(line.toString(), lineHeight);
            }
        }

        private void drawLine(@NonNull String line, float lineHeight) {
            ensure(lineHeight + 4F);
            canvas.drawText(line, PDF_MARGIN, y, paint);
            y += lineHeight;
        }

        private void ensure(float required) {
            if (y + required > PDF_HEIGHT - PDF_MARGIN) {
                document.finishPage(page);
                newPage();
            }
        }

        private void newPage() {
            pageNumber++;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    PDF_WIDTH,
                    PDF_HEIGHT,
                    pageNumber
            ).create();
            page = document.startPage(info);
            canvas = page.getCanvas();
            canvas.drawColor(0xFFFFFFFF);
            y = PDF_MARGIN;
        }
    }
}
