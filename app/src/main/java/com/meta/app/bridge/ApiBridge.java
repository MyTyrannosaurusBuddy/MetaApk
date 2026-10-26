package com.meta.app.bridge;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Entry point of the system API bridge layer - system capabilities exposed to dynamic plugins.
 */
public class ApiBridge {

    /** Receives the result of a runtime permission request. */
    public interface PermissionCallback {
        /** Called with one entry per requested permission, in the same order. */
        void onResult(boolean[] granted);
    }

    /** Host-side hook that performs the actual permission request flow. */
    public interface PermissionRequester {
        void request(String[] permissions, PermissionCallback callback);
    }

    public final FileBridge file;
    public final NetworkBridge network;
    public final UiBridge ui;
    public final IpcBridge ipc;

    private final Context context;
    /** Context handed to plugins through getContext(): normally the host
     *  Activity, switched to the virtual display's context during agent
     *  previews so dialogs/toasts never reach the physical screen. */
    private volatile Context activeContext;
    private volatile PermissionRequester permissionRequester;

    public ApiBridge(Context context) {
        this.context = context;
        this.activeContext = context;
        this.file = new FileBridge(context);
        this.network = new NetworkBridge();
        this.ui = new UiBridge(context);
        this.ipc = new IpcBridge(context);
    }

    public Context getContext() { return activeContext != null ? activeContext : context; }

    /** Switches the context handed to plugins (preview mode) and the UI bridge's toast target. */
    public void setActiveContext(Context activeContext) {
        this.activeContext = activeContext;
        if (activeContext != null) {
            this.ui.setContext(activeContext);
        }
    }

    /**
     * Routes the file bridge into the active plugin's lineage data dir, so
     * bridge-based storage survives feedback revisions (called by the host
     * before each injection).
     */
    public void setScopedFilesDir(java.io.File dir) {
        file.setBaseDir(dir);
    }

    /** Injected by the host Activity, which owns the permission request flow. */
    public void setPermissionRequester(PermissionRequester requester) {
        this.permissionRequester = requester;
    }

    /**
     * Whether the given runtime permission is currently granted.
     */
    public boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Requests runtime permissions. If all of them are already granted the
     * callback fires immediately; otherwise the system permission dialog is
     * shown by the host and the result is delivered to the callback.
     */
    public void requestPermissions(String[] permissions, PermissionCallback callback) {
        PermissionRequester requester = permissionRequester;
        if (requester == null || permissions == null || permissions.length == 0
                || callback == null) {
            return;
        }
        boolean allGranted = true;
        boolean[] granted = new boolean[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            granted[i] = hasPermission(permissions[i]);
            if (!granted[i]) {
                allGranted = false;
            }
        }
        if (allGranted) {
            callback.onResult(granted);
            return;
        }
        requester.request(permissions, callback);
    }

    public void release() {
        ipc.release();
    }
}
