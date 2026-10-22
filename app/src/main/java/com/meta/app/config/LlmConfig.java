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

    public LlmConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() { return prefs.getString(KEY_BASE_URL, BuildConfig.DEFAULT_BASE_URL); }
    public void setBaseUrl(String url) { prefs.edit().putString(KEY_BASE_URL, url).apply(); }

    public String getApiKey() { return prefs.getString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY); }
    public void setApiKey(String key) { prefs.edit().putString(KEY_API_KEY, key).apply(); }

    public String getModel() { return prefs.getString(KEY_MODEL, BuildConfig.DEFAULT_MODEL); }
    public void setModel(String model) { prefs.edit().putString(KEY_MODEL, model).apply(); }

    /** Backup model: switched to automatically when the primary model is unavailable (rate limited / down / empty response). */
    public String getBackupModel() { return prefs.getString(KEY_BACKUP_MODEL, BuildConfig.DEFAULT_BACKUP_MODEL); }
    public void setBackupModel(String model) { prefs.edit().putString(KEY_BACKUP_MODEL, model).apply(); }

    public boolean isConfigured() { return !getBaseUrl().isEmpty() && !getApiKey().isEmpty(); }
}
