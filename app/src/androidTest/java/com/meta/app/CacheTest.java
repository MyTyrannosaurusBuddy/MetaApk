package com.meta.app;

import android.content.Context;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.meta.app.UiTestUtils.GENERATION_TIMEOUT_MS;
import static com.meta.app.UiTestUtils.UI_TIMEOUT_MS;
import static com.meta.app.UiTestUtils.click;
import static com.meta.app.UiTestUtils.generateApp;
import static com.meta.app.UiTestUtils.getActivePluginId;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.waitForActivePluginIdChange;
import static org.junit.Assert.assertTrue;

/**
 * Same-prompt generation cache: re-entering a previous prompt switches back
 * to the cached app without an LLM round trip. The switch completes in a few
 * seconds; the wait timeout discriminates against a real generation.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class CacheTest {

    private static final String PROMPT_A = "a stopwatch app with a Start button";
    private static final String PROMPT_B = "a counter app with plus and minus buttons";
    private static final long CACHE_SWITCH_TIMEOUT_MS = 75_000;

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void reEnteringPrompt_switchesBackInstantly() throws Exception {
        // 1. Generate A, then B (both prompts are recorded in the cache).
        String idBefore = getActivePluginId(targetContext);
        generateApp(device, targetContext, PROMPT_A);
        String idA = waitForActivePluginIdChange(targetContext, idBefore, GENERATION_TIMEOUT_MS);
        generateApp(device, targetContext, PROMPT_B);
        waitForActivePluginIdChange(targetContext, idA, GENERATION_TIMEOUT_MS);

        // 2. Re-enter A's exact prompt. A cache hit switches back in seconds;
        //    a real generation would take far longer than the timeout.
        click(device, By.clazz(EditText.class), UI_TIMEOUT_MS);
        device.executeShellCommand("input text " + PROMPT_A.replace(" ", "%s"));
        String idBeforeSwitch = getActivePluginId(targetContext);
        long started = System.currentTimeMillis();
        click(device, By.text(targetContext.getString(R.string.action_generate)),
            UI_TIMEOUT_MS);

        waitForActivePluginIdChange(targetContext, idBeforeSwitch,
            CACHE_SWITCH_TIMEOUT_MS);
        long elapsedMs = System.currentTimeMillis() - started;
        assertTrue("Switch took " + elapsedMs + " ms - looks like a real generation",
            elapsedMs < 60_000);
        assertTrue("Active app did not switch back to A",
            idA.equals(getActivePluginId(targetContext)));
    }
}
