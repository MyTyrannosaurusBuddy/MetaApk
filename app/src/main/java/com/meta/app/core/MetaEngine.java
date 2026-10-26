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
    /** The build tool's success marker, shared by the producer and the progress-line renderer. */
    private static final String BUILD_OK_PREFIX = "Build OK";
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

    static String resolvePluginLineage(String pending, String active, String saved) {
        if (pending != null && !pending.isEmpty()) return pending;
        if (active != null && !active.isEmpty()) return active;
        return saved != null && !saved.isEmpty() ? saved : newLineageId();
    }

    static String agentRequirements(String originalIntent, String currentTask) {
        // Deliberately MINIMAL: the full revision history already lives in
        // the conversation itself (every feedback round is a message), so
        // listing it here again would duplicate it. Only the original intent
        // (which the message-rewrite would otherwise erase) and the current
        // task belong here.
        return "Original application requirements:\n" + originalIntent
            + "\n\nCurrent task:\n" + currentTask;
    }

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
        startAgentSession(userIntent, "Create the following app: " + userIntent,
            newLineageId(), null, true, "GENERATE", callback);
    }

    /**
     * Starts an agent session over the given workspace lineage. The pending
     * task (with its lineage) is persisted up front, so a process death
     * resumes the SAME workspace instead of starting over.
     *
     * @param replacingId non-null for in-place revisions (FEEDBACK/RETRY):
     *                    the final attach replaces the current app without a
     *                    duplicate archive entry
     * @param newApp      clears the feedback history (brand-new app lineage)
     */
    private void startAgentSession(String originalIntent, String sessionPrompt, String lineage,
                                   String replacingId, boolean newApp, String taskType,
                                   Callback callback) {
        AgentRun previous = currentAgentRun;
        if (previous != null) {
            previous.cancel();
            mainHandler.post(previous.callback::onClosePreview);
        }
        attemptCounter.set(0);
        cancelled.set(false);
        transportRetryCounter.set(0);
        synchronized (pluginStateLock) {
            feedbackReplacementId = replacingId;
        }
        if (newApp) {
            clearFeedbackHistory();
        }
        Workspace workspace = Workspace.forLineage(context, lineage);

        File checkpoint = new File(context.getFilesDir(), "agent_sessions/" + lineage + ".json");
        List<LLMClient.ChatMessage> messages = AgentConversation.load(checkpoint);
        List<String> savedRequirements = AgentConversation.loadRequirements(checkpoint);
        String requirements;
        synchronized (pluginStateLock) {
            for (String item : feedbackHistory) if (!savedRequirements.contains(item)) savedRequirements.add(item);
            feedbackHistory.clear();
            feedbackHistory.addAll(savedRequirements);
            requirements = agentRequirements(originalIntent, sessionPrompt);
        }
        if (messages.isEmpty()) {
            messages.add(LLMClient.ChatMessage.system(agentSystemPrompt()));
            messages.add(LLMClient.ChatMessage.user(requirements));
        } else {
            messages.set(0, LLMClient.ChatMessage.system(agentSystemPrompt()));
            messages.set(1, LLMClient.ChatMessage.user(requirements));
            // The tail of a loaded conversation ends with the previous round's
            // completion note; a bare "continue" makes some models answer
            // "already done" and rebuild without touching anything. State the
            // task explicitly so the new round actually changes the app.
            messages.add(LLMClient.ChatMessage.user(
                "A new revision request has arrived. Implement it now:\n"
                    + sessionPrompt
                    + "\n\nInspect the workspace, make the change with the tools, verify with "
                    + "build/run_app, then stop calling tools."));
        }

        PendingTask task = new PendingTask();
        task.type = taskType;
        task.originalIntent = originalIntent;
        task.errorMessage = sessionPrompt;
        task.sessionPrompt = sessionPrompt;
        task.feedback = savedRequirements;
        task.replacingId = replacingId;
        task.lineage = lineage;
        task.timestamp = System.currentTimeMillis();
        savePendingTask(task);

        AgentRun run = new AgentRun(workspace, messages, task, checkpoint, callback);
        currentAgentRun = run;
        try {
            executor.execute(() -> {
                if (isAgentAborted(run)) return;
                cleanupStaleAttempt();
                runAgentLoop(run);
            });
        } catch (RejectedExecutionException e) {
            // Destroy raced the guard above: the engine is gone. The persisted
            // task stays, so the next launch resumes the session.
            Log.i(TAG, "Agent session rejected (engine destroyed)", e);
            mainHandler.post(() -> callback.onError(context.getString(R.string.err_engine_destroyed)));
        }
    }

    /** The system prompt addendum describing the agent workspace and tools. */
    private String agentSystemPrompt() {
        return "You are an Android development agent working in a persistent multi-file Java project. "
            + "Use standard Android SDK APIs and the workspace tools to implement, verify and revise apps. "
            + "Import com.meta.app.core.PluginContract and com.meta.app.bridge.ApiBridge in the entry class. "
            + "The host adds common android.* and java.util imports to the entry file; helpers need normal imports. "
            + "ApiBridge supplies optional file/network/UI helpers and requestPermissions(String[], callback). "
            + "Prefer standard SDK APIs when they suffice. "
            + "\n\n"
            + "[AGENT MODE]\n"
            + "\n"
            + "Workflow:\n"
            + "1. Use list_files / read_file / grep to inspect the workspace, and "
            + "device_info to read screen properties before writing layout code.\n"
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
            + "Use inspect_ui, click, input_text and scroll to verify complete user flows. "
            + "View paths in UI dumps identify interaction targets. Keep architecture decisions "
            + "and verification notes in PROJECT.md so they survive context compaction.\n"
            + "\n"
            + "Layout sizing (general rule, every app):\n"
            + "- UI must fit the app canvas - the area your view tree actually gets, "
            + "reported in every run_app result. The canvas is smaller than the "
            + "physical screen (host chrome takes part of it).\n"
            + "- Before writing layout code, call device_info for the screen and "
            + "density; combine it with the canvas size from run_app.\n"
            + "- Sizes written in Java code are RAW PIXELS, not dp (no automatic "
            + "conversion): to use dp, convert explicitly with "
            + "(int)(N * context.getResources().getDisplayMetrics().density).\n"
            + "- The app fills the whole canvas: use MATCH_PARENT for the root view.\n"
            + "- CONTAINER-LEVEL sizes (grids, game boards, canvases, large blocks) "
            + "must be derived from the canvas (for example "
            + "container.getWidth()/columns), not hard-coded absolute pixel numbers. "
            + "The derived TOTAL must never exceed the canvas: clamp it to fit, "
            + "never overflow and clip.\n"
            + "- CONTROL-LEVEL sizes (button height, icons, paddings, text) may be "
            + "hard-coded; express them in dp with the explicit conversion above "
            + "(for example (int)(48 * density) for a standard touch target).\n"
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

    /** The agent's tool set. Public so the connection test can exercise the
     *  SAME request shape as a real generation session (a tool-less probe
     *  can pass where the real request fails). */
    public static List<LLMClient.ToolSpec> agentTools() {
        List<LLMClient.ToolSpec> tools = new ArrayList<>();
        tools.add(tool("list_files", "List all files in the project workspace.",
            "{}"));
        com.google.gson.JsonObject readParams = com.google.gson.JsonParser.parseString(props(requiredString("path"))).getAsJsonObject();
        for (String option : new String[] {"start_line", "line_count"}) {
            com.google.gson.JsonObject parameter = new com.google.gson.JsonObject();
            parameter.addProperty("type", "integer");
            parameter.addProperty("minimum", 1);
            readParams.getAsJsonObject("properties").add(option, parameter);
        }
        tools.add(new LLMClient.ToolSpec("read_file", "Read a numbered page of a workspace file. Optional start_line (1-based) and line_count (default 200, maximum 400).", readParams));
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
        tools.add(tool("inspect_ui", "Inspect the running preview after asynchronous work completes.", "{}"));
        tools.add(tool("click", "Tap a view in the running preview by injecting a REAL touch (down+up), "
            + "equivalent to a finger tap: works for onTouchEvent-based custom views, Canvas games and "
            + "OnClickListener-based buttons alike.",
            props(requiredString("path"))));
        tools.add(tool("input_text", "Replace an editable field's text in the running preview.",
            props(requiredString("path"), requiredString("text"))));
        tools.add(tool("scroll", "Swipe a preview view vertically by dy pixels (a signed integer string) "
            + "with an injected gesture; falls back to scrollBy for framework scroll containers.",
            props(requiredString("path"), requiredString("dy"))));
        tools.add(tool("device_info",
            "Report device properties: real screen size (px and dp), app window size, density, SDK level. Query this before writing layout code so UI sizes match the phone screen area.",
            "{}"));
        tools.add(tool("web_search",
            "Search the web and return the top result snippets (title, URL, excerpt). Use it to look up Android API usage, documentation, or troubleshooting knowledge when you are unsure.",
            props(requiredString("query"))));
        tools.add(tool("web_fetch",
            "Fetch the text content of an http(s) URL (documentation pages, raw source files, template repositories). Only text-like resources are returned, capped at 200 KB.",
            props(requiredString("url"))));
        tools.add(tool("list_templates",
            "List the available app templates (name, description). Before writing a common app type from scratch (counter, minesweeper, clock, stopwatch, calculator, weather...), check here for a reusable reference implementation.",
            "{}"));
        tools.add(tool("fetch_template",
            "Download a template's source code by name (see list_templates) to use as the starting point for the app; adapt it to the user's requirements.",
            props(requiredString("name"))));
        return tools;
    }

    private static LLMClient.ToolSpec tool(String name, String description, String properties) {
        com.google.gson.JsonObject params =
            com.google.gson.JsonParser.parseString(properties).getAsJsonObject();
        if (!params.has("type")) params.addProperty("type", "object");
        if (!params.has("properties")) params.add("properties", new com.google.gson.JsonObject());
        return new LLMClient.ToolSpec(name, description, params);
    }

    private static String requiredString(String name) {
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

    private void runAgentLoop(AgentRun run) {
        try {
            while (!isAgentAborted(run)) {
                try {
                    if (runAgentTurn(run)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "Agent loop iteration crashed", t);
                    failAgent(run, PluginRuntimeGuard.summarize(t));
                    return;
                }
            }
        } finally {
            if (isAgentAborted(run)) {
                closePreview(run);
                cleanupStaleAttempt();
            }
        }
    }

    private boolean isAgentAborted(AgentRun run) {
        return destroyed.get() || currentAgentRun != run || run.cancelled.get();
    }

    private void failAgent(AgentRun run, String error) {
        closePreview(run);
        cleanupStaleAttempt();
        // A terminal failure (loop crash / transport exhaustion) must not be
        // resurrected on the next launch: the persisted task is resolved. A
        // superseded run (aborted by a newer session) keeps the newer task.
        final boolean stillCurrent = !isAgentAborted(run);
        if (stillCurrent) {
            clearPendingTask();
        }
        mainHandler.post(() -> {
            if (!isAgentAborted(run)) run.callback.onError(error);
        });
    }

    private void checkpoint(AgentRun run) throws java.io.IOException {
        int budget = agentContextBudget(run);
        maybeSummarizeHistory(run, budget);
        AgentConversation.compact(run.messages, budget);
        AgentConversation.save(run.checkpoint, run.messages, run.task.feedback);
    }

    /**
     * The compaction budget follows the model's REAL context length (queried
     * from the endpoint metadata): ~4 chars per token, 75% headroom for the
     * response, capped at 2M chars. Unknown endpoints fall back to the
     * conservative default.
     */
    private int agentContextBudget(AgentRun run) {
        if (run.contextBudget <= 0) {
            int contextTokens = llmClient.fetchContextLength();
            if (contextTokens <= 0) {
                run.contextBudget = AgentConversation.MAX_CHARS;
            } else {
                long budget = (long) contextTokens * 4 * 3 / 4;
                run.contextBudget = (int) Math.min(budget, 2 * 1024 * 1024);
            }
        }
        return run.contextBudget;
    }

    /**
     * opencode-style compaction: when the history approaches the context
     * budget, the oldest tool turns are condensed into ONE summary by the
     * model itself, so the semantic continuity survives instead of being
     * dropped. Falls back to the plain drop-compaction on any failure.
     */
    private void maybeSummarizeHistory(AgentRun run, int budget) {
        if (AgentConversation.size(run.messages)
                <= (int) (budget * 0.8)
                || isAgentAborted(run)) {
            return;
        }
        List<LLMClient.ChatMessage> chunk = new ArrayList<>();
        int end = AgentConversation.collectOldestForSummary(run.messages, chunk, budget);
        if (end <= 2 || chunk.isEmpty()) {
            return;
        }
        String summary = requestHistorySummary(run, chunk);
        if (summary == null || summary.trim().isEmpty()) {
            return;
        }
        run.messages.subList(2, end).clear();
        LLMClient.ChatMessage summaryMessage = new LLMClient.ChatMessage();
        summaryMessage.role = "user";
        summaryMessage.content = "[Summarized earlier history]\n" + summary.trim();
        run.messages.add(2, summaryMessage);
        mainHandler.post(() -> {
            if (!isAgentAborted(run)) {
                run.callback.onAgentActivity(
                    context.getString(R.string.agent_activity_summarized));
            }
        });
    }

    private String requestHistorySummary(AgentRun run, List<LLMClient.ChatMessage> chunk) {
        List<LLMClient.ChatMessage> request = new ArrayList<>();
        LLMClient.ChatMessage instruction = new LLMClient.ChatMessage();
        instruction.role = "user";
        instruction.content = "Summarize the following development history. Preserve, in order of "
            + "importance: 1. the user's requirements and the requested feature list; 2. the work "
            + "that still needs to be continued - the unfinished tasks, the remaining TODOs and the "
            + "next steps, in detail, so nothing is lost; 3. the project's file layout - the path and "
            + "the purpose of every file; 4. the design decisions and the known issues. The workspace "
            + "files stay intact, so the summary is a map of the project, not a copy of the code. "
            + "Reply with the summary only.";
        request.add(instruction);
        request.addAll(chunk);
        final Object[] result = new Object[2];
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        llmClient.requestSummary(request, new LLMClient.SummaryCallback() {
            @Override
            public void onSummary(String summary) {
                result[0] = summary;
                latch.countDown();
            }

            @Override
            public void onError(Exception error) {
                result[1] = error;
                latch.countDown();
            }
        });
        while (true) {
            try {
                if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (isAgentAborted(run)) {
                return null;
            }
        }
        return (String) result[0];
    }

    /** Runs one agent turn; returns true when the app was delivered. */
    private boolean runAgentTurn(AgentRun run) throws Exception {
        List<LLMClient.ChatMessage> messages = run.messages;
        Workspace workspace = run.workspace;
        Callback callback = run.callback;
        checkpoint(run);
        mainHandler.post(() -> {
            if (isAgentAborted(run)) return;
            run.turnNumber++;
            long minutes = Math.max(1, (System.currentTimeMillis() - run.startedAt) / 60_000);
            callback.onAgentActivity(context.getString(
                R.string.agent_activity_thinking_turn, run.turnNumber, minutes));
        });
        // One tool-calling turn (blocks the executor thread).
        final Object[] result = new Object[2];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        LLMClient.RequestHandle request = llmClient.requestTools(messages, agentTools(), new LLMClient.ToolsCallback() {
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
                    if (isAgentAborted(run)) return;
                    callback.onStatusUpdate(context.getString(R.string.status_model_switched, from, to));
                    callback.onLlmReset();
                });
            }
        });
        run.request = request;
        while (!latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (isAgentAborted(run)) {
                request.cancel();
                return true;
            }
        }
        if (isAgentAborted(run)) return true;
        synchronized (result) {
            if (result[1] != null) {
                Exception error = (Exception) result[1];
                boolean retryable = !(error instanceof LLMClient.RequestException)
                    || ((LLMClient.RequestException) error).retryable;
                if (!retryable || ++run.transportFailures >= MAX_TRANSPORT_RETRIES) {
                    failAgent(run, error.getMessage());
                    return true;
                }
                // Do not grow model context with transport errors the model cannot fix.
                long until = android.os.SystemClock.elapsedRealtime() + 500L * run.transportFailures;
                while (!isAgentAborted(run) && android.os.SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(100);
                }
                return false;
            }
            run.transportFailures = 0;
            @SuppressWarnings("unchecked")
            List<LLMClient.ChatMessage> assistant = (List<LLMClient.ChatMessage>) result[0];
            messages.addAll(assistant);
            LLMClient.ChatMessage last = assistant.get(0);
            if (last.toolCalls != null && !last.toolCalls.isEmpty()) {
                List<LLMClient.ChatMessage> screenshots = new ArrayList<>();
                for (LLMClient.ToolCall call : last.toolCalls) {
                    if (isAgentAborted(run)) {
                        return true;
                    }
                    mainHandler.post(() -> {
                        if (isAgentAborted(run)) return;
                        callback.onStatusUpdate(context.getString(
                            R.string.status_llm_requesting) + " [tool: " + call.name + "]");
                    });
                    String toolResult;
                    try {
                        toolResult = executeTool(call, run);
                    } catch (Exception e) {
                        toolResult = "Error: " + e.getMessage();
                    }
                    final String activity = describeToolActivity(call, toolResult);
                    mainHandler.post(() -> {
                        if (isAgentAborted(run)) return;
                        callback.onAgentActivity(activity);
                    });
                    LLMClient.ChatMessage toolMsg = new LLMClient.ChatMessage();
                    toolMsg.role = "tool";
                    toolMsg.toolCallId = call.id;
                    toolMsg.name = call.name;
                    toolMsg.content = toolResult;
                    messages.add(toolMsg);
                    if (run.screenshot != null) {
                        LLMClient.ChatMessage screenshot = LLMClient.ChatMessage.user("Screenshot from " + call.name);
                        screenshot.imageBase64 = run.screenshot;
                        screenshots.add(screenshot);
                        run.screenshot = null;
                    }
                }
                // Complete the whole tool batch before inserting user image messages.
                messages.addAll(screenshots);
                checkpoint(run);
                return false;
            }
            // No tool calls: the model considers the app done -> build + load.
            // One-shot models fall through here too: their response carries
            // a <java_code> block which is written as the entry file first.
            String plainCode = extractJavaCode(last.content == null ? "" : last.content);
            if (plainCode != null) {
                workspace.writeFile("src/DynamicPlugin.java", plainCode);
            }
            closePreview(run);
            BuildOutcome outcome = buildWorkspace(run);
            if (outcome.plugin != null) {
                if (!run.ranApp && !run.verificationInjected) {
                    // Delivery gate: the app must have been RUN at least once
                    // before it can be delivered, otherwise the user would be
                    // the first to see its UI. Reuse the outcome built above
                    // (no second compilation) and feed the REAL UI dump back
                    // for one confirmation round. Injected only once: if the
                    // model still answers without tools next round, deliver.
                    run.verificationInjected = true;
                    registerPendingPlugin(outcome.plugin, outcome.pluginId, run.task.lineage);
                    run.preview = outcome;
                    String verified = inspectPreview(run, null, new com.google.gson.JsonObject());
                    messages.add(LLMClient.ChatMessage.user(
                        "You declared the app complete without running it. The host ran it "
                            + "for you; here is the actual UI:\n" + verified
                            + "\nIf the UI is correct, reply with a one-line summary and no tool "
                            + "calls. Otherwise fix the issues with the tools."));
                    checkpoint(run);
                    return false;
                }
                registerPendingPlugin(outcome.plugin, outcome.pluginId, run.task.lineage);
                // getTitle() is plugin code and may throw; a bad title must
                // not fail an otherwise successful build.
                String title;
                try {
                    title = outcome.plugin.getTitle();
                } catch (Throwable t) {
                    title = "Plugin";
                }
                Log.i(TAG, "Agent session succeeded: " + title);
                final String doneLine = context.getString(R.string.agent_activity_done, title);
                mainHandler.post(() -> {
                    if (isAgentAborted(run)) {
                        discardPlugin(outcome.plugin);
                        return;
                    }
                    callback.onAgentActivity(doneLine);
                    callback.onPlugin(outcome.plugin);
                });
                return true;
            }
            messages.add(LLMClient.ChatMessage.user(buildFailureMessage(run, outcome.error)));
            return false;
        }
    }

    /**
     * Failure message for the model. Repeating the SAME build error is the
     * classic no-progress loop: after a few identical rounds the message
     * escalates to force a change of approach instead of burning tokens.
     */
    static String buildFailureMessage(AgentRun run, String error) {
        boolean repeated = error != null && error.equals(run.lastBuildError);
        run.lastBuildError = error;
        run.repeatedBuildFailures = repeated ? run.repeatedBuildFailures + 1 : 1;
        if (run.repeatedBuildFailures >= 3) {
            return "This exact build error has now occurred " + run.repeatedBuildFailures
                + " times in a row:\n" + error
                + "\n\nRepeating the same edit is not converging. Reconsider the approach: "
                + "read the failing file and the files it references, verify the imports and "
                + "API signatures, and rewrite the problematic section differently. Use build "
                + "to verify, then stop calling tools when done.";
        }
        return "Build failed:\n" + error
            + "\nFix the files in the workspace (use build to verify), then stop calling tools when done.";
    }

    /**
     * Generic no-progress detection: the same outcome repeating across turns
     * (build errors, sanitizer rejections, identical tool failures) is the
     * universal signature of a stuck loop. Each outcome escalates once, at
     * the third occurrence.
     */
    static final class ConvergenceMonitor {
        static final int ESCALATION_THRESHOLD = 3;
        private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        private final java.util.Set<String> escalated = new java.util.HashSet<>();

        /** Registers an occurrence; true when this is the escalation point. */
        boolean register(String signature) {
            int count = counts.merge(signature, 1, Integer::sum);
            return count >= ESCALATION_THRESHOLD && escalated.add(signature);
        }

        int count(String signature) {
            return counts.getOrDefault(signature, 0);
        }

        boolean escalated(String signature) {
            return escalated.contains(signature);
        }
    }

    /** Executes one agent tool; returns the text result handed back to the model. */
    private String executeTool(LLMClient.ToolCall call, AgentRun run) throws Exception {
        Workspace workspace = run.workspace;
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
                return workspace.readFilePage(argString(args, "path"),
                    args.has("start_line") ? args.get("start_line").getAsInt() : 1,
                    args.has("line_count") ? args.get("line_count").getAsInt() : 200);
            case "write_file": {
                String content = argString(args, "content");
                CodeSanitizer.SanitizeResult check = new CodeSanitizer().checkOperations(content);
                if (!check.passed) {
                    String reason = check.reason;
                    boolean escalation = run.convergence.register("reject:" + reason);
                    if (escalation) {
                        return "Rejected: " + reason + ". This exact rejection has now repeated "
                            + run.convergence.count("reject:" + reason)
                            + " times - retrying the same approach will not converge. Find a "
                            + "different, compliant way to implement the feature, or drop it.";
                    }
                    return "Rejected: " + reason
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
                closePreview(run);
                BuildOutcome outcome = buildWorkspace(run);
                String report = outcome.plugin != null
                    ? BUILD_OK_PREFIX + " (entry class loaded successfully)"
                    : buildFailureMessage(run, outcome.error);
                // NOTE: a successful build's output is KEPT as the compile
                // cache; the next identical build reuses it instead of
                // recompiling (buildWorkspace manages cache eviction).
                return report;
            }
            case "run_app": {
                closePreview(run);
                BuildOutcome outcome = buildWorkspace(run);
                if (outcome.plugin == null) {
                    return outcome.error;
                }
                run.preview = outcome;
                run.ranApp = true;
                registerPendingPlugin(outcome.plugin, outcome.pluginId, run.task.lineage);
                return inspectPreview(run, null, args);
            }
            case "inspect_ui":
            case "click":
            case "input_text":
            case "scroll":
                if (run.preview == null) return "Error: no preview is running; call run_app first";
                return inspectPreview(run, call.name, args);
            case "device_info":
                return deviceInfo();
            case "web_search": {
                String query = argString(args, "query");
                // Prefer the native WebView search (real browser engine,
                // bot-wall resistant); fall back to the plain HTTP client
                // when the host WebView is unavailable (e.g. scripted tests).
                String result = WebViewBrowser.searchBlocking(query);
                if (result == null) {
                    result = WebSearch.search(query);
                }
                return result;
            }
            case "web_fetch": {
                String url = argString(args, "url");
                // Prefer the WebView (renders JS, resists bot walls); fall
                // back to the plain HTTP client when unavailable.
                String result = WebViewBrowser.fetchBlocking(url);
                if (result == null) {
                    result = WebFetch.fetch(url);
                }
                return result;
            }
            case "list_templates":
                return TemplateRepo.list();
            case "fetch_template":
                return TemplateRepo.fetch(argString(args, "name"));
            default:
                return "Error: unknown tool " + call.name;
        }
    }

    /** Device properties for layout decisions: sizes in px AND dp, plus density. */
    private String deviceInfo() {
        try {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            android.graphics.Point real = new android.graphics.Point();
            android.view.WindowManager wm =
                (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealSize(real);
            } else {
                real.set(dm.widthPixels, dm.heightPixels);
            }
            return "Screen: " + real.x + "x" + real.y + " px\n"
                + "Density: " + dm.density + " (" + dm.densityDpi + " dpi)\n"
                + "dp size: " + Math.round(dm.widthPixels / dm.density) + "x"
                + Math.round(dm.heightPixels / dm.density) + " dp\n"
                + "SDK level: " + android.os.Build.VERSION.SDK_INT + "\n"
                + "Note: the canvas your view tree actually gets is smaller than the "
                + "screen (host chrome) and is reported in every run_app result.";
        } catch (Throwable t) {
            return "Error: device info unavailable: " + t.getMessage();
        }
    }

    private String argString(com.google.gson.JsonObject args, String name) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            return "";
        }
        return args.get(name).getAsString();
    }

    /** One human-readable progress line for a tool execution. */
    private String describeToolActivity(LLMClient.ToolCall call, String result) {
        String path = argString(safeArgs(call), "path");
        switch (call.name == null ? "" : call.name) {
            case "list_files":
                return context.getString(R.string.agent_activity_list);
            case "read_file":
                return context.getString(R.string.agent_activity_read,
                    path.isEmpty() ? "…" : path);
            case "write_file":
                return context.getString(R.string.agent_activity_write,
                    path.isEmpty() ? "…" : path);
            case "edit_file":
                return context.getString(R.string.agent_activity_edit,
                    path.isEmpty() ? "…" : path);
            case "grep":
                return context.getString(R.string.agent_activity_grep);
            case "build":
                return result.startsWith(BUILD_OK_PREFIX)
                    ? context.getString(R.string.agent_activity_build_ok)
                    : context.getString(R.string.agent_activity_build_fix);
            case "run_app":
                return context.getString(R.string.agent_activity_run);
            case "inspect_ui":
                return context.getString(R.string.agent_activity_inspect);
            case "click":
            case "input_text":
            case "scroll":
                return context.getString(R.string.agent_activity_interact);
            default:
                return context.getString(R.string.agent_activity_tool, call.name);
        }
    }

    private com.google.gson.JsonObject safeArgs(LLMClient.ToolCall call) {
        try {
            return com.google.gson.JsonParser.parseString(
                call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
        } catch (Exception e) {
            return new com.google.gson.JsonObject();
        }
    }

    /** Build+load of the current workspace; the plugin is NOT attached as active. */
    private BuildOutcome buildWorkspace(AgentRun run) {
        Workspace workspace = run.workspace;
        if (isAgentAborted(run)) return BuildOutcome.aborted();
        cleanupStaleAttempt();
        // Compile cache: when the workspace sources are byte-identical to the
        // last successful build, reuse its dex instead of running ECJ+D8 again
        // (the model's standard rhythm is build -> run_app back to back, which
        // used to compile the same files twice).
        String fingerprint = computeWorkspaceFingerprint(workspace.getSrcDir());
        String cachedId = fingerprint != null && fingerprint.equals(cachedBuildFingerprint)
            ? cachedBuildPluginId : null;
        if (cachedId != null && getPluginDexFile(cachedId).exists()) {
            PluginLoader.LoadResult reuse = loader.loadPlugin(cachedId, getPluginDexFile(cachedId));
            if (reuse.success) {
                Log.i(TAG, "Build reused cached output " + cachedId
                    + " (sources unchanged)");
                return BuildOutcome.success(cachedId, reuse.plugin);
            }
        }
        String pluginId = "plugin_" + UUID.randomUUID().toString().substring(0, 8);
        setCurrentAttemptPluginId(pluginId);
        CodeCompiler.CompileResult compileResult = compiler.compileWorkspace(pluginId, workspace.getSrcDir());
        if (isAgentAborted(run)) {
            cleanupAttempt(pluginId);
            return BuildOutcome.aborted();
        }
        if (!compileResult.success) {
            cleanupAttempt(pluginId);
            return BuildOutcome.failed(compileResult.errorMessage);
        }
        DexTransformer.TransformResult transformResult =
            transformer.transform(pluginId, compileResult.classesDir);
        if (isAgentAborted(run)) {
            cleanupAttempt(pluginId);
            return BuildOutcome.aborted();
        }
        if (!transformResult.success) {
            cleanupAttempt(pluginId);
            return BuildOutcome.failed("DEX transformation failed: " + transformResult.errorMessage);
        }
        PluginLoader.LoadResult loadResult = loader.loadPlugin(pluginId, transformResult.dexFile);
        if (isAgentAborted(run)) {
            cleanupAttempt(pluginId);
            return BuildOutcome.aborted();
        }
        if (!loadResult.success) {
            cleanupAttempt(pluginId);
            return BuildOutcome.failed("Plugin load failed: " + loadResult.errorMessage);
        }
        // The fresh build becomes the new cache. The previous cached output is
        // NOT deleted here: it may still be the active app's dex (the active
        // app and the cache can point at the same directory). Orphaned cache
        // dirs are swept by the next engine construction.
        synchronized (pluginStateLock) {
            if (pluginId.equals(currentAttemptPluginId)) {
                currentAttemptPluginId = null;
            }
        }
        cachedBuildFingerprint = fingerprint;
        cachedBuildPluginId = pluginId;
        return BuildOutcome.success(pluginId, loadResult.plugin);
    }

    /**
     * Fingerprint of the workspace sources: relative path + content of every
     * .java file, hashed. Identical sources always produce the same value, so
     * the compile cache is safe (the sources passed the security checks under
     * exactly this fingerprint).
     */
    private static String computeWorkspaceFingerprint(File srcDir) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            List<File> files = new ArrayList<>();
            collectJavaFiles(srcDir, files);
            java.util.Collections.sort(files, (a, b) -> a.getPath().compareTo(b.getPath()));
            for (File file : files) {
                String rel = srcDir.toPath().relativize(file.toPath()).toString();
                digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(java.nio.file.Files.readAllBytes(file.toPath()));
                digest.update((byte) 0);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "Workspace fingerprint failed, skipping compile cache", t);
            return null;
        }
    }

    private static void collectJavaFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectJavaFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    /** Renders the built plugin in the host and reports the UI inspection result. */
    private String inspectPreview(AgentRun run, String action, com.google.gson.JsonObject args) throws InterruptedException {
        final Object[] result = new Object[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            if (isAgentAborted(run)) {
                result[0] = "Aborted";
                latch.countDown();
                return;
            }
            PreviewReady ready = (uiDump, screenshot) -> {
                StringBuilder sb = new StringBuilder();
                if (uiDump != null && !uiDump.trim().isEmpty()) {
                    sb.append("UI dump:\n").append(uiDump).append('\n');
                }
                synchronized (result) {
                    result[0] = sb.toString();
                    run.screenshot = screenshot;
                }
                latch.countDown();
            };
            try {
                if (action == null) run.callback.onPreviewPlugin(run.preview.plugin, ready);
                else run.callback.onInspectPreview(action, args, ready);
            } catch (Throwable t) {
                ready.done("Preview failed: " + PluginRuntimeGuard.summarize(t), null);
            }
        });
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (!latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (isAgentAborted(run)) return "Aborted";
            if (android.os.SystemClock.elapsedRealtime() > deadline) return "Error: UI inspection timed out"
                + " (the app's code may be blocking the UI thread - check for infinite loops or long work in event handlers)";
        }
        String r;
        synchronized (result) {
            r = (String) result[0];
        }
        return r == null ? "(no UI inspection available)" : r;
    }

    private void closePreview(AgentRun run) {
        BuildOutcome preview = run.preview;
        if (preview == null) return;
        run.preview = null;
        mainHandler.post(run.callback::onClosePreview);
        synchronized (pluginStateLock) {
            pendingPluginIds.remove(preview.plugin);
            pendingPluginLineages.remove(preview.plugin);
        }
        // The preview output directory is NOT deleted: it is the compile
        // cache's product (reused by the next identical build/run_app).
        // Orphaned dirs are swept by the next engine construction.
    }

    static final class AgentRun {
        final Workspace workspace;
        final List<LLMClient.ChatMessage> messages;
        final PendingTask task;
        final File checkpoint;
        final Callback callback;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        volatile LLMClient.RequestHandle request;
        BuildOutcome preview;
        volatile String screenshot;
        int transportFailures;
        /** Consecutive identical build errors: the no-progress circuit breaker. */
        int repeatedBuildFailures;
        String lastBuildError;
        /** Progress accounting for the user-visible log. */
        int turnNumber;
        final long startedAt = System.currentTimeMillis();
        /** Per-run context budget (chars), derived from the model's real
         *  context length; -1 until computed. */
        int contextBudget = -1;
        /** Generic no-progress detection across all repeated outcomes. */
        final ConvergenceMonitor convergence = new ConvergenceMonitor();
        /** Whether the app was actually RUN (run_app) at least once. */
        boolean ranApp;
        /** Whether the missing-run delivery gate was already injected once. */
        boolean verificationInjected;

        AgentRun(Workspace workspace, List<LLMClient.ChatMessage> messages,
                 PendingTask task, File checkpoint, Callback callback) {
            this.workspace = workspace; this.messages = messages; this.task = task;
            this.checkpoint = checkpoint; this.callback = callback;
        }

        void cancel() {
            cancelled.set(true);
            LLMClient.RequestHandle handle = request;
            if (handle != null) handle.cancel();
        }
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
        public String sessionPrompt;
        public List<String> feedback;
        /** The version being replaced in place (FEEDBACK/RETRY only). */
        public String replacingId;
        /** The workspace lineage of agent sessions (resumed across process death). */
        public String lineage;
        public long timestamp;
    }

    /** Compile cache: the last successful build's source fingerprint and the
     *  plugin id whose dex still matches it. Reused by build/run_app while the
     *  sources stay identical. */
    private String cachedBuildFingerprint;
    private String cachedBuildPluginId;

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
    private final Map<PluginContract, String> pendingPluginLineages = new IdentityHashMap<>();
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
    private volatile AgentRun currentAgentRun;
    /** Consecutive transport-level (network/model) failures; capped to stop infinite loops on bad keys/endpoints. */
    private final java.util.concurrent.atomic.AtomicInteger transportRetryCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** Maximum consecutive transport failures before giving up and surfacing the error. */
    private static final int MAX_TRANSPORT_RETRIES = 3;

    public interface Callback {
        void onStatusUpdate(String status);
        void onPlugin(PluginContract plugin);
        void onError(String message);

        /** Called when switching from the primary to the backup model; used to discard the primary model's partial output. */
        default void onLlmReset() { }

        /**
         * Agent mode: preview the given plugin in the host UI and report what
         * the UI looks like (text dump + optional screenshot) via ready.
         */
        default void onPreviewPlugin(PluginContract plugin, PreviewReady ready) {
            ready.done(null, null);
        }

        default void onInspectPreview(String action, com.google.gson.JsonObject args, PreviewReady ready) {
            ready.done("Error: preview interaction is unavailable", null);
        }

        default void onClosePreview() { }

        /**
         * Agent mode: one human-readable line describing what the agent just
         * did (wrote a file, built, previewed, clicked the UI...). The host
         * streams these into the progress log so the user can SEE the agent
         * working - the code itself is the vehicle, the visible progress is
         * the experience.
         */
        default void onAgentActivity(String line) { }
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
        this(context, new LlmConfig(context));
    }

    public MetaEngine(Context context, LlmConfig config) {
        this.context = context;
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
        this.selfHealPrefs = context.getSharedPreferences(SELFHEAL_PREFS, Context.MODE_PRIVATE);
        this.generationCache = new GenerationCache(context);
        loadFeedbackHistory();
        sweepOrphanedData();
    }

    /**
     * Removes data left behind by failed / cancelled / killed sessions:
     * attempt ids are only kept in memory, so a generation interrupted
     * mid-compile can strand files forever - and a cancelled or failed
     * session leaves its workspace and plugin_data behind with no other
     * cleanup path.
     *
     * Keeps everything still reachable: the active app, the pending task's
     * lineage (needed for resume), and every archive entry (id for plugins/,
     * lineage for workspaces/ and plugin_data/).
     */
    private void sweepOrphanedData() {
        Set<String> keepPluginIds = new HashSet<>();
        Set<String> keepLineages = new HashSet<>();
        String activeId = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_ID, "");
        if (!activeId.isEmpty()) {
            keepPluginIds.add(activeId);
        }
        String activeLineage = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_LINEAGE, "");
        if (!activeLineage.isEmpty()) {
            keepLineages.add(activeLineage);
        }
        PendingTask pending = loadPendingTask();
        if (pending != null) {
            if (pending.replacingId != null && !pending.replacingId.isEmpty()) {
                keepPluginIds.add(pending.replacingId);
            }
            if (pending.lineage != null && !pending.lineage.isEmpty()) {
                keepLineages.add(pending.lineage);
            }
        }
        for (PluginArchive.Entry entry : archive.list()) {
            keepPluginIds.add(entry.id);
            if (entry.lineage != null && !entry.lineage.isEmpty()) {
                keepLineages.add(entry.lineage);
            }
        }
        File pluginsDir = new File(context.getFilesDir(), "plugins");
        File[] dirs = pluginsDir.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.isDirectory() && !keepPluginIds.contains(dir.getName())) {
                    compiler.cleanPlugin(dir.getName());
                    Log.i(TAG, "Swept orphaned plugin dir: " + dir.getName());
                }
            }
        }
        sweepLineageDir(new File(context.getFilesDir(), "workspaces"), keepLineages);
        sweepLineageDir(new File(context.getFilesDir(), "plugin_data"), keepLineages);
        sweepLineageDir(new File(context.getCacheDir(), "plugin_data"), keepLineages);
    }

    /** Deletes lineage directories not in the keep set. */
    private void sweepLineageDir(File parent, Set<String> keep) {
        File[] dirs = parent.listFiles();
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            if (dir.isDirectory() && !keep.contains(dir.getName())) {
                deleteRecursive(dir);
                Log.i(TAG, "Swept orphaned lineage dir: " + dir.getName());
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
        AgentRun run = currentAgentRun;
        if (run != null) {
            run.cancel();
            mainHandler.post(() -> {
                if (currentAgentRun == run) run.callback.onClosePreview();
            });
        }
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
            } else if (currentAgentRun != null && currentAgentRun.task != null
                    && currentAgentRun.task.originalIntent != null
                    && !currentAgentRun.task.originalIntent.trim().isEmpty()) {
                // A session delivered after a process-death resume: the
                // host's in-memory lastIntent is gone, but the persisted
                // pending task carried the original intent across the
                // restart - it is the authoritative prompt.
                newPrompt = currentAgentRun.task.originalIntent;
            } else {
                // Cold-start restore scenario: keep the previously persisted prompt
                newPrompt = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_PROMPT, "");
            }
            activePluginPrompt = newPrompt;

            newLineage = resolvePluginLineage(pendingPluginLineages.remove(plugin),
                activePluginLineage, pluginPrefs.getString(KEY_ACTIVE_PLUGIN_LINEAGE, ""));
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
        // A successful attach clears the lineage's self-heal budget.
        resetSelfHealBudget(newLineage);
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
            pendingPluginLineages.remove(plugin);
        }
    }

    /**
     * Called when a generated plugin fails to inject, to avoid leftover dex / class / ClassLoader artifacts.
     */
    public void discardPlugin(PluginContract plugin) {
        String pluginId;
        synchronized (pluginStateLock) {
            pluginId = pendingPluginIds.remove(plugin);
            pendingPluginLineages.remove(plugin);
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
     * User feedback revision: records the feedback into the history (accumulating across rewrites) and feeds it back to the model for regeneration.
     */
    public void feedback(String originalIntent, String feedback, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        String replacingId;
        String lineage;
        synchronized (pluginStateLock) {
            // Remember the version being fixed: the new version replaces it in place, no duplicate archive entry
            replacingId = activePluginId;
            lineage = (activePluginLineage != null && !activePluginLineage.isEmpty())
                ? activePluginLineage : newLineageId();
        }
        transportRetryCounter.set(0);
        if (feedback != null && !feedback.trim().isEmpty()) {
            synchronized (pluginStateLock) {
                feedbackHistory.add(feedback.trim());
            }
            saveFeedbackHistory();
        }
        // Agent session on the SAME workspace: the revision is a continuation
        // of the app's project, edited incrementally instead of regenerated.
        startAgentSession(originalIntent,
            "The user reported the following problem with the app in this workspace:\n\""
                + feedback + "\"\n"
                + "Inspect the files, fix the issue with targeted edits, then use build "
                + "to verify and run_app to check the UI. All previous requirements "
                + "must stay satisfied.",
            lineage, replacingId, false, "FEEDBACK", callback);
    }

    /**
     * Runtime-error self-heal: the crash summary continues the app's agent
     * session on its existing workspace (in-place replacement). The loop is
     * bounded by a very generous per-lineage budget: past MAX_SELFHEAL_ROUNDS
     * consecutive failed rounds the engine gives up instead of burning tokens
     * forever on a crash the model cannot fix.
     */
    public void retryRuntimeError(String originalIntent, String errorMessage, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        String replacingId;
        String lineage;
        synchronized (pluginStateLock) {
            replacingId = activePluginId;
            lineage = (activePluginLineage != null && !activePluginLineage.isEmpty())
                ? activePluginLineage : newLineageId();
        }
        transportRetryCounter.set(0);
        if (selfHealBudgetExhausted(lineage, callback)) {
            return;
        }
        incrementSelfHealBudget(lineage);
        startAgentSession(originalIntent,
            "The app in this workspace crashed at runtime:\n" + errorMessage + "\n"
                + "Inspect the files, fix the issue with targeted edits, then use build "
                + "to verify and run_app to check the UI.",
            lineage, replacingId, false, "RETRY", callback);
    }

    /** An initial attach failure continues the same workspace, even before it became active. */
    public void retry(String originalIntent, String errorMessage, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        AgentRun run = currentAgentRun;
        if (run != null && !isAgentAborted(run)) {
            String lineage = run.task.lineage;
            if (selfHealBudgetExhausted(lineage, callback)) {
                return;
            }
            incrementSelfHealBudget(lineage);
            startAgentSession(originalIntent, "The application failed during initialization:\n" + errorMessage
                + "\nInspect the project and fix the failure. Use run_app to verify.", run.task.lineage,
                run.task.replacingId, false, "RETRY", callback);
        } else {
            retryRuntimeError(originalIntent, errorMessage, callback);
        }
    }

    // ---------- Self-heal budget (per lineage, persists across restarts) ----------

    /** Consecutive failed self-heal rounds before the engine gives up. Deliberately loose. */
    private static final int MAX_SELFHEAL_ROUNDS = 100;
    private static final String SELFHEAL_PREFS = "meta_runtime_heal";

    private SharedPreferences selfHealPrefs;

    private static String selfHealKey(String lineage) {
        return "heal_" + lineage;
    }

    /** True when the lineage has exhausted its self-heal budget (reports the error). */
    private boolean selfHealBudgetExhausted(String lineage, Callback callback) {
        int rounds = selfHealPrefs.getInt(selfHealKey(lineage), 0);
        if (rounds < MAX_SELFHEAL_ROUNDS) {
            return false;
        }
        Log.w(TAG, "Self-heal budget exhausted for lineage " + lineage
            + " (" + rounds + " failed rounds)");
        mainHandler.post(() -> {
            if (destroyed.get()) {
                return;
            }
            callback.onError(context.getString(R.string.err_selfheal_give_up,
                MAX_SELFHEAL_ROUNDS));
        });
        return true;
    }

    private void incrementSelfHealBudget(String lineage) {
        int rounds = selfHealPrefs.getInt(selfHealKey(lineage), 0) + 1;
        selfHealPrefs.edit().putInt(selfHealKey(lineage), rounds).apply();
        Log.i(TAG, "Self-heal round " + rounds + "/" + MAX_SELFHEAL_ROUNDS
            + " for lineage " + lineage);
    }

    /** A successful attach resets the lineage's self-heal budget. */
    private void resetSelfHealBudget(String lineage) {
        if (lineage == null || lineage.isEmpty()) {
            return;
        }
        selfHealPrefs.edit().remove(selfHealKey(lineage)).apply();
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


    private boolean shouldAbort() {
        return cancelled.get() || destroyed.get();
    }

    private void setCurrentAttemptPluginId(String pluginId) {
        synchronized (pluginStateLock) {
            currentAttemptPluginId = pluginId;
        }
    }

    private void registerPendingPlugin(PluginContract plugin, String pluginId) {
        registerPendingPlugin(plugin, pluginId, null);
    }

    private void registerPendingPlugin(PluginContract plugin, String pluginId, String lineage) {
        synchronized (pluginStateLock) {
            if (pluginId != null && pluginId.equals(currentAttemptPluginId)) {
                currentAttemptPluginId = null;
            }
            pendingPluginIds.put(plugin, pluginId);
            if (lineage != null) pendingPluginLineages.put(plugin, lineage);
        }
    }

    /** Resolve identity before onCreate, including previews and first-time generation. */
    public String getPluginLineage(PluginContract plugin) {
        synchronized (pluginStateLock) {
            return resolvePluginLineage(pendingPluginLineages.get(plugin), activePluginLineage,
                pluginPrefs.getString(KEY_ACTIVE_PLUGIN_LINEAGE, ""));
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
     * The current foreground app's own original prompt (in-memory source of
     * truth, stays correct after switches/generation). When the prompt was
     * lost historically (a pre-fix resume delivery persisted ""), it is
     * recovered from the lineage's session checkpoint and re-persisted, so
     * improve/runtime-repair never hit a dead end.
     */
    public String getActivePluginPrompt() {
        synchronized (pluginStateLock) {
            if (activePluginPrompt != null && !activePluginPrompt.trim().isEmpty()) {
                return activePluginPrompt;
            }
            String recovered = "";
            if (activePluginId != null) {
                recovered = pluginPrefs.getString(KEY_ACTIVE_PLUGIN_PROMPT, "");
            }
            if (recovered.trim().isEmpty() && activePluginLineage != null
                    && !activePluginLineage.isEmpty()) {
                recovered = recoverPromptFromCheckpoint(activePluginLineage);
                if (!recovered.isEmpty()) {
                    activePluginPrompt = recovered;
                    pluginPrefs.edit()
                        .putString(KEY_ACTIVE_PLUGIN_PROMPT, recovered)
                        .apply();
                    Log.i(TAG, "Recovered lost prompt from session checkpoint: "
                        + recovered);
                }
            }
            return recovered;
        }
    }

    /**
     * Extracts the original intent from a checkpoint's requirements message
     * ("Original application requirements:\\n&lt;intent&gt;\\n\\nCurrent task...").
     */
    private String recoverPromptFromCheckpoint(String lineage) {
        File checkpoint = new File(context.getFilesDir(),
            "agent_sessions/" + lineage + ".json");
        List<LLMClient.ChatMessage> messages = AgentConversation.load(checkpoint);
        if (messages.size() <= 1 || messages.get(1).content == null) {
            return "";
        }
        return extractOriginalIntent(messages.get(1).content);
    }

    /** "Original application requirements:\\nXXX\\n\\n..." -> XXX, or "". */
    static String extractOriginalIntent(String requirementsBlock) {
        if (requirementsBlock == null) {
            return "";
        }
        String marker = "Original application requirements:";
        int start = requirementsBlock.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int intentStart = start + marker.length();
        int end = requirementsBlock.indexOf("\n\n", intentStart);
        if (end < 0) {
            end = requirementsBlock.length();
        }
        return requirementsBlock.substring(intentStart, end).trim();
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
     * Resumes the persisted task on a fresh launch. The workspace (keyed by
     * the persisted lineage) survived the process death, so every task type
     * continues as an agent session over the SAME files - feedback/runtime
     * revisions keep their in-place replacement semantics.
     *
     * @return false when there is nothing to resume
     */
    public boolean resumePendingTask(Callback callback) {
        PendingTask task = loadPendingTask();
        if (task == null || destroyed.get()) {
            return false;
        }
        String lineage = (task.lineage != null && !task.lineage.isEmpty())
            ? task.lineage : newLineageId();
        if ("FEEDBACK".equals(task.type) || "RETRY".equals(task.type)) {
            startAgentSession(task.originalIntent,
                task.sessionPrompt != null ? task.sessionPrompt : "Continue the previous session (the process restarted; the workspace "
                    + "is intact). The last issue was:\n" + task.errorMessage + "\n"
                    + "Inspect the files and finish the fix with targeted edits.",
                lineage, task.replacingId, false, task.type, callback);
        } else {
            startAgentSession(task.originalIntent,
                "Continue the previous session (the process restarted; the workspace "
                    + "is intact). Finish creating the app originally requested.",
                lineage, null, true, "GENERATE", callback);
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
        // Capture the lineage before the entry is gone: when no other archive
        // entry shares it, the app's workspace and data directories go too.
        PluginArchive.Entry entry = archive.find(pluginId);
        String lineage = entry != null ? entry.lineage : null;
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
        if (lineage != null && !lineage.isEmpty() && !lineageStillReferenced(lineage)) {
            deleteRecursive(new File(context.getFilesDir(), "workspaces/" + lineage));
            deleteRecursive(new File(context.getFilesDir(), "plugin_data/" + lineage));
            deleteRecursive(new File(context.getCacheDir(), "plugin_data/" + lineage));
            Log.i(TAG, "Deleted orphaned lineage data: " + lineage);
        }
        if (wasActive) {
            clearSavedActivePluginId();
            clearFeedbackHistory();
        }
        Log.i(TAG, "Deleted plugin: " + pluginId + " wasActive=" + wasActive);
    }

    /** Whether another archive entry still uses this lineage. */
    private boolean lineageStillReferenced(String lineage) {
        for (PluginArchive.Entry entry : archive.list()) {
            if (lineage.equals(entry.lineage)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
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
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete: " + file.getAbsolutePath());
        }
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
            if (cachedBuildPluginId != null) {
                pluginIds.add(cachedBuildPluginId);
            }
            pluginIds.addAll(pendingPluginIds.values());
            currentAttemptPluginId = null;
            activePluginId = null;
            cachedBuildPluginId = null;
            cachedBuildFingerprint = null;
            pendingPluginIds.clear();
            pendingPluginLineages.clear();
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
