package com.meta.app.core;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** PathGuard is the single choke point for everything a share package can
 *  name - these cases pin down every traversal shape it must reject. */
public class PathGuardTest {

    @Test
    public void validRelativePathsPass() {
        assertTrue(PathGuard.isValidRelativePath("MainActivity.java"));
        assertTrue(PathGuard.isValidRelativePath("assets/icon.png"));
        assertTrue(PathGuard.isValidRelativePath("src/a/b/C.java"));
        assertTrue(PathGuard.isValidRelativePath("file-1_v2.java"));
        // Non-ASCII names do not match the strict segment pattern.
        assertFalse(PathGuard.isValidRelativePath("中文名.java"));
    }

    @Test
    public void traversalAndAbsolutePathsFail() {
        assertFalse(PathGuard.isValidRelativePath(""));
        assertFalse(PathGuard.isValidRelativePath(null));
        assertFalse(PathGuard.isValidRelativePath("../shared_prefs/x.xml"));
        assertFalse(PathGuard.isValidRelativePath("a/../../b"));
        assertFalse(PathGuard.isValidRelativePath("a/.."));
        assertFalse(PathGuard.isValidRelativePath("/absolute/path"));
        assertFalse(PathGuard.isValidRelativePath("C:/Windows/system32"));
        assertFalse(PathGuard.isValidRelativePath("a\\b.java"));
        assertFalse(PathGuard.isValidRelativePath("a//b.java"));
        assertFalse(PathGuard.isValidRelativePath("a/./b.java"));
        assertFalse(PathGuard.isValidRelativePath("a/.hidden"));
        assertFalse(PathGuard.isValidRelativePath(".git"));
        assertFalse(PathGuard.isValidRelativePath("a b.java"));
    }

    @Test
    public void depthIsBounded() {
        assertTrue(PathGuard.isValidRelativePath("1/2/3/4/5/6/7/8.java"));
        assertFalse(PathGuard.isValidRelativePath("1/2/3/4/5/6/7/8/9.java"));
    }

    @Test
    public void tokensFollowHostShapes() {
        assertTrue(PathGuard.isValidToken("plugin_ab12cd34"));
        assertTrue(PathGuard.isValidLineage("lineage_ab12cd34ef56"));
        assertFalse(PathGuard.isValidToken("../shared_prefs"));
        assertFalse(PathGuard.isValidToken("a/b"));
        assertFalse(PathGuard.isValidLineage(null));
        assertFalse(PathGuard.isValidLineage("lineage_../x"));
    }

    @Test
    public void canonicalContainment() throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"), "pg-base");
        base.mkdirs();
        File inside = new File(base, "workspaces/lineage_1/src");
        inside.mkdirs();
        File outside = new File(base.getParentFile(), "pg-outside");
        assertTrue(PathGuard.isWithin(base, inside));
        assertFalse(PathGuard.isWithin(base, outside));
        // A ".." sibling resolves to outside -> rejected even though it
        // lexically starts with the base path.
        assertFalse(PathGuard.isWithin(base, new File(base, "../pg-outside")));
        deleteRecursive(base);
    }

    private static void deleteRecursive(File file) {
        if (!file.exists()) {
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
