package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.text.InputFilter;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.tridev.familyhub.core.security.VaultCipher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Opt-in private text channel with a key exchanged in person. */
final class ChatSecureText {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int MAX_TEXT = 2000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChatSecureText() {}

    static void open(FamilyChatFragment host, FamilyChatStore store, FamilyChatStore.ThreadState thread) {
        String uid = store.accountId();
        String family = store.statusFamily();
        if (thread.isGroup() || uid == null || family == null || !store.canUse(thread)) return;
        String peer = thread.peer;
        if (uid.equals(peer)) return;
        String first = uid.compareTo(peer) < 0 ? uid : peer;
        String second = first.equals(uid) ? peer : uid;
        DatabaseReference messages = FirebaseDatabase.getInstance().getReference("familySecureText")
                .child(family).child(first).child(second);
        Context context = host.requireContext();
        android.content.SharedPreferences keys = context.getSharedPreferences("family_secure_text_keys", Context.MODE_PRIVATE);
        String localKey = family + "/" + uid + "/" + peer;
        try {
            String sealed = keys.getString(localKey, null);
            if (sealed == null) {
                setup(context, keys, localKey, () -> open(host, store, thread));
                return;
            }
            byte[] secret = Base64.decode(VaultCipher.decryptOrThrow(sealed), Base64.DEFAULT);
            openMessages(host, store, thread, family, uid, messages, new SecretKeySpec(secret, "AES"));
        } catch (Exception failure) {
            new ChatFormDialog(context).setTitle("Secure chat key unavailable")
                    .setMessage("Use the original device to access this secure chat.")
                    .setPositiveButton("Close", null).show();
        }
    }

    private static void setup(Context context, android.content.SharedPreferences keys, String localKey, Runnable onReady) {
        EditText input = new EditText(context);
        input.setHint("Shared secure chat key");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(44)});
        AlertDialog setup = new ChatFormDialog(context).setTitle("Set up secure text chat")
                .setMessage("Generate a key on one phone. In person, enter the same key on the other phone. Keep it private.")
                .setView(ChatFormDialog.outlined(input)).setNegativeButton("Cancel", null)
                .setNeutralButton("Generate key", null).setPositiveButton("Save key", null).show();
        setup.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setup.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            byte[] secret = new byte[KEY_BYTES];
            RANDOM.nextBytes(secret);
            input.setText(Base64.encodeToString(secret, Base64.NO_WRAP));
            input.setTransformationMethod(null);
        });
        setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String code = input.getText().toString().trim();
                byte[] secret = Base64.decode(code, Base64.DEFAULT);
                if (secret.length != KEY_BYTES) {
                    input.setError("Enter the complete 44-character key");
                    return;
                }
                String normalized = Base64.encodeToString(secret, Base64.NO_WRAP);
                if (!keys.edit().putString(localKey, VaultCipher.encrypt(normalized)).commit()) {
                    input.setError("Unable to save the key");
                    return;
                }
                setup.dismiss();
                onReady.run();
            } catch (RuntimeException invalid) {
                input.setError("Unable to save this key");
            }
        });
    }

    private static void openMessages(FamilyChatFragment host, FamilyChatStore store, FamilyChatStore.ThreadState thread,
                                     String family, String uid, DatabaseReference messages, SecretKeySpec secret) {
        Context context = host.requireContext();
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        TextView history = new TextView(context);
        history.setPadding(20, 12, 20, 12);
        scroll.addView(history);
        form.addView(scroll, new LinearLayout.LayoutParams(-1,
                Math.round(240 * context.getResources().getDisplayMetrics().density)));
        EditText input = new EditText(context);
        input.setHint("Secure message");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEXT)});
        form.addView(ChatFormDialog.outlined(input));
        AlertDialog screen = new ChatFormDialog(context).setTitle("Secure text · " + store.title(thread))
                .setView(form).setNegativeButton("Close", null).setPositiveButton("Send", null).show();
        ChatSendButton.stylePositive(screen);
        screen.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        com.google.firebase.database.Query recent = messages.limitToLast(50);
        com.google.firebase.database.ValueEventListener listener = new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                if (!screen.isShowing()) return;
                if (!uid.equals(store.accountId()) || !family.equals(store.statusFamily()) || !store.canUse(thread)) {
                    screen.dismiss();
                    return;
                }
                StringBuilder text = new StringBuilder();
                for (com.google.firebase.database.DataSnapshot row : snapshot.getChildren()) {
                    String sender = row.child("sender").getValue(String.class);
                    String encoded = row.child("ciphertext").getValue(String.class);
                    if (sender == null || encoded == null) continue;
                    text.append(sender.equals(uid) ? "You" : store.name(sender)).append("\n");
                    try {
                        text.append(decrypt(secret, associated(row.getRef(), sender), encoded));
                    } catch (Exception invalid) {
                        text.append("Unable to decrypt with this key");
                    }
                    text.append("\n\n");
                }
                history.setText(text.length() == 0 ? "No secure messages yet" : text.toString().trim());
                scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
            }
            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) {
                history.setText("Secure chat unavailable");
            }
        };
        androidx.lifecycle.Lifecycle lifecycle = host.getViewLifecycleOwner().getLifecycle();
        androidx.lifecycle.DefaultLifecycleObserver observer = new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                screen.dismiss();
            }
        };
        lifecycle.addObserver(observer);
        screen.setOnDismissListener(d -> {
            recent.removeEventListener(listener);
            lifecycle.removeObserver(observer);
        });
        recent.addValueEventListener(listener);
        screen.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!uid.equals(store.accountId()) || !family.equals(store.statusFamily()) || !store.canUse(thread)) return;
            String body = input.getText().toString().trim();
            if (body.isEmpty()) return;
            DatabaseReference target = messages.push();
            Map<String, Object> value = new LinkedHashMap<>();
            try {
                value.put("ciphertext", encrypt(secret, associated(target, uid), body));
            } catch (Exception failure) {
                input.setError("Unable to encrypt message");
                return;
            }
            value.put("sender", uid);
            value.put("at", ServerValue.TIMESTAMP);
            v.setEnabled(false);
            target.setValue(value).addOnCompleteListener(task -> {
                if (!screen.isShowing()) return;
                v.setEnabled(true);
                if (task.isSuccessful()) {
                    if (body.equals(input.getText().toString().trim())) input.setText("");
                } else input.setError("Unable to send secure message");
            });
        });
    }

    private static byte[] associated(DatabaseReference path, String sender) {
        return (path.toString() + "\n" + sender).getBytes(StandardCharsets.UTF_8);
    }

    private static String encrypt(SecretKeySpec key, byte[] aad, String body) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        byte[] sealed = cipher.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                .put(nonce).put(sealed).array(), Base64.NO_WRAP);
    }

    private static String decrypt(SecretKeySpec key, byte[] aad, String encoded) throws Exception {
        byte[] payload = Base64.decode(encoded, Base64.DEFAULT);
        if (payload.length < NONCE_BYTES + 16) throw new IllegalArgumentException("Invalid message");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload, 0, NONCE_BYTES));
        cipher.updateAAD(aad);
        return new String(cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES), StandardCharsets.UTF_8);
    }
}
