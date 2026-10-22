package com.meta.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meta.app.config.LlmConfig;
import com.meta.app.R;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Meta engine - orchestrates the full LLM -> security check -> compile -> DEX -> load pipeline.
 */
public class MetaEngine {

    private static final String TAG = "MetaEngine";
    private static final String ACTIVE_PLUGIN_PREFS = "meta_active_plugin";
    private static final String KEY_ACTIVE_PLUGIN_ID = "active_plugin_id";
    private static final String KEY_ACTIVE_PLUGIN_PROMPT = "active_plugin_prompt";
    private static final String KEY_ACTIVE_PLUGIN_TITLE = "active_plugin_title";
    private static final String KEY_ACTIVE_PLUGIN_FEEDBACK = "active_plugin_feedback";
    private static final String KEY_ACTIVE_PLUGIN_LINEAGE = "active_plugin_lineage";
    private static final String PENDING_TASK_PREFS = "meta_pending_task";
    private static final String KEY_PENDING_TASK = "task";
    private static final Pattern JAVA_CODE_PATTERN =
        Pattern.compile("<java_code>(.*?)</java_code>", Pattern.DOTALL);
    private static final Pattern JAVA_CODE_BLOCK_PATTERN =
        Pattern.compile("```[a-zA-Z]*\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);

    /**
     * Agent-mode generation: an opencode-style tool-calling loop over the
     * app's multi-file workspace (read/write/edit/list/grep/build/run).
     * Models without tool support naturally fall through: their plain-text
     * answer is extracted as a single DynamicPlugin.java, so both paths
     * converge on the same build-and-feedback loop.
     */
    public void processAgent(String userIntent, Callback callback) {
        if (destroyed.get()) {
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_engine_destroyed)));
            return;
        }
        cleanupStaleAttempt();
        attemptCounter.set(0);
        cancelled.set(false);
        transportRetryCounter.set(0);
        synchronized (pluginStateLock) {
            feedbackReplacementId = null;
        }
        clearFeedbackHistory();

        String lineage = newLineageId();
        agentLineage = lineage;
        Workspace workspace = Workspace.forLineage(context, lineage);

        List<LLMClient.ChatMessage> messages = new ArrayList<>();
        messages.add(LLMClient.ChatMessage.system(agentSystemPrompt()));
        messages.add(LLMClient.ChatMessage.user("Create the following app: " + userIntent));

        PendingTask task = new PendingTask();
        task.type = "GENERATE";
        task.originalIntent = userIntent;
        task.timestamp = System.currentTimeMillis();
        savePendingTask(task);

        executor.execute(() -> runAgentLoop(messages, workspace, userIntent, callback));
    }

    /** The system prompt addendum describing the agent workspace and tools. */
    private String agentSystemPrompt() {
        return llmClient.getSystemPrompt()
            + "\n\n"
            + "[AGENT MODE]\n"
            + "\n"
            + "Workflow:\n"
            + "1. Use list_files / read_file / grep to inspect the workspace.\n"
            + "2. Create or modify files with write_file / edit_file. Prefer small, "
            + "targeted edit_file changes over rewriting whole files.\n"
            + "3. Source files live under src/ (Java, package com.meta.plugin with "
            + "sub-packages allowed). The app entry point MUST be a class named "
            + "DynamicPlugin implementing PluginContract (onCreate(Context, ViewGroup, "
            + "ApiBridge) / onDestroy() / getTitle()).\n"
            + "4. Use build to compile; fix reported errors by editing files.\n"
            + "5. Use run_app to run the app and read the resulting UI dump "
            + "(and screenshot when available) to refine the layout.\n"
            + "6. When the app works, stop calling tools and give a one-line summary.\n"
            + "\n"
            + "Mandatory rules (unchanged): Java 8 only (no var/records/text blocks); "
            + "UI built purely in Java code (no XML/R.*); only standard android.* / "
            + "java.* APIs plus com.google.gson - androidx and other third-party "
            + "libraries are forbidden; system callbacks (PendingIntent/broadcast/"
            + "service) must use explicitly named component classes; forbidden: "
            + "Runtime.exec, ProcessBuilder, System.exit, com.android.internal, "
            + "android.os.SystemProperties. Suggested dark theme colors: "
            + "background #121212, surface #1E1E1E, text #E0E0E0, accents #6200EE, #03DAC5.\n";
    }

    private static List<LLMClient.ToolSpec> agentTools() {
        List<LLMClient.ToolSpec> tools = new ArrayList<>();
        tools.add(tool("list_files", "List all files in the project workspace.",
            "{}"));
        tools.add(tool("read_file", "Read the contents of a workspace file (paths relative to the workspace root).",
            props(requiredString("path"))));
        tools.add(tool("write_file", "Create or overwrite a workspace file with the given content.",
            props(requiredString("path"), requiredString("content"))));
        tools.add(tool("edit_file", "Replace old_string with new_string inside a file. old_string must match exactly once; include more context if it matches multiple times.",
            props(requiredString("path"), requiredString("old_string"), requiredString("new_string"))));
        tools.add(tool("grep", "Search workspace files for a substring; returns file:line matches.",
            props(requiredString("pattern"))));
        tools.add(tool("build", "Compile all workspace sources and report errors (or success).",
            "{}"));
        tools.add(tool("run_app", "Build, run the app and return what the live UI looks like (text dump and, when available, a screenshot).",
            "{}"));
        return tools;
    }

    private static LLMClient.ToolSpec tool(String name, String description, String properties) {
        com.google.gson.JsonObject params =
            com.google.gson.JsonParser.parseString(properties).getAsJsonObject();
        return new LLMClient.ToolSpec(name, description, params);
    }

    private static String requiredString(String name) {
        com.google.gson.JsonObject p = new com.google.gson.JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", name);
        return "\"" + name + "\":{\"type\":\"string\",\"description\":\"" + name + "\"}";
    }

    private static String props(String... required) {
        StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        sb.append(String.join(",", required));
        sb.append("},\"required\":[");
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String r : required) {
            names.add("\"" + r.substring(1, r.indexOf('"', 1)) + "\"");
        }
        sb.append(String.join(",", names));
        sb.append("]}");
        return sb.toString();
    }

    private void runAgentLoop(List<LLMClient.ChatMessage> messages, Workspace workspace,
                              String originalIntent, Callback callback) {
        while (!shouldAbort()) {
            try {
                if (runAgentTurn(messages, workspace, callback)) {
                    return;
                }
            } catch (Throwable t) {
                // Host-side agent bugs must not kill the process either: the
                // crash becomes one more fixable message for the model.
                Log.e(TAG, "Agent loop iteration crashed", t);
                messages.add(LLMClient.ChatMessage.user("An internal error occurred: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + ". Continue from the current workspace state."));
            }
        }
    }

    /** Runs one agent turn; returns true when the app was delivered. */
    private boolean runAgentTurn(List<LLMClient.ChatMessage> messages, Workspace workspace,
                                 Callback callback) throws Exception {
            // One tool-calling turn (blocks the executor thread).
            final Object[] result = new Object[2];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            llmClient.requestTools(messages, agentTools(), new LLMClient.ToolsCallback() {
                @Override
                public void onComplete(List<LLMClient.ChatMessage> assistantMessages) {
                    synchronized (result) {
                        result[0] = assistantMessages;
                    }
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    synchronized (result) {
                        result[1] = e;
                    }
                    latch.countDown();
                }

                @Override
                public void onModelSwitch(String from, String to) {
                    mainHandler.post(() -> {
                        if (shouldAbort()) return;
                        callback.onStatusUpdate(context.getString(R.string.status_model_switched, from, to));
                        callback.onLlmReset();
                    });
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                return false;
            }
            if (shouldAbort()) {
                return false;
            }
            synchronized (result) {
                if (result[1] != null) {
                    // LLM failure: keep the workspace and continue (unlimited retries).
                    messages.add(LLMClient.ChatMessage.user("LLM request failed: "
                        + ((Exception) result[1]).getMessage()
                        + ". Continue from the current workspace state."));
                    return false;
                }
                @SuppressWarnings("unchecked")
                List<LLMClient.ChatMessage> assistant = (List<LLMClient.ChatMessage>) result[0];
                messages.addAll(assistant);
                LLMClient.ChatMessage last = assistant.get(0);
                if (last.toolCalls != null && !last.toolCalls.isEmpty()) {
                    for (LLMClient.ToolCall call : last.toolCalls) {
                        if (shouldAbort()) {
                            return false;
                        }
                        mainHandler.post(() -> {
                            if (shouldAbort()) return;
                            callback.onStatusUpdate(context.getString(
                                R.string.status_llm_requesting) + " [tool: " + call.name + "]");
                        });
                        String toolResult = executeTool(call, workspace, callback);
                        LLMClient.ChatMessage toolMsg = new LLMClient.ChatMessage();
                        toolMsg.role = "tool";
                        toolMsg.toolCallId = call.id;
                        toolMsg.name = call.name;
                        toolMsg.content = toolResult;
                        messages.add(toolMsg);
                    }
                    return false;
                }
                // No tool calls: the model considers the app done -> build + load.
                // One-shot models fall through here too: their response carries
                // a <java_code> block which is written as the entry file first.
                String plainCode = extractJavaCode(last.content == null ? "" : last.content);
                if (plainCode != null) {
                    workspace.writeFile("src/DynamicPlugin.java", plainCode);
                }
                BuildOutcome outcome = buildWorkspace(workspace);
                if (outcome.plugin != null) {
                    registerPendingPlugin(outcome.plugin, outcome.pluginId);
                    String title = outcome.plugin.getTitle();
                    Log.i(TAG, "Agent session succeeded: " + title);
                    mainHandler.post(() -> {
                        if (shouldAbort()) {
                            discardPlugin(outcome.plugin);
                            return;
                        }
                        callback.onPlugin(outcome.plugin);
                    });
                    return true;
                }
                messages.add(LLMClient.ChatMessage.user("Build failed:\n" + outcome.error
                    + "\nFix the files in the workspace (use build to verify), then stop calling tools when done."));
                return false;
            }
    }

    /** Executes one agent tool; returns the text result handed back to the model. */
    private String executeTool(LLMClient.ToolCall call, Workspace workspace, Callback callback) {
        com.google.gson.JsonObject args;
        try {
            args = com.google.gson.JsonParser.parseString(
                call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
        } catch (Exception e) {
            return "Error: invalid arguments JSON: " + e.getMessage();
        }
        switch (call.name) {
            case "list_files":
                return workspace.listFiles();
            case "read_file":
                return workspace.readFile(argString(args, "path"));
            case "write_file": {
                String content = argString(args, "content");
                CodeSanitizer.SanitizeResult check = new CodeSanitizer().check(content);
                if (!check.passed) {
                    return "Rejected: " + check.reason
                        + ". Remove the forbidden operation and retry.";
                }
                return workspace.writeFile(argString(args, "path"), content);
            }
            case "edit_file": {
                String result = workspace.editFile(argString(args, "path"),
                    argString(args, "old_string"), argString(args, "new_string"));
                return result;
            }
            case "grep":
                return workspace.grep(argString(args, "pattern"));
            case "build": {
                BuildOutcome outcome = buildWorkspace(workspace);
                return outcome.plugin != null
                    ? "Build OK (entry class loaded successfully)"
                    : outcome.error;
            }
            case "run_app": {
                BuildOutcome outcome = buildWorkspace(workspace);
                if (outcome.plugin == null) {
                    return outcome.error;
                }
                return previewPlugin(outcome, callback);
            }
            default:
                return "Error: unknown tool " + call.name;
        }
    }

    private String argString(com.google.gson.JsonObject args, String name) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            return "";
        }
        return args.get(name).getAsString();
    }

    /** Build+load of the current workspace; the plugin is NOT attached as active. */
    private BuildOutcome buildWorkspace(Workspace workspace) {
        String pluginId = "plugin_" + UUID.randomUUID().toString().substring(0, 8);
        setCurrentAttemptPluginId(pluginId);
        CodeCompiler.CompileResult compileResult = compiler.compileWorkspace(pluginId, workspace.getSrcDir());
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return BuildOutcome.aborted();
        }
        if (!compileResult.success) {
            cleanupAttempt(pluginId);
            return BuildOutcome.failed(compileResult.errorMessage);
        }
        DexTransformer.TransformResult transformResult =
            transformer.transform(pluginId, compileResult.classesDir);
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return BuildOutcome.aborted();
        }
        if (!transformResult.success) {
            cleanupAttempt(pluginId);
            return BuildOutcome.failed("DEX transformation failed: " + transformResult.errorMessage);
        }
        PluginLoader.LoadResult loadResult = loader.loadPlugin(pluginId, transformResult.dexFile);
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return BuildOutcome.aborted();
        }
        if (!loadResult.success) {
            cleanupAttempt(pluginId);
            return BuildOutcome.failed("Plugin load failed: " + loadResult.errorMessage);
        }
        return BuildOutcome.success(pluginId, loadResult.plugin);
    }

    /** Renders the built plugin in the host and reports the UI inspection result. */
    private String previewPlugin(BuildOutcome outcome, Callback callback) {
        final Object[] result = new Object[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            if (shouldAbort()) {
                result[0] = "Aborted";
                latch.countDown();
                return;
            }
            callback.onPreviewPlugin(outcome.plugin, (uiDump, screenshot) -> {
                StringBuilder sb = new StringBuilder();
                if (uiDump != null && !uiDump.trim().isEmpty()) {
                    sb.append("UI dump:\n").append(uiDump).append('\n');
                }
                if (screenshot != null) {
                    sb.append("[screenshot included]");
                }
                synchronized (result) {
                    result[0] = sb.toString();
                }
                latch.countDown();
            });
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            return "Aborted";
        }
        String r;
        synchronized (result) {
            r = (String) result[0];
        }
        // The preview instance is throwaway; free its loader before continuing.
        loader.unloadPlugin(outcome.pluginId);
        return r == null ? "(no UI inspection available)" : r;
    }

    private static final class BuildOutcome {
        final String pluginId;
        final PluginContract plugin;
        final String error;

        private BuildOutcome(String pluginId, PluginContract plugin, String error) {
            this.pluginId = pluginId;
            this.plugin = plugin;
            this.error = error;
        }

        static BuildOutcome success(String pluginId, PluginContract plugin) {
            return new BuildOutcome(pluginId, plugin, null);
        }

        static BuildOutcome failed(String error) {
            return new BuildOutcome(null, null, error);
        }

        static BuildOutcome aborted() {
            return new BuildOutcome(null, null, "Aborted");
        }
    }

    /**
     * A generation/feedback/runtime-error task that survives process death,
     * so an interrupted task is resumed automatically on the next launch.
     */
    public static class PendingTask {
        /** Task kind: GENERATE, FEEDBACK or RETRY (runtime error). */
        public String type;
        /** The original user intent that started this app lineage. */
        public String originalIntent;
        /** The error/feedback message of the current round (null for GENERATE). */
        public String errorMessage;
        /** The version being replaced in place (FEEDBACK/RETRY only). */
        public String replacingId;
        public long timestamp;
    }

    private final Context context;
    private final LLMClient llmClient;
    private final CodeSanitizer sanitizer;
    private final CodeCompiler compiler;
    private final DexTransformer transformer;
    private final PluginLoader loader;
    private final PluginArchive archive;
    private final GenerationCache generationCache;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final SharedPreferences pluginPrefs;
    private final SharedPreferences pendingTaskPrefs;
    /** Number of retries so far for the current generation flow (shared by compile and runtime failures). */
    private final java.util.concurrent.atomic.AtomicInteger attemptCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** Whether the user cancelled this generation. */
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Whether the engine has been destroyed by the host. */
    private final java.util.concurrent.atomic.AtomicBoolean destroyed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Object pluginStateLock = new Object();
    private final Map<PluginContract, String> pendingPluginIds = new IdentityHashMap<>();
    /** Full info of the current foreground app (in-memory source of truth, kept in sync on switch/generate/restore). */
    private String activePluginId;
    private String activePluginTitle;
    private String activePluginPrompt;
    /** Stable lineage ID of the current app: shared by feedback revisions so their data persists across rewrites. */
    private String activePluginLineage;
    private String currentAttemptPluginId;
    /** Feedback history of the current app lineage (accumulates across rewrites; cleared when switching apps or generating a new one). */
    private final List<String> feedbackHistory = new ArrayList<>();
    /** ID of the version being replaced by a feedback revision: the new version upgrades in place without a duplicate archive entry. */
    private String feedbackReplacementId;
    /** Lineage assigned to an agent-mode generation (consumed when the final app attaches). */
    private String agentLineage;
    /** Consecutive transport-level (network/model) failures; capped to stop infinite loops on bad keys/endpoints. */
    private final java.util.concurrent.atomic.AtomicInteger transportRetryCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** Maximum consecutive transport failures before giving up and surfacing the error. */
    private static final int MAX_TRANSPORT_RETRIES = 3;

    public interface Callback {
        void onStatusUpdate(String status);
        void onPlugin(PluginContract plugin);
        void onError(String message);
        void onLlmToken(String token);

        /** Called when switching from the primary to the backup model; used to discard the primary model's partial output. */
        default void onLlmReset() { }

        /**
         * Agent mode: preview the given plugin in the host UI and report what
         * the UI looks like (text dump + optional screenshot) via ready.
         */
        default void onPreviewPlugin(PluginContract plugin, PreviewReady ready) {
            ready.done(null, null);
        }
    }

    public interface RestoreCallback {
        void onStatusUpdate(String status);
        void onPlugin(PluginContract plugin);
        void onError(String message);
    }

    /** Host-side UI inspector for the agent's run_app / preview turns. */
    public interface UiInspector {
        /** The current plugin UI as compact text (view classes + texts). */
        String dumpText();
        /** JPEG screenshot of the plugin area as base64, or null when unavailable. */
        String screenshotBase64();
    }

    /** Delivers a preview plugin to the host and receives the inspection result. */
    public interface PreviewReady {
        void done(String uiDump, String screenshotBase64);
    }

    public MetaEngine(Context context) {
        this.context = context;
        LlmConfig config = new LlmConfig(context);
        this.llmClient = new LLMClient(context, config);
        this.sanitizer = new CodeSanitizer();
        this.compiler = new CodeCompiler(context);
        this.transformer = new DexTransformer(context);
        this.loader = new PluginLoader(context);
        this.archive = new PluginArchive(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.pluginPrefs = context.getSharedPreferences(ACTIVE_PLUGIN_PREFS, Context.MODE_PRIVATE);
        this.pendingTaskPrefs = context.getSharedPreferences(PENDING_TASK_PREFS, Context.MODE_PRIVATE);
        this.generationCache = new GenerationCache(context);
        loadFeedbackHistory();
        sweepOrphanedPluginDirs();
    }

    /**
     * Removes plugin directories left behind by a killed process: attempt ids
     * are only kept in memory, so a generation interrupted mid-compile can
     * strand its files/plugins/&lt;id&gt;/ directory forever.
     *
     * Keeps the active plugin, the app being replaced by a pending task (its
     * source is needed for the resume) and every archive entry.
     */
    private void sweepOrphanedPluginDirs() {
        File pluginsDir = new File(context.getFilesDir(), "plugins");
        File[] dirs = pluginsDir.listFiles();
        if (dirs == null || dirs.length == 0) {
            return;
        }
        Set<String> keep = new HashSet<>();
        String activeId = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_ID, "");
        if (!activeId.isEmpty()) {
            keep.add(activeId);
        }
        PendingTask pending = loadPendingTask();
        if (pending != null && pending.replacingId != null && !pending.replacingId.isEmpty()) {
            keep.add(pending.replacingId);
        }
        for (PluginArchive.Entry entry : archive.list()) {
            keep.add(entry.id);
        }
        for (File dir : dirs) {
            if (dir.isDirectory() && !keep.contains(dir.getName())) {
                compiler.cleanPlugin(dir.getName());
                Log.i(TAG, "Swept orphaned plugin dir: " + dir.getName());
            }
        }
    }

    /**
     * Processes a user intent through the full pipeline:
     * LLM -> extract code -> security check -> ECJ compile -> D8 to DEX -> load -> return plugin
     */
    public void process(String userIntent, Callback callback) {
        if (destroyed.get()) {
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_engine_destroyed)));
            return;
        }
        cleanupStaleAttempt();
        attemptCounter.set(0);
        cancelled.set(false);

        // Same-prompt cache: a redundant generation becomes an instant switch
        // back to the previously generated app (no LLM round trip, no pending
        // task - the normal switch flow handles archive/lineage bookkeeping).
        String cachedId = generationCache.lookup(userIntent);
        if (cachedId != null && !cachedId.isEmpty()) {
            String activeId;
            synchronized (pluginStateLock) {
                activeId = activePluginId;
            }
            if (!cachedId.equals(activeId)
                    && getPluginDexFile(cachedId).exists()
                    && isArchived(cachedId)) {
                Log.i(TAG, "Generation cache hit, switching to cached plugin: " + cachedId);
                switchToArchivedPlugin(cachedId, new RestoreCallback() {
                    @Override
                    public void onStatusUpdate(String status) {
                        callback.onStatusUpdate(status);
                    }

                    @Override
                    public void onPlugin(PluginContract plugin) {
                        callback.onPlugin(plugin);
                    }

                    @Override
                    public void onError(String message) {
                        callback.onError(message);
                    }
                });
                return;
            }
        }

        // Agent-mode generation (tool-calling loop over the workspace; one-shot
        // models degrade naturally inside the loop).
        processAgent(userIntent, callback);
    }

    /**
     * Cancels the current generation flow: interrupts the streaming LLM request and lets subsequent compile/load steps bail out.
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "User cancelled generation");
        llmClient.cancel();
    }

    /**
     * Called when the host is destroyed: stops all background tasks and cleans up compile/load artifacts.
     */
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        cancel();
        String activePluginIdSnapshot;
        synchronized (pluginStateLock) {
            activePluginIdSnapshot = activePluginId;
        }
        Set<String> pluginIds = snapshotPluginIdsForCleanup();
        pluginIds.remove(activePluginIdSnapshot);
        for (String pluginId : pluginIds) {
            cleanupPlugin(pluginId);
        }
        loader.unloadAll();
        executor.shutdownNow();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * The plugin has been injected into the host UI successfully. The previous active app is not deleted;
     * instead it is archived together with its own title and prompt (taken from the in-memory source of truth, never polluted by other apps).
     *
     * @param userIntent the original prompt that generated this plugin (fed back to the model for a rewrite on runtime errors);
     *                   pass null to keep the target app's own prompt (restore/switch scenarios)
     */
    public void markPluginActive(PluginContract plugin, String userIntent) {
        String pluginId;
        String prevId;
        String prevTitle;
        String prevPrompt;
        String prevLineage;
        String newTitle;
        String newPrompt;
        String newLineage;
        boolean replaceInPlace;
        String replacedId;
        synchronized (pluginStateLock) {
            pluginId = pendingPluginIds.remove(plugin);
            if (pluginId == null) {
                return;
            }
            prevId = activePluginId;
            prevTitle = activePluginTitle;
            prevPrompt = activePluginPrompt;
            prevLineage = activePluginLineage;
            // Feedback revision: the replaced version upgrades in place, no duplicate archive entry
            replaceInPlace = (prevId != null && feedbackReplacementId != null
                && prevId.equals(feedbackReplacementId));
            replacedId = replaceInPlace ? prevId : null;
            if (replaceInPlace) {
                feedbackReplacementId = null;
            }
            activePluginId = pluginId;
            newTitle = (plugin.getTitle() != null) ? plugin.getTitle() : "";
            activePluginTitle = newTitle;
            if (userIntent != null && !userIntent.trim().isEmpty()) {
                newPrompt = userIntent;
            } else if (activePluginPrompt != null && !activePluginPrompt.isEmpty()) {
                // Switch scenario: switchToArchivedPlugin already wrote the target app's own prompt into memory
                newPrompt = activePluginPrompt;
            } else {
                // Cold-start restore scenario: keep the previously persisted prompt
                newPrompt = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_PROMPT, "");
            }
            activePluginPrompt = newPrompt;

            // Lineage: feedback/runtime-error revisions keep the app's lineage (shared data dir);
            // brand-new generations start a fresh lineage; a cold-start restore reloads the persisted one.
            if (replaceInPlace) {
                newLineage = (prevLineage != null && !prevLineage.isEmpty())
                    ? prevLineage : newLineageId();
            } else if (prevId != null) {
                // A real replacement (new generation or switch): switch already set the lineage in memory
                if (userIntent != null && agentLineage != null) {
                    // Agent-mode generation: the workspace lineage was assigned at session start
                    newLineage = agentLineage;
                } else {
                    newLineage = (activePluginLineage != null && !activePluginLineage.isEmpty())
                        ? activePluginLineage : newLineageId();
                }
            } else {
                // Cold-start restore
                String persisted = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_LINEAGE, "");
                newLineage = (!persisted.isEmpty()) ? persisted : newLineageId();
            }
            activePluginLineage = newLineage;
        }
        if (replaceInPlace) {
            // Feedback revision: clean up the superseded version's disk files and
            // replace its archive entry in place (the app stays listed).
            cleanupPlugin(replacedId);
            archive.remove(replacedId);
            Log.i(TAG, "Feedback revision replaced previous version id=" + replacedId
                + " (archive entry updated in place)");
        } else if (prevId != null && !prevId.equals(pluginId)) {
            archive.addFront(prevId, prevTitle, prevPrompt, System.currentTimeMillis(), prevLineage);
            Log.i(TAG, "Archived previous plugin id=" + prevId
                + " title=" + prevTitle + " prompt=" + prevPrompt);
        }
        // Library model: every app - including the active one - stays in the
        // archive list, so any app can be switched to or deleted at any time.
        archive.addFront(pluginId, activePluginTitle, activePluginPrompt,
            System.currentTimeMillis(), activePluginLineage);
        // Same-prompt cache: only brand-new generations are recorded
        // (feedback revisions and archive switches are not new prompts).
        if (!replaceInPlace && userIntent != null && !userIntent.trim().isEmpty()) {
            generationCache.record(userIntent, pluginId);
        }
        saveActivePluginFull(pluginId, activePluginPrompt, activePluginTitle, activePluginLineage);
    }

    private boolean isArchived(String pluginId) {
        for (PluginArchive.Entry entry : archive.list()) {
            if (entry.id.equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    private static String newLineageId() {
        return "lineage_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Called when injection fails in a restore/switch scenario: only unregisters the pending plugin and keeps its files,
     * so the switch can be retried or the app restored on a cold start.
     */
    public void abortPendingPlugin(PluginContract plugin) {
        synchronized (pluginStateLock) {
            pendingPluginIds.remove(plugin);
        }
    }

    /**
     * Called when a generated plugin fails to inject, to avoid leftover dex / class / ClassLoader artifacts.
     */
    public void discardPlugin(PluginContract plugin) {
        String pluginId;
        synchronized (pluginStateLock) {
            pluginId = pendingPluginIds.remove(plugin);
        }
        cleanupPlugin(pluginId);
    }

    public boolean hasRestorablePlugin() {
        String pluginId = getSavedActivePluginId();
        if (pluginId == null || pluginId.trim().isEmpty()) {
            Log.i(TAG, "No restorable plugin: saved id is empty");
            return false;
        }

        File dexFile = getPluginDexFile(pluginId);
        if (dexFile.exists()) {
            Log.i(TAG, "Restorable plugin found: " + pluginId + " dex=" + dexFile.getAbsolutePath());
            return true;
        }

        Log.w(TAG, "Restorable plugin dex missing: " + pluginId
            + " path=" + dexFile.getAbsolutePath() + ", clear saved id");
        clearSavedActivePluginId();
        return false;
    }

    public void restoreActivePlugin(RestoreCallback callback) {
        if (destroyed.get()) {
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_engine_destroyed)));
            return;
        }

        String pluginId = getSavedActivePluginId();
        if (pluginId == null || pluginId.trim().isEmpty()) {
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_nothing_to_restore)));
            return;
        }

        File dexFile = getPluginDexFile(pluginId);
        if (!dexFile.exists()) {
            clearSavedActivePluginId();
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_active_app_files_missing)));
            return;
        }

        try {
            executor.execute(() -> restoreActivePluginInternal(pluginId, dexFile, callback));
        } catch (RejectedExecutionException e) {
            Log.i(TAG, "Restore task already finished, ignoring this callback", e);
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_restore_task_finished)));
        }
    }

    private void restoreActivePluginInternal(String pluginId, File dexFile,
                                             RestoreCallback callback) {
        if (destroyed.get()) {
            return;
        }

        mainHandler.post(() -> {
            if (!destroyed.get()) {
                callback.onStatusUpdate(context.getString(R.string.status_restoring_app));
            }
        });

        PluginLoader.LoadResult loadResult = loader.loadPlugin(pluginId, dexFile);
        if (destroyed.get()) {
            if (loadResult.success) {
                loader.unloadPlugin(pluginId);
            }
            return;
        }

        if (!loadResult.success) {
            clearSavedActivePluginId();
            cleanupBrokenRestorablePlugin(pluginId);
            mainHandler.post(() -> {
                if (!destroyed.get()) {
                    callback.onError(context.getString(R.string.err_restore_failed, loadResult.errorMessage));
                }
            });
            return;
        }

        registerPendingPlugin(loadResult.plugin, pluginId);
        Log.i(TAG, "Restored plugin successfully: " + pluginId
            + " title: " + loadResult.plugin.getTitle());
        mainHandler.post(() -> {
            if (destroyed.get()) {
                discardPlugin(loadResult.plugin);
                return;
            }
            callback.onPlugin(loadResult.plugin);
        });
    }

    /**
     * @param userPrompt     the prompt sent to the LLM this time
     * @param originalIntent the original user intent (kept unchanged across retries)
     */
    private void requestLLM(String userPrompt, String originalIntent, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        mainHandler.post(() -> {
            if (shouldAbort()) return;
            callback.onStatusUpdate(
                    attemptCounter.get() == 0
                            ? context.getString(R.string.status_llm_requesting)
                            : context.getString(R.string.status_llm_retrying));
        });

        llmClient.requestStream(userPrompt, new LLMClient.StreamCallback() {
            @Override
            public void onToken(String token) {
                if (shouldAbort()) return;
                mainHandler.post(() -> {
                    if (shouldAbort()) return;
                    callback.onLlmToken(token);
                });
            }

            @Override
            public void onComplete(String fullResponse) {
                if (shouldAbort()) return;
                // A successful response resets the transport failure budget
                transportRetryCounter.set(0);
                try {
                    // Run the compile pipeline asynchronously
                    executor.execute(() -> compileAndLoad(fullResponse, originalIntent, callback));
                } catch (RejectedExecutionException e) {
                    Log.i(TAG, "Compile task already finished, ignoring this callback", e);
                }
            }

            @Override
            public void onError(Exception e) {
                if (shouldAbort()) return;
                // Transport-level failure (both primary and backup failed, or
                // a non-retryable HTTP error). Retry the SAME prompt with a
                // capped budget and backoff -- NOT the "fix the code" prompt,
                // which confuses the model into rewriting a working app.
                int retries = transportRetryCounter.incrementAndGet();
                if (retries > MAX_TRANSPORT_RETRIES) {
                    Log.e(TAG, "Giving up after " + retries + " transport failures: " + e.getMessage());
                    mainHandler.post(() -> {
                        if (!shouldAbort()) {
                            callback.onError(context.getString(
                                R.string.err_llm_unreachable, e.getMessage()));
                        }
                    });
                    return;
                }
                long backoffMs = Math.min(2500L * retries, 10000L);
                Log.w(TAG, "Transport failure " + retries + "/" + MAX_TRANSPORT_RETRIES
                    + ", retrying in " + backoffMs + "ms: " + e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (shouldAbort()) return;
                requestLLM(userPrompt, originalIntent, callback);
            }

            @Override
            public void onModelSwitch(String from, String to) {
                if (shouldAbort()) return;
                mainHandler.post(() -> {
                    if (shouldAbort()) return;
                    callback.onStatusUpdate(context.getString(R.string.status_model_switched, from, to));
                    callback.onLlmReset();
                });
            }
        });
    }

    /**
     * User feedback revision: records the feedback into the history (accumulating across rewrites) and feeds it back to the model for regeneration.
     */
    public void feedback(String originalIntent, String feedback, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        String replacingId;
        synchronized (pluginStateLock) {
            // Remember the version being fixed: the new version replaces it in place, no duplicate archive entry
            feedbackReplacementId = activePluginId;
            replacingId = activePluginId;
        }
        transportRetryCounter.set(0);
        if (feedback != null && !feedback.trim().isEmpty()) {
            synchronized (pluginStateLock) {
                feedbackHistory.add(feedback.trim());
            }
            saveFeedbackHistory();
        }
        // Persist the task so an interrupted revision is resumed on the next launch
        PendingTask task = new PendingTask();
        task.type = "FEEDBACK";
        task.originalIntent = originalIntent;
        task.errorMessage = "User feedback: " + feedback;
        task.replacingId = replacingId;
        task.timestamp = System.currentTimeMillis();
        savePendingTask(task);
        retry(originalIntent, "User feedback: " + feedback, callback);
    }

    /**
     * Runtime-error self-heal: marks the crashed version for in-place replacement,
     * persists the task for cross-process resumption, and feeds the error back to the model.
     */
    public void retryRuntimeError(String originalIntent, String errorMessage, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        String replacingId;
        synchronized (pluginStateLock) {
            feedbackReplacementId = activePluginId;
            replacingId = activePluginId;
        }
        transportRetryCounter.set(0);
        PendingTask task = new PendingTask();
        task.type = "RETRY";
        task.originalIntent = originalIntent;
        task.errorMessage = errorMessage;
        task.replacingId = replacingId;
        task.timestamp = System.currentTimeMillis();
        savePendingTask(task);
        retry(originalIntent, errorMessage, callback);
    }

    /**
     * Feeds an error back to the LLM for regeneration (single entry point for compile failures, injection failures and runtime errors),
     * with no attempt limit, until success or user cancellation.
     */
    public void retry(String originalIntent, String errorMessage, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        handleFailure(errorMessage, originalIntent, null, callback);
    }

    /**
     * Builds the retry prompt: feeds the error/feedback back to the LLM together with the feedback history and the previous complete code
     * (each LLM request is one-shot with no conversation history, so context must be passed explicitly).
     */
    static String buildRetryPrompt(String originalIntent, String errorMessage,
                                   String previousSource, String failedSource,
                                   List<String> feedbackHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The previously generated code has issues. Details:\n")
            .append(errorMessage).append("\n\n");
        if (failedSource != null && !failedSource.trim().isEmpty()) {
            // The code that just failed is the primary fix target.
            sb.append("This is the code that just failed; fix the errors in it and output the complete corrected code:\n```java\n")
                .append(failedSource)
                .append("\n```\n\n");
        } else if (previousSource != null && !previousSource.trim().isEmpty()) {
            sb.append("This is the previous code; make minimal fixes based on it:\n```java\n")
                .append(previousSource)
                .append("\n```\n\n");
        }
        if (feedbackHistory != null && !feedbackHistory.isEmpty()) {
            // The current error message already contains the latest feedback; the history block only lists earlier ones to avoid wasting context
            List<String> listed = new ArrayList<>(feedbackHistory);
            if (errorMessage != null && errorMessage.contains("User feedback: ")
                    && !feedbackHistory.isEmpty()) {
                String latest = feedbackHistory.get(feedbackHistory.size() - 1);
                if (errorMessage.endsWith(latest)) {
                    listed.remove(listed.size() - 1);
                }
            }
            if (!listed.isEmpty()) {
                sb.append("The user previously reported the following issues (in order; all of them must be satisfied):\n");
                for (int i = 0; i < listed.size(); i++) {
                    sb.append(i + 1).append(". ").append(listed.get(i)).append("\n");
                }
                sb.append("\n");
            }
        }
        sb.append("Fix the code and regenerate the complete code.")
            .append("Note: output only the corrected <java_code> block, no explanations.")
            .append("If the code needs to be invoked by the system at a future moment (e.g. PendingIntent / broadcast / service / Activity navigation),")
            .append("it must use explicitly named component classes rather than relying on process-lifetime mechanisms such as registerReceiver(...).\n")
            .append("Original intent: ").append(originalIntent);
        return sb.toString();
    }

    /**
     * Reads the complete source of the current foreground app, used to let the model make minimal fixes on retry/feedback.
     */
    private String readActivePluginSource() {
        String id;
        synchronized (pluginStateLock) {
            id = activePluginId;
        }
        return readPluginSource(id);
    }

    /**
     * Reads the source of the plugin with the given id, eliding the middle
     * when oversized. Used both for the active app and for the code that
     * just failed an attempt.
     */
    private String readPluginSource(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            return null;
        }
        File sourceFile = new File(context.getFilesDir(),
            "plugins/" + pluginId + "/source/DynamicPlugin.java");
        if (!sourceFile.exists()) {
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(sourceFile.toPath());
            return truncateSourceMiddle(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Failed to read plugin source " + pluginId, e);
            return null;
        }
    }

    /**
     * Keeps an oversized source usable in the feedback prompt by eliding the
     * middle: the model still sees the class header and the first views, and
     * the tail where later methods usually live.
     */
    static String truncateSourceMiddle(String source) {
        if (source.length() <= 30 * 1024) {
            return source;
        }
        int headChars = 20 * 1024;
        int tailChars = 10 * 1024;
        int elided = source.length() - headChars - tailChars;
        return source.substring(0, headChars)
            + "\n... [middle elided: " + elided + " chars] ...\n"
            + source.substring(source.length() - tailChars);
    }

    /**
     * Compile-and-load pipeline (runs on a worker thread).
     */
    private void compileAndLoad(String llmResponse, String originalIntent, Callback callback) {
        // 1. Extract the Java code
        String javaCode = extractJavaCode(llmResponse);
        if (javaCode == null) {
            // A missing code block is also a fixable error: feed it back to the model for a retry
            handleFailure("No Java code found in the LLM response; put the complete code inside "
                + "a <java_code>...</java_code> block and output it again", originalIntent, null, callback);
            return;
        }

        Log.i(TAG, "Extracted Java code:\n" + javaCode);
        if (shouldAbort()) return;
        mainHandler.post(() -> {
            if (shouldAbort()) return;
            callback.onStatusUpdate(context.getString(R.string.status_sanitizing));
        });

        // 2. Security check
        CodeSanitizer.SanitizeResult sanitizeResult = sanitizer.check(javaCode);
        if (!sanitizeResult.passed) {
            // A security-check rejection is also a fixable error: feed the reason back to the model
            Log.w(TAG, "Sanitizer rejected code: " + sanitizeResult.reason);
            handleFailure("Security check failed: " + sanitizeResult.reason,
                originalIntent, javaCode, callback);
            return;
        }

        String pluginId = "plugin_" + UUID.randomUUID().toString().substring(0, 8);
        setCurrentAttemptPluginId(pluginId);
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return;
        }
        mainHandler.post(() -> {
            if (shouldAbort()) return;
            callback.onStatusUpdate(context.getString(R.string.status_compiling));
        });

        // 3. ECJ compilation
        CodeCompiler.CompileResult compileResult = compiler.compile(pluginId, javaCode);
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return;
        }
        if (!compileResult.success) {
            String failedSource = readPluginSource(pluginId);
            cleanupAttempt(pluginId);
            handleFailure(compileResult.errorMessage, originalIntent, failedSource, callback);
            return;
        }

        mainHandler.post(() -> {
            if (shouldAbort()) return;
            callback.onStatusUpdate(context.getString(R.string.status_dexing));
        });

        // 4. D8 transformation
        DexTransformer.TransformResult transformResult =
            transformer.transform(pluginId, compileResult.classesDir);
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return;
        }
        if (!transformResult.success) {
            String failedSource = readPluginSource(pluginId);
            cleanupAttempt(pluginId);
            handleFailure("DEX transformation failed: " + transformResult.errorMessage,
                originalIntent, failedSource, callback);
            return;
        }

        mainHandler.post(() -> {
            if (shouldAbort()) return;
            callback.onStatusUpdate(context.getString(R.string.status_loading_plugin));
        });

        // 5. DexClassLoader load
        PluginLoader.LoadResult loadResult = loader.loadPlugin(pluginId, transformResult.dexFile);
        if (shouldAbort()) {
            cleanupAttempt(pluginId);
            return;
        }
        if (!loadResult.success) {
            String failedSource = readPluginSource(pluginId);
            cleanupAttempt(pluginId);
            handleFailure("Plugin load failed: " + loadResult.errorMessage,
                originalIntent, failedSource, callback);
            return;
        }

        registerPendingPlugin(loadResult.plugin, pluginId);
        Log.i(TAG, "Plugin loaded successfully: " + loadResult.plugin.getTitle());
        mainHandler.post(() -> {
            if (shouldAbort()) {
                discardPlugin(loadResult.plugin);
                return;
            }
            callback.onPlugin(loadResult.plugin);
        });
    }

    /**
     * Unified failure handling: feeds the error back to the LLM for a fix.
     * No attempt limit, until success or user cancellation (stop button / engine destruction).
     *
     * @param failedSource the code that just failed (compile/dex/load/sanitizer
     *                     rejections); null for failures before any code
     *                     existed, where the previous app's source is used as
     *                     the revision base instead
     */
    private void handleFailure(String errorMessage, String originalIntent,
                               String failedSource, Callback callback) {
        if (shouldAbort()) return;
        int attempt = attemptCounter.incrementAndGet();
        Log.w(TAG, "Attempt " + attempt + " failed, feeding back to the LLM for a retry: " + errorMessage);
        mainHandler.post(() -> {
            if (shouldAbort()) return;
            callback.onStatusUpdate(
                    context.getString(R.string.status_retry_attempt, attempt));
        });
        String previousSource = readActivePluginSource();
        List<String> history;
        synchronized (pluginStateLock) {
            history = new ArrayList<>(feedbackHistory);
        }
        requestLLM(buildRetryPrompt(originalIntent, errorMessage, previousSource,
                failedSource, history),
            originalIntent, callback);
    }

    private boolean shouldAbort() {
        return cancelled.get() || destroyed.get();
    }

    private void setCurrentAttemptPluginId(String pluginId) {
        synchronized (pluginStateLock) {
            currentAttemptPluginId = pluginId;
        }
    }

    private void registerPendingPlugin(PluginContract plugin, String pluginId) {
        synchronized (pluginStateLock) {
            if (pluginId != null && pluginId.equals(currentAttemptPluginId)) {
                currentAttemptPluginId = null;
            }
            pendingPluginIds.put(plugin, pluginId);
        }
    }

    private void cleanupStaleAttempt() {
        String stalePluginId;
        synchronized (pluginStateLock) {
            stalePluginId = currentAttemptPluginId;
            currentAttemptPluginId = null;
        }
        cleanupPlugin(stalePluginId);
    }

    private void cleanupAttempt(String pluginId) {
        synchronized (pluginStateLock) {
            if (pluginId != null && pluginId.equals(currentAttemptPluginId)) {
                currentAttemptPluginId = null;
            }
        }
        cleanupPlugin(pluginId);
    }

    private void cleanupPlugin(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            return;
        }
        loader.unloadPlugin(pluginId);
        compiler.cleanPlugin(pluginId);
    }

    private String getSavedActivePluginId() {
        return pluginPrefs.getString(KEY_ACTIVE_PLUGIN_ID, "");
    }

    /**
     * The original prompt of the current active plugin; used to feed the model on runtime errors in a restored session.
     */
    public String getSavedActivePluginPrompt() {
        return pluginPrefs.getString(KEY_ACTIVE_PLUGIN_PROMPT, "");
    }

    /**
     * The current foreground app's own original prompt (in-memory source of truth, stays correct after switches/generation).
     */
    public String getActivePluginPrompt() {
        synchronized (pluginStateLock) {
            if (activePluginPrompt != null && !activePluginPrompt.trim().isEmpty()) {
                return activePluginPrompt;
            }
            if (activePluginId != null) {
                return pluginPrefs.getString(KEY_ACTIVE_PLUGIN_PROMPT, "");
            }
            return "";
        }
    }

    /** Writes the full active info (id / prompt / title / lineage) directly. */
    private void saveActivePluginFull(String pluginId, String userIntent, String title,
                                      String lineage) {
        boolean success = pluginPrefs.edit()
            .putString(KEY_ACTIVE_PLUGIN_ID, pluginId)
            .putString(KEY_ACTIVE_PLUGIN_PROMPT, userIntent == null ? "" : userIntent)
            .putString(KEY_ACTIVE_PLUGIN_TITLE, title == null ? "" : title)
            .putString(KEY_ACTIVE_PLUGIN_LINEAGE, lineage == null ? "" : lineage)
            .commit();
        Log.i(TAG, "Persist active plugin full id=" + pluginId + " success=" + success);
    }

    private void clearSavedActivePluginId() {
        boolean success = pluginPrefs.edit()
            .remove(KEY_ACTIVE_PLUGIN_ID)
            .remove(KEY_ACTIVE_PLUGIN_PROMPT)
            .remove(KEY_ACTIVE_PLUGIN_TITLE)
            .remove(KEY_ACTIVE_PLUGIN_FEEDBACK)
            .remove(KEY_ACTIVE_PLUGIN_LINEAGE)
            .commit();
        Log.i(TAG, "Clear active plugin id success=" + success);
    }

    /**
     * The stable lineage ID of the current foreground app. Plugins store their
     * data under a lineage-scoped directory, so data survives feedback revisions.
     */
    public String getActivePluginLineage() {
        synchronized (pluginStateLock) {
            return activePluginLineage;
        }
    }

    /** The id of the current foreground app, or null when none is active. */
    public String getActivePluginId() {
        synchronized (pluginStateLock) {
            return activePluginId;
        }
    }

    // ---------- Pending task (persisted across process death, resumed on next launch) ----------

    static String serializePendingTask(PendingTask task) {
        return new Gson().toJson(task);
    }

    static PendingTask parsePendingTask(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return new Gson().fromJson(json, PendingTask.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void savePendingTask(PendingTask task) {
        boolean success = pendingTaskPrefs.edit()
            .putString(KEY_PENDING_TASK, serializePendingTask(task))
            .commit();
        Log.i(TAG, "Save pending task type=" + task.type + " success=" + success);
    }

    private PendingTask loadPendingTask() {
        return parsePendingTask(pendingTaskPrefs.getString(KEY_PENDING_TASK, null));
    }

    public boolean hasPendingTask() {
        return loadPendingTask() != null;
    }

    /** Clears the pending task (generation succeeded, or the user explicitly cancelled). */
    public void clearPendingTask() {
        boolean success = pendingTaskPrefs.edit().remove(KEY_PENDING_TASK).commit();
        Log.i(TAG, "Clear pending task success=" + success);
    }

    /**
     * Resumes the persisted task on a fresh launch. Feedback/runtime-error
     * revisions keep their in-place replacement semantics; a generation task
     * re-enters the pipeline as if freshly submitted.
     *
     * @return false when there is nothing to resume
     */
    public boolean resumePendingTask(Callback callback) {
        PendingTask task = loadPendingTask();
        if (task == null || destroyed.get()) {
            return false;
        }
        if ("FEEDBACK".equals(task.type) || "RETRY".equals(task.type)) {
            synchronized (pluginStateLock) {
                feedbackReplacementId = task.replacingId;
            }
            retry(task.originalIntent, task.errorMessage, callback);
        } else {
            cleanupStaleAttempt();
            attemptCounter.set(0);
            cancelled.set(false);
            synchronized (pluginStateLock) {
                feedbackReplacementId = null;
            }
            requestLLM(task.originalIntent, task.originalIntent, callback);
        }
        return true;
    }

// ---------- Feedback history (accumulates per app lineage, kept across rewrites) ----------

    private void saveFeedbackHistory() {
        List<String> snapshot;
        synchronized (pluginStateLock) {
            snapshot = new ArrayList<>(feedbackHistory);
        }
        boolean success = pluginPrefs.edit()
            .putString(KEY_ACTIVE_PLUGIN_FEEDBACK, new Gson().toJson(snapshot))
            .commit();
        Log.i(TAG, "Save feedback history count=" + snapshot.size()
            + " success=" + success);
    }

    private void loadFeedbackHistory() {
        String json = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_FEEDBACK, null);
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            List<String> loaded = new Gson().fromJson(json,
                new TypeToken<List<String>>() {}.getType());
            synchronized (pluginStateLock) {
                feedbackHistory.clear();
                if (loaded != null) {
                    feedbackHistory.addAll(loaded);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load feedback history, resetting", e);
        }
    }

    private void clearFeedbackHistory() {
        synchronized (pluginStateLock) {
            feedbackHistory.clear();
        }
        pluginPrefs.edit().remove(KEY_ACTIVE_PLUGIN_FEEDBACK).commit();
    }

    // ---------- App archive ----------

    /** Returns all archive entries, newest first. */
    public List<PluginArchive.Entry> getArchivedPlugins() {
        return archive.list();
    }

    /**
     * Switches to an archived app: after a successful load the swap happens in place -
     * the current active app goes into the archive, and the selected one becomes active.
     */
    public void switchToArchivedPlugin(String pluginId, RestoreCallback callback) {
        if (destroyed.get()) {
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_engine_destroyed)));
            return;
        }
        if (pluginId == null || pluginId.trim().isEmpty()) {
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_archive_entry_invalid)));
            return;
        }

        File dexFile = getPluginDexFile(pluginId);
        try {
            executor.execute(() -> {
            if (destroyed.get()) {
                return;
            }
            PluginLoader.LoadResult loadResult = loader.loadPlugin(pluginId, dexFile);
            if (destroyed.get()) {
                if (loadResult.success) {
                    loader.unloadPlugin(pluginId);
                }
                return;
            }
            if (!loadResult.success) {
                mainHandler.post(() -> {
                    if (!destroyed.get()) {
                        callback.onError(context.getString(R.string.err_archive_load_failed, loadResult.errorMessage));
                    }
                });
                return;
            }

            mainHandler.post(() -> {
                if (destroyed.get()) {
                    loader.unloadPlugin(pluginId);
                    return;
                }
                PluginArchive.Entry entry = archive.find(pluginId);
                if (entry == null) {
                    loader.unloadPlugin(pluginId);
                    callback.onError(context.getString(R.string.err_archive_entry_missing));
                    return;
                }
                synchronized (pluginStateLock) {
                    // The current foreground app (in-memory source of truth) is always archived - never depends on prefs, never lost
                    String curId = activePluginId;
                    String curTitle = activePluginTitle;
                    String curPrompt = activePluginPrompt;
                    String curLineage = activePluginLineage;
                    if (curId != null && !curId.trim().isEmpty() && !curId.equals(pluginId)) {
                        archive.addFront(curId, curTitle, curPrompt,
                            System.currentTimeMillis(), curLineage);
                        Log.i(TAG, "Archived previous plugin id=" + curId
                            + " title=" + curTitle);
                    }
                    // The switch target takes over the foreground with its own title, prompt and lineage
                    activePluginId = pluginId;
                    activePluginTitle = entry.title;
                    activePluginPrompt = entry.prompt;
                    activePluginLineage = (entry.lineage != null && !entry.lineage.isEmpty())
                        ? entry.lineage : newLineageId();
                    // Different lineage: clear the in-place feedback replacement marker
                    feedbackReplacementId = null;
                    // Library model: the switched app stays listed and moves to
                    // the front as the current one (remains deletable).
                    archive.addFront(pluginId, entry.title, entry.prompt,
                        System.currentTimeMillis(), activePluginLineage);
                }
                synchronized (pluginStateLock) {
                    saveActivePluginFull(pluginId, entry.prompt, entry.title, activePluginLineage);
                }
                // Different app lineage: reset the feedback history
                clearFeedbackHistory();
                registerPendingPlugin(loadResult.plugin, pluginId);
                Log.i(TAG, "Switched to archived plugin: " + pluginId
                    + " title: " + entry.title);
                callback.onPlugin(loadResult.plugin);
            });
        });
        } catch (RejectedExecutionException e) {
            Log.i(TAG, "Switch task already finished, ignoring this callback", e);
            mainHandler.post(() -> callback.onError(
                context.getString(R.string.err_restore_task_finished)));
        }
    }

    /**
     * Deletes an app from the archive and its on-disk files. If the deleted
     * app is the active one, the active state is cleared as well - the host
     * is expected to reset its UI in that case.
     */
    public void deleteArchivedPlugin(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            return;
        }
        boolean wasActive;
        synchronized (pluginStateLock) {
            wasActive = pluginId.equals(activePluginId);
            if (wasActive) {
                activePluginId = null;
                activePluginTitle = "";
                activePluginPrompt = "";
                activePluginLineage = "";
                feedbackReplacementId = null;
            }
        }
        archive.delete(pluginId);
        loader.unloadPlugin(pluginId);
        compiler.cleanPlugin(pluginId);
        if (wasActive) {
            clearSavedActivePluginId();
            clearFeedbackHistory();
        }
        Log.i(TAG, "Deleted plugin: " + pluginId + " wasActive=" + wasActive);
    }

    /** Reorders an archive entry; delta&lt;0 moves up, delta&gt;0 moves down. */
    public boolean moveArchivedPlugin(String pluginId, int delta) {
        return archive.move(pluginId, delta);
    }

    private File getPluginDexFile(String pluginId) {
        return new File(context.getFilesDir(), "plugins/" + pluginId + "/dex/classes.dex");
    }

    private void cleanupBrokenRestorablePlugin(String pluginId) {
        loader.unloadPlugin(pluginId);
        compiler.cleanPlugin(pluginId);
    }

    private Set<String> snapshotPluginIdsForCleanup() {
        Set<String> pluginIds = new LinkedHashSet<>();
        synchronized (pluginStateLock) {
            if (currentAttemptPluginId != null) {
                pluginIds.add(currentAttemptPluginId);
            }
            if (activePluginId != null) {
                pluginIds.add(activePluginId);
            }
            pluginIds.addAll(pendingPluginIds.values());
            currentAttemptPluginId = null;
            activePluginId = null;
            pendingPluginIds.clear();
        }
        return pluginIds;
    }

    /**
     * Extracts the code inside <java_code>...</java_code> from the LLM response.
     *
     * Multiple blocks are tolerated (models sometimes emit helper classes in
     * separate fences, or several tagged drafts): the block containing the
     * required DynamicPlugin class is preferred, otherwise the first one;
     * distinct fenced blocks are joined so helper classes are not lost.
     */
    static String extractJavaCode(String response) {
        Matcher tagMatcher = JAVA_CODE_PATTERN.matcher(response);
        String first = null;
        while (tagMatcher.find()) {
            String block = tagMatcher.group(1).trim();
            if (first == null) {
                first = block;
            }
            if (block.contains("DynamicPlugin")) {
                return block;
            }
        }
        if (first != null) {
            return first;
        }

        // Fall back to fenced blocks of any language, joined (distinct only).
        Matcher fenceMatcher = JAVA_CODE_BLOCK_PATTERN.matcher(response);
        StringBuilder joined = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (fenceMatcher.find()) {
            String block = fenceMatcher.group(1).trim();
            if (!block.isEmpty() && seen.add(block)) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(block);
            }
        }
        return joined.length() > 0 ? joined.toString() : null;
    }
}
