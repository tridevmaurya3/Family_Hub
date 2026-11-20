package com.tridev.familyhub.feature.chat;

import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ChatStatus {
    private final FamilyChatFragment host;
    private final FamilyChatStore store;
    private AlertDialog dialog;

    ChatStatus(FamilyChatFragment host, FamilyChatStore store) {
        this.host = host;
        this.store = store;
    }

    void open() {
        String uid = store.accountId();
        String family = store.statusFamily();
        if (uid == null || family == null) return;
        DatabaseReference statuses = FirebaseDatabase.getInstance().getReference("familyTextStatus").child(family);
        LinearLayout rows = new LinearLayout(host.requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * rows.getResources().getDisplayMetrics().density);
        rows.setPadding(padding, 0, padding, padding);
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(rows);
        dialog = new ChatFormDialog(host.requireContext()).setTitle("Family Status").setView(scroll)
                .setNeutralButton("Remove mine", (d, w) -> {
                    if (active(family, uid)) statuses.child(uid).removeValue()
                            .addOnFailureListener(error -> notice("Unable to remove status"));
                })
                .setNegativeButton("Close", null)
                .setPositiveButton("Post text", (d, w) -> {
                    if (active(family, uid)) compose(statuses.child(uid), family, uid);
                }).show();
        AlertDialog listing = dialog;
        Map<String, String> members = new LinkedHashMap<>();
        members.put(uid, "You");
        members.putAll(store.groupCandidates());
        for (Map.Entry<String, String> member : members.entrySet()) {
            statuses.child(member.getKey()).get().addOnSuccessListener(snapshot -> {
                if (!listing.isShowing() || !active(family, uid)) return;
                String body = snapshot.child("body").getValue(String.class);
                Long postedAt = snapshot.child("postedAt").getValue(Long.class);
                if (body == null || postedAt == null) return;
                long remaining = postedAt + 86400000L - System.currentTimeMillis();
                if (remaining <= 0) return;
                TextView text = new TextView(rows.getContext());
                text.setText(member.getValue() + "\n" + body);
                text.setTextIsSelectable(true);
                text.setPadding(0, padding / 2, 0, padding / 2);
                rows.addView(text);
                rows.postDelayed(() -> rows.removeView(text), remaining);
            });
        }
    }

    private void compose(DatabaseReference path, String family, String uid) {
        EditText input = new EditText(host.requireContext());
        input.setHint("Write a status");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        dialog = new ChatFormDialog(host.requireContext()).setTitle("New text status")
                .setMessage("Visible to active family members for 24 hours. This replaces your previous status.")
                .setView(ChatFormDialog.outlined(input))
                .setNegativeButton("Cancel", null).setPositiveButton("Post", null).show();
        AlertDialog editor = dialog;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!active(family, uid)) return;
            String body = input.getText().toString().trim();
            if (body.isEmpty()) { input.setError("Enter a status"); return; }
            view.setEnabled(false);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("body", body);
            value.put("postedAt", ServerValue.TIMESTAMP);
            path.setValue(value).addOnCompleteListener(task -> {
                if (!editor.isShowing()) return;
                if (task.isSuccessful()) editor.dismiss();
                else { view.setEnabled(true); notice("Unable to publish status"); }
            });
        });
    }

    private boolean active(String family, String uid) {
        return host.isAdded() && host.getView() != null
                && Objects.equals(family, store.statusFamily()) && Objects.equals(uid, store.accountId());
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void notice(String message) {
        if (host.isAdded()) Toast.makeText(host.requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
