package com.tridev.familyhub.feature.reminders;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.tridev.familyhub.data.local.entity.Reminder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A4 PDF and A4-print-ready Excel export for the currently filtered reminders. */
final class ReminderReportExporter {
    private ReminderReportExporter() { }

    static void pdf(File file, List<Reminder> rows) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PdfDocument.Page page = null; Canvas canvas = null;
        int pageNumber = 0, y = 0;
        SimpleDateFormat format = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        for (int i = 0; i < rows.size(); i++) {
            if (page == null || y > 790) {
                if (page != null) document.finishPage(page);
                page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create());
                canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(138, 90, 0)); paint.setTextSize(20); paint.setFakeBoldText(true);
                canvas.drawText("FAMILY HUB • REMINDER REPORT", 32, 42, paint);
                paint.setTextSize(9); paint.setColor(Color.DKGRAY);
                canvas.drawText("DATE & TIME", 34, 72, paint);
                canvas.drawText("TITLE", 160, 72, paint);
                canvas.drawText("PRIORITY", 330, 72, paint);
                canvas.drawText("STATUS", 410, 72, paint);
                canvas.drawText("MEMBER", 500, 72, paint);
                y = 94;
            }
            Reminder row = rows.get(i);
            paint.setFakeBoldText(false); paint.setTextSize(8.5f);
            paint.setColor("URGENT".equals(row.priority) ? Color.rgb(190, 35, 35) : Color.DKGRAY);
            canvas.drawText(shortText(format.format(new Date(row.reminderAt)), 24), 34, y, paint);
            canvas.drawText(shortText(row.title, 29), 160, y, paint);
            canvas.drawText(shortText(row.priority, 10), 330, y, paint);
            canvas.drawText(shortText(row.collaborationStatus, 14), 410, y, paint);
            canvas.drawText(shortText(row.assignedMemberName, 13), 500, y, paint);
            y += 20;
        }
        if (page == null) {
            page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
            paint.setTextSize(16); paint.setColor(Color.DKGRAY);
            canvas.drawText("FAMILY HUB • REMINDER REPORT", 32, 42, paint);
            canvas.drawText("No reminders in current filter", 32, 80, paint);
        }
        document.finishPage(page);
        try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
        document.close();
    }

    static void excel(File file, List<Reminder> rows) throws IOException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>"
                + "<?mso-application progid=\"Excel.Sheet\"?>"
                + "<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:x=\"urn:schemas-microsoft-com:office:excel\"><Worksheet ss:Name=\"Reminders\"><Table>"
                + row("Date & Time", "Title", "Priority", "Category", "Repeat", "Status", "Member", "Module", "Related item"));
        for (Reminder item : rows) xml.append(row(format.format(new Date(item.reminderAt)),
                item.title, item.priority, item.category, item.repeatType,
                item.collaborationStatus, item.assignedMemberName,
                item.relatedModule, item.relatedItemTitle));
        xml.append("</Table><WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\">"
                + "<PageSetup><Layout x:Orientation=\"Landscape\"/></PageSetup><FitToPage/>"
                + "<Print><FitWidth>1</FitWidth><FitHeight>0</FitHeight><PaperSizeIndex>9</PaperSizeIndex>"
                + "</Print></WorksheetOptions></Worksheet></Workbook>");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(xml.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String row(String... cells) {
        StringBuilder out = new StringBuilder("<Row>");
        for (String cell : cells) out.append("<Cell><Data ss:Type=\"String\">")
                .append(escape(cell)).append("</Data></Cell>");
        return out.append("</Row>").toString();
    }

    private static String shortText(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
