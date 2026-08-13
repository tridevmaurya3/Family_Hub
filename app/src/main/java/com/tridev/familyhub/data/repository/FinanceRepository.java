package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FinanceEntryDao;
import com.tridev.familyhub.data.local.dao.FinanceAccountDao;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.FinanceAccount;
import com.tridev.familyhub.data.local.entity.FinanceSummary;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Data boundary for the offline finance feature. */
public class FinanceRepository {

    public interface EntriesCallback {
        void onEntriesLoaded(List<FinanceEntry> entries);
    }

    public interface SummaryCallback {
        void onSummaryLoaded(FinanceSummary summary);
    }

    public interface ActionCallback {
        void onComplete();
    }
    public interface AccountsCallback { void onLoaded(List<FinanceAccount> accounts); }

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private final FinanceEntryDao financeEntryDao;
    private final FinanceAccountDao financeAccountDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private DatabaseReference sharedEntriesReference;
    @Nullable private ValueEventListener sharedEntriesListener;

    public FinanceRepository(Context context) {
        financeEntryDao = FamilyHubDatabase.getInstance(context).financeEntryDao();
        financeAccountDao = FamilyHubDatabase.getInstance(context).financeAccountDao();
    }

    public void loadEntries(@NonNull String searchQuery, @NonNull EntriesCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FinanceEntry> entries = searchQuery.trim().isEmpty()
                    ? financeEntryDao.getAll()
                    : financeEntryDao.search(searchQuery.trim());
            mainHandler.post(() -> callback.onEntriesLoaded(entries));
        });
    }

    public void loadCurrentMonthSummary(@NonNull SummaryCallback callback) {
        String monthPrefix = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        DATABASE_EXECUTOR.execute(() -> {
            FinanceSummary summary = financeEntryDao.getMonthSummary(monthPrefix);
            mainHandler.post(() -> callback.onSummaryLoaded(summary));
        });
    }

    public void save(FinanceEntry entry, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            entry.updatedAt = System.currentTimeMillis();
            ensureAccount(entry.accountName);
            prepareRecurrenceMetadata(entry);
            if (entry.isShared && entry.cloudId.trim().isEmpty()) {
                entry.cloudId = UUID.randomUUID().toString();
            }
            if (entry.id == 0) {
                entry.createdAt = entry.updatedAt;
                entry.id = financeEntryDao.insert(entry);
            } else {
                financeEntryDao.update(entry);
            }
            if (entry.isShared) {
                publishShared(entry);
            } else if (!entry.familyId.isEmpty() && !entry.cloudId.isEmpty()) {
                removeShared(entry.familyId, entry.cloudId);
                entry.cloudId = "";
                entry.familyId = "";
                entry.updatedByUid = "";
                entry.updatedByName = "";
                financeEntryDao.update(entry);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void loadAccounts(@NonNull AccountsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FinanceAccount> accounts = financeAccountDao.getActiveWithBalances();
            mainHandler.post(() -> callback.onLoaded(accounts));
        });
    }

    public void saveAccount(@NonNull FinanceAccount account, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            account.updatedAt = System.currentTimeMillis();
            FinanceAccount existing = financeAccountDao.getByName(account.name);
            if (existing == null) financeAccountDao.insert(account);
            else { account.id = existing.id; financeAccountDao.update(account); }
            mainHandler.post(callback::onComplete);
        });
    }

    private void ensureAccount(String name) {
        if (name == null || name.trim().isEmpty() || financeAccountDao.getByName(name.trim()) != null) return;
        FinanceAccount account = new FinanceAccount(); account.name = name.trim();
        account.accountType = "OTHER"; account.updatedAt = System.currentTimeMillis();
        financeAccountDao.insert(account);
    }

    /** Creates exactly one occurrence per recurring series for the current month. */
    public void runRecurringAutomation(@NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            Calendar today = Calendar.getInstance();
            String currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US)
                    .format(today.getTime());
            Map<String, FinanceEntry> series = new HashMap<>();
            for (FinanceEntry entry : financeEntryDao.getRecurringEntries()) {
                if (entry.recurrenceSeriesId.isEmpty()) {
                    entry.recurrenceSeriesId = UUID.randomUUID().toString();
                    entry.recurrenceMonth = monthOf(entry.transactionDate);
                    entry.recurrenceDay = dayOf(entry.transactionDate);
                    entry.recurrenceStatus = "POSTED";
                    entry.updatedAt = System.currentTimeMillis();
                    financeEntryDao.update(entry);
                    if (entry.isShared) publishShared(entry);
                }
                FinanceEntry anchor = series.get(entry.recurrenceSeriesId);
                if (anchor == null || entry.transactionDate.compareTo(anchor.transactionDate) < 0) {
                    series.put(entry.recurrenceSeriesId, entry);
                }
                if (currentMonth.equals(entry.recurrenceMonth)) {
                    String expected = dueStatus(entry.transactionDate, today);
                    if (!expected.equals(entry.recurrenceStatus)) {
                        entry.recurrenceStatus = expected;
                        entry.updatedAt = System.currentTimeMillis();
                        financeEntryDao.update(entry);
                        if (entry.isShared) publishShared(entry);
                    }
                }
            }
            for (FinanceEntry anchor : series.values()) {
                if (anchor.recurrenceMonth.compareTo(currentMonth) >= 0
                        || financeEntryDao.countRecurringOccurrence(
                        anchor.recurrenceSeriesId, currentMonth) > 0) continue;
                FinanceEntry generated = recurringCopy(anchor, currentMonth, today);
                generated.id = financeEntryDao.insert(generated);
                if (generated.isShared) publishShared(generated);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void delete(FinanceEntry entry, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            financeEntryDao.delete(entry);
            if (entry.isShared) {
                removeShared(entry.familyId, entry.cloudId);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    /** Starts family-scoped realtime reconciliation. Private entries are never uploaded. */
    public void startSharedSync(@NonNull ActionCallback onChanged) {
        stopSharedSync();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override public void onSuccess(FamilyAccountRepository.SessionState state) {
                        if (state == null || !state.isActive() || state.familyId == null) return;
                        attachSharedListener(state.familyId, onChanged);
                    }
                    @Override public void onError(@NonNull Exception error) { }
                });
    }

    public void stopSharedSync() {
        if (sharedEntriesReference != null && sharedEntriesListener != null) {
            sharedEntriesReference.removeEventListener(sharedEntriesListener);
        }
        sharedEntriesReference = null;
        sharedEntriesListener = null;
    }

    private void publishShared(@NonNull FinanceEntry entry) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        new FamilyAccountRepository().loadSession(
                new FamilyAccountRepository.ResultCallback<FamilyAccountRepository.SessionState>() {
                    @Override public void onSuccess(FamilyAccountRepository.SessionState state) {
                        if (state == null || !state.isActive() || state.familyId == null) return;
                        entry.familyId = state.familyId;
                        entry.updatedByUid = user.getUid();
                        String displayName = user.getDisplayName();
                        entry.updatedByName = displayName == null || displayName.trim().isEmpty()
                                ? "Family member" : displayName.trim();
                        Map<String, Object> values = toCloudValues(entry);
                        FirebaseDatabase.getInstance().getReference()
                                .child("sharedModules").child(state.familyId)
                                .child("finance").child(entry.cloudId).setValue(values);
                        DATABASE_EXECUTOR.execute(() -> financeEntryDao.update(entry));
                    }
                    @Override public void onError(@NonNull Exception error) { }
                });
    }

    private void attachSharedListener(@NonNull String familyId,
                                      @NonNull ActionCallback onChanged) {
        sharedEntriesReference = FirebaseDatabase.getInstance().getReference()
                .child("sharedModules").child(familyId).child("finance");
        sharedEntriesListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                DATABASE_EXECUTOR.execute(() -> {
                    Set<String> remoteIds = new HashSet<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        FinanceEntry remote = fromSnapshot(child, familyId);
                        if (remote == null) continue;
                        remoteIds.add(remote.cloudId);
                        FinanceEntry local = financeEntryDao.getByCloudId(remote.cloudId);
                        if (local == null) {
                            financeEntryDao.insert(remote);
                        } else if (remote.updatedAt >= local.updatedAt) {
                            remote.id = local.id;
                            remote.createdAt = local.createdAt == 0 ? remote.createdAt : local.createdAt;
                            financeEntryDao.update(remote);
                        }
                    }
                    for (FinanceEntry local : financeEntryDao.getSharedForFamily(familyId)) {
                        if (!local.cloudId.isEmpty() && !remoteIds.contains(local.cloudId)) {
                            financeEntryDao.deleteByCloudId(local.cloudId);
                        }
                    }
                    mainHandler.post(onChanged::onComplete);
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        sharedEntriesReference.addValueEventListener(sharedEntriesListener);
    }

    private static Map<String, Object> toCloudValues(FinanceEntry entry) {
        Map<String, Object> values = new HashMap<>();
        values.put("cloudId", entry.cloudId); values.put("familyId", entry.familyId);
        values.put("entryType", entry.entryType); values.put("amount", entry.amount);
        values.put("category", entry.category); values.put("note", entry.note);
        values.put("transactionDate", entry.transactionDate);
        values.put("accountName", entry.accountName); values.put("paymentMethod", entry.paymentMethod);
        values.put("recurring", entry.isRecurring); values.put("shared", true);
        values.put("createdAt", entry.createdAt); values.put("updatedAt", entry.updatedAt);
        values.put("updatedByUid", entry.updatedByUid); values.put("updatedByName", entry.updatedByName);
        values.put("recurrenceSeriesId", entry.recurrenceSeriesId);
        values.put("recurrenceMonth", entry.recurrenceMonth);
        values.put("recurrenceStatus", entry.recurrenceStatus);
        values.put("recurrenceDay", entry.recurrenceDay);
        values.put("paidByName", entry.paidByName); values.put("splitType", entry.splitType);
        values.put("participantNames", entry.participantNames); values.put("splitAmounts", entry.splitAmounts);
        values.put("settlementStatus", entry.settlementStatus);
        return values;
    }

    @Nullable private static FinanceEntry fromSnapshot(DataSnapshot value, String familyId) {
        String cloudId = text(value, "cloudId");
        String type = text(value, "entryType");
        Double amount = value.child("amount").getValue(Double.class);
        if (cloudId.isEmpty() || amount == null || amount <= 0
                || (!FinanceEntry.TYPE_EXPENSE.equals(type) && !FinanceEntry.TYPE_INCOME.equals(type))) return null;
        FinanceEntry entry = new FinanceEntry();
        entry.cloudId = cloudId; entry.familyId = familyId; entry.entryType = type; entry.amount = amount;
        entry.category = text(value, "category"); entry.note = text(value, "note");
        entry.transactionDate = text(value, "transactionDate"); entry.accountName = text(value, "accountName");
        entry.paymentMethod = text(value, "paymentMethod");
        entry.isRecurring = Boolean.TRUE.equals(value.child("recurring").getValue(Boolean.class));
        entry.isShared = true; entry.createdAt = number(value, "createdAt"); entry.updatedAt = number(value, "updatedAt");
        entry.updatedByUid = text(value, "updatedByUid"); entry.updatedByName = text(value, "updatedByName");
        entry.recurrenceSeriesId = text(value, "recurrenceSeriesId");
        entry.recurrenceMonth = text(value, "recurrenceMonth");
        entry.recurrenceStatus = text(value, "recurrenceStatus");
        entry.recurrenceDay = integer(value, "recurrenceDay");
        entry.paidByName = text(value, "paidByName"); entry.splitType = text(value, "splitType");
        entry.participantNames = text(value, "participantNames"); entry.splitAmounts = text(value, "splitAmounts");
        entry.settlementStatus = text(value, "settlementStatus");
        return entry;
    }

    private static void prepareRecurrenceMetadata(FinanceEntry entry) {
        if (!entry.isRecurring) {
            entry.recurrenceSeriesId = ""; entry.recurrenceMonth = "";
            entry.recurrenceStatus = ""; entry.recurrenceDay = 0;
            return;
        }
        if (entry.recurrenceSeriesId.isEmpty()) entry.recurrenceSeriesId = UUID.randomUUID().toString();
        entry.recurrenceMonth = monthOf(entry.transactionDate);
        entry.recurrenceDay = dayOf(entry.transactionDate);
        entry.recurrenceStatus = "POSTED";
    }

    private static FinanceEntry recurringCopy(FinanceEntry source, String month, Calendar today) {
        FinanceEntry copy = new FinanceEntry();
        copy.entryType = source.entryType; copy.amount = source.amount;
        copy.category = source.category; copy.note = source.note;
        copy.recurrenceDay = Math.max(1, source.recurrenceDay);
        Calendar due = (Calendar) today.clone();
        due.set(Calendar.DAY_OF_MONTH, Math.min(copy.recurrenceDay,
                due.getActualMaximum(Calendar.DAY_OF_MONTH)));
        copy.transactionDate = month + "-" + String.format(Locale.US, "%02d",
                due.get(Calendar.DAY_OF_MONTH));
        copy.accountName = source.accountName; copy.paymentMethod = source.paymentMethod;
        copy.isRecurring = true; copy.isShared = source.isShared;
        copy.recurrenceSeriesId = source.recurrenceSeriesId; copy.recurrenceMonth = month;
        copy.recurrenceStatus = dueStatus(copy.transactionDate, today);
        copy.createdAt = System.currentTimeMillis(); copy.updatedAt = copy.createdAt;
        copy.paidByName = source.paidByName; copy.splitType = source.splitType;
        copy.participantNames = source.participantNames; copy.splitAmounts = source.splitAmounts;
        copy.settlementStatus = source.settlementStatus;
        if (copy.isShared) {
            // Deterministic identity prevents two family devices creating duplicates.
            copy.cloudId = "rec_" + source.recurrenceSeriesId + "_" + month;
        }
        return copy;
    }

    private static String dueStatus(String date, Calendar today) {
        String todayText = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(today.getTime());
        return date.compareTo(todayText) > 0 ? "UPCOMING" : "POSTED";
    }

    private static String monthOf(String date) {
        return date != null && date.length() >= 7 ? date.substring(0, 7) : "";
    }

    private static int dayOf(String date) {
        try { return date != null && date.length() >= 10 ? Integer.parseInt(date.substring(8, 10)) : 1; }
        catch (NumberFormatException ignored) { return 1; }
    }

    private static String text(DataSnapshot value, String key) {
        String text = value.child(key).getValue(String.class); return text == null ? "" : text;
    }

    private static long number(DataSnapshot value, String key) {
        Long number = value.child(key).getValue(Long.class); return number == null ? 0L : number;
    }

    private static int integer(DataSnapshot value, String key) {
        Long number = value.child(key).getValue(Long.class); return number == null ? 0 : number.intValue();
    }

    private static void removeShared(String familyId, String cloudId) {
        if (familyId.isEmpty() || cloudId.isEmpty()) return;
        FirebaseDatabase.getInstance().getReference().child("sharedModules").child(familyId)
                .child("finance").child(cloudId).removeValue();
    }
}
