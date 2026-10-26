package com.meta.app.ui;

import android.content.Context;

/**
 * Small view helpers shared by the host UI components.
 */
public final class UiUtil {

    private UiUtil() {
    }

    public static int color(Context context, int resId) {
        return context.getColor(resId);
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Applies the host's dark surface to a dialog window. The default dialog
     * window background is the (light) platform theme; the host UI is dark,
     * so every dialog gets the same dark surface for a consistent look.
     */
    public static void styleDialogWindow(android.app.Dialog dialog) {
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                color(dialog.getContext(), com.meta.app.R.color.surface)));
        }
    }
}
