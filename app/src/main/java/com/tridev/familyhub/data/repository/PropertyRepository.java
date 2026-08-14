package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.firebase.database.DataSnapshot;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.PropertyDao;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.PropertyWithOwner;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    public interface DocumentsCallback {
        void onDocumentsLoaded(@NonNull List<DocumentEntry> documents);
    }

    public interface ActionCallback {
        void onComplete();
    }

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final PropertyDao propertyDao;
    private final FamilyMemberDao familyMemberDao;
    private final DocumentDao documentDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FamilyCollaborationSubscriber subscriber;

    public PropertyRepository(@NonNull Context context) {
        FamilyHubDatabase database = FamilyHubDatabase.getInstance(context);
        propertyDao = database.propertyDao();
        familyMemberDao = database.familyMemberDao();
        documentDao = database.documentDao();
    }

    public void startRealtimeSync(@NonNull Runnable changed) {
        stopRealtimeSync();
        subscriber = new FamilyCollaborationSubscriber("properties",
                new FamilyCollaborationSubscriber.Callback() {
                    @Override public void onChanged(@NonNull String familyId,
                            @NonNull DataSnapshot snapshot) {
                        mergeRemote(familyId, snapshot, changed);
                    }
                    @Override public void onRemoved(@NonNull String familyId,
                            @NonNull String cloudId) {
                        DATABASE_EXECUTOR.execute(() -> {
                            PropertyEntry local = propertyDao.getByCloudId(cloudId);
                            if (local == null || !local.isShared) return;
                            propertyDao.delete(local); mainHandler.post(changed);
                        });
                    }
                });
        subscriber.start();
    }

    public void stopRealtimeSync() {
        if (subscriber != null) subscriber.stop();
        subscriber = null;
    }

    public void loadDocuments(@NonNull DocumentsCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<DocumentEntry> documents = documentDao.getAll();
            mainHandler.post(() -> callback.onDocumentsLoaded(documents));
        });
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
            reconcileDocumentLinks(properties);
            mainHandler.post(() -> callback.onPropertiesLoaded(properties));
        });
    }

    private void reconcileDocumentLinks(
            @NonNull List<PropertyWithOwner> properties
    ) {
        for (PropertyWithOwner item : properties) {
            PropertyEntry property = item.property;
            if (property.linkedDocumentId <= 0L
                    && property.linkedDocumentTitle.isEmpty()) continue;
            DocumentEntry document = property.linkedDocumentId > 0L
                    ? documentDao.getById(property.linkedDocumentId)
                    : documentDao.getActiveByTitle(property.linkedDocumentTitle);
            if (property.linkedDocumentId <= 0L && document == null) continue;
            String currentTitle = document == null || document.deletedAt > 0L
                    ? "" : document.title;
            long currentId = currentTitle.isEmpty()
                    ? 0L : document.id;
            if (property.linkedDocumentId == currentId
                    && property.linkedDocumentTitle.equals(currentTitle)) {
                continue;
            }
            property.linkedDocumentId = currentId;
            property.linkedDocumentTitle = currentTitle;
            propertyDao.update(property);
        }
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
            property.updatedAt = System.currentTimeMillis();
            if (property.id == 0L) {
                property.id = propertyDao.insert(property);
            } else {
                propertyDao.update(property);
            }
            if (property.isShared) publish(property);
            else {
                String familyId = property.familyId, cloudId = property.cloudId;
                property.familyId = ""; property.cloudId = "";
                property.updatedByUid = ""; propertyDao.update(property);
                FamilyCollaborationPublisher.remove("properties", familyId, cloudId);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    private void publish(@NonNull PropertyEntry p) {
        Map<String, Object> v = new HashMap<>();
        v.put("ownerName", p.assignedOwnerName); v.put("propertyType", p.propertyType);
        v.put("title", p.title); v.put("address", p.address); v.put("city", p.city);
        v.put("state", p.state); v.put("postalCode", p.postalCode); v.put("area", p.area);
        v.put("purchaseValue", p.purchaseValue); v.put("estimatedValue", p.estimatedValue);
        v.put("purchaseDate", p.purchaseDate); v.put("registrationReference", p.registrationReference);
        v.put("notes", p.notes); v.put("linkedDocumentTitle", p.linkedDocumentTitle);
        v.put("timelineNote", p.timelineNote); v.put("shared", true); v.put("createdAt", p.createdAt);
        FamilyCollaborationPublisher.publish("properties", p.cloudId, v,
                (cloudId, familyId, uid) -> DATABASE_EXECUTOR.execute(() -> {
                    p.cloudId=cloudId; p.familyId=familyId; p.updatedByUid=uid; propertyDao.update(p);
                }));
    }

    private void mergeRemote(@NonNull String familyId, @NonNull DataSnapshot s,
                             @NonNull Runnable changed) {
        DATABASE_EXECUTOR.execute(() -> {
            String cloudId=text(s,"cloudId"); if(cloudId.isEmpty()) return;
            long updated=number(s,"updatedAt"); PropertyEntry p=propertyDao.getByCloudId(cloudId);
            if(p!=null && p.updatedAt>updated) return; boolean insert=p==null;
            if(insert) p=new PropertyEntry(); FamilyMember owner=familyMemberDao.getByName(text(s,"ownerName"));
            if(owner==null) return; p.cloudId=cloudId; p.familyId=familyId;
            p.ownerMemberId=owner.id; p.assignedOwnerName=owner.name;
            p.propertyType=fallback(text(s,"propertyType"),PropertyEntry.TYPE_OTHER);
            p.title=text(s,"title"); p.address=text(s,"address"); p.city=text(s,"city");
            p.state=text(s,"state"); p.postalCode=text(s,"postalCode"); p.area=text(s,"area");
            p.purchaseValue=decimal(s,"purchaseValue"); p.estimatedValue=decimal(s,"estimatedValue");
            p.purchaseDate=number(s,"purchaseDate"); p.registrationReference=text(s,"registrationReference");
            p.notes=text(s,"notes"); p.linkedDocumentTitle=text(s,"linkedDocumentTitle");
            p.timelineNote=text(s,"timelineNote"); p.isShared=true; p.updatedAt=updated;
            p.updatedByUid=text(s,"updatedByUid"); p.createdAt=number(s,"createdAt");
            if(p.createdAt==0L) p.createdAt=updated;
            if(insert) p.id=propertyDao.insert(p); else propertyDao.update(p); mainHandler.post(changed);
        });
    }

    @NonNull private static String text(DataSnapshot s,String k){String v=s.child(k).getValue(String.class);return v==null?"":v;}
    private static long number(DataSnapshot s,String k){Number v=s.child(k).getValue(Number.class);return v==null?0L:v.longValue();}
    private static double decimal(DataSnapshot s,String k){Number v=s.child(k).getValue(Number.class);return v==null?0d:v.doubleValue();}
    @NonNull private static String fallback(String v,String f){return v.isEmpty()?f:v;}

    public void delete(
            @NonNull PropertyEntry property,
            @NonNull ActionCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            FamilyCollaborationPublisher.remove("properties", property.familyId, property.cloudId);
            propertyDao.delete(property);
            mainHandler.post(callback::onComplete);
        });
    }
}
