package com.meta.app.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device Java compiler - compiles .java into .class with ECJ (Eclipse Compiler for Java).
 */
public class CodeCompiler {

    private static final String TAG = "CodeCompiler";
    private static final String PLUGIN_CLASS_NAME = "DynamicPlugin";
    private final Context context;

    public CodeCompiler(Context context) {
        this.context = context;
    }

    /**
     * Compilation result.
     */
    public static class CompileResult {
        public final boolean success;
        public final File classesDir;
        public final String errorMessage;

        private CompileResult(boolean success, File classesDir, String errorMessage) {
            this.success = success;
            this.classesDir = classesDir;
            this.errorMessage = errorMessage;
        }

        public static CompileResult success(File classesDir) {
            return new CompileResult(true, classesDir, null);
        }

        public static CompileResult failure(String error) {
            return new CompileResult(false, null, error);
        }
    }

    /**
     * Compiles LLM-generated Java source into .class files.
     * @param pluginId   unique plugin ID
     * @param sourceCode LLM-generated Java source (a full class body, without a class declaration)
     * @return the compilation result
     */
    public CompileResult compile(String pluginId, String sourceCode) {
        File pluginDir = getPluginDir(pluginId);
        File sourceDir = new File(pluginDir, "source");
        File classesDir = new File(pluginDir, "classes");
        sourceDir.mkdirs();
        classesDir.mkdirs();

        // Wrap the source: add the package declaration, imports and class definition
        String wrappedCode = wrapSourceCode(sourceCode);
        wrappedCode = adaptSourceToHostRuntime(pluginId, wrappedCode);
        File sourceFile = new File(sourceDir, PLUGIN_CLASS_NAME + ".java");

        try {
            FileWriter writer = new FileWriter(sourceFile);
            writer.write(wrappedCode);
            writer.close();
        } catch (IOException e) {
            return CompileResult.failure("Failed to write source file: " + e.getMessage());
        }

        // Build the classpath: android.jar + host interfaces
        String classpath = buildClasspath();

        // ECJ compile arguments
        String[] args = {
            "-source", "8",
            "-target", "8",
            "-classpath", classpath,
            "-d", classesDir.getAbsolutePath(),
            "-proc:none",
            sourceFile.getAbsolutePath()
        };

        // Invoke ECJ
        StringWriter errorOutput = new StringWriter();
        boolean success = invokeECJ(args, errorOutput);

        if (!success) {
            String errors = errorOutput.toString();
            Log.e(TAG, "ECJ compilation failed:\n" + errors);
            return CompileResult.failure("Compile errors:\n" + errors);
        }

        Log.i(TAG, "ECJ compilation succeeded for plugin: " + pluginId);
        return CompileResult.success(classesDir);
    }

    /**
     * Adds the package name, imports and a class shell to LLM-generated code.
     * The LLM may produce two formats:
     * 1. A complete Java file (with package/import/class) - used directly
     * 2. Only method bodies - wrapped automatically
     */
    private String wrapSourceCode(String userCode) {
        // If the code already contains a class declaration, treat it as a complete file
        if (userCode.contains("class DynamicPlugin") || userCode.contains("class " + PLUGIN_CLASS_NAME)) {
            // Ensure the package name is correct
            if (!userCode.contains("package com.meta.plugin")) {
                userCode = "package com.meta.plugin;\n\n" + userCode;
            }
            // The LLM often omits host-interface imports; add them to avoid immediate compile failures
            return injectMissingImports(userCode);
        }

        // Otherwise wrap automatically
        return "package com.meta.plugin;\n\n"
            + "import android.app.*;\n"
            + "import android.content.*;\n"
            + "import android.content.res.*;\n"
            + "import android.view.*;\n"
            + "import android.widget.*;\n"
            + "import android.graphics.*;\n"
            + "import android.os.*;\n"
            + "import android.util.*;\n"
            + "import android.media.*;\n"
            + "import android.net.*;\n"
            + "import android.text.*;\n"
            + "import java.util.*;\n"
            + "import java.text.*;\n"
            + "import com.meta.app.bridge.ApiBridge;\n"
            + "import com.meta.app.core.PluginContract;\n\n"
            + "public class " + PLUGIN_CLASS_NAME + " implements PluginContract {\n"
            + userCode + "\n"
            + "}\n";
    }

