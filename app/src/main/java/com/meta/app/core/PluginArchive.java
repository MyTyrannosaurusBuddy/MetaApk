package com.meta.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * App archive: each time a new app is generated, the previous active app is
 * moved into the archive, where the user can switch back to any entry, or
 * delete / reorder them.
 *
 * Only metadata is stored here (id / title / prompt / timestamp); the app
 * files remain under files/plugins/<id>/ and are cleaned up by MetaEngine
 * on deletion.
 */
public class PluginArchive {

    private static final String TAG = "PluginArchive";
    private static final String PREFS_NAME = "meta_plugin_archive";
    private static final String KEY_ENTRIES = "entries";
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();

    /** A single archive entry. */
    public static class Entry {
        public String id;
        public String title;
        public String prompt;
        public long timestamp;
        /** Stable lineage ID shared by the app and its feedback revisions (used for cross-version data persistence). */
        public String lineage;

        public Entry() {}

        public Entry(String id, String title, String prompt, long timestamp, String lineage) {
            this.id = id;
            this.title = title;
            this.prompt = prompt;
            this.timestamp = timestamp;
            this.lineage = lineage;
        }
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public PluginArchive(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns all archive entries, newest first. */
    public synchronized List<Entry> list() {
        String json = prefs.getString(KEY_ENTRIES, null);
        return parseEntries(json);
    }

    /** Inserts a new entry at the front (newest first); an existing entry with the same id is removed first. */
    public synchronized void addFront(String id, String title, String prompt, long timestamp,
                                      String lineage) {
        List<Entry> entries = list();
        addFrontEntry(entries, new Entry(id, title, prompt, timestamp, lineage));
        save(entries);
    }

    /** Returns the entry with the given id, or null. */
    public synchronized Entry find(String id) {
        return findEntry(list(), id);
    }

    /** Removes an entry and returns it. */
    public synchronized Entry remove(String id) {
        List<Entry> entries = list();
        Entry removed = removeEntry(entries, id);
        save(entries);
        return removed;
    }

    /** Deletes an entry (disk files are NOT cleaned up here; the caller is responsible). */
    public synchronized boolean delete(String id) {
        List<Entry> entries = list();
        boolean removed = removeEntry(entries, id) != null;
        if (removed) {
            save(entries);
        }
        return removed;
    }

    /** Moves an entry up (delta&lt;0) or down (delta&gt;0). */
    public synchronized boolean move(String id, int delta) {
        List<Entry> entries = list();
        boolean moved = moveEntry(entries, id, delta);
        if (moved) {
            save(entries);
        }
        return moved;
    }

    // ---------- Pure logic (unit-testable) ----------

    static void addFrontEntry(List<Entry> entries, Entry entry) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entry.id.equals(entries.get(i).id)) {
                entries.remove(i);
            }
        }
        entries.add(0, entry);
    }

    static Entry removeEntry(List<Entry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                return entries.remove(i);
            }
        }
        return null;
    }

    static Entry findEntry(List<Entry> entries, String id) {
        for (Entry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    static boolean moveEntry(List<Entry> entries, String id, int delta) {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        int target = index + delta;
        if (target < 0 || target >= entries.size()) {
            return false;
        }
        Collections.swap(entries, index, target);
        return true;
    }

    static String serializeEntries(List<Entry> entries) {
        return new Gson().toJson(entries);
    }

    static List<Entry> parseEntries(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Entry> entries = new Gson().fromJson(json, ENTRY_LIST_TYPE);
            return entries != null ? entries : new ArrayList<>();
        } catch (Exception e) {
            // Corrupted archive data: reset to an empty list; the caller decides whether to warn.
            return new ArrayList<>();
        }
    }

    private void save(List<Entry> entries) {
        boolean success = prefs.edit()
            .putString(KEY_ENTRIES, serializeEntries(entries))
            .commit();
        Log.i(TAG, "Save archive entries count=" + entries.size() + " success=" + success);
    }
}
