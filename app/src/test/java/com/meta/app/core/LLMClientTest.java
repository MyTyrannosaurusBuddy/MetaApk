package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LLMClientTest {

    @Test
    public void isDegenerate_returnsTrueForRepeatedTailPattern() {
        String text = "abc123".repeat(20);

        assertTrue(LLMClient.isDegenerate(text));
    }

    @Test
    public void isDegenerate_returnsFalseForShortText() {
        assertFalse(LLMClient.isDegenerate("abc123".repeat(10)));
    }

    @Test
    public void isDegenerate_returnsFalseForNonRepeatingText() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            builder.append("token-").append(i).append('-');
        }

        assertFalse(LLMClient.isDegenerate(builder.toString()));
    }
}
