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
        /** Whether this failure is worth switching to the backup model (auth/permission errors are not). */
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
                // 401/403 are configuration problems (invalid key); switching models does not help
                boolean retryable = code != 401 && code != 403;
                return Attempt.fail("HTTP " + code + ": " + errorBody, retryable);
            }

            // Parse the SSE stream
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()));
            StringBuilder fullText = new StringBuilder();
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
                                mainHandler.post(() -> callback.onToken(content));

                                // Weak models easily fall into degenerate loops (repeating the same code fragment endlessly); detect and abort to avoid screen spam and token waste
                                if (isDegenerate(fullText.toString())) {
                                    Log.w(TAG, "Model output entered a repetition loop, aborting the request");
                                    reader.close();
                                    return Attempt.fail("Model output entered a repetition loop, aborted", true);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignore parse errors
                    }
                }
            }
            reader.close();

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
