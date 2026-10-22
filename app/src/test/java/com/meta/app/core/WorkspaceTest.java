package com.meta.app.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkspaceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Workspace newWorkspace() throws Exception {
        return new Workspace(temp.newFolder("workspace"));
    }

    @Test
    public void writeAndReadFile_roundTrip() throws Exception {
        Workspace ws = newWorkspace();

        String result = ws.writeFile("src/a.txt", "hello");
        assertTrue(result.startsWith("Wrote src/a.txt"));

        assertEquals("hello", ws.readFile("src/a.txt").trim());
    }

    @Test
    public void listFiles_showsNestedEntries() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("src/com/meta/plugin/DynamicPlugin.java", "class X {}");
        ws.writeFile("notes.txt", "n");

        String listing = ws.listFiles();
        assertTrue(listing.contains("src/com/meta/plugin/DynamicPlugin.java"));
        assertTrue(listing.contains("notes.txt"));
    }

    @Test
    public void editFile_replacesUniqueMatch() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("src/A.java", "int x = 1;\nint y = 2;\n");

        String result = ws.editFile("src/A.java", "int x = 1;", "int x = 42;");

        assertTrue(result.startsWith("Wrote"));
        assertTrue(ws.readFile("src/A.java").contains("int x = 42;"));
    }

    @Test
    public void editFile_rejectsMissingOldString() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("src/A.java", "int x = 1;");

        String result = ws.editFile("src/A.java", "not there", "x");

        assertTrue(result.startsWith("Error: old_string not found"));
    }

    @Test
    public void editFile_rejectsAmbiguousOldString() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("src/A.java", "int x = 1;\nint x = 1;\n");

        String result = ws.editFile("src/A.java", "int x = 1;", "int x = 2;");

        assertTrue(result.contains("occurs 2 times"));
    }

    @Test
    public void readFile_reportsMissingFile() throws Exception {
        Workspace ws = newWorkspace();

        assertTrue(ws.readFile("missing.txt").startsWith("Error: file not found"));
    }

    @Test
    public void writeFile_rejectsParentTraversal() throws Exception {
        Workspace ws = newWorkspace();

        String result = ws.writeFile("../escape.txt", "x");

        assertTrue(result.contains("escapes the workspace"));
        assertFalse(new File(temp.getRoot(), "escape.txt").exists());
    }

    @Test
    public void grep_findsMatchesWithLineNumbers() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("src/A.java", "int alpha = 1;\nint beta = 2;\n");

        String result = ws.grep("beta");

        assertTrue(result.contains("src/A.java:2:"));
        assertTrue(result.contains("int beta = 2;"));
    }

    @Test
    public void isWithinRoot_acceptsAndRejectsCanonicalPaths() throws Exception {
        File root = temp.newFolder("root");

        assertTrue(Workspace.isWithinRoot(root, new File(root, "a/b.txt")));
        assertFalse(Workspace.isWithinRoot(root, new File(root, "../out.txt")));
        assertFalse(Workspace.isWithinRoot(root, new File("/etc/passwd")));
    }
}
