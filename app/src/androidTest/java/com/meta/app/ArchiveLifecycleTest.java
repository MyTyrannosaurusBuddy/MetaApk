package com.meta.app;

import android.content.Context;
import android.graphics.Rect;
import android.widget.Button;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static com.meta.app.UiTestUtils.GENERATION_TIMEOUT_MS;
import static com.meta.app.UiTestUtils.UI_TIMEOUT_MS;
import static com.meta.app.UiTestUtils.click;
import static com.meta.app.UiTestUtils.generateApp;
import static com.meta.app.UiTestUtils.getActivePluginId;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.waitForActivePluginIdChange;
import static com.meta.app.UiTestUtils.waitForObject;
import static org.junit.Assert.assertTrue;

/**
 * Archive "library" semantics:
 * - every app, including the active one, stays listed in the archive;
 * - the active row carries a marker;
 * - any app (active or not) can be deleted; deleting the active app
 *   resets the host to the initial state.
 *
 * Regression coverage for: switching to an archived app removed it from the
 * list, so the freshly switched (active) app could no longer be deleted.
 *
 * Async completion is asserted through the persisted active plugin id and
 * the archive rows (substring tags), never through model-generated UI text.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ArchiveLifecycleTest {

    private static final String PROMPT_A = "a stopwatch app with a Start button";
    private static final String TAG_A = "stopwatch";
    private static final String PROMPT_B = "a counter app with plus and minus buttons";
    private static final String TAG_B = "counter";

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void archiveLibrary_activeAppStaysListedAndDeletable() throws Exception {
        String marker = targetContext.getString(R.string.archive_active_marker);
        String deleteDesc = targetContext.getString(R.string.archive_delete);
        String archiveDesc = targetContext.getString(R.string.title_archive);

        // 1. Generate A: it must be listed immediately, marked as current.
        String idBefore = getActivePluginId(targetContext);
        generateApp(device, targetContext, PROMPT_A);
        String idA = waitForActivePluginIdChange(targetContext, idBefore, GENERATION_TIMEOUT_MS);
        openArchive(archiveDesc);
        assertTrue("A listed right after generation", hasRowTag(TAG_A));
        assertTrue("A marked as current", rowHasMarker(TAG_A, marker));
        device.pressBack();

        // 2. Generate B: both apps listed, B is the current one.
        generateApp(device, targetContext, PROMPT_B);
        String idB = waitForActivePluginIdChange(targetContext, idA, GENERATION_TIMEOUT_MS);
        openArchive(archiveDesc);
        assertTrue("A still listed", hasRowTag(TAG_A));
        assertTrue("B listed", hasRowTag(TAG_B));
        assertTrue("B marked as current", rowHasMarker(TAG_B, marker));
        device.pressBack();

        // 3. Switch to A: A must STAY in the list (regression: switching used
        //    to remove the target from the archive, making it undeletable).
        openArchive(archiveDesc);
        tapRow(TAG_A);
        waitForActivePluginIdChange(targetContext, idB, GENERATION_TIMEOUT_MS);
        openArchive(archiveDesc);
        assertTrue("A still listed after switch (regression)", hasRowTag(TAG_A));
        assertTrue("A marked as current after switch", rowHasMarker(TAG_A, marker));
        device.pressBack();

        // 4. Switch back to B, then to A again (A ends up active).
        openArchive(archiveDesc);
        tapRow(TAG_B);
        waitForActivePluginIdChange(targetContext, idA, GENERATION_TIMEOUT_MS);
        openArchive(archiveDesc);
        tapRow(TAG_A);
        waitForActivePluginIdChange(targetContext, idB, GENERATION_TIMEOUT_MS);

        // 5. Delete the ACTIVE app from the list.
        openArchive(archiveDesc);
        clickRowDelete(TAG_A, deleteDesc);
        click(device, By.clazz(Button.class)
            .text(targetContext.getString(R.string.action_delete)), UI_TIMEOUT_MS);
        // Host resets to the initial state.
        waitForObject(device, UI_TIMEOUT_MS,
            By.text(targetContext.getString(R.string.status_prompt_example)));

        // 6. A is gone from the list, B remains.
        openArchive(archiveDesc);
        assertTrue("A removed after deletion", !hasRowTag(TAG_A));
        assertTrue("B survives", hasRowTag(TAG_B));
    }

    // ---------- helpers ----------

    private void openArchive(String archiveDesc) {
        click(device, By.desc(archiveDesc), UI_TIMEOUT_MS);
        // Wait for the dialog to render before inspecting its rows.
        waitForObject(device, UI_TIMEOUT_MS,
            By.text(targetContext.getString(R.string.title_archive)));
    }

    private boolean hasRowTag(String tag) {
        return device.findObject(By.textContains(tag)) != null;
    }

    private UiObject2 rowObject(String tag) {
        return waitForObject(device, UI_TIMEOUT_MS, By.textContains(tag));
    }

    private void tapRow(String tag) {
        click(device, By.textContains(tag), UI_TIMEOUT_MS);
    }

    private boolean rowHasMarker(String tag, String marker) {
        UiObject2 row = rowObject(tag);
        Rect r = row.getVisibleBounds();
        for (UiObject2 obj : device.findObjects(By.textContains(marker))) {
            Rect m = obj.getVisibleBounds();
            if (m.centerY() >= r.top - 60 && m.centerY() <= r.bottom + 60) {
                return true;
            }
        }
        return false;
    }

    private void clickRowDelete(String tag, String deleteDesc) {
        StaleObjectException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                UiObject2 row = rowObject(tag);
                Rect r = row.getVisibleBounds();
                List<UiObject2> deletes = device.findObjects(By.desc(deleteDesc));
                for (UiObject2 del : deletes) {
                    Rect d = del.getVisibleBounds();
                    if (d.centerY() >= r.top - 60 && d.centerY() <= r.bottom + 60
                            && d.centerX() > r.right) {
                        del.click();
                        return;
                    }
                }
                assertTrue("Delete action not found for row " + tag, false);
            } catch (StaleObjectException e) {
                last = e;
            }
        }
        throw new AssertionError("Delete action kept going stale", last);
    }
}