    /**
     * Adds missing host-interface imports.
     * Complete classes produced by the LLM often omit the imports for
     * {@code PluginContract} / {@code ApiBridge}, which makes ECJ report
     * "cannot be resolved" and fails the whole generation.
     */
    private String injectMissingImports(String code) {
        StringBuilder missing = new StringBuilder();

        ensureWildcardImport(code, missing, "import android.app.*;");
        ensureWildcardImport(code, missing, "import android.content.*;");
        ensureWildcardImport(code, missing, "import android.content.res.*;");
        ensureWildcardImport(code, missing, "import android.view.*;");
        ensureWildcardImport(code, missing, "import android.widget.*;");
        ensureWildcardImport(code, missing, "import android.graphics.*;");
        ensureWildcardImport(code, missing, "import android.os.*;");
        ensureWildcardImport(code, missing, "import android.util.*;");
        ensureWildcardImport(code, missing, "import android.media.*;");
        ensureWildcardImport(code, missing, "import android.net.*;");
        ensureWildcardImport(code, missing, "import android.text.*;");
        ensureWildcardImport(code, missing, "import java.util.*;");
        ensureWildcardImport(code, missing, "import java.text.*;");

        if (code.contains("PluginContract")) {
            ensureSpecificImport(code, missing, "import com.meta.app.core.PluginContract;");
        }
        if (code.contains("ApiBridge")) {
            ensureSpecificImport(code, missing, "import com.meta.app.bridge.ApiBridge;");
        }
        if (code.contains("MainActivity")) {
            ensureSpecificImport(code, missing, "import com.meta.app.MainActivity;");
        }
        if (containsAny(code, "Gson", "JsonObject", "JsonArray", "JsonElement", "JsonParser")) {
            ensureWildcardImport(code, missing, "import com.google.gson.*;");
        }

        if (missing.length() == 0) {
            return code;
        }

        // Insert after the package statement
        int pkgIdx = code.indexOf("package com.meta.plugin");
        if (pkgIdx >= 0) {
            int eol = code.indexOf('\n', pkgIdx);
            if (eol >= 0) {
                return code.substring(0, eol + 1) + missing + code.substring(eol + 1);
            }
        }
        return missing + code;
    }

    /**
     * Makes standard Android patterns behave like a static APK at dynamic
     * runtime: when a plugin declares a BroadcastReceiver and constructs
     * new Intent(context, Receiver.class), the call is rewritten to target
     * the host's pre-registered StubReceiver explicitly, and the host
     * reflectively dispatches back to the dynamic receiver.
     *
     * This way the model keeps writing idiomatic Android SDK code instead of
     * learning host-private APIs.
     */
    private String adaptSourceToHostRuntime(String pluginId, String code) {
        List<DeclaredReceiver> receivers = findDeclaredReceivers(code);
        if (receivers.isEmpty()) {
            return code;
        }
        String adapted = code;
        for (DeclaredReceiver receiver : receivers) {
            adapted = replaceReceiverIntentConstructors(adapted, receiver, pluginId);
        }
        return adapted;
    }

