package com.meta.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Shared helpers for UIAutomator device tests.
 *
 * Host UI texts are matched through the app's string resources (locale
 * independent). Completion of async flows is asserted through the app's
 * persisted state (SharedPreferences), which the instrumentation can read
 * directly - generated-app UI texts are model-dependent and never asserted.
 */
public final class UiTestUtils {

    public static final long UI_TIMEOUT_MS = 30_000;
    /**
     * Generation / revision waits are effectively unbounded: the philosophy is
     * no artificial timeouts - agent sessions take as long as they take, and
     * the only limits are user cancellation (Stop) and process death.
     */
    public static final long GENERATION_TIMEOUT_MS = 24L * 60 * 60 * 1000;

    private static final String ACTIVE_PREFS = "meta_active_plugin";
    private static final String ARCHIVE_PREFS = "meta_plugin_archive";
    private static final String PENDING_PREFS = "meta_pending_task";
    private static final String TAG = "UiTestUtils";

    private UiTestUtils() {
    }

    /**
     * In-process window hierarchy snapshot (the shell `uiautomator dump` would
     * collide with the instrumentation's UiAutomation connection and crash).
     */
    private static String dumpUiSafely(UiDevice device) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            device.dumpWindowHierarchy(bos);
            String xml = bos.toString("UTF-8");
            return xml.length() > 30_000 ? xml.substring(0, 30_000) + "...(truncated)" : xml;
        } catch (Exception e) {
            return "(dump failed: " + e + ")";
        }
    }

    /** Writes the LLM endpoint from the debug BuildConfig into the app config. */
    public static void ensureConfigured(Context targetContext) {
        assertFalse("Debug build has no API key - configure local.properties",
            BuildConfig.DEFAULT_API_KEY.isEmpty());
        targetContext.getSharedPreferences("meta_app_config", Context.MODE_PRIVATE)
            .edit()
            .putString("base_url", BuildConfig.DEFAULT_BASE_URL)
            .putString("api_key", BuildConfig.DEFAULT_API_KEY)
            .putString("model", BuildConfig.DEFAULT_MODEL)
            .putString("backup_model", BuildConfig.DEFAULT_BACKUP_MODEL)
            .commit();
    }

    /** Drops any pending task left by a previous failed run (test isolation). */
    public static void clearPendingTask(Context targetContext) {
        targetContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            .edit().remove("task").commit();
    }

    public static String getActivePluginId(Context targetContext) {
        return targetContext.getSharedPreferences(ACTIVE_PREFS, Context.MODE_PRIVATE)
            .getString("active_plugin_id", "");
    }

    public static int getArchiveCount(Context targetContext) {
        String json = targetContext.getSharedPreferences(ARCHIVE_PREFS, Context.MODE_PRIVATE)
            .getString("entries", "");
        return json == null ? 0 : json.split("plugin_").length - 1;
    }

    /**
     * Polls until the active plugin id differs from oldId; returns the new id.
     * This is the deterministic completion signal for generation / feedback
     * / switching flows. The device is woken periodically: MIUI freezes
     * background apps when the screen turns off, which would stall a plain
     * poll forever.
     */
    public static String waitForActivePluginIdChange(UiDevice device, Context targetContext,
                                                     String oldId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int polls = 0;
        while (deadline - System.currentTimeMillis() > 0) {
            polls++;
            if (polls % 20 == 0) {
                try {
                    device.wakeUp();
                } catch (Exception ignored) {
                    // best-effort wake
                }
            }
            String current = getActivePluginId(targetContext);
            if (!current.isEmpty() && !current.equals(oldId)) {
                return current;
            }
            SystemClock.sleep(1000);
        }
        fail("Active plugin id did not change from " + oldId + " within " + timeoutMs + " ms");
        return null;
    }

    public static UiObject2 waitForObject(UiDevice device, long timeoutMs,
                                          BySelector selector) {
        UiObject2 obj = device.wait(Until.findObject(selector), timeoutMs);
        assertNotNull("Timed out waiting for UI object", obj);
        return obj;
    }

    /**
     * Finds the object and clicks it, re-finding on StaleObjectException
     * (the UI may rebuild between the find and the click).
     */
    public static UiObject2 click(UiDevice device, BySelector selector,
                                  long timeoutMs) {
        StaleObjectException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            UiObject2 obj = device.wait(Until.findObject(selector), timeoutMs);
            if (obj == null) {
                // The hierarchy at the moment of the miss tells us whether the
                // window disappeared (environment) or the node is elsewhere
                // (selector/geometry problem).
                android.util.Log.i(TAG, "CLICK-MISS\n" + dumpUiSafely(device));
                throw new AssertionError("Timed out waiting for UI object");
            }
            try {
                obj.click();
                return obj;
            } catch (StaleObjectException e) {
                last = e;
            }
        }
        throw new AssertionError("Object kept going stale", last);
    }

    public static UiObject2 clickByText(UiDevice device, String text) {
        return click(device, By.text(text), UI_TIMEOUT_MS);
    }

    /** Best-effort click: returns false instead of failing when absent. */
    public static boolean clickIfPresent(UiDevice device, BySelector selector,
                                         long timeoutMs) {
        UiObject2 obj = device.wait(Until.findObject(selector), timeoutMs);
        if (obj == null) {
            return false;
        }
        try {
            obj.click();
            return true;
        } catch (StaleObjectException e) {
            return clickIfPresent(device, selector, timeoutMs);
        }
    }

    /**
     * Submits feedback through the host's real product path (submitFeedback ->
     * state machine -> engine), invoked in-process from the instrumentation.
     * The improve flow is the plain input field (no dialog), so the product
     * path is the only thing left to exercise.
     */
    public static void submitFeedbackInProcess(Context targetContext, String feedback)
        throws Exception {
        // The activity query and the submission both run on the main thread,
        // but the polling loop runs HERE: sleeping on the main thread would
        // starve the very lifecycle that brings the activity to RESUMED.
        final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.CountDownLatch done =
            new java.util.concurrent.CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            main.post(() -> {
                if (done.getCount() == 0) {
                    return;
                }
                try {
                    com.meta.app.ui.HostActivity host = findResumedHost();
                    if (host == null) {
                        return;
                    }
                    host.submitFeedback(feedback);
                    done.countDown();
                } catch (Throwable t) {
                    failure[0] = t;
                    done.countDown();
                }
            });
            if (done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                break;
            }
        }
        if (done.getCount() != 0) {
            throw new AssertionError("Host activity not resumed");
        }
        if (failure[0] != null) {
            throw new AssertionError("submitFeedback failed", failure[0]);
        }
    }

    private static com.meta.app.ui.HostActivity findResumedHost() {
        java.util.Collection<android.app.Activity> activities =
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED);
        for (android.app.Activity activity : activities) {
            if (activity instanceof com.meta.app.ui.HostActivity) {
                return (com.meta.app.ui.HostActivity) activity;
            }
        }
        return null;
    }

    /**
     * Relaunches the host activity with a cleared task and waits until the
     * main screen (Generate button) is rendered.
     *
     * The launch goes through `am start` from the shell: MIUI blocks
     * background activity starts from the instrumentation context (BAL), and
     * the instrumentation must also never force-stop the app - it runs
     * inside the app process. A cleared task recreates the activity (and the
     * engine), which is also what exercises the pending-task resume path.
     */
    public static void launchHost(UiDevice device, Context targetContext) throws Exception {
        launchHostInternal(device, targetContext, true);
    }

    /**
     * Relaunches the host WITHOUT clearing a pending task - used by the
     * resume test, where the persisted task is the subject under test.
     */
    public static void launchHostForResume(UiDevice device, Context targetContext)
        throws Exception {
        launchHostInternal(device, targetContext, false);
    }

    private static void launchHostInternal(UiDevice device, Context targetContext,
                                           boolean clearPendingTask) throws Exception {
        ensureConfigured(targetContext);
        if (clearPendingTask) {
            clearPendingTask(targetContext);
        }
        // Long agent sessions must not be killed by screen-off app freezing:
        // wake the device, keep the screen on and dismiss the keyguard.
        try {
            device.wakeUp();
            device.executeShellCommand("svc power stayon true");
            device.executeShellCommand("wm dismiss-keyguard");
            hardenDeviceEnvironment(device);
        } catch (Exception ignored) {
            // best-effort device housekeeping
        }
        // -f 0x10008000 = FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK
        // (the named flag options are not supported by this ROM's am start).
        // Readiness is checked through the window manager (shell), the only
        // signal that stays reliable on this device - the tests assert the
        // persisted state, never the UI tree.
        for (int attempt = 0; attempt < 5; attempt++) {
            device.executeShellCommand("am start -f 0x10008000 -n "
                + targetContext.getPackageName() + "/.ui.HostActivity");
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                String top = device.executeShellCommand(
                    "dumpsys activity activities | grep topResumedActivity");
                if (top != null && top.contains(targetContext.getPackageName())) {
                    return;
                }
                SystemClock.sleep(1000);
            }
        }
        throw new AssertionError("Host main screen not reachable after retries");
    }

    /**
     * Disables MIUI device-guard features that hijack the foreground or
     * freeze the app during long-running tests (observed on the test device:
     * com.miui.greenguard freezing apps and the screen-time "continuous use"
     * limit launching LimitActivity over the host, which then gets frozen by
     * the cgroup freezer). One-shot, idempotent, best-effort.
     */
    private static boolean deviceEnvironmentHardened = false;

    private static void hardenDeviceEnvironment(UiDevice device) {
        if (deviceEnvironmentHardened) {
            return;
        }
        deviceEnvironmentHardened = true;
        try {
            device.executeShellCommand("settings put system continuous_use_limit_switch 0");
            device.executeShellCommand("settings put system weekday_device_limit_switch 0");
            device.executeShellCommand("settings put system weekend_device_limit_switch 0");
            device.executeShellCommand("settings put system in_continuous_limit 0");
            device.executeShellCommand("settings put system in_time_period_limit 0");
            device.executeShellCommand("pm disable-user --user 0 com.miui.greenguard");
            device.executeShellCommand("am force-stop com.xiaomi.misettings");
            // Agent-generated plugin UIs can animate forever (marquees,
            // blinking cursors), which keeps the window non-idle and stalls
            // every UIAutomator query behind a waitForIdle. The tests assert
            // persisted state, never animation state, so animations are
            // disabled (standard instrumentation practice).
            device.executeShellCommand("settings put global animator_duration_scale 0");
            device.executeShellCommand("settings put global transition_animation_scale 0");
            device.executeShellCommand("settings put global window_animation_scale 0");
        } catch (Exception ignored) {
            // non-MIUI devices or permission changes: tests still proceed
        }
    }

    /**
     * Types a prompt into the host input, taps Generate and waits for the
     * plugin to load. UI driving goes through raw input events at measured
     * positions - no accessibility-selector queries, no idle waits.
     * Completion is asserted through the persisted active-plugin id.
     */
    public static void generateApp(UiDevice device, Context targetContext,
                                   String prompt) throws Exception {
        String activeBefore = getActivePluginId(targetContext);
        // The + key (tapped repeatedly, MIUI misses singles) resets the input
        // to new-app mode and leaves the focus inside the input field, so the
        // text can be injected directly - no field tap, so no clipboard
        // prompt. `input text` still shows the IME; BACK closes it (it cannot
        // exit the activity while the IME is open) and returns the action
        // button to its measured position. The tap then retries until the
        // persisted pending task proves the submission landed.
        tapNewAppButton(device);
        device.executeShellCommand("input text " + prompt.replace(" ", "%s"));
        device.executeShellCommand("input keyevent 4");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            tapActionButton(device);
            long grace = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < grace) {
                String task = targetContext.getSharedPreferences("meta_pending_task",
                    Context.MODE_PRIVATE).getString("task", null);
                if (task != null && task.contains("GENERATE")) {
                    break;
                }
                SystemClock.sleep(500);
            }
            String task = targetContext.getSharedPreferences("meta_pending_task",
                Context.MODE_PRIVATE).getString("task", null);
            if (task != null && task.contains("GENERATE")) {
                break;
            }
        }
        String submitted = targetContext.getSharedPreferences("meta_pending_task",
            Context.MODE_PRIVATE).getString("task", null);
        if (submitted == null || !submitted.contains("GENERATE")) {
            throw new AssertionError("Generation was not submitted (action tap missed)");
        }
        // Completion signal is the persisted active-plugin id change (works
        // even while the UI is covered or briefly frozen).
        waitForActivePluginIdChange(device, targetContext, activeBefore,
            GENERATION_TIMEOUT_MS);
    }

    /**
     * Reads the current window hierarchy directly and returns the center of
     * the first node whose text / content-desc matches the string, or whose
     * resource-id matches. The direct dump has no waitForIdle dependency, so
     * it keeps working where the selector queries stall.
     */
    private static android.graphics.Point nodeCenter(UiDevice device, String text, String resId) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            device.dumpWindowHierarchy(bos);
            String xml = bos.toString("UTF-8");
            java.util.regex.Matcher node;
            if (resId != null) {
                node = java.util.regex.Pattern.compile(
                    "resource-id=\"" + java.util.regex.Pattern.quote(resId) + "\"").matcher(xml);
            } else {
                node = java.util.regex.Pattern.compile(
                    "(?:text|content-desc)=\"" + java.util.regex.Pattern.quote(text) + "\"").matcher(xml);
            }
            if (!node.find()) {
                return null;
            }
            int boundsStart = xml.indexOf("bounds=\"[", node.start());
            if (boundsStart < 0) {
                return null;
            }
            java.util.regex.Matcher bounds = java.util.regex.Pattern.compile(
                "bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").matcher(xml);
            if (!bounds.find(boundsStart)) {
                return null;
            }
            return new android.graphics.Point(
                (Integer.parseInt(bounds.group(1)) + Integer.parseInt(bounds.group(3))) / 2,
                (Integer.parseInt(bounds.group(2)) + Integer.parseInt(bounds.group(4))) / 2);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The host layout is deterministic, so its controls live at fixed
     * proportional positions: the + key (top-left), the input field (bottom
     * row, left) and the action button (bottom row, right). Every tap is
     * verified through the persisted state, never through the UI.
     */
    private static android.graphics.Point screenSize(UiDevice device) {
        try {
            String out = device.executeShellCommand("wm size");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(out);
            if (m.find()) {
                return new android.graphics.Point(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return new android.graphics.Point(1080, 2400);
    }

    public static void tapAtProportion(UiDevice device, float xFrac, float yFrac)
        throws Exception {
        android.graphics.Point size = screenSize(device);
        device.executeShellCommand("input tap " + Math.round(size.x * xFrac)
            + " " + Math.round(size.y * yFrac));
    }

    /**
     * Taps the + key several times: single taps occasionally miss on MIUI.
     * New-app mode also moves the focus into the input field, which is what
     * lets `input text` land without ever focusing the field manually (and
     * without triggering the clipboard-prompt or an IME layout shift before
     * the text is typed).
     */
    public static void tapNewAppButton(UiDevice device) throws Exception {
        for (int i = 0; i < 3; i++) {
            tapAtProportion(device, 0.498f, 0.092f);
            Thread.sleep(800);
        }
    }

    /**
     * The action button (Generate / Improve) sits at the right edge of the
     * input row. 0.855 / 0.916 is its measured center with the IME closed;
     * generateApp closes the IME before tapping, so this stays valid.
     */
    public static void tapActionButton(UiDevice device) throws Exception {
        tapAtProportion(device, 0.855f, 0.916f);
    }

    /** Taps the center of the view whose text or content-desc matches. */
    public static void tapByText(UiDevice device, String text, long timeoutMs)
        throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            android.graphics.Point center = nodeCenter(device, text, null);
            if (center != null) {
                device.executeShellCommand("input tap " + center.x + " " + center.y);
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("View not found: " + text + "\nDUMP:\n" + dumpUiSafely(device));
    }

    /** Taps the view with the given resource-id; false when absent. */
    public static boolean tapByResIdIfPresent(UiDevice device, String resId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            android.graphics.Point center = nodeCenter(device, null, resId);
            if (center != null) {
                try {
                    device.executeShellCommand("input tap " + center.x + " " + center.y);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
            SystemClock.sleep(500);
        }
        return false;
    }

    /**
     * Waits until any TextView's text matches the regex (case-insensitive).
     */
    public static void waitForDisplayValue(UiDevice device, String regex,
                                           long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                device.dumpWindowHierarchy(bos);
                String xml = bos.toString("UTF-8");
                java.util.regex.Matcher attr = java.util.regex.Pattern.compile(
                    "(?:text|content-desc)=\"([^\"]*)\"").matcher(xml);
                while (attr.find()) {
                    String value = attr.group(1)
                        .replace("&#10;", "\n").replace("&amp;", "&");
                    if (value.matches(regex)) {
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // retry on the next poll
            }
            SystemClock.sleep(1000);
        }
        fail("Display value not found: " + regex);
    }
}
