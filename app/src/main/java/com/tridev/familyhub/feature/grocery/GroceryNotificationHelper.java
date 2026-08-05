package com.tridev.familyhub.feature.grocery;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.main.MainActivity;

public final class GroceryNotificationHelper {
    private static final String CHANNEL_ID = "family_grocery_updates";
    private GroceryNotificationHelper() { }

    public static void notifyUpdate(@NonNull Context context,
                                    @NonNull GroceryItem item) {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.grocery_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                item.cloudId.hashCode(), new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String detail = GroceryItem.STATUS_BUYING.equals(item.buyingStatus)
                ? context.getString(R.string.grocery_notification_buying,
                item.updatedByName)
                : item.assignedMemberName.isEmpty()
                ? context.getString(R.string.grocery_notification_updated)
                : context.getString(R.string.grocery_notification_assigned,
                item.assignedMemberName);
        manager.notify(item.cloudId.hashCode(), new NotificationCompat.Builder(
                        context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_grocery)
                .setContentTitle(item.name)
                .setContentText(detail)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build());
    }
}
