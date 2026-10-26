package com.meta.app.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Web content through a REAL WebView (the phone's native browser engine):
 * search engines and many sites bot-wall plain HTTP clients, but a WebView
 * carries a real browser fingerprint and renders/executes pages like a user.
 *
 * The WebView must live in the host Activity (WebView needs an Activity
 * context and a window; a bare one in the engine never initializes). The
 * host attaches its hidden WebView here; the engine queries it blocking
 * (main-thread hop + latch, same pattern as preview inspection).
 *
 * Both search and fetch share this single WebView - safe, because the agent
 * runs one tool at a time. Returns null when unavailable or on failure -
 * callers fall back to the plain HTTP implementations.
 */
public final class WebViewBrowser {

    private static final String TAG = "WebViewBrowser";
    private static final String BING_URL = "https://cn.bing.com/search?q=";
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final int TIMEOUT_SECONDS = 20;
    private static final int MAX_FETCH_BYTES = 200 * 1024;

    private static volatile WebView webView;
    private static volatile boolean failed;
    private static Handler mainHandler;

    private WebViewBrowser() { }

    /** The host attaches its hidden WebView (created in the host layout). */
    public static void attach(WebView view) {
        mainHandler = new Handler(Looper.getMainLooper());
        webView = view;
        failed = false;
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setUserAgentString(UA);
    }

    /** The host detaches when it is destroyed. */
    public static void detach() {
        webView = null;
    }

    /**
     * Searches through the WebView; blocks up to TIMEOUT_SECONDS. Returns the
     * formatted results, "No results found for: ...", or null when the WebView
     * is unavailable or the page did not produce results in time.
     */
    public static String searchBlocking(String query) {
        WebView view = webView;
        if (view == null || failed || mainHandler == null
                || query == null || query.trim().isEmpty()) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] outcome = new String[1];
        mainHandler.post(() -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    String js = "(function(){"
                        + "var items=document.querySelectorAll('li.b_algo');"
                        + "var out=[];"
                        + "for(var i=0;i<Math.min(items.length,5);i++){"
                        // The mobile Bing layout has no <h2> wrapper; use the
                        // first anchor as link+title and the first paragraph
                        // as the snippet.
                        + "var a=items[i].querySelector('a');"
                        + "var p=items[i].querySelector('p');"
                        + "out.push((a?a.href:'')+'||'+(a?a.textContent.trim():'')+'||'+(p?p.textContent.trim():''));}"
                        + "return out.join('\\n')||'NONE';"
                        + "})()";
                    v.evaluateJavascript(js, value -> {
                        outcome[0] = value;
                        latch.countDown();
                    });
                }
            });
            try {
                view.loadUrl(BING_URL + URLEncoder.encode(query.trim(), "UTF-8"));
            } catch (Exception e) {
                Log.w(TAG, "loadUrl failed", e);
                outcome[0] = null;
                latch.countDown();
            }
        });
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "WebView search timed out");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return format(outcome[0], query);
    }

    /**
     * Fetches a page through the WebView and returns its RENDERED text (JS
     * executed, like what a user sees), capped at MAX_FETCH_BYTES. Returns
     * null when unavailable, on failure or on timeout - callers fall back to
     * the plain HTTP fetch. URLs rejected by WebFetch.isAllowed are refused
     * here too (they fall back, which reports the same error).
     */
    public static String fetchBlocking(String urlString) {
        if (!WebFetch.isAllowed(urlString)) {
            return null;
        }
        WebView view = webView;
        if (view == null || failed || mainHandler == null) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] outcome = new String[1];
        mainHandler.post(() -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    v.evaluateJavascript("document.body.innerText", value -> {
                        outcome[0] = value;
                        latch.countDown();
                    });
                }
            });
            try {
                view.loadUrl(urlString.trim());
            } catch (Exception e) {
                Log.w(TAG, "loadUrl failed", e);
                outcome[0] = null;
                latch.countDown();
            }
        });
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "WebView fetch timed out");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        String value = outcome[0];
        if (value == null) {
            return null;
        }
        String content = value.trim();
        if (content.startsWith("\"")) {
            content = content.substring(1);
        }
        if (content.endsWith("\"")) {
            content = content.substring(0, content.length() - 1);
        }
        content = content.replace("\\n", "\n").replace("\\\"", "\"")
            .replace("\\u003C", "<").replace("\\u003E", ">");
        if (content.length() > MAX_FETCH_BYTES) {
            content = content.substring(0, MAX_FETCH_BYTES)
                + "\n\n[truncated at " + MAX_FETCH_BYTES + " chars]";
        }
        return content;
    }

    /** Formats the evaluateJavascript output (a quoted string, lines
     *  "url||title||snippet"). */
    static String format(String jsValue, String query) {
        if (jsValue == null) {
            return null;
        }
        String content = jsValue.trim();
        if (content.startsWith("\"")) {
            content = content.substring(1);
        }
        if (content.endsWith("\"")) {
            content = content.substring(0, content.length() - 1);
        }
        content = content.replace("\\n", "\n").replace("\\\"", "\"");
        if (content.isEmpty() || "NONE".equals(content)) {
            return "No results found for: " + query;
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] parts = lines[i].split("\\|\\|", 3);
            String url = parts.length > 0 ? parts[0] : "";
            String title = parts.length > 1 ? parts[1] : "";
            String snippet = parts.length > 2 ? parts[2] : "";
            sb.append(i + 1).append(". ").append(title).append('\n');
            sb.append("   URL: ").append(url).append('\n');
            sb.append("   ").append(snippet).append('\n');
        }
        return sb.toString();
    }
}
