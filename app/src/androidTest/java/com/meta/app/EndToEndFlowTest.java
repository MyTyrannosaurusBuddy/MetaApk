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
import static com.meta.app.UiTestUtils.getArchiveCount;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.launchHostForResume;
import static com.meta.app.UiTestUtils.submitFeedbackInProcess;
import static com.meta.app.UiTestUtils.waitForActivePluginIdChange;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The entire real-LLM surface in ONE test: the device features are LOCAL
 * (compile, archive, resume, permissions), so paying the model twice covers
 * everything. Session 1 generates one app that exercises the sanitizer (an
 * Exit button) and the archive bookkeeping; session 2 is a single
 * improvement interrupted by a host recreation, which also proves the
 * resume and the in-place replacement.
 *
 * The camera permission is pre-granted through the shell: the bridge's
 * callback logic belongs to the scripted engine tests, and a real-device
 * flow must not depend on the system dialog's timing.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class EndToEndFlowTest {

    private static final String FLOW_PROMPT =
        "a counter app with a large Exit button and plus and minus buttons, that also requests "
            + "camera permission immediately in onCreate via bridge.requestPermissions and "
            + "shows whether it was granted";

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // Pre-grant every permission the host declares plus the MIUI
        // clipboard/media appops: the bridge's callback logic belongs to the
        // scripted engine tests, and a real-device flow must never depend on
        // a system dialog's timing (a stray dialog hangs the whole flow).
        String pkg = targetContext.getPackageName();
        String[] permissions = {
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
        };
        for (String permission : permissions) {
            device.executeShellCommand("pm grant " + pkg + " " + permission);
        }
        // Deny the clipboard access outright: MIUI prompts a security-center
        // dialog on clipboard reads, and denying silently returns empty -
        // the dialog must never gate the flow.
        device.executeShellCommand("appops set " + pkg + " READ_CLIPBOARD deny");
        device.executeShellCommand("appops set " + pkg + " SCHEDULE_EXACT_ALARM allow");
        // Dismiss anything a previous run left on top: killing the dialog
        // hosts removes their windows without needing their coordinates.
        device.executeShellCommand("am force-stop com.miui.securitycenter");
        device.executeShellCommand("am force-stop com.android.permissioncontroller");
        device.executeShellCommand("input keyevent 4");
        device.executeShellCommand("input keyevent 4");
        launchHost(device, targetContext);
    }

    @Test
    public void fullDeviceFlow_twoLlmSessionsCoverAllScenarios() throws Exception {
        String idBefore = getActivePluginId(targetContext);
        int archiveBefore = getArchiveCount(targetContext);

        // --- LLM session 1: the generation. The Exit button proves the
        // sanitizer accepts it; the archive bookkeeping runs underneath. ---
        generateApp(device, targetContext, FLOW_PROMPT);
        String idGenerated = waitForActivePluginIdChange(device, targetContext,
            idBefore, GENERATION_TIMEOUT_MS);
        int archiveAfterGenerate = getArchiveCount(targetContext);
        assertEquals("Generation did not add one archive entry",
            archiveBefore + 1, archiveAfterGenerate);

        // --- LLM session 2: one improvement, interrupted mid-flight by a
        // host recreation - the pending task auto-resumes, the revision
        // replaces the app in place (new active id, no duplicate entry). ---
        submitFeedbackInProcess(targetContext, "make the display font bigger");
        boolean recreated = false;
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            String task = targetContext.getSharedPreferences("meta_pending_task",
                Context.MODE_PRIVATE).getString("task", null);
            if (task != null && task.contains("FEEDBACK")) {
                launchHostForResume(device, targetContext);
                recreated = true;
                break;
            }
            Thread.sleep(400);
        }
        assertTrue("Could not catch a pending task before completion", recreated);
        waitForActivePluginIdChange(device, targetContext, idGenerated, GENERATION_TIMEOUT_MS);
        assertEquals("Feedback revision created a duplicate archive entry",
            archiveAfterGenerate, getArchiveCount(targetContext));
    }
}
