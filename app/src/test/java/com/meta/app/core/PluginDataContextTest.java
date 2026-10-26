package com.meta.app.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PluginDataContextTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private PluginDataContext newContext(String lineage, File dataDir, File cacheDir) {
        // The base context is only needed by SharedPreferences; file routing
        // below must never touch it.
        return new PluginDataContext(null, lineage, dataDir, cacheDir);
    }

    @Test
    public void getFilesDir_returnsLineageDataDir() throws Exception {
        File dataDir = temp.newFolder("data");
        File cacheDir = temp.newFolder("cache");
        PluginDataContext ctx = newContext("lineage-a", dataDir, cacheDir);

        assertEquals(dataDir, ctx.getFilesDir());
    }

    @Test
    public void getCacheDir_returnsLineageCacheDir() throws Exception {
        File dataDir = temp.newFolder("data");
        File cacheDir = temp.newFolder("cache");
        PluginDataContext ctx = newContext("lineage-a", dataDir, cacheDir);

        assertEquals(cacheDir, ctx.getCacheDir());
    }

    @Test
    public void constructor_createsMissingDirs() throws Exception {
        File root = temp.newFolder("root");
        File dataDir = new File(root, "plugin_data/lineage-a/files");
        File cacheDir = new File(root, "plugin_data/lineage-a/cache");
        assertFalse(dataDir.exists());

        newContext("lineage-a", dataDir, cacheDir);

        assertTrue(dataDir.exists());
        assertTrue(cacheDir.exists());
    }

    @Test
    public void getDir_createsSubdirInsideDataDir() throws Exception {
        File dataDir = temp.newFolder("data");
        File cacheDir = temp.newFolder("cache");
        PluginDataContext ctx = newContext("lineage-a", dataDir, cacheDir);

        File sub = ctx.getDir("sub", 0);

        assertEquals(new File(dataDir, "sub"), sub);
        assertTrue(sub.isDirectory());
    }

    @Test
    public void sharedPreferencesPrefix_isLineageScoped() throws Exception {
        PluginDataContext a = newContext("lineage-a", temp.newFolder("da"), temp.newFolder("ca"));
        PluginDataContext b = newContext("lineage-b", temp.newFolder("db"), temp.newFolder("cb"));

        assertEquals("plugin_lineage-a_", a.prefsPrefix);
        assertEquals("plugin_lineage-b_", b.prefsPrefix);
        assertNotEquals(a.prefsPrefix, b.prefsPrefix);
    }

    @Test
    public void getDir_rejectsTraversalNames() throws Exception {
        File dataDir = temp.newFolder("data");
        PluginDataContext ctx = newContext("lineage-a", dataDir, temp.newFolder("cache"));

        expectTraversal(() -> ctx.getDir("../escape", 0));
        expectTraversal(() -> ctx.getDir("a/b", 0));
        expectTraversal(() -> ctx.getDir("a\\b", 0));
        expectTraversal(() -> ctx.getDir("..", 0));
        expectTraversal(() -> ctx.getDir("", 0));
        expectTraversal(() -> ctx.getDir(null, 0));
    }

    @Test
    public void getDatabasePath_rejectsTraversalNames() throws Exception {
        File dataDir = temp.newFolder("data");
        PluginDataContext ctx = newContext("lineage-a", dataDir, temp.newFolder("cache"));

        expectTraversal(() -> ctx.getDatabasePath("../escape.db"));
        expectTraversal(() -> ctx.getDatabasePath("a/b.db"));
        expectTraversal(() -> ctx.getDatabasePath(".."));
        // Normal names still resolve inside dataDir/databases.
        File db = ctx.getDatabasePath("app.db");
        assertEquals(new File(new File(dataDir, "databases"), "app.db"), db);
    }

    @Test
    public void getDir_normalNameStillWorks() throws Exception {
        File dataDir = temp.newFolder("data");
        PluginDataContext ctx = newContext("lineage-a", dataDir, temp.newFolder("cache"));
        File sub = ctx.getDir("assets-2", 0);
        assertEquals(new File(dataDir, "assets-2"), sub);
        assertTrue(sub.isDirectory());
    }

    private static void expectTraversal(Runnable action) {
        boolean thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        assertTrue("Expected IllegalArgumentException for traversal name", thrown);
    }
}
