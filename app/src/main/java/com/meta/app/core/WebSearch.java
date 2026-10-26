package com.meta.app.core;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web search for the agent: the host performs the request against fixed,
 * allow-listed endpoints (the model supplies only a query, never a URL), and
 * the top result snippets are returned as plain text.
 *
 * DuckDuckGo's HTML endpoint is primary (no API key); Bing is the fallback
 * for networks where DuckDuckGo is unreachable.
 */
public final class WebSearch {

    private static final String TAG = "WebSearch";
    private static final int MAX_RESULTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private WebSearch() { }

    /** One search result. */
    public static final class Result {
        public final String title;
        public final String url;
        public final String snippet;

        Result(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    /** Searches the web; returns formatted results or an error string. */
    public static String search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "Error: empty search query";
        }
        // Bing first: it is reachable in the networks where DuckDuckGo is
        // blocked or bot-walled (observed: DDG returns 202 with no results).
        List<Result> results = searchBing(query);
        if (results == null || results.isEmpty()) {
            results = searchDuckDuckGo(query);
        }
        if (results == null || results.isEmpty()) {
            return "No results found for: " + query;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            sb.append(i + 1).append(". ").append(clean(r.title)).append('\n');
            sb.append("   URL: ").append(r.url).append('\n');
            sb.append("   ").append(clean(r.snippet)).append('\n');
        }
        return sb.toString();
    }

    /** Returns null when the endpoint is unreachable. */
    private static List<Result> searchDuckDuckGo(String query) {
        try {
            String body = httpPost("https://html.duckduckgo.com/html/",
                "q=" + URLEncoder.encode(query, "UTF-8"));
            List<Result> results = new ArrayList<>();
            Pattern link = Pattern.compile(
                "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Matcher links = link.matcher(body);
            Pattern snippet = Pattern.compile(
                "class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Matcher snippets = snippet.matcher(body);
            while (links.find() && results.size() < MAX_RESULTS) {
                String url = links.group(1);
                // DuckDuckGo wraps the target in a redirect parameter.
                int uddg = url.indexOf("uddg=");
                if (uddg >= 0) {
                    int end = url.indexOf('&', uddg);
                    url = url.substring(uddg + 5, end < 0 ? url.length() : end);
                    url = java.net.URLDecoder.decode(url, "UTF-8");
                }
                String snip = "";
                if (snippets.find(snippets.regionEnd())) {
                    snip = snippets.group(1);
                }
                results.add(new Result(links.group(2), url, snip));
            }
            return results;
        } catch (Exception e) {
            Log.w(TAG, "DuckDuckGo search failed, falling back to Bing", e);
            return null;
        }
    }

    /** Returns null when the endpoint is unreachable. */
    private static List<Result> searchBing(String query) {
        try {
            String body = httpGet("https://cn.bing.com/search?q="
                + URLEncoder.encode(query, "UTF-8"));
            List<Result> results = new ArrayList<>();
            Pattern item = Pattern.compile(
                "<li class=\"b_algo\">.*?<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>"
                    + ".*?<p[^>]*>(.*?)</p>", Pattern.DOTALL);
            Matcher m = item.matcher(body);
            while (m.find() && results.size() < MAX_RESULTS) {
                results.add(new Result(m.group(2), m.group(1), m.group(3)));
            }
            return results;
        } catch (Exception e) {
            Log.w(TAG, "Bing search failed", e);
            return null;
        }
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setInstanceFollowRedirects(true);
        return readBody(c);
    }

    private static String httpPost(String urlString, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.getOutputStream().write(form.getBytes("UTF-8"));
        return readBody(c);
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }

    /** Strips HTML tags and collapses whitespace. */
    static String clean(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ")
            .replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
            .replaceAll("\\s+", " ").trim();
    }

    static List<Result> parseBingHtml(String html) {
        List<Result> results = new ArrayList<>();
        Pattern item = Pattern.compile(
            "<li class=\"b_algo\">.*?<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>"
                + ".*?<p[^>]*>(.*?)</p>", Pattern.DOTALL);
        Matcher m = item.matcher(html);
        while (m.find() && results.size() < MAX_RESULTS) {
            results.add(new Result(m.group(2), m.group(1), m.group(3)));
        }
        return results;
    }
}
