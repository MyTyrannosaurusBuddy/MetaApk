package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonObject;
import com.meta.app.core.MetaEngine;
import com.meta.app.core.PluginContract;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * Regression for the engine-wide cancel flag: after a user cancel, the next
 * feedback/retry was silently dropped at the entry guard (the stale flag was
 * checked BEFORE the new session reset it), with no callback - the host UI
 * stayed stuck in the generating state forever. A new session must start
 * with a fresh cancellation scope.
 */
@RunWith(AndroidJUnit4.class)
public class CancelThenFeedbackDeviceTest {

    private static MetaEngine.Callback loggingCallback(final AtomicReference<String> terminal) {
        return new MetaEngine.Callback() {
            @Override public void onStatusUpdate(String status) {
                android.util.Log.i("CancelThenFeedback", "STATUS: " + status);
            }
            @Override public void onError(String text) {
                android.util.Log.i("CancelThenFeedback", "ERROR: " + text);
                terminal.set("error");
            }
            @Override public void onPlugin(PluginContract plugin) {
                terminal.set("plugin");
            }
        };
    }

    @Test
    public void cancelThenFeedback_isNotSilentlyDropped() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MetaEngine engine = new MetaEngine(context);
        try {
            AtomicReference<String> first = new AtomicReference<>("");
            engine.process("a counter app with one button", loggingCallback(first));
            // Wait until the generation task is persisted (submission landed).
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                String task = context.getSharedPreferences("meta_pending_task",
                    Context.MODE_PRIVATE).getString("task", null);
                if (task != null && task.contains("GENERATE")) {
                    break;
                }
                Thread.sleep(500);
            }
            String task = context.getSharedPreferences("meta_pending_task",
                Context.MODE_PRIVATE).getString("task", null);
            assertTrue("Generation was never submitted", task != null && task.contains("GENERATE"));

            // User cancels, then immediately asks for a revision.
            engine.cancel();
            Thread.sleep(1000);

            AtomicReference<String> second = new AtomicReference<>("");
            engine.feedback("a counter app with one button",
                "make the button blue", loggingCallback(second));

            // The feedback task must be persisted: the entry guard must not
            // have swallowed it because of the previous cancel.
            boolean feedbackStarted = false;
            deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                String pending = context.getSharedPreferences("meta_pending_task",
                    Context.MODE_PRIVATE).getString("task", null);
                if (pending != null && pending.contains("FEEDBACK")) {
                    feedbackStarted = true;
                    break;
                }
                Thread.sleep(500);
            }
            assertTrue("Feedback was silently dropped after cancel", feedbackStarted);
        } finally {
            engine.cancel();
            engine.clearPendingTask();
            engine.destroy();
        }
    }
}
