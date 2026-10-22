package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginRuntimeGuardTest {

    @Test
    public void isFromPlugin_detectsPluginFrameAnywhereInStack() {
        RuntimeException error = new RuntimeException("boom");
        error.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("android.app.AlarmManager", "setExact", "AlarmManager.java", 1),
            new StackTraceElement("com.meta.plugin.DynamicPlugin$1", "onClick", "DynamicPlugin.java", 42),
            new StackTraceElement("android.view.View", "performClick", "View.java", 1),
        });

        assertTrue(PluginRuntimeGuard.isFromPlugin(error));
    }

    @Test
    public void isFromPlugin_detectsPluginFrameInCauseChain() {
        Throwable root = new SecurityException("Permission Denial");
        root.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("com.meta.plugin.DynamicPlugin", "schedule", "DynamicPlugin.java", 7),
        });
        RuntimeException wrapper = new RuntimeException("wrapper", root);

        assertTrue(PluginRuntimeGuard.isFromPlugin(wrapper));
    }

    @Test
    public void isFromPlugin_rejectsHostOnlyErrors() {
        IllegalStateException error = new IllegalStateException("host bug");
        error.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("com.meta.app.ui.HostActivity", "onCreate", "HostActivity.java", 60),
            new StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 1),
        });

        assertFalse(PluginRuntimeGuard.isFromPlugin(error));
    }

    @Test
    public void summarize_includesTypeMessageAndFrames() {
        SecurityException error = new SecurityException("Caller com.meta.app needs to hold android.permission.SCHEDULE_EXACT_ALARM");
        error.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("com.meta.plugin.DynamicPlugin$1", "onClick", "DynamicPlugin.java", 42),
        });

        String summary = PluginRuntimeGuard.summarize(error);

        assertTrue(summary.contains("java.lang.SecurityException"));
        assertTrue(summary.contains("SCHEDULE_EXACT_ALARM"));
        assertTrue(summary.contains("com.meta.plugin.DynamicPlugin$1.onClick"));
    }

    @Test
    public void summarize_handlesCauseChain() {
        Throwable root = new IllegalArgumentException("root cause");
        RuntimeException wrapper = new RuntimeException("wrapper message", root);

        String summary = PluginRuntimeGuard.summarize(wrapper);

        assertTrue(summary.contains("wrapper message"));
        assertTrue(summary.contains("Caused by: java.lang.IllegalArgumentException: root cause"));
    }
}
