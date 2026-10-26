package com.meta.app.core;

import android.content.Context;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * App sharing: packages a generated app (dex + title/prompt + workspace
 * sources) into a .metaapk file and imports such a package into the archive.
 *
 * Sharing carries the running artifact AND the project sources, so the
 * receiver can use the app immediately (no install, it just joins their
 * archive) and keep iterating on it with their own MetaApk.
 */
public final class SharePackage {

    private static final int PACKAGE_VERSION = 1;
    private static final String META_ENTRY = "meta.json";
    private static final String DEX_ENTRY = "plugin.dex";
    private static final String WORKSPACE_PREFIX = "workspace/";
    /** Import safety: a share package beyond these bounds is rejected. */
    private static final long MAX_DEX_BYTES = 8L * 1024 * 1024;
    private static final long MAX_WORKSPACE_FILE_BYTES = 512L * 1024;
    private static final int MAX_WORKSPACE_FILES = 200;
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    public interface ImportCallback {
        void onImported(PluginArchive.Entry entry);
        void onError(String message);
    }

    private SharePackage() {}

    /** Packages an archived app into a shareable .metaapk file. */
    public static File create(Context context, PluginArchive.Entry entry) throws Exception {
        File dex = new File(context.getFilesDir(), "plugins/" + entry.id + "/dex/classes.dex");
        if (!dex.isFile()) {
            throw new IllegalStateException("App files are missing: " + dex);
        }
        File shareDir = new File(context.getCacheDir(), "share");
        shareDir.mkdirs();
        String name = (entry.title == null || entry.title.trim().isEmpty()
            ? "app" : entry.title.trim()) + ".metaapk";
        File output = new File(shareDir, name.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_"));

        JsonObject meta = new JsonObject();
        meta.addProperty("version", PACKAGE_VERSION);
        meta.addProperty("id", entry.id);
        meta.addProperty("title", entry.title);
        meta.addProperty("prompt", entry.prompt == null ? "" : entry.prompt);
        meta.addProperty("lineage", entry.lineage == null ? "" : entry.lineage);

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            writeEntry(out, META_ENTRY, meta.toString().getBytes(StandardCharsets.UTF_8));
            writeEntry(out, DEX_ENTRY, readAll(new FileInputStream(dex)));
            File workspace = Workspace.forLineage(context, entry.lineage).getRoot();
            List<String> files = listFiles(workspace);
            // Export mirrors the import limits, so a package the sender
            // produces is always acceptable to the receiver.
            long workspaceTotal = 0;
            if (files.size() > MAX_WORKSPACE_FILES) {
                throw new IllegalStateException("The project has too many files to share");
            }
            for (String relative : files) {
                File source = new File(workspace, relative);
                if (source.length() > MAX_WORKSPACE_FILE_BYTES) {
                    throw new IllegalStateException("Project file too large to share: " + relative);
                }
                workspaceTotal += source.length();
                if (workspaceTotal > MAX_TOTAL_BYTES) {
                    throw new IllegalStateException("The project is too large to share");
                }
            }
            for (String relative : files) {
                writeEntry(out, WORKSPACE_PREFIX + relative,
                    readAll(new FileInputStream(new File(workspace, relative))));
            }
        }
        return output;
    }

