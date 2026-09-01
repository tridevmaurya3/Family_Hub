package com.tridev.familyhub.feature.grocery.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.GroceryItem;
import com.tridev.familyhub.data.repository.GroceryRepository;
import com.tridev.familyhub.feature.grocery.GroceryRecurrenceEngine;

import java.util.ArrayList;
import java.util.List;

/** Supplies pending household items to the Grocery widget collection. */
public class GroceryWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new GroceryFactory(getApplicationContext());
    }

    private static final class GroceryFactory implements RemoteViewsFactory {
        private final Context context;
        private final List<GroceryItem> items = new ArrayList<>();

        GroceryFactory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() { }
        @Override public void onDestroy() { items.clear(); }

        @Override
        public void onDataSetChanged() {
            items.clear();
            List<GroceryItem> all = FamilyHubDatabase.getInstance(context)
                    .groceryItemDao().getAll();
            List<GroceryItem> pending = FamilyHubDatabase.getInstance(context)
                    .groceryItemDao().getPendingForWidget();
            GroceryRepository.annotateRecurrence(
                    pending, all, System.currentTimeMillis());
            for (GroceryItem item : pending) {
                if (!item.recurrenceShadowed) items.add(item);
            }
        }

        @Override public int getCount() { return items.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            GroceryItem item = items.get(position);
            RemoteViews views = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_grocery_item
            );
            views.setTextViewText(R.id.widget_item_name, item.name);
            String detail = item.quantity.isEmpty()
                    ? item.category
                    : item.quantity + " • " + item.category;
            String effective = GroceryRecurrenceEngine.effectiveCycle(
                    item, System.currentTimeMillis());
            String type = GroceryItem.LIST_THREE_MONTH.equals(effective) ? "Fortnightly"
                    : GroceryItem.LIST_TWO_MONTH.equals(effective) ? "Weekly"
                    : GroceryItem.LIST_MONTHLY.equals(effective) ? "Monthly" : "Daily";
            String badge = GroceryRecurrenceEngine.badgeLabel(
                    item, System.currentTimeMillis());
            if (!badge.isEmpty()) type += " • " + badge;
            detail = type + " • " + detail;
            if (!item.assignedMemberName.isEmpty()) {
                detail += " • " + item.assignedMemberName;
            }
            views.setTextViewText(R.id.widget_item_detail, detail);
            Intent fillIntent = new Intent();
            fillIntent.putExtra(
                    GroceryWidgetProvider.EXTRA_ITEM_ID,
                    item.id
            );
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIntent);
            return views;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @Override public boolean hasStableIds() { return true; }
    }
}
