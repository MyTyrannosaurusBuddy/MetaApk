package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;
import com.meta.app.core.PluginContract;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Restore rollback regression: when the CURRENT version's dex exists but is
 * corrupt (load fails), the restore path must fall back to the entry's
 * hidden previous version - the same policy the switch path applies - and
 * atomically repoint the active id, instead of clearing the active app.
 */
@RunWith(AndroidJUnit4.class)
public class RestoreRollbackDeviceTest {

    @Test
    public void corruptCurrentDexFallsBackToPreviousVersion() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // A known-good dex from an existing archived app serves as the
        // previous version (never touched).
        PluginArchive archive = new PluginArchive(context);
        PluginArchive.Entry donor = null;
        for (PluginArchive.Entry e : archive.list()) {
            File dex = new File(context.getFilesDir(), "plugins/" + e.id + "/dex/classes.dex");
            if (dex.isFile()) {
                donor = e;
                break;
            }
        }
        org.junit.Assume.assumeTrue("No archived app with a dex to borrow", donor != null);

        String brokenId = "plugin_rrbroken";
        String goodId = "plugin_rrgood";
        String lineage = "lineage_rrtest";
        File goodDex = new File(context.getFilesDir(),
            "plugins/" + goodId + "/dex/classes.dex");
        goodDex.getParentFile().mkdirs();
        copy(new File(context.getFilesDir(),
            "plugins/" + donor.id + "/dex/classes.dex"), goodDex);
        File brokenDex = new File(context.getFilesDir(),
            "plugins/" + brokenId + "/dex/classes.dex");
        brokenDex.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(brokenDex)) {
            out.write("this is not a dex".getBytes("UTF-8"));
        }

        // Entry: current=broken, prevId=good; active points at broken.
        archive.addFront(brokenId, "RollbackTest", "p", System.currentTimeMillis(),
            lineage, goodId, null);
        context.getSharedPreferences("meta_active_plugin", Context.MODE_PRIVATE)
            .edit()
            .putString("active_plugin_id", brokenId)
            .putString("active_plugin_title", "RollbackTest")
            .putString("active_plugin_prompt", "p")
            .putString("active_plugin_lineage", lineage)
            .commit();

        MetaEngine engine = new MetaEngine(context);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> outcome = new AtomicReference<>("");
        engine.restoreActivePlugin(new MetaEngine.RestoreCallback() {
            @Override public void onStatusUpdate(String status) {
                android.util.Log.i("RestoreRollback", "STATUS: " + status);
            }
            @Override public void onPlugin(PluginContract plugin) {
                outcome.set("RUNNING");
                done.countDown();
            }
            @Override public void onError(String message) {
                outcome.set("ERROR:" + message);
                done.countDown();
            }
        });
        assertTrue("Restore did not finish", done.await(60, TimeUnit.SECONDS));
        assertTrue("Expected rollback to run, got: " + outcome.get(),
            "RUNNING".equals(outcome.get()));

        // The active pointer must now name the previous version.
        String activeId = context.getSharedPreferences("meta_active_plugin",
            Context.MODE_PRIVATE).getString("active_plugin_id", "");
        assertEquals("Active id must be repointed to the rolled-back version", goodId, activeId);
        // The broken current version is dropped from the archive.
        assertEquals(null, archive.find(brokenId));
        assertEquals(goodId, archive.find(goodId).id);

        engine.destroy();
        archive.delete(goodId);
        archive.delete(brokenId);
        context.getSharedPreferences("meta_active_plugin", Context.MODE_PRIVATE)
            .edit().remove("active_plugin_id").commit();
    }

    private static void copy(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16384];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }
}
