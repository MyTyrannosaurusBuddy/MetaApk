package com.meta.app.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginArchiveTest {

    private static PluginArchive.Entry entry(String id) {
        return new PluginArchive.Entry(id, "Title-" + id, "prompt-" + id, 1000L, "lineage-" + id);
    }

    private static List<String> ids(List<PluginArchive.Entry> entries) {
        List<String> result = new ArrayList<>();
        for (PluginArchive.Entry e : entries) {
            result.add(e.id);
        }
        return result;
    }

    @Test
    public void addFrontEntry_prependsNewest() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.addFrontEntry(entries, entry("a"));
        PluginArchive.addFrontEntry(entries, entry("b"));
        PluginArchive.addFrontEntry(entries, entry("c"));

        assertEquals("[c, b, a]", ids(entries).toString());
    }

    @Test
    public void addFrontEntry_dedupesSameId() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.addFrontEntry(entries, entry("a"));
        PluginArchive.addFrontEntry(entries, entry("b"));
        PluginArchive.addFrontEntry(entries, entry("a"));

        assertEquals(2, entries.size());
        assertEquals("[a, b]", ids(entries).toString());
    }

    @Test
    public void removeEntry_returnsEntryAndRemovesIt() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.addFrontEntry(entries, entry("a"));
        PluginArchive.addFrontEntry(entries, entry("b"));

        PluginArchive.Entry removed = PluginArchive.removeEntry(entries, "a");

        assertEquals("a", removed.id);
        assertEquals("[b]", ids(entries).toString());
        assertNull(PluginArchive.removeEntry(entries, "missing"));
    }

    @Test
    public void findEntry_staticHelperReturnsMatch() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.addFrontEntry(entries, entry("a"));
        PluginArchive.addFrontEntry(entries, entry("b"));

        assertEquals("b", PluginArchive.findEntry(entries, "b").id);
        assertNull(PluginArchive.findEntry(entries, "missing"));
    }

    @Test
    public void moveEntry_reordersWithinBounds() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.addFrontEntry(entries, entry("a"));
        PluginArchive.addFrontEntry(entries, entry("b"));
        PluginArchive.addFrontEntry(entries, entry("c"));
        // [c, b, a]

        assertTrue(PluginArchive.moveEntry(entries, "b", 1));
        assertEquals("[c, a, b]", ids(entries).toString());
        assertTrue(PluginArchive.moveEntry(entries, "b", -1));
        assertEquals("[c, b, a]", ids(entries).toString());
        assertFalse(PluginArchive.moveEntry(entries, "c", -1));
        assertFalse(PluginArchive.moveEntry(entries, "a", 1));
        assertFalse(PluginArchive.moveEntry(entries, "missing", 1));
    }

    @Test
    public void serializeAndParse_roundTrips() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.addFrontEntry(entries, new PluginArchive.Entry(
            "plugin_abc", "Title with unicode ✓ é", "Prompt with, comma \"quote\"",
            123456L, "lineage_abc"));

        String json = PluginArchive.serializeEntries(entries);
        List<PluginArchive.Entry> parsed = PluginArchive.parseEntries(json);

        assertEquals(1, parsed.size());
        assertEquals("plugin_abc", parsed.get(0).id);
        assertEquals("Title with unicode ✓ é", parsed.get(0).title);
        assertEquals("Prompt with, comma \"quote\"", parsed.get(0).prompt);
        assertEquals(123456L, parsed.get(0).timestamp);
        assertEquals("lineage_abc", parsed.get(0).lineage);
    }

    @Test
    public void parseEntries_handlesEmptyAndGarbage() {
        assertTrue(PluginArchive.parseEntries(null).isEmpty());
        assertTrue(PluginArchive.parseEntries("").isEmpty());
        assertTrue(PluginArchive.parseEntries("not json {{{").isEmpty());
    }
}
