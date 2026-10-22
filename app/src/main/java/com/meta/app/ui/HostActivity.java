package com.meta.app.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.os.Bundle;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.meta.app.R;
import com.meta.app.bridge.ApiBridge;
import com.meta.app.config.LlmConfig;
import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;
import com.meta.app.core.PluginContract;
import com.meta.app.core.PluginRuntimeGuard;
import com.meta.app.inject.ActivityInjector;

import java.util.List;

/**
 * Host shell Activity - provides the input box + the plugin display area.
 * Contains no business logic itself; it only:
 * 1. Receives user input
 * 2. Delegates processing to MetaEngine
 * 3. Injects the dynamic plugin into pluginContainer
 */
public class HostActivity extends AppCompatActivity {

    private static final String TAG = "HostActivity";

    private FrameLayout contentArea;
    private FrameLayout pluginContainer;
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
    private String lastIntent;
    /** Whether a generation is in progress (drives the Generate/Stop button). */
    private boolean isGenerating;
    /** Whether the current app was swept into the archive animation at generation start (restored on failure/cancel). */
    private boolean pluginDetachedForGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildHostLayout();

        apiBridge = new ApiBridge(this);
        injector = new ActivityInjector(this);
        metaEngine = new MetaEngine(this);
        installBackPressHandler();
        // Plugin runtime errors must not crash; they are uniformly fed back to the model for regeneration
        PluginRuntimeGuard.setListener(this::onPluginRuntimeError);

