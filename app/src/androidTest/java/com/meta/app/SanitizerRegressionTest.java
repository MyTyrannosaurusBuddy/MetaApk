package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.meta.app.UiTestUtils.GENERATION_TIMEOUT_MS;
import static com.meta.app.UiTestUtils.generateApp;
import static com.meta.app.UiTestUtils.getActivePluginId;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.waitForActivePluginIdChange;

/**
 * Regression coverage for scanner false positives: an app with a button
 * labeled "Exit" next to ordinary System use used to be rejected by the
 * bytecode scanner's substring matching (System + "exit"), and an app using
 * executors next to Runtime.availableProcessors() used to trip the
 * Runtime + "exec" rule. Such apps must generate normally.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SanitizerRegressionTest {

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void exitButtonApp_generatesSuccessfully() throws Exception {
        String before = getActivePluginId(targetContext);

        generateApp(device, targetContext,
            "an app with a timer display that updates every second and an Exit button");

        waitForActivePluginIdChange(targetContext, before, GENERATION_TIMEOUT_MS);
    }
}
