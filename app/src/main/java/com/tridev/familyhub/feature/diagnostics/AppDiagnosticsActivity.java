package com.tridev.familyhub.feature.diagnostics;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.BuildConfig;
import com.tridev.familyhub.R;
import com.tridev.familyhub.backup.BackupPreferences;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.databinding.ActivityAppDiagnosticsBinding;
import com.tridev.familyhub.feature.grocery.overlay.GroceryOverlayService;
import com.tridev.familyhub.feature.main.MainActivity;
import com.tridev.familyhub.feature.profile.ProfileSettingsActivity;
import com.tridev.familyhub.location.LocationSharingStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-device readiness checks without uploading diagnostic or personal data. */
public class AppDiagnosticsActivity extends AppCompatActivity {
    private ActivityAppDiagnosticsBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int passed;
    private int required;
    private int checkGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppDiagnosticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.diagnosticsBack.setOnClickListener(v -> finish());
        binding.diagnosticsNotifications.setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_ROUTE,
                                MainActivity.ROUTE_REMINDERS)));
        binding.diagnosticsProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileSettingsActivity.class)));
        binding.diagnosticsRefresh.setOnClickListener(v -> runChecks());
        binding.diagnosticsVersion.setText(getString(R.string.diagnostics_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        runChecks();
    }

    private void runChecks() {
        int generation = ++checkGeneration;
        binding.diagnosticsChecksContainer.removeAllViews();
        binding.diagnosticsOverall.setText(R.string.diagnostics_checking);
        passed = 0;
        required = 0;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        addCheck(getString(R.string.diagnostics_account),
                user != null && user.isEmailVerified()
                        ? getString(R.string.diagnostics_account_ready)
                        : getString(R.string.diagnostics_account_attention),
                user != null && user.isEmailVerified(), true);
        boolean internet = hasInternet();
        addCheck(getString(R.string.diagnostics_network),
                internet ? getString(R.string.diagnostics_network_ready)
                        : getString(R.string.diagnostics_network_offline),
                internet, false);
        boolean notifications = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        addCheck(getString(R.string.diagnostics_notifications),
                notifications ? getString(R.string.diagnostics_permission_ready)
                        : getString(R.string.diagnostics_permission_missing),
                notifications, true);
        boolean location = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        addCheck(getString(R.string.diagnostics_location),
                location ? getString(R.string.diagnostics_permission_ready)
                        : getString(R.string.diagnostics_permission_missing),
                location, LocationSharingStore.isSharingEnabled(this));
        boolean sharingEnabled = LocationSharingStore.isSharingEnabled(this);
        boolean backgroundLocation = Build.VERSION.SDK_INT < 29
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        addCheck(getString(R.string.diagnostics_background_location),
                backgroundLocation ? getString(R.string.diagnostics_permission_ready)
                        : getString(R.string.diagnostics_permission_missing),
                backgroundLocation, sharingEnabled);
        boolean activityRecognition = Build.VERSION.SDK_INT < 29
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        addCheck(getString(R.string.diagnostics_activity_recognition),
                activityRecognition ? getString(R.string.diagnostics_permission_ready)
                        : getString(R.string.diagnostics_permission_missing),
                activityRecognition, sharingEnabled);
        boolean overlayEnabled = getSharedPreferences(GroceryOverlayService.PREFS,
                MODE_PRIVATE).getBoolean(GroceryOverlayService.KEY_ENABLED, false);
        boolean overlayReady = !overlayEnabled || Settings.canDrawOverlays(this);
        addCheck(getString(R.string.diagnostics_overlay),
                overlayReady ? getString(R.string.diagnostics_overlay_ready)
                        : getString(R.string.diagnostics_overlay_missing),
                overlayReady, overlayEnabled);
        BackupPreferences backup = new BackupPreferences(this);
        addCheck(getString(R.string.diagnostics_backup),
                backup.isReadyForAutomaticBackup()
                        ? lastBackupDetail(backup) : getString(R.string.diagnostics_backup_optional),
                backup.isReadyForAutomaticBackup(), false);
        executor.execute(() -> {
            boolean databaseReady;
            try {
                FamilyHubDatabase.getInstance(this).familyMemberDao().count();
                databaseReady = true;
            } catch (RuntimeException error) {
                databaseReady = false;
            }
            boolean finalReady = databaseReady;
            runOnUiThread(() -> {
                if (binding == null || generation != checkGeneration) return;
                addCheck(getString(R.string.diagnostics_database),
                        finalReady ? getString(R.string.diagnostics_database_ready)
                                : getString(R.string.diagnostics_database_error),
                        finalReady, true);
                renderOverall();
            });
        });
    }

    private void addCheck(String title, String detail, boolean ok, boolean mandatory) {
        if (mandatory) { required++; if (ok) passed++; }
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16)); card.setCardElevation(0); card.setStrokeWidth(dp(1));
        card.setStrokeColor(ContextCompat.getColor(this,
                ok ? R.color.fh_success : mandatory ? R.color.fh_error : R.color.fh_warning));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.fh_surface));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView dot = new TextView(this); dot.setText(ok ? "✓" : mandatory ? "!" : "i");
        dot.setTextSize(18); dot.setGravity(Gravity.CENTER); dot.setTextColor(ContextCompat.getColor(this,
                ok ? R.color.fh_success : mandatory ? R.color.fh_error : R.color.fh_warning));
        row.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f); labelsParams.leftMargin = dp(10);
        TextView heading = new TextView(this); heading.setText(title); heading.setTextSize(14); heading.setTextColor(ContextCompat.getColor(this, R.color.fh_on_surface)); heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        TextView description = new TextView(this); description.setText(detail); description.setTextSize(11); description.setTextColor(ContextCompat.getColor(this, R.color.fh_on_surface_variant));
        labels.addView(heading); labels.addView(description); row.addView(labels, labelsParams); card.addView(row);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8); binding.diagnosticsChecksContainer.addView(card, cardParams);
    }

    private void renderOverall() {
        binding.diagnosticsOverall.setText(passed == required
                ? R.string.diagnostics_ready : R.string.diagnostics_attention);
    }
    private boolean hasInternet() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private String lastBackupDetail(BackupPreferences backup) {
        if (backup.lastSuccessAt() <= 0) return getString(R.string.diagnostics_backup_ready);
        return getString(R.string.diagnostics_backup_last, new SimpleDateFormat(
                "dd MMM, hh:mm a", Locale.getDefault()).format(new Date(backup.lastSuccessAt())));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { executor.shutdownNow(); binding = null; super.onDestroy(); }
}
