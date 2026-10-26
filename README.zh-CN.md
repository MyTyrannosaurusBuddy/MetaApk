# MetaApk

<p align="center">
  <img src="docs/images/icon-512.png" width="160" alt="MetaApk 图标"/>
</p>

**一句话描述应用——MetaApk 在设备上现场生成、编译并运行它。**

MetaApk 是一个 Android 宿主应用：把自然语言需求交给大模型生成标准 Android SDK 代码，用 ECJ 编译、D8 转 DEX、`DexClassLoader` 动态加载并注入宿主界面——不用应用商店、不用重新打包、不用安装。

---

## ✨ 功能

- **文字生成应用**：输入「一个带开始/停止/重置的秒表」，几分钟内得到可用的应用。
- **项目式 Agent**：每个应用有独立、多文件的持久工作区，模型通过文件读写、精确编辑、搜索和构建工具持续修改项目。源码读取支持分页，编辑和检查点通过临时文件替换写入。
- **可交互运行验证**：模型可运行预览、检查界面、点击、输入和滚动，并收到界面文字与截图；不支持图片的模型自动退回界面文字。预览中的运行异常也会反馈给模型。
- **SDK 优先生成**：大模型只写标准 Android SDK 代码（`Activity` 式视图、`AlarmManager`、`NotificationManager`……），不教它任何私有 API。
- **运行时适配层**：动态编译类做不到的事（如 Manifest 预注册组件 `BroadcastReceiver`）在编译期/运行期**底下**适配——`MainActivity` 蹦床、`StubReceiver` 路由、`RuntimeCompat`。
- **自愈闭环**：编译错误、运行时崩溃、安全检查拒绝、乃至「1+1 显示 Error」这类用户反馈，都会反馈给模型在原工作区修复。编译修复不限制次数；连续网络错误最多尝试三轮，认证等永久错误直接显示。
- **崩溃护栏**：插件代码运行抛异常不再带崩进程；错误（含插件栈帧）被捕获并回喂模型。
- **应用存档**：生成过的应用全部保留（可切换/排序/删除）。生成新应用时旧应用自动进存档；**反馈修正原地升级，不产生重复存档条目**。
- **反馈与恢复**：原始需求、累计反馈和完整工具调用轮次保存在设备上；中断后继续同一工作区。较早的工具输出会压缩，项目决策可保存在 `PROJECT.md` 中。
- **进展可见**：Agent 的每一步动作（写文件、编译、预览、点击界面）都会以一行人类可读的日志流进宿主日志区，亲眼看着它干活。
- **应用分享**：应用以 `.metaapk` 包（dex + 标题/需求 + 项目源码）通过任意渠道分享；接收方的 MetaApk 直接导入存档库——**零安装、打开即用、还能继续迭代**。
- **项目文件查看**：只读浏览当前应用的工作区文件，看清 Agent 构建了什么。

当前运行后端使用 ECJ → D8 → 动态插件，支持 Java 8、标准 Android SDK 和 Gson。Java 8 lambda 的编译期符号随工具链接口提供；Receiver 会按整个项目解析和适配。它尚未提供完整 Gradle/Maven 依赖解析、Kotlin/Compose、Android 资源打包、完整 Service/Provider 代理或独立应用进程隔离。

## 📦 下载

预构建 APK 发布在 [GitHub Releases](../../releases)。发布版 APK **不内置任何 API Key** —— 首次启动时进入**设置**，填入你自己的 LLM 地址与 API Key。

## 🚀 快速开始

前置：JDK 17+、Android SDK（compileSdk 35）、Android 设备/模拟器（minSdk 28）。

```bash
cp local.properties.example local.properties   # 填入 sdk.dir 与你的 LLM API Key
./gradlew assembleDebug installDebug
```

打开应用，点击输入框描述你想要的应用，按 **生成**。

> 默认配置读取链路：`local.properties → BuildConfig → LlmConfig`。`local.properties` 已被 git 忽略，真实密钥永不入库。

## ⚙️ 工作原理

```
用户意图（自然语言）
   │  system_prompt.txt（SDK 优先、Java 8、显式组件）
   ▼
大模型（OpenRouter 兼容 API，主模型 + 备用模型）
   │  流式输出 <java_code> 块
   ▼
CodeSanitizer ── 安全检查（显式 Receiver、网络策略……）
   │
   ▼
CodeCompiler  ── ECJ 编译（补 import、BroadcastReceiver → StubReceiver 路由）
   │
   ▼
DexTransformer ── D8 → classes.dex
   │
   ▼
PluginLoader  ── DexClassLoader + SandboxClassLoader
   │
   ▼
ActivityInjector ── 插件 UI 注入 HostActivity
```

- **沙箱**：插件在 `SandboxClassLoader` 下运行，隔离宿主危险类。
- **显式组件**：未来系统回调（闹钟、通知）必须使用显式命名组件——编译期适配到宿主预声明的占坑组件。
- **持久化**：活跃应用与存档在进程死亡后仍然存活（SharedPreferences + 磁盘 dex）。

## 🗂️ 项目结构

```
app/src/main/java/com/meta/app/
├── MainActivity.java        # 别名蹦床：兼容大模型默认的 "MainActivity" 跳转
├── ui/HostActivity.java     # 宿主壳：输入框、日志区、插件容器
├── ui/SettingsActivity.java # LLM 地址/密钥/模型配置
├── core/                    # MetaEngine 流水线、CodeCompiler、DexTransformer、
│                            # PluginLoader、CodeSanitizer、PluginRuntimeGuard、
│                            # RuntimeCompat、PluginArchive、LLMClient
├── bridge/                  # 交给插件的 ApiBridge / IPC 工具
├── sandbox/                 # SandboxClassLoader
└── stub/                    # 预声明的占坑 Activity/Service/Receiver/Provider
app/src/main/assets/         # system_prompt.txt、ecj.dex.jar、d8.dex.jar、android.jar
```

## 🧪 测试

两级测试：

```bash
# 单元测试（纯 JVM：sanitizer、编译管线、存档逻辑、缓存）
./gradlew testDebugUnitTest

# UIAutomator 仪器测试（真机运行完整生成闭环）
./gradlew connectedDebugAndroidTest

# 确定性平台回归（本地模拟模型，不需要 API Key，不操作用户项目）
./gradlew connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.meta.app.AgentPlatformTest,com.meta.app.AgentEngineDeviceTest'
```

仪器测试位于 `app/src/androidTest/`，端到端覆盖可靠性行为：生成、反馈修正、
存档“库”语义（切换/删除当前应用）、任务断点续跑、运行时权限（含系统弹窗）、
生成缓存。需要联网的设备/模拟器；LLM 端点取自 debug `BuildConfig`
（配置 `local.properties`）。

## 📄 许可证

[MIT](LICENSE) © MyTyrannosaurusBuddy

---

*English docs: [README.md](README.md).*
