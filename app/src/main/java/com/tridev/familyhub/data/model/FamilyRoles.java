package com.tridev.familyhub.data.model;

import androidx.annotation.NonNull;

public final class FamilyRoles {

    public static final String OWNER_ADMIN = "OWNER_ADMIN";
    public static final String GUARDIAN = "GUARDIAN";
    public static final String ADULT_MEMBER = "ADULT_MEMBER";
    public static final String CHILD = "CHILD";
    public static final String SENIOR_CITIZEN = "SENIOR_CITIZEN";
    public static final String GUEST = "GUEST";

    private FamilyRoles() {
    }

    public static boolean isAssignable(@NonNull String role) {
        return GUARDIAN.equals(role)
                || ADULT_MEMBER.equals(role)
                || CHILD.equals(role)
                || SENIOR_CITIZEN.equals(role)
                || GUEST.equals(role);
    }
}
