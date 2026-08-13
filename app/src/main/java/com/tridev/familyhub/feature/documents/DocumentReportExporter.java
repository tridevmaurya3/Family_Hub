package com.tridev.familyhub.feature.documents;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import androidx.annotation.NonNull;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class DocumentReportExporter {
    private DocumentReportExporter() { }

    static void pdf(@NonNull File file, @NonNull List<DocumentEntry> rows) throws IOException {
        PdfDocument pdf = new PdfDocument(); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PdfDocument.Page page = null; Canvas canvas = null; int y = 0, number = 0;
        for (DocumentEntry row : rows) {
            if (page == null || y > 790) {
                if (page != null) pdf.finishPage(page);
                page = pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, ++number).create());
                canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(68,45,130)); paint.setTextSize(18); paint.setFakeBoldText(true);
                canvas.drawText("FAMILY HUB • DOCUMENTS INVENTORY", 32, 40, paint);
                paint.setTextSize(9); paint.setFakeBoldText(false);
                canvas.drawText("TITLE / MEMBER",32,68,paint); canvas.drawText("CATEGORY / MODULE",250,68,paint);
                canvas.drawText("STATUS / EXPIRY",430,68,paint); y=88;
            }
            paint.setColor(Color.DKGRAY); paint.setTextSize(10); paint.setFakeBoldText(true);
            canvas.drawText(shorten(row.title,30),32,y,paint); paint.setFakeBoldText(false); paint.setTextSize(8);
            canvas.drawText(shorten(row.memberName,30),32,y+13,paint);
            canvas.drawText(shorten(row.category,22),250,y,paint); canvas.drawText(shorten(row.linkedModule,22),250,y+13,paint);
            canvas.drawText(row.emergency ? "EMERGENCY" : "ACTIVE",430,y,paint);
            canvas.drawText(row.expiryAt>0 ? new SimpleDateFormat("dd-MM-yyyy",Locale.ENGLISH).format(new Date(row.expiryAt)) : "No expiry",430,y+13,paint);
            y += 36;
        }
        if (page != null) pdf.finishPage(page);
        try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);} pdf.close();
    }

    static void excel(@NonNull File file,@NonNull List<DocumentEntry> rows)throws IOException{
        StringBuilder x=new StringBuilder("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"><Worksheet ss:Name=\"Documents\"><Table>");
        x.append(row("Title","Member","Category","Module","Issuer","Document number","Expiry","Emergency"));
        for(DocumentEntry d:rows)x.append(row(d.title,d.memberName,d.category,d.linkedModule,d.issuer,d.documentNumber,d.expiryAt>0?new SimpleDateFormat("dd-MM-yyyy",Locale.ENGLISH).format(new Date(d.expiryAt)):"",d.emergency?"Yes":"No"));
        x.append("</Table></Worksheet></Workbook>"); try(FileOutputStream out=new FileOutputStream(file)){out.write(x.toString().getBytes(StandardCharsets.UTF_8));}
    }
    private static String row(String...v){StringBuilder x=new StringBuilder("<Row>");for(String s:v)x.append("<Cell><Data ss:Type=\"String\">").append((s==null?"":s).replace("&","&amp;").replace("<","&lt;")).append("</Data></Cell>");return x.append("</Row>").toString();}
    private static String shorten(String s,int n){if(s==null)return "";return s.length()>n?s.substring(0,n-1)+"…":s;}
}
