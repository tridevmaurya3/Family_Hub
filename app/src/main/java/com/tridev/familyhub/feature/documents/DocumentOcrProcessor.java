package com.tridev.familyhub.feature.documents;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tridev.familyhub.data.local.entity.DocumentEntry;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline on-device OCR enrichment for image documents. */
public final class DocumentOcrProcessor {

    public interface Callback {
        void onComplete(boolean textDetected);
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:document|id|licen[cs]e|policy|passport|certificate|account|registration|reg)"
                    + "(?:\\s+(?:no|number))?\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9/-]{4,24})");

    private DocumentOcrProcessor() {
    }

    public static void enrich(
            @NonNull Context context,
            @NonNull DocumentEntry document,
            @NonNull Callback callback
    ) {
        if (!document.mimeType.toLowerCase(Locale.ENGLISH).startsWith("image/")) {
            callback.onComplete(false);
            return;
        }
        try {
            InputImage image = InputImage.fromFilePath(
                    context, Uri.parse(document.contentUri));
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener(result -> {
                        String text = normalize(result.getText());
                        if (!text.isEmpty()) {
                            document.searchableText = merge(document.searchableText, text);
                            if (document.documentNumber.trim().isEmpty()) {
                                Matcher matcher = NUMBER_PATTERN.matcher(text);
                                if (matcher.find() && matcher.group(1) != null) {
                                    document.documentNumber = matcher.group(1).trim();
                                }
                            }
                            if ("Other".equalsIgnoreCase(document.category)) {
                                document.category = suggestedCategory(text);
                            }
                        }
                        callback.onComplete(!text.isEmpty());
                    })
                    .addOnFailureListener(error -> callback.onComplete(false));
        } catch (Exception error) {
            callback.onComplete(false);
        }
    }

    @NonNull
    private static String suggestedCategory(@NonNull String value) {
        String text = value.toLowerCase(Locale.ENGLISH);
        if (contains(text, "aadhaar", "passport", "identity", "pan card", "driving licence")) return "Identity";
        if (contains(text, "insurance", "policy", "premium")) return "Insurance";
        if (contains(text, "vehicle", "registration certificate", "chassis", "engine no")) return "Vehicle";
        if (contains(text, "hospital", "patient", "diagnostic", "medical")) return "Health";
        if (contains(text, "marksheet", "school", "university", "roll no")) return "Education";
        if (contains(text, "bank", "account", "statement", "ifsc")) return "Bank & Finance";
        if (contains(text, "property", "deed", "plot", "registry")) return "Property";
        return "Other";
    }

    private static boolean contains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    @NonNull
    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    @NonNull
    private static String merge(String first, String second) {
        String left = normalize(first);
        if (left.isEmpty()) return second;
        if (left.contains(second)) return left;
        return left + " " + second;
    }
}
