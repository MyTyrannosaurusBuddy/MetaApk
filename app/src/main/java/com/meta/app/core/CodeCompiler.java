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

        // Post-compile bytecode check: scan the constant pool of every .class
        // for forbidden references that source-level obfuscation could hide.
        CodeSanitizer.SanitizeResult bytecodeCheck =
            CodeSanitizer.scanCompiledClasses(classesDir);
        if (!bytecodeCheck.passed) {
            Log.w(TAG, "Bytecode security check failed: " + bytecodeCheck.reason);
            return CompileResult.failure("Security check failed: " + bytecodeCheck.reason);
        }

        return CompileResult.success(classesDir);
    }

    /**
     * Compiles every .java file in the agent's workspace src directory into
     * a single dex. The DynamicPlugin.java entry gets the host-runtime
     * adaptation (missing imports + explicit-component rewriting) applied at
     * compile time, so the workspace files stay pristine for the agent.
     * Every transformed file passes the source-level security check before
     * ECJ runs.
     *
     * @param pluginId unique plugin ID (this attempt)
     * @param srcDir   the workspace src directory
     */
    public CompileResult compileWorkspace(String pluginId, File srcDir) {
        File pluginDir = getPluginDir(pluginId);
        File classesDir = new File(pluginDir, "classes");
        classesDir.mkdirs();

        List<File> javaFiles = collectJavaFiles(srcDir);
        File dynamicPlugin = findDynamicPlugin(javaFiles);
        if (dynamicPlugin == null) {
            return CompileResult.failure(
                "No DynamicPlugin.java found in the workspace; create it as the app entry point");
        }

        // Stage transformed copies (workspace files stay untouched).
        File workDir = new File(pluginDir, "workspace-build");
        deleteRecursive(workDir);
        workDir.mkdirs();
        CodeSanitizer sanitizer = new CodeSanitizer();
        for (File file : javaFiles) {
            String rel = srcDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            File target = new File(workDir, rel);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            String content = readText(file);
            if (file.equals(dynamicPlugin)) {
                content = adaptSourceToHostRuntime(pluginId, injectMissingImports(content));
            }
            CodeSanitizer.SanitizeResult check = sanitizer.check(content);
            if (!check.passed) {
                return CompileResult.failure("Security check failed: " + check.reason
                    + " (in " + rel + ")");
            }
            writeText(target, content);
        }

        String classpath = buildClasspath();
        List<String> args = new ArrayList<>();
        args.add("-source");
        args.add("8");
        args.add("-target");
        args.add("8");
        args.add("-classpath");
        args.add(classpath);
        args.add("-d");
        args.add(classesDir.getAbsolutePath());
        args.add("-proc:none");
        for (File f : collectJavaFiles(workDir)) {
            args.add(f.getAbsolutePath());
        }

        StringWriter errorOutput = new StringWriter();
        boolean success = invokeECJ(args.toArray(new String[0]), errorOutput);
        if (!success) {
            String errors = errorOutput.toString();
            Log.e(TAG, "ECJ compilation failed:\n" + errors);
            return CompileResult.failure("Compile errors:\n" + errors);
        }
        Log.i(TAG, "ECJ workspace compilation succeeded for plugin: " + pluginId);

        CodeSanitizer.SanitizeResult bytecodeCheck =
            CodeSanitizer.scanCompiledClasses(classesDir);
        if (!bytecodeCheck.passed) {
            Log.w(TAG, "Bytecode security check failed: " + bytecodeCheck.reason);
            return CompileResult.failure("Security check failed: " + bytecodeCheck.reason);
        }
        return CompileResult.success(classesDir);
    }

    private static List<File> collectJavaFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(collectJavaFiles(f));
            } else if (f.getName().endsWith(".java")) {
                result.add(f);
            }
        }
        return result;
    }

    private static File findDynamicPlugin(List<File> javaFiles) {
        File found = null;
        for (File f : javaFiles) {
            if ("DynamicPlugin.java".equals(f.getName())) {
                if (found != null) {
                    return null; // ambiguous
                }
                found = f;
            }
        }
        return found;
    }

    private static String readText(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File file, String content) throws RuntimeException {
        try {
            java.nio.file.Files.write(file.toPath(),
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + file, e);
        }
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
        if (code.contains("MainActivity") && !code.contains("class MainActivity")) {
            // Only inject when the host class is referenced AND the plugin
            // does not declare its own MainActivity (a same-named import
            // collides with a local type and breaks the whole compile).
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
        // Structural scans run on a masked copy so braces inside string
        // literals / comments cannot confuse nesting detection.
        String masked = maskLiteralsAndComments(code);
        List<DeclaredReceiver> receivers = findDeclaredReceivers(masked);
        if (receivers.isEmpty()) {
            return code;
        }
        String adapted = code;
        for (DeclaredReceiver receiver : receivers) {
            adapted = replaceReceiverIntentConstructors(adapted, receiver, pluginId);
        }
        return adapted;
    }

    /**
     * Returns a copy of the source with string literals, char literals and
     * comments blanked out; all other characters (braces, declarations)
     * are preserved in place. Used for structural scans, e.g. receiver
     * nesting detection, where a "}" inside a string must not count.
     */
    static String maskLiteralsAndComments(String code) {
        char[] chars = code.toCharArray();
        char[] out = new char[chars.length];
        java.util.Arrays.fill(out, ' ');
        int i = 0;
        int n = chars.length;
        while (i < n) {
            char c = chars[i];
            if (c == '"' || c == '\'') {
                char quote = c;
                i++; // opening quote
                while (i < n) {
                    if (chars[i] == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (chars[i] == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && chars[i + 1] == '/') {
                while (i < n && chars[i] != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                i += 2;
                while (i + 1 < n && !(chars[i] == '*' && chars[i + 1] == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            out[i] = c;
            i++;
        }
        return new String(out);
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
        // The runtime class name must reflect the file's own package (in the
        // multi-file workspace receivers may live in sub-packages).
        String pkg = extractPackage(code);
        while (matcher.find()) {
            String receiverName = matcher.group(1);
            String runtimeClassName = isInsideDynamicPlugin(code, matcher.start())
                ? pkg + ".DynamicPlugin$" + receiverName
                : pkg + "." + receiverName;
            result.add(new DeclaredReceiver(receiverName, runtimeClassName));
        }
        return result;
    }

    /** "package com.meta.plugin.util;" -> "com.meta.plugin.util" (default: com.meta.plugin). */
    private static String extractPackage(String code) {
        Matcher m = Pattern.compile("package\\s+([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)\\s*;")
            .matcher(code);
        return m.find() ? m.group(1) : "com.meta.plugin";
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
