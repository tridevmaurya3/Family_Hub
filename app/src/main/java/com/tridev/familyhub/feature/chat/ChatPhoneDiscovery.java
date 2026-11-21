package com.tridev.familyhub.feature.chat;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ChatPhoneDiscovery {
    private final FamilyChatFragment host;

    ChatPhoneDiscovery(FamilyChatFragment host) {
        this.host = host;
    }

    void showSearch() {
        if (!host.verifiedConnectionAccount()) {
            host.connectionNotice("Sign in with a verified Family Hub account");
            return;
        }
        String account = currentUser().getUid();
        ScrollView page = new ScrollView(host.requireContext());
        LinearLayout body = content(page);
        body.addView(label("Enter the complete number of a Family Hub user, including the country code."));
        EditText number = field("Full number, including country code", InputType.TYPE_CLASS_PHONE);
        number.setText("+91");
        body.addView(ChatFormDialog.outlined(number));
        MaterialButton find = button("Find account", body);
        LinearLayout profile = new LinearLayout(host.requireContext());
        profile.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView initials = label("");
        initials.setGravity(android.view.Gravity.CENTER);
        initials.setTextColor(0xff136650);
        android.graphics.drawable.GradientDrawable badge = new android.graphics.drawable.GradientDrawable();
        badge.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        badge.setColor(0xffd1eee1);
        initials.setBackground(badge);
        int avatarSize = Math.round(42 * host.getResources().getDisplayMetrics().density);
        profile.addView(initials, new LinearLayout.LayoutParams(avatarSize, avatarSize));
        initials.setVisibility(View.GONE);
        TextView result = label("");
        profile.addView(result, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(profile);
        MaterialButton request = button("Send request", body);
        request.setVisibility(View.GONE);

        MaterialButton settings = button("Mobile discovery settings", body);
        settings.setOnClickListener(view -> showSettings());
        MaterialButton invite = button("Share invite instead", body);
        invite.setOnClickListener(view -> host.showConnectionInvite(null));
        number.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                result.setText("");
                initials.setVisibility(View.GONE);
                request.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        find.setOnClickListener(view -> {
            String query = readNumber(number);
            if (query == null) return;
            FirebaseUser user = currentUser();
            if (user == null || !account.equals(user.getUid())) return;
            if (user.getPhoneNumber() == null) {
                showSettings();
                host.connectionNotice("Verify your number to search for contacts");
                return;
            }
            find.setEnabled(false);
            request.setVisibility(View.GONE);
            initials.setVisibility(View.GONE);
            result.setText("Searching…");
            call(page, account, "find", "number", query, data -> {
                find.setEnabled(true);
                if (!query.equals(normalized(number))) return;
                if (data == null) {
                    result.setText("");
                    return;
                }
                if (!"found".equals(data.get("outcome"))) {
                    result.setText("No discoverable account found");
                    return;
                }
                String name = String.valueOf(data.get("name"));
                if (!name.isEmpty()) {
                    initials.setText(name.substring(0, name.offsetByCodePoints(0, 1))
                            .toUpperCase(java.util.Locale.ROOT));
                    initials.setVisibility(View.VISIBLE);
                }
                result.setText(name);
                request.setVisibility(View.VISIBLE);
                request.setEnabled(true);
                request.setOnClickListener(tap -> {
                    if (!query.equals(normalized(number))) return;
                    request.setEnabled(false);
                    Map<String, Object> target = new HashMap<>();
                    target.put("number", query);
                    target.put("match", data.get("match"));
                    call(page, account, "request", target, response -> {
                        if (!query.equals(normalized(number))) return;
                        if (response == null) {
                            request.setEnabled(true);
                            return;
                        }
                        request.setVisibility(View.GONE);
                        initials.setVisibility(View.GONE);
                        Object outcome = response.get("outcome");
                        if ("connected".equals(outcome)) host.closeSecondaryPage();
                        else if ("incoming".equals(outcome)) host.showConnectionRequests();
                        else if ("pending".equals(outcome)) result.setText("Request sent");
                        else result.setText("This account is no longer discoverable or a request was recently declined");
                    });
                });
            });
        });
        host.showSecondaryPage("Find by mobile number", page, host::closeSecondaryPage,
                () -> signedInAs(account));
    }

    private void showSettings() {
        FirebaseUser user = currentUser();
        if (user == null) return;
        String account = user.getUid();
        ScrollView page = new ScrollView(host.requireContext());
        LinearLayout body = content(page);
        body.addView(label("Verify a number on your existing Family Hub account. Discovery is optional."));
        TextView verified = label("");
        body.addView(verified);
        EditText number = field("Full number, including country code", InputType.TYPE_CLASS_PHONE);
        number.setText("+91");
        body.addView(ChatFormDialog.outlined(number));
        MaterialButton sendOtp = button("Send verification code", body);
        EditText code = field("SMS verification code", InputType.TYPE_CLASS_NUMBER);
        View codeField = ChatFormDialog.outlined(code);
        body.addView(codeField);
        MaterialButton verify = button("Verify number", body);
        codeField.setVisibility(View.GONE);
        verify.setVisibility(View.GONE);
        MaterialSwitch consent = new MaterialSwitch(host.requireContext());
        consent.setText("Let people find me by my number");
        consent.setEnabled(false);
        body.addView(consent);
        boolean[] refreshing = {false};
        Runnable refresh = () -> {
            FirebaseUser active = currentUser();
            String phone = active == null ? null : active.getPhoneNumber();
            verified.setText(phone == null ? "No verified number"
                    : "Verified number ending in " + phone.substring(Math.max(0, phone.length() - 4)));
            consent.setEnabled(false);
            call(page, account, "status", null, null, data -> {
                if (data == null) return;
                refreshing[0] = true;
                consent.setChecked(Boolean.TRUE.equals(data.get("enabled")));
                consent.setEnabled(Boolean.TRUE.equals(data.get("verified")));
                refreshing[0] = false;
            });
        };
        consent.setOnCheckedChangeListener((view, enabled) -> {
            if (refreshing[0]) return;
            consent.setEnabled(false);
            call(page, account, "consent", "enabled", enabled, data -> refresh.run());
        });

        String[] verificationId = {null};
        boolean[] committing = {false};
        Consumer<PhoneAuthCredential> link = credential -> {
            if (!host.isSecondaryPage(page) || !signedInAs(account) || committing[0]) return;
            committing[0] = true;
            verify.setEnabled(false);
            sendOtp.setEnabled(false);
            FirebaseUser active = currentUser();
            com.google.android.gms.tasks.Task<?> task = active.getPhoneNumber() == null
                    ? active.linkWithCredential(credential) : active.updatePhoneNumber(credential);
            task.addOnCompleteListener(done -> {
                if (!host.isSecondaryPage(page) || !signedInAs(account)) return;
                committing[0] = false;
                verify.setEnabled(true);
                sendOtp.setEnabled(true);
                if (!done.isSuccessful()) {
                    code.setError("Could not verify this number. Check the code and try again.");
                    return;
                }
                codeField.setVisibility(View.GONE);
                verify.setVisibility(View.GONE);
                verificationId[0] = null;
                sendOtp.setText("Send verification code");
                refresh.run();
            });
        };
        sendOtp.setOnClickListener(view -> {
            String value = readNumber(number);
            if (value == null || !signedInAs(account)) return;
            sendOtp.setEnabled(false);
            PhoneAuthProvider.verifyPhoneNumber(PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                    .setPhoneNumber(value).setActivity(host.requireActivity())
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                        @Override public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                            link.accept(credential);
                        }

                        @Override public void onVerificationFailed(@NonNull FirebaseException error) {
                            if (!host.isSecondaryPage(page) || !signedInAs(account)) return;
                            sendOtp.setEnabled(true);
                            host.connectionNotice("Could not send an SMS verification code");
                        }

                        @Override public void onCodeSent(@NonNull String id,
                                                         @NonNull PhoneAuthProvider.ForceResendingToken token) {
                            if (!host.isSecondaryPage(page) || !signedInAs(account)) return;
                            verificationId[0] = id;
                            code.setText("");
                            codeField.setVisibility(View.VISIBLE);
                            verify.setVisibility(View.VISIBLE);
                            sendOtp.setText("Resend verification code");
                            sendOtp.setEnabled(!committing[0]);
                        }
                    }).build());
        });
        verify.setOnClickListener(view -> {
            String value = code.getText().toString().trim();
            if (verificationId[0] == null || !value.matches("[0-9]{6}")) {
                code.setError("Enter the 6-digit SMS code");
                return;
            }
            link.accept(PhoneAuthProvider.getCredential(verificationId[0], value));
        });
        host.showSecondaryPage("Mobile discovery", page, this::showSearch, () -> signedInAs(account));
        refresh.run();
    }

    private void call(ScrollView page, String account, String operation, String key, Object value,
                      Consumer<Map<?, ?>> done) {
        Map<String, Object> payload = new HashMap<>();
        if (key != null) payload.put(key, value);
        call(page, account, operation, payload, done);
    }

    private void call(ScrollView page, String account, String operation, Map<String, Object> payload,
                      Consumer<Map<?, ?>> done) {
        payload.put("operation", operation);
        FirebaseFunctions.getInstance().getHttpsCallable("connectionPhone").call(payload)
                .addOnCompleteListener(task -> {
                    if (!host.isSecondaryPage(page) || !signedInAs(account)) return;
                    Object data = task.isSuccessful() ? task.getResult().getData() : null;
                    if (!(data instanceof Map)) {
                        Exception error = task.getException();
                        String message = "Mobile discovery is temporarily unavailable";
                        if (error instanceof FirebaseFunctionsException) {
                            FirebaseFunctionsException.Code code = ((FirebaseFunctionsException) error).getCode();
                            if (code == FirebaseFunctionsException.Code.NOT_FOUND || code == FirebaseFunctionsException.Code.UNIMPLEMENTED) {
                                message = "Mobile discovery is not set up on Firebase.";
                            } else if (code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                                message = "Too many attempts. Try again later.";
                            } else if (code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                                message = "Verify your mobile number first.";
                            }
                        }
                        host.connectionNotice(message);
                        done.accept(null);
                    } else done.accept((Map<?, ?>) data);
                });
    }

    private LinearLayout content(ScrollView page) {
        LinearLayout body = new LinearLayout(host.requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        page.addView(body);
        return body;
    }

    private TextView label(String value) {
        TextView text = new TextView(host.requireContext());
        text.setText(value);
        text.setTextSize(15);
        int padding = Math.round(8 * host.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        return text;
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(host.requireContext());
        input.setHint(hint);
        input.setSingleLine();
        input.setInputType(type);
        return input;
    }

    private MaterialButton button(String title, LinearLayout body) {
        MaterialButton button = host.flatButton(title);
        body.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private static FirebaseUser currentUser() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    private static boolean signedInAs(String uid) {
        FirebaseUser user = currentUser();
        return user != null && uid.equals(user.getUid()) && user.isEmailVerified();
    }

    private static String normalized(EditText input) {
        return input.getText().toString().trim().replaceAll("[ ()-]", "");
    }

    private static String readNumber(EditText input) {
        String value = normalized(input);
        if (value.matches("\\+[1-9][0-9]{7,14}")) return value;
        input.setError("Enter the full number with its country code");
        return null;
    }
}
