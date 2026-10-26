package com.meta.app.core;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import java.util.LinkedHashMap;
import java.util.Map;

public class CodeCompilerTest {

    @Test
    public void workspace_routesCrossFileReceiversFromHelperClasses() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("DynamicPlugin.java", "package com.meta.plugin;\npublic class DynamicPlugin {}");
        files.put("alarm/AlarmReceiver.java", "package com.meta.plugin.alarm;\n"
            + "public class AlarmReceiver extends android.content.BroadcastReceiver {} ");
        files.put("Scheduler.java", "package com.meta.plugin;\nimport com.meta.plugin.alarm.AlarmReceiver;\n"
            + "class Scheduler { Object schedule(Context c) { return new Intent(c, AlarmReceiver.class); } }");
        Map<String, String> staged = new CodeCompiler(null).prepareWorkspaceSources("plugin_test", files);
        assertTrue(staged.get("Scheduler.java").contains("RuntimeCompat.createBroadcastIntent"));
        assertTrue(staged.get("Scheduler.java").contains("\"com.meta.plugin.alarm.AlarmReceiver\""));
        assertTrue(files.get("Scheduler.java").contains("new Intent(c, AlarmReceiver.class)"));
    }

    @Test
    public void workspace_doesNotRewriteUnrelatedSameNameOrStringLiteral() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("DynamicPlugin.java", "package com.meta.plugin;\npublic class DynamicPlugin {}");
        files.put("Alarm.java", "package com.meta.plugin;\nclass Alarm extends BroadcastReceiver {}");
        files.put("Other.java", "package unrelated;\nclass Other { Object i = new Intent(c, Alarm.class); }");
        files.put("Text.java", "package com.meta.plugin;\nclass Text { String s = \"new Intent(c, Alarm.class)\"; }");
        Map<String, String> staged = new CodeCompiler(null).prepareWorkspaceSources("plugin_test", files);
        assertFalse(staged.get("Other.java").contains("RuntimeCompat.createBroadcastIntent"));
        assertFalse(staged.get("Text.java").contains("RuntimeCompat.createBroadcastIntent"));
    }

    @Test
    public void wrapSourceCode_wrapsLooseClassBody() throws Exception {
        CodeCompiler compiler = new CodeCompiler(null);
        Method wrapMethod = CodeCompiler.class.getDeclaredMethod("wrapSourceCode", String.class);
        wrapMethod.setAccessible(true);

        String body = "@Override public void onCreate(Context context, ViewGroup container, ApiBridge bridge) {}\n"
            + "@Override public void onDestroy() {}\n"
            + "@Override public String getTitle() { return \"Demo\"; }";

        String wrapped = (String) wrapMethod.invoke(compiler, body);

        assertTrue(wrapped.contains("package com.meta.plugin;"));
        assertTrue(wrapped.contains("import com.meta.app.bridge.ApiBridge;"));
        assertTrue(wrapped.contains("import com.meta.app.core.PluginContract;"));
        assertTrue(wrapped.contains("public class DynamicPlugin implements PluginContract"));
        assertTrue(wrapped.contains(body));
    }

    @Test
    public void wrapSourceCode_injectsMissingHostImportsForCompleteClass() throws Exception {
        CodeCompiler compiler = new CodeCompiler(null);
        Method wrapMethod = CodeCompiler.class.getDeclaredMethod("wrapSourceCode", String.class);
        wrapMethod.setAccessible(true);

        String completeClass = "public class DynamicPlugin implements PluginContract {\n"
            + "  @Override public void onCreate(android.content.Context context, android.view.ViewGroup container, ApiBridge bridge) {}\n"
            + "  @Override public void onDestroy() {}\n"
            + "  @Override public String getTitle() { return \"Demo\"; }\n"
            + "}";

        String wrapped = (String) wrapMethod.invoke(compiler, completeClass);

        assertTrue(wrapped.startsWith("package com.meta.plugin;"));
        assertTrue(wrapped.contains("import com.meta.app.core.PluginContract;"));
        assertTrue(wrapped.contains("import com.meta.app.bridge.ApiBridge;"));
        assertTrue(wrapped.contains("import android.widget.*;"));
    }

    @Test
    public void wrapSourceCode_addsWildcardImportsEvenWhenSpecificImportsExist() throws Exception {
        CodeCompiler compiler = new CodeCompiler(null);
        Method wrapMethod = CodeCompiler.class.getDeclaredMethod("wrapSourceCode", String.class);
        wrapMethod.setAccessible(true);

        String completeClass = "package com.meta.plugin;\n\n"
            + "import android.widget.TextView;\n"
            + "public class DynamicPlugin implements PluginContract {\n"
            + "  @Override public void onCreate(android.content.Context context, android.view.ViewGroup container, ApiBridge bridge) {\n"
            + "    TimePicker picker = null;\n"
            + "  }\n"
            + "  @Override public void onDestroy() {}\n"
            + "  @Override public String getTitle() { return \"Demo\"; }\n"
            + "}";

        String wrapped = (String) wrapMethod.invoke(compiler, completeClass);

        assertTrue(wrapped.contains("import android.widget.TextView;"));
        assertTrue(wrapped.contains("import android.widget.*;"));
    }

    @Test
    public void maskLiteralsAndComments_blanksStringsAndCommentsOnly() {
        String code = "String s = \"}}\"; // }\n"
            + "char c = '}'; /* { */\n"
            + "int depth = 0;";

        String masked = CodeCompiler.maskLiteralsAndComments(code);

        assertTrue(!masked.contains("}}"));
        assertTrue(!masked.contains("//"));
        assertTrue(!masked.contains("/*"));
        assertTrue(masked.contains("int depth = 0;"));
    }

    @Test
    public void adaptSourceToHostRuntime_receiverNestingIgnoresBraceInString() throws Exception {
        CodeCompiler compiler = new CodeCompiler(null);
        Method adapt = CodeCompiler.class.getDeclaredMethod(
            "adaptSourceToHostRuntime", String.class, String.class);
        adapt.setAccessible(true);

        String source = "package com.meta.plugin;\n"
            + "public class DynamicPlugin implements PluginContract {\n"
            + "  String brace = \"}\";\n"
            + "  public static class AlarmReceiver extends BroadcastReceiver {\n"
            + "    @Override public void onReceive(Context c, Intent i) {}\n"
            + "  }\n"
            + "  public void onCreate(Context c, ViewGroup v, ApiBridge b) {\n"
            + "    PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class), PendingIntent.FLAG_IMMUTABLE);\n"
            + "  }\n"
            + "  @Override public void onDestroy() {}\n"
            + "  @Override public String getTitle() { return \"x\"; }\n"
            + "}";

        String adapted = (String) adapt.invoke(compiler, "plugin_abc12345", source);

        assertTrue(adapted.contains("RuntimeCompat.createBroadcastIntent"));
        assertTrue(adapted.contains("com.meta.plugin.DynamicPlugin$AlarmReceiver"));
    }
}
