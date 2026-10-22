package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.meta.app.UiTestUtils.generateApp;
import static com.meta.app.UiTestUtils.launchHost;

/**
 * Core generation loop: prompt -> LLM -> sanitizer -> ECJ -> D8 -> plugin UI.
 * Requires a device with a configured LLM endpoint.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class GenerationTest {

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void generateCounterApp_loadsSuccessfully() throws Exception {
        generateApp(device, targetContext,
            "a simple counter app with plus and minus buttons");
    }
}
