from pathlib import Path
import re

root = Path('.')
frag_java = root / 'app/src/main/java/com/tridev/familyhub/feature/tasks/FamilyTasksFragment.java'
overlay_java = root / 'app/src/main/java/com/tridev/familyhub/feature/tasks/overlay/FamilyTaskOverlayService.java'
frag_xml = root / 'app/src/main/res/layout/fragment_family_tasks.xml'
dialog_xml = root / 'app/src/main/res/layout/dialog_family_task.xml'
manifest = root / 'app/src/main/AndroidManifest.xml'


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 anchor, found {count}')
    return text.replace(old, new, 1)

# Main To-Do: waveform directly above Quick Add textbox.
text = frag_xml.read_text(encoding='utf-8')
old = '''        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/task_quick_add_layout"
            style="@style/Widget.FamilyHub.FloatingCompact.Field"
            android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_weight="1" android:hint="@string/family_tasks_quick_add_hint"
            app:startIconDrawable="@drawable/ic_family_task" app:startIconTint="@color/fh_module_grocery"
            app:endIconMode="custom" app:endIconDrawable="@drawable/ic_mic"
            app:endIconContentDescription="@string/family_tasks_voice_add"
            app:endIconTint="@color/fh_module_grocery">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/task_quick_add_input"
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:imeOptions="actionDone" android:inputType="textCapSentences"
                android:maxLines="1" android:textSize="12.5sp" />
        </com.google.android.material.textfield.TextInputLayout>'''
new = '''        <LinearLayout
            android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_weight="1" android:orientation="vertical">
            <com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView
                android:id="@+id/task_quick_voice_wave"
                android:layout_width="match_parent" android:layout_height="26dp"
                android:layout_marginStart="10dp" android:layout_marginEnd="10dp"
                android:layout_marginBottom="2dp" android:visibility="gone" />
            <com.google.android.material.textfield.TextInputLayout
                android:id="@+id/task_quick_add_layout"
                style="@style/Widget.FamilyHub.FloatingCompact.Field"
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:hint="@string/family_tasks_quick_add_hint"
                app:startIconDrawable="@drawable/ic_family_task" app:startIconTint="@color/fh_module_grocery"
                app:endIconMode="custom" app:endIconDrawable="@drawable/ic_mic"
                app:endIconContentDescription="@string/family_tasks_voice_add"
                app:endIconTint="@color/fh_module_grocery">
                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/task_quick_add_input"
                    android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:imeOptions="actionDone" android:inputType="textCapSentences"
                    android:maxLines="1" android:textSize="12.5sp" />
            </com.google.android.material.textfield.TextInputLayout>
        </LinearLayout>'''
text = replace_once(text, old, new, 'main quick-add input')
frag_xml.write_text(text, encoding='utf-8')

# Add/Edit form: same waveform immediately above Task Name.
text = dialog_xml.read_text(encoding='utf-8')
anchor = '''                <com.google.android.material.textfield.TextInputLayout
                    android:id="@+id/task_title_layout"'''
inserted = '''                <com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView
                    android:id="@+id/task_editor_voice_wave"
                    android:layout_width="match_parent"
                    android:layout_height="26dp"
                    android:layout_marginStart="10dp"
                    android:layout_marginEnd="10dp"
                    android:layout_marginBottom="2dp"
                    android:visibility="gone" />

''' + anchor
text = replace_once(text, anchor, inserted, 'editor title field')
dialog_xml.write_text(text, encoding='utf-8')

# Main/Add/Edit recognizer keeps its current logic, with shared red waveform state.
text = frag_java.read_text(encoding='utf-8')
import_anchor = 'import com.tridev.familyhub.feature.tasks.overlay.FamilyTaskOverlayService;\n'
text = replace_once(text, import_anchor,
                    import_anchor + 'import com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView;\n',
                    'fragment import')
fields = '''    @Nullable private android.widget.EditText pendingVoiceTarget;
    @Nullable private SpeechRecognizer speechRecognizer;'''
fields_new = '''    @Nullable private android.widget.EditText pendingVoiceTarget;
    @Nullable private TaskVoiceWaveView pendingVoiceWave;
    @Nullable private TaskVoiceWaveView activeVoiceWave;
    @Nullable private SpeechRecognizer speechRecognizer;'''
text = replace_once(text, fields, fields_new, 'fragment fields')
text = replace_once(text,
                    'if (granted && pendingVoiceTarget != null) startVoiceCapture(pendingVoiceTarget);',
                    'if (granted && pendingVoiceTarget != null && pendingVoiceWave != null)\n                    startVoiceCapture(pendingVoiceTarget, pendingVoiceWave);',
                    'permission callback')
text = replace_once(text,
                    'requestVoiceCapture(binding.taskQuickAddInput));',
                    'requestVoiceCapture(binding.taskQuickAddInput, binding.taskQuickVoiceWave));',
                    'main mic listener')
text = replace_once(text,
                    'form.taskTitleLayout.setEndIconOnClickListener(v -> requestVoiceCapture(form.taskTitleInput));',
                    'form.taskTitleLayout.setEndIconOnClickListener(v ->\n                requestVoiceCapture(form.taskTitleInput, form.taskEditorVoiceWave));',
                    'editor mic listener')
