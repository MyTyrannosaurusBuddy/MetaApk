# ==== MetaApk keep rules ====

# ---- Dynamic plugin ABI ----
# Plugin dex is compiled externally against host-interfaces.jar and resolves
# these class/member names at runtime against the host class loader. Shrinking
# or obfuscating any of them would break every generated app.
-keep class com.meta.app.bridge.** { *; }
-keep class com.meta.app.core.PluginContract { *; }
-keep class com.meta.app.core.PluginContract$* { *; }
-keep class com.meta.app.core.RuntimeCompat { *; }
-keep class com.meta.app.core.AssetTools { *; }
-keep class com.meta.app.sandbox.SandboxClassLoader { *; }
# Manifest-registered stub components: kept by aapt's manifest rules today,
# but kept here explicitly so no R8 change can ever rename them (the
# manifest and the StubReceiver routing reference them by exact name).
-keep class com.meta.app.stub.** { *; }

# ---- Gson-reflected models (serialized by field name) ----
-keep class com.meta.app.core.PluginArchive$Entry { *; }
-keep class com.meta.app.core.MetaEngine$PendingTask { *; }
-keep class com.meta.app.core.GenerationCache$Entry { *; }
-keep class com.meta.app.core.LLMClient$ChatMessage { *; }
-keep class com.meta.app.core.LLMClient$ToolCall { *; }

# ---- Gson is advertised to plugins as "the only third-party library
# available directly" (system_prompt.txt, rule 5). R8 would strip the
# classes the host itself never references (e.g. JsonReader/JsonWriter),
# making them fail with ClassNotFoundException in release builds only. ----
-keep class com.google.gson.** { *; }

# ---- ECJ (ecj.dex.jar, loaded via DexClassLoader) resolves
# javax.lang.model.SourceVersion through the PARENT classloader. R8 strips
# it as unreachable (only ECJ's dex references it), which breaks ECJ
# initialization in release builds with NoClassDefFoundError. ----
-keep class javax.lang.model.** { *; }

# Gson generic metadata
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
