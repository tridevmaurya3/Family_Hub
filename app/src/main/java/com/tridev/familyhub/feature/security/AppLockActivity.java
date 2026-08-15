package com.tridev.familyhub.feature.security;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.AppSecurityStore;
import com.tridev.familyhub.core.security.FamilyHubAppLockManager;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.familyaccount.FamilySetupActivity;

/** Full-screen Family Hub App Lock gate. */
public final class AppLockActivity extends AppCompatActivity {

    private static final String EXTRA_FROM_SPLASH = "family_hub_lock_from_splash";

    private TextInputEditText pinInput;
    private TextView statusView;
    private MaterialButton biometricButton;
    private boolean fromSplash;
    private boolean biometricPromptShowing;

    @NonNull
    public static Intent intentForOverlay(@NonNull Context context) {
        return new Intent(context, AppLockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @NonNull
    public static Intent intentFromSplash(@NonNull Context context) {
        return new Intent(context, AppLockActivity.class)
                .putExtra(EXTRA_FROM_SPLASH, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        fromSplash = getIntent().getBooleanExtra(EXTRA_FROM_SPLASH, false);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            redirectToAuth();
            return;
        }
        if (!AppSecurityStore.isProtectionEnabled(this)) {
            finishUnlock();
            return;
        }

        setContentView(buildContent());
        pinInput.requestFocus();
        if (canUseBiometric() && AppSecurityStore.isBiometricEnabled(this)) {
            pinInput.postDelayed(this::showBiometricPrompt, 250L);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        fromSplash = fromSplash || intent.getBooleanExtra(EXTRA_FROM_SPLASH, false);
    }

    @NonNull
    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_page_three_tone);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(44), dp(24), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialCardView iconCard = new MaterialCardView(this);
        iconCard.setRadius(dp(34));
        iconCard.setCardElevation(0f);
        iconCard.setCardBackgroundColor(getColor(R.color.fh_primary_container));
        iconCard.setStrokeColor(getColor(R.color.fh_primary));
        iconCard.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams iconCardParams = new LinearLayout.LayoutParams(dp(68), dp(68));
        iconCard.setLayoutParams(iconCardParams);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setColorFilter(getColor(R.color.fh_primary));
        icon.setPadding(dp(18), dp(18), dp(18), dp(18));
        iconCard.addView(icon);
        root.addView(iconCard);

        TextView title = text("Family Hub locked", 24, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = wrapParams();
        titleParams.topMargin = dp(18);
        root.addView(title, titleParams);

        String email = AppSecurityStore.currentUserEmail();
        TextView subtitle = text(
                email.isEmpty()
                        ? "Unlock to continue"
                        : "Protected account • " + email,
                12, false);
        subtitle.setTextColor(getColor(R.color.fh_text_secondary));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = wrapParams();
        subtitleParams.topMargin = dp(5);
        root.addView(subtitle, subtitleParams);

        MaterialCardView formCard = new MaterialCardView(this);
        formCard.setRadius(dp(20));
        formCard.setCardElevation(0f);
        formCard.setCardBackgroundColor(getColor(R.color.fh_surface));
        formCard.setStrokeColor(getColor(R.color.fh_outline_variant));
        formCard.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams formCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        formCardParams.topMargin = dp(24);
        formCard.setLayoutParams(formCardParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(18), dp(18), dp(18));
        formCard.addView(form);

        TextInputLayout pinLayout = new TextInputLayout(this);
        pinLayout.setHint("4-digit PIN");
        pinLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        pinInput = new TextInputEditText(this);
        pinInput.setSingleLine(true);
        pinInput.setMaxLines(1);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pinInput.setMaxEms(4);
        pinLayout.addView(pinInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(pinLayout);

        statusView = text("Enter your PIN, use fingerprint, or verify your account password.",
                11, false);
        statusView.setTextColor(getColor(R.color.fh_text_secondary));
        LinearLayout.LayoutParams statusParams = wrapParams();
        statusParams.topMargin = dp(8);
        form.addView(statusView, statusParams);

        MaterialButton unlock = button("Unlock with PIN", true);
        LinearLayout.LayoutParams actionParams = matchParams(dp(50));
        actionParams.topMargin = dp(14);
        form.addView(unlock, actionParams);

        biometricButton = button("Use fingerprint / biometric", false);
        LinearLayout.LayoutParams biometricParams = matchParams(dp(50));
        biometricParams.topMargin = dp(8);
        form.addView(biometricButton, biometricParams);
        biometricButton.setEnabled(canUseBiometric()
                && AppSecurityStore.isBiometricEnabled(this));

        MaterialButton password = button("Use User ID / password", false);
        LinearLayout.LayoutParams passwordParams = matchParams(dp(50));
        passwordParams.topMargin = dp(8);
        form.addView(password, passwordParams);

        root.addView(formCard);

        TextView privacy = text(
                "Your Firebase password is verified securely and is never stored by Family Hub.",
                10, false);
        privacy.setTextColor(getColor(R.color.fh_text_secondary));
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = wrapParams();
        privacyParams.topMargin = dp(14);
        root.addView(privacy, privacyParams);

        unlock.setOnClickListener(v -> verifyPin());
        pinInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                verifyPin();
                return true;
            }
            return false;
        });
        biometricButton.setOnClickListener(v -> showBiometricPrompt());
        password.setOnClickListener(v -> showPasswordFallback());
        return scroll;
    }

