package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;
import com.meta.app.core.PluginContract;
import com.meta.app.core.SharePackage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end review flow against the real model on the device: import a
 * source-only package, switch to it, and assert that the pending entry
 * passes the mandatory model review, compiles locally and loads - with the
 * archive entry flipped to "approved". Manual/CI-optional: it needs the
 * configured model endpoint to respond.
 */
@RunWith(AndroidJUnit4.class)
public class ShareReviewFlowDeviceTest {

    private static final String DYNAMIC_PLUGIN = ""
        + "package com.meta.plugin;\n"
        + "import android.content.Context;\n"
        + "import android.view.ViewGroup;\n"
        + "import android.widget.TextView;\n"
        + "import com.meta.app.core.PluginContract;\n"
        + "import com.meta.app.bridge.ApiBridge;\n"
        + "public class DynamicPlugin implements PluginContract {\n"
        + "    public String getTitle() { return \"Review Demo\"; }\n"
        + "    public void onCreate(Context c, ViewGroup container, ApiBridge bridge) {\n"
        + "        TextView tv = new TextView(c);\n"
        + "        tv.setText(\"Security review passed\");\n"
        + "        container.addView(tv);\n"
        + "    }\n"
        + "    public void onDestroy() {}\n"
        + "}\n";

    @Test
    public void importedAppPassesReviewCompilesAndRuns() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Deliberately NOT reconfiguring the endpoint: the review must run on
        // the device's real current configuration (LlmConfig reads the user's
        // saved settings), which is also what the product path uses.

        byte[] pkg = buildPackage();
        final AtomicReference<String> imported = new AtomicReference<>("");
        final AtomicReference<String> importError = new AtomicReference<>("");
        final CountDownLatch importedLatch = new CountDownLatch(1);
        SharePackage.importInto(context, new ByteArrayInputStream(pkg),
            new SharePackage.ImportCallback() {
                @Override
                public void onImported(PluginArchive.Entry entry) {
                    imported.set(entry.id);
                    importedLatch.countDown();
                }

                @Override
                public void onError(String message) {
                    importError.set(message);
                    importedLatch.countDown();
                }
            });
        assertTrue("Import did not finish", importedLatch.await(30, TimeUnit.SECONDS));
        if (imported.get().isEmpty()) {
            fail("Import failed: " + importError.get());
        }
        String pluginId = imported.get();
        PluginArchive archive = new PluginArchive(context);
        assertEquals("pending", archive.find(pluginId).securityStatus);

        // Switch to the imported app: this is the run path that must gate on
        // the mandatory model review.
        MetaEngine engine = new MetaEngine(context);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> outcome = new AtomicReference<>("");
        engine.switchToArchivedPlugin(pluginId, new MetaEngine.RestoreCallback() {
            @Override
            public void onStatusUpdate(String status) {
                android.util.Log.i("ShareReviewFlow", "STATUS: " + status);
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
        assertTrue("Review/compile/load did not finish in 15 minutes",
            done.await(15, TimeUnit.MINUTES));
        assertTrue("Expected the app to run, got: " + outcome.get(),
            "RUNNING".equals(outcome.get()));
        String status = archive.find(pluginId).securityStatus;
        assertTrue("Approval must bind the source hash, got: " + status,
            status != null && status.startsWith("approved:"));
        java.io.File dex = new java.io.File(context.getFilesDir(),
            "plugins/" + pluginId + "/dex/classes.dex");
        assertTrue("Compiled dex missing", dex.isFile());

        // Clean up: archive entry; engine-side plugin dirs are removed via
        // the same public path the UI uses (deleteArchivedPlugin below).
        try {
            archive.delete(pluginId);
        } catch (Exception ignored) {
            // best-effort cleanup; orphan dirs are swept on next launch
        }
    }

    private static byte[] buildPackage() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write("{\"version\":2,\"title\":\"Review Demo\",\"prompt\":\"demo\"}"
                .getBytes("UTF-8"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("workspace/src/DynamicPlugin.java"));
            zip.write(DYNAMIC_PLUGIN.getBytes("UTF-8"));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
