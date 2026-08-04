package com.tridev.familyhub.feature.documents;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.feature.main.MainActivity;

import java.util.List;

/** Daily, deduplicated expiry notifications for private family documents. */
public final class DocumentExpiryWorker extends Worker {

    private static final String CHANNEL_ID = "family_hub_document_expiry";
    private static final String ALERT_STATE =
            "family_hub_document_expiry_alert_state";

    public DocumentExpiryWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        DocumentVaultPreferences preferences =
                new DocumentVaultPreferences(context);
        if (!preferences.expiryAlertsEnabled()) {
            return Result.success();
        }

        createChannel(context);
        List<DocumentEntry> documents = FamilyHubDatabase
                .getInstance(context)
                .documentDao()
                .getExpiryCandidates();
        long now = System.currentTimeMillis();
        int reminderDays = preferences.reminderDays();
        SharedPreferences alertState = context.getSharedPreferences(
                ALERT_STATE,
                Context.MODE_PRIVATE
        );

        for (DocumentEntry document : documents) {
            String status = DocumentExpiryPolicy.status(
                    document.expiryAt,
                    now,
                    reminderDays
            );
            if (!DocumentExpiryPolicy.STATUS_EXPIRING.equals(status)
                    && !DocumentExpiryPolicy.STATUS_EXPIRED.equals(status)) {
                continue;
            }

            String deduplicationKey = document.id
                    + ":"
                    + document.expiryAt
                    + ":"
                    + status;
            if (alertState.getBoolean(deduplicationKey, false)) {
                continue;
            }

            if (showNotification(context, document, status, now)) {
                alertState.edit()
                        .putBoolean(deduplicationKey, true)
                        .apply();
            }
        }
        return Result.success();
    }

    private boolean showNotification(
            @NonNull Context context,
            @NonNull DocumentEntry document,
            @NonNull String status,
            long now
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        Intent openApp = new Intent(context, MainActivity.class)
                .putExtra("open_documents_vault", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                Math.max(1, (int) (document.id % Integer.MAX_VALUE)),
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        long days = DocumentExpiryPolicy.daysRemaining(
                document.expiryAt,
                now
        );
        boolean expired = DocumentExpiryPolicy.STATUS_EXPIRED.equals(status);
        String title = context.getString(expired
                ? R.string.documents_vault_notification_expired_title
                : R.string.documents_vault_notification_expiring_title);
        String message;
        if (expired) {
            message = context.getString(
                    R.string.documents_vault_notification_expired_message,
                    document.title
            );
        } else if (days <= 0L) {
            message = context.getString(
                    R.string.documents_vault_notification_today_message,
                    document.title
            );
        } else {
            message = context.getString(
                    R.string.documents_vault_notification_expiring_message,
                    document.title,
                    days
            );
        }

        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_document)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(message))
                        .setPriority(expired
                                ? NotificationCompat.PRIORITY_HIGH
                                : NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(context).notify(
                ("document_expiry_" + document.id + status).hashCode(),
                notification.build()
        );
        return true;
    }

    private void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(
                NotificationManager.class
        );
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(
                        R.string.documents_vault_notification_channel
                ),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(context.getString(
                R.string.documents_vault_notification_channel_detail
        ));
        manager.createNotificationChannel(channel);
    }
}
