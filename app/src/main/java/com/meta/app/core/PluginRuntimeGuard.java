package com.meta.app.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Runtime error guard for dynamic plugins.
 *
 * Uncaught exceptions thrown by generated code outside the host boundary
 * (e.g. inside UI callbacks) would normally crash the whole process.
 * The handling here is generic - it does not care about specific APIs or
 * exception types:
 *   1. As long as the error comes from plugin code, the process is kept
 *      alive (the main thread re-enters the message loop and keeps running);
 *   2. The exception type, message and stack frames are summarized and
 *      reported to the host, which feeds them back to the LLM to regenerate
 *      the code.
 * Host bugs are NOT contained; they still go through the default crash path
 * so real problems are never swallowed.
 *
 * Containment is rate-limited: past the limit, crashes are allowed through,
 * preventing the app from turning into a zombie through endless swallowing.
 */
public final class PluginRuntimeGuard {

    private static final String TAG = "PluginRuntimeGuard";
    /** Plugin code is compiled into this package (see CodeCompiler); used to distinguish plugin errors from host errors. */
    private static final String PLUGIN_CLASS_PREFIX = "com.meta.plugin.";
    /** Maximum number of plugin errors contained within one process lifetime. */
    private static final int MAX_CONTAINED_ERRORS = 5;
    /** Maximum stack frames kept per Throwable in a summary. */
    private static final int MAX_FRAMES_PER_THROWABLE = 8;
    /** Maximum cause-chain depth expanded in a summary. */
    private static final int MAX_CAUSE_DEPTH = 3;

    public interface Listener {
        void onPluginRuntimeError(String errorSummary);
    }

    private static final Object LOCK = new Object();
    private static Listener listener;
    private static Thread.UncaughtExceptionHandler originalHandler;
    private static int containedCount;

    private PluginRuntimeGuard() {}

    /**
     * Registers the error listener (the host Activity). Passing null means
     * nobody is listening and plugin errors fall through to the default
     * crash path.
     */
    public static void setListener(Listener newListener) {
        synchronized (LOCK) {
            listener = newListener;
            if (newListener != null && originalHandler == null) {
                originalHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler(PluginRuntimeGuard::handleUncaught);
            }
        }
    }

    /**
     * Reports a plugin error proactively (e.g. from a host catch block),
     * routing it into the same "feed back to the model" flow instead of
     * swallowing it silently.
     */
    public static void reportPluginError(Throwable error) {
        if (error == null || !isFromPlugin(error)) {
            return;
        }
        report(error);
    }

    /**
     * Resets the containment count after a new plugin is injected:
     * every app gets its own fault budget instead of a process-wide
     * budget that eventually runs out.
     */
    public static void resetContainedCount() {
        synchronized (LOCK) {
            containedCount = 0;
        }
    }

    private static void handleUncaught(Thread thread, Throwable error) {
        Listener target;
        synchronized (LOCK) {
            target = listener;
        }
        if (target == null || !isFromPlugin(error)) {
            delegate(thread, error);
            return;
        }
        if (!contain(thread, error)) {
            delegate(thread, error);
        }
    }

    /**
     * Contains a plugin error and keeps the thread alive.
     * On the main thread, an exception would tear down the message loop;
     * we re-enter Looper.loop() to keep it running. On background threads the
     * error is simply swallowed (thread exit does not affect the process).
     *
     * @return true if contained; false if handed back to a system crash
     */
    private static boolean contain(Thread thread, Throwable firstError) {
        boolean mainThread = (Looper.getMainLooper() != null
            && thread == Looper.getMainLooper().getThread());
        Throwable current = firstError;
        while (true) {
            if (!isFromPlugin(current)) {
                return false;
            }
            if (!report(current)) {
                return false;
            }
            if (!mainThread) {
                return true;
            }
            try {
                Looper.loop();
                return true;
            } catch (Throwable next) {
                current = next;
            }
        }
    }

    /** @return false when the containment limit is exceeded and the crash should be allowed through */
    private static boolean report(Throwable error) {
        Listener target;
        synchronized (LOCK) {
            target = listener;
            if (target == null || containedCount >= MAX_CONTAINED_ERRORS) {
                return false;
            }
            containedCount++;
        }

        String summary = summarize(error);
        Log.e(TAG, "Plugin runtime error contained ("
            + containedCount + "/" + MAX_CONTAINED_ERRORS + "):\n" + summary, error);
        new Handler(Looper.getMainLooper()).post(() -> target.onPluginRuntimeError(summary));
        return true;
    }

    private static void delegate(Thread thread, Throwable error) {
        Thread.UncaughtExceptionHandler handler;
        synchronized (LOCK) {
            handler = originalHandler;
        }
        if (handler != null) {
            handler.uncaughtException(thread, error);
        }
    }

    /**
     * Whether the error comes from plugin code: walks the whole cause chain;
     * any frame whose class belongs to the plugin package counts.
     */
    public static boolean isFromPlugin(Throwable error) {
        Set<Throwable> seen = new HashSet<>();
        Throwable current = error;
        while (current != null && seen.add(current)) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String className = frame.getClassName();
                if (className != null && className.startsWith(PLUGIN_CLASS_PREFIX)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Builds an error summary for the model: exception type, message,
     * stack frames and cause chain.
     */
    public static String summarize(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = new HashSet<>();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (depth > 0) {
                sb.append("Caused by: ");
            }
            sb.append(current.getClass().getName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            sb.append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < frames.length && i < MAX_FRAMES_PER_THROWABLE; i++) {
                sb.append("    at ").append(frames[i]).append('\n');
            }
            if (frames.length > MAX_FRAMES_PER_THROWABLE) {
                sb.append("    ... ").append(frames.length - MAX_FRAMES_PER_THROWABLE)
                    .append(" more frames\n");
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString().trim();
    }
}
