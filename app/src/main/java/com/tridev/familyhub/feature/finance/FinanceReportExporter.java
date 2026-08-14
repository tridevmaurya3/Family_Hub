package com.tridev.familyhub.feature.finance;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.tridev.familyhub.data.local.entity.FinanceEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** A4 PDF and A4-print-ready Excel report for the currently visible finance list. */
final class FinanceReportExporter {
    private FinanceReportExporter() { }

    static void pdf(File file, List<FinanceEntry> rows) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PdfDocument.Page page = null;
        Canvas canvas = null;
        int pageNumber = 0, y = 0;
        double income = 0D, expense = 0D;
        for (int i = 0; i <= rows.size(); i++) {
            if (page == null || y > 790) {
                if (page != null) document.finishPage(page);
                page = document.startPage(new PdfDocument.PageInfo.Builder(
                        595, 842, ++pageNumber).create());
                canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(20, 105, 55)); paint.setTextSize(20);
                paint.setFakeBoldText(true);
                canvas.drawText("FAMILY HUB • FINANCE REPORT", 32, 42, paint);
                paint.setTextSize(10); paint.setColor(Color.DKGRAY);
                canvas.drawText("DATE", 34, 72, paint);
                canvas.drawText("TYPE", 115, 72, paint);
                canvas.drawText("CATEGORY", 180, 72, paint);
                canvas.drawText("ACCOUNT", 310, 72, paint);
                canvas.drawText("AMOUNT", 480, 72, paint);
                y = 94;
            }
            if (i == rows.size()) break;
            FinanceEntry row = rows.get(i);
            boolean isIncome = FinanceEntry.TYPE_INCOME.equals(row.entryType);
            if (isIncome) income += row.amount; else expense += row.amount;
            paint.setFakeBoldText(false); paint.setTextSize(9);
            paint.setColor(isIncome ? Color.rgb(20, 115, 55) : Color.rgb(185, 35, 35));
            canvas.drawText(row.transactionDate, 34, y, paint);
            canvas.drawText(isIncome ? "INCOME" : "EXPENSE", 115, y, paint);
            canvas.drawText(shortText(row.category, 20), 180, y, paint);
            canvas.drawText(shortText(row.accountName, 22), 310, y, paint);
            canvas.drawText(String.format(Locale.ENGLISH, "Rs %.2f", row.amount), 475, y, paint);
            y += 20;
        }
        if (page != null) {
            paint.setFakeBoldText(true); paint.setTextSize(11); paint.setColor(Color.DKGRAY);
            canvas.drawText(String.format(Locale.ENGLISH,
                    "Income Rs %.2f   Expense Rs %.2f   Balance Rs %.2f",
                    income, expense, income - expense), 34, Math.min(820, y + 18), paint);
            document.finishPage(page);
        }
        try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
        document.close();
    }

    static void excel(File file, List<FinanceEntry> rows) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>"
                + "<?mso-application progid=\"Excel.Sheet\"?>"
                + "<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:x=\"urn:schemas-microsoft-com:office:excel\">"
                + "<Worksheet ss:Name=\"Finance Report\"><Table>"
                + row("Date", "Type", "Category", "Account", "Payment", "Description", "Amount"));
        double income = 0D, expense = 0D;
        for (FinanceEntry item : rows) {
            boolean isIncome = FinanceEntry.TYPE_INCOME.equals(item.entryType);
            if (isIncome) income += item.amount; else expense += item.amount;
            xml.append(row(item.transactionDate, item.entryType, item.category,
                    item.accountName, item.paymentMethod, item.note,
                    String.format(Locale.ENGLISH, "%.2f", item.amount)));
        }
        xml.append(row("", "TOTAL", "", "", "", "Income",
                String.format(Locale.ENGLISH, "%.2f", income)));
        xml.append(row("", "TOTAL", "", "", "", "Expense",
                String.format(Locale.ENGLISH, "%.2f", expense)));
        xml.append(row("", "TOTAL", "", "", "", "Balance",
                String.format(Locale.ENGLISH, "%.2f", income - expense)));
        xml.append("</Table><WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\">"
                + "<PageSetup><Layout x:Orientation=\"Landscape\"/></PageSetup>"
                + "<FitToPage/><Print><FitWidth>1</FitWidth><FitHeight>0</FitHeight>"
                + "<PaperSizeIndex>9</PaperSizeIndex></Print></WorksheetOptions>"
                + "</Worksheet></Workbook>");
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
