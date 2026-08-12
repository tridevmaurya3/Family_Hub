package com.tridev.familyhub.feature.grocery;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import com.tridev.familyhub.data.local.entity.GroceryPurchase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Compact A4 PDF and A4-print-ready Excel 2003 XML grocery reports. */
final class GroceryReportExporter {
    private GroceryReportExporter() { }

    static void pdf(File file, List<GroceryPurchase> rows) throws IOException {
        rows = aggregate(rows);
        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int page = 0, y = 0;
        PdfDocument.Page current = null;
        Canvas canvas = null;
        String category = "";
        double grand = 0D;
        for (int index = -1; index <= rows.size(); index++) {
            if (current == null || y > 785) {
                if (current != null) document.finishPage(current);
                current = document.startPage(new PdfDocument.PageInfo.Builder(
                        595, 842, ++page).create());
                canvas = current.getCanvas();
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(31, 79, 67)); paint.setTextSize(20);
                paint.setFakeBoldText(true);
                canvas.drawText("FAMILY HUB • MONTHLY GROCERY REPORT", 34, 42, paint);
                paint.setTextSize(10); paint.setFakeBoldText(false);
                canvas.drawText(new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH)
                        .format(new Date()), 34, 60, paint);
                paint.setColor(Color.DKGRAY);
                canvas.drawText("ITEM", 44, 82, paint);
                canvas.drawText("QUANTITY", 245, 82, paint);
                canvas.drawText("STORE", 345, 82, paint);
                canvas.drawText("AMOUNT", 485, 82, paint);
                y = 100;
                category = "";
            }
            if (index < 0) continue;
            if (index == rows.size()) {
                paint.setColor(Color.rgb(31, 79, 67)); paint.setTextSize(12);
                paint.setFakeBoldText(true);
                canvas.drawText("MONTH TOTAL", 44, y + 14, paint);
                canvas.drawText(String.format(Locale.ENGLISH, "Rs %.2f", grand),
                        455, y + 14, paint);
                break;
            }
            GroceryPurchase row = rows.get(index);
            if (!category.equalsIgnoreCase(row.category)) {
                category = row.category;
                paint.setColor(Color.rgb(226, 244, 238));
                canvas.drawRect(34, y - 13, 561, y + 8, paint);
                paint.setColor(Color.rgb(31, 79, 67)); paint.setTextSize(11);
                paint.setFakeBoldText(true);
                canvas.drawText(category.toUpperCase(Locale.ENGLISH), 44, y + 2, paint);
                y += 25;
            }
            paint.setColor(Color.DKGRAY); paint.setTextSize(10); paint.setFakeBoldText(false);
            canvas.drawText(row.itemName, 44, y, paint);
            canvas.drawText(row.quantity, 245, y, paint);
            canvas.drawText(displayStore(row.storeName), 345, y, paint);
            canvas.drawText(String.format(Locale.ENGLISH, "Rs %.2f", row.actualCost),
                    485, y, paint);
            grand += row.actualCost; y += 19;
        }
        if (current != null) document.finishPage(current);
        try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
        document.close();
    }

    static void excel(File file, List<GroceryPurchase> rows) throws IOException {
        rows = aggregate(rows);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>"
                + "<?mso-application progid=\"Excel.Sheet\"?>"
                + "<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:x=\"urn:schemas-microsoft-com:office:excel\">"
                + "<Worksheet ss:Name=\"Monthly Grocery\"><Table>"
                + row("Category","Item","Quantity","Store / Shop","Amount","Purchase date"));
        double total = 0D;
        for (GroceryPurchase p : rows) {
            xml.append(row(p.category, p.itemName, p.quantity,
                    displayStore(p.storeName),
                    String.format(Locale.ENGLISH, "%.2f", p.actualCost),
                    new SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
                            .format(new Date(p.purchasedAt))));
            total += p.actualCost;
        }
        xml.append(row("","MONTH TOTAL","","",
                String.format(Locale.ENGLISH, "%.2f", total), ""));
        xml.append("</Table><WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\">"
                + "<PageSetup><Layout x:Orientation=\"Landscape\"/>"
                + "<PageMargins x:Bottom=\"0.4\" x:Left=\"0.35\" x:Right=\"0.35\" x:Top=\"0.5\"/>"
                + "</PageSetup><FitToPage/><Print><FitWidth>1</FitWidth><FitHeight>0</FitHeight>"
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

    private static List<GroceryPurchase> aggregate(List<GroceryPurchase> source) {
        Map<String, GroceryPurchase> totals = new LinkedHashMap<>();
        for (GroceryPurchase purchase : source) {
            String[] quantity = purchase.quantity.trim().split("\\s+", 2);
            String unit = quantity.length > 1 ? quantity[1] : "";
            String key = purchase.category.toLowerCase(Locale.ENGLISH) + "|"
                    + purchase.itemName.toLowerCase(Locale.ENGLISH) + "|"
                    + quantityFamily(unit) + "|"
                    + purchase.storeName.toLowerCase(Locale.ENGLISH);
            GroceryPurchase total = totals.get(key);
            if (total == null) {
                total = new GroceryPurchase();
                total.category = purchase.category;
                total.itemName = purchase.itemName;
                total.quantity = purchase.quantity;
                total.storeName = purchase.storeName;
                total.actualCost = purchase.actualCost;
                total.purchasedAt = purchase.purchasedAt;
                totals.put(key, total);
            } else {
                total.quantity = GroceryQuantityCalculator.add(
                        total.quantity, purchase.quantity);
                total.actualCost += purchase.actualCost;
                total.purchasedAt = Math.max(total.purchasedAt, purchase.purchasedAt);
            }
        }
        return new ArrayList<>(totals.values());
    }

    private static String displayStore(String storeName) {
        return storeName == null || storeName.trim().isEmpty()
                ? "Not specified" : storeName.trim();
    }

    private static String quantityFamily(String unit) {
        String value = unit.toLowerCase(Locale.ENGLISH);
        if (value.equals("g") || value.equals("kg") || value.startsWith("gram")) return "mass";
        if (value.equals("ml") || value.equals("l") || value.startsWith("lit")) return "volume";
        if (value.equals("dozen") || value.equals("pcs") || value.startsWith("piece")) return "count";
        return value;
    }

    private static double number(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return 0D; }
    }

    private static String clean(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ENGLISH, "%.2f", value);
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