    private void verifyPin() {
        long remaining = AppSecurityStore.remainingPinLockoutMillis(this);
        if (remaining > 0L) {
            statusView.setText("Too many incorrect attempts. Try again in "
                    + Math.max(1L, (remaining + 999L) / 1000L) + " seconds.");
            statusView.setTextColor(getColor(R.color.fh_error));
            return;
        }
        String pin = pinInput.getText() == null
                ? "" : pinInput.getText().toString().trim();
        if (!pin.matches("\\d{4}")) {
            pinInput.setError("Enter a 4-digit PIN");
            return;
        }
        if (!AppSecurityStore.verifyPin(this, pin)) {
            AppSecurityStore.recordPinFailure(this);
            pinInput.setText(null);
            statusView.setText("Incorrect PIN. You can also use fingerprint or account password.");
            statusView.setTextColor(getColor(R.color.fh_error));
            return;
        }
        AppSecurityStore.resetPinFailures(this);
        finishUnlock();
    }

    private boolean canUseBiometric() {
        return BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void showBiometricPrompt() {
        if (biometricPromptShowing || !canUseBiometric()
                || !AppSecurityStore.isBiometricEnabled(this)) return;
        biometricPromptShowing = true;
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        biometricPromptShowing = false;
                        AppSecurityStore.resetPinFailures(AppLockActivity.this);
                        finishUnlock();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        biometricPromptShowing = false;
                        if (statusView != null) {
                            statusView.setText("Biometric not completed. PIN and account password remain available.");
                            statusView.setTextColor(getColor(R.color.fh_text_secondary));
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        if (statusView != null) {
                            statusView.setText("Fingerprint not recognized. Try again or use PIN.");
                            statusView.setTextColor(getColor(R.color.fh_error));
                        }
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Family Hub")
                .setSubtitle("Confirm your fingerprint or device biometric")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Use PIN")
                .build();
        prompt.authenticate(info);
    }

    private void showPasswordFallback() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String email = user == null ? "" : safe(user.getEmail());
        if (user == null || email.isEmpty()) {
            Toast.makeText(this, "Email/password verification is unavailable for this account.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint("Password");
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int padding = dp(20);
        layout.setPadding(padding, dp(8), padding, 0);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Verify account")
                .setMessage("User ID: " + email + "\nEnter your Family Hub account password.")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Verify", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = input.getText() == null
                    ? "" : input.getText().toString();
            if (password.isEmpty()) {
                layout.setError("Password is required");
                return;
            }
            layout.setError(null);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setEnabled(false);
            user.reauthenticate(EmailAuthProvider.getCredential(email, password))
                    .addOnCompleteListener(this, task -> {
                        input.setText(null);
                        if (!task.isSuccessful()) {
                            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(true);
                            layout.setError("Password verification failed");
                            return;
                        }
                        dialog.dismiss();
                        AppSecurityStore.resetPinFailures(this);
                        finishUnlock();
                    });
        }));
        dialog.show();
    }

    private void finishUnlock() {
        FamilyHubAppLockManager.markUnlocked();
        if (fromSplash) {
            Intent intent = new Intent(this, FamilySetupActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finish();
    }

    private void redirectToAuth() {
        FamilyHubAppLockManager.markSignedOut();
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private MaterialButton button(String label, boolean primary) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(13f);
        button.setCornerRadius(dp(22));
        if (!primary) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.fh_primary_container)));
            button.setTextColor(getColor(R.color.fh_primary));
        }
        return button;
    }

    private TextView text(String value, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(R.color.fh_text_primary));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchParams(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
