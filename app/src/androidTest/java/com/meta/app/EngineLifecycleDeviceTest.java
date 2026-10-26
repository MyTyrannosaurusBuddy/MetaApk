package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;
import com.meta.app.core.PluginContract;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression for the destroy() cleanup bug: destroying the host (screen
 * rotation) once deleted persistent artifacts of apps that were still in
 * the archive (e.g. the compile cache pointing at an app the user switched
 * away from). Destroying must release memory only; every archived app must
 * survive a destroy + engine recreation and stay switchable.
 */
@RunWith(AndroidJUnit4.class)
public class EngineLifecycleDeviceTest {

    @Test
    public void destroyKeepsArchivedAppRunnable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PluginArchive archive = new PluginArchive(context);
        List<PluginArchive.Entry> entries = archive.list();
        PluginArchive.Entry target = null;
        for (PluginArchive.Entry entry : entries) {
            File dex = new File(context.getFilesDir(),
                "plugins/" + entry.id + "/dex/classes.dex");
            if (dex.isFile()) {
                target = entry;
                break;
            }
        }
        Assume.assumeTrue("No archived app with a dex on this device", target != null);

        // Simulate a rotation: engine created (sweep runs), then destroyed.
        MetaEngine first = new MetaEngine(context);
        first.destroy();

        // The archived app's artifacts and metadata must be intact.
        File dex = new File(context.getFilesDir(),
            "plugins/" + target.id + "/dex/classes.dex");
        assertTrue("Destroy deleted the archived app's dex", dex.isFile());
        assertEquals(target.id, archive.find(target.id).id);

        // A fresh engine (what rotation produces) can still switch to it.
        MetaEngine second = new MetaEngine(context);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> outcome = new AtomicReference<>("");
        second.switchToArchivedPlugin(target.id, new MetaEngine.RestoreCallback() {
            @Override
            public void onStatusUpdate(String status) {
                android.util.Log.i("EngineLifecycle", "STATUS: " + status);
            }

            @Override
            public void onPlugin(PluginContract plugin) {
                outcome.set("RUNNING");
                done.countDown();
            }

            @Override
            public void onError(String message) {
                outcome.set("ERROR:" + message);
                done.countDown();
            }
        });
        assertTrue("Switch did not finish", done.await(60, TimeUnit.SECONDS));
        assertTrue("Archived app failed to run after destroy: " + outcome.get(),
            "RUNNING".equals(outcome.get()));
        second.destroy();
    }
}
