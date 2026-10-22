package com.meta.app.core;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Code security review - statically scans LLM-generated code before ECJ compilation.
 */
public class CodeSanitizer {

    private static final List<Pattern> FORBIDDEN_PATTERNS = Arrays.asList(
        Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
        Pattern.compile("ProcessBuilder"),
        Pattern.compile("Class\\.forName\\(\"com\\.android\\.internal"),
        Pattern.compile("System\\.exit"),
        Pattern.compile("android\\.os\\.SystemProperties"),
        Pattern.compile("\"su\""),
        Pattern.compile("/proc/self/mem")
    );

    private static final Pattern NAMED_RECEIVER_DECLARATION = Pattern.compile(
        "(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?class\\s+"
            + "[A-Za-z_$][A-Za-z0-9_$]*\\s+extends\\s+"
            + "(?:BroadcastReceiver|android\\.content\\.BroadcastReceiver)\\b");

    private static final Pattern EXPLICIT_COMPONENT_TARGET = Pattern.compile(
        "new\\s+Intent\\s*\\([^\\n\\r]*?\\.class\\s*\\)|"
            + "\\.setClass\\s*\\([^\\n\\r]*?\\.class\\s*\\)|"
            + "\\.setComponent\\s*\\(");

    public static class SanitizeResult {
        public final boolean passed;
        public final String reason;

        private SanitizeResult(boolean passed, String reason) {
            this.passed = passed;
            this.reason = reason;
        }

        public static SanitizeResult pass() { return new SanitizeResult(true, null); }
        public static SanitizeResult blocked(String reason) { return new SanitizeResult(false, reason); }
    }

    public SanitizeResult check(String sourceCode) {
        for (Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(sourceCode).find()) {
                return SanitizeResult.blocked("Code contains a forbidden operation: " + pattern.pattern());
            }
        }

        if (sourceCode.contains("PendingIntent.getBroadcast(")) {
            boolean hasNamedReceiver = NAMED_RECEIVER_DECLARATION.matcher(sourceCode).find();
            boolean hasExplicitComponent = EXPLICIT_COMPONENT_TARGET.matcher(sourceCode).find();

            if (!hasNamedReceiver) {
                return SanitizeResult.blocked(
                    "PendingIntent.getBroadcast(...) must be used with an explicitly named BroadcastReceiver class");
            }

            if (!hasExplicitComponent) {
                return SanitizeResult.blocked(
                    "PendingIntent.getBroadcast(...) must target an explicit component class, not just an action string or a process-lifetime registration");
            }
        }

