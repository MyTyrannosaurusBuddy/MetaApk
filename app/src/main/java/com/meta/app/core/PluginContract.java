package com.meta.app.core;

import android.content.Context;
import android.view.ViewGroup;
import com.meta.app.bridge.ApiBridge;

/**
 * Plugin contract interface - all LLM-generated dynamic code must implement it.
 * It is the only contract between the host and a plugin.
 */
public interface PluginContract {

    /**
     * Plugin initialization: build the UI here and add it to the container.
     * @param context   the host Context, lineage-scoped: getFilesDir() /
     *                  getSharedPreferences() point to this app's own storage,
     *                  which persists across feedback revisions
     * @param container root ViewGroup provided by the host
     * @param bridge    system API bridge
     */
    void onCreate(Context context, ViewGroup container, ApiBridge bridge);

    /**
     * Plugin destruction: release resources.
     */
    void onDestroy();

    /**
     * Returns the plugin title (shown in the host toolbar).
     */
    String getTitle();

    /**
     * Optional: handles the host Activity's onBackPressed.
     * @return true if the plugin handled it, false to let the host handle it
     */
    default boolean onBackPressed() { return false; }
}
