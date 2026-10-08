package com.tridev.familyhub.data.repository;

import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Local identity handling for Health, Vehicle and Property collaboration. */
final class FamilySharedRecordSupport {
    private FamilySharedRecordSupport() { }

    @NonNull static String currentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? "" : user.getUid();
    }

    @NonNull static String cloudId(@NonNull String existing) {
        return existing.trim().isEmpty() ? UUID.randomUUID().toString() : existing;
    }

    static boolean canRetry(boolean shared, @NonNull String familyId,
                            @NonNull String savedUid, @NonNull String currentUid) {
        // Never adopt an unowned legacy pending record into a different account.
        return shared && familyId.isEmpty() && !currentUid.isEmpty()
                && currentUid.equals(savedUid);
    }

    @NonNull static String profileId(@NonNull FamilyMemberDao dao, long localId,
                                    @NonNull String publisherUid) {
        FamilyMember member = dao.getById(localId);
        if (member == null) return "";
        if (!member.cloudProfileId.isEmpty()) return member.cloudProfileId;
        // Numeric Room IDs alone are not portable between phones.
        return "local_" + UUID.nameUUIDFromBytes((publisherUid + ":" + localId)
                .getBytes(StandardCharsets.UTF_8));
    }

    @NonNull static synchronized FamilyMember resolveMember(@NonNull FamilyMemberDao dao,
            @NonNull String familyId, @NonNull String profileId, @NonNull String name) {
        List<FamilyMember> scoped = dao.getForFamily(familyId);
        if (!profileId.isEmpty()) {
            for (FamilyMember member : scoped) {
                if (profileId.equals(member.cloudProfileId)) return member;
            }
        } else {
            // Older APKs only publish a name. Only use an unambiguous family match.
            FamilyMember match = null;
            for (FamilyMember member : scoped) {
                if (name.equalsIgnoreCase(member.name)) {
                    if (match != null) { match = null; break; }
                    match = member;
                }
            }
            if (match != null) return match;
        }
        String resolvedId = profileId.isEmpty()
                ? "shared_" + UUID.nameUUIDFromBytes((familyId + ":" + name)
                    .getBytes(StandardCharsets.UTF_8)) : profileId;
        for (FamilyMember member : scoped) {
            if (resolvedId.equals(member.cloudProfileId)) return member;
        }
        // A record can arrive before its profile. Keep the record visible without
        // inventing account membership or publishing this minimal local profile.
        FamilyMember member = new FamilyMember();
        member.name = name.trim().isEmpty() ? "Family member" : name;
        member.cloudProfileId = resolvedId;
        member.ownerFamilyId = familyId;
        member.createdAt = System.currentTimeMillis();
        member.updatedAt = 0L; // The full cloud profile must be able to replace this.
        member.syncPending = false;
        member.id = dao.insert(member);
        return member;
    }
}
