package com.tridev.familyhub.feature.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GlobalSearchPolicyTest {
    @Test public void normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("family grocery", GlobalSearchPolicy.normalize("  family   grocery  "));
    }
    @Test public void normalizeCapsVeryLongQueries() {
        String value = new String(new char[120]).replace('\0', 'a');
        assertEquals(GlobalSearchPolicy.MAX_QUERY_LENGTH,
                GlobalSearchPolicy.normalize(value).length());
    }
    @Test public void normalizeAcceptsHindiText() {
        assertTrue(GlobalSearchPolicy.normalize("  परिवार सदस्य ").startsWith("परिवार"));
    }
}
