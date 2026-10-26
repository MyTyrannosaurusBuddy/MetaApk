package com.meta.app.inject;

import android.view.ViewGroup;

import com.meta.app.bridge.ApiBridge;
import com.meta.app.core.PluginContract;
import com.meta.app.ui.HostActivity;

/**
 * Activity injector - injects a dynamic plugin into the content area of the host Activity.
 */
public class ActivityInjector {

    private final HostActivity hostActivity;
    private PluginContract currentPlugin;

    public ActivityInjector(HostActivity hostActivity) {
        this.hostActivity = hostActivity;
    }

    /**
     * Injects a plugin into the host content area.
     */
    public void inject(PluginContract plugin, ApiBridge bridge) {
        // Destroy the previous plugin
        if (currentPlugin != null) {
            try { currentPlugin.onDestroy(); } catch (Throwable ignored) {}
        }

        currentPlugin = plugin;

        // Clear the content area
        ViewGroup container = hostActivity.getPluginContainer();
        container.removeAllViews();

        // Update the title; getTitle() is plugin code and may throw
        hostActivity.setPluginTitle(safeGetTitle(plugin));

        // Let the plugin add its views to the container. The plugin receives a
        // lineage-scoped context, so its file/prefs storage survives revisions.
        try {
            plugin.onCreate(hostActivity.getPluginContext(plugin), container, bridge);
        } catch (Throwable t) {
            // Plugin runtime failure: show the error and feed it back to the LLM
            currentPlugin = null;
            container.removeAllViews();
            throw new RuntimeException("Plugin onCreate failed: " + t.getMessage(), t);
        }
    }

    /**
     * Injects a plugin into an arbitrary container with an arbitrary context
     * (the virtual-display preview presentation) without touching the host's
     * content area or title.
     */
    public void previewInject(PluginContract plugin, ApiBridge bridge,
                              ViewGroup container, android.content.Context context) {
        if (currentPlugin != null) {
            try { currentPlugin.onDestroy(); } catch (Throwable ignored) {}
        }
        currentPlugin = plugin;
        container.removeAllViews();
        try {
            plugin.onCreate(context, container, bridge);
        } catch (Throwable t) {
            currentPlugin = null;
            container.removeAllViews();
            throw new RuntimeException("Plugin onCreate failed: " + t.getMessage(), t);
        }
    }

    /** Returns the plugin title, or "Plugin" when getTitle() throws or returns null. */
    private static String safeGetTitle(PluginContract plugin) {
        try {
            String title = plugin.getTitle();
            return (title != null && !title.trim().isEmpty()) ? title : "Plugin";
        } catch (Throwable t) {
            return "Plugin";
        }
    }

    public boolean onBackPressed() {
        if (currentPlugin == null) {
            return false;
        }
        try {
            return currentPlugin.onBackPressed();
        } catch (Throwable t) {
            // Plugin callback failure at the host boundary: report for the
            // self-heal flow instead of letting it tear down the main loop.
            com.meta.app.core.PluginRuntimeGuard.reportPluginError(t);
            return true;
        }
    }

    public void destroy() {
        if (currentPlugin != null) {
            try { currentPlugin.onDestroy(); } catch (Throwable ignored) {}
            currentPlugin = null;
        }
        hostActivity.getPluginContainer().removeAllViews();
    }

    public PluginContract getCurrentPlugin() { return currentPlugin; }
}
