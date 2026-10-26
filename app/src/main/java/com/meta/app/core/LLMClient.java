package com.meta.app.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meta.app.config.LlmConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LLM client - OpenAI-compatible protocol with streaming SSE.
 */
public class LLMClient {
    private static final String TAG = "LLMClient";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final LlmConfig config;
    private final Handler mainHandler;
    private String systemPrompt;

    /** The request currently in flight, used for cancellation. */
    private volatile RequestHandle currentToolsRequest;
    private final java.util.Set<String> textOnlyModels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public interface ModelsCallback {
        void onModels(List<String> modelIds);
        void onError(String message);
    }

    public LLMClient(Context context, LlmConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Load the system prompt
        try {
            InputStream is = context.getAssets().open("system_prompt.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            this.systemPrompt = sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to load system prompt", e);
            this.systemPrompt = "You are MetaApk code generator.";
        }
    }

    /**
     * Result of a single request.
     */
    private static class Attempt {
        final boolean success;
        final String text;
        final String error;
        /** Whether this failure is worth switching to the backup model (HTTP failures and empty content are). */
        final boolean retryable;

        Attempt(boolean success, String text, String error, boolean retryable) {
            this.success = success;
            this.text = text;
            this.error = error;
            this.retryable = retryable;
        }

        static Attempt ok(String text) { return new Attempt(true, text, null, false); }

        static Attempt fail(String error, boolean retryable) {
            return new Attempt(false, null, error, retryable);
        }
    }

    /**
     * Cancels the current request (user pressed the stop button).
     */
    public void cancel() {
        RequestHandle tools = currentToolsRequest;
        if (tools != null) tools.cancel();
    }

