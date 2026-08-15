package com.tridev.familyhub.feature.security;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.security.AppSecurityStore;
import com.tridev.familyhub.core.security.FamilyHubAppLockManager;
import com.tridev.familyhub.feature.auth.AuthActivity;

/** App Lock settings for the currently signed-in Family Hub account. */
public final class AppSecurityActivity extends AppCompatActivity {

    private MaterialSwitch lockSwitch;
    private MaterialSwitch biometricSwitch;
    private TextView statusView;
    private TextView biometricStatusView;
    private MaterialButton changePinButton;
    private MaterialButton lockNowButton;
    private final MaterialButton[] timeoutButtons = new MaterialButton[4];
    private final int[] timeoutMinutes = {1, 2, 5, 10};
    private boolean refreshing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            redirectToAuth();
            return;
        }
        setContentView(buildContent());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lockSwitch != null) refresh();
    }

    @NonNull
    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_page_three_tone);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = button("‹", false);
        back.setMinWidth(0);
        back.setPadding(0, 0, 0, 0);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headerTextParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerTextParams.leftMargin = dp(10);
        header.addView(headerText, headerTextParams);
        headerText.addView(text("App Security", 21, true));
        TextView subtitle = text("PIN • Biometric • Account verification • Auto-lock", 11, false);
        subtitle.setTextColor(getColor(R.color.fh_text_secondary));
        headerText.addView(subtitle);
        root.addView(header);
        back.setOnClickListener(v -> finish());

        String email = AppSecurityStore.currentUserEmail();
        TextView account = text(email.isEmpty()
                ? "Current Family Hub account"
                : "Protected account: " + email, 11, true);
        account.setTextColor(getColor(R.color.fh_primary));
        LinearLayout.LayoutParams accountParams = wrapParams();
        accountParams.topMargin = dp(14);
        root.addView(account, accountParams);

        MaterialCardView lockCard = card(R.color.fh_surface, R.color.fh_outline_variant);
        LinearLayout lockContent = content(lockCard);
        lockSwitch = new MaterialSwitch(this);
        lockSwitch.setText("App Lock");
        lockSwitch.setTextSize(15f);
        lockSwitch.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lockContent.addView(lockSwitch, matchWrap());
        statusView = text("", 11, false);
        statusView.setTextColor(getColor(R.color.fh_text_secondary));
        lockContent.addView(statusView, wrapParams());
        changePinButton = button("Set / Change 4-digit PIN", false);
        LinearLayout.LayoutParams pinParams = matchParams(dp(48));
        pinParams.topMargin = dp(12);
        lockContent.addView(changePinButton, pinParams);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(14);
        root.addView(lockCard, cardParams);

        MaterialCardView bioCard = card(R.color.fh_primary_container, R.color.fh_primary);
        LinearLayout bioContent = content(bioCard);
        biometricSwitch = new MaterialSwitch(this);
        biometricSwitch.setText("Fingerprint / Biometric unlock");
        biometricSwitch.setTextSize(14f);
        biometricSwitch.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bioContent.addView(biometricSwitch, matchWrap());
        biometricStatusView = text("", 10, false);
        biometricStatusView.setTextColor(getColor(R.color.fh_text_secondary));
        bioContent.addView(biometricStatusView, wrapParams());
        LinearLayout.LayoutParams bioParams = matchWrap();
        bioParams.topMargin = dp(10);
        root.addView(bioCard, bioParams);

        TextView timerHeading = text("Inactivity Auto-Lock", 15, true);
        LinearLayout.LayoutParams timerHeadingParams = wrapParams();
        timerHeadingParams.topMargin = dp(22);
        root.addView(timerHeading, timerHeadingParams);
        TextView timerDetail = text(
                "Touch, typing and scrolling restart the timer. Time spent in the background also counts.",
                10, false);
        timerDetail.setTextColor(getColor(R.color.fh_text_secondary));
        root.addView(timerDetail, wrapParams());

        MaterialCardView timerCard = card(R.color.fh_info_container, R.color.fh_info);
        LinearLayout timerContent = content(timerCard);
        LinearLayout rowOne = new LinearLayout(this);
        rowOne.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout rowTwo = new LinearLayout(this);
        rowTwo.setOrientation(LinearLayout.HORIZONTAL);
        timerContent.addView(rowOne, matchWrap());
        LinearLayout.LayoutParams rowTwoParams = matchWrap();
        rowTwoParams.topMargin = dp(8);
        timerContent.addView(rowTwo, rowTwoParams);
        for (int i = 0; i < timeoutMinutes.length; i++) {
            int minutes = timeoutMinutes[i];
            MaterialButton choice = button(minutes + (minutes == 1 ? " minute" : " minutes"), false);
            timeoutButtons[i] = choice;
            choice.setOnClickListener(v -> {
                AppSecurityStore.setTimeoutMinutes(this, minutes);
                FamilyHubAppLockManager.markUnlocked();
                refresh();
            });
            LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(
                    0, dp(48), 1f);
            choiceParams.setMargins(dp(3), 0, dp(3), 0);
            (i < 2 ? rowOne : rowTwo).addView(choice, choiceParams);
        }
        LinearLayout.LayoutParams timerCardParams = matchWrap();
        timerCardParams.topMargin = dp(10);
        root.addView(timerCard, timerCardParams);

        MaterialCardView fallbackCard = card(R.color.fh_secondary_container, R.color.fh_secondary);
        LinearLayout fallbackContent = content(fallbackCard);
        TextView fallbackTitle = text("User ID / Password fallback", 14, true);
        fallbackTitle.setTextColor(getColor(R.color.fh_secondary));
        fallbackContent.addView(fallbackTitle);
        TextView fallback = text(
                "If PIN or fingerprint is unavailable, the lock screen can securely re-verify your current Firebase email and password. The password is never stored.",
                10, false);
        fallback.setTextColor(getColor(R.color.fh_text_secondary));
        LinearLayout.LayoutParams fallbackTextParams = wrapParams();
        fallbackTextParams.topMargin = dp(4);
        fallbackContent.addView(fallback, fallbackTextParams);
        LinearLayout.LayoutParams fallbackParams = matchWrap();
        fallbackParams.topMargin = dp(14);
        root.addView(fallbackCard, fallbackParams);

        lockNowButton = button("Lock Family Hub now", true);
        LinearLayout.LayoutParams lockNowParams = matchParams(dp(52));
        lockNowParams.topMargin = dp(18);
        root.addView(lockNowButton, lockNowParams);

        lockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (checked) enableProtection();
            else disableProtection();
        });
        biometricSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (checked && !biometricAvailable()) {
                Toast.makeText(this,
                        "No enrolled fingerprint/biometric is available on this device.",
                        Toast.LENGTH_LONG).show();
                refresh();
                return;
            }
            AppSecurityStore.setBiometricEnabled(this, checked);
            refresh();
        });
        changePinButton.setOnClickListener(v -> showPinDialog());
        lockNowButton.setOnClickListener(v -> FamilyHubAppLockManager.forceLock(this));
        return scroll;
    }

    private void enableProtection() {
        if (AppSecurityStore.hasPin(this)) {
            if (AppSecurityStore.setEnabled(this, true)) {
                FamilyHubAppLockManager.markUnlocked();
                refresh();
            } else refresh();
            return;
        }
        showPinDialog();
    }

    private void disableProtection() {
        if (!AppSecurityStore.isProtectionEnabled(this)) {
            refresh();
            return;
        }
        showCurrentPinConfirmation(() -> {
            AppSecurityStore.setEnabled(this, false);
            AppSecurityStore.setBiometricEnabled(this, false);
            FamilyHubAppLockManager.markUnlocked();
            refresh();
            Toast.makeText(this, "App Lock disabled", Toast.LENGTH_SHORT).show();
        });
    }

    private void showPinDialog() {
        boolean existing = AppSecurityStore.hasPin(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        TextInputLayout currentLayout = pinLayout("Current PIN");
        TextInputEditText currentInput = pinEdit();
        currentLayout.addView(currentInput);
        if (existing) form.addView(currentLayout, matchWrap());

        TextInputLayout newLayout = pinLayout(existing ? "New 4-digit PIN" : "Create 4-digit PIN");
        TextInputEditText newInput = pinEdit();
        newLayout.addView(newInput);
        LinearLayout.LayoutParams newParams = matchWrap();
        newParams.topMargin = existing ? dp(8) : 0;
        form.addView(newLayout, newParams);

        TextInputLayout confirmLayout = pinLayout("Confirm PIN");
        TextInputEditText confirmInput = pinEdit();
        confirmLayout.addView(confirmInput);
        LinearLayout.LayoutParams confirmParams = matchWrap();
        confirmParams.topMargin = dp(8);
        form.addView(confirmLayout, confirmParams);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing ? "Change App Lock PIN" : "Create App Lock PIN")
                .setMessage("Use exactly four digits. The PIN is encrypted using Android Keystore.")
                .setView(form)
                .setNegativeButton("Cancel", (d, which) -> refresh())
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String current = value(currentInput);
            String fresh = value(newInput);
            String confirm = value(confirmInput);
            if (existing && !AppSecurityStore.verifyPin(this, current)) {
                currentLayout.setError("Current PIN is incorrect");
                return;
            }
            currentLayout.setError(null);
            if (!fresh.matches("\\d{4}")) {
                newLayout.setError("Enter exactly 4 digits");
                return;
            }
            newLayout.setError(null);
            if (!fresh.equals(confirm)) {
                confirmLayout.setError("PINs do not match");
                return;
            }
            confirmLayout.setError(null);
            if (!AppSecurityStore.savePin(this, fresh)) {
                newLayout.setError("PIN could not be saved securely");
                return;
            }
            AppSecurityStore.setEnabled(this, true);
            FamilyHubAppLockManager.markUnlocked();
            dialog.dismiss();
            refresh();
            Toast.makeText(this, "App Lock PIN saved", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private void showCurrentPinConfirmation(@NonNull Runnable success) {
        TextInputLayout layout = pinLayout("Current 4-digit PIN");
        TextInputEditText input = pinEdit();
        layout.addView(input);
        layout.setPadding(dp(20), dp(8), dp(20), 0);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Confirm App Lock PIN")
                .setView(layout)
                .setNegativeButton("Cancel", (d, which) -> refresh())
                .setPositiveButton("Confirm", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!AppSecurityStore.verifyPin(this, value(input))) {
                layout.setError("Incorrect PIN");
                return;
            }
            dialog.dismiss();
            success.run();
        }));
        dialog.show();
    }

    private void refresh() {
        refreshing = true;
        boolean protectedNow = AppSecurityStore.isProtectionEnabled(this);
        lockSwitch.setChecked(protectedNow);
        statusView.setText(protectedNow
                ? "Active • locks after " + AppSecurityStore.getTimeoutMinutes(this)
                        + " minute(s) without activity."
                : "Off • enable App Lock to protect Family Hub when unattended.");
        statusView.setTextColor(getColor(protectedNow ? R.color.fh_success : R.color.fh_text_secondary));
        changePinButton.setText(AppSecurityStore.hasPin(this)
                ? "Change 4-digit PIN" : "Create 4-digit PIN");

        boolean available = biometricAvailable();
        biometricSwitch.setEnabled(protectedNow && available);
        biometricSwitch.setChecked(protectedNow && available
                && AppSecurityStore.isBiometricEnabled(this));
        biometricStatusView.setText(available
                ? (protectedNow
                    ? "Uses the fingerprint/biometric already enrolled on this phone."
                    : "Enable App Lock first, then biometric unlock can be switched on.")
                : "No enrolled fingerprint/biometric is currently available on this device.");

        int selected = AppSecurityStore.getTimeoutMinutes(this);
        for (int i = 0; i < timeoutButtons.length; i++) {
            styleTimeout(timeoutButtons[i], timeoutMinutes[i] == selected);
        }
        lockNowButton.setEnabled(protectedNow);
        refreshing = false;
    }

    private boolean biometricAvailable() {
        return BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void styleTimeout(@Nullable MaterialButton button, boolean selected) {
        if (button == null) return;
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(
                selected ? R.color.fh_primary : R.color.fh_surface)));
        button.setTextColor(getColor(selected ? R.color.fh_white : R.color.fh_primary));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(getColor(R.color.fh_primary)));
    }

    private TextInputLayout pinLayout(String hint) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        return layout;
    }

    private TextInputEditText pinEdit() {
        TextInputEditText input = new TextInputEditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setGravity(Gravity.CENTER);
        return input;
    }

    private MaterialCardView card(int background, int stroke) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(getColor(background));
        card.setStrokeColor(getColor(stroke));
        card.setStrokeWidth(dp(1));
        return card;
    }

    private LinearLayout content(MaterialCardView card) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(content);
        return content;
    }

    private MaterialButton button(String label, boolean primary) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setCornerRadius(dp(22));
        button.setTextSize(12f);
        if (!primary) {
            button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.fh_primary_container)));
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchParams(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String value(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void redirectToAuth() {
        FamilyHubAppLockManager.markSignedOut();
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
