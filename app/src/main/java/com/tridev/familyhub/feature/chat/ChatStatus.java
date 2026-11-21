package com.tridev.familyhub.feature.chat;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ChatStatus {
    private static final int INK = 0xff1f2a31;
    private static final int GREEN = 0xff0f6c59;
    private static final int MUTED = 0xff4c626c;
    private static final int BLUE = 0xff0f6cbd;
    private static final int EDGE = 0xffc7d5dc;

    private final FamilyChatFragment host;
    private final FamilyChatStore store;
    private static final int MAX_PHOTO_BYTES = 384 * 1024;
    private static final int MAX_PHOTO_LENGTH = MAX_PHOTO_BYTES / 3 * 4;
    private static final int MAX_IMAGE_SIDE = 1280;
    private static final int MAX_VIDEO_BYTES = 5 * 1024 * 1024;
    private static final int MAX_VIDEO_LENGTH = ((MAX_VIDEO_BYTES + 2) / 3) * 4;
    private static final int MAX_VIDEO_DURATION_MS = 15000;
    private final ActivityResultLauncher<String> mediaPicker;
    private java.util.function.Consumer<Uri> mediaSelection;
    private android.widget.VideoView videoPlayer;
    private java.io.File videoFile;

    ChatStatus(FamilyChatFragment host, FamilyChatStore store) {
        this.host = host;
        this.store = store;
        mediaPicker = host.registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            java.util.function.Consumer<Uri> selection = mediaSelection;
            mediaSelection = null;
            if (uri != null && selection != null) selection.accept(uri);
        });
    }

    void open() {
        String uid = store.accountId();
        String family = store.statusFamily();
        if (uid == null || family == null) return;
        mediaSelection = null;
        DatabaseReference statuses = FirebaseDatabase.getInstance().getReference("familyTextStatus").child(family);
        LinearLayout rows = new LinearLayout(host.requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(12), 0, dp(16));
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(rows);
        host.showSecondaryPage("Family Status", scroll, host::closeSecondaryPage, () -> active(family, uid));

        LinearLayout mine = new LinearLayout(rows.getContext());
        mine.setOrientation(LinearLayout.VERTICAL);
        mine.setPadding(dp(12), dp(5), dp(12), dp(10));
        mine.setBackground(surface(20, EDGE, 0xffdcf3e9, 0xffeef7ff));
        rows.addView(mine);
        String ownName = host.ownStatusName();
        mine.addView(statusRow(uid, ownName, "My status", true, null));
        addAction(mine, "+ Create new story", true, () -> compose(statuses.child(uid), family, uid));

        rows.addView(section("Family updates", 18, GREEN), cardSpacing());
        LinearLayout updates = new LinearLayout(rows.getContext());
        updates.setOrientation(LinearLayout.VERTICAL);
        rows.addView(updates);
        TextView empty = section("Loading family updates…", 15, MUTED);
        rows.addView(empty);
        Map<String, String> members = new LinkedHashMap<>(store.groupCandidates());
        members.remove(uid);
        int[] pending = {members.size()};
        if (pending[0] == 0) empty.setText("No active family updates");
        members.put(uid, ownName);
        for (Map.Entry<String, String> member : members.entrySet()) {
            boolean own = uid.equals(member.getKey());
            statuses.child(member.getKey()).get().addOnCompleteListener(task -> {
                if (!host.isSecondaryPage(scroll) || !active(family, uid)) return;
                if (!own && --pending[0] == 0) empty.setText("No active family updates");
                if (!task.isSuccessful()) return;
                com.google.firebase.database.DataSnapshot snapshot = task.getResult();
                String body = snapshot.child("body").getValue(String.class);
                String photo = snapshot.child("photo").getValue(String.class);
                String video = snapshot.child("video").getValue(String.class);
                Long postedAt = snapshot.child("postedAt").getValue(Long.class);
                if (body == null || postedAt == null) return;
                long remaining = postedAt + 86400000L - System.currentTimeMillis();
                if (remaining <= 0) return;
                String memberId = member.getKey();
                DatabaseReference receipts = statuses.getRoot().child("familyStatusReceipts")
                        .child(family).child(memberId).child(Long.toString(postedAt));
                String time = android.text.format.DateUtils.getRelativeTimeSpanString(
                        postedAt, System.currentTimeMillis(), 60000).toString();
                String type = video != null ? "Video" : photo != null ? "Photo" : "Text";
                Runnable viewStatus = () -> {
                    if (!active(family, uid) || postedAt + 86400000L <= System.currentTimeMillis()) {
                        open();
                        return;
                    }
                    if (!own && !store.groupCandidates().containsKey(memberId)) return;
                    showStatus(family, uid, memberId, member.getValue(), body, photo, video, receipts);
                };
                if (own) {
                    LinearLayout current = new LinearLayout(rows.getContext());
                    current.setOrientation(LinearLayout.VERTICAL);
                    mine.addView(current);
                    addAction(current, "View my story · " + type + " · " + time, false, viewStatus);
                    addAction(current, "Remove my story", false, () -> {
                        if (!active(family, uid)) return;
                        statuses.child(uid).removeValue().addOnCompleteListener(removal -> {
                            if (!host.isSecondaryPage(scroll) || !active(family, uid)) return;
                            if (removal.isSuccessful()) open();
                            else notice("Unable to remove status");
                        });
                    });
                    removeOnExpiry(current, remaining, () -> mine.removeView(current));
                } else {
                    View entry = statusRow(memberId, member.getValue(), type + " · " + time, false, viewStatus);
                    entry.setTag(postedAt);
                    int index = 0;
                    while (index < updates.getChildCount()
                            && (Long) updates.getChildAt(index).getTag() >= postedAt) index++;
                    updates.addView(entry, index, cardSpacing());
                    empty.setVisibility(View.GONE);
                    removeOnExpiry(entry, remaining, () -> {
                        updates.removeView(entry);
                        if (updates.getChildCount() == 0) empty.setVisibility(View.VISIBLE);
                    });
                }
            });
        }
    }

    private void removeOnExpiry(View entry, long remaining, Runnable expiry) {
        entry.postDelayed(expiry, remaining);
        entry.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { view.removeCallbacks(expiry); }
        });
    }

    private View statusRow(String uid, String name, String detail, boolean own, Runnable click) {
        LinearLayout row = new LinearLayout(host.requireContext());
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        if (!own) row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24106c59),
                surface(16, EDGE, 0xffffffff, 0xfff0faf7), null));
        View avatar = host.statusAvatar(uid, name, 48, own);
        row.addView(avatar);
        LinearLayout labels = new LinearLayout(row.getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = section(name, 17, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(title);
        TextView subtitle = section(detail, 13, MUTED);
        subtitle.setPadding(dp(12), dp(3), dp(8), 0);
        labels.addView(subtitle);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            row.addView(section("›", 20, GREEN));
            row.setFocusable(true);
            row.setOnClickListener(view -> click.run());
        }
        return row;
    }

    private static final String[] REACTIONS = {"liked", "laugh", "wow", "sad", "clap", "fire"};
    private static final String[] EMOJI = {"❤️", "😂", "😮", "😢", "👏", "🔥"};

    private void showStatus(String family, String uid, String author, String name, String body,
                            String photo, String video, DatabaseReference receipts) {
        boolean mine = uid.equals(author);
        LinearLayout content = new LinearLayout(host.requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * content.getResources().getDisplayMetrics().density);
        content.setPadding(inset, inset, inset, inset);
        TextView post = section("", 24, GREEN);
        post.setText(body);
        post.setBackground(surface(16, EDGE, 0xffe2f4ee, 0xfff6fcfa));
        post.setPadding(dp(16), dp(20), dp(16), dp(20));
        post.setGravity(android.view.Gravity.CENTER);
        post.setMinHeight(8 * inset);
        post.setTextIsSelectable(true);
        if (video != null) {
            LinearLayout videoArea = new LinearLayout(content.getContext());
            videoArea.setOrientation(LinearLayout.VERTICAL);
            content.addView(videoArea);
            addAction(content, "Play video", true, () -> {
                if (active(family, uid)) playVideo(video, videoArea);
            });
            post.setMinHeight(0);
        }
        if (photo != null) {
            ImageView image = statusImage();
            content.addView(image, new LinearLayout.LayoutParams(-1, dp(270)));
            loadPreview(image, photo);
            post.setMinHeight(0);
        }
        if (!body.isEmpty()) content.addView(post, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(content);
        host.showSecondaryPage(name, scroll, this::open, () -> active(family, uid)
                && (mine || store.groupCandidates().containsKey(author)));
        if (mine) {
            TextView feedback = section("Viewers and reactions", 15, INK);
            feedback.setBackground(surface(12, EDGE, 0xffffffff, 0xfff4f9fc));
            content.addView(feedback, cardSpacing());
            receipts.get().addOnSuccessListener(data -> {
                if (!host.isSecondaryPage(scroll) || !active(family, uid)) return;
                StringBuilder result = new StringBuilder("Viewers and reactions");
                for (com.google.firebase.database.DataSnapshot item : data.getChildren()) {
                    if (item.getKey() == null) continue;
                    result.append("\n").append(store.name(item.getKey()));
                    String reaction = item.getValue(String.class);
                    for (int i = 0; i < REACTIONS.length; i++) {
                        if (REACTIONS[i].equals(reaction)) result.append("  ").append(EMOJI[i]);
                    }
                }
                if (!data.hasChildren()) result.append("\nNo views yet");
                feedback.setText(result);
            }).addOnFailureListener(error -> feedback.setText("Unable to load viewers"));
            return;
        }
        DatabaseReference receipt = receipts.child(uid);
        receipt.runTransaction(new com.google.firebase.database.Transaction.Handler() {
            public com.google.firebase.database.Transaction.Result doTransaction(
                    com.google.firebase.database.MutableData current) {
                if (current.getValue() == null) current.setValue("seen");
                return com.google.firebase.database.Transaction.success(current);
            }
            public void onComplete(com.google.firebase.database.DatabaseError error,
                                   boolean committed, com.google.firebase.database.DataSnapshot value) {}
        });
        com.google.android.material.chip.ChipGroup reactions =
                new com.google.android.material.chip.ChipGroup(content.getContext());
        reactions.setSingleSelection(true);
        reactions.setPadding(dp(8), 0, dp(8), 0);
        reactions.setBackground(surface(12, EDGE, 0xffffffff, 0xfff4f9fc));
        content.addView(reactions, cardSpacing());
        com.google.android.material.chip.Chip[] chips = new com.google.android.material.chip.Chip[EMOJI.length];
        for (int i = 0; i < EMOJI.length; i++) {
            final int index = i;
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(content.getContext());
            chip.setId(android.view.View.generateViewId());
            chip.setText(EMOJI[i]);
            chip.setContentDescription(new String[]{"Heart", "Laugh", "Surprised", "Sad", "Applause", "Fire"}[i]);
            chip.setCheckable(true);
            chip.setChipBackgroundColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                    new int[]{0xffcaeee0, 0xfff4faf8}));
            chip.setChipStrokeColor(ColorStateList.valueOf(EDGE));
            chip.setChipStrokeWidth(dp(1));
            chips[i] = chip;
            reactions.addView(chip);
            chip.setOnClickListener(v -> {
                if (!active(family, uid)) return;
                receipt.setValue(chip.isChecked() ? REACTIONS[index] : "seen")
                        .addOnFailureListener(error -> {
                            reactions.clearCheck();
                            notice("Unable to save reaction");
                        });
            });
        }
        receipt.get().addOnSuccessListener(snapshot -> {
            if (!host.isSecondaryPage(scroll) || reactions.getCheckedChipId() != android.view.View.NO_ID) return;
            for (int i = 0; i < REACTIONS.length; i++) {
                if (REACTIONS[i].equals(snapshot.getValue(String.class))) reactions.check(chips[i].getId());
            }
        });
        FamilyChatStore.ThreadState direct = store.thread(author);
        if (direct == null || !store.canUse(direct)) return;
        EditText reply = new EditText(content.getContext());
        reply.setHint("Reply privately");
        reply.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        content.addView(statusField(reply), cardSpacing());
        View sendReply = addAction(content, "Send reply", true, () -> {
            if (!active(family, uid) || !store.canUse(direct)) return;
            String message = reply.getText().toString().trim();
            if (message.isEmpty()) return;
            store.send(direct, message, () -> notice("Unable to send reply"));
            reply.setText("");
        });
        ChatSendButton.style((TextView) sendReply, "Send reply");
    }

    private View addAction(LinearLayout row, String title, boolean primary, Runnable action) {
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(
                row.getContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? 0xffffffff : GREEN);
        button.setGravity(primary ? android.view.Gravity.CENTER : android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24106c59),
                primary ? surface(12, BLUE, BLUE) : surface(12, EDGE, 0xffffffff, 0xffeff8f5), null));
        button.setOnClickListener(view -> action.run());
        row.addView(button, cardSpacing());
        return button;
    }

    private void compose(DatabaseReference path, String family, String uid) {
        EditText input = new EditText(host.requireContext());
        input.setHint("Status text or media caption");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        LinearLayout form = new LinearLayout(host.requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * form.getResources().getDisplayMetrics().density);
        form.setPadding(inset, 0, inset, inset);
        input.setMinLines(3);
        form.addView(statusField(input));
        ImageView preview = statusImage();
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewSize = new LinearLayout.LayoutParams(-1, dp(180));
        previewSize.topMargin = dp(8);
        form.addView(preview, previewSize);
        String[] selectedPhoto = {null};
        String[] selectedVideo = {null};
        TextView videoSelection = section("Video selected", 15, GREEN);
        videoSelection.setVisibility(View.GONE);
        form.addView(videoSelection, cardSpacing());
        boolean[] loadingMedia = {false};
        boolean[] posting = {false};
        com.google.android.material.button.MaterialButton postButton =
                new com.google.android.material.button.MaterialButton(form.getContext());
        addAction(form, "Choose photo", false, () -> {
            if (loadingMedia[0] || posting[0] || !active(family, uid)) return;
            mediaSelection = uri -> {
                if (!host.isSecondaryPage(form) || !active(family, uid)) return;
                loadingMedia[0] = true;
                postButton.setEnabled(false);
                android.content.ContentResolver resolver = form.getContext().getContentResolver();
                java.util.concurrent.CompletableFuture.supplyAsync(() -> encodePhoto(resolver, uri))
                        .thenAccept(encoded -> form.post(() -> {
                            if (!host.isSecondaryPage(form) || !active(family, uid)) return;
                            loadingMedia[0] = false;
                            postButton.setEnabled(true);
                            if (encoded == null) {
                                notice("Unable to prepare photo. Try another image.");
                                return;
                            }
                            stopVideo();
                            selectedPhoto[0] = encoded;
                            selectedVideo[0] = null;
                            videoSelection.setVisibility(View.GONE);
                            preview.setVisibility(View.VISIBLE);
                            loadPreview(preview, encoded);
                        }));
            };
            try { mediaPicker.launch("image/*"); } catch (android.content.ActivityNotFoundException unavailable) {
                mediaSelection = null;
                notice("No photo picker is available");
            }
        });
        addAction(form, "Choose video", false, () -> {
            if (loadingMedia[0] || posting[0] || !active(family, uid)) return;
            mediaSelection = uri -> {
                if (!host.isSecondaryPage(form) || !active(family, uid)) return;
                loadingMedia[0] = true;
                postButton.setEnabled(false);
                android.content.Context app = host.requireContext().getApplicationContext();
                java.util.concurrent.CompletableFuture.supplyAsync(() -> encodeVideo(app, uri))
                        .thenAccept(encoded -> form.post(() -> {
                            if (!host.isSecondaryPage(form) || !active(family, uid)) return;
                            loadingMedia[0] = false;
                            postButton.setEnabled(true);
                            if (encoded == null) {
                                notice("Choose a supported video up to 15 seconds and 5 MB");
                                return;
                            }
                            stopVideo();
                            selectedVideo[0] = encoded;
                            selectedPhoto[0] = null;
                            preview.setTag(null);
                            preview.setImageDrawable(null);
                            preview.setVisibility(View.GONE);
                            videoSelection.setVisibility(View.VISIBLE);
                        }));
            };
            try { mediaPicker.launch("video/*"); } catch (android.content.ActivityNotFoundException unavailable) {
                mediaSelection = null;
                notice("No video picker is available");
            }
        });
        LinearLayout videoArea = new LinearLayout(form.getContext());
        videoArea.setOrientation(LinearLayout.VERTICAL);
        form.addView(videoArea);
        addAction(form, "Preview video", false, () -> {
            if (selectedVideo[0] != null) playVideo(selectedVideo[0], videoArea);
            else notice("Choose a video first");
        });
        addAction(form, "Remove media", false, () -> {
            if (loadingMedia[0] || posting[0]) return;
            stopVideo();
            selectedPhoto[0] = null;
            selectedVideo[0] = null;
            videoSelection.setVisibility(View.GONE);
            preview.setTag(null);
            preview.setImageDrawable(null);
            preview.setVisibility(View.GONE);
        });
        form.addView(section("Choose who can see your status for 24 hours.", 14, MUTED));
        com.google.android.material.checkbox.MaterialCheckBox restricted =
                new com.google.android.material.checkbox.MaterialCheckBox(host.requireContext());
        restricted.setText("Only selected family members");
        restricted.setTextColor(INK);
        restricted.setButtonTintList(ColorStateList.valueOf(GREEN));
        restricted.setBackground(surface(12, EDGE, 0xffe5f6ef, 0xfff4fbf8));
        form.addView(restricted, cardSpacing());
        LinearLayout people = new LinearLayout(host.requireContext());
        people.setOrientation(LinearLayout.VERTICAL);
        form.addView(people);
        people.setVisibility(android.view.View.GONE);
        Map<String, Boolean> audience = new LinkedHashMap<>();
        for (Map.Entry<String, String> member : store.groupCandidates().entrySet()) {
            com.google.android.material.checkbox.MaterialCheckBox check =
                    new com.google.android.material.checkbox.MaterialCheckBox(host.requireContext());
            check.setText(member.getValue());
            check.setTextColor(INK);
            check.setButtonTintList(ColorStateList.valueOf(GREEN));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) audience.put(member.getKey(), true);
                else audience.remove(member.getKey());
            });
            people.addView(check);
        }
        restricted.setOnCheckedChangeListener((button, checked) ->
                people.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE));
        ScrollView scroll = new ScrollView(host.requireContext());
        scroll.addView(form);
        postButton.setText("Post story");
        postButton.setAllCaps(false);
        form.addView(postButton, cardSpacing());
        host.showSecondaryPage("Create new story", scroll, this::open, () -> active(family, uid));
        postButton.setOnClickListener(view -> {
            if (!active(family, uid) || !host.isSecondaryPage(form) || loadingMedia[0] || posting[0]) return;
            String body = input.getText().toString().trim();
            if (body.isEmpty() && selectedPhoto[0] == null && selectedVideo[0] == null) { input.setError("Enter text or choose media"); return; }
            if (restricted.isChecked() && audience.isEmpty()) {
                notice("Select at least one family member");
                return;
            }
            posting[0] = true;
            view.setEnabled(false);
            Map<String, Object> value = new LinkedHashMap<>();
            if (restricted.isChecked()) value.put("audience", new LinkedHashMap<>(audience));
            value.put("body", body);
            if (selectedVideo[0] != null) value.put("video", selectedVideo[0]);
            if (selectedPhoto[0] != null) value.put("photo", selectedPhoto[0]);
            value.put("postedAt", ServerValue.TIMESTAMP);
            path.setValue(value).addOnCompleteListener(task -> {
                if (!host.isSecondaryPage(form) || !active(family, uid)) return;
                if (task.isSuccessful()) open();
                else {
                    posting[0] = false;
                    view.setEnabled(true);
                    notice("Unable to publish status");
                }
            });
        });
    }

    private static String encodeVideo(android.content.Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > MAX_VIDEO_BYTES - bytes.size()) return null;
                bytes.write(buffer, 0, count);
            }
            byte[] video = bytes.toByteArray();
            if (video.length < 12) return null;
            android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
            try {
                metadata.setDataSource(context, uri);
                String duration = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                String videoTrack = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
                if (duration == null || !"yes".equals(videoTrack)) return null;
                long millis = Long.parseLong(duration);
                if (millis <= 0 || millis > MAX_VIDEO_DURATION_MS) return null;
            } finally {
                metadata.release();
            }
            return Base64.encodeToString(video, Base64.NO_WRAP);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private void playVideo(String encoded, LinearLayout area) {
        if (!host.isSecondaryPage(area) || encoded.length() > MAX_VIDEO_LENGTH) return;
        stopVideo();
        java.io.File clip;
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length > MAX_VIDEO_BYTES) return;
            clip = java.io.File.createTempFile("family-status-", ".video", host.requireContext().getCacheDir());
            try {
                java.nio.file.Files.write(clip.toPath(), payload);
            } catch (IOException failure) {
                clip.delete();
                throw failure;
            }
        } catch (IOException | IllegalArgumentException error) {
            notice("Unable to open this video");
            return;
        }
        android.widget.VideoView player = new android.widget.VideoView(host.requireContext());
        videoPlayer = player;
        videoFile = clip;
        area.addView(player, new LinearLayout.LayoutParams(-1, dp(280)));
        player.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                if (videoPlayer == player) releaseVideo();
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (videoPlayer == player) {
                notice("This video cannot be played");
                stopVideo();
            }
            return true;
        });
        android.widget.MediaController controller = new android.widget.MediaController(host.requireContext());
        player.setMediaController(controller);
        player.setVideoURI(Uri.fromFile(clip));
        player.start();
    }

    void stopVideo() {
        if (videoPlayer == null) return;
        android.widget.VideoView player = videoPlayer;
        releaseVideo();
        if (player.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) player.getParent()).removeView(player);
        }
    }

    private void releaseVideo() {
        android.widget.VideoView player = videoPlayer;
        videoPlayer = null;
        player.stopPlayback();
        player.setMediaController(null);
        if (videoFile != null) videoFile.delete();
        videoFile = null;
    }

    private ImageView statusImage() {
        ImageView image = new ImageView(host.requireContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(surface(16, EDGE, 0xffe2f4ee, 0xfff6fcfa));
        image.setClipToOutline(true);
        image.setContentDescription("Status photo");
        return image;
    }

    private void loadPreview(ImageView image, String photo) {
        image.setTag(photo);
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            if (photo.length() > MAX_PHOTO_LENGTH) return null;
            try {
                byte[] jpeg = Base64.decode(photo, Base64.NO_WRAP);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
                if (bounds.outWidth < 1 || bounds.outHeight < 1
                        || Math.max(bounds.outWidth, bounds.outHeight) > MAX_IMAGE_SIDE) return null;
                return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            } catch (IllegalArgumentException badData) {
                return null;
            }
        }).thenAccept(bitmap -> image.post(() -> {
            if (image.isAttachedToWindow() && photo.equals(image.getTag())) image.setImageBitmap(bitmap);
        }));
    }

    private static String encodePhoto(android.content.ContentResolver resolver, Uri uri) {
        Bitmap bitmap = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(resolver, uri);
                bitmap = android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, data) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    double ratio = Math.min(1d, MAX_IMAGE_SIDE / (double) Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, (int) (width * ratio)), Math.max(1, (int) (height * ratio)));
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream input = resolver.openInputStream(uri)) {
                    BitmapFactory.decodeStream(input, null, options);
                }
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_IMAGE_SIDE) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                try (InputStream input = resolver.openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
            }
            if (bitmap == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int quality = 85; quality >= 35; quality -= 10) {
                output.reset();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null;
                if (output.size() <= MAX_PHOTO_BYTES) return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            }
            return null;
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private View statusField(EditText input) {
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        com.google.android.material.textfield.TextInputLayout field = ChatFormDialog.outlined(input);
        field.setBoxBackgroundColor(0xfff9fcfd);
        field.setBoxStrokeColor(BLUE);
        field.setDefaultHintTextColor(ColorStateList.valueOf(MUTED));
        return field;
    }

    private TextView section(String label, int size, int color) {
        TextView view = new TextView(host.requireContext());
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        return view;
    }

    private LinearLayout.LayoutParams cardSpacing() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        return params;
    }

    private GradientDrawable surface(int radius, int stroke, int... colors) {
        GradientDrawable shape = new GradientDrawable();
        if (colors.length == 1) shape.setColor(colors[0]);
        else {
            shape.setOrientation(GradientDrawable.Orientation.TL_BR);
            shape.setColors(colors);
        }
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }

    private boolean active(String family, String uid) {
        return host.isAdded() && host.getView() != null
                && Objects.equals(family, store.statusFamily()) && Objects.equals(uid, store.accountId());
    }

    void dismiss() {
        mediaSelection = null;
        stopVideo();
    }

    private void notice(String message) {
        if (host.isAdded()) Toast.makeText(host.requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
