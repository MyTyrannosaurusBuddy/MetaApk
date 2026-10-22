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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile Call currentCall;
    /** Whether the user cancelled this generation. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public interface StreamCallback {
        void onToken(String token);
        void onComplete(String fullResponse);
        void onError(Exception e);

        /**
         * Called when the primary model is unavailable and the client switches to
     * the backup model; used to discard the partial output of the primary model.
         */
        default void onModelSwitch(String from, String to) { }
    }

    /** Result of a /models listing request (OpenAI-compatible endpoints). */
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
        /** Whether this failure is worth switching to the backup model (all HTTP failures are; see requestStream). */
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
     * Streams a request to the LLM.
     * Falls back to the backup model automatically when the primary model
     * fails (rate limited / unavailable / empty content).
     */
    public void requestStream(String userPrompt, StreamCallback callback) {
        cancelled.set(false);
        new Thread(() -> {
            String primary = config.getModel();
            String backup = config.getBackupModel();

            Attempt attempt = execute(primary, userPrompt, callback);

            if (attempt.success) {
                mainHandler.post(() -> callback.onComplete(attempt.text));
                return;
            }

            // Cancelled by the user: no fallback, stop immediately
            if (cancelled.get()) {
                Log.i(TAG, "User cancelled the request");
                return;
            }

            Log.w(TAG, "Primary model " + primary + " request failed: " + attempt.error);

            boolean noBackup = backup == null || backup.trim().isEmpty()
                    || backup.equals(primary);
            if (noBackup || !attempt.retryable) {
                mainHandler.post(() -> callback.onError(new IOException(attempt.error)));
                return;
            }

            Log.w(TAG, "Falling back to backup model: " + backup);
            callback.onModelSwitch(primary, backup);

            Attempt backupAttempt = execute(backup, userPrompt, callback);
            if (backupAttempt.success) {
                mainHandler.post(() -> callback.onComplete(backupAttempt.text));
            } else if (cancelled.get()) {
                Log.i(TAG, "User cancelled the request");
            } else {
                Log.e(TAG, "Backup model " + backup + " also failed: " + backupAttempt.error);
                mainHandler.post(() -> callback.onError(new IOException(
                    "Both primary model (" + primary + ") and backup model (" + backup + ") failed:\n"
                    + attempt.error + "\n" + backupAttempt.error)));
            }
        }).start();
    }

    /**
     * Cancels the current request (user pressed the stop button).
     */
    public void cancel() {
        cancelled.set(true);
        Call call = currentCall;
        if (call != null) {
            call.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
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
        new Thread(() -> {
            String url = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "") + "/models";
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

    // ---------- Tool calling (agent turns) ----------

    /** One conversation message for a tool-calling turn. */
    public static class ChatMessage {
        /** "system" | "user" | "assistant" | "tool" */
        public String role;
        /** Text content; null for assistant messages that only carry tool calls. */
        public String content;
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

    /**
     * One non-streaming tool-calling request with the automatic
     * primary/backup fallback. Callbacks arrive on the main thread.
     */
    public void requestTools(List<ChatMessage> messages, List<ToolSpec> tools,
                             ToolsCallback callback) {
        new Thread(() -> {
            String primary = config.getModel();
            String backup = config.getBackupModel();
            Attempt attempt = executeTools(primary, messages, tools);
            if (!attempt.success && attempt.retryable
                    && backup != null && !backup.isEmpty() && !backup.equals(primary)) {
                Log.w(TAG, "Falling back to backup model: " + backup);
                final String from = primary;
                mainHandler.post(() -> callback.onModelSwitch(from, backup));
                attempt = executeTools(backup, messages, tools);
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
            mainHandler.post(() -> callback.onError(new Exception(error)));
        }).start();
    }

    private Attempt executeTools(String model, List<ChatMessage> messages,
                                 List<ToolSpec> tools) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            body.addProperty("max_tokens", 16384);

            JsonArray msgArray = new JsonArray();
            for (ChatMessage m : messages) {
                JsonObject obj = new JsonObject();
                obj.addProperty("role", m.role);
                if (m.content != null) {
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
            body.add("messages", msgArray);

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

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown";
                    return Attempt.fail("HTTP " + response.code() + ": " + errorBody, true);
                }
                String raw = response.body() != null ? response.body().string() : "";
                if (raw.trim().isEmpty()) {
                    return Attempt.fail("Model returned no content", true);
                }
                return Attempt.ok(raw);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tool request failed", e);
            return Attempt.fail("Request exception: " + e.getMessage(), true);
        }
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

    /**
     * Performs one streaming request against a specific model.
     */
    private Attempt execute(String model, String userPrompt, StreamCallback callback) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", true);
            body.addProperty("max_tokens", 16384);

            // Reasoning models (e.g. minimax-m3.1-flash) spend their token budget
            // on the thinking process by default, leaving the final content empty.
            // The reasoning effort must be limited, otherwise the result is always empty.
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "low");
            body.add("reasoning", reasoning);

            JsonArray messages = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userPrompt);
            messages.add(userMsg);

            body.add("messages", messages);

            String baseUrl = config.getBaseUrl().replaceAll("/+$", "");
            String url;
            if (baseUrl.endsWith("/v1")) {
                url = baseUrl + "/chat/completions";
            } else {
                url = baseUrl + "/v1/chat/completions";
            }

            Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            Call call = httpClient.newCall(request);
            currentCall = call;
            if (cancelled.get()) {
                return Attempt.fail("Cancelled", false);
            }
            Response response = call.execute();

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown";
                int code = response.code();
                // Always worth trying the backup: 401/403 may mean an invalid key,
                // but they can also mean a gated/retired model (e.g. OpenRouter's
                // agentic-harness gate) where the backup works fine. An invalid
                // key simply fails again on the backup, which is harmless.
                return Attempt.fail("HTTP " + code + ": " + errorBody, true);
            }

            // Parse the SSE stream
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()));
            StringBuilder fullText = new StringBuilder();
            // Batch tokens before posting to the main thread: one token per
            // mainHandler.post floods the UI thread and janks long generations.
            StringBuilder batchBuffer = new StringBuilder();
            long lastFlush = System.currentTimeMillis();
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.get()) {
                    reader.close();
                    return Attempt.fail("Cancelled", false);
                }
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();

                        // OpenRouter may return an error object even with HTTP 200 (e.g. free-tier rate limiting)
                        if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                            String err = chunk.get("error").toString();
                            Log.e(TAG, "LLM stream error: " + err);
                            reader.close();
                            return Attempt.fail("Model returned an error: " + err, true);
                        }

                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices != null && choices.size() > 0) {
                            JsonObject delta = choices.get(0).getAsJsonObject()
                                    .getAsJsonObject("delta");
                            if (delta != null && delta.has("content")) {
                                String content = delta.get("content").getAsString();
                                if (content == null || content.isEmpty()) continue;
                                fullText.append(content);
                                batchBuffer.append(content);

                                // Weak models easily fall into degenerate loops (repeating the same code fragment endlessly); detect and abort to avoid screen spam and token waste
                                if (isDegenerate(fullText.toString())) {
                                    Log.w(TAG, "Model output entered a repetition loop, aborting the request");
                                    reader.close();
                                    return Attempt.fail("Model output entered a repetition loop, aborted", true);
                                }

                                // Flush the batch every 80ms / 256 chars
                                long now = System.currentTimeMillis();
                                if (batchBuffer.length() >= 256 || now - lastFlush >= 80) {
                                    final String batch = batchBuffer.toString();
                                    batchBuffer.setLength(0);
                                    lastFlush = now;
                                    mainHandler.post(() -> callback.onToken(batch));
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignore parse errors
                    }
                }
            }
            reader.close();
            // Flush any remaining batched tokens
            if (batchBuffer.length() > 0) {
                final String batch = batchBuffer.toString();
                mainHandler.post(() -> callback.onToken(batch));
            }

            String result = fullText.toString();
            if (result.trim().isEmpty()) {
                // HTTP 200 but no content: typical when a reasoning model spent its
            // token budget on the thinking process
                return Attempt.fail("Model returned no content", true);
            }
            return Attempt.ok(result);

        } catch (Exception e) {
            Log.e(TAG, "LLM request failed", e);
            return Attempt.fail("Request exception: " + e.getMessage(), true);
        }
    }

    /**
     * Detects degenerate output: the trailing characters are built from the
     * same short fragment repeated over and over. Weak models (especially
     * small ones randomly routed by openrouter/free) easily fall into
     * endless repetitions like "rootgenerateBgColor(...);" and must be cut
     * off early.
     */
    static boolean isDegenerate(String text) {
        int n = text.length();
        if (n < 120) return false;
        int window = 120;
        String tail = text.substring(n - window);
        for (int period = 6; period <= window / 3; period++) {
            if (window % period != 0) continue;
            String unit = tail.substring(0, period);
            boolean repeated = true;
            for (int i = period; i < window; i += period) {
                if (!tail.startsWith(unit, i)) {
                    repeated = false;
                    break;
                }
            }
            if (repeated) return true;
        }
        return false;
    }
}
