package com.meta.app.core;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CodeSanitizerTest {

    private final CodeSanitizer sanitizer = new CodeSanitizer();

    @Test
    public void allowsReceiverDefinedInAnotherWorkspaceFile() {
        String caller = "Intent i = new Intent(c, Alarm.class);\n"
            + "PendingIntent p = PendingIntent.getBroadcast(c, 1, i, 0);";
        String receiver = "class Alarm extends BroadcastReceiver {}";
        assertTrue(sanitizer.checkWorkspace(java.util.Arrays.asList(caller, receiver)).passed);
        assertFalse(sanitizer.checkWorkspace(java.util.Arrays.asList(caller, "class Helper {} ")).passed);
        assertFalse(sanitizer.checkWorkspace(java.util.Arrays.asList(caller, receiver, "System.exit(0)")).passed);
    }

    /**
     * Builds a minimal .class file whose constant pool contains exactly the
     * given Utf8 entries. The bytecode scanner only reads the constant pool,
     * so no full class file is needed for these tests.
     */
    private static File writeFakeClass(String... utf8Strings) throws Exception {
        ByteArrayOutputStream cp = new ByteArrayOutputStream();
        for (String s : utf8Strings) {
            cp.write(1); // CONSTANT_Utf8
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            cp.write((b.length >> 8) & 0xFF);
            cp.write(b.length & 0xFF);
            cp.write(b);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(0xCAFEBABE);
        d.writeShort(0);
        d.writeShort(52);
        d.writeShort(utf8Strings.length + 1); // cp_count
        cp.writeTo(out);
        d.flush();

        File dir = Files.createTempDirectory("bytecode-scan").toFile();
        File f = new File(dir, "X.class");
        Files.write(f.toPath(), out.toByteArray());
        return dir;
    }

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

    // ---------- Post-compile bytecode scan ----------

    /**
     * Hand-builds a .class file's constant pool (magic + version + entries),
     * with real CONSTANT_Class / CONSTANT_NameAndType / CONSTANT_Methodref
     * structure, so the scanner's reference resolution is exercised exactly
     * without needing a JDK compiler in the test JVM.
     */
    private static class PoolBuilder {
        final java.util.List<String> utf8 = new java.util.ArrayList<>();
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        int nextIndex = 1;

        int addUtf8(String s) {
            utf8.add(s);
            body.write(1); // CONSTANT_Utf8
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            body.write((b.length >> 8) & 0xFF);
            body.write(b.length & 0xFF);
            body.write(b, 0, b.length);
            return nextIndex++;
        }

        int addClass(String internalName) {
            int nameIdx = addUtf8(internalName);
            body.write(7); // CONSTANT_Class
            body.write((nameIdx >> 8) & 0xFF);
            body.write(nameIdx & 0xFF);
            return nextIndex++;
        }

        int addNameAndType(String name, String descriptor) {
            int nameIdx = addUtf8(name);
            int descIdx = addUtf8(descriptor);
            body.write(12); // CONSTANT_NameAndType
            body.write((nameIdx >> 8) & 0xFF);
            body.write(nameIdx & 0xFF);
            body.write((descIdx >> 8) & 0xFF);
            body.write(descIdx & 0xFF);
            return nextIndex++;
        }

        void addMethodref(String internalName, String methodName, String descriptor) {
            int classIdx = addClass(internalName);
            int natIdx = addNameAndType(methodName, descriptor);
            body.write(10); // CONSTANT_Methodref
            body.write((classIdx >> 8) & 0xFF);
            body.write(classIdx & 0xFF);
            body.write((natIdx >> 8) & 0xFF);
            body.write(natIdx & 0xFF);
            nextIndex++;
        }

        File write(String className) throws Exception {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeInt(0xCAFEBABE);
            d.writeShort(0);
            d.writeShort(52);
            d.writeShort(nextIndex); // cp_count
            body.writeTo(out);
            File dir = Files.createTempDirectory("bytecode-scan").toFile();
            File f = new File(dir, className + ".class");
            Files.write(f.toPath(), out.toByteArray());
            return dir;
        }
    }

    @Test
    public void bytecodeScan_blocksRuntimeExec() throws Exception {
        PoolBuilder pool = new PoolBuilder();
        pool.addMethodref("java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;");

        CodeSanitizer.SanitizeResult result =
            CodeSanitizer.scanCompiledClasses(pool.write("RuntimeExecPlugin"));

        assertFalse(result.passed);
        assertTrue(result.reason.contains("Runtime.exec"));
    }

    @Test
    public void bytecodeScan_blocksSystemExitCall() throws Exception {
        PoolBuilder pool = new PoolBuilder();
        pool.addMethodref("java/lang/System", "exit", "(I)V");

        CodeSanitizer.SanitizeResult result =
            CodeSanitizer.scanCompiledClasses(pool.write("SystemExitPlugin"));

        assertFalse(result.passed);
        assertTrue(result.reason.contains("System.exit"));
    }

    @Test
    public void bytecodeScan_blocksReflectionRuntimeExec() throws Exception {
        // Reflection bypass: the dotted class name appears as a STRING in
        // the pool (Class.forName("java.lang.Runtime").getMethod("exec")).
        PoolBuilder pool = new PoolBuilder();
        pool.addUtf8("java.lang.Runtime");
        pool.addUtf8("exec");

        CodeSanitizer.SanitizeResult result =
            CodeSanitizer.scanCompiledClasses(pool.write("ReflectiveExec"));

        assertFalse(result.passed);
        assertTrue(result.reason.contains("Runtime.exec"));
    }

    @Test
    public void bytecodeScan_allowsExitButtonWithSystemUse() throws Exception {
        // Regression: a button labeled "Exit" next to ordinary System use is
        // NOT a System.exit call and must pass.
        PoolBuilder pool = new PoolBuilder();
        pool.addUtf8("Exit");
        pool.addMethodref("java/lang/System", "currentTimeMillis", "()J");

        CodeSanitizer.SanitizeResult result =
            CodeSanitizer.scanCompiledClasses(pool.write("ExitButtonApp"));

        assertTrue(result.passed);
    }

    @Test
    public void bytecodeScan_allowsExecutorWithRuntimeCpuCount() throws Exception {
        // Regression: an executor's "execute" next to the legit
        // Runtime.availableProcessors() must not be mistaken for Runtime.exec.
        PoolBuilder pool = new PoolBuilder();
        pool.addMethodref("java/lang/Runtime", "availableProcessors", "()I");
        pool.addMethodref("java/util/concurrent/ExecutorService", "execute",
            "(Ljava/lang/Runnable;)V");

        CodeSanitizer.SanitizeResult result =
            CodeSanitizer.scanCompiledClasses(pool.write("ExecutorApp"));

        assertTrue(result.passed);
    }

    @Test
    public void bytecodeScan_blocksProcessBuilder() throws Exception {
        File dir = writeFakeClass("java/lang/ProcessBuilder", "<init>");

        CodeSanitizer.SanitizeResult result = CodeSanitizer.scanCompiledClasses(dir);

        assertFalse(result.passed);
        assertTrue(result.reason.contains("ProcessBuilder"));
    }

    @Test
    public void bytecodeScan_blocksInternalApiReference() throws Exception {
        File dir = writeFakeClass("com/android/internal/os/RuntimeInit");

        CodeSanitizer.SanitizeResult result = CodeSanitizer.scanCompiledClasses(dir);

        assertFalse(result.passed);
        assertTrue(result.reason.contains("com.android.internal"));
    }

    @Test
    public void bytecodeScan_allowsBenignClass() throws Exception {
        File dir = writeFakeClass("java/lang/String", "android/widget/Button", "setText");

        CodeSanitizer.SanitizeResult result = CodeSanitizer.scanCompiledClasses(dir);

        assertTrue(result.passed);
    }
}
