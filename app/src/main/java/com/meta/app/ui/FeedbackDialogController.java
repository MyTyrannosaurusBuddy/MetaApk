package com.meta.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.meta.app.R;

/**
 * Feedback dialog: collects the problem description and hands it to the host,
 * which owns the regeneration state machine.
 */
public class FeedbackDialogController {

    public interface Host {
        boolean isBusy();
        boolean hasActivePlugin();
        void submitFeedback(String feedback);
    }

    private final Activity activity;
    private final Host host;

    public FeedbackDialogController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void show() {
        if (host.isBusy()) {
            Toast.makeText(activity, R.string.status_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!host.hasActivePlugin()) {
            Toast.makeText(activity, R.string.feedback_empty_app, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(activity);
        input.setHint(R.string.feedback_hint);
        input.setMaxLines(4);
        input.setPadding(UiUtil.dp(activity, 16), UiUtil.dp(activity, 12),
            UiUtil.dp(activity, 16), UiUtil.dp(activity, 12));

        FrameLayout wrap = new FrameLayout(activity);
        wrap.setPadding(UiUtil.dp(activity, 24), UiUtil.dp(activity, 16),
            UiUtil.dp(activity, 24), 0);
        wrap.addView(input);

        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_feedback)
            .setView(wrap)
            .setPositiveButton(R.string.feedback_submit, (d, w) -> {
                String feedback = input.getText().toString().trim();
                if (!feedback.isEmpty()) {
                    host.submitFeedback(feedback);
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }
}
