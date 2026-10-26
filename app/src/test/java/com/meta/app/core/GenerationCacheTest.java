package com.meta.app.core;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GenerationCacheTest {

    private static GenerationCache.Entry entry(String id, long time) {
        GenerationCache.Entry e = new GenerationCache.Entry();
        e.id = id;
        e.time = time;
        return e;
    }

    @Test
    public void normalize_trimsAndCollapsesWhitespace() {
        assertEquals("", GenerationCache.normalize(null));
        assertEquals("", GenerationCache.normalize("   "));
        assertEquals("a b c", GenerationCache.normalize("  a   b\nc  "));
        assertEquals("a b c", GenerationCache.normalize("a\tb  c"));
    }

    @Test
    public void evictOldest_removesBeyondCapByOldestTime() {
        Map<String, GenerationCache.Entry> map = new LinkedHashMap<>();
        map.put("p1", entry("a", 100L));
        map.put("p2", entry("b", 300L));
        map.put("p3", entry("c", 200L));

        GenerationCache.evictOldest(map, 2);

        assertEquals(2, map.size());
        assertNull(map.get("p1"));
        assertEquals("b", map.get("p2").id);
        assertEquals("c", map.get("p3").id);
    }

    @Test
    public void evictOldest_noOpWhenWithinCap() {
        Map<String, GenerationCache.Entry> map = new LinkedHashMap<>();
        map.put("p1", entry("a", 100L));

        GenerationCache.evictOldest(map, 5);

        assertEquals(1, map.size());
    }
}