        if (!tryRestoreActivePlugin()) {
            showInitialStatus();
        }
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
        if (injector != null && injector.getCurrentPlugin() == null && !isGenerating) {
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

        // Feedback-revision entry (edit icon), placed left of the archive button
        ImageButton feedbackBtn = new ImageButton(this);
        feedbackBtn.setImageResource(android.R.drawable.ic_menu_edit);
        feedbackBtn.setBackgroundColor(0x00000000);
        feedbackBtn.setColorFilter(color(R.color.on_surface_variant));
        feedbackBtn.setContentDescription(getString(R.string.title_feedback));
        feedbackBtn.setOnClickListener(v -> showFeedbackDialog());
        topBar.addView(feedbackBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // App archive entry (stacked-layers icon), placed left of the settings button
        archiveBtn = new ImageButton(this);
        archiveBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        archiveBtn.setBackgroundColor(0x00000000);
        archiveBtn.setColorFilter(color(R.color.on_surface_variant));
        archiveBtn.setContentDescription(getString(R.string.title_archive));
        archiveBtn.setOnClickListener(v -> showArchiveDialog());
        topBar.addView(archiveBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton settingsBtn = new ImageButton(this);
        settingsBtn.setImageResource(android.R.drawable.ic_menu_manage);
        settingsBtn.setBackgroundColor(0x00000000);
        settingsBtn.setColorFilter(color(R.color.on_surface_variant));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        topBar.addView(settingsBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

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

        // Top layer: plugin injection area, hidden initially, shown after a successful injection
        pluginContainer = new FrameLayout(this);
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
            if (isGenerating) {
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
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * Switches the button to the "stop" state.
     */
    private void setGenerating(boolean generating) {
        isGenerating = generating;
        sendBtn.setText(getString(generating ? R.string.action_stop : R.string.action_generate));
        sendBtn.setTextColor(color(R.color.on_primary));
        sendBtn.setBackgroundColor(color(generating ? R.color.error : R.color.user_bubble));
        sendBtn.setEnabled(true);
    }

    /**
     * User pressed the stop button.
     */
    private void onCancel() {
        if (!isGenerating) return;
        setGenerating(false);
        progressBar.setVisibility(View.GONE);
        setStatusText(getString(R.string.status_cancelled), R.color.accent);
        if (metaEngine != null) {
            metaEngine.cancel();
        }
        // After cancelling, restore the app that was swept into the archive animation
        restoreDetachedPlugin();
    }

    private void onUserInput() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        LlmConfig config = new LlmConfig(this);
        if (!config.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        lastIntent = text;
        inputField.setText("");
        setGenerating(true);
        progressBar.setVisibility(View.VISIBLE);
        logText.setText("");
        logScroll.setVisibility(View.VISIBLE);

        // When an app is displayed: shrink it into the archive button and hide it, exposing the underlying generation progress
        detachPluginForRegeneration(true);

        metaEngine.process(text, createGenerationCallback());
    }

    /**
     * At generation/rewrite start: shrinks the currently displayed app into the archive button and hides it,
     * exposing the underlying log area to show the generation progress.
     *
     * @param willRegenerate whether a regeneration follows (drives restoring the old app on failure/cancel)
     */
    private void detachPluginForRegeneration(boolean willRegenerate) {
        if (injector == null || injector.getCurrentPlugin() == null) {
            return;
        }
        pluginDetachedForGeneration = willRegenerate;
        animatePluginToArchive(null);
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
                    pluginContainer.setVisibility(View.GONE);
                    // If a new plugin was injected early during the animation, keep the new content instead of destroying it
                    if (injector != null && injector.getCurrentPlugin() == current) {
                        injector.destroy();
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
            public void onLlmToken(String token) {
                logText.append(token);
                logScroll.fullScroll(View.FOCUS_DOWN);
            }

            @Override
            public void onLlmReset() {
                // After falling back to the backup model, discard the primary model's partial output
                logText.setText("");
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
        };
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
            metaEngine.retry(intent, errorSummary, createGenerationCallback());
        });
    }

    /**
     * User feedback revision: describes the problem (e.g. "1+1 shows Error"),
     * and feeds it back to the model for regeneration together with the original prompt and the previous complete code.
     */
    private void showFeedbackDialog() {
        if (injector == null || injector.getCurrentPlugin() == null || metaEngine == null) {
            Toast.makeText(this, R.string.feedback_empty_app, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint(R.string.feedback_hint);
        input.setMaxLines(4);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(16), dp(24), 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
            .setTitle(R.string.title_feedback)
            .setView(wrap)
            .setPositiveButton(R.string.feedback_submit, (d, w) -> {
                String feedback = input.getText().toString().trim();
                if (feedback.isEmpty()) {
                    return;
                }
                submitFeedback(feedback);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void submitFeedback(String feedback) {
        String intent = metaEngine.getActivePluginPrompt();
        if (intent == null || intent.trim().isEmpty()) {
            setStatusText(getString(R.string.status_plugin_runtime_error), R.color.error);
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
        if (metaEngine == null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.setContentView(buildArchiveContentView(dialog));
        dialog.show();
    }

    private View buildArchiveContentView(final Dialog dialog) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView header = new TextView(this);
        header.setText(R.string.title_archive);
        header.setTextSize(16);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(color(R.color.on_background));
        root.addView(header);

        List<PluginArchive.Entry> entries = metaEngine.getArchivedPlugins();
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.archive_empty);
            empty.setTextSize(14);
            empty.setTextColor(color(R.color.on_surface_variant));
            empty.setPadding(0, dp(12), 0, 0);
            root.addView(empty);
        } else {
            for (PluginArchive.Entry entry : entries) {
                root.addView(buildArchiveRow(dialog, entry));
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private View buildArchiveRow(final Dialog dialog, final PluginArchive.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText((entry.title == null || entry.title.trim().isEmpty())
            ? entry.id : entry.title);
        title.setTextSize(15);
        title.setTextColor(color(R.color.on_background));
        titleArea.addView(title);

        if (entry.prompt != null && !entry.prompt.trim().isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(entry.prompt);
            subtitle.setTextSize(11);
            subtitle.setTextColor(color(R.color.on_surface_variant));
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleArea.addView(subtitle);
        }

        titleArea.setOnClickListener(v -> {
            dialog.dismiss();
            switchToArchived(entry);
        });
        row.addView(titleArea);

        row.addView(buildArchiveAction(getString(R.string.archive_move_up),
            android.R.drawable.arrow_up_float,
            v -> {
                metaEngine.moveArchivedPlugin(entry.id, -1);
                dialog.setContentView(buildArchiveContentView(dialog));
            }));
        row.addView(buildArchiveAction(getString(R.string.archive_move_down),
            android.R.drawable.arrow_down_float,
            v -> {
                metaEngine.moveArchivedPlugin(entry.id, 1);
                dialog.setContentView(buildArchiveContentView(dialog));
            }));
        row.addView(buildArchiveAction(getString(R.string.archive_delete),
            android.R.drawable.ic_menu_delete,
            v -> confirmDeleteArchived(dialog, entry)));

        return row;
    }

    private ImageButton buildArchiveAction(String contentDesc, int iconRes,
                                           View.OnClickListener listener) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(iconRes);
        btn.setBackgroundColor(0x00000000);
        btn.setColorFilter(color(R.color.on_surface_variant));
        btn.setContentDescription(contentDesc);
        btn.setOnClickListener(listener);
        btn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return btn;
    }

    private void confirmDeleteArchived(final Dialog dialog, final PluginArchive.Entry entry) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.archive_delete)
            .setMessage(getString(R.string.archive_delete_confirm, entry.title))
            .setPositiveButton(R.string.action_delete, (d, w) -> {
                if (metaEngine != null) {
                    metaEngine.deleteArchivedPlugin(entry.id);
                }
                dialog.setContentView(buildArchiveContentView(dialog));
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /**
     * Switches to an archived app: after a successful load the swap happens in place (current app goes into the archive).
     */
    private void switchToArchived(final PluginArchive.Entry entry) {
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
                progressBar.setVisibility(View.GONE);
                setStatusText(message, R.color.error);
            }
        });
    }

    private void attachPlugin(PluginContract plugin, MetaEngine.Callback retryCallback,
                              boolean restoring) {
        progressBar.setVisibility(View.GONE);
        setGenerating(false);
        setStatusText(getString(R.string.status_plugin_loaded, plugin.getTitle()), R.color.success);
        logScroll.setVisibility(View.GONE);
        pluginContainer.setVisibility(View.VISIBLE);

        try {
            injector.inject(plugin, apiBridge);
            PluginRuntimeGuard.resetContainedCount();
            pluginDetachedForGeneration = false;
            if (metaEngine != null) {
                // Only newly generated apps use this input as their prompt; restore/switch keep the app's own prompt
                metaEngine.markPluginActive(plugin, restoring ? null : lastIntent);
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
        if (apiBridge != null) apiBridge.release();
        if (metaEngine != null) metaEngine.destroy();
    }

    private void setStatusText(CharSequence text, int colorRes) {
        statusText.setText(text);
        statusText.setTextColor(color(colorRes));
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }
}
