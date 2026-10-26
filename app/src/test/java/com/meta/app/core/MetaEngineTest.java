package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MetaEngineTest {

    @Test
    public void pendingLineage_winsForFirstGenerationAndReplacement() {
        assertEquals("workspace-a", MetaEngine.resolvePluginLineage("workspace-a", null, null));
        assertEquals("workspace-b", MetaEngine.resolvePluginLineage("workspace-b", "old-app", "saved-app"));
        assertEquals("saved-app", MetaEngine.resolvePluginLineage(null, null, "saved-app"));
        assertEquals("old-app", MetaEngine.resolvePluginLineage(null, "old-app", "saved-app"));
    }

    @Test
    public void buildFailureMessage_escalatesAfterIdenticalErrors() {
        MetaEngine.AgentRun run = new MetaEngine.AgentRun(
            new Workspace(new java.io.File(System.getProperty("java.io.tmpdir"))),
            new java.util.ArrayList<>(), null, null, null);
        String first = MetaEngine.buildFailureMessage(run, "error X");
        assertTrue(first.contains("Build failed"));
        MetaEngine.buildFailureMessage(run, "error X");
        String third = MetaEngine.buildFailureMessage(run, "error X");
        assertTrue(third.contains("3 times in a row"));
        assertTrue(third.contains("error X"));
        // A different error resets the escalation counter.
        String other = MetaEngine.buildFailureMessage(run, "error Y");
        assertTrue(other.contains("Build failed"));
        assertFalse(other.contains("times in a row"));
    }

    @Test
    public void convergenceMonitor_escalatesOncePerPatternAtThreshold() {
        MetaEngine.ConvergenceMonitor monitor = new MetaEngine.ConvergenceMonitor();
        assertFalse(monitor.register("build:errorX"));
        assertFalse(monitor.register("build:errorX"));
        assertTrue("Third occurrence must escalate", monitor.register("build:errorX"));
        assertTrue(monitor.escalated("build:errorX"));
        assertFalse("Already escalated patterns do not re-escalate", monitor.register("build:errorX"));
        assertEquals(4, monitor.count("build:errorX"));
        // Different patterns track independently.
        assertFalse(monitor.register("reject:su"));
        assertFalse(monitor.escalated("reject:su"));
    }

    @Test
    public void agentRequirements_keepsOriginalIntentAndCurrentTaskOnly() {
        String prompt = MetaEngine.agentRequirements("inventory app", "fix search");
        assertTrue(prompt.contains("inventory app"));
        assertTrue(prompt.contains("fix search"));
        // The revision history is NOT duplicated into the requirements block:
        // it already lives in the conversation messages.
        assertFalse(prompt.contains("Accumulated"));
    }

    @Test
    public void feedbackSessionPrompt_answersCurrentFeedbackOnly() {
        String prompt = MetaEngine.feedbackSessionPrompt("帽兜小一点");
        assertTrue(prompt.contains("帽兜小一点"));
        assertTrue(prompt.contains("Inspect the files"));
        // No permanent-obligation clause: contradictory or superseded earlier
        // feedback must not be re-imposed on every revision (endless polish
        // loops observed with "all previous requirements must stay satisfied").
        assertFalse(prompt.contains("All previous requirements"));
        assertFalse(prompt.contains("must stay satisfied"));
    }

    @Test
    public void extractJavaCode_readsTaggedBlock() {
        String response = "preamble\n<java_code>\npublic class Demo {}\n</java_code>\n<description>ok</description>";

        String javaCode = MetaEngine.extractJavaCode(response);

        assertEquals("public class Demo {}", javaCode);
    }

    @Test
    public void extractJavaCode_fallsBackToFencedJavaBlock() {
        String response = "```java\npublic class Demo {}\n```";

        String javaCode = MetaEngine.extractJavaCode(response);

        assertEquals("public class Demo {}", javaCode);
    }

    @Test
    public void extractJavaCode_fallsBackToAnyFencedBlock() {
        String response = "```kotlin\nfun demo() {}\n```";

        String javaCode = MetaEngine.extractJavaCode(response);

        assertEquals("fun demo() {}", javaCode);
    }

    @Test
    public void extractJavaCode_returnsNullWhenNoCodeExists() {
        assertNull(MetaEngine.extractJavaCode("just an explanation, no code"));
    }

    @Test
    public void extractJavaCode_prefersTaggedBlockContainingDynamicPlugin() {
        String response = "<java_code>\npublic class Draft {}\n</java_code>\n"
            + "<java_code>\npublic class DynamicPlugin {}\n</java_code>";

        String javaCode = MetaEngine.extractJavaCode(response);

        assertEquals("public class DynamicPlugin {}", javaCode);
    }

    @Test
    public void extractJavaCode_joinsDistinctFencedBlocks() {
        String response = "```java\npublic class DynamicPlugin {}\n```\n"
            + "```java\nclass Helper {}\n```";

        String javaCode = MetaEngine.extractJavaCode(response);

        assertTrue(javaCode.contains("public class DynamicPlugin {}"));
        assertTrue(javaCode.contains("class Helper {}"));
    }

    @Test
    public void pendingTask_serializesAndParsesRoundTrip() {
        MetaEngine.PendingTask task = new MetaEngine.PendingTask();
        task.type = "FEEDBACK";
        task.originalIntent = "make a calculator";
        task.errorMessage = "User feedback: 1+1 shows Error";
        task.replacingId = "plugin_abc";
        task.timestamp = 123456L;

        MetaEngine.PendingTask parsed =
            MetaEngine.parsePendingTask(MetaEngine.serializePendingTask(task));

        assertEquals(task.type, parsed.type);
        assertEquals(task.originalIntent, parsed.originalIntent);
        assertEquals(task.errorMessage, parsed.errorMessage);
        assertEquals(task.replacingId, parsed.replacingId);
        assertEquals(task.timestamp, parsed.timestamp);
    }

    @Test
    public void parsePendingTask_handlesEmptyAndGarbage() {
        assertNull(MetaEngine.parsePendingTask(null));
        assertNull(MetaEngine.parsePendingTask(""));
        assertNull(MetaEngine.parsePendingTask("not json {{{"));
    }

    @Test
    public void extractOriginalIntent_readsTheRequirementsBlock() {
        assertEquals("扫雷", MetaEngine.extractOriginalIntent(
            "Original application requirements:\n扫雷\n\nCurrent task:\nfix"));
        assertEquals("multi-line\nintent", MetaEngine.extractOriginalIntent(
            "Original application requirements:\nmulti-line\nintent\n\nCurrent task:"));
        assertEquals("", MetaEngine.extractOriginalIntent("no marker here"));
        assertEquals("", MetaEngine.extractOriginalIntent(null));
    }

    @Test
    public void isReplaceInPlace_requiresDistinctNewBuild() {
        // Normal revision: a new build replaces the version under revision.
        assertTrue(MetaEngine.isReplaceInPlace("plugin_old", "plugin_old", "plugin_new"));
        // Cold-start restore: the restored plugin carries its own previous id.
        // Treating this as a replacement would delete the very files being
        // persisted (observed data loss). It must be a no-op attach.
        assertFalse(MetaEngine.isReplaceInPlace("plugin_old", "plugin_old", "plugin_old"));
        // No revision in flight, or nothing replaced yet.
        assertFalse(MetaEngine.isReplaceInPlace("plugin_old", null, "plugin_new"));
        assertFalse(MetaEngine.isReplaceInPlace(null, "plugin_old", "plugin_new"));
        // Different revision target: not THIS plugin's replacement.
        assertFalse(MetaEngine.isReplaceInPlace("plugin_old", "plugin_other", "plugin_new"));
    }
}
