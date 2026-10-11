package com.tridev.familyhub.feature.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Saves an optional profile image per signed-in account in app storage. */
public final class ProfilePhotoStore {

    private static final int TARGET_SIZE = 720;

    private ProfilePhotoStore() {
    }

    public static boolean save(
            @NonNull Context context,
            @NonNull Uri uri
    ) {
        File outputFile = photoFile(context);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(outputFile)) {
            if (input == null) {
                return false;
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
            output.flush();
            return true;
        } catch (Exception error) {
            outputFile.delete();
            return false;
        }
    }

    @Nullable
    public static Bitmap load(@NonNull Context context) {
        return load(context, accountUid());
    }

    public static Bitmap load(Context context, String uid) {
        File file = photoFile(context, uid);
        if (!file.exists()) {
            return null;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_SIZE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    public static boolean exists(@NonNull Context context) {
        return photoFile(context).exists();
    }

    public static void remove(@NonNull Context context) {
        photoFile(context).delete();
    }

    @NonNull
    private static File photoFile(@NonNull Context context) {
        return photoFile(context, accountUid());
    }

    private static String accountUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? "guest" : user.getUid();
    }

    private static File photoFile(Context context, String account) {
        String safeAccount = account.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(context.getFilesDir(), "family_hub_profile_" + safeAccount + ".jpg");
    }

    /** Atomically replace only this account's local cache; an empty value is a removal. */
    static boolean applyCloud(Context context, String uid, String encoded) {
        File file = photoFile(context, uid);
        if (encoded.isEmpty()) return !file.exists() || file.delete();
        if (encoded.length() > 64000) return false;
        File temp = new File(file.getPath() + ".tmp");
        try {
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 640 || bounds.outHeight > 640) return false;
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
            return temp.renameTo(file);
        } catch (Exception error) { return false; }
        finally { if (temp.exists()) temp.delete(); }
    }
}
