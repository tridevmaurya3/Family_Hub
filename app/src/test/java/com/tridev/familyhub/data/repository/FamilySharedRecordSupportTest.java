package com.tridev.familyhub.data.repository;

import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FamilySharedRecordSupportTest {
    private final List<FamilyMember> rows = new ArrayList<>();
    private final FamilyMemberDao dao = (FamilyMemberDao) Proxy.newProxyInstance(
            FamilyMemberDao.class.getClassLoader(), new Class<?>[]{FamilyMemberDao.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getForFamily": {
                        List<FamilyMember> scoped = new ArrayList<>();
                        for (FamilyMember row : rows) if (row.ownerFamilyId.equals(args[0])) scoped.add(row);
                        return scoped;
                    }
                    case "getById":
                        for (FamilyMember row : rows) if (row.id == (Long) args[0]) return row;
                        return null;
                    case "insert": {
                        FamilyMember row = (FamilyMember) args[0];
                        row.id = rows.size() + 1L; rows.add(row); return row.id;
                    }
                    default: throw new AssertionError("Unexpected DAO method: " + method.getName());
                }
            });

    private FamilyMember add(String family, String profile, String name) {
        FamilyMember row = new FamilyMember();
        row.ownerFamilyId = family; row.cloudProfileId = profile; row.name = name;
        row.id = dao.insert(row); return row;
    }

    @Test public void missingProfileDoesNotDropIncomingRecord() {
        FamilyMember resolved = FamilySharedRecordSupport.resolveMember(dao, "A", "profile1", "Member");
        assertTrue(resolved.id > 0L);
        assertEquals("A", resolved.ownerFamilyId);
        assertEquals("profile1", resolved.cloudProfileId);
        assertFalse(resolved.syncPending);
        assertEquals(0L, resolved.updatedAt);
    }
    @Test public void repeatedSnapshotReusesMember() {
        FamilyMember first = FamilySharedRecordSupport.resolveMember(dao, "A", "profile1", "Member");
        assertSame(first, FamilySharedRecordSupport.resolveMember(dao, "A", "profile1", "Member"));
        assertEquals(1, rows.size());
    }
    @Test public void stableProfileSurvivesDisplayNameChange() {
        FamilyMember existing = add("A", "profile1", "New name");
        assertSame(existing, FamilySharedRecordSupport.resolveMember(dao, "A", "profile1", "Old name"));
        assertEquals("New name", existing.name);
    }
    @Test public void sameNamesUseStableIdentity() {
        add("A", "profile1", "Same");
        FamilyMember second = add("A", "profile2", "Same");
        assertSame(second, FamilySharedRecordSupport.resolveMember(dao, "A", "profile2", "Same"));
    }
    @Test public void otherFamilyProfileCannotBeUsedAsLocalForeignKey() {
        FamilyMember other = add("B", "profile1", "Member");
        FamilyMember local = FamilySharedRecordSupport.resolveMember(dao, "A", "profile1", "Member");
        assertNotEquals(other.id, local.id);
        assertEquals("A", local.ownerFamilyId);
    }
    @Test public void legacyNameUsesOnlyCurrentFamily() {
        add("B", "profileB", "Member");
        FamilyMember local = add("A", "profileA", "Member");
        assertSame(local, FamilySharedRecordSupport.resolveMember(dao, "A", "", "member"));
    }
    @Test public void ambiguousLegacyNameDoesNotChooseFirstPerson() {
        FamilyMember first = add("A", "profile1", "Same");
        FamilyMember second = add("A", "profile2", "Same");
        FamilyMember resolved = FamilySharedRecordSupport.resolveMember(dao, "A", "", "Same");
        assertNotEquals(first.id, resolved.id);
        assertNotEquals(second.id, resolved.id);
        assertEquals(3, rows.size());
    }
    @Test public void blankLegacyNameStillCreatesValidLocalLink() {
        assertEquals("Family member", FamilySharedRecordSupport.resolveMember(dao, "A", "", "").name);
    }
    @Test public void portableProfileIdIsPreferredOverRoomId() {
        FamilyMember member = add("A", "account_uid1", "Member");
        assertEquals("account_uid1", FamilySharedRecordSupport.profileId(dao, member.id, "sender"));
    }
    @Test public void localProfileFallbackIsStableAndAccountScoped() {
        FamilyMember member = add("", "", "Member");
        String first = FamilySharedRecordSupport.profileId(dao, member.id, "sender1");
        assertEquals(first, FamilySharedRecordSupport.profileId(dao, member.id, "sender1"));
        assertNotEquals(first, FamilySharedRecordSupport.profileId(dao, member.id, "sender2"));
    }
    @Test public void recordIdIsStableAcrossRetries() {
        String first = FamilySharedRecordSupport.cloudId("");
        assertFalse(first.isEmpty());
        assertEquals(first, FamilySharedRecordSupport.cloudId(first));
        assertNotEquals(first, FamilySharedRecordSupport.cloudId(""));
    }
    @Test public void retriesDoNotAdoptOtherAccountsOrUnownedLegacyRecords() {
        assertTrue(FamilySharedRecordSupport.canRetry(true, "", "alice", "alice"));
        assertFalse(FamilySharedRecordSupport.canRetry(true, "", "alice", "bob"));
        assertFalse(FamilySharedRecordSupport.canRetry(true, "", "", "alice"));
        assertFalse(FamilySharedRecordSupport.canRetry(true, "", "alice", ""));
        assertFalse(FamilySharedRecordSupport.canRetry(false, "", "alice", "alice"));
        assertFalse(FamilySharedRecordSupport.canRetry(true, "familyA", "alice", "alice"));
    }
}
