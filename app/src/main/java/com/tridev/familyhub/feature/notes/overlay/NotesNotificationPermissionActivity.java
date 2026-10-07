package com.tridev.familyhub.feature.notes.overlay;

import android.app.Activity;
import android.Manifest;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;

public final class NotesNotificationPermissionActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("notes_audio", false)) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 5311);
            else finishAudio(true);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5310);
        else finish();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 5311) finishAudio(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED);
        else finish();
    }
    private void finishAudio(boolean granted) {
        android.os.ResultReceiver receiver = getIntent().getParcelableExtra("receiver");
        if (receiver != null) receiver.send(granted ? 1 : 0, new Bundle());
        finish();
    }
}
