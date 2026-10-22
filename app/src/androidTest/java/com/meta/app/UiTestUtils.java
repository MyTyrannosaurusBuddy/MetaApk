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
    public static final long GENERATION_TIMEOUT_MS = 600_000;

    private static final String ACTIVE_PREFS = "meta_active_plugin";
    private static final String ARCHIVE_PREFS = "meta_plugin_archive";
    private static final String PENDING_PREFS = "meta_pending_task";

    private UiTestUtils() {
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
     * / switching flows.
     */
    public static String waitForActivePluginIdChange(Context targetContext,
                                                     String oldId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
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
            assertNotNull("Timed out waiting for UI object", obj);
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
        // -f 0x10008000 = FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK
        // (the named flag options are not supported by this ROM's am start).
        // Other apps / system dialogs may steal the foreground on a busy
        // device: retry the launch and dismiss overlays until the main
        // screen is actually visible.
        String generateText = targetContext.getString(R.string.action_generate);
        for (int attempt = 0; attempt < 5; attempt++) {
            device.executeShellCommand("am start -f 0x10008000 -n "
                + targetContext.getPackageName() + "/.ui.HostActivity");
            UiObject2 generate = device.wait(Until.findObject(By.text(generateText)), 15_000);
            if (generate != null) {
                return;
            }
            // An overlay (permission dialog, another app) may be covering the
            // host: dismiss it with BACK and retry.
            device.pressBack();
        }
        throw new AssertionError("Host main screen not reachable after retries");
    }

    /**
     * Types a prompt into the host input, taps Generate and waits for the
     * plugin to load (the status line starts with the "loaded" prefix).
     *
     * NOTE: no BACK key is sent to close the IME - UIAutomator clicks are
     * accessibility actions that work with the keyboard open, while a BACK
     * with no IME would exit the activity.
     */
    public static void generateApp(UiDevice device, Context targetContext,
                                   String prompt) throws Exception {
        click(device, By.clazz(EditText.class), UI_TIMEOUT_MS);
        device.executeShellCommand("input text " + prompt.replace(" ", "%s"));
        // The generate button can vanish briefly while the IME reshapes the
        // window (adjustResize); poll for it instead of a single wait, and
        // retry the click if the node goes stale.
        String generateText = targetContext.getString(R.string.action_generate);
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        UiObject2 generate = null;
        while (System.currentTimeMillis() < deadline) {
            generate = device.findObject(By.text(generateText));
            if (generate != null) {
                break;
            }
            SystemClock.sleep(1000);
        }
        assertNotNull("Generate button not found after typing", generate);
        boolean clicked = false;
        StaleObjectException last = null;
        for (int attempt = 0; attempt < 6 && !clicked; attempt++) {
            try {
                if (generate == null) {
                    generate = device.findObject(By.text(generateText));
                    if (generate == null) {
                        SystemClock.sleep(500);
                        continue;
                    }
                }
                generate.click();
                clicked = true;
            } catch (StaleObjectException e) {
                last = e;
                generate = null;
            }
        }
        if (!clicked) {
            throw new AssertionError("Generate button kept going stale", last);
        }
        waitForObject(device, GENERATION_TIMEOUT_MS,
            By.textContains(targetContext.getString(R.string.status_plugin_loaded, "")));
    }

    /**
     * Waits until any TextView's text matches the regex (case-insensitive).
     */
    public static void waitForDisplayValue(UiDevice device, String regex,
                                           long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (UiObject2 obj : device.findObjects(By.clazz(TextView.class))) {
                String text = obj.getText();
                if (text != null && text.trim().matches(regex)) {
                    return;
                }
            }
            SystemClock.sleep(1000);
        }
        fail("Display value not found: " + regex);
    }
}
