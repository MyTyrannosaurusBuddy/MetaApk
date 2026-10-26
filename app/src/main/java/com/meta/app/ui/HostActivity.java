package com.meta.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.*;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.meta.app.R;
import com.meta.app.bridge.ApiBridge;
import com.meta.app.config.LlmConfig;
import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;
import com.meta.app.core.PluginContract;
import com.meta.app.core.PluginDataContext;
import com.meta.app.core.PluginRuntimeGuard;
import com.meta.app.core.SharePackage;
import com.meta.app.inject.ActivityInjector;

import java.io.File;
import java.io.InputStream;

/**
 * Host shell Activity - provides the input box + the plugin display area.
 * Contains no business logic itself; it only:
 * 1. Receives user input
 * 2. Delegates processing to MetaEngine
 * 3. Injects the dynamic plugin into pluginContainer
 */
public class HostActivity extends AppCompatActivity
    implements ArchiveDialogController.Host {

    private static final String TAG = "HostActivity";

    private FrameLayout contentArea;
    private SafePluginContainer pluginContainer;
    /** The real canvas size (content area measured with the IME closed); the
     *  agent preview uses it so what the model sees is what the user gets. */
    private int cachedCanvasWidth;
    private int cachedCanvasHeight;
    private TextView statusText;
    private EditText inputField;
    private Button sendBtn;
    private TextView titleText;
    private ProgressBar progressBar;
    private ScrollView logScroll;
    private TextView logText;
    private ImageButton archiveBtn;

    private ActivityInjector injector;
    private ApiBridge apiBridge;
    private MetaEngine metaEngine;
    private ArchiveDialogController archiveController;
    private WorkspaceFileDialogController projectFilesController;
    private PreviewScreen previewScreen;
    private String lastIntent;
    /**
     * Input mode: false = the input describes a brand-new app (Generate),
     * true = the input improves the current app in place (Revise). The + key
     * resets to the new-app mode; every successful attach switches to the
     * revise mode.
     */
    private boolean improveMode;

    /**
     * High-level host state machine. All user-initiated operations check the
     * current state, so generation/restore/switch flows can never interleave.
     */
    private enum HostState { IDLE, GENERATING, RESTORING, SWITCHING }

    private HostState state = HostState.IDLE;
    /** Whether the current app was swept into the archive animation at generation start (restored on failure/cancel). */
    private boolean pluginDetachedForGeneration;
    private boolean agentPreviewActive;
    private final StringBuilder previewErrors = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildHostLayout();

        apiBridge = new ApiBridge(this);
        apiBridge.setPermissionRequester(this::requestPluginPermissions);
        injector = new ActivityInjector(this);
        metaEngine = new MetaEngine(this);
        archiveController = new ArchiveDialogController(this, metaEngine, this);
        projectFilesController = new WorkspaceFileDialogController(this, metaEngine);
        installBackPressHandler();
        // Plugin runtime errors must not crash; they are uniformly fed back to the model for regeneration
        PluginRuntimeGuard.setListener(this::onPluginRuntimeError);

        if (!tryRestoreActivePlugin()) {
            showInitialStatus();
        }
        resumePendingTaskIfAny();
        handleSharedAppIntent(getIntent());
    }

    /** A received .metaapk share joins the archive directly - no install, ever. */
    private void handleSharedAppIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
            || intent.getData() == null || metaEngine == null) {
            return;
        }
        try {
            InputStream stream = getContentResolver().openInputStream(intent.getData());
            if (stream == null) {
                return;
            }
            setStatusText(getString(R.string.status_importing), R.color.accent);
            SharePackage.importInto(this, stream, new SharePackage.ImportCallback() {
                @Override
                public void onImported(PluginArchive.Entry entry) {
                    setStatusText(getString(R.string.status_share_imported, entry.title),
                        R.color.accent);
                    Toast.makeText(HostActivity.this,
                        getString(R.string.status_share_imported, entry.title),
                        Toast.LENGTH_LONG).show();
                    showArchiveDialog();
                }

                @Override
                public void onError(String message) {
                    setStatusText(getString(R.string.status_share_import_failed, message),
                        R.color.error);
                    Toast.makeText(HostActivity.this,
                        getString(R.string.status_share_import_failed, message),
                        Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            setStatusText(getString(R.string.status_share_import_failed,
                e.getClass().getSimpleName() + ": " + e.getMessage()), R.color.error);
        }
    }

    /**
     * Resumes an interrupted generation/feedback/revision task that survived
     * a process death, so no user work is silently lost.
     */
    private void resumePendingTaskIfAny() {
        if (metaEngine == null || !metaEngine.hasPendingTask()) {
            return;
        }
        Log.i(TAG, "Pending task found, resuming it");
        detachPluginForRegeneration(true);
        logText.setText("");
        logScroll.setVisibility(View.VISIBLE);
        setGenerating(true);
        progressBar.setVisibility(View.VISIBLE);
        setStatusText(getString(R.string.status_resuming_task), R.color.accent);
        metaEngine.resumePendingTask(createGenerationCallback());
    }

    /**
     * Notification taps / MainActivity forwarding may reuse an existing instance (onCreate will not run again);
     * in that case, if no plugin is being displayed, retry a restore to avoid a bare host screen.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(TAG, "onNewIntent, hasPlugin="
            + (injector != null && injector.getCurrentPlugin() != null));
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            handleSharedAppIntent(intent);
            return;
        }
        if (injector != null && injector.getCurrentPlugin() == null && state == HostState.IDLE) {
            if (!tryRestoreActivePlugin()) {
                showInitialStatus();
            }
        }
    }

    private void showInitialStatus() {
        LlmConfig config = new LlmConfig(this);
        if (!config.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            setStatusText(getString(R.string.status_config_required), R.color.accent);
        } else {
            setStatusText(getString(R.string.status_prompt_example), R.color.accent);
        }
    }

    private boolean tryRestoreActivePlugin() {
        if (metaEngine == null || !metaEngine.hasRestorablePlugin()) {
            Log.i(TAG, "Skip restore: restorable plugin not available");
            return false;
        }
        if (isBusy()) {
            Log.i(TAG, "Skip restore: host is busy");
            return false;
        }
        state = HostState.RESTORING;

        Log.i(TAG, "Start restoring active plugin");
        progressBar.setVisibility(View.VISIBLE);
        logText.setText("");
        logScroll.setVisibility(View.VISIBLE);
        pluginContainer.setVisibility(View.GONE);
        setStatusText(getString(R.string.status_restoring_app), R.color.accent);
        metaEngine.restoreActivePlugin(new MetaEngine.RestoreCallback() {
            @Override
            public void onStatusUpdate(String status) {
                setStatusText(status, R.color.accent);
            }

            @Override
            public void onPlugin(PluginContract plugin) {
                attachPlugin(plugin, null, true);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Restore failed: " + message);
                state = HostState.IDLE;
                progressBar.setVisibility(View.GONE);
                logScroll.setVisibility(View.VISIBLE);
                showInitialStatus();
                setStatusText(message, R.color.error);
            }
        });
        return true;
    }

    /**
     * Builds the host UI in code (no XML).
     */
    private void buildHostLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.background));

        // === Top bar ===
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        topBar.setBackgroundColor(color(R.color.surface));

        titleText = new TextView(this);
        titleText.setText(R.string.title_meta_app);
        titleText.setTextSize(20);
        titleText.setTextColor(color(R.color.on_background));
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(titleText);

        // New-app entry (plus icon): switches the input back to describing a
        // brand-new app. Placed left of the archive button.
        ImageButton newAppBtn = new ImageButton(this);
        newAppBtn.setImageResource(android.R.drawable.ic_input_add);
        newAppBtn.setBackground(circleBackground(R.color.icon_feedback));
        newAppBtn.setColorFilter(android.graphics.Color.WHITE);
        newAppBtn.setContentDescription(getString(R.string.title_new_app));
        newAppBtn.setOnClickListener(v -> onNewAppRequested());
        topBar.addView(newAppBtn, iconButtonParams(0));

        // App archive entry (stacked-layers icon), placed left of the settings button
        archiveBtn = new ImageButton(this);
        archiveBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        archiveBtn.setBackground(circleBackground(R.color.icon_archive));
        archiveBtn.setColorFilter(android.graphics.Color.WHITE);
        archiveBtn.setContentDescription(getString(R.string.title_archive));
        archiveBtn.setOnClickListener(v -> showArchiveDialog());
        topBar.addView(archiveBtn, iconButtonParams(1));

        ImageButton filesBtn = new ImageButton(this);
        filesBtn.setImageResource(android.R.drawable.ic_menu_agenda);
        filesBtn.setBackground(circleBackground(R.color.icon_archive));
        filesBtn.setColorFilter(android.graphics.Color.WHITE);
        filesBtn.setContentDescription(getString(R.string.title_project_files));
        filesBtn.setOnClickListener(v -> projectFilesController.show());
        topBar.addView(filesBtn, iconButtonParams(2));

        ImageButton settingsBtn = new ImageButton(this);
        settingsBtn.setImageResource(android.R.drawable.ic_menu_manage);
        settingsBtn.setBackground(circleBackground(R.color.icon_settings));
        settingsBtn.setColorFilter(android.graphics.Color.WHITE);
        settingsBtn.setContentDescription(getString(R.string.title_settings));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        topBar.addView(settingsBtn, iconButtonParams(3));

        root.addView(topBar);

        // === Progress bar ===
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        // === Status text ===
        statusText = new TextView(this);
        statusText.setTextColor(color(R.color.accent));
        statusText.setTextSize(13);
        statusText.setPadding(dp(16), dp(8), dp(16), dp(8));
        statusText.setBackgroundColor(color(R.color.surface));
        root.addView(statusText);

        // === Plugin display container: log area (bottom layer) and plugin content area (top layer) stacked ===
        contentArea = new FrameLayout(this);
        contentArea.setBackgroundColor(color(R.color.background));
        LinearLayout.LayoutParams containerParams =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentArea.setLayoutParams(containerParams);

        // Bottom layer: log area shown by default (generation progress / streamed output / error summaries)
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(color(R.color.assistant_bubble));
        logText = new TextView(this);
        logText.setTextColor(color(R.color.on_surface_variant));
        logText.setTextSize(12);
        logText.setPadding(dp(16), dp(8), dp(16), dp(8));
        logScroll.addView(logText);
        contentArea.addView(logScroll);

        // Top layer: plugin injection area, hidden initially, shown after a
        // successful injection. The safe container keeps plugin exceptions in
        // measure/layout/draw at the host boundary (see SafePluginContainer).
        pluginContainer = new SafePluginContainer(this);
        pluginContainer.setBackgroundColor(color(R.color.background));
        pluginContainer.setVisibility(View.GONE);
        contentArea.addView(pluginContainer);

        root.addView(contentArea);

        // === Divider ===
        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.divider));
        root.addView(divider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // === Bottom input area ===
        LinearLayout inputArea = new LinearLayout(this);
        inputArea.setOrientation(LinearLayout.HORIZONTAL);
        inputArea.setGravity(Gravity.CENTER_VERTICAL);
        inputArea.setPadding(dp(12), dp(8), dp(12), dp(8));
        inputArea.setBackgroundColor(color(R.color.surface));

        inputField = new EditText(this);
        inputField.setHint(R.string.hint_user_intent);
        inputField.setTextColor(color(R.color.on_background));
        inputField.setHintTextColor(color(R.color.on_surface_variant));
        inputField.setBackgroundColor(color(R.color.input_bg));
        inputField.setPadding(dp(16), dp(12), dp(16), dp(12));
        inputField.setMaxLines(3);
        inputField.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputArea.addView(inputField);

        sendBtn = new Button(this);
        sendBtn.setText(R.string.action_generate);
        sendBtn.setAllCaps(false);
        sendBtn.setTextColor(color(R.color.on_primary));
        sendBtn.setBackgroundColor(color(R.color.user_bubble));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMarginStart(dp(8));
        sendBtn.setLayoutParams(btnParams);
        sendBtn.setOnClickListener(v -> {
            if (isGenerating()) {
                onCancel();
            } else {
                onUserInput();
            }
        });
        inputArea.addView(sendBtn);

        root.addView(inputArea);

        setContentView(root);
        applyWindowInsets(root);
    }

    /**
     * Handles system-bar and IME insets.
     *
     * Since targetSdk 35 the system enforces edge-to-edge, and windowSoftInputMode="adjustResize" no longer shrinks the window;
     * the insets must be consumed manually, otherwise:
     *   1) content slides under the status bar (the title gets covered)
     *   2) the input box is covered by the keyboard once it pops up
     */
    private void applyWindowInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            if (ime.bottom == 0) {
                // The IME is closed: the content area now has its true
                // "available canvas" size, which is what the agent preview
                // must match. Read it after the current layout pass.
                v.post(this::cacheCanvasSize);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** Caches the real canvas size (content area with the IME closed). */
    private void cacheCanvasSize() {
        if (contentArea != null && contentArea.getWidth() > 0 && contentArea.getHeight() > 0) {
            cachedCanvasWidth = contentArea.getWidth();
            cachedCanvasHeight = contentArea.getHeight();
        }
    }

    /**
     * Switches the button to the "stop" state, and moves the host state
     * machine into GENERATING / back to IDLE.
     */
    private void setGenerating(boolean generating) {
        state = generating ? HostState.GENERATING : HostState.IDLE;
        // Long agent sessions must not be interrupted by the screen sleeping:
        // hold the window-level keep-screen-on flag while work is in flight.
        if (generating) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        sendBtn.setText(getString(generating ? R.string.action_stop
            : (improveMode ? R.string.action_improve : R.string.action_generate)));
        sendBtn.setTextColor(color(R.color.on_primary));
        sendBtn.setBackgroundColor(color(generating ? R.color.error : R.color.user_bubble));
        sendBtn.setEnabled(true);
    }

    private boolean isGenerating() {
        return state == HostState.GENERATING;
    }

    private boolean isBusy() {
        return state != HostState.IDLE;
    }

    /**
     * User pressed the stop button.
     */
    private void onCancel() {
        if (!isGenerating()) return;
        setGenerating(false);
        progressBar.setVisibility(View.GONE);
        setStatusText(getString(R.string.status_cancelled), R.color.accent);
        if (metaEngine != null) {
            metaEngine.cancel();
            // Explicit user cancellation: the pending task must not be resumed later
            metaEngine.clearPendingTask();
        }
        // After cancelling, restore the app that was swept into the archive animation
        restoreDetachedPlugin();
    }

    private void onUserInput() {
        if (isBusy()) {
            return;
        }
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        LlmConfig config = new LlmConfig(this);
        if (!config.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        lastIntent = text;
        inputField.setText("");
        // Submitting means the user is done typing: collapse the keyboard so
        // the generation progress and the preview are not covered by it (an
        // open IME also shrinks the content area, distorting the agent's
        // preview window).
        inputField.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(inputField.getWindowToken(), 0);
        }
        if (improveMode) {
            // The input improves the current app in place (the old feedback
            // flow, now the same natural gesture as generating).
            submitFeedback(text);
            return;
        }

        setGenerating(true);
        progressBar.setVisibility(View.VISIBLE);
        logText.setText("");
        logScroll.setVisibility(View.VISIBLE);

        // When an app is displayed: shrink it into the archive button and hide it, exposing the underlying generation progress
        detachPluginForRegeneration(true);

        metaEngine.process(text, createGenerationCallback());
    }

    /** The + key: the current app shrinks into the archive and the input goes
     *  back to describing a brand-new app. The app stays in the archive (it
     *  was listed when it became active), so it remains restorable from the
     *  library; a cancelled/failed generation restores it automatically. */
    private void onNewAppRequested() {
        if (isBusy()) {
            return;
        }
        improveMode = false;
        inputField.setText("");
        inputField.setHint(R.string.hint_user_intent);
        sendBtn.setText(R.string.action_generate);
        setStatusText(getString(R.string.status_new_app), R.color.accent);
        // The shrink-into-archive motion runs first, uninterrupted; the
        // keyboard opens only after the app visibly lands in the archive.
        detachPluginForRegeneration(true, () -> {
            inputField.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(inputField,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /** The input mode follows the current app: with an app shown, typing means revising it. */
    private void applyImproveMode() {
        improveMode = true;
        inputField.setHint(R.string.hint_improve);
        sendBtn.setText(R.string.action_improve);
    }

    private void applyNewAppMode() {
        improveMode = false;
        inputField.setHint(R.string.hint_user_intent);
        sendBtn.setText(R.string.action_generate);
    }

    /**
     * At generation/rewrite start: shrinks the currently displayed app into the archive button and hides it,
     * exposing the underlying log area to show the generation progress.
     *
     * @param willRegenerate whether a regeneration follows (drives restoring the old app on failure/cancel)
     */
    private void detachPluginForRegeneration(boolean willRegenerate) {
        detachPluginForRegeneration(willRegenerate, null);
    }

    /**
     * Same as above; {@code onEnd} runs after the shrink animation completes
     * (or immediately when there is no app to shrink). Used by the + key so
     * the keyboard opens only AFTER the app visibly lands in the archive -
     * the motion stays continuous instead of being cut by an IME relayout.
     */
    private void detachPluginForRegeneration(boolean willRegenerate, Runnable onEnd) {
        if (injector == null || injector.getCurrentPlugin() == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        pluginDetachedForGeneration = willRegenerate;
        animatePluginToArchive(onEnd);
    }

    /**
     * Archive animation: the current app view shrinks, moves to the top-right archive button and fades out.
     */
    private void animatePluginToArchive(final Runnable onEnd) {
        final PluginContract current = (injector != null) ? injector.getCurrentPlugin() : null;
        if (current == null || pluginContainer == null || archiveBtn == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }

        int[] btnLoc = new int[2];
        archiveBtn.getLocationOnScreen(btnLoc);
        float targetX = btnLoc[0] + archiveBtn.getWidth() / 2f;
        float targetY = btnLoc[1] + archiveBtn.getHeight() / 2f;

        int[] containerLoc = new int[2];
        pluginContainer.getLocationOnScreen(containerLoc);
        float dx = targetX - (containerLoc[0] + pluginContainer.getWidth() / 2f);
        float dy = targetY - (containerLoc[1] + pluginContainer.getHeight() / 2f);

        pluginContainer.animate()
            .translationX(dx)
            .translationY(dy)
            .scaleX(0.05f)
            .scaleY(0.05f)
            .alpha(0f)
            .setDuration(320)
            .setInterpolator(new AccelerateInterpolator(1.6f))
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Reset the transforms so the next injection is unaffected
                    pluginContainer.animate().setListener(null);
                    pluginContainer.setTranslationX(0);
                    pluginContainer.setTranslationY(0);
                    pluginContainer.setScaleX(1);
                    pluginContainer.setScaleY(1);
                    pluginContainer.setAlpha(1);
                    // If a new plugin was injected early during the animation (fast paths
                    // like the generation cache), keep the new content visible instead of
                    // hiding/destroying it.
                    if (injector != null && injector.getCurrentPlugin() == current) {
                        pluginContainer.setVisibility(View.GONE);
                        injector.destroy();
                    } else {
                        pluginContainer.setVisibility(View.VISIBLE);
                    }
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }
            })
            .start();
    }

    /**
     * After a generation failure / user cancellation, restores the app that was swept into the archive animation.
     */
    private void restoreDetachedPlugin() {
        if (!pluginDetachedForGeneration) {
            return;
        }
        pluginDetachedForGeneration = false;
        if (metaEngine != null && injector != null && injector.getCurrentPlugin() == null) {
            if (!tryRestoreActivePlugin()) {
                showInitialStatus();
            }
        }
    }

    /**
     * Callback shared by generation and retries: streams output to the log area and injects the plugin on success.
     */
    private MetaEngine.Callback createGenerationCallback() {
        return new MetaEngine.Callback() {
            @Override
            public void onStatusUpdate(String status) {
                setStatusText(status, R.color.accent);
            }

            @Override
            public void onLlmReset() {
                // After falling back to the backup model, discard partial output.
                logText.setText("");
            }

            @Override
            public void onAgentActivity(String line) {
                logText.append("\n" + line);
                capProgressLog();
                logScroll.fullScroll(View.FOCUS_DOWN);
            }

            @Override
            public void onPlugin(PluginContract plugin) {
                attachPlugin(plugin, this, false);
            }

            @Override
            public void onError(String message) {
                progressBar.setVisibility(View.GONE);
                setGenerating(false);
                setStatusText(getString(R.string.status_plugin_error, message), R.color.error);
                restoreDetachedPlugin();
            }

            @Override
            public void onPreviewPlugin(PluginContract plugin, MetaEngine.PreviewReady ready) {
                closeAgentPreview();
                try {
                    previewErrors.setLength(0);
                    agentPreviewActive = true;
                    // The preview virtual display uses the REAL available
                    // canvas size (content area measured with the IME
                    // closed), so the preview is exactly what the app will
                    // get when it is attached - never the distorted
                    // IME-shrunk area, never the full screen minus nothing.
                    cacheCanvasSize();
                    android.graphics.Point screen = new android.graphics.Point();
                    getWindowManager().getDefaultDisplay().getRealSize(screen);
                    int width = cachedCanvasWidth > 0 ? cachedCanvasWidth : screen.x;
                    int height = cachedCanvasHeight > 0 ? cachedCanvasHeight : screen.y;
                    if (previewScreen == null) {
                        previewScreen = new PreviewScreen(HostActivity.this);
                    }
                    previewScreen.start(HostActivity.this, width, height);
                    android.content.Context previewContext = previewScreen.getContext();
                    // Plugin code reading bridge.getContext() (dialogs, toasts)
                    // must land on the virtual display during previews, never
                    // on the physical screen.
                    if (apiBridge != null) {
                        apiBridge.setActiveContext(previewContext);
                    }
                    String lineage = (metaEngine != null)
                        ? metaEngine.getPluginLineage(plugin) : null;
                    if (lineage == null || lineage.isEmpty()) {
                        lineage = "lineage_default";
                    }
                    File dataDir = new File(getFilesDir(), "plugin_data/" + lineage);
                    File cacheDir = new File(getCacheDir(), "plugin_data/" + lineage);
                    if (apiBridge != null) {
                        apiBridge.setScopedFilesDir(dataDir);
                    }
                    android.content.Context scoped = new PluginDataContext(
                        previewContext, lineage, dataDir, cacheDir);
                    injector.previewInject(plugin, apiBridge,
                        previewScreen.getContainer(), scoped);
                    Log.i(TAG, "PREVIEW-START virtual display");
                } catch (Throwable t) {
                    ready.done("Preview inject failed: " + PluginRuntimeGuard.summarize(t), null);
                    closeAgentPreview();
                    return;
                }
                reportAgentPreview(ready);
            }

            @Override
            public void onInspectPreview(String action, com.google.gson.JsonObject args, MetaEngine.PreviewReady ready) {
                if (!agentPreviewActive) {
                    ready.done("Error: no live preview; call run_app", null);
                    return;
                }
                try {
                    com.meta.app.core.PreviewUi.perform(previewScreen.getContainer(), action, args);
                    reportAgentPreview(ready);
                } catch (Throwable t) {
                    ready.done("Interaction failed: " + PluginRuntimeGuard.summarize(t), null);
                }
            }

            @Override
            public void onClosePreview() {
                closeAgentPreview();
            }
        };
    }

    private void closeAgentPreview() {
        if (!agentPreviewActive) return;
        agentPreviewActive = false;
        injector.destroy();
        if (previewScreen != null) {
            previewScreen.stop();
        }
        // The bridge goes back to the physical host context.
        if (apiBridge != null) {
            apiBridge.setActiveContext(this);
        }
        pluginContainer.setVisibility(View.GONE);
        logScroll.setVisibility(View.VISIBLE);
        titleText.setText(R.string.title_meta_app);
    }

    private void reportAgentPreview(MetaEngine.PreviewReady ready) {
        // Yield the main thread for layout, draw and posted plugin work.
        pluginContainer.postDelayed(() -> {
            if (!agentPreviewActive || isDestroyed() || previewScreen == null
                    || previewScreen.getContainer() == null) {
                ready.done("Preview closed", null);
                return;
            }
            String dump = com.meta.app.core.PreviewUi.dump(previewScreen.getContainer());
            // Report the CANVAS size (what the app actually gets), not the
            // physical screen: the host chrome takes part of the screen and
            // the preview virtual display matches the canvas exactly.
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int canvasW = cachedCanvasWidth > 0 ? cachedCanvasWidth : dm.widthPixels;
            int canvasH = cachedCanvasHeight > 0 ? cachedCanvasHeight : dm.heightPixels;
            int dpW = Math.round(canvasW / dm.density);
            int dpH = Math.round(canvasH / dm.density);
            dump = "App canvas: " + canvasW + "x" + canvasH + " px (density "
                + dm.density + ", " + dpW + "x" + dpH + " dp). UI dump sizes are px.\n" + dump;
            if (previewErrors.length() > 0) dump += "\nRuntime errors:\n" + previewErrors;
            String screenshot = null;
            try {
                android.graphics.Bitmap frame = previewScreen.captureFrame();
                if (frame != null) {
                    Log.i(TAG, "SCREENSHOT-FRAME " + frame.getWidth() + "x" + frame.getHeight());
                    screenshot = com.meta.app.core.PreviewUi.encodeBitmap(frame);
                    frame.recycle();
                } else {
                    // No composed frame yet: fall back to drawing the container.
                    Log.i(TAG, "SCREENSHOT-FALLBACK");
                    screenshot = com.meta.app.core.PreviewUi.screenshot(
                        previewScreen.getContainer());
                }
            } catch (Throwable ignored) { }
            ready.done(dump, screenshot);
        }, 300);
    }

    /**
     * A plugin error at UI-callback time (already contained by PluginRuntimeGuard, the process did not crash):
     * feeds the error summary back to the model for regeneration; if no original prompt can be traced, just shows the error.
     */
    private void onPluginRuntimeError(String errorSummary) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (isBusy()) {
                if (agentPreviewActive && previewErrors.length() < 16 * 1024) {
                    previewErrors.append(errorSummary).append('\n');
                }
                // A late error callback arriving during another flow: don't interleave
                return;
            }
            String intent = null;
            if (metaEngine != null) {
                intent = metaEngine.getActivePluginPrompt();
            }
            if ((intent == null || intent.trim().isEmpty())) {
                intent = lastIntent;
            }
            logScroll.setVisibility(View.VISIBLE);
            logText.setText(errorSummary);
            if (intent == null || intent.trim().isEmpty() || metaEngine == null) {
                // Cannot feed back to the model: sweep away the broken app and just show the error summary
                detachPluginForRegeneration(false);
                setStatusText(getString(R.string.status_plugin_runtime_error), R.color.error);
                return;
            }
            setGenerating(true);
            progressBar.setVisibility(View.VISIBLE);
            setStatusText(getString(R.string.status_error_regenerating), R.color.error);
            detachPluginForRegeneration(true);
            // Persisted + in-place replacement: survives process death and resumes on the next launch
            metaEngine.retryRuntimeError(intent, errorSummary, createGenerationCallback());
        });
    }

    public void submitFeedback(String feedback) {
        String intent = metaEngine.getActivePluginPrompt();
        if (intent == null || intent.trim().isEmpty()) {
            // Honest diagnosis: the app lost its original request info (e.g.
            // delivered before the resume fix), so an automatic revision has
            // nothing to continue from. Never pretend this is a plugin crash.
            setStatusText(getString(R.string.err_feedback_no_intent), R.color.error);
            return;
        }
        setGenerating(true);
        progressBar.setVisibility(View.VISIBLE);
        logText.setText("");
        logScroll.setVisibility(View.VISIBLE);
        setStatusText(getString(R.string.feedback_regenerating), R.color.accent);
        detachPluginForRegeneration(true);
        // feedback() records this item into the history, accumulating across rewrites
        metaEngine.feedback(intent, feedback, createGenerationCallback());
    }

    /**
     * App archive: pops up a list for switching / deleting / reordering.
     */
    private void showArchiveDialog() {
        if (archiveController != null) {
            archiveController.show();
        }
    }

    /**
     * Deletes an app from the archive (library model: the active app is
     * listed and deletable too). When the active app is deleted the host
     * resets to the initial state.
     */
    @Override
    public void deleteArchivedEntry(final PluginArchive.Entry entry) {
        boolean wasActive = metaEngine != null
            && entry.id.equals(metaEngine.getActivePluginId());
        if (wasActive) {
            if (injector != null) {
                injector.destroy();
            }
            if (pluginContainer != null) {
                pluginContainer.removeAllViews();
                pluginContainer.setVisibility(View.GONE);
            }
            titleText.setText(R.string.title_meta_app);
            applyNewAppMode();
        }
        if (metaEngine != null) {
            metaEngine.deleteArchivedPlugin(entry.id);
        }
        if (wasActive) {
            state = HostState.IDLE;
            showInitialStatus();
        }
    }

    /**
     * Shares an archived (or active) app as a .metaapk package (dex + title +
     * prompt + project sources) through the system share sheet. The receiver
     * opens it with MetaApk and it joins their archive directly - no install.
     */
    @Override
    public void shareEntry(final PluginArchive.Entry entry) {
        try {
            File file = SharePackage.create(this, entry);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".share", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/metaapk");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.title_share)));
        } catch (Exception e) {
            setStatusText(getString(R.string.status_share_error,
                e.getClass().getSimpleName() + ": " + e.getMessage()), R.color.error);
        }
    }

    /**
     * Switches to an archived app: after a successful load the swap happens in place (current app goes into the archive).
     */
    @Override
    public void switchToArchived(final PluginArchive.Entry entry) {        if (isBusy()) {
            Toast.makeText(this, R.string.status_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        state = HostState.SWITCHING;
        progressBar.setVisibility(View.VISIBLE);
        setStatusText(getString(R.string.archive_switching, entry.title), R.color.accent);
        metaEngine.switchToArchivedPlugin(entry.id, new MetaEngine.RestoreCallback() {
            @Override
            public void onStatusUpdate(String status) {
                setStatusText(status, R.color.accent);
            }

            @Override
            public void onPlugin(PluginContract plugin) {
                attachPlugin(plugin, null, true);
            }

            @Override
            public void onError(String message) {
                state = HostState.IDLE;
                progressBar.setVisibility(View.GONE);
                setStatusText(message, R.color.error);
            }
        });
    }

    private void attachPlugin(PluginContract plugin, MetaEngine.Callback retryCallback,
                              boolean restoring) {
        progressBar.setVisibility(View.GONE);
        setGenerating(false);
        logScroll.setVisibility(View.GONE);
        pluginContainer.setVisibility(View.VISIBLE);
        // Real injection: the bridge context is the physical host again.
        if (apiBridge != null) {
            apiBridge.setActiveContext(this);
        }
        Log.i(TAG, "ATTACH-REVEAL restoring=" + restoring + " agentPreviewActive=" + agentPreviewActive);

        try {
            // getTitle() is plugin code and may throw: guard it
            String title;
            try {
                title = plugin.getTitle();
                if (title == null || title.trim().isEmpty()) {
                    title = "Plugin";
                }
            } catch (Throwable t) {
                title = "Plugin";
            }
            setStatusText(getString(R.string.status_plugin_loaded, title), R.color.success);
            injector.inject(plugin, apiBridge);
            PluginRuntimeGuard.resetContainedCount();
            pluginDetachedForGeneration = false;
            applyImproveMode();
            if (metaEngine != null) {
                // Only newly generated apps use this input as their prompt; restore/switch keep the app's own prompt
                metaEngine.markPluginActive(plugin, restoring ? null : lastIntent);
                // A generation/feedback/revision completed successfully: the persisted task is resolved
                if (!restoring && retryCallback != null) {
                    metaEngine.clearPendingTask();
                }
            }
        } catch (Throwable t) {
            if (metaEngine != null) {
                if (restoring) {
                    // Restore/switch failure: keep the app files, just unregister the pending plugin; retryable
                    metaEngine.abortPendingPlugin(plugin);
                } else {
                    metaEngine.discardPlugin(plugin);
                }
            }
            String errorSummary = PluginRuntimeGuard.summarize(t);
            pluginContainer.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            logText.setText(errorSummary);
            setStatusText(getString(R.string.status_plugin_runtime_error), R.color.error);
            if (!restoring && retryCallback != null && metaEngine != null) {
                // Prefer this input as the retry intent, otherwise the current foreground app's original prompt (feedback/restore sessions)
                String retryIntent = (lastIntent != null && !lastIntent.trim().isEmpty())
                    ? lastIntent : metaEngine.getActivePluginPrompt();
                if (retryIntent != null && !retryIntent.trim().isEmpty()) {
                    progressBar.setVisibility(View.VISIBLE);
                    setGenerating(true);
                    metaEngine.retry(retryIntent, errorSummary, retryCallback);
                }
            }
        }
    }

    public ViewGroup getPluginContainer() { return pluginContainer; }

    // ---------- Runtime permission requests from plugins ----------

    private static final int REQUEST_CODE_PLUGIN_PERMISSIONS = 4371;
    private ApiBridge.PermissionCallback pendingPermissionCallback;
    private String[] pendingPermissions;

    private void requestPluginPermissions(String[] permissions,
                                          ApiBridge.PermissionCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper())
                .post(() -> requestPluginPermissions(permissions, callback));
            return;
        }
        pendingPermissions = permissions;
        pendingPermissionCallback = callback;
        requestPermissions(permissions, REQUEST_CODE_PLUGIN_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_PLUGIN_PERMISSIONS
                || pendingPermissionCallback == null || pendingPermissions == null) {
            return;
        }
        boolean[] granted = new boolean[pendingPermissions.length];
        for (int i = 0; i < pendingPermissions.length; i++) {
            for (int j = 0; j < permissions.length; j++) {
                if (pendingPermissions[i].equals(permissions[j])) {
                    granted[i] = grantResults[j] == PackageManager.PERMISSION_GRANTED;
                    break;
                }
            }
        }
        ApiBridge.PermissionCallback callback = pendingPermissionCallback;
        pendingPermissionCallback = null;
        pendingPermissions = null;
        callback.onResult(granted);
    }

    /**
     * Builds the lineage-scoped context handed to plugins: standard SDK calls
     * such as getFilesDir() / getSharedPreferences() land in a per-app lineage
     * area, so plugin data persists across feedback revisions.
     */
    public android.content.Context getPluginContext(PluginContract plugin) {
        String lineage = (metaEngine != null) ? metaEngine.getPluginLineage(plugin) : null;
        if (lineage == null || lineage.isEmpty()) {
            lineage = "lineage_default";
        }
        File dataDir = new File(getFilesDir(), "plugin_data/" + lineage);
        File cacheDir = new File(getCacheDir(), "plugin_data/" + lineage);
        // The file bridge follows the same lineage area, so bridge-based
        // storage survives feedback revisions like context storage does.
        if (apiBridge != null) {
            apiBridge.setScopedFilesDir(dataDir);
        }
        return new PluginDataContext(this, lineage, dataDir, cacheDir);
    }

    public void setPluginTitle(String title) {
        titleText.setText(title);
    }

    private void installBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (injector != null && injector.onBackPressed()) {
                    return;
                }
                setEnabled(false);
                try {
                    getOnBackPressedDispatcher().onBackPressed();
                } finally {
                    setEnabled(true);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PluginRuntimeGuard.setListener(null);
        if (injector != null) injector.destroy();
        if (previewScreen != null) previewScreen.stop();
        if (apiBridge != null) apiBridge.release();
        if (metaEngine != null) metaEngine.destroy();
    }

    private void setStatusText(CharSequence text, int colorRes) {
        statusText.setText(text);
        statusText.setTextColor(color(colorRes));
    }

    /**
     * The progress log is a scrolling window: past ~300 lines the oldest
     * entries are folded into one note so a pathological long session cannot
     * grow the TextView without bound.
     */
    private void capProgressLog() {
        CharSequence text = logText.getText();
        if (text == null || text.length() <= 24_000) {
            return;
        }
        String content = text.toString();
        int cut = content.length() - 16_000;
        int lineBreak = content.indexOf('\n', cut);
        if (lineBreak > 0) {
            content = content.substring(lineBreak + 1);
        }
        logText.setText(getString(R.string.progress_log_truncated) + "\n" + content);
    }

    /**
     * Solid circular background for the top-bar icon buttons; white glyphs
     * on deep saturated hues read clearly as buttons on the dark surface.
     */
    private android.graphics.drawable.Drawable circleBackground(int colorRes) {
        android.graphics.drawable.GradientDrawable d =
            new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color(colorRes));
        return d;
    }

    /** 44dp circular icon button with an 8dp gap from its left neighbour. */
    private LinearLayout.LayoutParams iconButtonParams(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        if (index > 0) {
            lp.setMarginStart(dp(8));
        }
        return lp;
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }
}
