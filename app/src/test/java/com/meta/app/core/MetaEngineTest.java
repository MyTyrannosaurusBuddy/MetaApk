package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MetaEngineTest {

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
    public void truncateSourceMiddle_keepsSmallSourcesIntact() {
        String small = "public class DynamicPlugin {}";

        assertEquals(small, MetaEngine.truncateSourceMiddle(small));
    }

    @Test
    public void truncateSourceMiddle_elidesMiddleOfLargeSources() {
        StringBuilder sb = new StringBuilder();
        sb.append("public class DynamicPlugin {\n");
        for (int i = 0; i < 3000; i++) {
            sb.append("  // filler line ").append(i).append("\n");
        }
        sb.append("  public void tail() {}\n}\n");
        String source = sb.toString();
        assertTrue(source.length() > 30 * 1024);

        String truncated = MetaEngine.truncateSourceMiddle(source);

        assertTrue(truncated.startsWith("public class DynamicPlugin"));
        assertTrue(truncated.contains("middle elided"));
        assertTrue(truncated.endsWith("  public void tail() {}\n}\n"));
        assertTrue(truncated.length() < 32 * 1024);
    }

    @Test
    public void buildRetryPrompt_containsErrorAndOriginalIntent() {
        String prompt = MetaEngine.buildRetryPrompt("turn into a calculator", "cannot find symbol",
            null, null, null);

        assertTrue(prompt.contains("cannot find symbol"));
        assertTrue(prompt.contains("turn into a calculator"));
        assertTrue(prompt.contains("<java_code>"));
    }

    @Test
    public void buildRetryPrompt_embedsPreviousSource() {
        String prompt = MetaEngine.buildRetryPrompt("calculator", "1+1=Error", "public class X {}",
            null, null);

        assertTrue(prompt.contains("public class X {}"));
        assertTrue(prompt.contains("minimal fixes"));
    }

    @Test
    public void buildRetryPrompt_prefersFailedSourceOverPreviousSource() {
        String prompt = MetaEngine.buildRetryPrompt("calculator", "compile error",
            "public class Old {}", "public class Failed {}", null);

        assertTrue(prompt.contains("public class Failed {}"));
        assertTrue(prompt.contains("code that just failed"));
        assertTrue(!prompt.contains("public class Old {}"));
    }

    @Test
    public void buildRetryPrompt_includesFeedbackHistory() {
        java.util.List<String> history = java.util.Arrays.asList("feedback A", "feedback B");

        String prompt = MetaEngine.buildRetryPrompt("calculator", "feedback C", null, null, history);

        assertTrue(prompt.contains("feedback A"));
        assertTrue(prompt.contains("feedback B"));
        assertTrue(prompt.contains("feedback C"));
        assertTrue(prompt.contains("all of them must be satisfied"));
    }

    @Test
    public void buildRetryPrompt_doesNotDuplicateLatestFeedback() {
        java.util.List<String> history = java.util.Arrays.asList("feedback A", "feedback B");

        String prompt = MetaEngine.buildRetryPrompt("calculator", "User feedback: feedback B", null,
            null, history);

        assertTrue(prompt.contains("feedback A"));
        // The latest feedback already appears in the error message; the history block must not repeat it
        int firstOccurrence = prompt.indexOf("feedback B");
        int lastOccurrence = prompt.lastIndexOf("feedback B");
        assertEquals(firstOccurrence, lastOccurrence);
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
