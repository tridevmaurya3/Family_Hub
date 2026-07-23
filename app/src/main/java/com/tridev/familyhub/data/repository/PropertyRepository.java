package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.PropertyDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.PropertyWithOwner;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repository boundary for private family property profiles. */
public class PropertyRepository {

    public interface PropertiesCallback {
        void onPropertiesLoaded(@NonNull List<PropertyWithOwner> properties);
    }

    public interface MembersCallback {
        void onMembersLoaded(@NonNull List<FamilyMember> members);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final PropertyDao propertyDao;
    private final FamilyMemberDao familyMemberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PropertyRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        propertyDao = database.propertyDao();
        familyMemberDao = database.familyMemberDao();
    }

    public void loadProperties(
            @NonNull String query,
            @NonNull PropertiesCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            String trimmedQuery = query.trim();
            List<PropertyWithOwner> properties = trimmedQuery.isEmpty()
                    ? propertyDao.getAllWithOwner()
                    : propertyDao.searchWithOwner(trimmedQuery);
            mainHandler.post(() -> callback.onPropertiesLoaded(properties));
        });
    }

    public void loadMembers(@NonNull MembersCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members = familyMemberDao.getAll();
            mainHandler.post(() -> callback.onMembersLoaded(members));
        });
    }

    public void save(
            @NonNull PropertyEntry property,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            if (property.createdAt == 0L) {
                property.createdAt = System.currentTimeMillis();
            }
            if (property.id == 0L) {
                property.id = propertyDao.insert(property);
            } else {
                propertyDao.update(property);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void delete(
            @NonNull PropertyEntry property,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            propertyDao.delete(property);
            mainHandler.post(callback::onComplete);
        });
    }
}
