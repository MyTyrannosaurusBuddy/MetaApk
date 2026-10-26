package com.meta.app.bridge;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * File bridge - only the app-private (lineage-scoped) directory is accessible.
 *
 * The base directory follows the current plugin's lineage data dir, so data
 * written through the bridge survives feedback revisions exactly like
 * Context.getFilesDir() does. Relative paths are contained: a canonical path
 * escaping the base dir is rejected.
 */
public class FileBridge {

    private final Context context;
    private volatile File baseDir;
    /** Read-only view of the agent's workspace (set by the host per lineage):
     *  assets the model downloaded with web_fetch/to_workspace live here, and
     *  the app reads them at runtime through getAssetFile(). */
    private volatile File assetDir;

    public FileBridge(Context context) {
        this.context = context;
        this.baseDir = context.getFilesDir();
    }

    /** Switches the base directory (set by the host per active lineage). */
    public void setBaseDir(File dir) {
        this.baseDir = dir != null ? dir : context.getFilesDir();
    }

    /** Switches the read-only workspace asset directory (host, per lineage). */
    public void setAssetDir(File dir) {
        this.assetDir = dir;
    }

    /**
     * A workspace file the agent placed for the app (e.g. a photo downloaded
     * with web_fetch + to_workspace). Read-only by convention, contained by
     * canonical path check. Returns null when unavailable.
     */
    public File getAssetFile(String relativePath) {
        File root = assetDir;
        if (root == null) {
            return null;
        }
        try {
            File target = new File(root, relativePath == null ? "" : relativePath);
            if (!isWithinBase(root, target) || !target.isFile()) {
                return null;
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    /** The workspace asset directory itself (null when the host didn't set one). */
    public File getAssetDir() {
        return assetDir;
    }

    public String readFile(String relativePath) throws IOException {
        File file = resolve(relativePath);
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    public void writeFile(String relativePath, String content) throws IOException {
        File file = resolve(relativePath);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    public String[] listFiles(String relativePath) {
        try {
            return resolve(relativePath).list();
        } catch (IOException e) {
            return null;
        }
    }

    public File getFilesDir() {
        return baseDir;
    }

    /** Resolves relativePath against the base dir, rejecting escapes. */
    private File resolve(String relativePath) throws IOException {
        File target = new File(baseDir, relativePath == null ? "" : relativePath);
        if (!isWithinBase(baseDir, target)) {
            throw new IOException("Path escapes the app directory: " + relativePath);
        }
        return target;
    }

    /** Whether target stays inside base (canonical paths). */
    static boolean isWithinBase(File base, File target) throws IOException {
        String b = base.getCanonicalPath();
        String t = target.getCanonicalPath();
        return t.equals(b) || t.startsWith(b + File.separator);
    }
}
