# MetaApk

<p align="center">
  <img src="docs/images/icon-512.png" width="160" alt="MetaApk logo"/>
</p>

**Describe an app in one sentence — MetaApk generates, compiles and runs it on your device.**

MetaApk is an Android host app that turns natural-language descriptions into fully working, dynamically loaded applications. It asks an LLM for standard Android SDK code, compiles it with the Eclipse Java compiler (ECJ), converts it to DEX, loads it through `DexClassLoader` and injects it into the host UI — no app store, no rebuild, no install.

---

## ✨ Features

- **Text → app**: type e.g. *"a stopwatch with start/stop/reset"* and get a working app in minutes.
- **SDK-first generation**: the LLM writes plain Android SDK code (`Activity`-style views, `AlarmManager`, `NotificationManager`, …). No proprietary APIs are taught to the model.
- **Runtime adaptation layer**: things that a dynamically compiled class can't do (manifest-registered components such as `BroadcastReceiver`) are adapted *underneath* at compile/runtime — `MainActivity` trampoline, `StubReceiver` routing, `RuntimeCompat`.
- **Self-healing loop**: compile errors, runtime crashes, sanitizer rejections and even *"1+1 shows Error"* style user feedback are fed back to the LLM — with the previous full source — and regenerated automatically until it works. No attempt limit.
- **Crash guard**: plugin code that throws at runtime no longer kills the process; the error (with plugin stack frames) is captured and fed back to the model.
- **App archive**: every previously generated app is kept in an archive (switch / reorder / delete). Generating a new app archives the current one; feedback revisions upgrade it *in place* without duplicate entries.
- **Feedback history**: iterative feedback carries the full history, so the model never forgets earlier complaints.

## 📦 Downloads

Prebuilt APKs are published on [GitHub Releases](../../releases). The release APK bundles **no API key** — on first launch, open **Settings** and fill in your own LLM endpoint and API key.

## 🚀 Quick start

Prerequisites: JDK 17+, Android SDK (compileSdk 35), and an Android device/emulator (minSdk 28).

```bash
cp local.properties.example local.properties   # fill in sdk.dir and your LLM API key
./gradlew assembleDebug installDebug
```

Then open the app, tap the input box, describe the app you want and press **Generate**.

> Defaults are read from `local.properties → BuildConfig → LlmConfig`. `local.properties` is git-ignored; never commit real keys.

## ⚙️ How it works

```
User intent (natural language)
   │  system_prompt.txt  (SDK-first, Java 8, explicit components)
   ▼
LLM (OpenRouter-compatible API, primary + fallback model)
   │  streaming, <java_code> block
   ▼
CodeSanitizer ── security checks (explicit receivers, network policy …)
   │
   ▼
CodeCompiler  ── ECJ compile (import fixes, BroadcastReceiver → StubReceiver routing)
   │
   ▼
DexTransformer ── D8 → classes.dex
   │
   ▼
PluginLoader  ── DexClassLoader + SandboxClassLoader
   │
   ▼
ActivityInjector ── plugin UI injected into HostActivity
```

- **Sandbox**: plugins run under a `SandboxClassLoader` that blocks dangerous host classes.
- **Explicit components**: future system callbacks (alarms, notifications) must use explicit named components — adapted to the host's pre-declared stubs.
- **Persistence**: the active app and the archive survive process death (`SharedPreferences` + on-disk dex).

## 🗂️ Project structure

```
app/src/main/java/com/meta/app/
├── MainActivity.java        # alias/trampoline for LLM-written "MainActivity" jumps
├── ui/HostActivity.java     # host shell: input, log area, plugin container
├── ui/SettingsActivity.java # LLM endpoint/key/model configuration
├── core/                    # MetaEngine pipeline, CodeCompiler, DexTransformer,
│                            # PluginLoader, CodeSanitizer, PluginRuntimeGuard,
│                            # RuntimeCompat, PluginArchive, LLMClient
├── bridge/                  # ApiBridge, IPC helpers handed to plugins
├── sandbox/                 # SandboxClassLoader
└── stub/                    # pre-declared Activity/Service/Receiver/Provider stubs
app/src/main/assets/         # system_prompt.txt, ecj.dex.jar, d8.dex.jar, android.jar
```

## 🧪 Testing

Two tiers:

```bash
# Unit tests (pure JVM: sanitizer, compiler pipeline, archive logic, cache)
./gradlew testDebugUnitTest

# UIAutomator instrumentation tests (real device; run the full generation loop)
./gradlew connectedDebugAndroidTest
```

The instrumentation tests live in `app/src/androidTest/` and cover the
reliability behaviours end to end: generation, feedback rewriting, the
archive "library" semantics (switch / delete the active app), kill-and-resume
of pending tasks, runtime permissions (including the system dialog) and the
generation cache. They need a device/emulator with network access; the LLM
endpoint is taken from the debug `BuildConfig` (configure `local.properties`).

## 📄 License

[MIT](LICENSE) © MyTyrannosaurusBuddy

---

*Chinese docs: [README.zh-CN.md](README.zh-CN.md).*
