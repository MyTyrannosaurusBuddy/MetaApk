package com.meta.app.core;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verdict parsing and the no-default-allow rule of the security review. */
public class SecurityReviewTest {

    @Test
    public void extractJsonToleratesMarkdownFences() {
        JsonObject plain = SecurityReview.extractJson("{\"verdict\":\"PASS\"}");
        assertEquals("PASS", plain.get("verdict").getAsString());
        JsonObject fenced = SecurityReview.extractJson(
            "```json\n{\"verdict\": \"BLOCK\", \"reason\": \"exfiltration\"}\n```");
        assertEquals("BLOCK", fenced.get("verdict").getAsString());
        assertNull(SecurityReview.extractJson("no json here"));
        assertNull(SecurityReview.extractJson(""));
        assertNull(SecurityReview.extractJson("{broken"));
    }

    @Test
    public void passCallsApprovedWithHash() {
        AtomicReference<String> state = new AtomicReference<>("");
        AtomicReference<String> hash = new AtomicReference<>("");
        SecurityReview.handleResponse(assistant("{\"verdict\":\"PASS\"}"), "hash-1",
            new SecurityReview.Callback() {
                @Override public void onApproved(String h) { state.set("approved"); hash.set(h); }
                @Override public void onBlocked(String reason) { state.set("blocked"); }
                @Override public void onError(String message) { state.set("error"); }
            });
        assertEquals("approved", state.get());
        assertEquals("hash-1", hash.get());
    }

    @Test
    public void blockCallsBlockedWithReason() {
        AtomicReference<String> state = new AtomicReference<>("");
        AtomicReference<String> reason = new AtomicReference<>("");
        SecurityReview.handleResponse(
            assistant("{\"verdict\":\"BLOCK\",\"reason\":\"sends files to the network\"}"), "h",
            new SecurityReview.Callback() {
                @Override public void onApproved(String h) { state.set("approved"); }
                @Override public void onBlocked(String r) { state.set("blocked"); reason.set(r); }
                @Override public void onError(String message) { state.set("error"); }
            });
        assertEquals("blocked", state.get());
        assertEquals("sends files to the network", reason.get());
    }

    @Test
    public void unreadableVerdictNeverDefaultsToAllow() {
        AtomicReference<String> state = new AtomicReference<>("");
        SecurityReview.handleResponse(assistant("I think this code is fine!"), "h",
            new SecurityReview.Callback() {
                @Override public void onApproved(String h) { state.set("approved"); }
                @Override public void onBlocked(String reason) { state.set("blocked"); }
                @Override public void onError(String message) { state.set("error"); }
            });
        assertEquals("error", state.get());

        state.set("");
        SecurityReview.handleResponse(assistant("{\"verdict\":\"MAYBE\"}"), "h",
            new SecurityReview.Callback() {
                @Override public void onApproved(String h) { state.set("approved"); }
                @Override public void onBlocked(String reason) { state.set("blocked"); }
                @Override public void onError(String message) { state.set("error"); }
            });
        assertEquals("error", state.get());
    }

    @Test
    public void emptyResponseIsAnError() {
        AtomicReference<String> state = new AtomicReference<>("");
        SecurityReview.handleResponse(assistant("  "), "h",
            new SecurityReview.Callback() {
                @Override public void onApproved(String h) { state.set("approved"); }
                @Override public void onBlocked(String reason) { state.set("blocked"); }
                @Override public void onError(String message) { state.set("error"); }
            });
        assertEquals("error", state.get());
    }

    private static List<LLMClient.ChatMessage> assistant(String content) {
        List<LLMClient.ChatMessage> list = new ArrayList<>();
        LLMClient.ChatMessage message = new LLMClient.ChatMessage();
        message.role = "assistant";
        message.content = content;
        list.add(message);
        return list;
    }

    @Test
    public void collectSources_hashesContentAndIsStable() throws Exception {
        java.io.File root = new java.io.File(System.getProperty("java.io.tmpdir"),
            "sr-review-" + System.nanoTime());
        Workspace ws = new Workspace(root);
        java.io.File src = ws.getSrcDir();
        write(src, "A.java", "class A {}");
        write(src, "B.java", "class B {}");
        SecurityReview.CollectedSources first = SecurityReview.collectSources(ws);
        assertTrue(first.content.contains("=== A.java ==="));
        assertTrue(first.content.contains("=== B.java ==="));
        assertEquals(64, first.sha256.length());
        // Stable: same content, same hash.
        SecurityReview.CollectedSources second = SecurityReview.collectSources(ws);
        assertEquals(first.sha256, second.sha256);
        // Content change -> different hash.
        write(src, "A.java", "class A { int x; }");
        SecurityReview.CollectedSources third = SecurityReview.collectSources(ws);
        assertTrue(!first.sha256.equals(third.sha256));
        deleteRecursive(root);
    }

    @Test
    public void collectSources_overBudgetThrowsAndNeverTruncates() throws Exception {
        java.io.File root = new java.io.File(System.getProperty("java.io.tmpdir"),
            "sr-large-" + System.nanoTime());
        Workspace ws = new Workspace(root);
        java.io.File src = ws.getSrcDir();
        // Two files that together exceed the budget: the second one must
        // trigger a refusal, not a silent truncation of the rest.
        StringBuilder big = new StringBuilder(SecurityReview.MAX_SOURCE_CHARS);
        for (int i = 0; i < SecurityReview.MAX_SOURCE_CHARS; i++) {
            big.append('a');
        }
        write(src, "Big.java", big.toString());
        write(src, "Trailing.java", "class Trailing {}");
        try {
            SecurityReview.collectSources(ws);
            fail("Expected TooLargeException for over-budget sources");
        } catch (SecurityReview.TooLargeException expected) {
            // expected: refusal, never truncated approval
        } finally {
            deleteRecursive(root);
        }
    }

    private static void write(java.io.File dir, String name, String content) throws Exception {
        java.io.File f = new java.io.File(dir, name);
        f.getParentFile().mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    private static void deleteRecursive(java.io.File file) {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    @Test
    public void archiveSerializesSecurityStatus() {
        List<PluginArchive.Entry> entries = new ArrayList<>();
        PluginArchive.Entry pending = new PluginArchive.Entry(
            "plugin_a", "T", "p", 1L, "lineage_1", null, "pending");
        entries.add(pending);
        String json = PluginArchive.serializeEntries(entries);
        assertTrue(json.contains("pending"));
        List<PluginArchive.Entry> parsed = PluginArchive.parseEntries(json);
        assertEquals("pending", parsed.get(0).securityStatus);
        // Hash-bound approval survives the round trip.
        PluginArchive.Entry approved = new PluginArchive.Entry(
            "plugin_c", "T", "p", 2L, "lineage_1", null, "approved:abc123");
        entries.add(approved);
        List<PluginArchive.Entry> parsed2 = PluginArchive.parseEntries(
            PluginArchive.serializeEntries(entries));
        assertEquals("approved:abc123", parsed2.get(1).securityStatus);
        // Legacy entries (no field) deserialize to null = locally trusted.
        List<PluginArchive.Entry> legacy = PluginArchive.parseEntries(
            "[{\"id\":\"plugin_b\",\"title\":\"T\",\"prompt\":\"\",\"timestamp\":1,"
                + "\"lineage\":\"lineage_2\"}]");
        assertNull(legacy.get(0).securityStatus);
    }
}
