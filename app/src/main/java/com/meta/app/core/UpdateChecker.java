package com.meta.app.core;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meta.app.BuildConfig;

/**
 * GitHub release update check. Runs on a worker thread: the latest release
 * is fetched from the GitHub API (public endpoints, through the SSRF-guarded
 * WebFetch client) and compared against the running version. Network
 * failures are reported quietly - an offline restart must never nag.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String GITHUB_API =
        "https://api.github.com/repos/MyTyrannosaurusBuddy/MetaApk/releases/latest";

    /** A discovered newer release. */
    public static final class Update {
        public final String version;
        public final String downloadUrl;
        public final String notes;

        Update(String version, String downloadUrl, String notes) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
        }
    }

    public interface Callback {
        void onUpdate(Update update);
        void onNoUpdate();
        void onError(String message);
    }

    private UpdateChecker() {}

    /** Runs the check off the main thread; callbacks arrive on the worker.
     *  {@code force} skips the version comparison (debug test channel). */
    public static void check(boolean force, Callback callback) {
        new Thread(() -> {
            try {
                String json = WebFetch.fetch(GITHUB_API);
                if (json == null || json.startsWith("Error:")) {
                    callback.onError(json == null ? "fetch failed" : json);
                    return;
                }
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                String tag = root.get("tag_name").getAsString();
                String remote = tag.startsWith("v") ? tag.substring(1) : tag;
                String current = BuildConfig.VERSION_NAME;
                android.util.Log.i(TAG, "Latest=" + remote + " current=" + current
                    + " force=" + force);
                if (!force && compareVersions(remote, current) <= 0) {
                    callback.onNoUpdate();
                    return;
                }
                JsonArray assets = root.getAsJsonArray("assets");
                String url = null;
                if (assets != null) {
                    for (int i = 0; i < assets.size(); i++) {
                        JsonObject asset = assets.get(i).getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name != null && name.endsWith(".apk")) {
                            url = asset.get("browser_download_url").getAsString();
                            break;
                        }
                    }
                }
                if (url == null || url.isEmpty()) {
                    callback.onError("Release has no apk asset");
                    return;
                }
                String body = root.has("body") && !root.get("body").isJsonNull()
                    ? root.get("body").getAsString() : "";
                callback.onUpdate(new Update(remote, url, body));
            } catch (Exception e) {
                android.util.Log.w(TAG, "Update check failed", e);
                callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "UpdateCheck").start();
    }

    /**
     * Semver-ish comparison over dot-separated numeric segments (with an
     * optional trailing suffix). Returns &lt;0 / 0 / &gt;0.
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.trim().split("\\.");
        String[] pb = b.trim().split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            long va = i < pa.length ? numericPrefix(pa[i]) : 0;
            long vb = i < pb.length ? numericPrefix(pb[i]) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    /** The leading integer of a segment ("10" of "10-beta"), or 0. */
    private static long numericPrefix(String segment) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
