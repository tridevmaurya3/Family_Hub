package com.tridev.familyhub.core.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.speech.RecognizerIntent;
import android.widget.Toast;
import com.tridev.familyhub.R;
import java.util.ArrayList;
import java.util.Locale;

/** Returns dictated text to the current form field; never creates or saves a record. */
public final class FormVoiceCaptureActivity extends Activity {
    private static final int REQUEST_SPEECH = 2107;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        speech.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.family_tasks_voice_add));
        try {
            startActivityForResult(speech, REQUEST_SPEECH);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.form_voice_unavailable, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != REQUEST_SPEECH) return;
        ResultReceiver receiver = getIntent().getParcelableExtra("receiver");
        if (receiver != null && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                Bundle result = new Bundle();
                result.putString("text", matches.get(0));
                receiver.send(1, result);
            }
        }
        finish();
    }
}
