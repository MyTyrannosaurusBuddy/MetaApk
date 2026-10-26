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

    @Test
    public void editFile_preservesLargeFileBeyondReadLimit() throws Exception {
        Workspace ws = newWorkspace();
        String original = "HEADER\n" + "x".repeat(240 * 1024) + "\nTAIL";
        ws.writeFile("src/Large.java", original);
        assertTrue(ws.editFile("src/Large.java", "HEADER", "UPDATED").startsWith("Wrote"));
        String actual = new String(java.nio.file.Files.readAllBytes(
            new File(ws.getRoot(), "src/Large.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(original.replace("HEADER", "UPDATED"), actual);
    }

    @Test
    public void editFile_detectsDuplicateInElidedMiddle() throws Exception {
        Workspace ws = newWorkspace();
        String original = "MATCH\n" + "x".repeat(210 * 1024) + "\nMATCH\n" + "x".repeat(40 * 1024);
        ws.writeFile("large.txt", original);
        assertTrue(ws.editFile("large.txt", "MATCH", "changed").startsWith("Error:"));
        assertEquals(original, new String(java.nio.file.Files.readAllBytes(
            new File(ws.getRoot(), "large.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void editFile_allowsLiteralErrorPrefix() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("notes.txt", "Error: the application failed");
        assertTrue(ws.editFile("notes.txt", "failed", "recovered").startsWith("Wrote"));
        assertEquals("Error: the application recovered", ws.readFile("notes.txt"));
    }

    @Test
    public void readFilePage_hasLineNumbersAndContinuation() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("notes.txt", "one\ntwo\nthree\nfour\n");
        String page = ws.readFilePage("notes.txt", 2, 2);
        assertTrue(page.contains("2: two\n3: three"));
        assertFalse(page.contains("1: one"));
        assertTrue(page.contains("start_line=4"));
        assertTrue(ws.readFilePage("notes.txt", 4, 20).contains("4: four"));
    }

    @Test
    public void readFilePage_boundsSingleHugeLine() throws Exception {
        Workspace ws = newWorkspace();
        ws.writeFile("data.json", "x".repeat(300 * 1024));
        String page = ws.readFilePage("data.json", 1, 200);
        assertTrue(page.length() < 21 * 1024);
        assertTrue(page.contains("line truncated"));
    }
}
