package com.tridev.familyhub.feature.insights;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Lightweight horizontal bar chart with no third-party chart dependency. */
public final class FamilyInsightsBarChartView extends View {

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    @NonNull private List<String> labels = Collections.emptyList();
    @NonNull private List<Float> values = Collections.emptyList();
    @NonNull private String suffix = "";

    public FamilyInsightsBarChartView(@NonNull Context context) {
        this(context, null);
    }

    public FamilyInsightsBarChartView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        labelPaint.setTextSize(dp(12));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.fh_text_primary));
        valuePaint.setTextSize(dp(11));
        valuePaint.setColor(ContextCompat.getColor(context, R.color.fh_text_secondary));
        trackPaint.setColor(ContextCompat.getColor(context, R.color.fh_surface_container));
        barPaint.setColor(ContextCompat.getColor(context, R.color.fh_primary));
        setMinimumHeight(dp(120));
    }

    public void setData(
            @NonNull List<String> sourceLabels,
            @NonNull List<Float> sourceValues,
            @NonNull String valueSuffix
    ) {
        int size = Math.min(sourceLabels.size(), sourceValues.size());
        List<String> safeLabels = new ArrayList<>(size);
        List<Float> safeValues = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            safeLabels.add(sourceLabels.get(index));
            safeValues.add(Math.max(0F, sourceValues.get(index)));
        }
        labels = safeLabels;
        values = safeValues;
        suffix = valueSuffix;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = labels.isEmpty()
                ? dp(120)
                : dp(28) + labels.size() * dp(44);
        int width = resolveSize(dp(280), widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (labels.isEmpty()) {
            valuePaint.setTextSize(dp(13));
            canvas.drawText(
                    getResources().getString(R.string.family_reports_chart_no_data),
                    dp(12),
                    getHeight() / 2F,
                    valuePaint
            );
            valuePaint.setTextSize(dp(11));
            return;
        }

        float max = 0F;
        for (Float value : values) {
            max = Math.max(max, value);
        }
        max = Math.max(1F, max);

        float left = dp(12);
        float right = getWidth() - dp(12);
        float labelWidth = Math.min(dp(96), getWidth() * 0.34F);
        float barLeft = left + labelWidth;
        float barRight = right;
        float y = dp(18);

        for (int index = 0; index < labels.size(); index++) {
            String label = ellipsize(labels.get(index), labelPaint, labelWidth - dp(8));
            canvas.drawText(label, left, y + dp(16), labelPaint);

            float top = y + dp(22);
            float bottom = top + dp(10);
            rect.set(barLeft, top, barRight, bottom);
            canvas.drawRoundRect(rect, dp(5), dp(5), trackPaint);

            float fraction = values.get(index) / max;
            rect.set(barLeft, top, barLeft + (barRight - barLeft) * fraction, bottom);
            canvas.drawRoundRect(rect, dp(5), dp(5), barPaint);

            String value = formatValue(values.get(index)) + suffix;
            float valueWidth = valuePaint.measureText(value);
            canvas.drawText(value, Math.max(barLeft, barRight - valueWidth),
                    bottom + dp(15), valuePaint);
            y += dp(44);
        }
    }

    @NonNull
    private String formatValue(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05F) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    @NonNull
    private String ellipsize(
            @NonNull String value,
            @NonNull Paint paint,
            float maximumWidth
    ) {
        if (paint.measureText(value) <= maximumWidth) {
            return value;
        }
        String suffixText = "…";
        int end = value.length();
        while (end > 1 && paint.measureText(
                value.substring(0, end) + suffixText
        ) > maximumWidth) {
            end--;
        }
        return value.substring(0, Math.max(1, end)) + suffixText;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
