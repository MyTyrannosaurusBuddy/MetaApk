package com.meta.app.core;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;

public class CodeCompilerTest {

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
