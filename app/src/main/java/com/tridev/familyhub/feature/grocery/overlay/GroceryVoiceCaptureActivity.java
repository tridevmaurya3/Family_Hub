package com.tridev.familyhub.feature.grocery.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;

import java.util.ArrayList;

/** Small permission/result bridge that keeps voice capture reliable from an overlay. */
public class GroceryVoiceCaptureActivity extends AppCompatActivity {
    public static final String ACTION_RESULT =
            "com.tridev.familyhub.action.GROCERY_VOICE_RESULT";
    public static final String EXTRA_RESULT = "grocery_voice_result";
    private static final int REQUEST_AUDIO = 9021;
    private static final int REQUEST_SPEECH = 9022;
    private boolean launched;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) launched = savedInstanceState.getBoolean("launched");
        if (!launched) beginCapture();
    }

    private void beginCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        launchRecognizer();
    }

    private void launchRecognizer() {
        launched = true;
        Intent voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                .putExtra(RecognizerIntent.EXTRA_PROMPT,
                        getString(R.string.grocery_overlay_voice));
        try {
            startActivityForResult(voice, REQUEST_SPEECH);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.grocery_overlay_voice_unavailable,
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchRecognizer();
        } else {
            Toast.makeText(this, R.string.grocery_overlay_voice_permission,
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
            @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SPEECH && resultCode == Activity.RESULT_OK
                && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                sendBroadcast(new Intent(ACTION_RESULT)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_RESULT, results.get(0)));
            }
        }
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("launched", launched);
        super.onSaveInstanceState(outState);
    }
}