    /** Imports a .metaapk stream: places the files and registers the archive entry. */
    public static void importInto(Context context, InputStream source, ImportCallback callback) {
        new Thread(() -> {
            try {
                PluginArchive.Entry entry = extract(context, source);
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onImported(entry));
            } catch (Throwable t) {
                android.util.Log.e("SharePackage", "Import failed", t);
                final String message = t.getClass().getSimpleName() + ": " + t.getMessage();
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onError(message));
            }
        }, "ShareImport").start();
    }

    private static PluginArchive.Entry extract(Context context, InputStream source)
        throws Exception {
        File dexOut = null;
        JsonObject meta = null;
        List<String[]> workspaceFiles = new ArrayList<>();
        byte[] buffer = new byte[16384];
        long totalBytes = 0;
        try (ZipInputStream in = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(META_ENTRY)) {
                    meta = JsonParser.parseString(
                        new String(readAll(in), StandardCharsets.UTF_8)).getAsJsonObject();
                } else if (name.equals(DEX_ENTRY)) {
                    if (entry.getSize() > MAX_DEX_BYTES) {
                        throw new IllegalArgumentException("Share package dex too large");
                    }
                    File target = new File(context.getFilesDir(), "plugins/import.dex");
                    target.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        copy(in, out, buffer);
                    }
                    // The imported dex is taken as-is (no local compile), so it
                    // gets the same forbidden-operation rules the local build
                    // path enforces, applied to its string table.
                    CodeSanitizer.SanitizeResult scan = CodeSanitizer.scanDexStrings(target);
                    if (!scan.passed) {
                        target.delete();
                        throw new SecurityException(scan.reason);
                    }
                    totalBytes += target.length();
                    dexOut = target;
                } else if (name.startsWith(WORKSPACE_PREFIX)) {
                    String relative = name.substring(WORKSPACE_PREFIX.length());
                    if (relative.isEmpty() || relative.contains("..")) {
                        continue;
                    }
                    if (workspaceFiles.size() >= MAX_WORKSPACE_FILES) {
                        throw new IllegalArgumentException("Share package has too many files");
                    }
                    if (entry.getSize() > MAX_WORKSPACE_FILE_BYTES) {
                        throw new IllegalArgumentException("Share package file too large: " + relative);
                    }
                    File target = new File(context.getFilesDir(), "import-workspace/" + relative);
                    target.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        copy(in, out, buffer);
                    }
                    totalBytes += target.length();
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IllegalArgumentException("Share package too large");
                    }
                    workspaceFiles.add(new String[] {relative, target.getAbsolutePath()});
                }
            }
        }
        if (meta == null || dexOut == null) {
            throw new IllegalArgumentException("Not a MetaApk share package");
        }
        String id = meta.has("id") ? meta.get("id").getAsString() : "";
        String title = meta.has("title") && !meta.get("title").isJsonNull()
            ? meta.get("title").getAsString() : "Imported app";
        String prompt = meta.has("prompt") && !meta.get("prompt").isJsonNull()
            ? meta.get("prompt").getAsString() : "";
        String lineage = meta.has("lineage") && !meta.get("lineage").isJsonNull()
            ? meta.get("lineage").getAsString()
            : "lineage_" + java.util.UUID.randomUUID().toString().substring(0, 12);
        if (id.isEmpty()) {
            id = "plugin_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        // Collision safety: a fresh id when the receiver already has it. A
        // duplicate import must also get a fresh lineage, otherwise the two
        // archive entries would share one workspace/data dir and revisions
        // would leak between them.
        PluginArchive archive = new PluginArchive(context);
        if (archive.find(id) != null) {
            id = "plugin_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            lineage = "lineage_" + java.util.UUID.randomUUID().toString().substring(0, 12);
        }
        File finalDex = new File(context.getFilesDir(), "plugins/" + id + "/dex/classes.dex");
        finalDex.getParentFile().mkdirs();
        if (!dexOut.renameTo(finalDex)) {
            try (FileInputStream in = new FileInputStream(dexOut);
                 FileOutputStream out = new FileOutputStream(finalDex)) {
                copy(in, out, buffer);
            }
            dexOut.delete();
        }
        AssetTools.makeReadOnly(finalDex);
        Workspace workspace = Workspace.forLineage(context, lineage);
        for (String[] file : workspaceFiles) {
            File target = new File(workspace.getRoot(), file[0]);
            target.getParentFile().mkdirs();
            File staged = new File(file[1]);
            if (!staged.renameTo(target)) {
                try (FileInputStream in = new FileInputStream(staged);
                     FileOutputStream out = new FileOutputStream(target)) {
                    copy(in, out, buffer);
                }
                staged.delete();
            }
        }
        archive.addFront(id, title, prompt, System.currentTimeMillis(), lineage);
        return archive.find(id);
    }

    private static List<String> listFiles(File root) {
        List<String> result = new ArrayList<>();
        collect(root, root, result);
        return result;
    }

    private static void collect(File root, File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collect(root, file, out);
            } else {
                String relative = root.toPath().relativize(file.toPath())
                    .toString().replace('\\', '/');
                if (!relative.contains("..")) {
                    out.add(relative);
                }
            }
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data)
        throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private static void copy(InputStream in, java.io.OutputStream out, byte[] buffer)
        throws Exception {
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        copy(in, out, new byte[16384]);
        return out.toByteArray();
    }
}
