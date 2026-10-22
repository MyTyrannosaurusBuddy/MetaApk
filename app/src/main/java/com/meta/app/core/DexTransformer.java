package com.meta.app.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * DEX transformer - converts .class files into .dex using D8.
 */
public class DexTransformer {

    private static final String TAG = "DexTransformer";
    private final Context context;

    public DexTransformer(Context context) {
        this.context = context;
    }

    /**
     * Transformation result.
     */
    public static class TransformResult {
        public final boolean success;
        public final File dexFile;
        public final String errorMessage;

        private TransformResult(boolean success, File dexFile, String errorMessage) {
            this.success = success;
            this.dexFile = dexFile;
            this.errorMessage = errorMessage;
        }

        public static TransformResult success(File dexFile) {
            return new TransformResult(true, dexFile, null);
        }

        public static TransformResult failure(String error) {
            return new TransformResult(false, null, error);
        }
    }

    private ClassLoader getD8ClassLoader() throws Exception {
        File optimizedDir = new File(context.getCacheDir(), "d8_odex");
        optimizedDir.mkdirs();
        // Must be read-only after extraction: Android 14+ refuses to load writable dex files
        File d8Dex = AssetTools.releaseReadOnly(context, "d8.dex.jar", "d8.dex.jar");
        return new dalvik.system.DexClassLoader(
            d8Dex.getAbsolutePath(),
            optimizedDir.getAbsolutePath(),
            null,
            context.getClassLoader()
        );
    }

    /**
     * Converts a .class directory into a .dex.
     * @param pluginId   plugin ID
     * @param classesDir directory containing the .class files
     * @return the transformation result
     */
    @SuppressLint("PrivateApi")
    public TransformResult transform(String pluginId, File classesDir) {
        File pluginDir = new File(context.getFilesDir(), "plugins/" + pluginId);
        File dexDir = new File(pluginDir, "dex");
        dexDir.mkdirs();

        // Collect all .class files
        List<String> classFiles = collectClassFiles(classesDir);
        if (classFiles.isEmpty()) {
            return TransformResult.failure("No .class files found");
        }

        // Build the D8 command arguments
        List<String> args = new ArrayList<>();
        args.add("--min-api");
        args.add("28");
        args.add("--output");
        args.add(dexDir.getAbsolutePath());
        // Add android.jar as the library classpath
        File androidJar = null;
        try {
            androidJar = AssetTools.releaseReadOnly(context, "android.jar", "android.jar");
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to extract android.jar", e);
        }
        if (androidJar != null && androidJar.exists()) {
            args.add("--lib");
            args.add(androidJar.getAbsolutePath());
        }
        // Add the host interface jar to the classpath (provides PluginContract etc.)
        File hostJar = null;
        try {
            hostJar = AssetTools.releaseReadOnly(context, "host-interfaces.jar", "host-interfaces.jar");
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to extract host-interfaces.jar", e);
        }
        if (hostJar != null && hostJar.exists()) {
            args.add("--classpath");
            args.add(hostJar.getAbsolutePath());
        }
        args.addAll(classFiles);

        try {
            // Invoke D8 through a dedicated ClassLoader via reflection.
            // D8.main(String[]) catches CompilationException and calls
            // System.exit(1) -- which kills the whole app process with no
            // error report to the user or the self-healing loop.
            // D8.run(String[]) propagates the exception instead, so the
            // failure becomes a normal, retryable pipeline error.
            ClassLoader d8Loader = getD8ClassLoader();
            Class<?> d8Class = d8Loader.loadClass("com.android.tools.r8.D8");
            Method runMethod;
            try {
                runMethod = d8Class.getMethod("run", String[].class);
            } catch (NoSuchMethodException e) {
                // Very old D8 builds may only expose main(); fall back.
                runMethod = d8Class.getMethod("main", String[].class);
            }
            runMethod.invoke(null, (Object) args.toArray(new String[0]));

            File outputDex = new File(dexDir, "classes.dex");
            if (outputDex.exists()) {
                // Android 14+ refuses to load writable dex; the output must be read-only
                AssetTools.makeReadOnly(outputDex);
                Log.i(TAG, "D8 transform succeeded for plugin: " + pluginId
                    + " dex size: " + outputDex.length() + " bytes");
                return TransformResult.success(outputDex);
            } else {
                return TransformResult.failure("D8 produced no dex file");
            }
        } catch (Throwable e) {
            Log.e(TAG, "D8 transform failed", e);
            return TransformResult.failure("D8 transformation failed: " + e.getMessage());
        }
    }

    /**
     * Recursively collects all .class files in a directory.
     */
    private List<String> collectClassFiles(File dir) {
        List<String> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(collectClassFiles(f));
            } else if (f.getName().endsWith(".class")) {
                result.add(f.getAbsolutePath());
            }
        }
        return result;
    }
}
