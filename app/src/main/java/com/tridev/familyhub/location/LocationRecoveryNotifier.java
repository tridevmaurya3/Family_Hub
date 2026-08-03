package com.tridev.familyhub.location;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tridev.familyhub.R;
import com.tridev.familyhub.feature.main.MainActivity;

/**
 * Shows transparent, user-actionable Family Live recovery notifications.
 *
 * The app never silently asks for a battery-optimization exemption. Instead it
 * explains why background reliability may be reduced and opens Android's own
 * settings only after the user taps the action.
 */
public final class LocationRecoveryNotifier {

    private static final String CHANNEL_ID = "family_live_recovery";
    private static final int RECOVERY_NOTIFICATION_ID = 4121;
    private static final int BATTERY_NOTIFICATION_ID = 4122;

    private static final String PREFS = "family_live_recovery_notice";
    private static final String KEY_LAST_BATTERY_NOTICE =
            "last_battery_notice";
    private static final long BATTERY_NOTICE_INTERVAL_MS =
            7L * 24L * 60L * 60L * 1000L;

    private LocationRecoveryNotifier() {
    }

    public static void showResumeRequired(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        createChannel(appContext);

        NotificationCompat.Builder builder = baseBuilder(appContext)
                .setContentTitle(appContext.getString(
                        R.string.family_live_recovery_title
                ))
                .setContentText(appContext.getString(
                        R.string.family_live_recovery_detail
                ))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent(appContext))
                .addAction(
                        R.drawable.ic_fh_location_pin,
                        appContext.getString(
                                R.string.family_live_recovery_resume_action
                        ),
                        resumeSharingPendingIntent(appContext)
                );

        notifySafely(
                appContext,
                RECOVERY_NOTIFICATION_ID,
                builder
        );
    }

    public static void showBatteryRestrictionIfNeeded(
            @NonNull Context context
    ) {
        Context appContext = context.getApplicationContext();
        if (!LocationSharingStore.isSharingEnabled(appContext)
                || isIgnoringBatteryOptimizations(appContext)
                || !shouldShowBatteryNotice(appContext)) {
            return;
        }

        createChannel(appContext);
        NotificationCompat.Builder builder = baseBuilder(appContext)
                .setContentTitle(appContext.getString(
                        R.string.family_live_battery_restriction_title
                ))
                .setContentText(appContext.getString(
                        R.string.family_live_battery_restriction_detail
                ))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent(appContext))
                .addAction(
                        R.drawable.ic_fh_battery,
                        appContext.getString(
                                R.string.family_live_battery_settings_action
                        ),
                        batterySettingsPendingIntent(appContext)
                );

        if (notifySafely(
                appContext,
                BATTERY_NOTIFICATION_ID,
                builder
        )) {
            preferences(appContext).edit()
                    .putLong(
                            KEY_LAST_BATTERY_NOTICE,
                            System.currentTimeMillis()
                    )
                    .apply();
        }
    }

    public static void cancelResumeRequired(@NonNull Context context) {
        NotificationManagerCompat.from(context.getApplicationContext())
                .cancel(RECOVERY_NOTIFICATION_ID);
    }

    public static void cancelAll(@NonNull Context context) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(
                context.getApplicationContext()
        );
        manager.cancel(RECOVERY_NOTIFICATION_ID);
        manager.cancel(BATTERY_NOTIFICATION_ID);
    }

    public static boolean isIgnoringBatteryOptimizations(
            @NonNull Context context
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        PowerManager manager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE
        );
        return manager != null
                && manager.isIgnoringBatteryOptimizations(
                        context.getPackageName()
                );
    }

    @NonNull
    private static NotificationCompat.Builder baseBuilder(
            @NonNull Context context
    ) {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_family)
                .setColor(context.getColor(R.color.fh_module_family))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(true);
    }

    private static boolean notifySafely(
            @NonNull Context context,
            int notificationId,
            @NonNull NotificationCompat.Builder builder
    ) {
        NotificationManagerCompat manager =
                NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return false;
        }

        try {
            manager.notify(notificationId, builder.build());
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static void createChannel(@NonNull Context context) {
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
                        R.string.family_live_recovery_channel
                ),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(context.getString(
                R.string.family_live_recovery_channel_detail
        ));
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private static PendingIntent openAppPendingIntent(
            @NonNull Context context
    ) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                );
        return PendingIntent.getActivity(
                context,
                4121,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @NonNull
    private static PendingIntent resumeSharingPendingIntent(
            @NonNull Context context
    ) {
        return PendingIntent.getForegroundService(
                context,
                4122,
                FamilyLocationService.startIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @NonNull
    private static PendingIntent batterySettingsPendingIntent(
            @NonNull Context context
    ) {
        Intent intent = new Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                context,
                4123,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static boolean shouldShowBatteryNotice(
            @NonNull Context context
    ) {
        long lastShown = preferences(context).getLong(
                KEY_LAST_BATTERY_NOTICE,
                0L
        );
        return System.currentTimeMillis() - lastShown
                >= BATTERY_NOTICE_INTERVAL_MS;
    }

    @NonNull
    private static SharedPreferences preferences(
            @NonNull Context context
    ) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
