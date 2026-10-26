# MetaApk

<p align="center">
  <img src="docs/images/icon-512.png" width="160" alt="MetaApk logo"/>
</p>

**Describe an app in one sentence — MetaApk generates, compiles and runs it on your phone.**

No computer, no app store, no install. The phone is the development machine: say *"a stopwatch with start/stop/reset"* and a real, tappable app appears on screen within minutes.

---

## 📦 Getting started

1. Download and install the APK from [GitHub Releases](../../releases) — the release APK bundles **no API key**;
2. Open the app → **Settings**, and enter your own LLM endpoint and API key (OpenAI-compatible, primary + fallback model);
3. Back on the home screen, describe the app you want in the input box and press **Generate**;
4. Watch the log stream as it works step by step: write code → compile → run → tap through its own UI to verify;
5. Not happy? Keep talking: *"1+1 shows the wrong result"* sends it back to fix the project in place, as many rounds as it takes.

---

## ✨ What it does

- **Plain language in, working app out.** Every generated app stays in the **archive** — switch, reorder or delete anytime.
- **Verifies itself**: it runs the preview, inspects the UI, clicks, types and scrolls, and compares screenshots. When something is off it edits the code itself.
- **Heals itself**: compile errors, runtime crashes, sanitizer rejections and your feedback all loop back into the same workspace. Compile fixes have no attempt limit; transport failures retry up to three rounds, and permanent errors like bad credentials surface immediately.
- **Safe to run**: a crashing generated app can't take down the host — the error (with plugin stack frames) is captured and fed back to the model.
- **Shareable**: export an app as a `.metaapk` package and send it through any channel. The receiver's MetaApk imports it straight into the archive — no install, runs immediately, stays iterable.
- **Transparent**: every agent action streams a human-readable line into the host log (file written, build result, button tapped), so you can watch it work.

### Current limits

Generated apps are plain Android SDK code (Java 8) — a good fit for tools, games and info apps. **Not yet supported**: Kotlin/Compose, full third-party dependency resolution (Gradle/Maven), Android XML resource packaging. For apps that need such dependencies the model will try to self-implement with system APIs, with no guarantee of equivalence.

---

## ⚙️ How it works

```text
User intent (natural language)
   │  system_prompt.txt  (SDK-first, Java 8, explicit components)
   ▼
LLM (OpenAI-compatible API, primary + fallback model)
   │  streaming <java_code> block
   ▼
CodeSanitizer ── security checks (explicit components, network policy …)
   │
   ▼
CodeCompiler  ── ECJ compile (import fixes, BroadcastReceiver → stub routing)
   │
   ▼
DexTransformer ── D8 → classes.dex
   │
   ▼
PluginLoader  ── DexClassLoader + SandboxClassLoader
   │
   ▼
ActivityInjector ── plugin UI injected into the host
```

Three key design points:

- **Sandbox**: plugins run under a `SandboxClassLoader` that blocks dangerous host classes;
- **Explicit components**: system callbacks (alarms, notifications) must use explicitly named components, adapted at compile time to the host's pre-declared stubs — things a dynamically compiled class can't do are handled underneath;
- **Persistence**: the active app and the archive survive process death (SharedPreferences + on-disk dex); interrupted sessions resume in the same workspace.

---

## 🗂️ Project structure

```text
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

---

## 🚀 Building and testing

Prerequisites: JDK 17+, Android SDK (compileSdk 35), and an Android device/emulator (minSdk 28).

```bash
cp local.properties.example local.properties   # fill in sdk.dir and your LLM API key
./gradlew assembleDebug installDebug
```

> Configuration chain: `local.properties → BuildConfig → LlmConfig`. `local.properties` is git-ignored; never commit real keys.

Two test tiers:

```bash
# Unit tests (pure JVM: sanitizer, compiler pipeline, archive logic, cache)
./gradlew testDebugUnitTest

# UIAutomator instrumentation tests (real device; run the full generation loop)
./gradlew connectedDebugAndroidTest

# Deterministic platform regression (local mock model, no API key, no user projects touched)
./gradlew connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.meta.app.AgentPlatformTest,com.meta.app.AgentEngineDeviceTest'
```

The instrumentation tests live in `app/src/androidTest/` and cover the reliability behaviours end to end: generation, feedback rewriting, archive "library" semantics (switch / delete the active app), kill-and-resume of pending tasks, runtime permissions (including the system dialog) and the generation cache.

---

## 📄 License

[MIT](LICENSE) © MyTyrannosaurusBuddy

---

*Chinese docs: [README.zh-CN.md](README.zh-CN.md).*
