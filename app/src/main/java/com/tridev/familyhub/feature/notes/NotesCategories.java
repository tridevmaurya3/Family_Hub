package com.tridev.familyhub.feature.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** User categories are local UI preferences; notes still use the existing category field. */
final class NotesCategories {
    static final String ADD = "+ Add new category";
    private static SharedPreferences store(Context context) {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        return context.getSharedPreferences("notes_categories_" + (user == null ? "local" : user.getUid()), Context.MODE_PRIVATE);
    }
    static List<String> labels(Context context) {
        List<String> values = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.notes_category_labels)));
        try {
            org.json.JSONArray saved = new org.json.JSONArray(store(context).getString("custom", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String value = saved.optString(i).trim();
                if (!value.isEmpty() && !values.contains(value)) values.add(value);
            }
        } catch (org.json.JSONException ignored) { }
        return values;
    }
    static AlertDialog prompt(Context context, boolean overlay, Consumer<String> saved) {
        EditText input = new EditText(context); input.setHint("Category name"); input.setSingleLine(true);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle("Add new category")
                .setView(input).setNegativeButton(R.string.cancel, null).setPositiveButton("Add", null).create();
        NotesEditor.present(dialog, overlay);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty() || ADD.equals(name)) { input.setError("Enter a category name"); return; }
            List<String> values = labels(context);
            String match = null;
            for (String value : values) if (value.equalsIgnoreCase(name)) { match = value; break; }
            if (match == null) {
                values.add(name);
                store(context).edit().putString("custom", new org.json.JSONArray(values).toString()).apply();
                match = name;
            }
            saved.accept(match); dialog.dismiss();
        });
        return dialog;
    }
}
