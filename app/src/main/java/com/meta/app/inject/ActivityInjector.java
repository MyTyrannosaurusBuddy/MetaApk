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
            try { currentPlugin.onDestroy(); } catch (Exception ignored) {}
        }

        currentPlugin = plugin;

        // Clear the content area
        ViewGroup container = hostActivity.getPluginContainer();
        container.removeAllViews();

        // Update the title
        hostActivity.setPluginTitle(plugin.getTitle());

        // Let the plugin add its views to the container
        try {
            plugin.onCreate(hostActivity, container, bridge);
        } catch (Throwable t) {
            // Plugin runtime failure: show the error and feed it back to the LLM
            currentPlugin = null;
            container.removeAllViews();
            throw new RuntimeException("Plugin onCreate failed: " + t.getMessage(), t);
        }
    }

    public boolean onBackPressed() {
        return currentPlugin != null && currentPlugin.onBackPressed();
    }

    public void destroy() {
        if (currentPlugin != null) {
            try { currentPlugin.onDestroy(); } catch (Exception ignored) {}
            currentPlugin = null;
        }
        hostActivity.getPluginContainer().removeAllViews();
    }

    public PluginContract getCurrentPlugin() { return currentPlugin; }
}
