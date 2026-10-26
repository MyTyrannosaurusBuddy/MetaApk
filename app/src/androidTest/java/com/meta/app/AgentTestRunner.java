package com.meta.app;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps a readable device-side result when no instrumentation watcher is
 * attached, and guarantees the instrumentation process dies after the run:
 * non-daemon threads left by the engine/LLM can keep the process lingering,
 * which hangs the connected harness waiting for process death.
 */
public class AgentTestRunner extends AndroidJUnitRunner {
    @Override public void finish(int resultCode, Bundle results) {
        try (FileOutputStream output = new FileOutputStream(
                new File(getTargetContext().getCacheDir(), "agent-test-results.txt"))) {
            output.write(("resultCode=" + resultCode + "\n" + results).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.e("AgentTestRunner", "Could not save test result", e);
        }
        super.finish(resultCode, results);
        // The results are flushed above; a short delay lets the harness read
        // them before the forced exit.
        new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                // fall through to the kill
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "AgentTestRunner-exit").start();
    }
}
