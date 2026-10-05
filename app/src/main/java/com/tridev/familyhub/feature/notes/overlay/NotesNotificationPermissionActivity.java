package com.tridev.familyhub.feature.notes.overlay;

import android.app.Activity;
import android.Manifest;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;

public final class NotesNotificationPermissionActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5310);
        else finish();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results); finish();
    }
}