    private String replaceReceiverIntentConstructors(String code, DeclaredReceiver receiver,
                                                     String pluginId) {
        Pattern intentCtor = Pattern.compile(
            "new\\s+Intent\\s*\\(\\s*([^,\\n\\r]+?)\\s*,\\s*"
                + Pattern.quote(receiver.simpleName) + "\\.class\\s*\\)");
        Matcher matcher = intentCtor.matcher(code);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = "com.meta.app.core.RuntimeCompat.createBroadcastIntent("
                + matcher.group(1).trim() + ", \""
                + receiver.runtimeClassName + "\", \""
                + pluginId + "\")";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private List<DeclaredReceiver> findDeclaredReceivers(String code) {
        List<DeclaredReceiver> result = new ArrayList<>();
        Matcher matcher = Pattern.compile(
            "(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?class\\s+"
                + "([A-Za-z_$][A-Za-z0-9_$]*)\\s+extends\\s+"
                + "(?:BroadcastReceiver|android\\.content\\.BroadcastReceiver)\\b")
            .matcher(code);
        while (matcher.find()) {
            String receiverName = matcher.group(1);
            String runtimeClassName = isInsideDynamicPlugin(code, matcher.start())
                ? "com.meta.plugin." + PLUGIN_CLASS_NAME + "$" + receiverName
                : "com.meta.plugin." + receiverName;
            result.add(new DeclaredReceiver(receiverName, runtimeClassName));
        }
        return result;
    }

    private boolean isInsideDynamicPlugin(String code, int position) {
        int pluginStart = code.indexOf("class " + PLUGIN_CLASS_NAME);
        if (pluginStart < 0) {
            return false;
        }

        int bodyStart = code.indexOf('{', pluginStart);
        if (bodyStart < 0 || position <= bodyStart) {
            return false;
        }

        int depth = 0;
        for (int i = bodyStart; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return position < i;
                }
            }
        }
        return false;
    }

    private static final class DeclaredReceiver {
        private final String simpleName;
        private final String runtimeClassName;

        private DeclaredReceiver(String simpleName, String runtimeClassName) {
            this.simpleName = simpleName;
            this.runtimeClassName = runtimeClassName;
        }
    }

    private void ensureWildcardImport(String code, StringBuilder missing, String importLine) {
        if (!code.contains(importLine)) {
            missing.append(importLine).append('\n');
        }
    }

    private void ensureSpecificImport(String code, StringBuilder missing, String importLine) {
        if (!code.contains(importLine)) {
            missing.append(importLine).append('\n');
        }
    }

    private boolean containsAny(String code, String... tokens) {
        for (String token : tokens) {
            if (code.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private ClassLoader getEcjClassLoader() throws Exception {
        File optimizedDir = new File(context.getCacheDir(), "ecj_odex");
        optimizedDir.mkdirs();
        // Must be read-only after extraction: Android 14+ refuses to load writable dex files
        File ecjDex = AssetTools.releaseReadOnly(context, "ecj.dex.jar", "ecj.dex.jar");
        return new dalvik.system.DexClassLoader(
            ecjDex.getAbsolutePath(),
            optimizedDir.getAbsolutePath(),
            null,
            context.getClassLoader()
        );
    }

    /**
     * Invokes the ECJ compiler via reflection.
     */
    private boolean invokeECJ(String[] args, StringWriter errorWriter) {
        try {
            ClassLoader ecjLoader = getEcjClassLoader();
            // ECJ main class: org.eclipse.jdt.internal.compiler.batch.Main
            Class<?> ecjClass = ecjLoader.loadClass(
                "org.eclipse.jdt.internal.compiler.batch.Main");
            Constructor<?> ctor = ecjClass.getConstructor(
                PrintWriter.class, PrintWriter.class, boolean.class);
            Object compiler = ctor.newInstance(
                new PrintWriter(errorWriter),  // out
                new PrintWriter(errorWriter),  // err
                false);                        // systemExitWhenFinished
            Method compileMethod = ecjClass.getMethod("compile", String[].class);
            return (boolean) compileMethod.invoke(compiler, (Object) args);
        } catch (Exception e) {
            // InvocationTargetException has a null getMessage(); the cause must be printed
            errorWriter.write("ECJ invocation failed: " + e);
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            errorWriter.write("\n" + sw.toString());
            Log.e(TAG, "ECJ invocation failed", e);
            return false;
        }
    }

    /**
     * Builds the compile classpath.
     * Contains host-interfaces.jar (host interfaces such as PluginContract /
     * ApiBridge) and android.jar (system APIs).
     *
     * Note: the host APK cannot be used as the classpath - an APK only
     * contains classes.dex, while ECJ looks for .class inside jars; passing
     * the APK directly causes "The import com.meta.app cannot be resolved".
     */
    private String buildClasspath() {
        StringBuilder cp = new StringBuilder();

        // Host interface jar
        File hostJar = getHostInterfacesJar();
        if (hostJar != null && hostJar.exists()) {
            cp.append(hostJar.getAbsolutePath());
        } else {
            Log.e(TAG, "host-interfaces.jar unavailable, plugins cannot resolve PluginContract / ApiBridge");
        }

        // android.jar: ECJ needs it to resolve android.* / java.* types,
        // otherwise every Android API reports "cannot be resolved".
        File androidJar = getAndroidJar();
        if (androidJar != null && androidJar.exists()) {
            cp.append(File.pathSeparator).append(androidJar.getAbsolutePath());
        } else {
            Log.e(TAG, "android.jar unavailable, compilation will most likely fail due to missing android APIs");
        }

        return cp.toString();
    }

    /**
     * Obtains the host interface jar (extracted from assets on first use).
     */
    public File getHostInterfacesJar() {
        try {
            return AssetTools.releaseReadOnly(context, "host-interfaces.jar", "host-interfaces.jar");
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract host-interfaces.jar", e);
            return null;
        }
    }

    /**
     * Obtains android.jar (extracted from assets on first use).
     */
    public File getAndroidJar() {
        try {
            return AssetTools.releaseReadOnly(context, "android.jar", "android.jar");
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract android.jar", e);
            return null;
        }
    }

    private File getPluginDir(String pluginId) {
        return new File(context.getFilesDir(), "plugins/" + pluginId);
    }

    /**
     * Cleans up the compile files of a plugin.
     */
    public void cleanPlugin(String pluginId) {
        File pluginDir = getPluginDir(pluginId);
        deleteRecursive(pluginDir);
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
