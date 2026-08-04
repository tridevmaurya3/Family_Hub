package com.tridev.familyhub.feature.grocery.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Resizable household shopping widget backed by the Room grocery list. */
public class GroceryWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TOGGLE_PURCHASED =
            "com.tridev.familyhub.action.GROCERY_WIDGET_TOGGLE";
    public static final String EXTRA_ITEM_ID = "grocery_item_id";
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(
            Context context,
            AppWidgetManager manager,
            int[] widgetIds
    ) {
        for (int widgetId : widgetIds) {
            updateWidget(context, manager, widgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION_TOGGLE_PURCHASED.equals(intent.getAction())) {
            return;
        }
        long itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0L);
        if (itemId <= 0L) {
            return;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            FamilyHubDatabase database = FamilyHubDatabase.getInstance(
                    appContext
            );
            GroceryItem item = database.groceryItemDao().getById(itemId);
            if (item != null) {
                item.isPurchased = true;
                item.purchasedAt = System.currentTimeMillis();
                database.groceryItemDao().update(item);
            }
            refreshAll(appContext);
        });
    }

    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(
                context,
                GroceryWidgetProvider.class
        );
        int[] ids = manager.getAppWidgetIds(provider);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_grocery_list);
    }

    private static void updateWidget(
            Context context,
            AppWidgetManager manager,
            int widgetId
    ) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            List<GroceryItem> pending = FamilyHubDatabase.getInstance(
                    appContext
            ).groceryItemDao().getPendingForWidget();
            double total = 0;
            for (GroceryItem item : pending) {
                total += Math.max(0, item.estimatedCost);
            }

            RemoteViews views = new RemoteViews(
                    appContext.getPackageName(),
                    R.layout.widget_grocery
            );
            views.setTextViewText(
                    R.id.widget_pending_count,
                    appContext.getString(
                            R.string.grocery_widget_pending,
                            pending.size()
                    )
            );
            views.setTextViewText(
                    R.id.widget_estimated_total,
                    NumberFormat.getCurrencyInstance(
                            new Locale("en", "IN")
                    ).format(total)
            );

            Intent serviceIntent = new Intent(
                    appContext,
                    GroceryWidgetService.class
            );
            serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            serviceIntent.setData(android.net.Uri.parse(
                    serviceIntent.toUri(Intent.URI_INTENT_SCHEME)
            ));
            views.setRemoteAdapter(R.id.widget_grocery_list, serviceIntent);
            views.setEmptyView(
                    R.id.widget_grocery_list,
                    R.id.widget_grocery_empty
            );

            Intent openIntent = new Intent(appContext, MainActivity.class);
            openIntent.putExtra(MainActivity.EXTRA_OPEN_GROCERY, true);
            PendingIntent openPendingIntent = PendingIntent.getActivity(
                    appContext,
                    widgetId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE
            );
            views.setOnClickPendingIntent(
                    R.id.widget_grocery_header,
                    openPendingIntent
            );
            views.setOnClickPendingIntent(
                    R.id.widget_add_item,
                    openPendingIntent
            );

            Intent toggleIntent = new Intent(
                    appContext,
                    GroceryWidgetProvider.class
            );
            toggleIntent.setAction(ACTION_TOGGLE_PURCHASED);
            PendingIntent toggleTemplate = PendingIntent.getBroadcast(
                    appContext,
                    widgetId,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_MUTABLE
            );
            views.setPendingIntentTemplate(
                    R.id.widget_grocery_list,
                    toggleTemplate
            );
            manager.updateAppWidget(widgetId, views);
            manager.notifyAppWidgetViewDataChanged(
                    widgetId,
                    R.id.widget_grocery_list
            );
        });
    }
}
