package com.tridev.familyhub.data.repository;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.snapshot.IndexedNode;
import com.google.firebase.database.snapshot.NodeUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class FirebaseNumbersTest {
    private DataSnapshot snapshot(Object value) {
        return new DataSnapshot(null,IndexedNode.from(NodeUtilities.NodeFromJSON(value)));
    }
    @Test public void actualFirebaseSnapshotReadsServerTimestampWithoutTypedNumberDeserialization() {
        assertEquals(1791720000123L,FirebaseNumbers.read(snapshot(1791720000123L)).longValue());
    }
    @Test public void actualFirebaseSnapshotKeepsDecimalPrecision() {
        assertEquals(1250.75,FirebaseNumbers.read(snapshot(1250.75)).doubleValue(),0d);
    }
    @Test public void absentAndMalformedNumbersKeepTheExistingFallback() {
        assertNull(FirebaseNumbers.read(snapshot(null)));
        assertNull(FirebaseNumbers.read(snapshot("not-a-number")));
        assertNull(FirebaseNumbers.read(snapshot(true)));
    }
}
