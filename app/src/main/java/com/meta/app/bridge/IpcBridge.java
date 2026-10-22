package com.meta.app.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * IPC bridge - cross-app service binding and broadcast.
 */
public class IpcBridge {
    private final Context context;
    private final Map<String, ServiceConnection> connections = new HashMap<>();

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

    public void release() {
        for (ServiceConnection conn : connections.values()) {
            try { context.unbindService(conn); } catch (Exception ignored) {}
        }
        connections.clear();
    }
}
