package com.meta.app.bridge;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

/**
 * Network bridge - HTTP request capability.
 */
public class NetworkBridge {

    private final OkHttpClient client;

    public interface ResponseCallback {
        void onSuccess(int statusCode, String body);
        void onError(String message);
    }

    public NetworkBridge() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
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
            .post(RequestBody.create(jsonBody, MediaType.parse("application/json")));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.addHeader(e.getKey(), e.getValue());
            }
        }
        execute(builder.build(), callback);
    }

    private void execute(Request request, ResponseCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                callback.onSuccess(response.code(), body);
            }
        });
    }
}
