package com.tridev.familyhub.feature.automation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;

/**
 * Reads Firebase primitive numbers without asking CustomClassMapper to
 * deserialize to java.lang.Number, which Realtime Database does not support.
 */
public final class FirebaseNumericValueReader {

    private FirebaseNumericValueReader() {
    }

    public static long nonNegativeLong(
            @NonNull DataSnapshot snapshot,
            long fallback
    ) {
        Number value = rawNumber(snapshot);
        if (value == null) {
            return fallback;
        }
        double decimal = value.doubleValue();
        if (!Double.isFinite(decimal) || decimal < 0D) {
            return fallback;
        }
        if (decimal >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return value.longValue();
    }

    public static int intValue(
            @NonNull DataSnapshot snapshot,
            int fallback
    ) {
        Number value = rawNumber(snapshot);
        if (value == null) {
            return fallback;
        }
        double decimal = value.doubleValue();
        if (!Double.isFinite(decimal)) {
            return fallback;
        }
        if (decimal > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (decimal < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return value.intValue();
    }

    @Nullable
    public static Double doubleValue(@NonNull DataSnapshot snapshot) {
        Number value = rawNumber(snapshot);
        if (value == null) {
            return null;
        }
        double decimal = value.doubleValue();
        return Double.isFinite(decimal) ? decimal : null;
    }

    @Nullable
    private static Number rawNumber(@NonNull DataSnapshot snapshot) {
        Object raw = snapshot.getValue();
        if (raw instanceof Number) {
            return (Number) raw;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
