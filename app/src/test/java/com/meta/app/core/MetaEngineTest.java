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
    public void agentRequirements_includeOriginalAndAccumulatedFeedback() {
        String prompt = MetaEngine.agentRequirements("inventory app", "fix search",
            java.util.Arrays.asList("keep offline mode", "support barcode"));
        assertTrue(prompt.contains("inventory app"));
        assertTrue(prompt.contains("fix search"));
        assertTrue(prompt.contains("keep offline mode"));
        assertTrue(prompt.contains("support barcode"));
    }

    @Test
    public void agentRequirements_capsTheAccumulatedHistory() {
        java.util.List<String> history = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) history.add("request " + i);
        String prompt = MetaEngine.agentRequirements("app", "task", history);
        // The most recent requests survive; the earliest are folded into a note.
        assertTrue(prompt.contains("request 14"));
        assertTrue(prompt.contains("request 5"));
        assertFalse(prompt.contains("request 0"));
        assertTrue(prompt.contains("5 earlier improvement requests were compacted"));
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
}
