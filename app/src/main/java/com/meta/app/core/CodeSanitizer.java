package com.meta.app.core;

import java.util.Arrays;
import java.util.List;
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
}
