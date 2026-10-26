package com.meta.app.core;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    /** Source image download cap (same as opencode: 20 MiB). */
    private static final int MAX_IMAGE_DOWNLOAD_BYTES = 20 * 1024 * 1024;
    /** Longest edge after resizing (opencode default: 2000). */
    private static final int MAX_IMAGE_EDGE = 2000;
    /** Base64 size target (opencode default: 5 MiB); encoding quality is
     *  reduced progressively until the output fits. */
    private static final int MAX_IMAGE_BASE64_BYTES = 5 * 1024 * 1024;
    /** Progressive JPEG qualities (opencode photon.ts: [80, 85, 70, 55, 40]). */
    private static final int[] JPEG_QUALITIES = {80, 85, 70, 55, 40};
    /** Size-gradient step when encodings of one size don't fit (opencode
     *  photon.ts: 0.75 factor, up to 32 sizes). */
    private static final float SIZE_DECAY = 0.75f;
    private static final int MAX_SIZE_STEPS = 32;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private WebFetch() { }

    /** The outcome of a fetch: either text, or a downloaded image (base64
     *  JPEG/PNG data without the "data:" prefix). Exactly one is non-null. */
    public static final class FetchResult {
        public final String text;
        public final String imageBase64;
        public final String imageType; // e.g. "image/png"
        /** The normalized image bytes (what imageBase64 encodes) - the caller
         *  may persist them so the reference stays on disk after the context
         *  drops the image. */
        public final byte[] imageBytes;

        FetchResult(String text, String imageBase64, String imageType) {
            this(text, imageBase64, imageType, null);
        }

        FetchResult(String text, String imageBase64, String imageType, byte[] imageBytes) {
            this.text = text;
            this.imageBase64 = imageBase64;
            this.imageType = imageType;
            this.imageBytes = imageBytes;
        }
    }

    /** Fetches a URL; returns its text content or an error string. */
    public static String fetch(String urlString) {
        FetchResult result = fetchDetailed(urlString);
        return result.imageBase64 != null
            ? "Downloaded image (" + result.imageType + ")"
            : result.text;
    }

    /**
     * Opens a connection following redirects MANUALLY: every hop re-runs
     * the SSRF host check, which HttpURLConnection's automatic redirects
     * would skip (an external URL could 302 into a private address, and the
     * auto-follower would happily connect to it). Returns the final
     * connection with redirects disabled, or null with an error message.
     */
    private static HttpURLConnection openChecked(String urlString, StringBuilder errorOut)
            throws IOException {
        String current = urlString;
        for (int hop = 0; hop < 5; hop++) {
            if (!isAllowed(current)) {
                errorOut.append("Error: private network addresses are not allowed");
                return null;
            }
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", UA);
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    errorOut.append("Error: redirect without Location header");
                    return null;
                }
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        errorOut.append("Error: too many redirects");
        return null;
    }

    /**
     * Fetches a URL and routes by response content type: image responses are
     * downloaded (bounded) and returned as base64 so the model can SEE them
     * (multimodal models); text-like responses are read as before. All the
     * guards of fetch() apply (scheme, SSRF host check, size caps).
     */
    public static FetchResult fetchDetailed(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return new FetchResult("Error: empty URL", null, null);
        }
        urlString = urlString.trim();
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            return new FetchResult("Error: only http/https URLs are allowed", null, null);
        }
        if (!isAllowed(urlString)) {
            return new FetchResult("Error: private network addresses are not allowed", null, null);
        }
        try {
            StringBuilder redirectErr = new StringBuilder();
            HttpURLConnection connection = openChecked(urlString, redirectErr);
            if (connection == null) {
                return new FetchResult(redirectErr.toString(), null, null);
            }
            try {
                int code = connection.getResponseCode();
                if (code != 200) {
                    return new FetchResult("Error: HTTP " + code, null, null);
                }
                String type = connection.getContentType();
                String lower = type == null ? ""
                    : type.toLowerCase(java.util.Locale.ROOT).split(";")[0].trim();
                if (lower.startsWith("image/")) {
                    return downloadImage(connection, lower);
                }
                if (type != null && !lower.startsWith("text/") && !lower.equals("application/json")
                        && !lower.equals("application/xml")) {
                    return new FetchResult("Error: not a text or image resource (" + lower + ")", null, null);
                }
                byte[] body = readCapped(connection.getInputStream(), MAX_BYTES);
                boolean truncated = body.length >= MAX_BYTES;
                String text = new String(body, StandardCharsets.UTF_8);
                return new FetchResult(truncated
                    ? text + "\n\n[truncated at " + MAX_BYTES + " bytes]"
                    : text, null, null);
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Fetch failed: " + urlString, e);
            return new FetchResult("Error: fetch failed: " + e.getMessage(), null, null);
        }
    }

    /**
     * Image normalization follows opencode's photon pipeline exactly:
     * 1. within-limits images pass through unchanged (original format kept);
     * 2. otherwise resize fit-inside maxW/maxH, then try PNG first, then
     *    JPEG at progressively lower quality [80, 85, 70, 55, 40];
     * 3. if no encoding fits, shrink the size by 0.75 and retry (up to 32
     *    size steps);
     * 4. only when every size and encoding fails does it error.
     * Returns the image as base64 (no data: prefix).
     */
    private static FetchResult downloadImage(HttpURLConnection connection, String type)
            throws Exception {
        byte[] bytes = readCapped(connection.getInputStream(), MAX_IMAGE_DOWNLOAD_BYTES);
        if (bytes.length >= MAX_IMAGE_DOWNLOAD_BYTES) {
            return new FetchResult("Error: image too large to download (limit "
                + MAX_IMAGE_DOWNLOAD_BYTES + " bytes)", null, null);
        }
        return normalizeImage(bytes, type);
    }

    /** Same opencode pipeline, but for LOCAL bytes (workspace files re-read
     *  by the agent). Shared with downloadImage so both paths behave alike. */
    public static FetchResult normalizeImage(byte[] bytes, String type) {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return new FetchResult("Error: cannot decode image (" + type + ")", null, null);
        }
        int srcWidth = bounds.outWidth;
        int srcHeight = bounds.outHeight;
        // Fast path (opencode): small images pass through byte-identical,
        // original format preserved.
        String originalBase64 = android.util.Base64.encodeToString(
            bytes, android.util.Base64.NO_WRAP);
        if (srcWidth <= MAX_IMAGE_EDGE && srcHeight <= MAX_IMAGE_EDGE
                && originalBase64.length() <= MAX_IMAGE_BASE64_BYTES) {
            return new FetchResult(null, originalBase64, type, bytes);
        }
        // Fit-inside target (opencode: min(1, maxW/w, maxH/h)).
        float scale = Math.min(1f,
            Math.min((float) MAX_IMAGE_EDGE / srcWidth, (float) MAX_IMAGE_EDGE / srcHeight));
        int targetWidth = Math.max(1, Math.round(srcWidth * scale));
        int targetHeight = Math.max(1, Math.round(srcHeight * scale));
        // Size gradient: start at the target, shrink by 0.75 per step, 32 max.
        for (int step = 0; step < MAX_SIZE_STEPS; step++) {
            int w = step == 0 ? targetWidth
                : Math.max(1, (int) Math.floor(targetWidth * Math.pow(SIZE_DECAY, step)));
            int h = step == 0 ? targetHeight
                : Math.max(1, (int) Math.floor(targetHeight * Math.pow(SIZE_DECAY, step)));
            if (w == 1 && h == 1 && step > 0) break;
            android.graphics.Bitmap resized = decodeScaled(bytes, srcWidth, srcHeight, w, h);
            if (resized == null) {
                return new FetchResult("Error: cannot decode image (" + type + ")", null, null);
            }
            try {
                // Encoder order (opencode): PNG first, then JPEG qualities.
                String png = encodePng(resized);
                if (png != null) {
                    return new FetchResult(null, png,
                        "image/png (resized " + w + "x" + h + ")",
                        encodeToBytes(resized, android.graphics.Bitmap.CompressFormat.PNG, 100));
                }
                for (int quality : JPEG_QUALITIES) {
                    String jpeg = encodeJpeg(resized, quality);
                    if (jpeg != null) {
                        return new FetchResult(null, jpeg,
                            "image/jpeg (resized " + w + "x" + h + ", q" + quality + ")",
                            encodeToBytes(resized, android.graphics.Bitmap.CompressFormat.JPEG, quality));
                    }
                }
            } finally {
                resized.recycle();
            }
        }
        return new FetchResult("Error: image cannot be compressed below "
            + MAX_IMAGE_BASE64_BYTES + " base64 bytes", null, null);
    }

    /** Decodes to the target size via inSampleSize pre-scale + exact scale. */
    private static android.graphics.Bitmap decodeScaled(byte[] bytes, int srcWidth, int srcHeight,
                                                        int targetWidth, int targetHeight) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(srcWidth, srcHeight, targetWidth, targetHeight);
        android.graphics.Bitmap bitmap =
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) {
            return null;
        }
        if (bitmap.getWidth() == targetWidth && bitmap.getHeight() == targetHeight) {
            return bitmap;
        }
        android.graphics.Bitmap scaled =
            android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    /** Encodes PNG when it fits the base64 budget; null otherwise. */
    private static String encodePng(android.graphics.Bitmap bitmap) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        String base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        return base64.length() <= MAX_IMAGE_BASE64_BYTES ? base64 : null;
    }

    /** Encodes JPEG at the given quality when it fits the base64 budget; null otherwise. */
    private static String encodeJpeg(android.graphics.Bitmap bitmap, int quality) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out);
        String base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        return base64.length() <= MAX_IMAGE_BASE64_BYTES ? base64 : null;
    }

    /** Encodes the bitmap in the given format and returns the raw bytes. */
    private static byte[] encodeToBytes(android.graphics.Bitmap bitmap,
                                        android.graphics.Bitmap.CompressFormat format, int quality) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(format, quality, out);
        return out.toByteArray();
    }

    /** Power-of-two sample size that lands the bitmap at or above the target. */
    static int calculateInSampleSize(int srcWidth, int srcHeight, int targetWidth, int targetHeight) {
        int sample = 1;
        while (srcWidth / (sample * 2) >= targetWidth && srcHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Downloads raw bytes of an http(s) URL (any content type) for the app
     * to USE as a file (audio, fonts, data...). Same guards as fetch():
     * scheme check, SSRF host check, 20 MiB cap. Returns null plus an error
     * message on failure.
     */
    public static byte[] fetchBinary(String urlString, StringBuilder errorOut) {
        if (urlString == null || urlString.trim().isEmpty()) {
            errorOut.append("Error: empty URL");
            return null;
        }
        urlString = urlString.trim();
        if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
            errorOut.append("Error: only http/https URLs are allowed");
            return null;
        }
        if (!isAllowed(urlString)) {
            errorOut.append("Error: private network addresses are not allowed");
            return null;
        }
        try {
            HttpURLConnection connection = openChecked(urlString, errorOut);
            if (connection == null) {
                return null;
            }
            try {
                int code = connection.getResponseCode();
                if (code != 200) {
                    errorOut.append("Error: HTTP ").append(code);
                    return null;
                }
                byte[] bytes = readCapped(connection.getInputStream(), MAX_IMAGE_DOWNLOAD_BYTES);
                if (bytes.length >= MAX_IMAGE_DOWNLOAD_BYTES) {
                    errorOut.append("Error: file too large (limit ")
                        .append(MAX_IMAGE_DOWNLOAD_BYTES).append(" bytes)");
                    return null;
                }
                return bytes;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Binary fetch failed: " + urlString, e);
            errorOut.append("Error: fetch failed: ").append(e.getMessage());
            return null;
        }
    }

    /** Whether a URL looks like a direct image link (http/https and a common
     *  image file extension). */
    public static boolean looksLikeImageUrl(String urlString) {        if (urlString == null || urlString.trim().isEmpty()) {
            return false;
        }
        String lower = urlString.trim().toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("http://") || lower.startsWith("https://"))
            && lower.matches(".*\\.(png|jpe?g|gif|webp)(\\?.*)?$");
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

    private static byte[] readCapped(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int len;
        while ((len = in.read(buffer)) > 0 && total < cap) {
            int take = Math.min(len, cap - total);
            out.write(buffer, 0, take);
            total += take;
        }
        return out.toByteArray();
    }
}
