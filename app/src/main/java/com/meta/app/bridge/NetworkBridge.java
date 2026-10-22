package com.meta.app.bridge;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

/**
 * Network bridge - HTTP request capability.
 *
 * Callbacks are delivered on the MAIN thread: plugin code overwhelmingly
 * updates UI from these callbacks, and a background-thread callback would
 * crash with CalledFromWrongThreadException.
 */
public class NetworkBridge {

    private final OkHttpClient client;
    private final android.os.Handler mainHandler;

    public interface ResponseCallback {
        void onSuccess(int statusCode, String body);
        void onError(String message);
    }

    public NetworkBridge() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        this.mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public void get(String url, Map<String, String> headers, ResponseCallback callback) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.addHeader(e.getKey(), e.getValue());
            }
        }
        execute(builder.build(), callback);
    }

    public void post(String url, String jsonBody, Map<String, String> headers, ResponseCallback callback) {
        Request.Builder builder = new Request.Builder().url(url)
            .post(RequestBody.create(jsonBody == null ? "" : jsonBody,
                MediaType.parse("application/json")));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.addHeader(e.getKey(), e.getValue());
            }
        }
        execute(builder.build(), callback);
    }

    private void execute(Request request, final ResponseCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                final String message = e.getMessage();
                mainHandler.post(() -> callback.onError(message));
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body() != null ? response.body().string() : "";
                final int code = response.code();
                mainHandler.post(() -> callback.onSuccess(code, body));
            }
        });
    }
}
