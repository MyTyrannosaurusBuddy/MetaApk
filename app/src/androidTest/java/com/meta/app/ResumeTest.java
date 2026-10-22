package com.meta.app;

import android.content.Context;
import android.content.SharedPreferences;
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
import static com.meta.app.UiTestUtils.getActivePluginId;
import static com.meta.app.UiTestUtils.getArchiveCount;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.launchHostForResume;
import static com.meta.app.UiTestUtils.waitForActivePluginIdChange;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Task persistence round trip: submit feedback, recreate the host while the
 * task is pending and verify the task auto-resumes, produces a new revision
 * and is cleared (no duplicate archive entry).
 *
 * A process kill cannot be done from inside the instrumentation (it runs in
 * the app process), so the activity is recreated with a cleared task - the
 * resume path runs on every engine start, and the persisted state is what
 * survives the "death".
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ResumeTest {

    private static final String PENDING_PREFS = "meta_pending_task";

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void pendingTaskSurvivesRecreationAndResumes() throws Exception {
        String activeBefore = getActivePluginId(targetContext);
        int archiveBefore = getArchiveCount(targetContext);

        // Submit feedback; the task is committed before the LLM request runs.
        click(device, By.desc(targetContext.getString(R.string.title_feedback)),
            UI_TIMEOUT_MS);
        click(device, By.clazz(EditText.class), UI_TIMEOUT_MS);
        device.executeShellCommand("input text make%sdisplay%sfont%sbigger");
        click(device, By.text(targetContext.getString(R.string.feedback_submit)),
            UI_TIMEOUT_MS);

        // Recreate the activity as soon as the task is persisted.
        boolean recreated = false;
        long deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            String task = prefs(PENDING_PREFS).getString("task", null);
            if (task != null && task.contains("FEEDBACK")) {
                launchHostForResume(device, targetContext);
                recreated = true;
                break;
            }
            Thread.sleep(400);
        }
        assertTrue("Could not catch a pending task before completion", recreated);
        assertTrue("Task was lost when the activity was destroyed",
            prefs(PENDING_PREFS).getString("task", null) != null);

        // The recreated host resumes the task automatically: the active id
        // changes to the new revision, the task is cleared, no new archive
        // entry appears.
        waitForActivePluginIdChange(targetContext, activeBefore, GENERATION_TIMEOUT_MS);
        long clearedDeadline = System.currentTimeMillis() + 30_000;
        boolean cleared = false;
        while (System.currentTimeMillis() < clearedDeadline) {
            if (prefs(PENDING_PREFS).getString("task", null) == null) {
                cleared = true;
                break;
            }
            Thread.sleep(500);
        }
        assertTrue("Pending task not cleared after success", cleared);
        assertEquals("Archive count changed (duplicate entry)",
            archiveBefore, getArchiveCount(targetContext));
    }

    private SharedPreferences prefs(String name) {
        return targetContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
