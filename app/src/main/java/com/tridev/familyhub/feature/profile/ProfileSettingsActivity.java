package com.tridev.familyhub.feature.profile;

import android.content.Intent;
import android.content.res.Configuration;
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
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.tridev.familyhub.R;
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
        setContentView(R.layout.activity_profile_settings);

        profilePhoto = findViewById(R.id.imageProfilePhoto);
        nameInput = findViewById(R.id.inputProfileName);
        emailView = findViewById(R.id.textProfileEmail);
        familyView = findViewById(R.id.textProfileFamily);
        roleView = findViewById(R.id.textProfileRole);
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

        boolean darkThemeEnabled =
                (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        darkThemeSwitch.setChecked(darkThemeEnabled);
        darkThemeSwitch.setOnCheckedChangeListener((button, enabled) ->
                AppCompatDelegate.setDefaultNightMode(
                        enabled
                                ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_NO
                ));

        renderProfilePhoto();
        loadProfile();
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

        String displayName = user.getDisplayName();
        nameInput.setText(displayName == null ? "" : displayName.trim());
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
        FirebaseDatabase.getInstance().getReference()
                .child("families")
                .child(familyId)
                .child("name")
                .get()
                .addOnSuccessListener(snapshot -> {
                    progress.setVisibility(View.GONE);
                    String name = stringValue(snapshot);
                    familyView.setText(name.isEmpty() ? familyId : name);
                })
                .addOnFailureListener(error -> {
                    progress.setVisibility(View.GONE);
                    familyView.setText(familyId);
                });
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
