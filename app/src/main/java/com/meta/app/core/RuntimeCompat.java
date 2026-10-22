package com.meta.app.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.meta.app.sandbox.SandboxClassLoader;
import com.meta.app.stub.StubReceiver;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import dalvik.system.DexClassLoader;

/**
 * Runtime compatibility layer: lets dynamic plugins be written in idiomatic
 * Android SDK style, while the host adapts underneath the cases that require
 * manifest pre-registered components.
 */
public final class RuntimeCompat {

    private static final String TAG = "RuntimeCompat";
    public static final String EXTRA_PROXY_PLUGIN_ID = "__meta_plugin_id";
    public static final String EXTRA_PROXY_RECEIVER_CLASS = "__meta_receiver_class";

    private RuntimeCompat() {}

    public static Intent createBroadcastIntent(Context context, String receiverClassName,
                                               String pluginId) {
        Intent intent = new Intent(context, StubReceiver.R01.class);
        intent.putExtra(EXTRA_PROXY_PLUGIN_ID, pluginId);
        intent.putExtra(EXTRA_PROXY_RECEIVER_CLASS, receiverClassName);
        return intent;
    }

    public static void dispatchBroadcast(Context context, Intent proxyIntent) {
        if (context == null || proxyIntent == null) {
            return;
        }

        String pluginId = proxyIntent.getStringExtra(EXTRA_PROXY_PLUGIN_ID);
        String receiverClassName = proxyIntent.getStringExtra(EXTRA_PROXY_RECEIVER_CLASS);
        if (TextUtils.isEmpty(pluginId) || TextUtils.isEmpty(receiverClassName)) {
            return;
        }

        try {
            Class<?> receiverClass = loadReceiverClass(context, pluginId, receiverClassName);
            BroadcastReceiver receiver = instantiateReceiver(receiverClass);
            if (receiver == null) {
                Log.e(TAG, "Unable to instantiate dynamic BroadcastReceiver: " + receiverClassName);
                return;
            }

            Intent forwarded = new Intent(proxyIntent);
            forwarded.removeExtra(EXTRA_PROXY_PLUGIN_ID);
            forwarded.removeExtra(EXTRA_PROXY_RECEIVER_CLASS);
            forwarded.setComponent(null);
            receiver.onReceive(context, forwarded);
        } catch (Throwable t) {
            Log.e(TAG, "Dynamic BroadcastReceiver dispatch failed: plugin=" + pluginId
                + " receiver=" + receiverClassName, t);
            // Do not swallow silently: feed the error back to the model for a fix
            PluginRuntimeGuard.reportPluginError(t);
        }
    }

    private static Class<?> loadReceiverClass(Context context, String pluginId,
                                              String receiverClassName) throws Exception {
        File dexFile = new File(context.getFilesDir(), "plugins/" + pluginId + "/dex/classes.dex");
        if (!dexFile.exists()) {
            throw new IllegalStateException("Plugin dex does not exist: " + dexFile.getAbsolutePath());
        }

        AssetTools.makeReadOnly(dexFile);
        File optimizedDir = new File(context.getCacheDir(), "odex/dispatch/" + pluginId);
        optimizedDir.mkdirs();

        ClassLoader parent = new SandboxClassLoader(context.getClassLoader());
        DexClassLoader loader = new DexClassLoader(
            dexFile.getAbsolutePath(),
            optimizedDir.getAbsolutePath(),
            null,
            parent
        );
        return loader.loadClass(receiverClassName);
    }

    private static BroadcastReceiver instantiateReceiver(Class<?> receiverClass) throws Exception {
        if (!BroadcastReceiver.class.isAssignableFrom(receiverClass)) {
            return null;
        }

        try {
            Constructor<?> ctor = receiverClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (BroadcastReceiver) ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            // Fall back to trying non-static inner classes
        }

        Class<?> enclosingClass = receiverClass.getEnclosingClass();
        if (enclosingClass != null && !Modifier.isStatic(receiverClass.getModifiers())) {
            Constructor<?> outerCtor = enclosingClass.getDeclaredConstructor();
            outerCtor.setAccessible(true);
            Object outer = outerCtor.newInstance();

            Constructor<?> innerCtor = receiverClass.getDeclaredConstructor(enclosingClass);
            innerCtor.setAccessible(true);
            return (BroadcastReceiver) innerCtor.newInstance(outer);
        }

        return null;
    }
}
