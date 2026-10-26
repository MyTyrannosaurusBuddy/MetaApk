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
    public void extractPromptTokens_readsServerReportedUsage() {
        assertEquals(1234, LLMClient.extractPromptTokens(
            "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56}}"));
        assertEquals(-1, LLMClient.extractPromptTokens("{\"choices\":[]}"));
        assertEquals(-1, LLMClient.extractPromptTokens("not json"));
        assertEquals(-1, LLMClient.extractPromptTokens(null));
        assertEquals(-1, LLMClient.extractPromptTokens(""));
    }

    @Test
    public void nameForms_normalizesVendorPrefixedAndBareNames() {
        java.util.List<String> prefixed = LLMClient.nameForms("deepseek/deepseek-flash");
        assertEquals(2, prefixed.size());
        assertEquals("deepseek/deepseek-flash", prefixed.get(0));
        assertEquals("deepseek-flash", prefixed.get(1));
        java.util.List<String> bare = LLMClient.nameForms("deepseek-flash");
        assertEquals(1, bare.size());
        assertEquals("deepseek-flash", bare.get(0));
        assertTrue(LLMClient.nameForms(null).isEmpty());
        assertTrue(LLMClient.nameForms("  ").isEmpty());
    }

    @Test
    public void imageFallback_onlyHandlesUnsupportedImageInputs() {
        assertTrue(LLMClient.isUnsupportedImageError("HTTP 400: model does not support image input"));
        assertTrue(LLMClient.isUnsupportedImageError("HTTP 404: No endpoints found that support image input"));
        assertFalse(LLMClient.isUnsupportedImageError("HTTP 401: invalid api key"));
        assertFalse(LLMClient.isUnsupportedImageError("HTTP 400: context length exceeded"));
    }

    @Test
    public void parseModelInfos_keepsIdOwnedByAndModelType() {
        String body = "{\"data\":["
            + "{\"id\":\"deepseek-flash\",\"owned_by\":\"deepseek\",\"model_type\":\"llm\"},"
            + "{\"id\":\"openai/gpt-x\",\"object\":\"model\"},"
            + "{\"id\":\"tts-multivoice-v1\",\"owned_by\":\"xiaomi\",\"model_type\":\"tts\"}]}";
        java.util.List<LLMClient.ModelInfo> infos = LLMClient.parseModelInfos(body);
        assertEquals(3, infos.size());
        assertEquals("deepseek-flash", infos.get(0).id);
        assertEquals("deepseek", infos.get(0).ownedBy);
        assertEquals("llm", infos.get(0).modelType);
        assertEquals("openai/gpt-x", infos.get(1).id);
        assertEquals("", infos.get(1).ownedBy);
        assertEquals("", infos.get(1).modelType);
        assertEquals("tts", infos.get(2).modelType);
    }

    @Test
    public void candidates_bareIdGetsQualifiedFormAndQualifiedIdStaysSingle() {
        LLMClient.ModelInfo bare = new LLMClient.ModelInfo("deepseek-flash", "deepseek", "llm");
        assertEquals(2, bare.candidates().size());
        assertEquals("deepseek-flash", bare.candidates().get(0));
        assertEquals("deepseek/deepseek-flash", bare.candidates().get(1));
        LLMClient.ModelInfo qualified = new LLMClient.ModelInfo("openai/gpt-x", "openai", "llm");
        assertEquals(1, qualified.candidates().size());
        assertEquals("openai/gpt-x", qualified.candidates().get(0));
        LLMClient.ModelInfo noVendor = new LLMClient.ModelInfo("Atria-Dawn", "", "");
        assertEquals(1, noVendor.candidates().size());
    }

    @Test
    public void chatCapable_filtersDeclaredNonChatTypesAndKeepsUnknown() {
        assertTrue(new LLMClient.ModelInfo("a", "", "").chatCapable());
        assertTrue(new LLMClient.ModelInfo("a", "", "llm").chatCapable());
        assertFalse(new LLMClient.ModelInfo("a", "", "tts").chatCapable());
        assertFalse(new LLMClient.ModelInfo("a", "", "text-embedding").chatCapable());
        assertFalse(new LLMClient.ModelInfo("a", "", "speech2text").chatCapable());
        assertFalse(new LLMClient.ModelInfo("a", "", "image_generation").chatCapable());
    }
}
