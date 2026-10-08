package com.tridev.familyhub.feature.profile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.R;
import com.tridev.familyhub.core.ThemeModeController;
import com.tridev.familyhub.feature.auth.AuthActivity;
import com.tridev.familyhub.feature.familyaccount.FamilyManagementActivity;
import com.tridev.familyhub.feature.main.MainActivity;

/** Account profile, local avatar and app preference screen. */
public final class ProfileSettingsActivity extends AppCompatActivity {

    private ImageView profilePhoto;
    private TextInputEditText nameInput;
    private TextView emailView;
    private TextView familyView;
    private TextView roleView;
    private ProgressBar progress;
    private MaterialSwitch darkThemeSwitch;
    private com.google.android.material.button.MaterialButton editFamilyButton;
    private com.google.firebase.database.DatabaseReference familyReference;
    private com.google.firebase.database.ValueEventListener familyListener;
    private String currentFamilyId = "";
    private String profileUid = "";
    private boolean canEditFamily;
    private boolean nameInitialized;

    private final ActivityResultLauncher<String> photoPicker =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) {
                            return;
                        }
                        if (ProfilePhotoStore.save(this, uri)) {
                            renderProfilePhoto();
                        } else {
                            Toast.makeText(
                                    this,
                                    R.string.profile_photo_error,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
            );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_profile_settings);
        com.tridev.familyhub.core.ui.CompactFormStyle.applyInputs(findViewById(android.R.id.content));
        applySystemBarInsets();

        profilePhoto = findViewById(R.id.imageProfilePhoto);
        nameInput = findViewById(R.id.inputProfileName);
        emailView = findViewById(R.id.textProfileEmail);
        familyView = findViewById(R.id.textProfileFamily);
        roleView = findViewById(R.id.textProfileRole);
        editFamilyButton = findViewById(R.id.buttonProfileEditFamily);
        editFamilyButton.setOnClickListener(v -> editFamilyName());
        progress = findViewById(R.id.progressProfile);
        darkThemeSwitch = findViewById(R.id.switchProfileDarkTheme);

        findViewById(R.id.buttonProfileBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonProfileNotifications).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_ROUTE,
                                MainActivity.ROUTE_REMINDERS)));
        findViewById(R.id.buttonProfileHeaderProfile).setOnClickListener(v ->
                findViewById(R.id.inputProfileName).requestFocus());
        findViewById(R.id.buttonProfileChangePhoto).setOnClickListener(v ->
                photoPicker.launch("image/*"));
        findViewById(R.id.buttonProfileRemovePhoto).setOnClickListener(v -> {
            ProfilePhotoStore.remove(this);
            renderProfilePhoto();
            Toast.makeText(
                    this,
                    R.string.profile_photo_removed,
                    Toast.LENGTH_SHORT
            ).show();
        });
        findViewById(R.id.buttonProfileSave).setOnClickListener(v ->
                saveProfile());
        findViewById(R.id.cardProfileNotifications).setOnClickListener(v ->
                openNotificationSettings());
        findViewById(R.id.cardProfileFamilySettings).setOnClickListener(v ->
                startActivity(new Intent(
                        this,
                        FamilyManagementActivity.class
                )));
        findViewById(R.id.buttonProfileLogout).setOnClickListener(v ->
                confirmLogout());

        prepareThemeSwitch();
        renderProfilePhoto();
    }

    @Override
    protected void onResume() {
        super.onResume();
        attachStableThemeListener();
        loadProfile();
    }

    @Override
    protected void onPause() {
        if (darkThemeSwitch != null) {
            darkThemeSwitch.setOnCheckedChangeListener(null);
        }
        super.onPause();
    }

    @Override protected void onStop() {
        detachFamilyListener();
        super.onStop();
    }

    private void detachFamilyListener() {
        if (familyReference != null && familyListener != null)
            familyReference.removeEventListener(familyListener);
        familyReference = null;
        familyListener = null;
        canEditFamily = false;
        if (editFamilyButton != null) editFamilyButton.setVisibility(View.GONE);
    }

    private void applySystemBarInsets() {
        View content = findViewById(android.R.id.content);
        int initialLeft = content.getPaddingLeft();
        int initialTop = content.getPaddingTop();
        int initialRight = content.getPaddingRight();
        int initialBottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(
                    initialLeft + safe.left,
                    initialTop + safe.top,
                    initialRight + safe.right,
                    initialBottom + safe.bottom
            );
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /**
     * Never allow Android view-state restoration to become the theme source of truth.
     * Listener is intentionally attached only in onResume(), after hierarchy restore.
     */
    private void prepareThemeSwitch() {
        darkThemeSwitch.setSaveEnabled(false);
        darkThemeSwitch.setSaveFromParentEnabled(false);
        darkThemeSwitch.setOnCheckedChangeListener(null);
        darkThemeSwitch.setChecked(ThemeModeController.isDarkEnabled(this));
        darkThemeSwitch.setEnabled(true);
    }

    private void attachStableThemeListener() {
        if (darkThemeSwitch == null) return;
        darkThemeSwitch.setOnCheckedChangeListener(null);
        darkThemeSwitch.setChecked(ThemeModeController.isDarkEnabled(this));
        darkThemeSwitch.setEnabled(true);
        darkThemeSwitch.setOnCheckedChangeListener((button, enabled) -> {
            button.setEnabled(false);
            boolean changed = ThemeModeController.requestMode(this, enabled);
            if (!changed) {
                button.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(ThemeModeController.isDarkEnabled(this));
                    button.setEnabled(true);
                    attachStableThemeListener();
                });
            }
        });
    }

    private void renderProfilePhoto() {
        Bitmap bitmap = ProfilePhotoStore.load(this);
        if (bitmap == null) {
            profilePhoto.setPadding(dp(24), dp(24), dp(24), dp(24));
            profilePhoto.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            profilePhoto.setImageResource(R.drawable.ic_profile_person);
            return;
        }
        profilePhoto.setPadding(0, 0, 0, 0);
        profilePhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profilePhoto.setImageBitmap(bitmap);
    }

    private void loadProfile() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            redirectToAuth();
            return;
        }

        detachFamilyListener();
        currentFamilyId = "";
        profileUid = user.getUid();
        String displayName = user.getDisplayName();
        if (!nameInitialized) {
            nameInput.setText(displayName == null ? "" : displayName.trim());
            nameInitialized = true;
        }
        emailView.setText(safeText(user.getEmail()));
        progress.setVisibility(View.VISIBLE);

        FirebaseDatabase.getInstance().getReference()
                .child("users")
                .child(user.getUid())
                .get()
                .addOnSuccessListener(userSnapshot -> {
                    String familyId = stringValue(
                            userSnapshot.child("familyId")
                    );
                    if (familyId.isEmpty()) {
                        progress.setVisibility(View.GONE);
                        familyView.setText(R.string.profile_unknown);
                        roleView.setText(R.string.profile_unknown);
                        return;
                    }
                    loadMembership(user, familyId);
                })
                .addOnFailureListener(error -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(
                            this,
                            R.string.profile_load_error,
                            Toast.LENGTH_LONG
                    ).show();
                });
    }

    private void loadMembership(
            @NonNull FirebaseUser user,
            @NonNull String familyId
    ) {
        FirebaseDatabase.getInstance().getReference()
                .child("memberships")
                .child(familyId)
                .child(user.getUid())
                .get()
                .addOnSuccessListener(membership -> {
                    String memberName = stringValue(
                            membership.child("displayName")
                    );
                    if ((nameInput.getText() == null
                            || nameInput.getText().toString().trim().isEmpty())
                            && !memberName.isEmpty()) {
                        nameInput.setText(memberName);
                    }
                    roleView.setText(readableRole(stringValue(
                            membership.child("role")
                    )));
                    loadFamilyName(familyId);
                })
                .addOnFailureListener(error -> {
                    progress.setVisibility(View.GONE);
                    roleView.setText(R.string.profile_unknown);
                    familyView.setText(familyId);
                });
    }

    private void loadFamilyName(@NonNull String familyId) {
        if (isFinishing() || isDestroyed() || !getLifecycle().getCurrentState()
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
        detachFamilyListener();
        currentFamilyId = familyId;
        familyReference = FirebaseDatabase.getInstance().getReference()
                .child("families").child(familyId);
        familyListener = new com.google.firebase.database.ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                String name = stringValue(snapshot.child("name"));
                familyView.setText(name.isEmpty() ? familyId : name);
                canEditFamily = profileUid.equals(stringValue(snapshot.child("ownerUid")));
                editFamilyButton.setVisibility(canEditFamily ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                progress.setVisibility(View.GONE);
                canEditFamily = false;
                editFamilyButton.setVisibility(View.GONE);
                familyView.setText(R.string.profile_unknown);
                Toast.makeText(ProfileSettingsActivity.this, R.string.profile_load_error, Toast.LENGTH_LONG).show();
            }
        };
        familyReference.addValueEventListener(familyListener);
    }

    private void editFamilyName() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (!canEditFamily || currentFamilyId.isEmpty() || user == null
                || !profileUid.equals(user.getUid())) return;
        final String familyId = currentFamilyId;
        com.google.android.material.textfield.TextInputLayout field =
                new com.google.android.material.textfield.TextInputLayout(this);
        field.setHint(getString(R.string.profile_family));
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        field.addView(input, new android.widget.LinearLayout.LayoutParams(-1, -2));
        field.setPadding(dp(20), dp(8), dp(20), 0);
        input.setText(familyView.getText());
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_edit_family_name).setView(field)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.profile_family_name_save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (name.length() < 2 || name.length() > 60) {
                        field.setError(getString(R.string.profile_family_name_length));
                        return;
                    }
                    FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
                    if (!canEditFamily || !familyId.equals(currentFamilyId) || current == null
                            || !profileUid.equals(current.getUid())) return;
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    FirebaseDatabase.getInstance().getReference().child("families")
                            .child(familyId).child("name").setValue(name)
                            .addOnSuccessListener(unused -> {
                                dialog.dismiss();
                                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                            })
                            .addOnFailureListener(error -> {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                field.setError(getString(R.string.profile_save_error));
                            });
                }));
        dialog.show();
    }

    private void saveProfile() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            redirectToAuth();
            return;
        }
        String displayName = nameInput.getText() == null
                ? ""
                : nameInput.getText().toString().trim();
        if (displayName.isEmpty()) {
            nameInput.setError(getString(R.string.profile_name_required));
            nameInput.requestFocus();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        findViewById(R.id.buttonProfileSave).setEnabled(false);
        UserProfileChangeRequest request = new UserProfileChangeRequest.Builder()
                .setDisplayName(displayName)
                .build();
        user.updateProfile(request)
                .addOnSuccessListener(unused -> {
                    progress.setVisibility(View.GONE);
                    findViewById(R.id.buttonProfileSave).setEnabled(true);
                    Toast.makeText(
                            this,
                            R.string.profile_saved,
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .addOnFailureListener(error -> {
                    progress.setVisibility(View.GONE);
                    findViewById(R.id.buttonProfileSave).setEnabled(true);
                    Toast.makeText(
                            this,
                            R.string.profile_save_error,
                            Toast.LENGTH_LONG
                    ).show();
                });
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_logout)
                .setMessage(R.string.profile_logout_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.profile_logout,
                        (dialog, which) -> logout())
                .show();
    }

    private void logout() {
        FirebaseAuth.getInstance().signOut();
        redirectToAuth();
    }

    private void redirectToAuth() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @NonNull
    private String safeText(@Nullable String value) {
        return value == null || value.trim().isEmpty()
                ? getString(R.string.profile_unknown)
                : value.trim();
    }

    @NonNull
    private String readableRole(@NonNull String role) {
        if (role.isEmpty()) {
            return getString(R.string.profile_unknown);
        }
        String value = role.toLowerCase().replace('_', ' ');
        StringBuilder output = new StringBuilder(value.length());
        boolean capitalize = true;
        for (char character : value.toCharArray()) {
            if (capitalize && Character.isLetter(character)) {
                output.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                output.append(character);
            }
            if (character == ' ') {
                capitalize = true;
            }
        }
        return output.toString();
    }

    @NonNull
    private static String stringValue(@NonNull DataSnapshot snapshot) {
        String value = snapshot.getValue(String.class);
        return value == null ? "" : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

