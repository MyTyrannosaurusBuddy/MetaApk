package com.meta.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.meta.app.R;
import com.meta.app.core.MetaEngine;
import com.meta.app.core.Workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Project files dialog: lists the workspace source files of the current app,
 * with a read-only content view per file. Gives the user transparency into
 * what the agent built (the workspace is otherwise invisible).
 *
 * Owns only the dialog UI; the workspace is never modified from here.
 */
public class WorkspaceFileDialogController {

    private final Activity activity;
    private final MetaEngine engine;

    public WorkspaceFileDialogController(Activity activity, MetaEngine engine) {
        this.activity = activity;
        this.engine = engine;
    }

    public void show() {
        if (engine == null) {
            return;
        }
        String lineage = engine.getActivePluginLineage();
        if (lineage == null || lineage.trim().isEmpty()) {
            new AlertDialog.Builder(activity)
                .setTitle(R.string.title_project_files)
                .setMessage(R.string.project_files_no_app)
                .setPositiveButton(R.string.action_cancel, null)
                .show();
            return;
        }
        final Workspace workspace = Workspace.forLineage(activity, lineage);
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(buildContent(dialog, workspace));
        UiUtil.styleDialogWindow(dialog);
        dialog.show();
    }

    private View buildContent(final Dialog dialog, final Workspace workspace) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiUtil.dp(activity, 20), UiUtil.dp(activity, 20),
            UiUtil.dp(activity, 20), UiUtil.dp(activity, 20));

        TextView header = new TextView(activity);
        header.setText(R.string.title_project_files);
        header.setTextSize(16);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(UiUtil.color(activity, R.color.on_background));
        root.addView(header);

        List<String> files = listWorkspaceFiles(workspace);
        if (files.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.project_files_empty);
            empty.setTextSize(14);
            empty.setTextColor(UiUtil.color(activity, R.color.on_surface_variant));
            empty.setPadding(0, UiUtil.dp(activity, 12), 0, 0);
            root.addView(empty);
        } else {
            for (String path : files) {
                root.addView(buildFileRow(dialog, workspace, path));
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        return scroll;
    }

    /** Workspace-relative file paths, sorted (listFiles already sorts). */
    private static List<String> listWorkspaceFiles(Workspace workspace) {
        List<String> files = new ArrayList<>();
        for (String line : workspace.listFiles().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.equals("(empty workspace)")) {
                files.add(trimmed);
            }
        }
        return files;
    }

    private View buildFileRow(final Dialog dialog, final Workspace workspace,
                              final String path) {
        TextView row = new TextView(activity);
        row.setText(path);
        row.setTextSize(13);
        row.setTextColor(UiUtil.color(activity, R.color.accent));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiUtil.dp(activity, 8), 0, UiUtil.dp(activity, 8));
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> showFileContent(workspace, path));
        return row;
    }

    /** Read-only content view for one file. */
    private void showFileContent(Workspace workspace, String path) {
        String content = workspace.readFile(path);
        TextView body = new TextView(activity);
        body.setText(content);
        body.setTextSize(12);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextColor(UiUtil.color(activity, R.color.on_surface_variant));
        body.setPadding(UiUtil.dp(activity, 16), UiUtil.dp(activity, 12),
            UiUtil.dp(activity, 16), UiUtil.dp(activity, 12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        new AlertDialog.Builder(activity)
            .setTitle(path)
            .setView(scroll)
            .setPositiveButton(R.string.action_cancel, null)
            .show();
    }
}
