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
    public void buildRetryPrompt_containsErrorAndOriginalIntent() {
        String prompt = MetaEngine.buildRetryPrompt("turn into a calculator", "cannot find symbol",
            null, null);

        assertTrue(prompt.contains("cannot find symbol"));
        assertTrue(prompt.contains("turn into a calculator"));
        assertTrue(prompt.contains("<java_code>"));
    }

    @Test
    public void buildRetryPrompt_embedsPreviousSource() {
        String prompt = MetaEngine.buildRetryPrompt("calculator", "1+1=Error", "public class X {}", null);

        assertTrue(prompt.contains("public class X {}"));
        assertTrue(prompt.contains("minimal fixes"));
    }

    @Test
    public void buildRetryPrompt_includesFeedbackHistory() {
        java.util.List<String> history = java.util.Arrays.asList("feedback A", "feedback B");

        String prompt = MetaEngine.buildRetryPrompt("calculator", "feedback C", null, history);

        assertTrue(prompt.contains("feedback A"));
        assertTrue(prompt.contains("feedback B"));
        assertTrue(prompt.contains("feedback C"));
        assertTrue(prompt.contains("all of them must be satisfied"));
    }

    @Test
    public void buildRetryPrompt_doesNotDuplicateLatestFeedback() {
        java.util.List<String> history = java.util.Arrays.asList("feedback A", "feedback B");

        String prompt = MetaEngine.buildRetryPrompt("calculator", "User feedback: feedback B", null, history);

        assertTrue(prompt.contains("feedback A"));
        // The latest feedback already appears in the error message; the history block must not repeat it
        int firstOccurrence = prompt.indexOf("feedback B");
        int lastOccurrence = prompt.lastIndexOf("feedback B");
        assertEquals(firstOccurrence, lastOccurrence);
    }
}
