package com.tridev.familyhub.feature.tasks;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.entity.FamilyTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-only parser for text explicitly shared by the user from an SMS/messaging app.
 * Raw message text is never persisted or uploaded by this class.
 */
final class FamilyTaskSmsSuggestionParser {
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b([0-3]?\\d)[/.-]([01]?\\d)(?:[/.-](\\d{2,4}))?\\b");
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?i)\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s*(am|pm)?\\b");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern EMAIL_UPI_PATTERN = Pattern.compile(
            "(?i)\\b[\\w.%+.-]{2,}@[a-z0-9.-]{2,}\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+?91[-\\s]?)?[6-9]\\d{9}(?!\\d)");
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(?:otp|pin|cvv|password|passcode)\\b\\s*[:=-]?\\s*[a-z0-9]{3,12}");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "(?i)\\b(?:a/c|acct|account|card)\\s*(?:no\\.?|number)?\\s*[:x*#-]*\\s*(?:\\d[ -]?){6,19}\\b");
    private static final Pattern LONG_NUMBER_PATTERN = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern LONG_ID_PATTERN = Pattern.compile(
            "(?i)\\b(?=[a-z0-9-]{12,}\\b)(?=[a-z0-9-]*[a-z])(?=[a-z0-9-]*\\d)[a-z0-9-]+\\b");
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(?i)(?:₹|rs\\.?|inr)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?");
    private static final Pattern ACTION_WORDS = Pattern.compile(
            "(?i)\\b(?:pay|payment|bill|due|renew|renewal|recharge|submit|appointment|visit|collect|pickup|deliver|delivery|reminder|deadline|book|booking|verify|complete|भुगतान|बिल|देय|रिचार्ज|जमा|नवीनीकरण|अपॉइंटमेंट|याद|अंतिम|करें|करना)\\b");
    private static final Pattern URGENT_WORDS = Pattern.compile(
            "(?i)\\b(?:urgent|immediately|overdue|final reminder|last date|expires today|due today|तुरंत|अति आवश्यक|आज अंतिम|अंतिम तिथि|बकाया)\\b");

    static final class Suggestion {
        @NonNull final String title;
        final long dueAt;
        @NonNull final String priority;
        @NonNull final String fingerprint;
        final boolean actionable;
        final boolean detectedDueDate;

        Suggestion(@NonNull String title, long dueAt, @NonNull String priority,
                   @NonNull String fingerprint, boolean actionable,
                   boolean detectedDueDate) {
            this.title = title;
            this.dueAt = dueAt;
            this.priority = priority;
            this.fingerprint = fingerprint;
            this.actionable = actionable;
            this.detectedDueDate = detectedDueDate;
        }
    }

    private FamilyTaskSmsSuggestionParser() { }

    @NonNull
    static Suggestion parse(String rawText, long now) {
        String raw = rawText == null ? "" : rawText.trim();
        DueResult due = detectDue(raw, now);
        boolean actionable = ACTION_WORDS.matcher(raw).find();
        String title = sanitizeTitle(raw);
        if (title.isEmpty()) title = "Review shared message";
        String priority = detectPriority(raw, due, now);
        return new Suggestion(title, due.dueAt, priority,
                fingerprint(raw), actionable, due.detected);
    }

    @NonNull
    private static String sanitizeTitle(@NonNull String raw) {
        String value = raw.replace('\r', ' ').replace('\n', ' ');
        value = URL_PATTERN.matcher(value).replaceAll(" ");
        value = SECRET_PATTERN.matcher(value).replaceAll(" ");
        value = EMAIL_UPI_PATTERN.matcher(value).replaceAll(" ");
        value = PHONE_PATTERN.matcher(value).replaceAll(" ");
        value = MONEY_PATTERN.matcher(value).replaceAll(" ");
        value = ACCOUNT_PATTERN.matcher(value).replaceAll(" ");
        value = LONG_NUMBER_PATTERN.matcher(value).replaceAll(" ");
        value = LONG_ID_PATTERN.matcher(value).replaceAll(" ");
        value = value.replaceAll("(?i)^\\s*(?:dear customer|dear user|hello|hi|प्रिय ग्राहक|प्रिय उपभोक्ता)[, :.-]*", "");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.length() > 110) {
            int cut = sentenceCut(value, 110);
            value = value.substring(0, cut).trim();
        }
        return value;
    }

    private static int sentenceCut(@NonNull String value, int max) {
        int best = -1;
        for (char separator : new char[]{'.', ';', '!', '?'}) {
            int at = value.lastIndexOf(separator, max);
            if (at >= 28) best = Math.max(best, at);
        }
        if (best >= 28) return best;
        int space = value.lastIndexOf(' ', max);
        return space >= 40 ? space : Math.min(max, value.length());
    }

    @NonNull
    private static String detectPriority(@NonNull String raw,
                                         @NonNull DueResult due, long now) {
        if (URGENT_WORDS.matcher(raw).find()) return FamilyTask.PRIORITY_URGENT;
        if (due.detected && due.dueAt > 0L
                && due.dueAt <= now + 24L * 60L * 60L * 1000L) {
            return FamilyTask.PRIORITY_HIGH;
        }
        return FamilyTask.PRIORITY_NORMAL;
    }

    @NonNull
    private static DueResult detectDue(@NonNull String raw, long now) {
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(now);
        Calendar due = (Calendar) base.clone();
        due.set(Calendar.SECOND, 0);
        due.set(Calendar.MILLISECOND, 0);
        boolean detected = false;

        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("tomorrow") || raw.contains("कल")) {
            due.add(Calendar.DAY_OF_YEAR, 1);
            detected = true;
        } else if (lower.contains("today") || raw.contains("आज")) {
            detected = true;
        } else {
            Matcher dateMatcher = DATE_PATTERN.matcher(raw);
            if (dateMatcher.find()) {
                int day = parseInt(dateMatcher.group(1), due.get(Calendar.DAY_OF_MONTH));
                int month = parseInt(dateMatcher.group(2), due.get(Calendar.MONTH) + 1);
                String yearText = dateMatcher.group(3);
                int year = due.get(Calendar.YEAR);
                if (yearText != null && !yearText.trim().isEmpty()) {
                    year = parseInt(yearText, year);
                    if (year < 100) year += 2000;
                }
                Calendar candidate = (Calendar) due.clone();
                candidate.setLenient(false);
                try {
                    candidate.set(Calendar.YEAR, year);
                    candidate.set(Calendar.MONTH, month - 1);
                    candidate.set(Calendar.DAY_OF_MONTH, day);
                    candidate.getTimeInMillis();
                    if (yearText == null
                            && candidate.getTimeInMillis() < now - 12L * 60L * 60L * 1000L) {
                        candidate.add(Calendar.YEAR, 1);
                    }
                    due = candidate;
                    detected = true;
                } catch (RuntimeException ignored) { }
            }
        }

        int hour = 18;
        int minute = 0;
        Matcher timeMatcher = TIME_PATTERN.matcher(raw);
        if (timeMatcher.find()) {
            hour = parseInt(timeMatcher.group(1), 18);
            minute = parseInt(timeMatcher.group(2), 0);
            String amPm = timeMatcher.group(3);
            if (amPm != null) {
                if ("pm".equalsIgnoreCase(amPm) && hour < 12) hour += 12;
                if ("am".equalsIgnoreCase(amPm) && hour == 12) hour = 0;
            }
            detected = true;
        }
        due.set(Calendar.HOUR_OF_DAY, hour);
        due.set(Calendar.MINUTE, minute);

        if (!detected && due.getTimeInMillis() <= now + 15L * 60L * 1000L) {
            due.add(Calendar.DAY_OF_YEAR, 1);
        }
        return new DueResult(due.getTimeInMillis(), detected);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    @NonNull
    private static String fingerprint(@NonNull String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.substring(0, 24);
        } catch (Exception impossible) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static final class DueResult {
        final long dueAt;
        final boolean detected;
        DueResult(long dueAt, boolean detected) {
            this.dueAt = dueAt;
            this.detected = detected;
        }
    }
}
