package com.meta.app.bridge;

import android.content.Context;

/**
 * Entry point of the system API bridge layer - system capabilities exposed to dynamic plugins.
 */
public class ApiBridge {

    public final FileBridge file;
    public final NetworkBridge network;
    public final UiBridge ui;
    public final IpcBridge ipc;
    public final ApkBridge apk;

    private final Context context;

    public ApiBridge(Context context) {
        this.context = context;
        this.file = new FileBridge(context);
        this.network = new NetworkBridge();
        this.ui = new UiBridge(context);
        this.ipc = new IpcBridge(context);
        this.apk = new ApkBridge(context);
    }

    public Context getContext() { return context; }

    public void release() {
        ipc.release();
    }
}
