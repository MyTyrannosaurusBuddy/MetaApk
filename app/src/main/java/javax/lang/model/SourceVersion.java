package javax.lang.model;

import java.util.Locale;

/**
 * The on-device ECJ (assets/ecj.dex.jar) depends on the JDK's
 * {@code javax.lang.model.SourceVersion} to determine the supported Java
 * source levels (RELEASE_5 ~ RELEASE_17).
 *
 * The Android framework does not provide this class, and without it ECJ throws
 * {@code NoClassDefFoundError: Failed resolution of: Ljavax/lang/model/SourceVersion;}
 * during static initialization, breaking the whole compile pipeline.
 *
 * This minimal implementation is compiled into the host APK: ECJ's
 * DexClassLoader uses the host ClassLoader as its parent, so this class
 * resolves directly without touching ecj.dex.jar.
 */
public enum SourceVersion {

    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11,
    RELEASE_12,
    RELEASE_13,
    RELEASE_14,
    RELEASE_15,
    RELEASE_16,
    RELEASE_17;

    /** The highest source level supported by this implementation. */
    public static SourceVersion latest() {
        return RELEASE_17;
    }

    /** The highest stable source level supported by this implementation. */
    public static SourceVersion latestSupported() {
        return RELEASE_17;
    }

    /** Whether {@code version} is supported by this implementation. */
    public boolean isSupported(SourceVersion version) {
        return version.compareTo(this) <= 0;
    }

    /** Whether {@code version} equals this version. */
    public boolean isLatest(SourceVersion version) {
        return version == this;
    }

    /** "11" / "RELEASE_11" -> RELEASE_11 */
    public static SourceVersion forName(String sourceVersion) {
        return forSourceVersion(sourceVersion);
    }

    /** "11" / "RELEASE_11" -> RELEASE_11 */
    public static SourceVersion forSourceVersion(String sourceVersion) {
        if (sourceVersion == null) {
            throw new IllegalArgumentException("sourceVersion is null");
        }
        String name = sourceVersion.trim().toUpperCase(Locale.ROOT);
        if (!name.startsWith("RELEASE_")) {
            name = "RELEASE_" + name;
        }
        for (SourceVersion v : values()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown source version " + sourceVersion);
    }
}
