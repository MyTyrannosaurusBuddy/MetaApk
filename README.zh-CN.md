# MetaApk

<p align="center">
  <img src="docs/images/icon-512.png" width="160" alt="MetaApk 图标"/>
</p>

**用一句话说出你想要的应用，MetaApk 在你的手机上现场生成、编译并运行它。**

不用电脑、不用应用商店、不用安装。手机就是开发机：你说"做一个带开始/停止/重置的秒表"，几分钟后屏幕上就是一个真的、可以点的应用。

---

## 📦 怎么用

1. 从 [GitHub Releases](../../releases) 下载 APK 安装（APK 里**没有内置 API Key**）；
2. 打开应用 → 进**设置**，填上你的大模型接口地址和 API Key（支持 OpenAI 兼容接口，主模型 + 备用模型）；
3. 回到主页，输入框里描述你想要的应用，点**生成**；
4. 看着日志区里它一步步干活：写代码 → 编译 → 跑起来 → 自己点一遍界面验证；
5. 不满意？在输入框里继续提意见（"1+1 显示不对"），它会在原项目上修到你说行为止。

---

## ✨ 它能做什么

- **说人话，得应用**：自然语言进，能用的应用出。生成过的应用全部留在**应用存档**里，随时切换、排序、删除。
- **自己会验证**：生成的界面它能自己运行、查看、点击、输入，还拿截图比对——哪里不对它会自己改。
- **做错了自己修**：编译不过、运行崩溃、你的反馈意见，都会喂回给它继续修。编译错误不限制修改次数；网络断了自动重试三轮，密钥错误这类硬伤直接明说。
- **崩了不伤手机**：生成的应用运行出错不会带崩宿主，错误被拦下来并回喂给模型。
- **应用可分享**：把应用导出成 `.metaapk` 包（几十 KB 到几百 KB）发给别人，对方用 MetaApk 直接导入打开——零安装、能继续改。
- **看得见过程**：每一步动作（写哪个文件、编译结果、点了哪个按钮）都有一行日志流出来，不用猜它卡没卡。

### 目前的边界

生成的是标准 Android SDK 代码（Java 8），适合工具类、游戏类、信息类应用。**还不支持**：Kotlin/Compose、完整第三方依赖（Gradle/Maven 库）、Android XML 资源打包。要复杂依赖的应用，模型会尽量用系统 API 自实现，但不保证等价。

---

## ⚙️ 工作原理

```text
你的描述（自然语言）
   │  system_prompt.txt（只教标准 SDK、Java 8、显式组件）
   ▼
大模型（OpenAI 兼容接口，主模型 + 备用模型）
   │  流式输出 <java_code> 代码块
   ▼
CodeSanitizer ── 安全检查（显式组件、网络策略……）
   │
   ▼
CodeCompiler  ── ECJ 编译（补 import、把广播接收器路由到占坑组件）
   │
   ▼
DexTransformer ── D8 转 classes.dex
   │
   ▼
PluginLoader ── DexClassLoader + SandboxClassLoader 加载
   │
   ▼
ActivityInjector ── 界面注入宿主
```

三个关键设计：

- **沙箱**：插件代码跑在 `SandboxClassLoader` 下，碰不到宿主的关键类；
- **显式组件**：闹钟、通知这类系统回调必须用显式命名的组件，编译期自动适配到宿主预声明的占坑组件——动态编译的类做不了的事，在底下悄悄接住；
- **持久化**：当前应用和存档在进程被杀后仍然存活（SharedPreferences + 磁盘 dex），断点续跑不丢活。

---

## 🗂️ 项目结构

```text
app/src/main/java/com/meta/app/
├── MainActivity.java        # 别名入口：兼容模型默认的 "MainActivity" 跳转
├── ui/HostActivity.java     # 宿主界面：输入框、日志区、应用容器
├── ui/SettingsActivity.java # 大模型地址 / Key / 模型配置
├── core/                    # MetaEngine 流水线、CodeCompiler、DexTransformer、
│                            # PluginLoader、CodeSanitizer、PluginRuntimeGuard、
│                            # RuntimeCompat、PluginArchive、LLMClient
├── bridge/                  # 交给生成应用的 ApiBridge / IPC 工具
├── sandbox/                 # SandboxClassLoader
└── stub/                    # 预声明的 Activity/Service/Receiver/Provider 占坑
app/src/main/assets/         # system_prompt.txt、ecj.dex.jar、d8.dex.jar、android.jar
```

---

## 🚀 开发者：构建与测试

前置：JDK 17+、Android SDK（compileSdk 35）、Android 设备/模拟器（minSdk 28）。

```bash
cp local.properties.example local.properties   # 填 sdk.dir 和你的 LLM API Key
./gradlew assembleDebug installDebug
```

> 配置读取链路：`local.properties → BuildConfig → LlmConfig`。`local.properties` 已被 git 忽略，真实密钥永不入库。

测试分两级：

```bash
# 单元测试（纯 JVM：sanitizer、编译管线、存档逻辑、缓存）
./gradlew testDebugUnitTest

# UIAutomator 仪器测试（真机跑完整生成闭环，需要联网）
./gradlew connectedDebugAndroidTest

# 确定性平台回归（本地模拟模型，不需要 API Key，不操作用户项目）
./gradlew connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.meta.app.AgentPlatformTest,com.meta.app.AgentEngineDeviceTest'
```

仪器测试位于 `app/src/androidTest/`，端到端覆盖：生成、反馈修正、存档"库"语义（切换/删除当前应用）、任务断点续跑、运行时权限（含系统弹窗）、生成缓存。

---

## 📄 许可证

[MIT](LICENSE) © MyTyrannosaurusBuddy

---

*English docs: [README.md](README.md).*
