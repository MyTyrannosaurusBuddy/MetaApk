package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CodeSanitizerTest {

    private final CodeSanitizer sanitizer = new CodeSanitizer();

    @Test
    public void allowsSafePluginCode() {
        CodeSanitizer.SanitizeResult result = sanitizer.check(
            "public void onDestroy() { /* no-op */ }");

        assertTrue(result.passed);
        assertNull(result.reason);
    }

    @Test
    public void blocksRuntimeExec() {
        CodeSanitizer.SanitizeResult result = sanitizer.check(
            "Runtime.getRuntime().exec(\"id\")");

        assertFalse(result.passed);
    }

    @Test
    public void blocksPendingBroadcastWithoutNamedReceiver() {
        String code = "public class DynamicPlugin implements PluginContract {\n"
            + "  public void onCreate(Context context, ViewGroup container, ApiBridge bridge) {\n"
            + "    context.registerReceiver(new BroadcastReceiver() {\n"
            + "      @Override public void onReceive(Context c, Intent i) {}\n"
            + "    }, new IntentFilter(\"x\"));\n"
            + "    PendingIntent pi = PendingIntent.getBroadcast(context, 1, new Intent(\"x\"), PendingIntent.FLAG_IMMUTABLE);\n"
            + "  }\n"
            + "  public void onDestroy() {}\n"
            + "  public String getTitle() { return \"x\"; }\n"
            + "}";

        CodeSanitizer.SanitizeResult result = sanitizer.check(code);

        assertFalse(result.passed);
    }

    @Test
    public void allowsPendingBroadcastWithExplicitNamedReceiver() {
        String code = "public class DynamicPlugin implements PluginContract {\n"
            + "  public void onCreate(Context context, ViewGroup container, ApiBridge bridge) {\n"
            + "    Intent intent = new Intent(context, AlarmReceiver.class);\n"
            + "    PendingIntent pi = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE);\n"
            + "  }\n"
            + "  public void onDestroy() {}\n"
            + "  public String getTitle() { return \"x\"; }\n"
            + "  public static class AlarmReceiver extends BroadcastReceiver {\n"
            + "    @Override public void onReceive(Context c, Intent i) {}\n"
            + "  }\n"
            + "}";

        CodeSanitizer.SanitizeResult result = sanitizer.check(code);

        assertTrue(result.passed);
    }
}
