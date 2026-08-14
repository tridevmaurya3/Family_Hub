package com.tridev.familyhub.feature.familylive;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

/** Creates compact photo/initial markers without changing the existing map flow. */
final class FamilyMemberMarkerFactory {
    private FamilyMemberMarkerFactory() { }

    @NonNull
    static BitmapDescriptor create(
            @NonNull String name,
            @Nullable Bitmap photo,
            @ColorInt int statusColor
    ) {
        int size = 72;
        Bitmap output = Bitmap.createBitmap(size, 84, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = size / 2F, cy = 34F, radius = 28F;

        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, radius + 4F, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5F);
        paint.setColor(statusColor);
        canvas.drawCircle(cx, cy, radius + 1F, paint);
        paint.setStyle(Paint.Style.FILL);

        Path clip = new Path();
        clip.addCircle(cx, cy, radius - 2F, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (photo != null) {
            Rect source = new Rect(0, 0, photo.getWidth(), photo.getHeight());
            RectF target = new RectF(cx - radius, cy - radius,
                    cx + radius, cy + radius);
            canvas.drawBitmap(photo, source, target, paint);
        } else {
            paint.setColor(lighten(statusColor));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setColor(statusColor);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(20F);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = cy - (metrics.ascent + metrics.descent) / 2F;
            canvas.drawText(initials(name), cx, baseline, paint);
        }
        canvas.restore();

        Path tail = new Path();
        tail.moveTo(cx - 9F, 62F);
        tail.lineTo(cx + 9F, 62F);
        tail.lineTo(cx, 80F);
        tail.close();
        paint.setColor(statusColor);
        canvas.drawPath(tail, paint);
        return BitmapDescriptorFactory.fromBitmap(output);
    }

    private static int lighten(@ColorInt int color) {
        return Color.rgb((Color.red(color) + 510) / 3,
                (Color.green(color) + 510) / 3,
                (Color.blue(color) + 510) / 3);
    }

    @NonNull
    private static String initials(@NonNull String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return "?";
        String first = words[0].substring(0, 1).toUpperCase();
        if (words.length == 1) return first;
        return first + words[words.length - 1].substring(0, 1).toUpperCase();
    }
}
