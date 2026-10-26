package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.meta.app.core.PluginArchive;
import com.meta.app.core.SharePackage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Share import hardening, exercised against the real filesystem of the
 * device: a share package is untrusted input, so traversal names, absolute
 * paths, carried dexes and old-format packages must all be rejected, and a
 * valid source-only package must land as pending-security-review with a
 * receiver-generated lineage.
 */
@RunWith(AndroidJUnit4.class)
public class SharePackageDeviceTest {

    private static byte[] buildPackage(String metaJson, String... entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(metaJson.getBytes("UTF-8"));
            zip.closeEntry();
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("x".getBytes("UTF-8"));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Package builder with per-entry payload sizes (limit-bypass cases). */
    private static byte[] buildPackageSized(String metaJson, String[] names, int[] sizes)
        throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(metaJson.getBytes("UTF-8"));
            zip.closeEntry();
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                byte[] chunk = new byte[16384];
                java.util.Arrays.fill(chunk, (byte) 'a');
                int remaining = sizes[i];
                while (remaining > 0) {
                    int n = Math.min(remaining, chunk.length);
                    zip.write(chunk, 0, n);
                    remaining -= n;
                }
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Hand-assembled STORED-mode zip. Needed because ZipOutputStream refuses
     * duplicate entry names - but a hand-crafted (malicious) zip can carry
     * them, and the importer must reject that shape.
     */
    private static byte[] buildRawZip(String[] names, String[] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        int offset = 0;
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes("UTF-8");
            byte[] data = contents[i].getBytes("UTF-8");
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            int crcV = (int) crc.getValue();
            // Local file header (stored).
            out.write(new byte[] {0x50, 0x4B, 0x03, 0x04});
            putLe16(out, 20);
            putLe16(out, 0);
            putLe16(out, 0);
            putLe16(out, 0);
            putLe16(out, 0);
            putLe32(out, crcV);
            putLe32(out, data.length);
            putLe32(out, data.length);
            putLe16(out, name.length);
            putLe16(out, 0);
            out.write(name);
            out.write(data);
            // Central directory entry.
            central.write(new byte[] {0x50, 0x4B, 0x01, 0x02});
            putLe16(central, 20);
            putLe16(central, 20);
            putLe16(central, 0);
            putLe16(central, 0);
            putLe16(central, 0);
            putLe16(central, 0);
            putLe32(central, crcV);
            putLe32(central, data.length);
            putLe32(central, data.length);
            putLe16(central, name.length);
            putLe16(central, 0);
            putLe16(central, 0);
            putLe16(central, 0);
            putLe16(central, 0);
            putLe32(central, 0);
            putLe32(central, offset);
            central.write(name);
            offset += 30 + name.length + data.length;
        }
        out.write(central.toByteArray());
        int centralSize = central.size();
        out.write(new byte[] {0x50, 0x4B, 0x05, 0x06});
        putLe16(out, 0);
        putLe16(out, 0);
        putLe16(out, names.length);
        putLe16(out, names.length);
        putLe32(out, centralSize);
        putLe32(out, offset);
        putLe16(out, 0);
        return out.toByteArray();
    }

