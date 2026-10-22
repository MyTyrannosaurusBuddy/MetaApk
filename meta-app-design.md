# Android 元应用设计文档

> 版本：1.0 | 目标平台：Android 14 (API 34) | 语言：Java

---

## 目录

1. [项目概述](#1-项目概述)
2. [架构总览与技术选型](#2-架构总览与技术选型)
3. [LLM 交互与 Prompt 设计](#3-llm-交互与-prompt-设计)
4. [端侧编译链路（ECJ + D8）](#4-端侧编译链路ecj--d8)
5. [插件化组件注入方案](#5-插件化组件注入方案)
6. [系统 API 桥接层设计](#6-系统-api-桥接层设计)
7. [自升级 / 编译新 APK 方案](#7-自升级--编译新-apk-方案)
8. [安全与沙箱隔离](#8-安全与沙箱隔离)
9. [风险分析](#9-风险分析)

---

## 1. 项目概述

### 1.1 目标

构建一个"元应用"（Meta-App），其自身不包含固定业务逻辑，而是通过与大语言模型（LLM）交互，动态获取 Java 代码并在运行时编译、注入、执行，使应用呈现任意形态。

**典型交互示例：**

```
用户输入："变成一个计算器"
      ↓
LLM 生成 Java 代码
      ↓
端侧 ECJ 编译 → D8 转 dex
      ↓
DexClassLoader 加载
      ↓
反射注入宿主 Activity
      ↓
界面呈现为计算器
```

### 1.2 核心能力

| 能力 | 描述 |
|------|------|
| 动态形态变换 | 通过 LLM 生成代码，运行时改变应用 UI 和逻辑 |
| 端侧代码编译 | 无需服务器，设备本地完成 Java → dex 编译 |
| 组件注入 | 向 Activity、Service、BroadcastReceiver、ContentProvider 注入动态逻辑 |
| 系统 API 访问 | 暴露摄像头、传感器、文件系统、IPC 等原生能力给动态代码 |
| 自升级 | 编译并安装新 APK，实现自我更新 |

---

## 2. 架构总览与技术选型

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                        宿主 APK                          │
│                                                         │
│  ┌──────────┐    ┌──────────────┐    ┌───────────────┐  │
│  │  用户 UI  │───▶│  LLM 交互层  │───▶│  代码编译引擎  │  │
│  │(宿主壳)  │    │(Prompt/解析) │    │  ECJ + D8    │  │
│  └──────────┘    └──────────────┘    └──────┬────────┘  │
│                                             │           │
│  ┌──────────────────────────────────────────▼────────┐  │
│  │                  插件运行时                        │  │
│  │  ┌─────────┐  ┌──────────┐  ┌─────────────────┐  │  │
│  │  │Activity │  │ Service  │  │BroadcastReceiver │  │  │
│  │  │  注入器  │  │  注入器  │  │     注入器       │  │  │
│  │  └─────────┘  └──────────┘  └─────────────────┘  │  │
│  └────────────────────────┬──────────────────────────┘  │
│                           │                             │
│  ┌────────────────────────▼──────────────────────────┐  │
│  │               系统 API 桥接层                      │  │
│  │  Camera | Sensor | File | Network | IPC | ...     │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 2.2 技术选型

| 模块 | 选型 | 理由 |
|------|------|------|
| 动态代码执行 | DexClassLoader | Android 官方唯一合规方案，兼容 API 34 |
| 端侧 Java 编译 | ECJ（Eclipse Compiler for Java） | 可在 Android 运行，无需 JDK，约 2MB |
| Class → Dex 转换 | D8（内置于 APK） | Google 官方工具，输出优化 dex |
| UI 动态渲染 | 反射构建 View 树 | 无需 XML，完全由代码描述 |
| 组件注入 | Hook + 占坑组件 | 绕过 Manifest 静态注册限制 |
| LLM 通信 | OkHttp + 流式 SSE | 支持 streaming，响应更快 |
| 沙箱隔离 | 独立 ClassLoader + SecurityManager 替代方案 | Android 14 兼容 |

### 2.3 模块划分

```
com.meta.app/
├── core/
│   ├── LLMClient.java          # LLM 通信
│   ├── CodeCompiler.java       # ECJ 编译
│   ├── DexTransformer.java     # D8 转换
│   └── PluginLoader.java       # DexClassLoader 加载
├── inject/
│   ├── ActivityInjector.java   # Activity 注入
│   ├── ServiceInjector.java    # Service 注入
│   └── ReceiverInjector.java   # BroadcastReceiver 注入
├── bridge/
│   ├── ApiBridge.java          # 桥接层总入口
│   ├── CameraBridge.java       # 摄像头
│   ├── SensorBridge.java       # 传感器
│   └── FileBridge.java         # 文件系统
├── sandbox/
│   └── SandboxClassLoader.java # 沙箱 ClassLoader
├── upgrade/
│   └── SelfUpgrader.java       # 自升级
└── ui/
    └── HostActivity.java       # 宿主壳 Activity
```

---

## 3. LLM 交互与 Prompt 设计

### 3.1 交互流程

```
用户输入自然语言需求
      ↓
构建 System Prompt（含 API 桥接层描述）
      ↓
发送请求（流式）
      ↓
解析响应，提取 <java_code> 标签内容
      ↓
校验代码结构（必须实现 PluginContract 接口）
      ↓
进入编译流程
```

### 3.2 System Prompt 设计

```
你是一个 Android 元应用的代码生成引擎。
你的任务是根据用户需求，生成符合以下规范的 Java 代码。

【强制规范】
1. 所有代码必须实现 PluginContract 接口：
   - void onCreate(Context context, ViewGroup container)
   - void onDestroy()
   - String getTitle()

2. UI 构建必须使用代码方式（不得使用 XML），通过 container 参数添加 View。

3. 可调用以下系统桥接方法（通过 ApiBridge 实例，已注入为 bridge 变量）：
   - bridge.camera.takePicture(callback)
   - bridge.file.readFile(path)
   - bridge.file.writeFile(path, content)
   - bridge.network.get(url, callback)
   - bridge.sensor.startListening(type, callback)
   - bridge.ipc.bindService(packageName, action, callback)

4. 禁止使用：
   - 反射访问私有系统 API（以 com.android.internal 开头的类）
   - Runtime.exec() 执行 shell 命令
   - 直接访问 /data/data 以外的路径

【输出格式】
<java_code>
// 你的完整 Java 代码
</java_code>
<description>
// 简短描述这段代码的功能
</description>
```

### 3.3 LLMClient 实现

```java
public class LLMClient {

    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient httpClient;

    public LLMClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 流式请求 LLM，回调返回增量文本
     */
    public void requestStream(String userPrompt, StreamCallback callback) {
        String systemPrompt = PromptBuilder.buildSystemPrompt();

        JSONObject body = new JSONObject();
        body.put("model", "your-model-name");
        body.put("stream", true);
        body.put("max_tokens", 4096);
        body.put("messages", new JSONArray()
            .put(new JSONObject().put("role", "user").put("content", userPrompt)));
        body.put("system", systemPrompt);

        Request request = new Request.Builder()
            .url(baseUrl + "/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(RequestBody.create(body.toString(),
                MediaType.parse("application/json")))
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                // 解析 SSE 流
                parseSSEStream(response, callback);
            }
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }
        });
    }

    private void parseSSEStream(Response response, StreamCallback callback) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream()))) {
            StringBuilder fullText = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if (data.equals("[DONE]")) break;
                    // 解析增量 token
                    String delta = extractDelta(data);
                    if (delta != null) {
                        fullText.append(delta);
                        callback.onToken(delta);
                    }
                }
            }
            // 从完整响应中提取代码
            String code = extractCode(fullText.toString());
            callback.onComplete(code);
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private String extractCode(String response) {
        // 提取 <java_code>...</java_code> 之间的内容
        int start = response.indexOf("<java_code>") + 11;
        int end = response.indexOf("</java_code>");
        if (start < 11 || end < 0) return null;
        return response.substring(start, end).trim();
    }

    public interface StreamCallback {
        void onToken(String token);       // 流式 token 回调
        void onComplete(String code);     // 代码提取完成
        void onError(Exception e);
    }
}
```

### 3.4 PluginContract 接口定义

```java
// 所有 LLM 生成的插件必须实现此接口
public interface PluginContract {

    /**
     * 插件初始化，在此构建 UI 并添加到 container
     * @param context  宿主 Context
     * @param container 宿主提供的根 ViewGroup
     * @param bridge   系统 API 桥接层
     */
    void onCreate(Context context, ViewGroup container, ApiBridge bridge);

    /**
     * 插件销毁，释放资源
     */
    void onDestroy();

    /**
     * 返回插件标题（用于宿主 Toolbar 显示）
     */
    String getTitle();

    /**
     * 可选：处理宿主 Activity 的 onBackPressed
     * @return true 表示插件已处理，false 交由宿主处理
     */
    default boolean onBackPressed() { return false; }
}
```

---

## 4. 端侧编译链路（ECJ + D8）

### 4.1 整体流程

```
LLM 输出 Java 代码字符串
      ↓
CodeCompiler（ECJ）
  输入：.java 源文件（写入应用私有目录）
  输出：.class 文件
      ↓
DexTransformer（D8）
  输入：.class 文件
  输出：classes.dex
      ↓
PluginLoader（DexClassLoader）
  输入：包含 classes.dex 的目录
  输出：已加载的 Class<PluginContract>
      ↓
反射实例化，注入宿主 Activity
```

### 4.2 目录结构（应用私有存储）

```
/data/data/com.meta.app/files/
└── plugins/
    └── {pluginId}/
        ├── source/
        │   └── Plugin.java      # LLM 生成的源码
        ├── classes/
        │   └── Plugin.class     # ECJ 编译产物
        └── dex/
            └── classes.dex      # D8 转换产物
```

### 4.3 CodeCompiler（ECJ 编译）

```java
public class CodeCompiler {

    private static final String PLUGIN_CLASS_NAME = "DynamicPlugin";
    private final Context context;

    public CodeCompiler(Context context) {
        this.context = context;
    }

    /**
     * 将 Java 源码编译为 .class 文件
     * @param pluginId  插件唯一 ID
     * @param sourceCode LLM 生成的 Java 源码
     * @return 编译成功返回 true
     */
    public CompileResult compile(String pluginId, String sourceCode) {
        File pluginDir = getPluginDir(pluginId);
        File sourceDir = new File(pluginDir, "source");
        File classesDir = new File(pluginDir, "classes");
        sourceDir.mkdirs();
        classesDir.mkdirs();

        // 写入源文件，包名固定为 com.meta.plugin
        String wrappedCode = wrapSourceCode(sourceCode);
        File sourceFile = new File(sourceDir, PLUGIN_CLASS_NAME + ".java");
        try {
            FileUtils.writeStringToFile(sourceFile, wrappedCode, "UTF-8");
        } catch (IOException e) {
            return CompileResult.failure("写入源文件失败: " + e.getMessage());
        }

        // ECJ 编译参数
        // -source 11 -target 11：目标 Java 版本
        // -classpath：包含 android.jar 和 PluginContract 等接口
        String androidJar = getAndroidJarPath();
        String hostJar = getHostJarPath(); // 包含 PluginContract、ApiBridge

        String[] args = {
            "-source", "11",
            "-target", "11",
            "-classpath", androidJar + File.pathSeparator + hostJar,
            "-d", classesDir.getAbsolutePath(),
            sourceFile.getAbsolutePath()
        };

        // 调用 ECJ（通过反射调用打包进 APK 的 ecj.jar）
        StringWriter errorOutput = new StringWriter();
        boolean success = invokeECJ(args, errorOutput);

        if (!success) {
            return CompileResult.failure("编译错误:\n" + errorOutput.toString());
        }
        return CompileResult.success(classesDir);
    }

    /**
     * 为 LLM 生成的代码添加包名和必要 import
     */
    private String wrapSourceCode(String userCode) {
        return "package com.meta.plugin;\n\n"
            + "import android.content.Context;\n"
            + "import android.view.*;\n"
            + "import android.widget.*;\n"
            + "import android.graphics.*;\n"
            + "import com.meta.app.bridge.ApiBridge;\n"
            + "import com.meta.app.core.PluginContract;\n\n"
            + "public class " + PLUGIN_CLASS_NAME + " implements PluginContract {\n"
            + userCode + "\n"
            + "}\n";
    }

    private boolean invokeECJ(String[] args, StringWriter errorWriter) {
        try {
            // ECJ 打包为 assets/ecj.jar，首次使用时释放到 filesDir
            Class<?> ecjClass = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main");
            Constructor<?> ctor = ecjClass.getConstructor(
                PrintWriter.class, PrintWriter.class, boolean.class);
            Object compiler = ctor.newInstance(
                new PrintWriter(errorWriter),
                new PrintWriter(errorWriter),
                false);
            Method compileMethod = ecjClass.getMethod("compile", String[].class);
            return (boolean) compileMethod.invoke(compiler, (Object) args);
        } catch (Exception e) {
            errorWriter.write("ECJ 调用失败: " + e.getMessage());
            return false;
        }
    }

    private File getPluginDir(String pluginId) {
        return new File(context.getFilesDir(), "plugins/" + pluginId);
    }

    // android.jar 需打包进 assets，首次启动时释放
    private String getAndroidJarPath() {
        return new File(context.getFilesDir(), "android.jar").getAbsolutePath();
    }

    private String getHostJarPath() {
        return new File(context.getFilesDir(), "host-interfaces.jar").getAbsolutePath();
    }
}
```

### 4.4 DexTransformer（D8 转换）

```java
public class DexTransformer {

    private final Context context;

    public DexTransformer(Context context) {
        this.context = context;
    }

    /**
     * 将 .class 文件目录转换为 dex
     * D8 工具打包进 APK，通过反射调用
     */
    public TransformResult transform(String pluginId, File classesDir) {
        File pluginDir = new File(context.getFilesDir(), "plugins/" + pluginId);
        File dexDir = new File(pluginDir, "dex");
        dexDir.mkdirs();
        File outputDex = new File(dexDir, "classes.dex");

        // 收集所有 .class 文件
        List<String> classFiles = collectClassFiles(classesDir);
        if (classFiles.isEmpty()) {
            return TransformResult.failure("未找到 .class 文件");
        }

        // 构建 D8 命令参数
        // --min-api 34：Android 14
        // --output：输出目录
        List<String> args = new ArrayList<>();
        args.add("--min-api");
        args.add("34");
        args.add("--output");
        args.add(dexDir.getAbsolutePath());
        args.addAll(classFiles);

        try {
            // 通过反射调用 D8 主类（打包进 assets/d8.jar）
            Class<?> d8Class = Class.forName("com.android.tools.r8.D8");
            Method mainMethod = d8Class.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args.toArray(new String[0]));

            if (outputDex.exists()) {
                return TransformResult.success(outputDex);
            } else {
                return TransformResult.failure("D8 未生成 dex 文件");
            }
        } catch (Exception e) {
            return TransformResult.failure("D8 转换失败: " + e.getMessage());
        }
    }

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
```

### 4.5 PluginLoader（DexClassLoader 加载）

```java
public class PluginLoader {

    private static final String PLUGIN_CLASS = "com.meta.plugin.DynamicPlugin";
    private final Context context;
    // 缓存已加载的插件 ClassLoader，避免重复加载
    private final Map<String, ClassLoader> loaderCache = new HashMap<>();

    public PluginLoader(Context context) {
        this.context = context;
    }

    /**
     * 从 dex 文件加载插件 Class
     */
    public LoadResult loadPlugin(String pluginId, File dexFile) {
        try {
            File optimizedDir = new File(context.getCacheDir(), "odex/" + pluginId);
            optimizedDir.mkdirs();

            // 使用 SandboxClassLoader 包裹，限制访问范围
            DexClassLoader dexClassLoader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optimizedDir.getAbsolutePath(),
                null,
                new SandboxClassLoader(context.getClassLoader()) // 沙箱父 ClassLoader
            );

            loaderCache.put(pluginId, dexClassLoader);

            Class<?> pluginClass = dexClassLoader.loadClass(PLUGIN_CLASS);

            // 验证是否实现了 PluginContract
            if (!PluginContract.class.isAssignableFrom(pluginClass)) {
                return LoadResult.failure("插件类未实现 PluginContract 接口");
            }

            PluginContract instance = (PluginContract) pluginClass
                .getDeclaredConstructor().newInstance();

            return LoadResult.success(instance);

        } catch (Exception e) {
            return LoadResult.failure("插件加载失败: " + e.getMessage());
        }
    }

    /**
     * 卸载插件，释放 ClassLoader 引用
     */
    public void unloadPlugin(String pluginId) {
        loaderCache.remove(pluginId);
        // 触发 GC，让 ClassLoader 被回收
        System.gc();
    }
}
```

### 4.6 编译性能预估（骁龙 8 Gen 系列）

| 代码规模 | ECJ 编译 | D8 转换 | 总耗时 |
|----------|----------|---------|--------|
| 简单 UI（100行） | ~400ms | ~300ms | ~700ms |
| 中等逻辑（300行） | ~800ms | ~500ms | ~1.3s |
| 复杂功能（800行） | ~2s | ~1s | ~3s |

> 编译过程应在子线程执行，主线程显示 Loading 动画。

---

## 5. 插件化组件注入方案

### 5.1 核心问题

Android 要求所有四大组件必须在 `AndroidManifest.xml` 中静态声明，动态生成的类无法直接成为 Activity 或 Service。

**解决方案：占坑组件 + Hook 转发**

```
Manifest 中预注册若干"占坑"组件（StubActivity01~10 等）
      ↓
启动动态插件时，实际启动对应的占坑组件
      ↓
Hook Activity 生命周期回调，转发给插件实例
      ↓
插件代码运行在占坑组件的上下文中
```

### 5.2 AndroidManifest 预注册占坑

```xml
<application>

    <!-- 宿主壳 Activity（主入口） -->
    <activity android:name=".ui.HostActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
    </activity>

    <!-- 占坑 Activity（支持不同 launchMode） -->
    <activity android:name=".stub.StubActivity$Standard"
        android:launchMode="standard"/>
    <activity android:name=".stub.StubActivity$SingleTop"
        android:launchMode="singleTop"/>
    <activity android:name=".stub.StubActivity$SingleTask"
        android:launchMode="singleTask"/>

    <!-- 占坑 Service -->
    <service android:name=".stub.StubService$01"/>
    <service android:name=".stub.StubService$02"/>

    <!-- 占坑 BroadcastReceiver -->
    <receiver android:name=".stub.StubReceiver$01"
        android:exported="false"/>

    <!-- 占坑 ContentProvider -->
    <provider android:name=".stub.StubProvider"
        android:authorities="${applicationId}.stub"
        android:exported="false"/>

</application>
```

### 5.3 ActivityInjector 实现

```java
public class ActivityInjector {

    private final HostActivity hostActivity;
    private PluginContract currentPlugin;

    public ActivityInjector(HostActivity hostActivity) {
        this.hostActivity = hostActivity;
    }

    /**
     * 将插件注入到宿主 Activity 的内容区域
     * 这是最简洁的方案：插件不是独立 Activity，
     * 而是寄生在宿主 Activity 的 ViewGroup 中
     */
    public void inject(PluginContract plugin, ApiBridge bridge) {
        // 销毁旧插件
        if (currentPlugin != null) {
            currentPlugin.onDestroy();
        }

        currentPlugin = plugin;

        // 清空宿主内容区域
        ViewGroup container = hostActivity.getPluginContainer();
        container.removeAllViews();

        // 更新标题
        hostActivity.setTitle(plugin.getTitle());

        // 让插件向 container 添加 View
        plugin.onCreate(hostActivity, container, hostActivity.getApiBridge());
    }

    /**
     * 处理宿主 Activity 的 onBackPressed，先问插件
     */
    public boolean onBackPressed() {
        return currentPlugin != null && currentPlugin.onBackPressed();
    }

    /**
     * 宿主 Activity 销毁时清理
     */
    public void destroy() {
        if (currentPlugin != null) {
            currentPlugin.onDestroy();
            currentPlugin = null;
        }
    }
}
```

### 5.4 HostActivity 实现

```java
public class HostActivity extends AppCompatActivity {

    private FrameLayout pluginContainer;
    private ActivityInjector injector;
    private ApiBridge apiBridge;
    private MetaEngine metaEngine; // 整合 LLM+编译+加载的统一入口

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 构建宿主 UI：顶部 Toolbar + 底部输入框 + 中间插件容器
        buildHostLayout();

        apiBridge = new ApiBridge(this);
        injector = new ActivityInjector(this);
        metaEngine = new MetaEngine(this);
    }

    private void buildHostLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("元应用");

        // 插件展示区域
        pluginContainer = new FrameLayout(this);
        LinearLayout.LayoutParams containerParams =
            new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        pluginContainer.setLayoutParams(containerParams);

        // 底部输入区域
        LinearLayout inputArea = buildInputArea();

        root.addView(toolbar);
        root.addView(pluginContainer);
        root.addView(inputArea);
        setContentView(root);
    }

    private LinearLayout buildInputArea() {
        LinearLayout inputArea = new LinearLayout(this);
        inputArea.setOrientation(LinearLayout.HORIZONTAL);
        inputArea.setPadding(16, 8, 16, 8);

        EditText input = new EditText(this);
        input.setHint("告诉我你想要什么应用...");
        input.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

        Button sendBtn = new Button(this);
        sendBtn.setText("生成");
        sendBtn.setOnClickListener(v -> {
            String userIntent = input.getText().toString().trim();
            if (!userIntent.isEmpty()) {
                input.setText("");
                onUserIntent(userIntent);
            }
        });

        inputArea.addView(input);
        inputArea.addView(sendBtn);
        return inputArea;
    }

    /**
     * 用户输入意图，触发完整的 LLM→编译→注入 流程
     */
    private void onUserIntent(String intent) {
        // 显示进度
        showLoading("正在生成代码...");

        metaEngine.process(intent, new MetaEngine.Callback() {
            @Override
            public void onPlugin(PluginContract plugin) {
                runOnUiThread(() -> {
                    hideLoading();
                    injector.inject(plugin, apiBridge);
                });
            }
            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    hideLoading();
                    Toast.makeText(HostActivity.this, "失败: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public ViewGroup getPluginContainer() { return pluginContainer; }
    public ApiBridge getApiBridge() { return apiBridge; }

    @Override
    public void onBackPressed() {
        if (!injector.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        injector.destroy();
    }
}
```

### 5.5 Service 注入方案

对于需要后台运行的场景，使用占坑 Service 代理：

```java
public class StubService extends Service {

    private ServicePlugin servicePlugin; // 动态加载的 Service 逻辑

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String pluginId = intent.getStringExtra("plugin_id");
        if (pluginId != null && servicePlugin == null) {
            // 从 PluginRegistry 获取已加载的插件实例
            servicePlugin = PluginRegistry.getInstance().getServicePlugin(pluginId);
            if (servicePlugin != null) {
                servicePlugin.onServiceStart(this, intent);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (servicePlugin != null) {
            servicePlugin.onServiceDestroy();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
```

---

## 6. 系统 API 桥接层设计

### 6.1 设计原则

- **白名单制**：只暴露明确允许的 API，拒绝其他所有调用
- **异步优先**：所有耗时操作通过回调返回，不阻塞插件代码
- **权限前置**：调用前检查运行时权限，不满足则回调错误

### 6.2 ApiBridge 总入口

```java
public class ApiBridge {

    public final CameraBridge camera;
    public final FileBridge file;
    public final NetworkBridge network;
    public final SensorBridge sensor;
    public final IpcBridge ipc;
    public final UiBridge ui;
    public final ApkBridge apk; // 自升级相关

    public ApiBridge(Context context) {
        this.camera = new CameraBridge(context);
        this.file = new FileBridge(context);
        this.network = new NetworkBridge(context);
        this.sensor = new SensorBridge(context);
        this.ipc = new IpcBridge(context);
        this.ui = new UiBridge(context);
        this.apk = new ApkBridge(context);
    }

    /**
     * 释放所有桥接资源（在 Activity.onDestroy 中调用）
     */
    public void release() {
        camera.release();
        sensor.release();
        ipc.release();
    }
}
```

### 6.3 主要桥接模块接口

```java
// 摄像头桥接
public class CameraBridge {
    // 拍照
    public void takePicture(PictureCallback callback);
    // 开始预览（返回 SurfaceView 供插件放入 container）
    public SurfaceView startPreview();
    // 停止预览
    public void stopPreview();
    public void release();

    public interface PictureCallback {
        void onSuccess(Bitmap bitmap);
        void onError(String message);
    }
}

// 文件桥接（只允许访问应用私有目录和用户授权目录）
public class FileBridge {
    public void readFile(String relativePath, ReadCallback callback);
    public void writeFile(String relativePath, String content, WriteCallback callback);
    public void listFiles(String relativePath, ListCallback callback);
    // 通过 SAF 让用户选择文件（访问外部存储）
    public void pickFile(Activity activity, PickCallback callback);

    public interface ReadCallback {
        void onSuccess(String content);
        void onError(String message);
    }
}

// 网络桥接
public class NetworkBridge {
    public void get(String url, Map<String, String> headers, ResponseCallback callback);
    public void post(String url, String jsonBody, Map<String, String> headers, ResponseCallback callback);

    public interface ResponseCallback {
        void onSuccess(int statusCode, String body);
        void onError(String message);
    }
}

// 传感器桥接
public class SensorBridge {
    // type 为 Sensor.TYPE_ACCELEROMETER 等常量
    public void startListening(int sensorType, SensorCallback callback);
    public void stopListening(int sensorType);
    public void release();

    public interface SensorCallback {
        void onData(float[] values);
    }
}

// IPC 桥接（访问其他应用的公开 Service）
public class IpcBridge {
    public void bindService(String packageName, String action, ServiceCallback callback);
    public void sendBroadcast(String action, Bundle extras);
    public void release();

    public interface ServiceCallback {
        void onConnected(IBinder binder);
        void onDisconnected();
        void onError(String message);
    }
}
```

---

## 7. 自升级 / 编译新 APK 方案

### 7.1 自升级流程

```
LLM 生成新版本宿主 APK 的配置/代码变更描述
      ↓
SelfUpgrader 调用构建脚本（预置在应用内）
      ↓
使用内置 ECJ + D8 重新编译变更的宿主类
      ↓
使用 apktool（内置）重新打包 APK
      ↓
对 APK 进行签名（使用预置的 debug keystore）
      ↓
调用 PackageInstaller API 请求安装
      ↓
用户确认安装 → 应用更新
```

> **注意**：Android 14 要求通过 `PackageInstaller` API 安装 APK，不能直接调用 `ACTION_INSTALL_PACKAGE`（需要 `REQUEST_INSTALL_PACKAGES` 权限）。

### 7.2 SelfUpgrader 实现

```java
public class SelfUpgrader {

    private final Context context;

    public SelfUpgrader(Context context) {
        this.context = context;
    }

    /**
     * 从 URL 下载新 APK 并安装（服务端已编译好的升级包）
     */
    public void upgradeFromUrl(String apkUrl, UpgradeCallback callback) {
        File downloadDir = new File(context.getCacheDir(), "upgrade");
        downloadDir.mkdirs();
        File apkFile = new File(downloadDir, "update.apk");

        // 下载 APK
        downloadFile(apkUrl, apkFile, new DownloadCallback() {
            @Override
            public void onComplete(File file) {
                installApk(file, callback);
            }
            @Override
            public void onError(String message) {
                callback.onError("下载失败: " + message);
            }
        });
    }

    /**
     * 使用 PackageInstaller API 安装 APK（Android 14 推荐方式）
     */
    public void installApk(File apkFile, UpgradeCallback callback) {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);

        try {
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            // 写入 APK 数据
            try (InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("package", 0, apkFile.length())) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, len);
                }
                session.fsync(out);
            }

            // 创建安装完成的 PendingIntent
            Intent intent = new Intent(context, InstallResultReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            session.commit(pendingIntent.getIntentSender());
            callback.onInstallStarted();

        } catch (IOException e) {
            callback.onError("安装失败: " + e.getMessage());
        }
    }

    public interface UpgradeCallback {
        void onInstallStarted();   // 安装弹窗已弹出，等待用户确认
        void onError(String message);
    }
}
```

### 7.3 编译新 APK（完全端侧，实验性）

端侧完整编译 APK 是高级场景，需要内置以下工具链：

| 工具 | 用途 | 包大小 |
|------|------|--------|
| ECJ | Java → .class | ~2MB |
| D8 | .class → .dex | ~5MB |
| apktool（精简版） | 资源打包 + APK 组装 | ~3MB |
| 签名工具 | APK 签名 | ~0.5MB |

> 总计约 10MB 工具链，仅在"自升级"功能启用时从 assets 释放，不影响普通用户。

---

## 8. 安全与沙箱隔离

### 8.1 威胁模型

| 威胁 | 攻击向量 | 影响 |
|------|---------|------|
| 恶意 LLM 响应 | LLM 被注入，生成恶意代码 | 数据窃取、权限滥用 |
| 代码注入 | 用户输入包含代码片段 | 执行任意代码 |
| 权限提升 | 动态代码访问 hidden API | 突破 Android 沙箱 |
| 存储攻击 | 动态代码访问 /data 其他应用 | 隐私泄露 |

### 8.2 SandboxClassLoader

```java
/**
 * 沙箱 ClassLoader：拦截动态插件对禁止类的访问
 */
public class SandboxClassLoader extends ClassLoader {

    // 黑名单：禁止动态代码加载的包前缀
    private static final List<String> BLOCKED_PACKAGES = Arrays.asList(
        "com.android.internal",          // 隐藏系统 API
        "android.os.SystemProperties",   // 系统属性
        "java.lang.Runtime",             // 执行 shell
        "java.lang.ProcessBuilder",      // 执行 shell
        "sun.misc.Unsafe",               // 不安全内存操作
        "dalvik.system.VMDebug"          // 调试接口
    );

    // 白名单：允许直接访问的包（不经过沙箱检查）
    private static final List<String> ALLOWED_PACKAGES = Arrays.asList(
        "android.view",
        "android.widget",
        "android.graphics",
        "android.content.Context",
        "android.os.Bundle",
        "android.os.Handler",
        "android.os.Looper",
        "com.meta.app.bridge",           // 桥接层（白名单）
        "com.meta.app.core",             // 接口定义
        "java.util",
        "java.lang.String",
        "java.lang.Math",
        "java.lang.StringBuilder"
    );

    public SandboxClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        // 检查黑名单
        for (String blocked : BLOCKED_PACKAGES) {
            if (name.startsWith(blocked)) {
                throw new ClassNotFoundException(
                    "安全限制：禁止访问 " + name);
            }
        }
        return super.loadClass(name, resolve);
    }
}
```

### 8.3 代码静态检查（编译前）

在 ECJ 编译之前，对 LLM 生成的代码做简单的静态扫描：

```java
public class CodeSanitizer {

    // 禁止出现的字符串模式
    private static final List<Pattern> FORBIDDEN_PATTERNS = Arrays.asList(
        Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
        Pattern.compile("ProcessBuilder"),
        Pattern.compile("Class\\.forName\\(\"com\\.android\\.internal"),
        Pattern.compile("getDeclaredField.*setAccessible.*true"),
        Pattern.compile("System\\.exit"),
        Pattern.compile("android\\.os\\.SystemProperties"),
        Pattern.compile("\"su\""),                    // su 命令
        Pattern.compile("/proc/self/mem")             // 进程内存
    );

    public SanitizeResult check(String sourceCode) {
        for (Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(sourceCode).find()) {
                return SanitizeResult.blocked(
                    "代码包含禁止的操作: " + pattern.pattern());
            }
        }
        return SanitizeResult.pass();
    }
}
```

### 8.4 运行时权限控制

```java
public class PermissionGuard {

    private final Activity activity;
    // 记录用户已授权的权限请求，避免重复弹窗
    private final Set<String> grantedByUser = new HashSet<>();

    public PermissionGuard(Activity activity) {
        this.activity = activity;
    }

    /**
     * 在桥接层调用敏感 API 之前调用此方法
     */
    public boolean checkAndRequest(String permission, PermissionCallback callback) {
        if (ContextCompat.checkSelfPermission(activity, permission)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        // 向用户解释为什么需要此权限
        String reason = getPermissionReason(permission);
        new AlertDialog.Builder(activity)
            .setTitle("需要权限")
            .setMessage("当前功能需要" + reason + "，是否授权？")
            .setPositiveButton("授权", (d, w) ->
                ActivityCompat.requestPermissions(activity,
                    new String[]{permission}, generateRequestCode(permission)))
            .setNegativeButton("拒绝", (d, w) ->
                callback.onDenied(permission))
            .show();
        return false;
    }
}
```

---

## 9. 风险分析

### 9.1 技术风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Android 14 限制 DexClassLoader 加载应用外 dex | 低 | 高 | 使用应用私有目录，规避 W^X 限制 |
| ECJ/D8 在 ARM 上运行异常 | 中 | 高 | 充分测试；备选方案：服务端编译 |
| LLM 生成代码编译失败率高 | 中 | 中 | 完善 Prompt；增加编译错误反馈重试 |
| 占坑组件不够用（插件并发） | 低 | 中 | 预注册足够数量；动态管理占坑池 |
| Google Play 上架审核拒绝 | 高 | 高 | 此类应用难以通过 Play 审核，建议侧载分发 |

### 9.2 Google Play 政策风险

**高风险**：Google Play 政策明确禁止"下载并执行代码"的应用，本项目的 DexClassLoader 动态执行方案很可能被拒绝上架。

**建议**：
- 以侧载（APK 直接安装）方式分发
- 或在企业内部分发（不走 Play Store）
- 如需上架，考虑将功能限制在 WebView 执行 JS（Play 政策允许）

### 9.3 LLM 代码质量风险

LLM 生成的代码可能存在：
- 编译错误（需要重试机制）
- 运行时异常（需要 try-catch 兜底）
- 逻辑错误（用户体验问题）

**建议**：将插件运行在 try-catch 中，任何未捕获异常都回退到默认界面，并将错误信息反馈给 LLM 进行修正。

```java
try {
    plugin.onCreate(context, container, bridge);
} catch (Throwable t) {
    // 显示错误，反馈给 LLM 修正
    showError("插件运行出错，正在修正...");
    metaEngine.retry(lastIntent, t.getMessage());
}
```

### 9.4 APK 体积影响

内置工具链增加的体积：

| 组件 | 大小 |
|------|------|
| ECJ 编译器 | ~2MB |
| D8 工具 | ~5MB |
| android.jar（接口编译用） | ~8MB |
| host-interfaces.jar | <0.1MB |
| **合计** | **~15MB** |

建议将工具链打包为独立模块，用户首次触发编译时再下载，避免所有用户承担体积成本。

---

*文档结束 | 如需补充任何模块的详细设计，请告知*
