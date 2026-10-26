package com.meta.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.meta.app.BuildConfig;

import java.util.List;

/**
 * LLM configuration management.
 */
public class LlmConfig {
    private static final String PREFS_NAME = "meta_app_config";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_BACKUP_MODEL = "backup_model";
    private static final String KEY_CONTEXT_LENGTH = "context_length";

    private final SharedPreferences prefs;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String backupModel;
    private final int contextLength;

    public LlmConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        baseUrl = null;
        apiKey = null;
        model = null;
        backupModel = null;
        contextLength = 0;
    }

    /**
     * Ephemeral configuration for connection tests: never touches
     * SharedPreferences, so unsaved form values can be probed safely.
     */
    public LlmConfig(String baseUrl, String apiKey, String model, String backupModel) {
        prefs = null;
        this.baseUrl = stripInvisibleChars(baseUrl);
        this.apiKey = stripInvisibleChars(apiKey);
        this.model = model;
        this.backupModel = backupModel;
        this.contextLength = 0;
    }

    public String getBaseUrl() {
        return prefs != null ? prefs.getString(KEY_BASE_URL, BuildConfig.DEFAULT_BASE_URL) : baseUrl;
    }

    public void setBaseUrl(String url) {
        if (prefs != null) {
            prefs.edit().putString(KEY_BASE_URL, stripInvisibleChars(url)).apply();
        }
    }

    /**
     * Strips invisible zero-width/format characters. Keys and URLs pasted
     * from documents frequently carry U+200B & friends between every
     * character, which silently corrupts the value and breaks every request.
     */
    static String stripInvisibleChars(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\u200b\\u200c\\u200d\\ufeff\\u2060\\u00ad]", "");
    }

    public String getApiKey() {
        return prefs != null ? prefs.getString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY) : apiKey;
    }

    public void setApiKey(String key) {
        if (prefs != null) {
            prefs.edit().putString(KEY_API_KEY, stripInvisibleChars(key)).apply();
        }
    }

    public String getModel() {
        return prefs != null ? prefs.getString(KEY_MODEL, BuildConfig.DEFAULT_MODEL) : model;
    }

    public void setModel(String model) {
        if (prefs != null) {
            prefs.edit().putString(KEY_MODEL, model).apply();
        }
    }

    /** Backup model: switched to automatically when the primary model is unavailable (rate limited / down / empty response). */
    public String getBackupModel() {
        return prefs != null ? prefs.getString(KEY_BACKUP_MODEL, BuildConfig.DEFAULT_BACKUP_MODEL) : backupModel;
    }

    public void setBackupModel(String model) {
        if (prefs != null) {
            prefs.edit().putString(KEY_BACKUP_MODEL, model).apply();
        }
    }

    /**
     * Manual context-length override in tokens; 0 means auto (queried from
     * the endpoint metadata, falling back to a conservative default). Some
     * gateways never report context_length in /models, so the manual value
     * is the only reliable source there.
     */
    public int getContextLength() {
        if (prefs == null) {
            return contextLength;
        }
        return prefs.getInt(KEY_CONTEXT_LENGTH, 0);
    }

    public void setContextLength(int tokens) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_CONTEXT_LENGTH, Math.max(0, tokens)).apply();
        }
    }

    public boolean isConfigured() { return !getBaseUrl().isEmpty() && !getApiKey().isEmpty(); }

    // ---------- Configuration profiles (multiple provider setups, switchable) ----------

    /** One saved provider configuration. */
    public static class Profile {
        public String name;
        public String baseUrl;
        public String apiKey;
        public String model;
        public String backupModel;

        public Profile() { }

        public Profile(String name, String baseUrl, String apiKey,
                       String model, String backupModel) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.backupModel = backupModel;
        }
    }

    private static final String KEY_PROFILES = "profiles";

    /** Returns the saved profiles (empty when none). */
    public List<Profile> getProfiles() {
        if (prefs == null) {
            return new java.util.ArrayList<>();
        }
        String json = prefs.getString(KEY_PROFILES, "");
        if (json == null || json.trim().isEmpty()) {
            return new java.util.ArrayList<>();
        }
        try {
            List<Profile> list = new com.google.gson.Gson().fromJson(json,
                new com.google.gson.reflect.TypeToken<List<Profile>>() {}.getType());
            return list != null ? list : new java.util.ArrayList<>();
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    /** Saves (or overwrites by name) a profile. */
    public void saveProfile(Profile profile) {
        if (prefs == null || profile == null || profile.name == null) {
            return;
        }
        List<Profile> profiles = getProfiles();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equals(profile.name)) {
                profiles.set(i, profile);
                prefs.edit().putString(KEY_PROFILES,
                    new com.google.gson.Gson().toJson(profiles)).apply();
                return;
            }
        }
        profiles.add(profile);
        prefs.edit().putString(KEY_PROFILES, new com.google.gson.Gson().toJson(profiles)).apply();
    }

    /** Deletes a profile by name. */
    public void deleteProfile(String name) {
        if (prefs == null || name == null) {
            return;
        }
        List<Profile> profiles = getProfiles();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equals(name)) {
                profiles.remove(i);
                break;
            }
        }
        prefs.edit().putString(KEY_PROFILES, new com.google.gson.Gson().toJson(profiles)).apply();
    }

    /** Applies a profile to the active configuration. */
    public void applyProfile(Profile profile) {
        if (profile == null) {
            return;
        }
        setBaseUrl(profile.baseUrl);
        setApiKey(profile.apiKey);
        setModel(profile.model);
        setBackupModel(profile.backupModel);
    }
}
