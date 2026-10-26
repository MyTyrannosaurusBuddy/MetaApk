package com.meta.app.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class LLMClientTest {

    @Test
    public void serializeMessages_includesScreenshotAsImageContent() {
        LLMClient.ChatMessage message = LLMClient.ChatMessage.user("Current preview");
        message.imageBase64 = "jpeg-data";
        com.google.gson.JsonArray messages = LLMClient.serializeMessages(java.util.Arrays.asList(message));
        com.google.gson.JsonArray content = messages.get(0).getAsJsonObject().getAsJsonArray("content");
        assertEquals("Current preview", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("data:image/jpeg;base64,jpeg-data", content.get(1).getAsJsonObject()
            .getAsJsonObject("image_url").get("url").getAsString());
    }

    @Test
    public void cancellation_isStickyAndRequestScoped() {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder().url("http://localhost/").build();
        LLMClient.RequestHandle old = new LLMClient.RequestHandle();
        old.cancel();
        okhttp3.Call oldCall = client.newCall(request);
        old.attach(oldCall);
        assertTrue(oldCall.isCanceled());
        LLMClient.RequestHandle next = new LLMClient.RequestHandle();
        okhttp3.Call nextCall = client.newCall(request);
        next.attach(nextCall);
        old.cancel();
        assertFalse(nextCall.isCanceled());
        next.cancel();
        assertTrue(nextCall.isCanceled());
    }

    @Test
    public void httpErrors_distinguishPermanentFailuresFromTransientFailures() {
        assertFalse(LLMClient.isRetryableStatus(400));
        assertFalse(LLMClient.isRetryableStatus(401));
        assertFalse(LLMClient.isRetryableStatus(403));
        assertTrue(LLMClient.isRetryableStatus(408));
        assertTrue(LLMClient.isRetryableStatus(429));
        assertTrue(LLMClient.isRetryableStatus(503));
    }

    @Test
    public void imageFallback_onlyHandlesUnsupportedImageInputs() {
        assertTrue(LLMClient.isUnsupportedImageError("HTTP 400: model does not support image input"));
        assertTrue(LLMClient.isUnsupportedImageError("HTTP 404: No endpoints found that support image input"));
        assertFalse(LLMClient.isUnsupportedImageError("HTTP 401: invalid api key"));
        assertFalse(LLMClient.isUnsupportedImageError("HTTP 400: context length exceeded"));
    }

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