    /** The system prompt text loaded from assets. */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Fetches the model list from an OpenAI-compatible endpoint
     * ({baseUrl}/models). Runs on a background thread; callbacks arrive on
     * the main thread.
     */
    public void fetchModels(String baseUrl, String apiKey, ModelsCallback callback) {
        new Thread(() -> {            String url = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "") + "/models";
            Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String detail = "HTTP " + response.code();
                    try {
                        if (response.body() != null) {
                            String body = response.body().string();
                            if (body != null && !body.isEmpty()) {
                                detail = body;
                            }
                        }
                    } catch (Exception ignored) { }
                    postModelsError(callback, detail);
                    return;
                }
                String body = response.body() != null ? response.body().string() : "";
                List<String> ids = new ArrayList<>();
                try {
                    JsonArray data = JsonParser.parseString(body)
                        .getAsJsonObject().getAsJsonArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.size(); i++) {
                            JsonObject item = data.get(i).getAsJsonObject();
                            if (item.has("id") && !item.get("id").isJsonNull()) {
                                String id = item.get("id").getAsString();
                                if (!id.isEmpty()) {
                                    ids.add(id);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    postModelsError(callback, "unexpected response format");
                    return;
                }
                if (ids.isEmpty()) {
                    postModelsError(callback, "no models returned");
                    return;
                }
                // No cap: keep the server order (popular endpoints list newest/
                // featured first) and rely on the picker's live search to
                // narrow a long list - truncating would silently hide models.
                final List<String> result = new ArrayList<>(ids);
                mainHandler.post(() -> callback.onModels(result));
            } catch (Exception e) {
                Log.e(TAG, "Fetch models failed", e);
                postModelsError(callback, e.getMessage());
            }
        }).start();
    }

    private void postModelsError(ModelsCallback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    /**
     * Queries the endpoint's model metadata for the configured model's
     * context length (tokens). Blocking; cached per process. Returns -1 when
     * the metadata is unavailable, so callers fall back to the conservative
     * default budget.
     */
    private volatile int cachedContextLength = -2; // -2 = not fetched yet

    public synchronized int fetchContextLength() {
        if (cachedContextLength != -2) {
            return cachedContextLength;
        }
        cachedContextLength = -1;
        try {
            String url = (config.getBaseUrl() == null ? "" : config.getBaseUrl())
                .replaceAll("/+$", "") + "/models";
            Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .get()
                .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return -1;
                }
                String body = response.body() != null ? response.body().string() : "";
                JsonArray data = JsonParser.parseString(body)
                    .getAsJsonObject().getAsJsonArray("data");
                String target = config.getModel();
                if (data != null && target != null) {
                    for (int i = 0; i < data.size(); i++) {
                        JsonObject item = data.get(i).getAsJsonObject();
                        if (target.equals(item.get("id").getAsString())
                                && item.has("context_length")
                                && !item.get("context_length").isJsonNull()) {
                            int context = item.get("context_length").getAsInt();
                            if (context > 0) {
                                cachedContextLength = context;
                                return context;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Context length fetch failed", e);
        }
        return -1;
    }

    // ---------- Tool calling (agent turns) ----------

    /** One conversation message for a tool-calling turn. */
    public static class ChatMessage {
        /** "system" | "user" | "assistant" | "tool" */
        public String role;
        /** Text content; null for assistant messages that only carry tool calls. */
        public String content;
        /** Optional preview image, sent as multimodal user content. */
        public String imageBase64;
        /** Assistant-side tool calls (role = assistant). */
        public List<ToolCall> toolCalls;
        /** Tool result fields (role = tool). */
        public String toolCallId;
        public String name;

        public ChatMessage() { }

        public static ChatMessage system(String text) {
            ChatMessage m = new ChatMessage();
            m.role = "system";
            m.content = text;
            return m;
        }

        public static ChatMessage user(String text) {
            ChatMessage m = new ChatMessage();
            m.role = "user";
            m.content = text;
            return m;
        }
    }

    /** A single function call requested by the model. */
    public static class ToolCall {
        public String id;
        public String name;
        /** Raw JSON string of the arguments. */
        public String arguments;
    }

    /** A function the model may call (name + description + JSON schema). */
    public static class ToolSpec {
        public final String name;
        public final String description;
        public final com.google.gson.JsonObject parameters;

        public ToolSpec(String name, String description, com.google.gson.JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }

    /** Result of one tool-calling request. */
    public interface ToolsCallback {
        /** The assistant messages produced (text and/or tool calls). */
        void onComplete(List<ChatMessage> assistantMessages);
        void onError(Exception e);
        /** Primary model failed and the backup took over; discard partial UI state. */
        default void onModelSwitch(String from, String to) { }
    }

    /** Completion callback for the no-tools summarization requests. */
    public interface SummaryCallback {
        void onSummary(String summary);
        void onError(Exception error);
    }

    /**
     * A plain completion without tools, used to distill the conversation
     * history when it approaches the context budget: the model condenses the
     * oldest tool turns into one summary message (opencode-style compaction).
     */
    public void requestSummary(List<ChatMessage> messages, SummaryCallback callback) {
        requestTools(messages, java.util.Collections.emptyList(), new ToolsCallback() {
            @Override
            public void onComplete(List<ChatMessage> assistantMessages) {
                StringBuilder summary = new StringBuilder();
                for (ChatMessage message : assistantMessages) {
                    if (message.content != null) {
                        summary.append(message.content);
                    }
                }
                if (summary.toString().trim().isEmpty()) {
                    callback.onError(new IOException("Empty summary response"));
                    return;
                }
                callback.onSummary(summary.toString().trim());
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        });
    }

    /** A request owns its cancellation flag; starting another cannot revive it. */
    public static final class RequestHandle {
        private boolean cancelled;
        private Call call;

        synchronized void attach(Call next) {
            call = next;
            if (cancelled) next.cancel();
        }

        public synchronized void cancel() {
            cancelled = true;
            if (call != null) call.cancel();
        }

        public synchronized boolean isCancelled() { return cancelled; }
    }

    public static final class RequestException extends IOException {
        public final boolean retryable;
        RequestException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
    }

    static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    static boolean isUnsupportedImageError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("http 400:") || lower.startsWith("http 404:") || lower.startsWith("http 422:"))
            && (lower.contains("image") || lower.contains("vision") || lower.contains("multimodal"));
    }

    private Attempt executeToolsWithImageFallback(String model, List<ChatMessage> messages,
            List<ToolSpec> tools, RequestHandle handle) {
        String key = config.getBaseUrl() + "/" + model;
        boolean includeImages = !textOnlyModels.contains(key);
        Attempt attempt = executeTools(model, messages, tools, handle, includeImages);
        if (includeImages && !handle.isCancelled() && isUnsupportedImageError(attempt.error)
                && messages.stream().anyMatch(m -> m.imageBase64 != null)) {
            textOnlyModels.add(key);
            return executeTools(model, messages, tools, handle, false);
        }
        return attempt;
    }

    /**
     * One non-streaming tool-calling request with the automatic
     * primary/backup fallback. Callbacks arrive on the main thread.
     */
    public RequestHandle requestTools(List<ChatMessage> messages, List<ToolSpec> tools,
                             ToolsCallback callback) {
        RequestHandle handle = new RequestHandle();
        currentToolsRequest = handle;
        new Thread(() -> {
            String primary = config.getModel();
            String backup = config.getBackupModel();
            Attempt attempt = executeToolsWithImageFallback(primary, messages, tools, handle);
            if (!handle.isCancelled() && !attempt.success && attempt.retryable
                    && backup != null && !backup.isEmpty() && !backup.equals(primary)) {
                Log.w(TAG, "Falling back to backup model: " + backup);
                final String from = primary;
                mainHandler.post(() -> callback.onModelSwitch(from, backup));
                attempt = executeToolsWithImageFallback(backup, messages, tools, handle);
            }
            if (handle.isCancelled()) {
                mainHandler.post(() -> callback.onError(new java.util.concurrent.CancellationException()));
                return;
            }
            if (attempt.success) {
                List<ChatMessage> parsed = parseAssistantMessages(attempt.text);
                if (parsed == null) {
                    attempt = Attempt.fail("Unreadable assistant response", true);
                } else {
                    final List<ChatMessage> result = parsed;
                    mainHandler.post(() -> callback.onComplete(result));
                    return;
                }
            }
            final String error = attempt.error;
            final boolean retryable = attempt.retryable;
            mainHandler.post(() -> callback.onError(new RequestException(error, retryable)));
        }).start();
        return handle;
    }

    private Attempt executeTools(String model, List<ChatMessage> messages,
                                 List<ToolSpec> tools, RequestHandle handle, boolean includeImages) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            body.addProperty("max_tokens", 16384);

            body.add("messages", serializeMessages(messages, includeImages));

            JsonArray toolArray = new JsonArray();
            for (ToolSpec spec : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "function");
                JsonObject fn = new JsonObject();
                fn.addProperty("name", spec.name);
                fn.addProperty("description", spec.description);
                fn.add("parameters", spec.parameters);
                t.add("function", fn);
                toolArray.add(t);
            }
            body.add("tools", toolArray);

            String baseUrl = config.getBaseUrl().replaceAll("/+$", "");
            String url = baseUrl.endsWith("/v1")
                ? baseUrl + "/chat/completions" : baseUrl + "/v1/chat/completions";

            Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            Call call = httpClient.newCall(request);
            handle.attach(call);
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown";
                    return Attempt.fail("HTTP " + response.code() + ": " + errorBody,
                        isRetryableStatus(response.code()));
                }
                String raw = response.body() != null ? response.body().string() : "";
                if (raw.trim().isEmpty()) return Attempt.fail("Model returned no content", true);
                return Attempt.ok(raw);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tool request failed", e);
            return Attempt.fail("Request exception: " + e.getMessage(), !handle.isCancelled());
        }
    }

    static JsonArray serializeMessages(List<ChatMessage> messages) {
        return serializeMessages(messages, true);
    }

    private static JsonArray serializeMessages(List<ChatMessage> messages, boolean includeImages) {
        JsonArray msgArray = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", m.role);
            if (includeImages && m.imageBase64 != null) {
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", m.content == null ? "Preview screenshot" : m.content);
                content.add(text);
                JsonObject image = new JsonObject();
                image.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:image/jpeg;base64," + m.imageBase64);
                image.add("image_url", url);
                content.add(image);
                obj.add("content", content);
            } else if (m.content != null) {
                obj.addProperty("content", m.content);
            }
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ToolCall call : m.toolCalls) {
                    JsonObject c = new JsonObject();
                    c.addProperty("id", call.id);
                    c.addProperty("type", "function");
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", call.name);
                    fn.addProperty("arguments", call.arguments == null ? "{}" : call.arguments);
                    c.add("function", fn);
                    calls.add(c);
                }
                obj.add("tool_calls", calls);
            }
            if ("tool".equals(m.role)) {
                obj.addProperty("tool_call_id", m.toolCallId);
                obj.addProperty("name", m.name == null ? "" : m.name);
            }
            msgArray.add(obj);
        }
        return msgArray;
    }

    private List<ChatMessage> parseAssistantMessages(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return null;
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                return null;
            }
            List<ChatMessage> out = new ArrayList<>();
            ChatMessage m = new ChatMessage();
            m.role = "assistant";
            if (message.has("content") && !message.get("content").isJsonNull()) {
                m.content = message.get("content").getAsString();
            }
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                m.toolCalls = new ArrayList<>();
                JsonArray calls = message.getAsJsonArray("tool_calls");
                for (int i = 0; i < calls.size(); i++) {
                    JsonObject c = calls.get(i).getAsJsonObject();
                    ToolCall call = new ToolCall();
                    call.id = c.has("id") && !c.get("id").isJsonNull()
                        ? c.get("id").getAsString() : String.valueOf(i);
                    JsonObject fn = c.getAsJsonObject("function");
                    call.name = fn.get("name").getAsString();
                    call.arguments = fn.has("arguments") && !fn.get("arguments").isJsonNull()
                        ? fn.get("arguments").getAsString() : "{}";
                    m.toolCalls.add(call);
                }
            }
            if (m.content == null && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                return null;
            }
            out.add(m);
            return out;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse assistant message", e);
            return null;
        }
    }
}
