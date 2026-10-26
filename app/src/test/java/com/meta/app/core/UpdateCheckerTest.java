package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UpdateCheckerTest {

    @Test
    public void compareVersions_handlesBasicOrdering() {
        assertTrue(UpdateChecker.compareVersions("1.11.0", "1.10.0") > 0);
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.11.0") < 0);
        assertEquals(0, UpdateChecker.compareVersions("1.11.0", "1.11.0"));
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.99.99") > 0);
        assertTrue(UpdateChecker.compareVersions("0.0.1", "0.0.2") < 0);
    }

    @Test
    public void compareVersions_handlesSegmentCountMismatch() {
        assertTrue(UpdateChecker.compareVersions("1.11", "1.10.9") > 0);
        assertEquals(0, UpdateChecker.compareVersions("1.11", "1.11.0"));
        assertTrue(UpdateChecker.compareVersions("1.11.0.1", "1.11.0") > 0);
    }

    @Test
    public void compareVersions_ignoresNonNumericSuffixes() {
        assertEquals(0, UpdateChecker.compareVersions("1.11.0", "1.11.0-beta"));
        assertTrue(UpdateChecker.compareVersions("1.11.1", "1.11.0-beta") > 0);
    }

    @Test
    public void compareVersions_handlesGarbageGracefully() {
        assertEquals(0, UpdateChecker.compareVersions("abc", "xyz"));
        assertEquals(0, UpdateChecker.compareVersions("", ""));
        assertTrue(UpdateChecker.compareVersions("1.0", "abc") > 0);
    }
}
