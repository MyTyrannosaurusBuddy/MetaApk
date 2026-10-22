package com.meta.app.bridge;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * IPC bridge - broadcast sending and explicit Activity launching.
 */
public class IpcBridge {
    private final Context context;

    public interface ServiceCallback {
        void onConnected(IBinder binder);
        void onDisconnected();
        void onError(String message);
    }

    public IpcBridge(Context context) {
        this.context = context;
    }

    public void sendBroadcast(String action, Bundle extras) {
        Intent intent = new Intent(action);
        if (extras != null) intent.putExtras(extras);
        context.sendBroadcast(intent);
    }

    public void startActivity(String action, String data) {
        Intent intent = new Intent(action);
        if (data != null) intent.setData(android.net.Uri.parse(data));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** No persistent connections exist; kept for API symmetry. */
    public void release() { }
}
