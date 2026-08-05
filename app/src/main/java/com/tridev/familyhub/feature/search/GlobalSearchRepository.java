package com.tridev.familyhub.feature.search;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.R;
import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.entity.*;
import com.tridev.familyhub.feature.main.MainActivity;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local, offline and privacy-safe search index across Family Hub modules. */
public class GlobalSearchRepository {
    public interface Callback { void onLoaded(@NonNull List<GlobalSearchResult> results); }
    public static final String FILTER_ALL = "ALL";
    private static final int LIMIT = 80;
    private final FamilyHubDatabase database;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final NumberFormat money = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

    public GlobalSearchRepository(@NonNull Context context) {
        database = FamilyHubDatabase.getInstance(context.getApplicationContext());
    }

    public void search(@NonNull String query, @NonNull String filter,
                       @NonNull Callback callback) {
        executor.execute(() -> {
            String q = GlobalSearchPolicy.normalize(query);
            List<GlobalSearchResult> out = new ArrayList<>();
            if (!q.isEmpty()) collect(q, filter, out);
            main.post(() -> callback.onLoaded(out));
        });
    }

    private void collect(String q, String filter, List<GlobalSearchResult> out) {
        if (accept(filter, "Family")) for (FamilyMember item : database.familyMemberDao().search(q))
            add(out, new GlobalSearchResult("Family", item.name,
                    join(item.relation, item.phone), MainActivity.ROUTE_FAMILY,
                    R.drawable.ic_family, false));
        if (accept(filter, "Reminders")) for (Reminder item : database.reminderDao().search(q))
            add(out, new GlobalSearchResult("Reminders", item.title, item.note,
                    MainActivity.ROUTE_REMINDERS, R.drawable.ic_reminder,
                    item.isEnabled && item.reminderAt > 0 && item.reminderAt < System.currentTimeMillis() + 86400000L));
        if (accept(filter, "Finance")) for (FinanceEntry item : database.financeEntryDao().search(q))
            add(out, new GlobalSearchResult("Finance", item.category,
                    item.entryType + " • " + money.format(item.amount) + " • " + item.transactionDate,
                    MainActivity.ROUTE_FINANCE, R.drawable.ic_wallet, false));
        if (accept(filter, "Grocery")) for (GroceryItem item : database.groceryItemDao().search(q))
            add(out, new GlobalSearchResult("Grocery", item.name,
                    join(item.listType, item.quantity, item.assignedMemberName),
                    MainActivity.ROUTE_GROCERY, R.drawable.ic_grocery,
                    GroceryItem.PRIORITY_URGENT.equals(item.priority) && !item.isPurchased));
        if (accept(filter, "Documents")) for (DocumentEntry item : database.documentDao().search(q))
            add(out, new GlobalSearchResult("Documents", item.title, item.category,
                    MainActivity.ROUTE_DOCUMENTS, R.drawable.ic_document,
                    item.expiryAt > 0 && item.expiryAt < System.currentTimeMillis() + 2592000000L));
        if (accept(filter, "Health")) for (HealthRecordWithMember row : database.healthRecordDao().searchWithMember(q))
            add(out, new GlobalSearchResult("Health", row.record.title,
                    join(row.memberName, row.record.recordType, row.record.value),
                    MainActivity.ROUTE_HEALTH, R.drawable.ic_health, false));
        if (accept(filter, "Vehicles")) for (VehicleWithOwner row : database.vehicleDao().searchWithOwner(q))
            add(out, new GlobalSearchResult("Vehicles", row.vehicle.displayName,
                    join(row.vehicle.registrationNumber, row.ownerName),
                    MainActivity.ROUTE_VEHICLES, R.drawable.ic_vehicle,
                    dueSoon(row.vehicle.insuranceExpiryAt) || dueSoon(row.vehicle.pollutionExpiryAt) || dueSoon(row.vehicle.serviceDueAt)));
        if (accept(filter, "Property")) for (PropertyWithOwner row : database.propertyDao().searchWithOwner(q))
            add(out, new GlobalSearchResult("Property", row.property.title,
                    join(row.property.propertyType, row.property.city, row.ownerName),
                    MainActivity.ROUTE_PROPERTY, R.drawable.ic_property, false));
        if (accept(filter, "Notes")) for (NoteEntry item : database.noteDao().searchActive(q))
            add(out, new GlobalSearchResult("Notes", item.title,
                    join(item.category, item.content), MainActivity.ROUTE_NOTES,
                    R.drawable.ic_note, item.isPinned));
        if (accept(filter, "Planner")) for (PlannerItem item : database.plannerItemDao().search(q))
            add(out, new GlobalSearchResult("Planner", item.title,
                    join(item.itemType, item.location), MainActivity.ROUTE_PLANNER,
                    R.drawable.ic_planner,
                    PlannerItem.PRIORITY_URGENT.equals(item.priority) && !item.isCompleted));
        // Vault secrets remain encrypted: only non-sensitive title and website are indexed.
        if (accept(filter, "Vault")) for (PasswordEntry item : database.passwordEntryDao().search(q))
            add(out, new GlobalSearchResult("Vault", item.title, item.website,
                    MainActivity.ROUTE_VAULT, R.drawable.ic_lock, false));
    }

    private void add(List<GlobalSearchResult> out, GlobalSearchResult result) {
        if (out.size() < LIMIT) out.add(result);
    }
    private boolean accept(String filter, String module) {
        return FILTER_ALL.equals(filter) || module.equals(filter);
    }
    private boolean dueSoon(long when) {
        return when > 0 && when < System.currentTimeMillis() + 2592000000L;
    }
    private String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (value != null && !value.trim().isEmpty()) {
            if (result.length() > 0) result.append(" • ");
            result.append(value.trim());
        }
        String text = result.toString();
        return text.length() > 110 ? text.substring(0, 107) + "…" : text;
    }

    public void close() { executor.shutdownNow(); }
}
