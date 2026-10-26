package com.meta.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Same-prompt generation cache: remembers which plugin id a normalized prompt
 * produced, so re-entering the exact same prompt becomes an instant switch
 * back to that app instead of a new LLM round trip.
 *
 * Only brand-new generations are recorded; feedback revisions are not.
 */
public class GenerationCache {

    private static final String PREFS_NAME = "meta_generation_cache";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 50;
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

    /** One cached result. */
    public static class Entry {
        public String id;
        public long time;
    }

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public GenerationCache(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the plugin id cached for this prompt, or null. */
    public synchronized String lookup(String prompt) {
        Entry entry = load().get(normalize(prompt));
        return entry != null ? entry.id : null;
    }

    /** Records a successful generation result; evicts the oldest entry beyond the cap. */
    public synchronized void record(String prompt, String pluginId) {
        Map<String, Entry> map = load();
        Entry entry = new Entry();
        entry.id = pluginId;
        entry.time = System.currentTimeMillis();
        map.remove(normalize(prompt));
        map.put(normalize(prompt), entry);
        evictOldest(map, MAX_ENTRIES);
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(map)).apply();
    }

    // ---------- Pure logic (unit-testable) ----------

    /** Trims and collapses whitespace so "a  b\nc" and "a b c" share one key. */
    static String normalize(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt.trim().replaceAll("\\s+", " ");
    }

    /** Removes entries until the map is within the cap, oldest first. */
    static void evictOldest(Map<String, Entry> map, int maxEntries) {
        while (map.size() > maxEntries) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> it : map.entrySet()) {
                if (it.getValue().time < oldest) {
                    oldest = it.getValue().time;
                    oldestKey = it.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            map.remove(oldestKey);
        }
    }

    private Map<String, Entry> load() {
        String json = prefs.getString(KEY_ENTRIES, null);
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Entry> map = gson.fromJson(json, MAP_TYPE);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            // Corrupted cache: start over; generation just falls back to the LLM.
            return new LinkedHashMap<>();
        }
    }
}
