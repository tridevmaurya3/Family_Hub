package com.tridev.familyhub.feature.documents;

import org.junit.Test;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DocumentExpiryPolicyTest {

    @Test
    public void noExpiryIsNeverNotified() {
        long now = noonOn(2026, Calendar.AUGUST, 4);
        assertEquals(
                DocumentExpiryPolicy.STATUS_NO_EXPIRY,
                DocumentExpiryPolicy.status(0L, now, 30)
        );
        assertFalse(DocumentExpiryPolicy.shouldNotify(0L, now, 30));
    }

    @Test
    public void documentInsideReminderWindowIsExpiring() {
        long now = noonOn(2026, Calendar.AUGUST, 4);
        long expiry = DocumentExpiryPolicy.startOfDay(now)
                + TimeUnit.DAYS.toMillis(15L);
        assertEquals(
                DocumentExpiryPolicy.STATUS_EXPIRING,
                DocumentExpiryPolicy.status(expiry, now, 30)
        );
        assertTrue(DocumentExpiryPolicy.shouldNotify(expiry, now, 30));
        assertEquals(15L, DocumentExpiryPolicy.daysRemaining(expiry, now));
    }

    @Test
    public void futureDocumentOutsideWindowRemainsValid() {
        long now = noonOn(2026, Calendar.AUGUST, 4);
        long expiry = DocumentExpiryPolicy.startOfDay(now)
                + TimeUnit.DAYS.toMillis(90L);
        assertEquals(
                DocumentExpiryPolicy.STATUS_VALID,
                DocumentExpiryPolicy.status(expiry, now, 30)
        );
        assertFalse(DocumentExpiryPolicy.shouldNotify(expiry, now, 30));
    }

    @Test
    public void pastDateIsExpired() {
        long now = noonOn(2026, Calendar.AUGUST, 4);
        long expiry = DocumentExpiryPolicy.startOfDay(now)
                - TimeUnit.DAYS.toMillis(1L);
        assertEquals(
                DocumentExpiryPolicy.STATUS_EXPIRED,
                DocumentExpiryPolicy.status(expiry, now, 30)
        );
        assertTrue(DocumentExpiryPolicy.shouldNotify(expiry, now, 30));
        assertEquals(-1L, DocumentExpiryPolicy.daysRemaining(expiry, now));
    }

    private static long noonOn(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, 12, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
