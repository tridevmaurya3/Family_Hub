package com.tridev.familyhub.feature.chat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.repository.FamilyMemberRepository;
import com.tridev.familyhub.feature.familylive.FamilyMemberDetailActivity;
import com.tridev.familyhub.feature.profile.AccountProfileSync;
import com.tridev.familyhub.feature.profile.FamilyAvatarStore;
import com.tridev.familyhub.feature.profile.ProfilePhotoCropActivity;
import com.tridev.familyhub.feature.profile.ProfileSettingsActivity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ChatGroupScreens {
    private final FamilyChatFragment fragment;
    private final FamilyChatStore store;
    private final Consumer<String> openThread;
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> gallery;
    private final ActivityResultLauncher<Uri> camera;
    private final ActivityResultLauncher<Intent> crop;
    private Dialog active;
    private Consumer<String> photoResult;
    private File cameraFile;
    private boolean closed;

    ChatGroupScreens(FamilyChatFragment fragment, FamilyChatStore store, Consumer<String> openThread) {
        this.fragment = fragment;
        this.store = store;
        this.openThread = openThread;
        gallery = fragment.registerForActivityResult(new ActivityResultContracts.GetContent(), this::editPhoto);
        camera = fragment.registerForActivityResult(new ActivityResultContracts.TakePicture(), taken -> {
            if (taken && cameraFile != null) editPhoto(privatePhotoUri(cameraFile));
            else deleteCamera();
        });
        crop = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            deleteCamera();
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Uri uri = result.getData().getData();
            if (uri != null) encode(uri);
        });
    }

    void showCreate() {
        showCreate(new LinkedHashSet<>(), null, null);
    }

    private void showCreate(Set<String> selected, String draft, String photo) {
        Map<String, String> candidates = store.groupCandidates();
        selected.retainAll(candidates.keySet());
        Page page = page("New group", "Step 1 of 2 · Choose members", this::dismiss);
        EditText search = field("Search family members");
        page.content.addView(search);
        String own = store.accountId();
        page.content.addView(memberRow(own, "You", "Included automatically", null));
        TextView label = label("Choose at least 1 family member", 14, R.color.fh_primary, true);
        page.content.addView(label);
        LinearLayout list = column();
        page.content.addView(list);
        MaterialButton next = button("Next", true);
        Runnable updateNext = () -> {
            next.setText("Next · " + (selected.size() + 1) + " members");
            next.setEnabled(!selected.isEmpty());
        };
        Runnable render = () -> {
            list.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            candidates.forEach((uid, name) -> {
                if (!name.toLowerCase(Locale.ROOT).contains(query)) return;
                LinearLayout row = memberRow(uid, name, "Family member", null);
                CheckBox box = new CheckBox(context());
                box.setContentDescription("Select " + name);
                box.setChecked(selected.contains(uid));
                box.setButtonTintList(ColorStateList.valueOf(color(R.color.fh_module_grocery)));
                row.addView(box);
                row.setOnClickListener(view -> box.toggle());
                box.setOnCheckedChangeListener((view, checked) -> {
                    if (checked) selected.add(uid);
                    else selected.remove(uid);
                    shade(row, checked);
                    updateNext.run();
                });
                shade(row, box.isChecked());
                list.addView(row);
            });
        };
        watchText(search, render);
        render.run();
        updateNext.run();
        next.setOnClickListener(view -> showSetup(selected, draft, photo));
        page.footer.addView(next);
        page.show();
    }

    private void showSetup(Set<String> selected, String draft, String initialPhoto) {
        String[] photo = {initialPhoto};
        EditText name = field("Group name");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        name.setText(draft);
        Page page = page("Group setup", "Step 2 of 2 · Name your group",
                () -> showCreate(selected, name.getText().toString(), photo[0]));
        LinearLayout hero = hero(page, "Group photo", FamilyChatStore.decodeGroupPhoto(photo[0]), "Family");
        hero.addView(button("Add group photo", false, () -> choosePhoto(encoded -> {
            photo[0] = encoded;
            showSetup(selected, name.getText().toString(), encoded);
        })));
        page.content.addView(name);
        page.content.addView(label((selected.size() + 1) + " members", 15, R.color.fh_primary, true));
        page.content.addView(memberRow(store.accountId(), "You", "Group creator", null));
        for (String uid : selected) page.content.addView(memberRow(uid, store.name(uid), "Family member", null));
        MaterialButton create = button("Create group", true);
        create.setOnClickListener(view -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) {
                name.setError("Enter a group name");
                return;
            }
            create.setEnabled(false);
            store.createRoom(title, new ArrayList<>(selected), photo[0], destination -> {
                if (closed || active != page.dialog) return;
                dismiss();
                openThread.accept(destination);
            }, () -> {
                if (closed || active != page.dialog) return;
                create.setEnabled(true);
                toast("Unable to create group");
            });
        });
        page.footer.addView(create);
        page.show();
    }

    void showGroup(FamilyChatStore.ThreadState thread) {
        if (!store.canUse(thread) || !thread.isGroup()) return;
        List<String> members = store.groupMembers(thread);
        Page page = page("Group info", thread.members == null ? "Family group" : "Private group", this::dismiss);
        LinearLayout hero = hero(page, store.title(thread), thread.photo, store.title(thread));
        if (store.canEditGroupPhoto(thread)) hero.addView(button("Rename group", false, () -> renameGroup(thread)));
        if (store.canEditGroupPhoto(thread)) hero.addView(button("Change group photo", false, () ->
                choosePhoto(photo -> store.setGroupPhoto(thread, photo, saved -> {
                    if (closed || active != page.dialog) return;
                    if (saved) showGroup(thread);
                    else toast("Unable to save group photo");
                }))));
        page.content.addView(label("Members · " + members.size(), 15, R.color.fh_primary, true));
        if (store.canEditGroupPhoto(thread)) {
            hero.addView(button("Add member", false, () -> addMember(thread)));
        }
        for (String uid : members) {
            String name = uid.equals(store.accountId()) ? "You" : store.name(uid);
            boolean admin = thread.admins.contains(uid);
            LinearLayout row = memberRow(uid, name, uid.equals(thread.creator) ? "Group creator"
                    : admin ? "Admin" : "Family member", () -> showMember(uid, () -> showGroup(thread)));
            if (store.canEditGroupPhoto(thread) && !uid.equals(thread.creator) && !uid.equals(store.accountId())
                    && (!admin || store.ownsGroup(thread))) {
                MaterialButton options = button("⋮", false, () -> memberActions(thread, uid, admin));
                options.setContentDescription("Manage " + name);
                options.setMinWidth(0);
                options.setMinimumWidth(0);
                options.setPadding(0, 0, 0, 0);
                row.addView(options, new LinearLayout.LayoutParams(dp(44), dp(48)));
            }
            page.content.addView(row);
        }
        page.show();
    }

    private void addMember(FamilyChatStore.ThreadState thread) {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        store.groupCandidates().forEach((uid, name) -> {
            if (!thread.members.contains(uid)) {
                ids.add(uid);
                labels.add(name);
            }
        });
        if (ids.isEmpty()) { toast("No additional family members available"); return; }
        new ChatFormDialog(context())
                .setTitle("Add to group")
                .setMessage("New members can read existing group messages.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose member", (dialog, which) ->
                        new ChatFormDialog(context())
                                .setTitle("Choose family member")
                                .setItems(labels.toArray(new String[0]), (picker, selected) ->
                                        store.changeGroupMember(thread, ids.get(selected), true,
                                                ok -> finishGroupChange(thread, ok)))
                                .setNegativeButton("Cancel", null).show())
                .show();
    }

    private void memberActions(FamilyChatStore.ThreadState thread, String uid, boolean admin) {
        String name = store.name(uid);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (store.ownsGroup(thread)) {
            labels.add(admin ? "Remove admin role" : "Make admin");
            actions.add(() -> store.changeGroupRole(thread, uid, !admin, ok -> finishGroupChange(thread, ok)));
        }
        labels.add("Remove from group");
        actions.add(() -> {
            ChatActionDialog design = new ChatActionDialog(context(), "Remove " + name + "?", R.drawable.ic_profile_person);
            design.add(design.content, design.label("They will lose access to this group.", 15, 0xff52636c, false), 10);
            androidx.appcompat.app.AlertDialog dialog = design.create();
            design.addFooter(dialog, "Remove", view -> {
                dialog.dismiss();
                store.changeGroupMember(thread, uid, false, ok -> finishGroupChange(thread, ok));
            });
            design.show(dialog);
        });
        new ChatActionDialog(context(), name, R.drawable.ic_profile_person)
                .showChoices(labels.toArray(new String[0]), -1, selected -> actions.get(selected).run());
    }

    private void finishGroupChange(FamilyChatStore.ThreadState thread, boolean ok) {
        if (closed || active == null || !fragment.isAdded()) return;
        if (ok) showGroup(thread);
        else toast("Unable to update group");
    }

    private void renameGroup(FamilyChatStore.ThreadState thread) {
        EditText input = field("Group name");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        input.setText(store.title(thread));
        ChatActionDialog design = new ChatActionDialog(context(), "Rename group", R.drawable.ic_family_task);
        design.add(design.content, ChatFormDialog.outlined(input), 10);
        androidx.appcompat.app.AlertDialog dialog = design.create();
        design.addFooter(dialog, "Save", view -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) { input.setError("Enter a group name"); return; }
            view.setEnabled(false);
            store.renameGroup(thread, title, ok -> {
                if (closed || !dialog.isShowing()) return;
                if (ok) {
                    dialog.dismiss();
                    showGroup(thread);
                } else {
                    view.setEnabled(true);
                    toast("Unable to rename group");
                }
            });
        });
        design.show(dialog);
    }

    void showMember(String uid, Runnable back) {
        boolean own = uid.equals(store.accountId());
        if (!own && !store.groupCandidates().containsKey(uid)) return;
        String name = own ? "You" : store.name(uid);
        Page page = page("Member details", "Family Chat", back);
        hero(page, name, photos().photo(uid), name);
        page.content.addView(memberRow(uid, "Family profile", "View available profile details", () ->
                fragment.startActivity(own ? new Intent(context(), ProfileSettingsActivity.class)
                        : FamilyMemberDetailActivity.createIntent(context(), uid))));
        LinearLayout details = column();
        page.content.addView(details);
        FamilyMemberRepository profiles = new FamilyMemberRepository(context());
        profiles.loadMembers("", members -> {
            if (closed || active != page.dialog
                    || (!own && !store.groupCandidates().containsKey(uid))
                    || (own && !uid.equals(store.accountId()))) return;
            for (com.tridev.familyhub.data.local.entity.FamilyMember member : members) {
                if (!uid.equals(member.cloudUid)) continue;
                addProfileField(details, "Relationship", member.relation);
                addProfileField(details, "Phone", member.phone);
                addProfileField(details, "Email", member.email);
                break;
            }
        });
        if (!own) page.footer.addView(button("Open chat", true, () -> {
            if (store.thread(uid) == null) return;
            dismiss();
            openThread.accept(uid);
        }));
        page.show();
    }

    private void addProfileField(LinearLayout details, String title, String value) {
        if (value.isEmpty()) return;
        LinearLayout field = column();
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setBackground(background(R.color.fh_surface, 12));
        field.addView(label(title, 12, R.color.fh_text_secondary, false));
        field.addView(label(value, 15, R.color.fh_on_surface, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        details.addView(field, params);
    }

    private void choosePhoto(Consumer<String> result) {
        photoResult = result;
        BottomSheetDialog sheet = new BottomSheetDialog(context());
        LinearLayout options = column();
        options.setPadding(dp(18), dp(18), dp(18), dp(18));
        options.addView(label("Group photo", 22, R.color.fh_module_grocery, true));
        options.addView(label("Choose a photo", 14, R.color.fh_text_secondary, false));
        MaterialButton take = button("Camera", false, () -> {
            sheet.dismiss();
            try {
                File directory = new File(context().getCacheDir(), "profile_crops");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
                cameraFile = File.createTempFile("group-camera-", ".jpg", directory);
                camera.launch(privatePhotoUri(cameraFile));
            } catch (IOException | android.content.ActivityNotFoundException | IllegalArgumentException error) {
                deleteCamera();
                toast("Unable to open camera");
            }
        });
        take.setIconResource(R.drawable.ic_document_camera);
        options.addView(take);
        MaterialButton pick = button("Gallery", false, () -> {
            sheet.dismiss();
            try {
                gallery.launch("image/*");
            } catch (android.content.ActivityNotFoundException error) {
                toast("Unable to open gallery");
            }
        });
        pick.setIconResource(R.drawable.ic_document_share);
        options.addView(pick);
        sheet.setContentView(options);
        sheet.show();
    }

    private Uri privatePhotoUri(File file) {
        return FileProvider.getUriForFile(context(), context().getPackageName() + ".backupfiles", file);
    }

    private void editPhoto(Uri uri) {
        if (uri == null || photoResult == null || closed) return;
        crop.launch(new Intent(context(), ProfilePhotoCropActivity.class).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private void encode(Uri uri) {
        Consumer<String> result = photoResult;
        Dialog page = active;
        photoResult = null;
        if (result == null || !fragment.isAdded() || closed) return;
        Context app = context().getApplicationContext();
        imageWorker.execute(() -> {
            String encoded = null;
            try (InputStream source = app.getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                Bitmap photo = BitmapFactory.decodeStream(source, null, options);
                if (photo != null) encoded = AccountProfileSync.encodeAvatar(photo);
            } catch (IOException | SecurityException | OutOfMemoryError ignored) {
                // A failed read is reported on the active screen.
            } finally {
                try {
                    app.getContentResolver().delete(uri, null, null);
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // The app cache can reclaim a leftover temporary file.
                }
            }
            String data = encoded;
            ContextCompat.getMainExecutor(app).execute(() -> {
                if (closed || !fragment.isAdded() || active != page) return;
                if (data == null) toast("Unable to read group photo");
                else result.accept(data);
            });
        });
    }

    private void deleteCamera() {
        if (cameraFile != null) cameraFile.delete();
        cameraFile = null;
    }

    void dismiss() {
        if (active == null) return;
        active.dismiss();
        active = null;
        photoResult = null;
    }

    void close() {
        closed = true;
        dismiss();
        deleteCamera();
        imageWorker.shutdown();
    }

    View avatar(String name, Bitmap photo, int size, Runnable click) {
        FrameLayout icon = new FrameLayout(context());
        icon.setBackground(background(R.color.fh_module_grocery_container, 100));
        icon.setClipToOutline(true);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        if (photo != null) {
            ImageView image = new ImageView(context());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(photo);
            icon.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            String title = name.trim();
            String initial = title.isEmpty() ? "?" : title.substring(0, title.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
            TextView initials = label(initial, Math.max(15, size / 3), R.color.fh_module_grocery, true);
            initials.setGravity(Gravity.CENTER);
            icon.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        }
        if (click != null) {
            icon.setContentDescription("View " + name + " information");
            icon.setFocusable(true);
            icon.setOnClickListener(view -> click.run());
        }
        return icon;
    }

    private LinearLayout memberRow(String uid, String name, String detail, Runnable click) {
        LinearLayout row = new LinearLayout(context());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        shade(row, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.addView(avatar(name, photos().photo(uid), 42, null));
        LinearLayout names = column();
        names.setPadding(dp(12), 0, 0, 0);
        names.addView(label(name, 16, R.color.fh_on_surface, true));
        names.addView(label(detail, 13, R.color.fh_text_secondary, false));
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            row.setFocusable(true);
            row.setOnClickListener(view -> click.run());
            row.addView(label("›", 22, R.color.fh_primary, true));
        }
        return row;
    }

    private LinearLayout hero(Page page, String title, Bitmap photo, String fallback) {
        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(12), dp(16), dp(12), dp(12));
        hero.setBackground(background(R.color.fh_surface, 18));
        hero.addView(avatar(fallback, photo, 72, null));
        hero.addView(label(title, 22, R.color.fh_on_surface, true));
        page.content.addView(hero);
        return hero;
    }

    private void shade(LinearLayout row, boolean selected) {
        row.setBackground(background(selected ? R.color.fh_module_grocery_container : R.color.fh_surface, 16));
    }

    private EditText field(String hint) {
        EditText field = new EditText(context());
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextSize(FloatingChatSizing.text(context(), 16));
        field.setPadding(dp(14), dp(6), dp(14), dp(6));
        field.setBackground(background(R.color.fh_surface, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, dp(10), 0, dp(10));
        field.setLayoutParams(params);
        return field;
    }

    private static void watchText(EditText edit, Runnable render) {
        edit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { render.run(); }
            @Override public void afterTextChanged(android.text.Editable edit) {}
        });
    }

    private Page page(String title, String subtitle, Runnable back) {
        dismiss();
        return new Page(title, subtitle, back);
    }

    private final class Page {
        final Dialog dialog = new androidx.activity.ComponentDialog(context());
        final LinearLayout panel = column();
        final LinearLayout content = column();
        final LinearLayout footer = column();

        Page(String title, String subtitle, Runnable back) {
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            ((androidx.activity.ComponentDialog) dialog).getOnBackPressedDispatcher().addCallback(
                    new androidx.activity.OnBackPressedCallback(true) {
                        @Override public void handleOnBackPressed() { back.run(); }
                    });
            panel.setPadding(dp(12), dp(8), dp(12), dp(8));
            panel.setBackgroundResource(R.drawable.bg_page_three_tone);
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            LinearLayout header = new LinearLayout(context());
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(8), dp(6), dp(8), dp(6));
            header.setBackground(background(R.color.fh_module_grocery_container, 18));
            ImageButton backButton = new ImageButton(context());
            backButton.setImageResource(R.drawable.ic_arrow_back);
            backButton.setImageTintList(ColorStateList.valueOf(color(R.color.fh_module_grocery)));
            backButton.setBackground(background(R.color.fh_surface, 12));
            backButton.setContentDescription("Back");
            backButton.setOnClickListener(view -> back.run());
            header.addView(backButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout headings = column();
            headings.setPadding(dp(12), 0, 0, 0);
            headings.addView(label(title, 19, R.color.fh_module_grocery, true));
            headings.addView(label(subtitle, 13, R.color.fh_text_secondary, false));
            header.addView(headings);
            panel.addView(header);
            ScrollView scroll = new ScrollView(context());
            content.setPadding(0, dp(12), 0, dp(8));
            scroll.addView(content);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            panel.addView(footer);
            dialog.setContentView(panel);
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panel, (view, insets) -> {
                androidx.core.graphics.Insets space = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                view.setPadding(dp(12) + space.left, dp(8) + space.top,
                        dp(12) + space.right, dp(8) + space.bottom);
                return insets;
            });
        }

        void show() {
            active = dialog;
            dialog.show();
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            if (fragment.getActivity() instanceof FloatingChatActivity) {
                ((FloatingChatActivity) fragment.getActivity()).fitDialog(dialog, true);
            } else dialog.getWindow().setLayout(-1, -1);
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private GradientDrawable background(int fill, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(fill));
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), color(R.color.fh_outline));
        return background;
    }

    private MaterialButton button(String title, boolean primary, Runnable run) {
        MaterialButton button = button(title, primary);
        button.setOnClickListener(view -> run.run());
        return button;
    }

    private MaterialButton button(String title, boolean primary) {
        MaterialButton button = primary ? new MaterialButton(context()) : new MaterialButton(context(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setAllCaps(false);
        button.setText(title);
        FloatingChatSizing.compactButton(button);
        return button;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(context());
        text.setText(value);
        text.setTextSize(FloatingChatSizing.text(context(), size));
        text.setTextColor(color(color));
        if (bold) text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(context());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private FamilyAvatarStore photos() { return FamilyAvatarStore.get(context()); }
    private Context context() { return fragment.requireContext(); }
    private int color(int id) { return ContextCompat.getColor(context(), id); }
    private int dp(int value) { return FloatingChatSizing.pixels(context(), value); }
    private void toast(String text) { if (fragment.isAdded()) Toast.makeText(context(), text, Toast.LENGTH_SHORT).show(); }
}
