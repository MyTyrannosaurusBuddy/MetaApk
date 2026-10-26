package com.meta.app.core;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meta.app.config.LlmConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mandatory model security review for externally shared code. The workspace
 * sources of a share-imported app are sent to the configured model (primary
 * with backup fallback, the same request path the agent uses) and must come
 * back with a PASS verdict before the code may be compiled and run.
 *
 * Error philosophy: an unparseable verdict or a failed request NEVER defaults
 * to "allow" - the app simply does not run and the user sees the reason.
 */
public final class SecurityReview {

    private static final String TAG = "SecurityReview";
    /**
     * Total source text sent to the model in one review. A project over this
     * budget is REFUSED (never truncated-and-passed): approving a truncated
     * review would let unexamined files run. Share export enforces the same
     * bound, so every shareable project is fully reviewable.
     */
    public static final int MAX_SOURCE_CHARS = 384 * 1024;

    public interface Callback {
        /** @param sourceHash SHA-256 of the reviewed sources (path+content) */
        void onApproved(String sourceHash);
        void onBlocked(String reason);
        void onError(String message);
    }

    private SecurityReview() {}

    private static final String REVIEW_PROMPT =
        "You are a strict Android security auditor. The user sends Java sources that will be "
            + "COMPILED LOCALLY and dynamically loaded into a host app process. The host gives "
            + "plugins a real but limited capability model:\n"
            + "- plugins run IN-PROCESS (same app, no OS isolation between host and plugin);\n"
            + "- file access goes through a lineage-scoped context: one workspace directory and "
            + "one per-app data directory, no raw paths to other apps or the host's own storage;\n"
            + "- network access only through a host-provided fetch helper (public http/https, "
            + "bounded, redirected hops re-checked);\n"
            + "- a plugin can REQUEST Android permissions through the host's request flow, and "
            + "the host may grant or deny them;\n"
            + "- reflection into host internals is not part of the contract, but the plugin "
            + "shares the process - treat code that reaches outside its own classes as hostile.\n"
            + "\n"
            + "Audit for hidden or dangerous behavior:\n"
            + "1. Data exfiltration: reading workspace files, clipboard or user input and sending them to a network.\n"
            + "2. Path escapes: absolute paths, \"../\" traversal to reach files outside the workspace/data directories.\n"
            + "3. Resource abuse: endless loops without an exit condition, unbounded threads, huge allocations, battery drain.\n"
            + "4. Sandbox escape: Runtime.exec, reflection tricks, dynamic class loading, native code, process control.\n"
            + "5. Deceptive UI: phishing prompts, credential harvesting, hidden overlays, intercepting user input.\n"
            + "6. Interference with the host app or other installed apps beyond the plugin contract.\n"
            + "\n"
            + "Benign app logic (calculators, games, timers, file tools inside the workspace) must PASS; "
            + "do not block ordinary functionality.\n"
            + "\n"
            + "Output ONLY one JSON object and nothing else:\n"
            + "{\"verdict\": \"PASS\"}  OR  {\"verdict\": \"BLOCK\", \"reason\": \"one short sentence\"}";

    /** Collects the workspace sources and runs the review on a worker thread. */
    public static void review(Context context, Workspace workspace, Callback callback) {
        final String sources;
        final String sourceHash;
        try {
            CollectedSources collected = collectSources(workspace);
            sources = collected.content;
            sourceHash = collected.sha256;
        } catch (SecurityReview.TooLargeException e) {
            Log.w(TAG, e.getMessage());
            callback.onError(e.getMessage());
            return;
        } catch (Exception e) {
            Log.e(TAG, "Failed to collect sources for review", e);
            callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (sources.trim().isEmpty()) {
            callback.onError("No source files to review");
            return;
        }
        LlmConfig config = new LlmConfig(context);
        if (config.getApiKey().isEmpty() || config.getBaseUrl().isEmpty()) {
            callback.onError("No model configured; configure one in settings before running imported apps");
            return;
        }
        List<LLMClient.ChatMessage> messages = new ArrayList<>();
        messages.add(LLMClient.ChatMessage.system(REVIEW_PROMPT));
        messages.add(LLMClient.ChatMessage.user(
            "SOURCE CODE TO REVIEW:\n" + sources + "\n\nReply with your JSON verdict."));
        new LLMClient(context, config, 300, true).requestTools(
            messages, Collections.emptyList(), new LLMClient.ToolsCallback() {
                @Override
                public void onComplete(List<LLMClient.ChatMessage> assistantMessages) {
                    handleResponse(assistantMessages, sourceHash, callback);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Security review request failed", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
    }

    static void handleResponse(List<LLMClient.ChatMessage> assistantMessages,
                               String sourceHash, Callback callback) {
        StringBuilder text = new StringBuilder();
        if (assistantMessages != null) {
            for (LLMClient.ChatMessage message : assistantMessages) {
                if (message != null && message.content != null) {
                    text.append(message.content);
                }
            }
        }
        String raw = text.toString().trim();
        JsonObject verdict = extractJson(raw);
        if (verdict == null) {
            String snippet = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
            callback.onError("Unreadable review verdict (raw: " + snippet + ")");
            return;
        }
        String value = verdict.has("verdict") && !verdict.get("verdict").isJsonNull()
            ? verdict.get("verdict").getAsString().trim() : "";
        if ("PASS".equalsIgnoreCase(value)) {
            callback.onApproved(sourceHash);
        } else if ("BLOCK".equalsIgnoreCase(value)) {
            String reason = verdict.has("reason") && !verdict.get("reason").isJsonNull()
                ? verdict.get("reason").getAsString().trim() : "security policy";
            callback.onBlocked(reason);
        } else {
            String snippet = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
            callback.onError("Unknown review verdict '" + value + "' (raw: " + snippet + ")");
        }
    }

    /** First '{' to last '}' - tolerates markdown fences around the JSON. */
    static JsonObject extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Concatenates every .java file under src/ with path headers AND hashes
     * the same bytes ("path\n" + content, per file, in traversal order). The
     * hash is what the approved verdict binds to: the compile step re-runs
     * this exact collection and must see the identical hash, so the reviewed
     * text and the compiled code are provably the same version. Over-budget
     * projects throw TooLargeException - they are REFUSED, never truncated.
     */
    static CollectedSources collectSources(Workspace workspace) throws Exception {
        StringBuilder out = new StringBuilder();
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        File srcDir = workspace.getSrcDir();
        List<File> javaFiles = new ArrayList<>();
        collectJava(srcDir, javaFiles);
        for (File file : javaFiles) {
            String relative = srcDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            out.append("=== ").append(relative).append(" ===\n").append(content).append('\n');
            if (out.length() > MAX_SOURCE_CHARS) {
                throw new TooLargeException(
                    "Project sources exceed the security review budget (" + MAX_SOURCE_CHARS
                        + " chars); refusing to run - the whole project must be reviewable");
            }
            md.update((relative + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(content.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", b));
        }
        return new CollectedSources(out.toString(), hex.toString());
    }

    /** The reviewed text and its binding hash. */
    static final class CollectedSources {
        final String content;
        final String sha256;

        CollectedSources(String content, String sha256) {
            this.content = content;
            this.sha256 = sha256;
        }
    }

    /** Over-budget review input: the run is refused, never truncated. */
    static final class TooLargeException extends Exception {
        TooLargeException(String message) {
            super(message);
        }
    }

    private static void collectJava(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectJava(file, out);
            } else if (file.getName().endsWith(".java")) {
                out.add(file);
            }
        }
    }
}
