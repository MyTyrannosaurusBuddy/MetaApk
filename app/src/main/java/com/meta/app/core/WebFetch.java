package com.meta.app.core;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Web fetch for the agent: retrieves the text content of an http(s) URL and
 * hands it back to the model (documentation pages, raw source files, template
 * repositories). Bounded and guarded:
 *  - http/https only
 *  - private / loopback / link-local hosts are rejected
 *  - response size is capped (oversized bodies are truncated with a note)
 *  - only text-like content types are read
 */
public final class WebFetch {

    private static final String TAG = "WebFetch";
    private static final int MAX_BYTES = 200 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private WebFetch() { }

    /** Fetches a URL; returns its text content or an error string. */
    public static String fetch(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return "Error: empty URL";
        }
        urlString = urlString.trim();
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            return "Error: only http/https URLs are allowed";
        }
        if (!isAllowed(urlString)) {
            return "Error: private network addresses are not allowed";
        }
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", UA);
            try {
                int code = connection.getResponseCode();
                if (code != 200) {
                    return "Error: HTTP " + code;
                }
                String type = connection.getContentType();
                if (type != null) {
                    String lower = type.toLowerCase(java.util.Locale.ROOT).split(";")[0].trim();
                    if (!lower.startsWith("text/") && !lower.equals("application/json")
                            && !lower.equals("application/xml")) {
                        return "Error: not a text resource (" + lower + ")";
                    }
                }
                byte[] body = readCapped(connection.getInputStream());
                boolean truncated = body.length >= MAX_BYTES;
                String text = new String(body, StandardCharsets.UTF_8);
                return truncated
                    ? text + "\n\n[truncated at " + MAX_BYTES + " bytes]"
                    : text;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Fetch failed: " + urlString, e);
            return "Error: fetch failed: " + e.getMessage();
        }
    }

    /**
     * Whether the URL may be fetched: http/https only, and the resolved host
     * must not be a private / loopback / link-local address. Shared with the
     * WebView fetch path.
     */
    public static boolean isAllowed(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return false;
        }
        urlString = urlString.trim();
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            return false;
        }
        try {
            URL url = new URL(urlString);
            InetAddress address = InetAddress.getByName(url.getHost());
            return !(address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress());
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readCapped(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int len;
        while ((len = in.read(buffer)) > 0 && total < MAX_BYTES) {
            int take = Math.min(len, MAX_BYTES - total);
            out.write(buffer, 0, take);
            total += take;
        }
        return out.toByteArray();
    }
}
