package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.meta.app.core.PluginArchive;
import com.meta.app.core.SharePackage;
import com.meta.app.core.Workspace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Share round trip without an LLM: a fabricated app (dex bytes + workspace
 * sources) is packaged into a .metaapk file and imported back, joining the
 * archive with its files in place. Deterministic and fast.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ShareTest {

    private static final String TEST_ID = "plugin_sharetest";
    private static final String TEST_TITLE = "Shared Test App";
    private static final String TEST_LINEAGE = "lineage_sharetest";

    private Context targetContext;

    @Before
    public void setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // A fabricated app: a structurally valid minimal dex (header only,
        // empty string table - the import path scans the string table) plus
        // one workspace source file.
        writeFile(new File(targetContext.getFilesDir(),
            "plugins/" + TEST_ID + "/dex/classes.dex"), minimalDex());
        Workspace.forLineage(targetContext, TEST_LINEAGE)
            .writeFile("src/DynamicPlugin.java", "package com.meta.plugin;\nclass X {}\n");
    }

    /** A header-only dex: 112-byte header, zeroed tables. Structurally valid. */
    private static byte[] minimalDex() {
        byte[] dex = new byte[0x70];
        System.arraycopy(new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', '\0'}, 0, dex, 0, 8);
        dex[0x20] = 0x70; // file_size = 112
        dex[0x24] = 0x70; // header_size = 112
        dex[0x28] = 0x78; dex[0x29] = 0x56; dex[0x2A] = 0x34; dex[0x2B] = 0x12; // endian tag 0x12345678
        return dex;
    }

    @After
    public void tearDown() {
        new PluginArchive(targetContext).delete(TEST_ID);
        deleteRecursive(new File(targetContext.getFilesDir(), "plugins/" + TEST_ID));
    }

    @Test
    public void shareRoundTrip_importsIntoArchive() throws Exception {
        PluginArchive.Entry source = new PluginArchive.Entry(
            TEST_ID, TEST_TITLE, "a shared test app", System.currentTimeMillis(), TEST_LINEAGE);
        File packageFile = SharePackage.create(targetContext, source);
        assertTrue("Share package was not produced", packageFile.length() > 0);

        final CountDownLatch latch = new CountDownLatch(1);
        final PluginArchive.Entry[] imported = new PluginArchive.Entry[1];
        final Throwable[] failure = new Throwable[1];
        try (FileInputStream in = new FileInputStream(packageFile)) {
            SharePackage.importInto(targetContext, in, new SharePackage.ImportCallback() {
                @Override
                public void onImported(PluginArchive.Entry entry) {
                    imported[0] = entry;
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    failure[0] = new AssertionError(message);
                    latch.countDown();
                }
            });
            // The import runs on a background thread; the stream must stay
            // open until it finishes.
            assertTrue("Import did not finish", latch.await(30, TimeUnit.SECONDS));
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        assertNotNull("Import produced no archive entry", imported[0]);
        assertEquals(TEST_TITLE, imported[0].title);
        assertEquals("a shared test app", imported[0].prompt);
        assertNotNull("Archive entry not registered", new PluginArchive(targetContext).find(TEST_ID));
        File dex = new File(targetContext.getFilesDir(),
            "plugins/" + TEST_ID + "/dex/classes.dex");
        assertTrue("Imported dex missing", dex.isFile());
        byte[] importedBytes = java.nio.file.Files.readAllBytes(dex.toPath());
        byte[] expectedBytes = minimalDex();
        assertEquals("Imported dex content differs", expectedBytes.length, importedBytes.length);
        for (int i = 0; i < expectedBytes.length; i++) {
            assertEquals("Imported dex byte " + i + " differs", expectedBytes[i], importedBytes[i]);
        }

        // Importing the SAME package again must yield an independent entry:
        // a fresh id AND a fresh lineage (no shared workspace/data dir).
        final CountDownLatch secondLatch = new CountDownLatch(1);
        final PluginArchive.Entry[] second = new PluginArchive.Entry[1];
        try (FileInputStream in = new FileInputStream(packageFile)) {
            SharePackage.importInto(targetContext, in, new SharePackage.ImportCallback() {
                @Override
                public void onImported(PluginArchive.Entry entry) {
                    second[0] = entry;
                    secondLatch.countDown();
                }

                @Override
                public void onError(String message) {
                    secondLatch.countDown();
                }
            });
            assertTrue(secondLatch.await(30, TimeUnit.SECONDS));
        }
        assertNotNull("Second import produced no entry", second[0]);
        assertFalse("Duplicate import reused the id", TEST_ID.equals(second[0].id));
        assertFalse("Duplicate import shared the lineage", TEST_LINEAGE.equals(second[0].lineage));
        assertTrue("Second entry not registered",
            new PluginArchive(targetContext).find(second[0].id) != null);
        // Clean up the second entry (the tearDown only knows the fixed id).
        new PluginArchive(targetContext).delete(second[0].id);
        deleteRecursive(new File(targetContext.getFilesDir(), "plugins/" + second[0].id));
    }

    private void writeFile(File file, byte[] content) {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
