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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * App sharing: packages the project SOURCES (workspace files) of a generated
 * app into a .metaapk file and imports such a package into the archive.
 *
 * v2 (the only accepted format) carries NO compiled dex: the receiver always
 * compiles locally, and - because the code is foreign - must pass a mandatory
 * model security review before it may run (the archive entry is created with
 * securityStatus="pending"; MetaEngine enforces the review on the run path).
 *
 * Import hardening (a share package is untrusted input):
 *  - id/lineage are NEVER taken from the package; the receiver generates them
 *  - every entry name passes PathGuard.isValidRelativePath (no absolute
 *    paths, no "..", no separators, bounded depth)
 *  - everything is extracted into a unique staging dir first; the workspace
 *    is published only after all checks pass
 *  - a canonical containment check guards every write
 *  - a dex entry inside the package is an immediate rejection
 */
public final class SharePackage {

    private static final int PACKAGE_VERSION = 2;
    private static final String META_ENTRY = "meta.json";
    private static final String DEX_ENTRY = "plugin.dex";
    private static final String WORKSPACE_PREFIX = "workspace/";
    /** Import safety: a share package beyond these bounds is rejected. */
    private static final long MAX_WORKSPACE_FILE_BYTES = 512L * 1024;
    private static final int MAX_WORKSPACE_FILES = 200;
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    /** meta.json is small JSON; a larger one is never legitimate. */
    private static final int MAX_META_BYTES = 64 * 1024;
    /** Zip-slip guard: the entry count itself is bounded. */
    private static final int MAX_TOTAL_ENTRIES = 2048;

    public interface ImportCallback {
        void onImported(PluginArchive.Entry entry);
        void onError(String message);
    }

    private SharePackage() {}

