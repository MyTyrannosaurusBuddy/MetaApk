package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebFetchTest {

    @Test
    public void looksLikeImageUrl_matchesCommonImageExtensions() {
        assertTrue(WebFetch.looksLikeImageUrl("https://example.com/a.png"));
        assertTrue(WebFetch.looksLikeImageUrl("https://example.com/a.JPG"));
        assertTrue(WebFetch.looksLikeImageUrl("https://example.com/a.jpeg"));
        assertTrue(WebFetch.looksLikeImageUrl("https://example.com/a.gif"));
        assertTrue(WebFetch.looksLikeImageUrl("https://example.com/a.webp"));
        assertTrue(WebFetch.looksLikeImageUrl("https://example.com/a.png?width=400"));
    }

    @Test
    public void looksLikeImageUrl_rejectsTextAndOtherSchemes() {
        assertFalse(WebFetch.looksLikeImageUrl("https://example.com/doc.html"));
        assertFalse(WebFetch.looksLikeImageUrl("https://example.com/data.json"));
        assertFalse(WebFetch.looksLikeImageUrl("https://example.com/no-extension"));
        assertFalse(WebFetch.looksLikeImageUrl(null));
        assertFalse(WebFetch.looksLikeImageUrl("ftp://example.com/a.png"));
        // A URL merely containing "png" in the path is not an image link.
        assertFalse(WebFetch.looksLikeImageUrl("https://example.com/png-info.html"));
    }

    @Test
    public void isAllowed_rejectsNonHttpAndEmpty() {
        assertFalse(WebFetch.isAllowed(null));
        assertFalse(WebFetch.isAllowed(""));
        assertFalse(WebFetch.isAllowed("ftp://example.com/x"));
        assertFalse(WebFetch.isAllowed("file:///etc/passwd"));
    }

    @Test
    public void calculateInSampleSize_landsAtOrAboveTarget() {
        // 4000x4000 -> 2000x2000 target: sampling by 2 is exact.
        assertEquals(2, WebFetch.calculateInSampleSize(4000, 4000, 2000, 2000));
        // 4000x4000 -> 1000x1000 target: sampling by 2 gives 2000, by 4 gives
        // 1000 - both halves stay >= target, so 4 is chosen.
        assertEquals(4, WebFetch.calculateInSampleSize(4000, 4000, 1000, 1000));
        // Source smaller than target: no sampling.
        assertEquals(1, WebFetch.calculateInSampleSize(500, 500, 2000, 2000));
        // One dimension dominates: the larger dimension must also fit.
        assertEquals(1, WebFetch.calculateInSampleSize(2200, 100, 2000, 100));
    }
}
