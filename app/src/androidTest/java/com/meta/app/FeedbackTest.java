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
import static com.meta.app.UiTestUtils.clickIfPresent;
import static com.meta.app.UiTestUtils.generateApp;
import static com.meta.app.UiTestUtils.getActivePluginId;
import static com.meta.app.UiTestUtils.getArchiveCount;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.waitForActivePluginIdChange;
import static org.junit.Assert.assertEquals;

/**
 * Feedback round trip: generate a deliberately broken calculator, submit
 * feedback and verify the revision replaces the app in place (new active id,
 * no duplicate archive entry).
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class FeedbackTest {

    private static final String BROKEN_PROMPT =
        "a calculator app with digit buttons and a plus and an equals button "
            + "where pressing 1 plus 1 equals shows Error";

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void feedbackRoundTrip_replacesAppInPlace() throws Exception {
        String idBefore = getActivePluginId(targetContext);
        generateApp(device, targetContext, BROKEN_PROMPT);
        String idGenerated = waitForActivePluginIdChange(targetContext, idBefore,
            GENERATION_TIMEOUT_MS);
        int archiveBefore = getArchiveCount(targetContext);

        // Reproduce the bug (best-effort: the model's calculator layout
        // varies, and the round trip is asserted through persisted state).
        clickIfPresent(device, By.text("1"), 10_000);
        clickIfPresent(device, By.text("+"), 10_000);
        clickIfPresent(device, By.text("1"), 10_000);
        clickIfPresent(device, By.text("="), 10_000);

        // Submit feedback through the dialog.
        click(device, By.desc(targetContext.getString(R.string.title_feedback)),
            UI_TIMEOUT_MS);
        click(device, By.clazz(EditText.class), UI_TIMEOUT_MS);
        device.executeShellCommand("input text one%splus%sone%sshows%sError%sinstead%sof%s2%s"
            + "please%sfix%sthe%sarithmetic");
        click(device, By.text(targetContext.getString(R.string.feedback_submit)),
            UI_TIMEOUT_MS);

        // The revision replaces the app in place: a new active id, and the
        // archive count must not grow.
        waitForActivePluginIdChange(targetContext, idGenerated, GENERATION_TIMEOUT_MS);
        assertEquals("Feedback revision created a duplicate archive entry",
            archiveBefore, getArchiveCount(targetContext));
    }
}