    private static void putLe16(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >> 8) & 0xFF);
    }

    private static void putLe32(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >> 8) & 0xFF);
        bos.write((v >> 16) & 0xFF);
        bos.write((v >> 24) & 0xFF);
    }

    private static String validMeta() {
        return "{\"version\":2,\"title\":\"ImportedTest\",\"prompt\":\"test\"}";
    }

    /** Imports and returns the terminal outcome: entry or error message. */
    private static AtomicReference<String> importResult(byte[] pkg) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> outcome = new AtomicReference<>("");
        SharePackage.importInto(context, new ByteArrayInputStream(pkg),
            new SharePackage.ImportCallback() {
                @Override
                public void onImported(PluginArchive.Entry entry) {
                    outcome.set("OK:" + entry.id + ":" + entry.lineage + ":" + entry.securityStatus);
                    done.countDown();
                }

                @Override
                public void onError(String message) {
                    outcome.set("ERR:" + message);
                    done.countDown();
                }
            });
        assertTrue("Import did not finish", done.await(30, TimeUnit.SECONDS));
        return outcome;
    }

    @Test
    public void validSourcePackageImportsAsPending() throws Exception {
        byte[] pkg = buildPackage(validMeta(),
            "workspace/src/DynamicPlugin.java", "workspace/src/MainActivity.java");
        AtomicReference<String> result = importResult(pkg);
        String value = result.get();
        assertTrue("Expected success, got: " + value, value.startsWith("OK:"));
        String[] parts = value.split(":");
        // Receiver-generated id/lineage: never the package's own tokens.
        assertTrue(parts[1].startsWith("plugin_"));
        assertTrue(parts[2].startsWith("lineage_"));
        assertEquals("pending", parts[3]);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<PluginArchive.Entry> entries = new PluginArchive(context).list();
        assertTrue(!entries.isEmpty());
        assertEquals("pending", entries.get(0).securityStatus);
        // Clean up so later tests start from the same state.
        new PluginArchive(context).delete(parts[1]);
    }

    @Test
    public void traversalEntryIsRejected() throws Exception {
        byte[] pkg = buildPackage(validMeta(), "workspace/../evil.java");
        assertTrue(importResult(pkg).get().startsWith("ERR:"));
    }

    @Test
    public void absolutePathEntryIsRejected() throws Exception {
        byte[] pkg = buildPackage(validMeta(), "workspace//data/data/com.meta.app/x.xml");
        assertTrue(importResult(pkg).get().startsWith("ERR:"));
    }

    @Test
    public void packageCarryingDexIsRejected() throws Exception {
        byte[] pkg = buildPackage(validMeta(),
            "workspace/src/DynamicPlugin.java", "plugin.dex");
        String result = importResult(pkg).get();
        assertTrue("Expected dex rejection, got: " + result, result.startsWith("ERR:"));
        assertTrue(result.contains("dex"));
    }

    @Test
    public void oldVersionPackageIsRejected() throws Exception {
        byte[] pkg = buildPackage(
            "{\"version\":1,\"title\":\"Old\",\"prompt\":\"\"}",
            "workspace/src/DynamicPlugin.java", "plugin.dex");
        // v1 rejected on version before anything else is considered; the dex
        // presence alone also rejects, either way it must never import.
        assertTrue(importResult(pkg).get().startsWith("ERR:"));
    }

    @Test
    public void emptyPackageIsRejected() throws Exception {
        byte[] pkg = buildPackage(validMeta());
        assertTrue(importResult(pkg).get().startsWith("ERR:"));
    }

    @Test
    public void oversizedMetaIsRejected() throws Exception {
        StringBuilder big = new StringBuilder(80 * 1024);
        for (int i = 0; i < 80 * 1024; i++) {
            big.append('x');
        }
        byte[] pkg = buildPackage("{\"version\":2,\"title\":\"" + big + "\"}",
            "workspace/src/DynamicPlugin.java");
        assertTrue(importResult(pkg).get().startsWith("ERR:"));
    }

    @Test
    public void duplicateWorkspaceEntryIsRejected() throws Exception {
        // Hand-crafted zip with two same-named workspace entries (a shape
        // ZipOutputStream refuses to produce but an attacker can).
        byte[] pkg = buildRawZip(
            new String[] {"meta.json", "workspace/src/DynamicPlugin.java",
                "workspace/src/DynamicPlugin.java"},
            new String[] {validMeta(), "first", "second"});
        String result = importResult(pkg).get();
        assertTrue("Expected duplicate rejection, got: " + result, result.startsWith("ERR:"));
    }

    @Test
    public void entryCountIsBounded() throws Exception {
        String[] names = new String[2100];
        for (int i = 0; i < names.length; i++) {
            names[i] = "workspace/junk" + i + ".txt";
        }
        // 2100 entries > MAX_TOTAL_ENTRIES; rejected before anything lands.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(validMeta().getBytes("UTF-8"));
            zip.closeEntry();
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("x".getBytes("UTF-8"));
                zip.closeEntry();
            }
        }
        assertTrue(importResult(bos.toByteArray()).get().startsWith("ERR:"));
    }

    @Test
    public void oversizedPayloadBeyondDeclaredLimitIsRejected() throws Exception {
        // 513KB payload in one file: over the 512KB per-file bound, rejected
        // on ACTUAL bytes read.
        byte[] pkg = buildPackageSized(validMeta(),
            new String[] {"workspace/src/DynamicPlugin.java"},
            new int[] {513 * 1024});
        String result = importResult(pkg).get();
        assertTrue("Expected size rejection, got: " + result, result.startsWith("ERR:"));
    }

    @Test
    public void totalBytesBoundedAcrossManyFiles() throws Exception {
        // 190 files x 90KB = ~16.7MB > 16MB total bound.
        String[] names = new String[190];
        int[] sizes = new int[190];
        for (int i = 0; i < 190; i++) {
            names[i] = "workspace/src/File" + i + ".java";
            sizes[i] = 90 * 1024;
        }
        byte[] pkg = buildPackageSized(validMeta(), names, sizes);
        String result = importResult(pkg).get();
        assertTrue("Expected total-size rejection, got: " + result, result.startsWith("ERR:"));
    }

    @Test
    public void metaWithEvilIdAndLineageIsIgnored() throws Exception {
        // The package tries to name the receiver's own paths; the receiver
        // must regenerate both and never join them into a file path.
        String evilMeta = "{\"version\":2,\"title\":\"Evil\",\"prompt\":\"\","
            + "\"id\":\"../../shared_prefs\",\"lineage\":\"../../databases\"}";
        byte[] pkg = buildPackage(evilMeta, "workspace/src/DynamicPlugin.java");
        AtomicReference<String> result = importResult(pkg);
        String value = result.get();
        assertTrue("Expected success with regenerated tokens, got: " + value,
            value.startsWith("OK:"));
        String[] parts = value.split(":");
        assertTrue(parts[1].startsWith("plugin_"));
        assertTrue(parts[2].startsWith("lineage_"));
        assertNotNull(parts[3]);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new PluginArchive(context).delete(parts[1]);
    }
}
