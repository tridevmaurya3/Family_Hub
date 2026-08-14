package com.tridev.familyhub;

import java.util.Locale;
import java.util.UUID;

/** Common V1 cross-app finance integration contract. */
public final class TridevIntegrationContract {
    public static final int SCHEMA_VERSION = 1;
    public static final String APP_SMART_SMS = "smart_sms_pro";
    public static final String APP_MONEY_MANAGER = "money_manager_pro";
    public static final String APP_FAMILY_HUB = "family_hub";
    public static final String APP_LOAN_MANAGER = "loan_manager_pro";
    public static final String DEFAULT_CURRENCY = "INR";

    private TridevIntegrationContract() { }

    public enum EventType { SMS_FINANCIAL_SIGNAL, FINANCE_TRANSACTION, GROCERY_PURCHASE, LOAN_PAYMENT, BILL_PAYMENT, INCOME, EXPENSE, TRANSFER, REFUND }
    public enum Direction { CREDIT, DEBIT, TRANSFER, UNKNOWN }
    public enum Scope { PERSONAL, FAMILY, UNKNOWN }
    public enum SyncState { LOCAL_ONLY, PENDING, SYNCED, FAILED, NEEDS_REVIEW, SUPERSEDED }
    public enum MatchConfidence { EXACT, HIGH, MEDIUM, LOW, UNMATCHED }

    public static final class References {
        public final String moneyManagerAccountId;
        public final String moneyManagerCategoryId;
        public final String moneyManagerTransactionId;
        public final String familyFinanceRecordId;
        public final String familyGroceryRecordId;
        public final String loanManagerLoanId;
        public final String loanManagerPaymentId;

        public References(String moneyManagerAccountId, String moneyManagerCategoryId,
                          String moneyManagerTransactionId, String familyFinanceRecordId,
                          String familyGroceryRecordId, String loanManagerLoanId,
                          String loanManagerPaymentId) {
            this.moneyManagerAccountId = clean(moneyManagerAccountId);
            this.moneyManagerCategoryId = clean(moneyManagerCategoryId);
            this.moneyManagerTransactionId = clean(moneyManagerTransactionId);
            this.familyFinanceRecordId = clean(familyFinanceRecordId);
            this.familyGroceryRecordId = clean(familyGroceryRecordId);
            this.loanManagerLoanId = clean(loanManagerLoanId);
            this.loanManagerPaymentId = clean(loanManagerPaymentId);
        }

        public static References empty() { return new References("", "", "", "", "", "", ""); }
    }

    public static final class Event {
        public final int schemaVersion;
        public final String eventId;
        public final String sourceApp;
        public final String sourceRecordId;
        public final EventType eventType;
        public final Direction direction;
        public final Scope scope;
        public final long amountMinor;
        public final String currency;
        public final long occurredAt;
        public final long createdAt;
        public final String accountHint;
        public final String merchantHint;
        public final String categoryHint;
        public final String linkedEventId;
        public final String dedupeFingerprint;
        public final SyncState syncState;
        public final MatchConfidence matchConfidence;
        public final References references;

        public Event(String eventId, String sourceApp, String sourceRecordId,
                     EventType eventType, Direction direction, Scope scope,
                     long amountMinor, String currency, long occurredAt, long createdAt,
                     String accountHint, String merchantHint, String categoryHint,
                     String linkedEventId, String dedupeFingerprint, SyncState syncState,
                     MatchConfidence matchConfidence, References references) {
            this.schemaVersion = SCHEMA_VERSION;
            this.eventId = requireValue(eventId, "eventId");
            this.sourceApp = requireKnownApp(sourceApp);
            this.sourceRecordId = clean(sourceRecordId);
            this.eventType = eventType == null ? EventType.EXPENSE : eventType;
            this.direction = direction == null ? Direction.UNKNOWN : direction;
            this.scope = scope == null ? Scope.UNKNOWN : scope;
            this.amountMinor = Math.max(0L, amountMinor);
            this.currency = clean(currency).isEmpty() ? DEFAULT_CURRENCY : clean(currency).toUpperCase(Locale.ROOT);
            this.occurredAt = Math.max(0L, occurredAt);
            this.createdAt = createdAt > 0L ? createdAt : System.currentTimeMillis();
            this.accountHint = clean(accountHint);
            this.merchantHint = clean(merchantHint);
            this.categoryHint = clean(categoryHint);
            this.linkedEventId = clean(linkedEventId);
            this.dedupeFingerprint = clean(dedupeFingerprint);
            this.syncState = syncState == null ? SyncState.LOCAL_ONLY : syncState;
            this.matchConfidence = matchConfidence == null ? MatchConfidence.UNMATCHED : matchConfidence;
            this.references = references == null ? References.empty() : references;
        }
    }

    public static String newEventId() { return UUID.randomUUID().toString(); }

    public static boolean isKnownApp(String appId) {
        return APP_SMART_SMS.equals(appId) || APP_MONEY_MANAGER.equals(appId)
                || APP_FAMILY_HUB.equals(appId) || APP_LOAN_MANAGER.equals(appId);
    }

    private static String requireKnownApp(String value) {
        String cleaned = clean(value);
        if (!isKnownApp(cleaned)) throw new IllegalArgumentException("Unknown integration source app: " + cleaned);
        return cleaned;
    }

    private static String requireValue(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return cleaned;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
