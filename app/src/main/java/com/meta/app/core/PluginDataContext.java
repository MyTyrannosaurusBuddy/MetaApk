package com.meta.app.core;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Lineage-scoped context handed to plugins.
 *
 * getFilesDir() / getCacheDir() / getSharedPreferences() are routed into a
 * per-app-lineage area, so a plugin's data survives its feedback revisions
 * while staying isolated from other apps. Plugins keep writing plain,
 * idiomatic SDK code - no host-private API needs to be learned.
 */
public class PluginDataContext extends ContextWrapper {

    private final File dataDir;
    private final File cacheDir;
    // Package-visible for unit tests.
    final String prefsPrefix;

    public PluginDataContext(Context base, String lineage, File dataDir, File cacheDir) {
        super(base);
        this.dataDir = dataDir;
        this.cacheDir = cacheDir;
        this.prefsPrefix = "plugin_" + lineage + "_";
        dataDir.mkdirs();
        cacheDir.mkdirs();
    }

    @Override
    public File getFilesDir() {
        return dataDir;
    }

    @Override
    public File getCacheDir() {
        return cacheDir;
    }

    @Override
    public File getDir(String name, int mode) {
        File dir = safeChild(dataDir, name);
        dir.mkdirs();
        return dir;
    }

    /**
     * SQLite databases must be lineage-scoped too; without this override they
     * land in the host's shared databases/ directory, leaking data across
     * app lineages.
     */
    @Override
    public File getDatabasePath(String name) {
        File dbDir = new File(dataDir, "databases");
        dbDir.mkdirs();
        return safeChild(dbDir, name);
    }

    @Override
    public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
            String name, int mode, android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
        return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            getDatabasePath(name).getAbsolutePath(), factory);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        // Same guard: SharedPreferences filenames hit the disk too, so a
        // name with separators would escape shared_prefs/.
        return super.getSharedPreferences(prefsPrefix + safeName(name), mode);
    }

    /**
     * The containment promise of this wrapper: names are single plain
     * segments. Anything with separators or ".." would escape the lineage
     * directory and is refused outright (PathGuard containment as the last
     * line on the resolved file).
     */
    private File safeChild(File root, String name) {
        File child = new File(root, safeName(name));
        if (!PathGuard.isWithin(root, child)) {
            throw new IllegalArgumentException("Plugin data path escapes its directory: " + name);
        }
        return child;
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains("\u0000")) {
            throw new IllegalArgumentException("Unsafe plugin data name: " + name);
        }
        return name;
    }

    /**
     * External storage must be lineage-scoped too: without these overrides a
     * plugin's external files would be shared across app lineages.
     */
    @Override
    public File getExternalFilesDir(String type) {
        File dir = new File(super.getExternalFilesDir(type), "plugin_" + lineage());
        dir.mkdirs();
        return dir;
    }

    @Override
    public File getExternalCacheDir() {
        File dir = new File(super.getExternalCacheDir(), "plugin_" + lineage());
        dir.mkdirs();
        return dir;
    }

    @Override
    public File getNoBackupFilesDir() {
        return getDir("no_backup", Context.MODE_PRIVATE);
    }

    private String lineage() {
        return prefsPrefix.substring("plugin_".length(), prefsPrefix.length() - 1);
    }
}
