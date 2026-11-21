package com.tridev.familyhub.feature.chat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.familylive.FamilyMapActivity;
import com.tridev.familyhub.feature.profile.ProfilePhotoCropActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChatAttachments {
    private static final int FILE_LIMIT = 1024 * 1024;
    private final FamilyChatFragment fragment;
    private final FamilyChatStore store;
    private final ChatVoiceNotes voiceNotes;
    private final android.util.LruCache<String, Bitmap> photoCache = new android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private final Map<String, java.util.List<java.util.function.Consumer<Bitmap>>> photoRequests = new HashMap<>();
    private final Map<String, com.tridev.familyhub.data.model.FamilyLiveCloudMember> liveMembers = new HashMap<>();
    private final Map<String, java.util.List<java.lang.ref.WeakReference<java.util.function.Consumer<com.tridev.familyhub.data.model.FamilyLiveCloudMember>>>> liveCards = new HashMap<>();
    private com.tridev.familyhub.data.repository.FamilyLiveRepository liveRepository;
    private android.app.Dialog fullPhoto;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> gallery;
    private final ActivityResultLauncher<String[]> documents;
    private final ActivityResultLauncher<Uri> camera;
    private final ActivityResultLauncher<Intent> editor;
    private final ActivityResultLauncher<Intent> contactPicker;
    private final ActivityResultLauncher<String[]> locationPermission;
    private String targetPeer;
    private String targetPath;
    private String targetAccount;
    private Uri captured;
    private File captureFile;
    private CancellationTokenSource locationRequest;

    ChatAttachments(FamilyChatFragment fragment, FamilyChatStore store, ChatVoiceNotes voiceNotes) {
        this.fragment = fragment;
        this.store = store;
        this.voiceNotes = voiceNotes;
        gallery = fragment.registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) edit(uri);
        });
        documents = fragment.registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) prepare(uri, false);
        });
        camera = fragment.registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (ok && captured != null) edit(captured);
            else clearCapture();
        });
        editor = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            clearCapture();
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null
                    && result.getData().getData() != null) prepare(result.getData().getData(), true);
        });
        contactPicker = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                prepareContact(result.getData().getData());
            }
        });
        locationPermission = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    if (hasLocationPermission()) requestLocation();
                    else notice("Location permission is required to share your position");
                });
    }

    void show(FamilyChatStore.ThreadState thread) {
        targetPeer = thread.peer;
        targetPath = thread.ref.toString();
        targetAccount = store.accountId();
        Context context = fragment.requireContext();
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        android.widget.TextView title = new android.widget.TextView(context);
        title.setText("Attach to chat");
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(context, R.color.fh_on_surface));
        panel.addView(title);
        android.widget.TextView recipient = new android.widget.TextView(context);
        recipient.setText(store.title(thread));
        recipient.setTextColor(ContextCompat.getColor(context, R.color.fh_text_secondary));
        recipient.setPadding(0, 0, 0, padding);
        panel.addView(recipient);
        LinearLayout row = attachmentRow(panel);
        addButton(row, sheet, "Gallery", R.drawable.ic_document_share, () -> gallery.launch("image/*"));
        addButton(row, sheet, "Camera", R.drawable.ic_document_camera, this::takePhoto);
        row = attachmentRow(panel);
        addButton(row, sheet, "File", R.drawable.ic_document, () -> documents.launch(new String[]{"*/*"}));
        addButton(row, sheet, "Location", R.drawable.ic_fh_location_pin, this::chooseLocation);
        row = attachmentRow(panel);
        addButton(row, sheet, "Contact", R.drawable.ic_profile_person, this::chooseContact);
        addButton(row, sheet, "Poll", R.drawable.ic_family_task, this::choosePoll);
        row = attachmentRow(panel);
        addButton(row, sheet, "Event", R.drawable.ic_planner, this::chooseEvent);
        addButton(row, sheet, "Voice typing", R.drawable.ic_mic, () -> fragment.toggleVoiceTyping(thread));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(panel);
        sheet.setContentView(scroll);
        sheet.show();
    }

    private LinearLayout attachmentRow(LinearLayout panel) {
        LinearLayout row = new LinearLayout(panel.getContext());
        panel.addView(row);
        return row;
    }

    private void addButton(LinearLayout row, BottomSheetDialog sheet, String title, int icon, Runnable action) {
        Context context = row.getContext();
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setIconResource(icon);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.fh_primary_container)));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fh_outline)));
        button.setOnClickListener(view -> {
            sheet.dismiss();
            try {
                action.run();
            } catch (android.content.ActivityNotFoundException failure) {
                notice("No app is available for " + title.toLowerCase(java.util.Locale.ROOT));
            }
        });
        int density = Math.round(4 * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(density, 0, density, density);
        row.addView(button, params);
    }

    private void takePhoto() {
        Context context = fragment.requireContext();
        try {
            captureFile = tempFile(context, ".jpg");
            captured = privateUri(context, captureFile);
            camera.launch(captured);
        } catch (IOException | android.content.ActivityNotFoundException failure) {
            clearCapture();
            notice("Unable to open the camera");
        }
    }

    private void clearCapture() {
        if (captureFile != null) captureFile.delete();
        captureFile = null;
        captured = null;
    }

    private void edit(Uri source) {
        if (target() == null) {
            clearCapture();
            notice("Open the conversation and select the photo again");
            return;
        }
        editor.launch(new Intent(fragment.requireContext(), ProfilePhotoCropActivity.class)
                .setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(ProfilePhotoCropActivity.EXTRA_CHAT_PHOTO, true));
    }

    private void prepare(Uri uri, boolean photo) {
        FamilyChatStore.ThreadState thread = target();
        if (thread == null || !store.canUse(thread)) {
            notice("Open the conversation and select the attachment again");
            return;
        }
        Context context = fragment.requireContext().getApplicationContext();
        worker.execute(() -> {
            try {
                String name = photo ? "Photo" : fileName(context, uri);
                String mime = photo ? "image/jpeg" : context.getContentResolver().getType(uri);
                if (mime == null || mime.length() > 100) mime = "application/octet-stream";
                byte[] bytes = photo ? photoBytes(context, uri) : readFile(context, uri);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("kind", photo ? "photo" : "file");
                metadata.put("mime", mime);
                String data = Base64.encodeToString(bytes, Base64.NO_WRAP);
                ContextCompat.getMainExecutor(context).execute(() -> confirm(thread, name, metadata, data, bytes));
            } catch (IOException | SecurityException failure) {
                ContextCompat.getMainExecutor(context).execute(() -> notice("Unable to read attachment. File limit is 1 MB."));
            } finally {
                if (photo) {
                    try {
                        context.getContentResolver().delete(uri, null, null);
                    } catch (SecurityException | IllegalArgumentException ignored) {
                        // The temporary file can also be reclaimed by the app cache.
                    }
                }
            }
        });
    }

    private void confirm(FamilyChatStore.ThreadState thread, String label, Map<String, Object> metadata,
                         String data, byte[] photoOrFile) {
        if (!visible() || !store.canUse(thread)) return;
        Context context = fragment.requireContext();
        MaterialAlertDialogBuilder builder = "event".equals(metadata.get("kind"))
                ? new ChatFormDialog(context) : new MaterialAlertDialogBuilder(context);
        MaterialAlertDialogBuilder dialog = builder.setTitle(label)
                .setMessage("Share with " + store.title(thread) + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d, w) -> store.send(thread, label, metadata, data,
                        () -> notice("Unable to send attachment")));
        if ("photo".equals(metadata.get("kind")) && photoOrFile != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(photoOrFile, 0, photoOrFile.length);
            android.widget.ImageView preview = new android.widget.ImageView(fragment.requireContext());
            preview.setAdjustViewBounds(true);
            preview.setMaxHeight(Math.round(220 * preview.getResources().getDisplayMetrics().density));
            preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            preview.setImageBitmap(bitmap);
            dialog.setView(preview);
        }
        ChatSendButton.stylePositive(dialog.show());
    }

    private void chooseContact() {
        contactPicker.launch(new Intent(Intent.ACTION_PICK,
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
    }

    private void prepareContact(Uri uri) {
        FamilyChatStore.ThreadState thread = target();
        if (thread == null || uri == null || !store.canUse(thread)) return;
        String nameColumn = android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME;
        String numberColumn = android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER;
        try (android.database.Cursor cursor = fragment.requireContext().getContentResolver()
                .query(uri, new String[]{nameColumn, numberColumn}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                notice("Contact is unavailable");
                return;
            }
            String name = limit(cursor.getString(0), "Contact", 180);
            String number = limit(cursor.getString(1), "", 50);
            if (number.isEmpty()) {
                notice("The selected contact has no phone number");
                return;
            }
            Map<String, Object> details = new HashMap<>();
            details.put("kind", "contact");
            details.put("phone", number);
            confirm(thread, name, details, null, null);
        } catch (SecurityException | IllegalArgumentException error) {
            notice("Contact could not be opened");
        }
    }

    private void chooseEvent() {
        FamilyChatStore.ThreadState thread = target();
        if (thread == null || !store.canUse(thread)) return;
        android.widget.EditText title = entry("Event title", 180);
        android.widget.LinearLayout form = new android.widget.LinearLayout(fragment.requireContext());
        int padding = Math.round(20 * form.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, 0);
        form.addView(ChatFormDialog.outlined(title));
        androidx.appcompat.app.AlertDialog dialog = new ChatFormDialog(fragment.requireContext())
                .setTitle("Share an event").setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Choose date", null).show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = title.getText().toString().trim();
            if (label.isEmpty()) {
                title.setError("Enter an event title");
                return;
            }
            dialog.dismiss();
            ChatDateTimePicker.pickDateTime(fragment, System.currentTimeMillis(), false, start -> {
                Map<String, Object> details = new HashMap<>();
                details.put("kind", "event");
                details.put("startsAt", start);
                confirm(thread, label, details, null, null);
            });
        });
    }

    private android.widget.EditText entry(String hint, int max) {
        android.widget.EditText field = new android.widget.EditText(fragment.requireContext());
        field.setSingleLine(true);
        field.setHint(hint);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        return field;
    }

    private void choosePoll() {
        FamilyChatStore.ThreadState thread = target();
        if (thread == null || !store.canUse(thread)) return;
        Context context = fragment.requireContext();
        ChatActionDialog design = new ChatActionDialog(context, "Create a poll", R.drawable.ic_family_task);
        LinearLayout form = design.content;
        design.add(form, design.label("Question", 13, ChatActionDialog.GREEN, true), 12);
        android.widget.EditText question = entry("Ask your family a question", 180);
        design.add(form, pollField(question), 4);
        design.add(form, design.label("Options", 13, ChatActionDialog.GREEN, true), 10);
        java.util.List<android.widget.EditText> choices = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            android.widget.EditText choice = entry("Option " + i + (i > 2 ? " (optional)" : ""), 100);
            choices.add(choice);
            design.add(form, pollField(choice), 4);
        }
        long[] closesAt = {0L};
        LinearLayout timing = new LinearLayout(context);
        timing.setOrientation(LinearLayout.VERTICAL);
        timing.setPadding(design.dp(10), design.dp(8), design.dp(10), design.dp(6));
        timing.setBackground(design.surface(0xffeaf5ef, 0xffc9dfd3, 13));
        design.add(form, timing, 10);
        design.add(timing, design.label("Voting deadline", 14, ChatActionDialog.GREEN, true), 0);
        android.widget.TextView date = design.label("No deadline", 13, 0xff4d6562, false);
        design.add(timing, date, 3);
        LinearLayout deadlineActions = new LinearLayout(context);
        deadlineActions.setGravity(android.view.Gravity.END);
        design.add(timing, deadlineActions, 0);
        MaterialButton deadline = design.button("Set deadline", false);
        MaterialButton clearDeadline = design.button("No deadline", false);
        clearDeadline.setVisibility(android.view.View.GONE);
        deadlineActions.addView(clearDeadline);
        deadlineActions.addView(deadline);
        deadline.setOnClickListener(view -> pickPollDeadline(value -> {
            closesAt[0] = value;
            date.setText("Closes · " + pollDate(value));
            clearDeadline.setVisibility(android.view.View.VISIBLE);
        }));
        clearDeadline.setOnClickListener(view -> {
            closesAt[0] = 0L;
            date.setText("No deadline");
            clearDeadline.setVisibility(android.view.View.GONE);
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(form);
        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(android.view.Gravity.END);
        design.add(form, footer, 10);
        MaterialButton cancel = design.button("Cancel", false);
        MaterialButton send = design.button("Send poll", true);
        send.setIconResource(R.drawable.ic_chat_send);
        send.setIconTint(android.content.res.ColorStateList.valueOf(0xffffffff));
        send.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        footer.addView(cancel);
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(-2, -2);
        sendSize.setMarginStart(design.dp(8));
        footer.addView(send, sendSize);
        androidx.appcompat.app.AlertDialog dialog = new ChatFormDialog(context).setView(scroll).create();
        dialog.show();
        cancel.setOnClickListener(v -> dialog.dismiss());
        send.setOnClickListener(v -> {
            if (!visible() || !store.canUse(thread)) return;
            String label = question.getText().toString().trim();
            if (label.isEmpty()) {
                question.setError("Enter a question");
                return;
            }
            Map<String, Object> options = new HashMap<>();
            for (android.widget.EditText choice : choices) {
                String value = choice.getText().toString().trim();
                if (!value.isEmpty()) options.put(Integer.toString(options.size()), value);
            }
            if (options.size() < 2) {
                choices.get(1).setError("Enter at least two options");
                return;
            }
            Map<String, Object> details = new HashMap<>();
            details.put("kind", "poll");
            details.put("options", options);
            if (closesAt[0] != 0L) {
                if (closesAt[0] <= System.currentTimeMillis()) {
                    notice("Choose a deadline in the future");
                    return;
                }
                details.put("closesAt", closesAt[0]);
            }
            dialog.dismiss();
            store.send(thread, label, details, null, () -> notice("Unable to send poll"));
        });
    }

    private android.view.View pollField(android.widget.EditText field) {
        com.google.android.material.textfield.TextInputLayout box = ChatFormDialog.outlined(field);
        box.setBoxBackgroundColor(0xffffffff);
        box.setBoxStrokeColor(0xffb8d2c7);
        return box;
    }

    private void pickPollDeadline(java.util.function.LongConsumer selected) {
        ChatDateTimePicker.pickDateTime(fragment, System.currentTimeMillis() + 3600000L, true, value -> {
            if (value <= System.currentTimeMillis()) notice("Choose a deadline in the future");
            else selected.accept(value);
        });
    }

    String pollDate(long millis) {
        Context context = fragment.requireContext();
        java.util.Date date = new java.util.Date(millis);
        return android.text.format.DateFormat.getDateFormat(context).format(date) + " "
                + android.text.format.DateFormat.getTimeFormat(context).format(date);
    }

    private static String limit(String value, String fallback, int length) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.substring(0, Math.min(value.length(), length));
    }

    void confirmLiveShare(FamilyChatStore.ThreadState thread, Runnable openChat) {
        androidx.appcompat.app.AlertDialog confirmation =
                new MaterialAlertDialogBuilder(fragment.requireContext()).setTitle("Share Family Live location")
                .setMessage("Send your Family Live link to " + store.title(thread) + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> {
                    store.send(thread, "Family Live location",
                            java.util.Collections.singletonMap("kind", "live"), null,
                            () -> notice("Unable to share Family Live location"));
                    openChat.run();
                }).show();
        ChatSendButton.stylePositive(confirmation);
    }

    private void chooseLocation() {
        FamilyChatStore.ThreadState thread = target();
        if (thread == null || !store.canUse(thread)) return;
        Context context = fragment.requireContext();
        ChatActionDialog design = new ChatActionDialog(context, "Share location", R.drawable.ic_fh_location_pin);
        androidx.appcompat.app.AlertDialog dialog = new ChatFormDialog(context).setView(design.content)
                .setNegativeButton("Cancel", null).create();
        addLocationChoice(design, "Current location", "Send a one-time map pin", () -> {
            dialog.dismiss();
            if (hasLocationPermission()) requestLocation();
            else locationPermission.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        });
        addLocationChoice(design, "Family Live", "Share a link to your Family Live map", () -> {
            dialog.dismiss();
            confirmLiveShare(thread, () -> {});
        });
        dialog.show();
    }

    private void addLocationChoice(ChatActionDialog design, String title, String subtitle, Runnable clicked) {
        Context context = fragment.requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(design.dp(10), design.dp(10), design.dp(10), design.dp(10));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22136650),
                design.surface(0xffffffff, 0xffd1e1d9, 14), null));
        android.widget.ImageView icon = new android.widget.ImageView(context);
        icon.setImageResource(R.drawable.ic_fh_location_pin);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(ChatActionDialog.GREEN));
        icon.setPadding(design.dp(8), design.dp(8), design.dp(8), design.dp(8));
        icon.setBackground(design.surface(0xffe8f4ee, 0xffe8f4ee, 10));
        row.addView(icon, new LinearLayout.LayoutParams(design.dp(40), design.dp(40)));
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(design.dp(10), 0, design.dp(6), 0);
        words.addView(design.label(title, 16, 0xff1d3934, true));
        words.addView(design.label(subtitle, 12, 0xff4d6562, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(design.label("›", 24, ChatActionDialog.GREEN, true));
        row.setContentDescription(title + ". " + subtitle);
        row.setFocusable(true);
        row.setOnClickListener(v -> clicked.run());
        design.add(design.content, row, 8);
    }

    private boolean hasLocationPermission() {
        Context context = fragment.requireContext();
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void requestLocation() {
        FamilyChatStore.ThreadState thread = target();
        if (thread == null || !hasLocationPermission() || !store.canUse(thread)) return;
        if (locationRequest != null) locationRequest.cancel();
        Context context = fragment.requireContext();
        locationRequest = new CancellationTokenSource();
        LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, locationRequest.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        notice("Current location is unavailable");
                        return;
                    }
                    Map<String, Object> details = new HashMap<>();
                    details.put("kind", "location");
                    details.put("lat", location.getLatitude());
                    details.put("lng", location.getLongitude());
                    confirm(thread, "Current location", details, null, null);
                }).addOnFailureListener(error -> notice("Current location is unavailable"));
    }

    String selectedVote(FamilyChatStore.Message message) { return store.vote(message); }

    void observeLive(String sender, java.util.function.Consumer<com.tridev.familyhub.data.model.FamilyLiveCloudMember> update) {
        java.util.List<java.lang.ref.WeakReference<java.util.function.Consumer<com.tridev.familyhub.data.model.FamilyLiveCloudMember>>> cards =
                liveCards.computeIfAbsent(sender, key -> new java.util.ArrayList<>());
        cards.removeIf(ref -> ref.get() == null);
        cards.add(new java.lang.ref.WeakReference<>(update));
        update.accept(liveMembers.get(sender));
        if (liveRepository != null) return;
        liveRepository = new com.tridev.familyhub.data.repository.FamilyLiveRepository(fragment.requireContext());
        liveRepository.observeCloudMembers(members -> {
            liveMembers.clear();
            for (com.tridev.familyhub.data.model.FamilyLiveCloudMember member : members) liveMembers.put(member.uid, member);
            updateLiveCards();
        }, error -> {
            liveMembers.clear();
            updateLiveCards();
        });
    }

    private void updateLiveCards() {
        liveCards.forEach((sender, cards) -> {
            cards.removeIf(ref -> ref.get() == null);
            for (java.lang.ref.WeakReference<java.util.function.Consumer<com.tridev.familyhub.data.model.FamilyLiveCloudMember>> ref : cards) {
                java.util.function.Consumer<com.tridev.familyhub.data.model.FamilyLiveCloudMember> update = ref.get();
                if (update != null) update.accept(liveMembers.get(sender));
            }
        });
    }

    void stopPreviews() {
        if (liveRepository != null) liveRepository.close();
        liveRepository = null;
        liveCards.clear();
        liveMembers.clear();
        photoCache.evictAll();
        photoRequests.clear();
        if (fullPhoto != null) fullPhoto.dismiss();
    }

    void loadPhoto(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message,
                   java.util.function.Consumer<Bitmap> result) {
        if (!store.canUse(thread) || worker.isShutdown()) {
            result.accept(null);
            return;
        }
        String key = thread.files.child(message.snapshot.getKey()).toString();
        Bitmap cached = photoCache.get(key);
        if (cached != null) {
            result.accept(cached);
            return;
        }
        java.util.List<java.util.function.Consumer<Bitmap>> existing = photoRequests.get(key);
        if (existing != null) {
            existing.add(result);
            return;
        }
        java.util.List<java.util.function.Consumer<Bitmap>> requests = new java.util.ArrayList<>();
        requests.add(result);
        photoRequests.put(key, requests);
        Context context = fragment.requireContext().getApplicationContext();
        thread.files.child(message.snapshot.getKey()).get().addOnCompleteListener(task -> {
            if (photoRequests.get(key) != requests || worker.isShutdown()) return;
            String data = task.isSuccessful() ? task.getResult().getValue(String.class) : null;
            worker.execute(() -> {
                Bitmap photo = decodePhoto(data);
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (photoRequests.get(key) != requests) return;
                    photoRequests.remove(key);
                    Bitmap allowed = store.canUse(thread) ? photo : null;
                    if (allowed != null) photoCache.put(key, allowed);
                    for (java.util.function.Consumer<Bitmap> callback : requests) callback.accept(allowed);
                });
            });
        });
    }

    private static Bitmap decodePhoto(String data) {
        if (data == null || data.length() > 1_400_000) return null;
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 1600 || bounds.outHeight > 1600) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException | OutOfMemoryError error) {
            return null;
        }
    }

    void openFamilyMap(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!store.canUse(thread)) return;
        Double latitude = message.snapshot.child("lat").getValue(Double.class);
        Double longitude = message.snapshot.child("lng").getValue(Double.class);
        if (latitude == null || longitude == null || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return;
        fragment.startActivity(FamilyMapActivity.createLocationIntent(fragment.requireContext(), latitude, longitude));
    }

    boolean announcementAuthor(FamilyChatStore.Message message) { return store.own(message); }

    boolean acknowledged(FamilyChatStore.Message message) {
        String uid = store.accountId();
        return uid != null && message.snapshot.child("acks").child(uid).exists();
    }

    void acknowledge(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        store.acknowledgeAnnouncement(thread, message, ok -> { if (!ok) notice("Unable to acknowledge"); });
    }

    void toggleChecklist(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message, String item) {
        boolean checked = message.snapshot.child("checks").child(item).exists();
        store.setChecklistItem(thread, message, item, !checked, ok -> { if (!ok) notice("Unable to update checklist"); });
    }

    void announcementResponses(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        store.loadMessage(thread, message.snapshot.getKey(), latest -> {
            if (latest == null || !visible()) return;
            java.util.List<String> yes = new java.util.ArrayList<>();
            java.util.List<String> no = new java.util.ArrayList<>();
            for (String uid : store.participants(thread)) {
                if (uid == null || uid.equals(latest.sender)) continue;
                (latest.snapshot.child("acks").child(uid).exists() ? yes : no).add(store.name(uid));
            }
            new ChatFormDialog(fragment.requireContext()).setTitle("Acknowledgments")
                    .setMessage("Got it · " + yes.size() + "\n" + String.join(", ", yes)
                            + "\n\nWaiting · " + no.size() + "\n" + String.join(", ", no))
                    .setPositiveButton("Done", null).show();
        });
    }

    View voiceMessage(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        return voiceNotes.messageView(thread, message);
    }

    void open(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (!store.canUse(thread) || message.deleted()) return;
        String kind = message.kind();
        if ("announcement".equals(kind)) {
            announcementResponses(thread, message);
        } else if ("checklist".equals(kind)) {
            StringBuilder text = new StringBuilder();
            for (com.google.firebase.database.DataSnapshot item : message.snapshot.child("entries").getChildren()) {
                text.append(message.snapshot.child("checks").child(item.getKey()).exists() ? "✓  " : "○  ")
                        .append(item.getValue(String.class)).append('\n');
            }
            new ChatFormDialog(fragment.requireContext()).setTitle(message.body)
                    .setMessage(text).setPositiveButton("Done", null).show();
        } else if ("voice".equals(kind)) {
            voiceNotes.open(thread, message);
        } else if ("live".equals(kind)) {
            fragment.startActivity(FamilyMapActivity.createIntent(fragment.requireContext(), message.liveMember()));
        } else if ("contact".equals(kind)) {
            String number = message.snapshot.child("phone").getValue(String.class);
            if (number == null) return;
            new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle(message.body).setMessage(number).setNegativeButton("Close", null)
                    .setPositiveButton("Dial", (d, w) -> launch(new Intent(Intent.ACTION_DIAL,
                            Uri.fromParts("tel", number, null)))).show();
        } else if ("event".equals(kind)) {
            Long start = message.snapshot.child("startsAt").getValue(Long.class);
            if (start == null) return;
            launch(new Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, message.body)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.longValue()));
        } else if ("poll".equals(kind)) {
            showPoll(thread, message);
        } else if ("location".equals(kind)) {
            Double lat = message.snapshot.child("lat").getValue(Double.class);
            Double lng = message.snapshot.child("lng").getValue(Double.class);
            if (lat == null || lng == null) return;
            Uri point = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng);
            launch(new Intent(Intent.ACTION_VIEW, point));
        } else if ("photo".equals(kind)) {
            loadPhoto(thread, message, bitmap -> {
                if (bitmap == null) notice("Photo could not be opened");
                else showFullPhoto(thread, bitmap);
            });
        } else if ("file".equals(kind)) {
            thread.files.child(message.snapshot.getKey()).get().addOnSuccessListener(snapshot -> {
                if (!visible() || !store.canUse(thread)) return;
                String data = snapshot.getValue(String.class);
                if (data == null) {
                    notice("Attachment is unavailable");
                    return;
                }
                byte[] bytes;
                try {
                    bytes = Base64.decode(data, Base64.DEFAULT);
                } catch (IllegalArgumentException failure) {
                    notice("Attachment could not be read");
                    return;
                }
                openFile(thread, message, bytes);
            }).addOnFailureListener(error -> notice("Attachment is unavailable"));
        }
    }

    private void showPoll(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message) {
        if (message.pollClosed()) { showPollResults(message); return; }
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (com.google.firebase.database.DataSnapshot option : message.snapshot.child("options").getChildren()) {
            String id = option.getKey();
            ids.add(id);
            labels.add(option.getValue(String.class));
        }
        int[] selected = {ids.indexOf(store.vote(message))};
        new ChatFormDialog(fragment.requireContext()).setTitle(message.body)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected[0], (d, which) -> selected[0] = which)
                .setNegativeButton("Close", null)
                .setPositiveButton("Vote", (d, w) -> {
                    if (message.pollClosed()) { notice("Voting has closed"); return; }
                    if (selected[0] >= 0) store.vote(thread, message, ids.get(selected[0]),
                            () -> notice("Unable to save vote"));
                }).show();
    }

    void showPollResults(FamilyChatStore.Message message) {
        Context context = fragment.requireContext();
        Map<String, Integer> counts = new HashMap<>();
        for (com.google.firebase.database.DataSnapshot vote : message.snapshot.child("votes").getChildren()) {
            String id = vote.getValue(String.class);
            counts.put(id, counts.getOrDefault(id, 0) + 1);
        }
        int total = (int) message.snapshot.child("votes").getChildrenCount();
        LinearLayout results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        results.setPadding(padding, padding / 2, padding, padding / 2);
        for (com.google.firebase.database.DataSnapshot option : message.snapshot.child("options").getChildren()) {
            int count = counts.getOrDefault(option.getKey(), 0);
            android.widget.TextView label = new android.widget.TextView(context);
            label.setText(option.getValue(String.class) + "  ·  " + count + (count == 1 ? " vote" : " votes"));
            label.setTextSize(15);
            results.addView(label);
            com.google.android.material.progressindicator.LinearProgressIndicator bar =
                    new com.google.android.material.progressindicator.LinearProgressIndicator(context);
            bar.setMax(Math.max(total, 1));
            bar.setProgress(count);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
            barParams.bottomMargin = padding / 2;
            results.addView(bar, barParams);
        }
        new ChatFormDialog(context).setTitle(message.body).setView(results)
                .setPositiveButton("Done", null).show();
    }

    void showFullPhoto(FamilyChatStore.ThreadState thread, Bitmap bitmap) {
        if (!visible() || !fragment.isResumed() || !store.canUse(thread)) return;
        Context context = fragment.requireContext();
        if (fullPhoto != null) fullPhoto.dismiss();
        android.app.Dialog viewer = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        fullPhoto = viewer;
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setBackgroundColor(android.graphics.Color.BLACK);
        android.widget.ImageView image = new android.widget.ImageView(context);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        image.setContentDescription("Shared photo");
        frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
        MaterialButton close = new MaterialButton(context);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(view -> viewer.dismiss());
        android.widget.FrameLayout.LayoutParams button =
                new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
        int margin = Math.round(16 * context.getResources().getDisplayMetrics().density);
        button.setMargins(margin, margin, margin, margin);
        frame.addView(close, button);
        viewer.setContentView(frame);
        viewer.setOnDismissListener(dialog -> { if (fullPhoto == viewer) fullPhoto = null; });
        viewer.show();
        viewer.getWindow().setLayout(-1, -1);
    }

    private void openFile(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message, byte[] bytes) {
        Context context = fragment.requireContext().getApplicationContext();
        worker.execute(() -> {
            try {
                String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(message.body);
                if (!extension.matches("[a-zA-Z0-9]{1,12}")) extension = "bin";
                File file = tempFile(context, "." + extension);
                Files.write(file.toPath(), bytes);
                Uri uri = privateUri(context, file);
                String mime = message.snapshot.child("mime").getValue(String.class);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime == null ? "application/octet-stream" : mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ContextCompat.getMainExecutor(context).execute(() -> {
                    if (visible() && store.canUse(thread)) launch(intent);
                });
            } catch (IOException failure) {
                ContextCompat.getMainExecutor(context).execute(() -> notice("File could not be opened"));
            }
        });
    }

    private void launch(Intent intent) {
        try {
            fragment.startActivity(intent);
        } catch (android.content.ActivityNotFoundException error) {
            notice("No app is available to open this attachment");
        }
    }

    private static byte[] readFile(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Missing input");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > FILE_LIMIT) throw new IOException("File exceeds limit");
                out.write(buffer, 0, count);
            }
            if (out.size() == 0) throw new IOException("Empty input");
            return out.toByteArray();
        }
    }

    private static byte[] photoBytes(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Missing photo");
            Bitmap image = BitmapFactory.decodeStream(in);
            if (image == null) throw new IOException("Invalid photo");
            try {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 84, out) || out.size() > FILE_LIMIT) {
                    throw new IOException("Unable to compress photo");
                }
                return out.toByteArray();
            } finally {
                image.recycle();
            }
        }
    }

    private static String fileName(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.substring(0, Math.min(180, name.length()));
            }
        }
        return "File";
    }

    private static File tempFile(Context context, String suffix) throws IOException {
        File folder = new File(context.getCacheDir(), "chat_attachments");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) throw new IOException();
        File[] old = folder.listFiles();
        if (old != null) for (File file : old) {
            if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) file.delete();
        }
        return File.createTempFile("chat-", suffix, folder);
    }

    private static Uri privateUri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".backupfiles", file);
    }

    private FamilyChatStore.ThreadState target() {
        FamilyChatStore.ThreadState thread = store.thread(targetPeer);
        if (thread == null || targetPath == null || !targetPath.equals(thread.ref.toString())
                || targetAccount == null || !targetAccount.equals(store.accountId()) || !store.canUse(thread)) {
            return null;
        }
        return thread;
    }

    void saveState(android.os.Bundle state) {
        state.putString("attachment_peer", targetPeer);
        state.putString("attachment_path", targetPath);
        state.putString("attachment_account", targetAccount);
        if (captureFile != null) state.putString("attachment_capture", captureFile.getName());
    }

    void restoreState(android.os.Bundle state) {
        targetPeer = state.getString("attachment_peer");
        targetPath = state.getString("attachment_path");
        targetAccount = state.getString("attachment_account");
        String fileName = state.getString("attachment_capture");
        if (fileName != null && fileName.matches("chat-[0-9]+[.]jpg")) {
            Context context = fragment.requireContext();
            File file = new File(new File(context.getCacheDir(), "chat_attachments"), fileName);
            if (file.isFile()) {
                captureFile = file;
                captured = privateUri(context, file);
            }
        }
    }

    private boolean visible() { return fragment.isAdded() && fragment.getView() != null; }

    private void notice(String text) {
        if (visible()) Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    void close() {
        stopPreviews();
        if (locationRequest != null) locationRequest.cancel();
        worker.shutdown();
    }
}
