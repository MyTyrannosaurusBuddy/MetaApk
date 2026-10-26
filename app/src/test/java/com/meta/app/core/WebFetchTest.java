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

    private static byte[] v4(int a, int b, int c, int d) {
        return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private static byte[] v6(int... words) {
        byte[] out = new byte[16];
        for (int i = 0; i < words.length && i < 16; i++) {
            out[i] = (byte) words[i];
        }
        return out;
    }

    @Test
    public void ipv4PrivateRangesAreNotPublic() {
        assertFalse(WebFetch.isPublicAddress(v4(10, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(172, 16, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(172, 31, 255, 254)));
        assertFalse(WebFetch.isPublicAddress(v4(192, 168, 1, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(127, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(0, 0, 0, 0)));
        assertFalse(WebFetch.isPublicAddress(v4(169, 254, 1, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(100, 64, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(100, 127, 255, 254)));
        assertFalse(WebFetch.isPublicAddress(v4(192, 0, 0, 9)));
        assertFalse(WebFetch.isPublicAddress(v4(198, 18, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(224, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v4(255, 255, 255, 255)));
    }

    @Test
    public void ipv4PublicAddressesPass() {
        assertTrue(WebFetch.isPublicAddress(v4(8, 8, 8, 8)));
        assertTrue(WebFetch.isPublicAddress(v4(1, 1, 1, 1)));
        assertTrue(WebFetch.isPublicAddress(v4(93, 184, 216, 34)));
        assertTrue(WebFetch.isPublicAddress(v4(172, 32, 0, 1)));
        assertTrue(WebFetch.isPublicAddress(v4(100, 128, 0, 1)));
    }

    @Test
    public void ipv6NonGlobalRangesAreNotPublic() {
        assertFalse(WebFetch.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
        assertFalse(WebFetch.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v6(0xFC, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v6(0xFD, 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v6(0xFE, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        assertFalse(WebFetch.isPublicAddress(v6(0xFF, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        // IPv4-mapped loopback.
        assertFalse(WebFetch.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 127, 0, 0, 1)));
    }

    @Test
    public void ipv6GlobalAddressesPass() {
        assertTrue(WebFetch.isPublicAddress(v6(0x26, 0x06, 0x28, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        assertTrue(WebFetch.isPublicAddress(v6(0x20, 0x01, 0x0D, 0xB8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
        // IPv4-mapped public.
        assertTrue(WebFetch.isPublicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 8, 8, 8, 8)));
        assertFalse(WebFetch.isPublicAddress(null));
        assertFalse(WebFetch.isPublicAddress(new byte[8]));
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
