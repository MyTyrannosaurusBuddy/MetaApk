package com.meta.app.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.meta.app.R;

import java.io.*;

/**
 * APK bridge - self-upgrade capability.
 */
public class ApkBridge {
    private static final String TAG = "ApkBridge";
    private final Context context;

    public interface UpgradeCallback {
        void onInstallStarted();
        void onError(String message);
    }

    public ApkBridge(Context context) {
        this.context = context;
    }

    public void installApk(File apkFile, UpgradeCallback callback) {
        try {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            try (InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("package", 0, apkFile.length())) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) >= 0) out.write(buffer, 0, len);
                session.fsync(out);
            }

            Intent intent = new Intent(context, context.getClass());
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
            callback.onInstallStarted();
        } catch (Exception e) {
            Log.e(TAG, "Install APK error", e);
            callback.onError(context.getString(R.string.err_apk_install_failed, e.getMessage()));
        }
    }

    public String getCurrentApkPath() { return context.getApplicationInfo().sourceDir; }

    public int getCurrentVersionCode() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) { return -1; }
    }
}
