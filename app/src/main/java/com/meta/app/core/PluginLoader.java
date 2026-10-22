package com.meta.app.core;

import android.content.Context;
import android.util.Log;

import com.meta.app.sandbox.SandboxClassLoader;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * Plugin loader - loads .dex via DexClassLoader and instantiates PluginContract reflectively.
 */
public class PluginLoader {

    private static final String TAG = "PluginLoader";
    private static final String PLUGIN_CLASS = "com.meta.plugin.DynamicPlugin";
    private final Context context;
    private final Map<String, ClassLoader> loaderCache = new HashMap<>();

    public PluginLoader(Context context) {
        this.context = context;
    }

    /**
     * Load result.
     */
    public static class LoadResult {
        public final boolean success;
        public final PluginContract plugin;
        public final String errorMessage;

        private LoadResult(boolean success, PluginContract plugin, String errorMessage) {
            this.success = success;
            this.plugin = plugin;
            this.errorMessage = errorMessage;
        }

        public static LoadResult success(PluginContract plugin) {
            return new LoadResult(true, plugin, null);
        }

        public static LoadResult failure(String error) {
            return new LoadResult(false, null, error);
        }
    }

    /**
     * Loads a plugin from a dex file.
     * @param pluginId plugin ID
     * @param dexFile  the .dex file
     * @return load result containing the PluginContract instance
     */
    public LoadResult loadPlugin(String pluginId, File dexFile) {
        try {
            File optimizedDir = new File(context.getCacheDir(), "odex/" + pluginId);
            optimizedDir.mkdirs();

            // Android 14+ refuses to load writable dex files; ensure read-only before loading
            AssetTools.makeReadOnly(dexFile);

            // SandboxClassLoader as the parent ClassLoader for security isolation
            ClassLoader parentLoader = new SandboxClassLoader(context.getClassLoader());

            DexClassLoader dexClassLoader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optimizedDir.getAbsolutePath(),
                null,
                parentLoader
            );

            // Cache the ClassLoader
            loaderCache.put(pluginId, dexClassLoader);

            // Load the plugin class
            Class<?> pluginClass = dexClassLoader.loadClass(PLUGIN_CLASS);

            // Verify that PluginContract is implemented
            if (!PluginContract.class.isAssignableFrom(pluginClass)) {
                loaderCache.remove(pluginId);
                return LoadResult.failure("Plugin class does not implement PluginContract");
            }

            // Instantiate reflectively
            PluginContract instance = (PluginContract) pluginClass
                .getDeclaredConstructor().newInstance();

            Log.i(TAG, "Plugin loaded successfully: " + pluginId
                + " title: " + instance.getTitle());
            return LoadResult.success(instance);

        } catch (Exception e) {
            loaderCache.remove(pluginId);
            Log.e(TAG, "Plugin load failed: " + pluginId, e);
            return LoadResult.failure("Plugin load failed: " + e.getMessage());
        }
    }

    /**
     * Unloads a plugin and releases its ClassLoader reference.
     */
    public void unloadPlugin(String pluginId) {
        loaderCache.remove(pluginId);
        System.gc();
    }

    /**
     * Releases all plugin ClassLoaders when the host is destroyed.
     */
    public void unloadAll() {
        loaderCache.clear();
        System.gc();
    }
}
