package com.meta.app.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.meta.app.R;

/**
 * Foreground service held while a generation / revision session is running.
 *
 * The engine itself runs on a plain background thread; without this the whole
 * process is background-cached and can be frozen (background app freezer) or
 * reclaimed the moment the user locks the screen or leaves the host - the
 * session would hang invisibly. A foreground service is the standard Android
 * answer for "work that must survive the user leaving": the process stays at
 * foreground priority, and the notification shows the session is alive.
 *
 * Lifecycle is driven by the host state machine (setGenerating on/off), which
 * is the same signal that toggles the keep-screen-on flag.
 */
public class GenerationService extends Service {

    private static final String CHANNEL_ID = "generation";
    private static final int NOTIFICATION_ID = 1;

    /** Starts the foreground service; idempotent. */
    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, GenerationService.class));
        } catch (Exception ignored) {
            // best-effort: the session still runs, just without process priority
        }
    }

    /** Stops the foreground service; idempotent. */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, GenerationService.class));
        } catch (Exception ignored) {
            // already stopped
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        // startForeground goes HERE, not in onStartCommand: the system gives
        // a startForegroundService() call a 5-second window to reach the
        // foreground state. A busy main thread can delay onStartCommand past
        // that window and the process is killed with
        // ForegroundServiceDidNotStartInTimeException - onCreate runs earlier,
        // shrinking the race window to the minimum.
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_generation),
                NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, HostActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent content = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(R.string.notification_generating_title))
            .setContentText(getString(R.string.notification_generating_text))
            .setContentIntent(content)
            .setOngoing(true)
            .build();
    }
}
