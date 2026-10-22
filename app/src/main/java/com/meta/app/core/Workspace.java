package com.meta.app.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The agent's working directory: one workspace per app lineage, containing
 * multiple source files (src/) plus arbitrary asset/data files. The agent
 * reads and edits files here instead of regenerating a single class every
 * round.
 *
 * All paths are contained: a relative path whose canonical target escapes
 * the workspace root is rejected.
 */
public class Workspace {

    private static final String TAG = "Workspace";
    /** Reads are truncated to this size; oversized files note the elision. */
    private static final int MAX_READ_CHARS = 200 * 1024;

    private final File root;
    private final File srcDir;

    public Workspace(File root) {
        this.root = root;
        this.srcDir = new File(root, "src");
    }

    /** The workspace for the given lineage (created lazily). */
    public static Workspace forLineage(Context context, String lineage) {
        File dir = new File(context.getFilesDir(), "workspaces/" + lineage);
        dir.mkdirs();
        return new Workspace(dir);
    }

    public File getRoot() {
        return root;
    }

    public File getSrcDir() {
        srcDir.mkdirs();
        return srcDir;
    }

    // ---------- agent tools ----------

    /** "dir/file" tree of the workspace, one entry per line. */
    public synchronized String listFiles() {
        StringBuilder sb = new StringBuilder();
        List<String> lines = new ArrayList<>();
        collect(root, "", lines);
        Collections.sort(lines);
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (lines.isEmpty()) {
            sb.append("(empty workspace)\n");
        }
        return sb.toString();
    }

    private void collect(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                collect(f, name, out);
            } else {
                out.add(name);
            }
        }
    }

    /** Reads a workspace file; oversized content keeps head and tail. */
    public synchronized String readFile(String relativePath) {
        File file = resolve(relativePath);
        if (file == null || !file.isFile()) {
            return "Error: file not found: " + relativePath;
        }
        try {
            StringBuilder sb = new StringBuilder();
            Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
            try {
                char[] buffer = new char[8192];
                int len;
                while ((len = reader.read(buffer)) > 0) {
                    sb.append(buffer, 0, len);
                }
            } finally {
                reader.close();
            }
            String content = sb.toString();
            if (content.length() > MAX_READ_CHARS) {
                int tail = 16 * 1024;
                int elided = content.length() - MAX_READ_CHARS;
                return content.substring(0, MAX_READ_CHARS)
                    + "\n... [elided " + elided + " chars] ...\n"
                    + content.substring(content.length() - tail);
            }
            return content;
        } catch (IOException e) {
            Log.w(TAG, "readFile failed: " + relativePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /** Creates or overwrites a workspace file. */
    public synchronized String writeFile(String relativePath, String content) {
        File file = resolve(relativePath);
        if (file == null) {
            return "Error: path escapes the workspace: " + relativePath;
        }
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                writer.write(content == null ? "" : content);
            } finally {
                writer.close();
            }
            return "Wrote " + relativePath + " (" + file.length() + " bytes)";
        } catch (IOException e) {
            Log.w(TAG, "writeFile failed: " + relativePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Exact-match replacement inside a file: oldString must occur exactly
     * once (zero or multiple matches are reported as errors with guidance,
     * never a silent partial edit).
     */
    public synchronized String editFile(String relativePath, String oldString, String newString) {
        File file = resolve(relativePath);
        if (file == null || !file.isFile()) {
            return "Error: file not found: " + relativePath;
        }
        String content = readFile(relativePath);
        if (content.startsWith("Error:")) {
            return content;
        }
        if (oldString == null || oldString.isEmpty()) {
            return "Error: old_string must not be empty";
        }
        int count = 0;
        int index = -1;
        int from = 0;
        while ((index = content.indexOf(oldString, from)) >= 0) {
            count++;
            from = index + oldString.length();
        }
        if (count == 0) {
            return "Error: old_string not found in " + relativePath
                + " (check the exact content with read_file)";
        }
        if (count > 1) {
            return "Error: old_string occurs " + count
                + " times; include more surrounding context to make it unique";
        }
        String updated = content.replace(oldString, newString == null ? "" : newString);
        return writeFile(relativePath, updated);
    }

    /** Case-sensitive search; returns "file:line: text" per match (capped). */
    public synchronized String grep(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "Error: empty search pattern";
        }
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        List<String> lines = new ArrayList<>();
        collectForGrep(root, pattern, lines);
        for (String line : lines) {
            sb.append(line).append('\n');
            if (++hits >= 200) {
                sb.append("... (more matches truncated)\n");
                break;
            }
        }
        if (hits == 0) {
            sb.append("(no matches)\n");
        }
        return sb.toString();
    }

    private void collectForGrep(File dir, String pattern, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectForGrep(f, pattern, out);
            } else if (f.isFile() && f.length() < 512 * 1024) {
                try {
                    List<String> fileLines = java.nio.file.Files.readAllLines(
                        f.toPath(), StandardCharsets.UTF_8);
                    String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                    for (int i = 0; i < fileLines.size(); i++) {
                        if (fileLines.get(i).contains(pattern)) {
                            out.add(rel + ":" + (i + 1) + ": " + fileLines.get(i).trim());
                        }
                    }
                } catch (Exception ignored) {
                    // skip unreadable files
                }
            }
        }
    }

    // ---------- internals ----------

    private File resolve(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        File target = new File(root, relativePath);
        return isWithinRoot(root, target) ? target : null;
    }

    /** Whether target stays inside base (canonical paths). */
    static boolean isWithinRoot(File base, File target) {
        try {
            String b = base.getCanonicalPath();
            String t = target.getCanonicalPath();
            return t.equals(b) || t.startsWith(b + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