    /** Packages an archived app's sources into a shareable .metaapk file. */
    public static File create(Context context, PluginArchive.Entry entry) throws Exception {
        Workspace workspace = Workspace.forLineage(context, entry.lineage);
        List<String> files = listFiles(workspace.getRoot());
        boolean hasJava = false;
        long javaChars = 0;
        for (String relative : files) {
            if (relative.endsWith(".java")) {
                hasJava = true;
                javaChars += new File(workspace.getRoot(), relative).length();
            }
        }
        if (!hasJava) {
            throw new IllegalStateException(
                "This app has no project sources to share (only the compiled version exists)");
        }
        // Export mirrors the review budget: anything shareable must be fully
        // reviewable on the receiving side (no truncated review, no unexamined
        // files ever run).
        if (javaChars > SecurityReview.MAX_SOURCE_CHARS) {
            throw new IllegalStateException(
                "The project's sources exceed the security review budget ("
                    + SecurityReview.MAX_SOURCE_CHARS + " chars) and cannot be shared");
        }
        File shareDir = new File(context.getCacheDir(), "share");
        shareDir.mkdirs();
        String name = (entry.title == null || entry.title.trim().isEmpty()
            ? "app" : entry.title.trim()) + ".metaapk";
        File output = new File(shareDir, name.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_"));

        JsonObject meta = new JsonObject();
        meta.addProperty("version", PACKAGE_VERSION);
        meta.addProperty("title", entry.title);
        meta.addProperty("prompt", entry.prompt == null ? "" : entry.prompt);
        // id/lineage deliberately NOT exported: the receiver generates its
        // own, so a package can never address the receiver's file layout.

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            writeEntry(out, META_ENTRY, meta.toString().getBytes(StandardCharsets.UTF_8));
            long workspaceTotal = 0;
            if (files.size() > MAX_WORKSPACE_FILES) {
                throw new IllegalStateException("The project has too many files to share");
            }
            for (String relative : files) {
                if (!PathGuard.isValidRelativePath(relative)) {
                    throw new IllegalStateException("Unsafe project path cannot be shared: " + relative);
                }
                File source = new File(workspace.getRoot(), relative);
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
                    readAll(new FileInputStream(new File(workspace.getRoot(), relative))));
            }
        }
        return output;
    }

    /** Imports a .metaapk stream: validates, stages, publishes sources and
     *  registers the archive entry as pending-security-review. */
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
        // Unique staging dir: parallel imports can never interfere, and
        // nothing is published until every check has passed.
        File staging = new File(context.getCacheDir(),
            "imports/" + UUID.randomUUID().toString().substring(0, 12));
        File stagedWorkspace = new File(staging, "workspace");
        stagedWorkspace.mkdirs();
        byte[] buffer = new byte[16384];
        long totalBytes = 0;
        int entryCount = 0;
        JsonObject meta = null;
        List<String> stagedFiles = new ArrayList<>();
        Set<String> seenWorkspaceNames = new HashSet<>();
        boolean sawDex = false;
        try {
            try (ZipInputStream in = new ZipInputStream(source)) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    if (++entryCount > MAX_TOTAL_ENTRIES) {
                        throw new IllegalArgumentException("Share package has too many entries");
                    }
                    String name = entry.getName();
                    if (name.equals(META_ENTRY)) {
                        // Metadata is read with a hard byte bound - never the
                        // zip header's word or an unbounded readAll.
                        meta = JsonParser.parseString(
                            new String(readAllBounded(in, MAX_META_BYTES, "meta.json"),
                                StandardCharsets.UTF_8)).getAsJsonObject();
                    } else if (name.equals(DEX_ENTRY)) {
                        // v2 never carries a dex; one present means an old or
                        // tampered package. Reject outright - compiled foreign
                        // code must never be executed.
                        sawDex = true;
                    } else if (name.startsWith(WORKSPACE_PREFIX)) {
                        String relative = name.substring(WORKSPACE_PREFIX.length());
                        if (!PathGuard.isValidRelativePath(relative)) {
                            throw new SecurityException("Unsafe path in share package: " + relative);
                        }
                        if (!seenWorkspaceNames.add(relative)) {
                            // A duplicate entry would overwrite the staged
                            // copy of the first one - reject instead of
                            // letting later content silently replace it.
                            throw new SecurityException("Duplicate workspace entry: " + relative);
                        }
                        if (stagedFiles.size() >= MAX_WORKSPACE_FILES) {
                            throw new IllegalArgumentException("Share package has too many files");
                        }
                        File target = new File(stagedWorkspace, relative);
                        // Last line of defence: canonical containment.
                        if (!PathGuard.isWithin(stagedWorkspace, target)) {
                            throw new SecurityException("Path escapes staging dir: " + relative);
                        }
                        target.getParentFile().mkdirs();
                        // Limits are enforced on ACTUAL bytes read while the
                        // stream is consumed - the zip header's declared size
                        // is attacker-controlled and never trusted.
                        long remainingTotal = MAX_TOTAL_BYTES - totalBytes;
                        try (FileOutputStream out = new FileOutputStream(target)) {
                            long written = copyLimited(in, out, buffer,
                                Math.min(MAX_WORKSPACE_FILE_BYTES, remainingTotal),
                                relative);
                            totalBytes += written;
                        }
                        stagedFiles.add(relative);
                    }
                    // Unknown entries are ignored (forward compatibility).
                }
            }
            if (sawDex) {
                throw new SecurityException(
                    "This share package carries a compiled dex; only source-only v2 packages are accepted");
            }
            if (meta == null) {
                throw new IllegalArgumentException("Not a MetaApk share package");
            }
            int version = meta.has("version") ? meta.get("version").getAsInt() : 0;
            if (version != PACKAGE_VERSION) {
                throw new IllegalArgumentException(
                    "Unsupported share package version " + version + " (expected " + PACKAGE_VERSION
                        + "); export it again from the latest MetaApk");
            }
            boolean hasJava = false;
            for (String relative : stagedFiles) {
                if (relative.endsWith(".java")) {
                    hasJava = true;
                    break;
                }
            }
            if (!hasJava) {
                throw new IllegalArgumentException("Share package contains no source files");
            }

            // The package never names the receiver's files: fresh local id
            // and lineage, always host-generated.
            String id = "plugin_" + UUID.randomUUID().toString().substring(0, 8);
            String lineage = "lineage_" + UUID.randomUUID().toString().substring(0, 12);
            String title = meta.has("title") && !meta.get("title").isJsonNull()
                ? meta.get("title").getAsString() : "Imported app";
            String prompt = meta.has("prompt") && !meta.get("prompt").isJsonNull()
                ? meta.get("prompt").getAsString() : "";

            // Publish: copy the staged sources into the new workspace under a
            // containment check on the final location too.
            Workspace workspace = Workspace.forLineage(context, lineage);
            File workspaceRoot = workspace.getRoot();
            for (String relative : stagedFiles) {
                File stagedFile = new File(stagedWorkspace, relative);
                File target = new File(workspaceRoot, relative);
                if (!PathGuard.isWithin(workspaceRoot, target)) {
                    throw new SecurityException("Path escapes workspace: " + relative);
                }
                target.getParentFile().mkdirs();
                try (FileInputStream in = new FileInputStream(stagedFile);
                     FileOutputStream out = new FileOutputStream(target)) {
                    copy(in, out, buffer);
                }
            }

            PluginArchive archive = new PluginArchive(context);
            archive.addFront(id, title, prompt, System.currentTimeMillis(), lineage, null,
                "pending");
            return archive.find(id);
        } finally {
            deleteRecursive(staging);
        }
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

    /** Reads at most {@code max} bytes; anything larger is rejected - the
     *  caller's size limits must not depend on attacker-controlled headers. */
    private static byte[] readAllBounded(InputStream in, int max, String what)
        throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int len;
        int total = 0;
        while ((len = in.read(buffer)) > 0) {
            total += len;
            if (total > max) {
                throw new IllegalArgumentException(
                    "Share package " + what + " exceeds " + max + " bytes");
            }
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    /** Stream copy that rejects after {@code limit} REAL bytes consumed
     *  (never the zip header's declared size); returns the bytes written. */
    private static long copyLimited(InputStream in, java.io.OutputStream out, byte[] buffer,
                                    long limit, String what) throws Exception {
        long total = 0;
        int len;
        while ((len = in.read(buffer)) > 0) {
            total += len;
            if (total > limit) {
                throw new IllegalArgumentException(
                    "Share package entry too large: " + what);
            }
            out.write(buffer, 0, len);
        }
        return total;
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
        // noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
