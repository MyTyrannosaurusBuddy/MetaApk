package com.meta.app.bridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileBridgeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void isWithinBase_allowsInsideAndSubdirs() throws Exception {
        File base = temp.newFolder("base");

        assertTrue(FileBridge.isWithinBase(base, base));
        assertTrue(FileBridge.isWithinBase(base, new File(base, "a.txt")));
        assertTrue(FileBridge.isWithinBase(base, new File(base, "sub/dir/deep.txt")));
    }

    @Test
    public void isWithinBase_rejectsParentTraversal() throws Exception {
        File base = temp.newFolder("base");

        assertFalse(FileBridge.isWithinBase(base, new File(base, "../escape.txt")));
        assertFalse(FileBridge.isWithinBase(base, new File(base, "../../escape.txt")));
    }

    @Test
    public void isWithinBase_rejectsSiblingAndAbsolutePaths() throws Exception {
        File base = temp.newFolder("base");
        File sibling = temp.newFolder("sibling");

        assertFalse(FileBridge.isWithinBase(base, new File(sibling, "x.txt")));
        assertFalse(FileBridge.isWithinBase(base, new File("/etc/passwd")));
    }
}
