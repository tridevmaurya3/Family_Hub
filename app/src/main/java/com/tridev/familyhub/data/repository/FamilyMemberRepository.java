package com.tridev.familyhub.data.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.tridev.familyhub.data.local.FamilyHubDatabase;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps Room work off the UI thread and returns results safely to the screen. */
public class FamilyMemberRepository {

    public interface MembersCallback {
        void onMembersLoaded(List<FamilyMember> members);
    }

    public interface ActionCallback {
        void onComplete();
    }

    public interface UniquenessCallback {
        void onChecked(boolean phoneAvailable, boolean emailAvailable);
    }

    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor();

    private final FamilyMemberDao memberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FamilyMemberRepository(Context context) {
        memberDao = FamilyHubDatabase.getInstance(context).familyMemberDao();
    }

    public void loadMembers(@NonNull String searchQuery, @NonNull MembersCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            List<FamilyMember> members = searchQuery.trim().isEmpty()
                    ? memberDao.getAll()
                    : memberDao.search(searchQuery.trim());
            mainHandler.post(() -> callback.onMembersLoaded(members));
        });
    }

    public void save(FamilyMember member, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            if (member.id == 0) {
                member.createdAt = System.currentTimeMillis();
                memberDao.insert(member);
            } else {
                memberDao.update(member);
            }
            mainHandler.post(callback::onComplete);
        });
    }

    public void delete(FamilyMember member, @NonNull ActionCallback callback) {
        DATABASE_EXECUTOR.execute(() -> {
            memberDao.delete(member);
            mainHandler.post(callback::onComplete);
        });
    }

    public void checkUniqueContact(
            long memberId,
            @NonNull String phone,
            @NonNull String email,
            @NonNull UniquenessCallback callback
    ) {
        DATABASE_EXECUTOR.execute(() -> {
            boolean phoneAvailable = phone.trim().isEmpty()
                    || memberDao.countOtherMembersWithPhone(
                    phone.trim(), memberId
            ) == 0;
            boolean emailAvailable = email.trim().isEmpty()
                    || memberDao.countOtherMembersWithEmail(
                    email.trim(), memberId
            ) == 0;
            mainHandler.post(() -> callback.onChecked(
                    phoneAvailable,
                    emailAvailable
            ));
        });
    }
}
