package com.meta.app.sandbox;

import java.util.Arrays;
import java.util.List;

/**
 * Sandbox ClassLoader - blocks dynamic plugins from accessing forbidden classes.
 */
public class SandboxClassLoader extends ClassLoader {

    private static final List<String> BLOCKED_PACKAGES = Arrays.asList(
        "com.android.internal",
        "android.os.SystemProperties",
        "sun.misc.Unsafe",
        "dalvik.system.VMDebug"
    );

    public SandboxClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String blocked : BLOCKED_PACKAGES) {
            if (name.startsWith(blocked)) {
                throw new ClassNotFoundException("Security restriction: access to " + name + " is blocked");
            }
        }
        return super.loadClass(name, resolve);
    }
}
