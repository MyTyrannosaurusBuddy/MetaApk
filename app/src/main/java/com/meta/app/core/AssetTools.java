package com.meta.app.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Asset extraction utilities - extracts the embedded toolchain from assets
 * into the app-private directory.
 *
 * Important: since Android 14 (API 34) ART refuses to load writable dex files,
 * extracted .dex.jar files must be made read-only, otherwise DexClassLoader
 * throws "Writable dex file '...' is not allowed.".
 */
public final class AssetTools {

    private static final String TAG = "AssetTools";

    private AssetTools() { }

    /**
     * Extracts an asset into filesDir and makes it read-only.
     * If the file already exists and matches the asset content it is reused
     * (still ensuring read-only). Content is compared via a stamp file, so
     * the file is re-extracted automatically when an app upgrade changes the asset.
     *
     * @param context   application context
     * @param assetName file name inside assets, e.g. "ecj.dex.jar"
     * @param fileName  target file name inside filesDir
     * @return the target file (read-only)
     */
    public static File releaseReadOnly(Context context, String assetName, String fileName)
            throws IOException {
        File target = new File(context.getFilesDir(), fileName);
        File stamp = new File(context.getFilesDir(), fileName + ".stamp");

        String assetHash = hashAsset(context, assetName);
        String currentHash = readText(stamp);

        if (target.exists() && target.length() > 0 && assetHash.equals(currentHash)) {
            makeReadOnly(target);
            return target;
        }

        // The old file may be read-only (set after the previous extraction);
        // restore writability and delete it before overwriting.
        if (target.exists()) {
            target.setWritable(true);
            if (!target.delete()) {
                Log.w(TAG, "Failed to delete old file, trying to overwrite directly: " + target.getPath());
            }
        }

        try (InputStream is = context.getAssets().open(assetName);
             OutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            os.flush();
        }
        writeText(stamp, assetHash);
        makeReadOnly(target);
        return target;
    }

    /**
     * Makes a file read-only.
     * ART decides dex writability via access(path, W_OK), so the write
     * permission bits must be cleared.
     */
    public static void makeReadOnly(File file) {
        if (!file.exists()) return;
        if (!file.setReadOnly()) {
            Log.w(TAG, "setReadOnly() failed for " + file.getPath());
        }
    }

    private static String hashAsset(Context context, String assetName) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 unavailable", e);
        }
        try (InputStream is = context.getAssets().open(assetName)) {
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = is.read(buffer)) > 0) {
                md.update(buffer, 0, len);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String readText(File file) {
        if (!file.exists()) return null;
        try (InputStream is = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = is.read(data);
            if (read <= 0) return null;
            return new String(data, 0, read, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File file, String text) {
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(text.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "Failed to write stamp: " + e.getMessage());
        }
    }
}
