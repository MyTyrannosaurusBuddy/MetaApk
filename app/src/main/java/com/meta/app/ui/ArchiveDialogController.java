package com.meta.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.meta.app.R;
import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginArchive;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Archive dialog: list of archived apps with switch / delete / reorder.
 *
 * Owns only the dialog UI. The state-machine side of switching
 * (busy checks, engine call, attach) stays in the host activity.
 */
public class ArchiveDialogController {

    public interface Host {
        void switchToArchived(PluginArchive.Entry entry);
        void deleteArchivedEntry(PluginArchive.Entry entry);
        void shareEntry(PluginArchive.Entry entry);
    }

    private final Activity activity;
    private final MetaEngine engine;
    private final Host host;

    public ArchiveDialogController(Activity activity, MetaEngine engine, Host host) {
        this.activity = activity;
        this.engine = engine;
        this.host = host;
    }

    public void show() {
        if (engine == null) {
            return;
        }
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(buildContent(dialog));
        UiUtil.styleDialogWindow(dialog);
        dialog.show();
    }

    private View buildContent(final Dialog dialog) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiUtil.dp(activity, 20), UiUtil.dp(activity, 20),
            UiUtil.dp(activity, 20), UiUtil.dp(activity, 20));

        TextView header = new TextView(activity);
        header.setText(R.string.title_archive);
        header.setTextSize(16);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(UiUtil.color(activity, R.color.on_background));
        root.addView(header);

        List<PluginArchive.Entry> entries = engine.getArchivedPlugins();
        if (entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.archive_empty);
            empty.setTextSize(14);
            empty.setTextColor(UiUtil.color(activity, R.color.on_surface_variant));
            empty.setPadding(0, UiUtil.dp(activity, 12), 0, 0);
            root.addView(empty);
        } else {
            for (PluginArchive.Entry entry : entries) {
                root.addView(buildRow(dialog, entry));
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        return scroll;
    }

    private View buildRow(final Dialog dialog, final PluginArchive.Entry entry) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiUtil.dp(activity, 8), 0, UiUtil.dp(activity, 8));

        LinearLayout titleArea = new LinearLayout(activity);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText((entry.title == null || entry.title.trim().isEmpty())
            ? entry.id : entry.title);
        title.setTextSize(15);
        title.setTextColor(UiUtil.color(activity, R.color.on_background));
        titleArea.addView(title);

        if (entry.prompt != null && !entry.prompt.trim().isEmpty()) {
            TextView subtitle = new TextView(activity);
            subtitle.setText(entry.prompt);
            subtitle.setTextSize(11);
            subtitle.setTextColor(UiUtil.color(activity, R.color.on_surface_variant));
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleArea.addView(subtitle);
        }

        TextView meta = new TextView(activity);
        // Timestamp + active marker only: the internal plugin id means
        // nothing to the user and reads like noise.
        meta.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(entry.timestamp)));
        String activeId = engine.getActivePluginId();
        if (activeId != null && activeId.equals(entry.id)) {
            meta.append(" · " + activity.getString(R.string.archive_active_marker));
        }
        meta.setTextSize(10);
        meta.setTextColor(UiUtil.color(activity, R.color.on_surface_variant));
        titleArea.addView(meta);

        titleArea.setOnClickListener(v -> {
            dialog.dismiss();
            host.switchToArchived(entry);
        });
        row.addView(titleArea);

        row.addView(buildAction(activity.getString(R.string.archive_move_up),
            android.R.drawable.arrow_up_float,
            v -> {
                engine.moveArchivedPlugin(entry.id, -1);
                dialog.setContentView(buildContent(dialog));
            }));
        row.addView(buildAction(activity.getString(R.string.archive_move_down),
            android.R.drawable.arrow_down_float,
            v -> {
                engine.moveArchivedPlugin(entry.id, 1);
                dialog.setContentView(buildContent(dialog));
            }));
        row.addView(buildAction(activity.getString(R.string.archive_delete),
            android.R.drawable.ic_menu_delete,
            v -> confirmDelete(dialog, entry)));
        row.addView(buildAction(activity.getString(R.string.title_share),
            android.R.drawable.ic_menu_share,
            v -> {
                dialog.dismiss();
                host.shareEntry(entry);
            }));

        return row;
    }

    private ImageButton buildAction(String contentDesc, int iconRes,
                                    View.OnClickListener listener) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(iconRes);
        btn.setBackgroundColor(0x00000000);
        btn.setColorFilter(UiUtil.color(activity, R.color.on_surface_variant));
        btn.setContentDescription(contentDesc);
        btn.setOnClickListener(listener);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
            UiUtil.dp(activity, 36), UiUtil.dp(activity, 36)));
        return btn;
    }

    private void confirmDelete(final Dialog dialog, final PluginArchive.Entry entry) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.archive_delete)
            .setMessage(activity.getString(R.string.archive_delete_confirm, entry.title))
            .setPositiveButton(R.string.action_delete, (d, w) -> {
                dialog.dismiss();
                host.deleteArchivedEntry(entry);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }
}
