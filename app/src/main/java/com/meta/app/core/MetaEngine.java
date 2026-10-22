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
    private static final Pattern JAVA_CODE_PATTERN =
        Pattern.compile("<java_code>(.*?)</java_code>", Pattern.DOTALL);
    private static final Pattern JAVA_CODE_BLOCK_PATTERN =
        Pattern.compile("```[a-zA-Z]*\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);

    private final Context context;
    private final LLMClient llmClient;
    private final CodeSanitizer sanitizer;
    private final CodeCompiler compiler;
    private final DexTransformer transformer;
    private final PluginLoader loader;
    private final PluginArchive archive;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final SharedPreferences pluginPrefs;
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
    private String currentAttemptPluginId;
    /** Feedback history of the current app lineage (accumulates across rewrites; cleared when switching apps or generating a new one). */
    private final List<String> feedbackHistory = new ArrayList<>();
    /** ID of the version being replaced by a feedback revision: the new version upgrades in place without a duplicate archive entry. */
    private String feedbackReplacementId;

    public interface Callback {
        void onStatusUpdate(String status);
        void onPlugin(PluginContract plugin);
        void onError(String message);
        void onLlmToken(String token);

        /** Called when switching from the primary to the backup model; used to discard the primary model's partial output. */
        default void onLlmReset() { }
    }

    public interface RestoreCallback {
        void onStatusUpdate(String status);
        void onPlugin(PluginContract plugin);
        void onError(String message);
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
        loadFeedbackHistory();
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
        // Brand-new app: clear the previous app lineage's feedback history and the in-place replacement marker
        synchronized (pluginStateLock) {
            feedbackReplacementId = null;
        }
        clearFeedbackHistory();
        requestLLM(userIntent, userIntent, callback);
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
        String newTitle;
        String newPrompt;
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
        }
        if (replaceInPlace) {
            // Feedback revision: clean up the superseded version's disk files, no duplicate archive entry
            cleanupPlugin(replacedId);
            Log.i(TAG, "Feedback revision replaced previous version id=" + replacedId
                + " (no archive entry)");
        } else if (prevId != null && !prevId.equals(pluginId)) {
            archive.addFront(prevId, prevTitle, prevPrompt, System.currentTimeMillis());
            Log.i(TAG, "Archived previous plugin id=" + prevId
                + " title=" + prevTitle + " prompt=" + prevPrompt);
        }
        saveActivePluginFull(pluginId, activePluginPrompt, activePluginTitle);
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
                // Transient failures (network/rate-limit) also retry indefinitely, with a small backoff to avoid hammering the API
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                    // ignored
                }
                if (shouldAbort()) return;
                handleFailure("LLM request failed: " + e.getMessage(), originalIntent, callback);
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
        synchronized (pluginStateLock) {
            // Remember the version being fixed: the new version replaces it in place, no duplicate archive entry
            feedbackReplacementId = activePluginId;
        }
        if (feedback != null && !feedback.trim().isEmpty()) {
            synchronized (pluginStateLock) {
                feedbackHistory.add(feedback.trim());
            }
            saveFeedbackHistory();
        }
        retry(originalIntent, "User feedback: " + feedback, callback);
    }

    /**
     * Feeds an error back to the LLM for regeneration (single entry point for compile failures, injection failures and runtime errors),
     * with no attempt limit, until success or user cancellation.
     */
    public void retry(String originalIntent, String errorMessage, Callback callback) {
        if (shouldAbort()) {
            return;
        }
        handleFailure(errorMessage, originalIntent, callback);
    }

    /**
     * Builds the retry prompt: feeds the error/feedback back to the LLM together with the feedback history and the previous complete code
     * (each LLM request is one-shot with no conversation history, so context must be passed explicitly).
     */
    static String buildRetryPrompt(String originalIntent, String errorMessage,
                                   String previousSource, List<String> feedbackHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The previously generated code has issues. Details:\n")
            .append(errorMessage).append("\n\n");
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
        if (previousSource != null && !previousSource.trim().isEmpty()) {
            sb.append("This is the previous complete code; make minimal fixes based on it:\n```java\n")
                .append(previousSource)
                .append("\n```\n\n");
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
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        File sourceFile = new File(context.getFilesDir(),
            "plugins/" + id + "/source/DynamicPlugin.java");
        if (!sourceFile.exists()) {
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(sourceFile.toPath());
            if (bytes.length > 30 * 1024) {
                return null; // omit when the source is too large, keeping the prompt from bloating
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read active plugin source", e);
            return null;
        }
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
                + "a <java_code>...</java_code> block and output it again", originalIntent, callback);
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
                originalIntent, callback);
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
            cleanupAttempt(pluginId);
            handleFailure(compileResult.errorMessage, originalIntent, callback);
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
            cleanupAttempt(pluginId);
            handleFailure("DEX transformation failed: " + transformResult.errorMessage, originalIntent, callback);
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
            cleanupAttempt(pluginId);
            handleFailure("Plugin load failed: " + loadResult.errorMessage, originalIntent, callback);
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
     */
    private void handleFailure(String errorMessage, String originalIntent, Callback callback) {
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
        requestLLM(buildRetryPrompt(originalIntent, errorMessage, previousSource, history),
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

    /** Writes the full active info (id / prompt / title) directly. */
    private void saveActivePluginFull(String pluginId, String userIntent, String title) {
        boolean success = pluginPrefs.edit()
            .putString(KEY_ACTIVE_PLUGIN_ID, pluginId)
            .putString(KEY_ACTIVE_PLUGIN_PROMPT, userIntent == null ? "" : userIntent)
            .putString(KEY_ACTIVE_PLUGIN_TITLE, title == null ? "" : title)
            .commit();
        Log.i(TAG, "Persist active plugin full id=" + pluginId + " success=" + success);
    }

    private void clearSavedActivePluginId() {
        boolean success = pluginPrefs.edit()
            .remove(KEY_ACTIVE_PLUGIN_ID)
            .remove(KEY_ACTIVE_PLUGIN_PROMPT)
            .remove(KEY_ACTIVE_PLUGIN_TITLE)
            .remove(KEY_ACTIVE_PLUGIN_FEEDBACK)
            .commit();
        Log.i(TAG, "Clear active plugin id success=" + success);
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
                PluginArchive.Entry entry = archive.remove(pluginId);
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
                    if (curId != null && !curId.trim().isEmpty() && !curId.equals(pluginId)) {
                        archive.addFront(curId, curTitle, curPrompt,
                            System.currentTimeMillis());
                        Log.i(TAG, "Archived previous plugin id=" + curId
                            + " title=" + curTitle);
                    }
                    // The switch target takes over the foreground with its own title and prompt
                    activePluginId = pluginId;
                    activePluginTitle = entry.title;
                    activePluginPrompt = entry.prompt;
                    // Different lineage: clear the in-place feedback replacement marker
                    feedbackReplacementId = null;
                }
                saveActivePluginFull(pluginId, entry.prompt, entry.title);
                // Different app lineage: reset the feedback history
                clearFeedbackHistory();
                registerPendingPlugin(loadResult.plugin, pluginId);
                Log.i(TAG, "Switched to archived plugin: " + pluginId
                    + " title: " + entry.title);
                callback.onPlugin(loadResult.plugin);
            });
        });
    }

    /** Deletes an archive entry and its on-disk files. */
    public void deleteArchivedPlugin(String pluginId) {
        if (pluginId == null || pluginId.trim().isEmpty()) {
            return;
        }
        archive.delete(pluginId);
        loader.unloadPlugin(pluginId);
        compiler.cleanPlugin(pluginId);
        Log.i(TAG, "Deleted archived plugin: " + pluginId);
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
     */
    static String extractJavaCode(String response) {
        Matcher matcher = JAVA_CODE_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // Fall back to ```java ... ``` fences
        matcher = JAVA_CODE_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }
}