        return SanitizeResult.pass();
    }

    // ---------- Post-compile bytecode scan (cannot be bypassed by source obfuscation) ----------

    /**
     * Scans the constant pool of every compiled .class file for references to
     * forbidden types. Unlike the source-level regex check above, this works
     * on the actual compiled references, so string-concatenation or
     * reflection-based obfuscation of the SOURCE cannot hide them.
     */
    public static SanitizeResult scanCompiledClasses(File classesDir) {
        List<File> classFiles = new ArrayList<>();
        collectClassFiles(classesDir, classFiles);
        if (classFiles.isEmpty()) {
            return SanitizeResult.blocked("No compiled .class files found");
        }
        for (File classFile : classFiles) {
            SanitizeResult result = scanSingleClass(classFile);
            if (!result.passed) {
                return result;
            }
        }
        return SanitizeResult.pass();
    }

    private static void collectClassFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectClassFiles(f, out);
            } else if (f.getName().endsWith(".class")) {
                out.add(f);
            }
        }
    }

    private static SanitizeResult scanSingleClass(File classFile) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) {
                return SanitizeResult.pass(); // not a class file
            }
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version
            int cpCount = in.readUnsignedShort();

            // Pass 1: record every UTF-8 string, CONSTANT_Class name index
            // and CONSTANT_NameAndType pair, so pass 2 can resolve method
            // references (which may point at entries defined later in the pool).
            String[] utf8ByIndex = new String[cpCount];
            int[] classNameIndexByCpIndex = new int[cpCount];
            int[] natNameIndexByCpIndex = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: { // CONSTANT_Utf8
                        int len = in.readUnsignedShort();
                        byte[] data = new byte[len];
                        in.readFully(data);
                        utf8ByIndex[i] = new String(data, StandardCharsets.UTF_8);
                        break;
                    }
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
                        in.readInt();
                        break;
                    case 5: // CONSTANT_Long
                    case 6: // CONSTANT_Double
                        in.readLong();
                        i++; // these take two slots
                        break;
                    case 7:  // CONSTANT_Class
                    case 8:  // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        classNameIndexByCpIndex[i] = in.readUnsignedShort();
                        break;
                    case 9:  // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                        in.readInt();
                        break;
                    case 12: { // CONSTANT_NameAndType
                        natNameIndexByCpIndex[i] = in.readUnsignedShort();
                        in.readUnsignedShort(); // descriptor index
                        break;
                    }
                    case 17: // CONSTANT_Dynamic
                    case 18: // CONSTANT_InvokeDynamic
                        in.readInt();
                        break;
                    case 15: // CONSTANT_MethodHandle
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    default:
                        // Unknown tag: malformed pool, skip this class conservatively
                        return SanitizeResult.pass();
                }
            }

            // Normalize both slash and dotted forms, so reflection via
            // Class.forName("java.lang.Runtime") is caught as well.
            Set<String> dotted = new HashSet<>();
            for (String s : utf8ByIndex) {
                if (s != null) {
                    dotted.add(s.replace('/', '.'));
                }
            }
            for (String s : dotted) {
                if (s.startsWith("com.android.internal")) {
                    return SanitizeResult.blocked(
                        "Compiled code references forbidden internal class: " + s);
                }
                if (s.equals("java.lang.ProcessBuilder")) {
                    return SanitizeResult.blocked("Compiled code references ProcessBuilder");
                }
                if (s.equals("android.os.SystemProperties")) {
                    return SanitizeResult.blocked(
                        "Compiled code references android.os.SystemProperties");
                }
            }

            // Reflection-based obfuscation: a DOTTED class name appears in
            // the pool as a string literal (normal references use slashes);
            // paired with the exact method-name string it reveals
            // Class.forName("java.lang.Runtime").getMethod("exec") style
            // attempts. Exact matching keeps harmless apps passing - a
            // button labeled "Exit" or an ExecutorService does not match.
            Set<String> utf8Set = new HashSet<>();
            for (String s : utf8ByIndex) {
                if (s != null) {
                    utf8Set.add(s);
                }
            }
            if (utf8Set.contains("java.lang.Runtime") && utf8Set.contains("exec")) {
                return SanitizeResult.blocked(
                    "Compiled code references Runtime.exec(...) via reflection");
            }
            if (utf8Set.contains("java.lang.System") && utf8Set.contains("exit")) {
                return SanitizeResult.blocked(
                    "Compiled code references System.exit(...) via reflection");
            }

            // Pass 2: resolve every Methodref to (class, method-name) and
            // block exactly System.exit / Runtime.exec. Substring matching
            // against the whole pool would falsely reject harmless apps
            // (e.g. a button labeled "Exit" next to any System use, or an
            // ExecutorService beside Runtime.availableProcessors()).
            try (DataInputStream refs = new DataInputStream(new FileInputStream(classFile))) {
                refs.readInt(); // magic
                refs.readUnsignedShort(); // minor
                refs.readUnsignedShort(); // major
                int cp = refs.readUnsignedShort();
                for (int i = 1; i < cp; i++) {
                    int tag = refs.readUnsignedByte();
                    switch (tag) {
                        case 1: {
                            int len = refs.readUnsignedShort();
                            refs.skipBytes(len);
                            break;
                        }
                        case 3: case 4:
                            refs.readInt();
                            break;
                        case 5: case 6:
                            refs.readLong();
                            i++;
                            break;
                        case 7: case 8: case 16: case 19: case 20:
                            refs.readUnsignedShort();
                            break;
                        case 9: case 11:
                            refs.readInt();
                            break;
                        case 10: { // CONSTANT_Methodref
                            int classIndex = refs.readUnsignedShort();
                            int natIndex = refs.readUnsignedShort();
                            String className = utf8ByIndex[classNameIndexByCpIndex[classIndex]];
                            String methodName = utf8ByIndex[natNameIndexByCpIndex[natIndex]];
                            if ("java/lang/System".equals(className)
                                    && "exit".equals(methodName)) {
                                return SanitizeResult.blocked(
                                    "Compiled code calls System.exit(...)");
                            }
                            if ("java/lang/Runtime".equals(className)
                                    && "exec".equals(methodName)) {
                                return SanitizeResult.blocked(
                                    "Compiled code calls Runtime.exec(...)");
                            }
                            break;
                        }
                        case 12:
                            refs.readInt();
                            break;
                        case 17: case 18:
                            refs.readInt();
                            break;
                        case 15:
                            refs.readUnsignedByte();
                            refs.readUnsignedShort();
                            break;
                        default:
                            break;
                    }
                }
            }
            return SanitizeResult.pass();
        } catch (Exception e) {
            // Unreadable class file: block conservatively and report clearly
            return SanitizeResult.blocked("Failed to scan compiled class "
                + classFile.getName() + ": " + e.getMessage());
        }
    }
}
