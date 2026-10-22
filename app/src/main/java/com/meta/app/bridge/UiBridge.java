package com.meta.app.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * UI bridge - Toast, notifications and so on.
 */
public class UiBridge {
    private final Context context;
    private final Handler handler;

    public UiBridge(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void showToast(String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void showToastLong(String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void runOnUiThread(Runnable action) {
        handler.post(action);
    }
}
