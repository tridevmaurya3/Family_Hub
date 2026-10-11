package com.tridev.familyhub.data.repository;

import com.google.firebase.database.DataSnapshot;

/** Firebase exposes numbers as Long/Double; Number.class is not a supported target. */
final class FirebaseNumbers {
    private FirebaseNumbers() { }
    static Number read(DataSnapshot snapshot) {
        Object value = snapshot.getValue();
        return value instanceof Number ? (Number)value : null;
    }
}
