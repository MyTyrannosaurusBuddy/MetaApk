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
}
