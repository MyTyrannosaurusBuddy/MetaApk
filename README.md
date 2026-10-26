# MetaApk

<p align="center">
  <img src="docs/images/icon-512.png" width="160" alt="MetaApk logo"/>
</p>

**Describe an app in one sentence — MetaApk generates, compiles and runs it on your device.**

MetaApk is an Android host app that turns natural-language descriptions into fully working, dynamically loaded applications. It asks an LLM for standard Android SDK code, compiles it with the Eclipse Java compiler (ECJ), converts it to DEX, loads it through `DexClassLoader` and injects it into the host UI — no app store, no rebuild, no install.

---

## ✨ Features

- **Text → app**: type e.g. *"a stopwatch with start/stop/reset"* and get a working app in minutes.
- **Project agent**: each app has a persistent multi-file workspace. The model reads paginated source, edits exact matches, searches and builds incrementally. File writes and conversation checkpoints use staged replacement.
- **Interactive verification**: run a live preview, inspect its UI, click, type and scroll. UI text, screenshots and captured runtime errors return to the model; text-only models automatically continue using the UI dump.
- **SDK-first generation**: the LLM writes plain Android SDK code (`Activity`-style views, `AlarmManager`, `NotificationManager`, …). No proprietary APIs are taught to the model.
- **Runtime adaptation layer**: things that a dynamically compiled class can't do (manifest-registered components such as `BroadcastReceiver`) are adapted *underneath* at compile/runtime — `MainActivity` trampoline, `StubReceiver` routing, `RuntimeCompat`.
- **Self-healing loop**: compile errors, runtime crashes, sanitizer rejections and user feedback continue in the existing workspace. Compile fixes have no attempt limit; consecutive transport failures stop after three rounds, and permanent errors such as invalid credentials surface immediately.
- **Crash guard**: plugin code that throws at runtime no longer kills the process; the error (with plugin stack frames) is captured and fed back to the model.
- **App archive**: every previously generated app is kept in an archive (switch / reorder / delete). Generating a new app archives the current one; feedback revisions upgrade it *in place* without duplicate entries.
- **Feedback and recovery**: original requirements, accumulated feedback and completed tool turns persist on device. Interrupted sessions resume in the same workspace. Older tool output is compacted; project decisions can be retained in `PROJECT.md`.
- **Visible progress**: every agent action streams a human-readable line into the host log (writing a file, building, previewing, tapping the UI) so you can watch the agent work.
- **App sharing**: share an app as a `.metaapk` package (dex + title/prompt + project sources) through any channel. The receiver's MetaApk imports it straight into the archive — no install, runs immediately, stays iterable.
- **Project files viewer**: browse the workspace files of the current app read-only, for transparency into what the agent built.

The current backend is ECJ → D8 → dynamically loaded plugins, with Java 8, public Android SDK APIs and Gson. Compiler-only lambda bootstrap symbols are included, and named receivers are adapted across project files. Full Gradle/Maven dependency resolution, Kotlin/Compose, Android resource packaging, complete Service/Provider proxies and separate application processes remain future work.

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
