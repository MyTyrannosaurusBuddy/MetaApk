package com.meta.app.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Network bridge - HTTP request capability.
 *
 * Implemented on the platform HttpURLConnection so the exact same bridge
 * runs both inside the MetaApk host and inside standalone exported apps
 * (no third-party HTTP library bundled into the shell).
 *
 * Callbacks are delivered on the MAIN thread: plugin code overwhelmingly
 * updates UI from these callbacks, and a background-thread callback would
 * crash with CalledFromWrongThreadException.
 */
public class NetworkBridge {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** Response bodies beyond this size are rejected instead of being buffered
     *  into memory (plugin code is LLM-generated; downloading a large file
     *  must not OOM the host). */
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final android.os.Handler mainHandler;

    public interface ResponseCallback {
        void onSuccess(int statusCode, String body);
        void onError(String message);
    }

    public NetworkBridge() {
        this.mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public void get(String url, Map<String, String> headers, ResponseCallback callback) {
        execute("GET", url, null, headers, callback);
    }

    public void post(String url, String jsonBody, Map<String, String> headers, ResponseCallback callback) {
        execute("POST", url, jsonBody == null ? "" : jsonBody, headers, callback);
    }

    private void execute(String method, String url, String body, Map<String, String> headers,
                         final ResponseCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod(method);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(bytes);
                    }
                }
                final int statusCode = connection.getResponseCode();
                InputStream stream = statusCode >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
                final String responseBody = readAll(stream);
                mainHandler.post(() -> callback.onSuccess(statusCode, responseBody));
            } catch (Exception e) {
                final String message = e.getMessage();
                mainHandler.post(() -> callback.onError(message));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "MetaNetworkBridge").start();
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream;
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            int total = 0;
            while ((len = in.read(buffer)) > 0) {
                total += len;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Response exceeds the "
                        + (MAX_RESPONSE_BYTES / (1024 * 1024)) + " MB limit");
                }
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