pattern = re.compile(r'''    private void requestVoiceCapture\(@NonNull android\.widget\.EditText target\) \{.*?\n    \}\n\n    private boolean applyVoiceResult''', re.S)
replacement = '''    private void requestVoiceCapture(@NonNull android.widget.EditText target,
                                     @NonNull TaskVoiceWaveView wave) {
        pendingVoiceTarget = target;
        pendingVoiceWave = wave;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startVoiceCapture(target, wave);
    }

    private void startVoiceCapture(@NonNull android.widget.EditText target,
                                   @NonNull TaskVoiceWaveView wave) {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            wave.stopListening();
            android.widget.Toast.makeText(requireContext(),
                    R.string.family_tasks_voice_unavailable, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        stopVoiceCapture();
        pendingVoiceTarget = target;
        pendingVoiceWave = wave;
        activeVoiceWave = wave;
        wave.startListening();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { wave.startListening(); }
            @Override public void onBeginningOfSpeech() { wave.startListening(); }
            @Override public void onRmsChanged(float rmsdB) { wave.setLevel(rmsdB); }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { wave.showProcessing(); }
            @Override public void onError(int error) {
                stopVoiceCapture();
                toast(error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? R.string.family_tasks_voice_no_match
                        : R.string.family_tasks_voice_unavailable);
            }
            @Override public void onResults(Bundle results) {
                boolean added = applyVoiceResult(results, target);
                stopVoiceCapture();
                toast(added ? R.string.family_tasks_voice_added
                        : R.string.family_tasks_voice_no_match);
            }
            @Override public void onPartialResults(Bundle results) {
                applyVoiceResult(results, target);
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechRecognizer.startListening(intent);
    }

    private boolean applyVoiceResult'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f'fragment voice method region: {count}')
stop_old = '''    private void stopVoiceCapture() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        pendingVoiceTarget = null;
    }'''
stop_new = '''    private void stopVoiceCapture() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (activeVoiceWave != null) activeVoiceWave.stopListening();
        activeVoiceWave = null;
        pendingVoiceWave = null;
        pendingVoiceTarget = null;
    }'''
text = replace_once(text, stop_old, stop_new, 'fragment stop voice')
frag_java.write_text(text, encoding='utf-8')

# Floating To-Do: use transparent foreground Activity for reliable microphone access.
text = overlay_java.read_text(encoding='utf-8')
text = replace_once(text, 'import android.content.Intent;\n',
                    'import android.content.BroadcastReceiver;\nimport android.content.Context;\nimport android.content.Intent;\nimport android.content.IntentFilter;\n',
                    'overlay imports')
import_anchor = 'import com.tridev.familyhub.feature.main.MainActivity;\n'
text = replace_once(text, import_anchor,
                    import_anchor + 'import com.tridev.familyhub.feature.tasks.voice.TaskVoiceCaptureActivity;\nimport com.tridev.familyhub.feature.tasks.voice.TaskVoiceWaveView;\n',
                    'overlay voice imports')
fields = '''    @Nullable private TextView voiceStatus;
    @Nullable private Button priorityCollapseButton;
    @Nullable private android.speech.SpeechRecognizer speechRecognizer;'''
fields_new = '''    @Nullable private TextView voiceStatus;
    @Nullable private TaskVoiceWaveView voiceWave;
    @Nullable private EditText voiceInput;
    @Nullable private ImageButton voiceButton;
    private boolean voiceCaptureActive;
    @Nullable private Button priorityCollapseButton;
    @Nullable private android.speech.SpeechRecognizer speechRecognizer;
    private final BroadcastReceiver taskVoiceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !TaskVoiceCaptureActivity.ACTION_EVENT.equals(intent.getAction())) return;
            String state = intent.getStringExtra(TaskVoiceCaptureActivity.EXTRA_STATE);
            if (TaskVoiceCaptureActivity.STATE_LISTENING.equals(state)) {
                voiceCaptureActive = true;
                if (voiceWave != null) voiceWave.startListening();
                if (voiceButton != null) voiceButton.setColorFilter(Color.rgb(220, 45, 82));
            } else if (TaskVoiceCaptureActivity.STATE_RMS.equals(state)) {
                if (voiceWave != null) voiceWave.setLevel(
                        intent.getFloatExtra(TaskVoiceCaptureActivity.EXTRA_RMS, 0f));
            } else if (TaskVoiceCaptureActivity.STATE_PROCESSING.equals(state)) {
                if (voiceWave != null) voiceWave.showProcessing();
            } else if (TaskVoiceCaptureActivity.STATE_PARTIAL.equals(state)) {
                String spoken = intent.getStringExtra(TaskVoiceCaptureActivity.EXTRA_TEXT);
                if (voiceInput != null && spoken != null && !spoken.trim().isEmpty()) {
                    voiceInput.setText(spoken.trim());
                    voiceInput.setSelection(voiceInput.length());
                }
            } else if (TaskVoiceCaptureActivity.STATE_RESULT.equals(state)) {
                String spoken = intent.getStringExtra(TaskVoiceCaptureActivity.EXTRA_TEXT);
                if (voiceInput != null && spoken != null && !spoken.trim().isEmpty()) {
                    voiceInput.setText(spoken.trim());
                    voiceInput.setSelection(voiceInput.length());
                }
                finishVoiceUi();
            } else if (TaskVoiceCaptureActivity.STATE_ERROR.equals(state)) {
                finishVoiceUi();
                android.widget.Toast.makeText(FamilyTaskOverlayService.this,
                        R.string.family_tasks_voice_no_match, android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    };'''
text = replace_once(text, fields, fields_new, 'overlay fields')
create_anchor = '''        repository = new FamilyTaskRepository(this);
        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {'''
create_new = '''        repository = new FamilyTaskRepository(this);
        androidx.core.content.ContextCompat.registerReceiver(this, taskVoiceReceiver,
                new IntentFilter(TaskVoiceCaptureActivity.ACTION_EVENT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        repository.startRealtimeSync(new FamilyTaskRepository.RealtimeCallback() {'''
text = replace_once(text, create_anchor, create_new, 'receiver registration')
text = replace_once(text, '        LinearLayout quick = row();\n',
                    '''        voiceWave = new TaskVoiceWaveView(this);
        voiceWave.setVisibility(View.GONE);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(-1, dp(26));
        waveParams.leftMargin = dp(10);
        waveParams.rightMargin = dp(10);
        waveParams.bottomMargin = dp(2);
        root.addView(voiceWave, waveParams);

        LinearLayout quick = row();
''', 'overlay wave placement')
text = replace_once(text,
                    '''        voice.setBackgroundColor(Color.TRANSPARENT);
        voice.setElevation(0f);''',
                    '''        voice.setBackgroundColor(Color.TRANSPARENT);
        voice.setElevation(0f);
        voiceInput = input;
        voiceButton = voice;''',
                    'overlay voice references')
old_status = '''        voiceStatus = text("", 10f, true);
        voiceStatus.setTextColor(Color.rgb(15, 105, 80));
        voiceStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        voiceStatus.setPadding(dp(6), 0, dp(6), 0);
        voiceStatus.setVisibility(View.GONE);
        root.addView(voiceStatus, new LinearLayout.LayoutParams(-1, dp(22)));

'''
text = replace_once(text, old_status, '', 'old overlay status')
pattern = re.compile(r'''    private void startVoiceCapture\(@NonNull EditText input, @NonNull ImageButton voice\) \{.*?\n    \}\n\n    private boolean applyVoiceResult''', re.S)
replacement = '''    private void startVoiceCapture(@NonNull EditText input, @NonNull ImageButton voice) {
        if (voiceCaptureActive) return;
        voiceInput = input;
        voiceButton = voice;
        voiceCaptureActive = true;
        voice.setColorFilter(Color.rgb(220, 45, 82));
        if (voiceWave != null) voiceWave.startListening();
        try {
            startActivity(new Intent(this, TaskVoiceCaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (RuntimeException error) {
            finishVoiceUi();
            android.widget.Toast.makeText(this, R.string.family_tasks_voice_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void finishVoiceUi() {
        voiceCaptureActive = false;
        if (voiceWave != null) voiceWave.stopListening();
        if (voiceButton != null) voiceButton.setColorFilter(Color.rgb(15, 105, 80));
    }

    private boolean applyVoiceResult'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f'overlay voice method region: {count}')
text = replace_once(text,
                    '''    private void stopVoiceCapture() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }''',
                    '''    private void stopVoiceCapture() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        finishVoiceUi();
    }''',
                    'overlay stop voice')
text = replace_once(text,
                    '''        liveStatus = null;
        voiceStatus = null;
        priorityCollapseButton = null;''',
                    '''        liveStatus = null;
        voiceStatus = null;
        voiceWave = null;
        voiceInput = null;
        voiceButton = null;
        priorityCollapseButton = null;''',
                    'overlay clear refs')
text = replace_once(text,
                    '''        if (repository != null) repository.stopRealtimeSync();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();''',
                    '''        if (repository != null) repository.stopRealtimeSync();
        try { unregisterReceiver(taskVoiceReceiver); } catch (IllegalArgumentException ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();''',
                    'overlay unregister')
overlay_java.write_text(text, encoding='utf-8')

# Register transparent bridge Activity only; do not change service FGS type.
text = manifest.read_text(encoding='utf-8')
activity_anchor = '''        <activity
            android:name=".feature.grocery.overlay.GroceryVoiceCaptureActivity"
            android:exported="false"
            android:theme="@style/Theme.FamilyHub.VoiceCapture" />'''
activity_new = activity_anchor + '''
        <activity
            android:name=".feature.tasks.voice.TaskVoiceCaptureActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:noHistory="true"
            android:theme="@style/Theme.FamilyHub.VoiceCapture" />'''
text = replace_once(text, activity_anchor, activity_new, 'manifest activity')
manifest.write_text(text, encoding='utf-8')

print('To-Do voice patch applied successfully.')
