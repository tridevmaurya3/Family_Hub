package com.tridev.familyhub.feature.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Saves an optional profile image in this app's internal files directory. */
public final class ProfilePhotoStore {

    private static final String FILE_NAME = "family_hub_profile.jpg";

    private ProfilePhotoStore() {
    }

    public static boolean save(
            @NonNull Context context,
            @NonNull Uri uri
    ) {
        File outputFile = new File(context.getFilesDir(), FILE_NAME);
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
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    public static boolean exists(@NonNull Context context) {
        return new File(context.getFilesDir(), FILE_NAME).exists();
    }

    public static void remove(@NonNull Context context) {
        new File(context.getFilesDir(), FILE_NAME).delete();
    }
}
