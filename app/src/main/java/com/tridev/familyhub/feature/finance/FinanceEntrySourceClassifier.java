package com.tridev.familyhub.feature.finance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.entity.FinanceEntry;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a stable display/filter source from existing FinanceEntry metadata.
 *
 * This intentionally does not require a Room migration, so historical entries
 * immediately participate in source filtering. Trusted integrations already
 * leave deterministic markers in paymentMethod, cloudId, or note.
 */
public final class FinanceEntrySourceClassifier {

    public static final String SOURCE_ALL = "ALL";
    public static final String SOURCE_GROCERY = "GROCERY";
    public static final String SOURCE_LOAN_MANAGER = "LOAN_MANAGER";
    public static final String SOURCE_MONEY_MANAGER = "MONEY_MANAGER";
    public static final String SOURCE_DIRECT = "DIRECT";
    public static final String SOURCE_OTHER = "OTHER";

    private static final Pattern NOTE_SOURCE = Pattern.compile("^\\[([^]]+)]");

    private FinanceEntrySourceClassifier() { }

    @NonNull
    public static String key(@Nullable FinanceEntry entry) {
        if (entry == null) return SOURCE_OTHER;
        String paymentMethod = safe(entry.paymentMethod);
        String note = safe(entry.note);
        String cloudId = safe(entry.cloudId);

        if (equalsIgnoreCase(paymentMethod, "LoanManagerPro")
                || startsWithIgnoreCase(cloudId, "loan_payment_")
                || containsIgnoreCase(note, "[LoanManagerProjection]")) {
            return SOURCE_LOAN_MANAGER;
        }
        if (startsWithIgnoreCase(cloudId, "grocery_")
                || containsIgnoreCase(note, "[Grocery]")) {
            return SOURCE_GROCERY;
        }
        if (equalsIgnoreCase(paymentMethod, "MoneyManagerPro")
                || containsIgnoreCase(note, "[MoneyManager]")
                || containsIgnoreCase(note, "[MoneyManagerPro]")) {
            return SOURCE_MONEY_MANAGER;
        }
        if (!sourceMarker(note).isEmpty()) {
            return SOURCE_OTHER;
        }
        return SOURCE_DIRECT;
    }

    @NonNull
    public static String displayLabel(@Nullable FinanceEntry entry) {
        String key = key(entry);
        if (SOURCE_GROCERY.equals(key)) return "Grocery";
        if (SOURCE_LOAN_MANAGER.equals(key)) return "Loan Manager";
        if (SOURCE_MONEY_MANAGER.equals(key)) return "MoneyManager";
        if (SOURCE_DIRECT.equals(key)) return "Direct";

        String marker = entry == null ? "" : sourceMarker(entry.note);
        if (marker.isEmpty()) return "Other";
        if ("LoanManagerProjection".equalsIgnoreCase(marker)) return "Loan Manager";
        return marker;
    }

    public static boolean matches(@Nullable FinanceEntry entry, @Nullable String selectedKey) {
        String wanted = safe(selectedKey).toUpperCase(Locale.ROOT);
        return wanted.isEmpty() || SOURCE_ALL.equals(wanted) || key(entry).equals(wanted);
    }

    @NonNull
    private static String sourceMarker(@Nullable String note) {
        Matcher matcher = NOTE_SOURCE.matcher(safe(note));
        if (!matcher.find()) return "";
        return safe(matcher.group(1));
    }

    private static boolean equalsIgnoreCase(@Nullable String left, @NonNull String right) {
        return right.equalsIgnoreCase(safe(left));
    }

    private static boolean startsWithIgnoreCase(@Nullable String value, @NonNull String prefix) {
        return safe(value).toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private static boolean containsIgnoreCase(@Nullable String value, @NonNull String part) {
        return safe(value).toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
