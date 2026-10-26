package com.meta.app.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.meta.app.BuildConfig;

/**
 * LLM configuration management.
 */
public class LlmConfig {
    private static final String PREFS_NAME = "meta_app_config";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_BACKUP_MODEL = "backup_model";

    private final SharedPreferences prefs;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String backupModel;

    public LlmConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        baseUrl = null;
        apiKey = null;
        model = null;
        backupModel = null;
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

    public boolean isConfigured() { return !getBaseUrl().isEmpty() && !getApiKey().isEmpty(); }
}
