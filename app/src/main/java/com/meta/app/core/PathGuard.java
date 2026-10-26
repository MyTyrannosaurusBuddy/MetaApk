package com.meta.app.core;

import java.io.File;
import java.io.IOException;

/**
 * Central path-safety rules for everything that lands on disk from outside
 * (share imports) or flows into path joins (ids, lineages).
 *
 * Every rule here is pure and unit-testable; callers (SharePackage, Workspace,
 * MetaEngine deletion) all go through this class so a single miss cannot
 * reintroduce a traversal bug.
 */
public final class PathGuard {

    /** Max depth of a relative path (share package workspace entries). */
    public static final int MAX_PATH_DEPTH = 8;
    /** One path segment: printable name characters, never separators or dots-only. */
    private static final String SEGMENT_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    /** Plugin ids are always host-generated (plugin_ + hex); anything else is untrusted. */
    private static final String ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    /** Lineages are host-generated (lineage_ + hex); anything else is untrusted. */
    private static final String LINEAGE_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    private PathGuard() {}

    /**
     * Whether a share-package entry name is a safe, non-empty relative path:
     * no leading/trailing slash, no backslash, no absolute path, no "..",
     * every segment a plain name, depth bounded. The caller still does a
     * canonical containment check on the resolved file as the last line.
     */
    public static boolean isValidRelativePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.contains("\\")) {
            return false;
        }
        if (path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        if (path.contains("..")) {
            return false;
        }
        String[] segments = path.split("/");
        if (segments.length > MAX_PATH_DEPTH) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || !segment.matches(SEGMENT_PATTERN)) {
                return false;
            }
        }
        return true;
    }

    /** Whether an id/lineage token is safe to join into host paths. */
    public static boolean isValidToken(String token) {
        return token != null && token.matches(ID_PATTERN);
    }

    /** Stricter check for lineages (host-generated shape). */
    public static boolean isValidLineage(String lineage) {
        return lineage != null && lineage.matches(LINEAGE_PATTERN);
    }

    /**
     * Canonical containment: true only when the canonical target is the base
     * itself or strictly below it. Used before every read/write/move/delete
     * of anything addressed by a stored token.
     */
    public static boolean isWithin(File base, File target) {
        return Workspace.isWithinRoot(base, target);
    }

    /** Canonical path helper that never throws to callers. */
    public static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
