package com.tridev.familyhub.core.tasks;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.tridev.familyhub.R;

/** Small permission bridge: an overlay Service cannot display a runtime permission prompt. */
public final class FamilyTaskReminderPermissionActivity extends AppCompatActivity {
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (getIntent().getBooleanExtra("test", false)) FamilyTaskReceiver.testNotification(this,
                            getIntent().getBooleanExtra("important", false));
                    FamilyTaskScheduler.rescheduleAll(this, () -> { });
                } else Toast.makeText(this, R.string.task_reminder_permission_required, Toast.LENGTH_LONG).show();
                finish();
            });
    public static boolean needsPermission(Context context) {
        return Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }
    public static void request(Context context, boolean test, boolean important) {
        context.startActivity(new Intent(context, FamilyTaskReminderPermissionActivity.class)
                .putExtra("test", test).putExtra("important", important).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    public static void requestOnce(Context context) {
        if (needsPermission(context) && !context.getSharedPreferences("family_task_reminder_access", MODE_PRIVATE)
                .getBoolean("requested", false)) request(context, false, false);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        if (!needsPermission(this)) { finish(); return; }
        boolean asked = getSharedPreferences("family_task_reminder_access", MODE_PRIVATE).getBoolean("requested", false);
        if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (settings.resolveActivity(getPackageManager()) != null) startActivity(settings);
            finish(); return;
        }
        getSharedPreferences("family_task_reminder_access", MODE_PRIVATE).edit().putBoolean("requested", true).apply();
        permission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
}
