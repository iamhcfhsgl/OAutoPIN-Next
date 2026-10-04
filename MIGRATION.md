# OAutoPIN → libxposed 迁移说明

## 来源、修改与免责声明

- **本项目不是原创**，而是从 [achyuki/OAutoPIN](https://github.com/achyuki/OAutoPIN) 修改升级而来，
  上游基线提交 `275367d`（上游 `main`）。
- **修改原因：原作者并未更新。** 上游模块仍使用 legacy Xposed API，并依赖
  `XSharedPreferences` + `MODE_WORLD_READABLE` 的跨进程读写方案；该方案在 LSPosed 中已被标记为
  废弃、计划于 2.3.0 移除（模块列表页会显示废弃警告），`MODE_WORLD_READABLE` 本身也早已被
  Android 废弃。因此在**不改变原有功能**的前提下做了适配性升级。
- **本项目的修改工作由 AI 大肥鱼老师完成。**
- 除上述适配性修改外，目标应用（ColorOS 的 SystemUI）、hook 逻辑与使用方式均沿用上游；
  上游的代码、名称等权利归原作者所有，本项目保留上游的 [MIT License](LICENSE)。
- 本项目为**非官方修改版**，与原作者 **无隶属或授权关系**，沿用上游项目名与包名仅为保持兼容与
  可追溯，不代表任何官方背书。
- **仅供学习与技术研究使用，禁止商业用途**；修改系统行为有风险，请自行备份，作者不对任何损失负责。
- **如有侵权请联系删除**：请提交 [Issue](https://github.com/iamhcfhsgl/OAutoPIN-Next/issues)，
  我们会立即删除相关内容并停止分发；若原作者希望停止分发，请告知，我们会立即配合。

## 迁移内容

基线：`achyuki/OAutoPIN@275367d`（上游 `main`）。

本次改动把模块从 **legacy Xposed API（`de.robv.android.xposed:api:82`）** 迁移到
**libxposed 公共 API 102**，并彻底移除 `XSharedPreferences` / `MODE_WORLD_READABLE`，
以消除 LSPosed 模块页针对新式 XSharedPreferences 的废弃警告（该机制计划于 2.3.0 移除）。

### 版本历史

| 版本 | 内容 |
| --- | --- |
| `16.1.0`（`versionCode 2`） | 首个 libxposed 版本：迁移到 API 102、改用 RemotePreferences、支持热重载 |
| `16.2.0`（`versionCode 3`） | 包名由 `io.github.achyuki.oautopin` 改为 `io.github.iamhcfhsgl.oautopin` |

包名变更后，`applicationId` 与签名都与 `16.1.0` 不同，**属于全新安装**：请先卸载旧版
（会连同旧版遗留的 others 可读 `shared_prefs/pin.xml` 一并清除），再安装新版并重新保存 PIN。

## 为什么必须换掉 XSharedPreferences

原实现把 PIN 写在 others 可读的 preference 文件里，再用 `XSharedPreferences` 跨进程读回来：

- `MainActivity`：`getSharedPreferences("pin", MODE_WORLD_READABLE)` 写入；
- `Hook`：`new XSharedPreferences("io.github.achyuki.oautopin", "pin")` 读取。

LSPosed 里与这两个 key 直接相关的判定在 `XSharedPreferences` 的构造器：

```java
isModule = metaData.containsKey("xposedminversion");
xposedsharedprefs = metaData.containsKey("xposedsharedprefs");   // 只看 key 是否存在
newModule = isModule && (xposedminversion > 92 || xposedsharedprefs);
if (newModule) mFile = new File(serviceClient.getPrefsPath(packageName), prefFileName + ".xml");
else           mFile = new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/" + prefFileName + ".xml");
```

也就是说，「`xposedminversion = 82` + 删除 `xposedsharedprefs`」这条绕法之所以有效，是因为
`newModule` 变为 false，强制回到旧式直读路径。这条路的代价是必须一直维持一个 others 可读的
XML，而 `MODE_WORLD_READABLE` 本身早已被 Android 废弃。

因此这里换掉整条通路：PIN 存进框架自有的 **RemotePreferences**，由框架负责跨进程分发，
模块不再产生任何可被其它应用读取的 preference 文件。

> 说明：LSPosed master 的模块页警告分支（`ModulesFragment`）里没有检查
> `xposedsharedprefs`/legacy/others 可读 XML 的代码，因此该警告的确切实现未能在本机确证。
> 但迁移后两个条件都不再成立 —— 清单里没有 `xposedminversion`，代码里也没有
> `MODE_WORLD_READABLE` 与 `XSharedPreferences`（grep 仅命中注释）—— 所以无论判定细节如何，
> 都不会再命中。

## 改动清单

### 构建

| 文件 | 变化 |
| --- | --- |
| `gradle/libs.versions.toml` | 新增 `libxposed = "102.0.0"`，拆出 `libxposed-api`（compileOnly）与 `libxposed-service`（implementation） |
| `app/build.gradle.kts` | 去掉 `de.robv.android.xposed:api`，改用 `io.github.libxposed:api` + `io.github.libxposed:service`；`compileSdk` 由 36 提到 **37**；包名改为 `io.github.iamhcfhsgl.oautopin` |
| `app/proguard-rules.pro` | keep 规则改为保护**新的**入口类（见下） |
| `settings.gradle.kts` | 移除只服务于 legacy API 的 `https://api.xposed.info/` 仓库 |
| `app/src/main/kotlin/io/github/iamhcfhsgl/oautopin/**` | 源码包由 `io.github.achyuki.oautopin` 迁移到 `io.github.iamhcfhsgl.oautopin` |
| `META-INF/xposed/java_init.list` | 入口类名同步改为 `io.github.iamhcfhsgl.oautopin.Hook` |

- `api` 必须是 `compileOnly`：hooked 进程里由框架提供。
- `service` 必须是 `implementation`：模块自身的设置界面要通过 `XposedServiceHelper`
  绑定框架服务才能写入 RemotePreferences，且该构件的清单会合并出框架自有的 `XposedProvider`；
  它的 POM 以 runtime scope 带出 `io.github.libxposed:interface`，会一并打包。
- `compileSdk = 37`：`io.github.libxposed:service:102.0.0` 发布了 `minCompileSdk 37`，
  用 36 会被 AGP 直接拒绝。
- 删掉 `api.xposed.info` 无风险：`io.github.libxposed` 的构件都在 Maven Central，
  且代码已不引用任何 `de.robv.android.xposed` 类。

### 模块声明：旧式 → 新式

删除 `app/src/main/assets/xposed_init`，改用 libxposed 新式元数据：

```
app/src/main/resources/META-INF/xposed/java_init.list   -> io.github.iamhcfhsgl.oautopin.Hook
app/src/main/resources/META-INF/xposed/module.prop      -> minApiVersion=102 / targetApiVersion=102 / staticScope=true / autoHotReload=true
app/src/main/resources/META-INF/xposed/scope.list       -> com.android.systemui
```

`minApiVersion` 取 102：`setId`（hook id 原子替换）、`HookHandle.replaceHook` 与
`onHotReloaded` 都是 API 102 引入的。
`autoHotReload=true`：模块 APK 更新后由框架自动触发重载，`onHotReloaded` 才会被调用。

`AndroidManifest.xml` 中的 legacy meta-data 全部删除：
`xposedmodule`、`xposeddescription`、`xposedminversion`、`xposedscope`。

`xposedminversion` 是关键：LSPosed 判断模块是否 legacy 就看清单里有没有这个 key
（`info.metaData.containsKey("xposedminversion")`），而新式 APK 由 `java_init.list` 是否存在决定。
两者同时存在时以新式为准，但既然已迁移就全部删掉，避免歧义。

### 代码

| 文件 | 说明 |
| --- | --- |
| `Hook.kt` | 入口类由 `IXposedHookLoadPackage` 改为继承 `XposedModule`；`XposedBridge`/`XposedHelpers` 全部替换为公共 API（`hook(...).intercept { chain -> ... }` + 标准反射，`module.log(...)`） |
| `RemotePrefs.kt` | 新增。hooked 进程侧只读访问，经 `module.getRemotePreferences("pin")` |
| `PinStore.kt` | 新增。模块进程侧写入，经 `XposedServiceHelper` + `XposedService.getRemotePreferences("pin")` |
| `MainActivity.kt` | 用 `PinStore` 读写 PIN，去掉 `MODE_WORLD_READABLE`；所有调用移出主线程 |

### 保留不变

`gradle/wrapper/*`、`gradlew*`、`gradle.properties`、`app/src/main/res/**`、`.gitignore`、
`LICENSE`、`README.md` 均未改动（内容与 upstream 逐字节一致）。
`res/values/arrays.xml` 里的 `scope` 数组保留（新式改用 `scope.list`，该数组已无人引用，
如需可另行删除）。

## R8 / release 构建（有一个会让 release 包静默失效的坑）

入口类由框架按名反射加载（名字写在 `java_init.list` 里），而 release 开了
`isMinifyEnabled = true`，因此 `app/proguard-rules.pro` 里的 keep 规则必须让这个类
**保留原名**：

```proguard
-keep class io.github.iamhcfhsgl.oautopin.Hook { *; }
-keep class * extends io.github.libxposed.api.XposedModule { public <init>(); }
-dontwarn io.github.libxposed.annotation.**
```

这一点是实测出来的，值得写清楚：最初按参考工程写成
`-keep,allowoptimization,allowobfuscation ...` 并配 `-adaptresourcefilecontents
META-INF/xposed/java_init.list`，结果 release 包里 `java_init.list` 变成 `d0.c`，
而 dex 里也已经没有入口类（当时叫 `io.github.achyuki.oautopin.Hook`）—— 两者对不上，
**release 包会彻底失效，而 debug 包完全正常**（不混淆，所以看不出问题）。

结论：**这里不要用 `allowobfuscation`**，也不要把 `-adaptresourcefilecontents` 当作补救手段；
直接用无修饰的 `-keep`。云编译里的 “Inspect the built APK” 步骤会断言
「`java_init.list` 的内容 == 真实类名」且「该类确实存在于 dex」，防止以后回归。

## 验证

### 云端编译与发行版（已通过）

`.github/workflows/android.yml` 在 GitHub Actions 上构建 release 并断言产物；正式发行由
`.github/workflows/release.yml` 在 `v*` 标签上完成签名与发布。已发布：

- 发行版：<https://github.com/iamhcfhsgl/OAutoPIN-Next/releases/tag/v16.2.0>
  （附件 `OAutoPIN-16.2.0.apk`，575.6 KB，`versionCode 3` / `versionName 16.2.0`）
- 构建记录：<https://github.com/iamhcfhsgl/OAutoPIN-Next/actions/runs/37178436694>
- 上一个版本：<https://github.com/iamhcfhsgl/OAutoPIN-Next/releases/tag/v16.1.0>（`16.1.0`，改名前的 `io.github.achyuki.oautopin`）

对**已发布附件**的独立复核结果（本地校验脚本直接解析 APK Signing Block，不依赖 apksigner）：

| 检查项 | 结果 |
| --- | --- |
| APK Signing Block + **v2 签名方案** 存在 | ✅ |
| 签名证书可提取（v3、RSA 4096） | ✅ |
| 证书有效期 | `2026-10-04` → **`2056-09-26`**（30 年，不会因证书过期而无法安装） |
| `java_init.list` = `io.github.iamhcfhsgl.oautopin.Hook` | ✅ |
| 清单与 dex 中已无旧包名 `io.github.achyuki.oautopin` | ✅ |
| `module.prop` = `minApiVersion=102` / `targetApiVersion=102` / `staticScope=true` / `autoHotReload=true` | ✅ |
| Hook 类存在于 dex（R8 未改名） | ✅ |
| `assets/xposed_init` 不存在 | ✅ |
| dex 中无 `XSharedPreferences` 等 legacy 引用 | ✅ |
| `io.github.libxposed.service.XposedProvider` 已合并进清单与 dex | ✅ |

构建时唯一的提示是 AGP 8.13 对 `compileSdk = 37` 的「建议使用更新的 AGP」警告，不影响构建；
若想静音可在 `gradle.properties` 加 `android.suppressUnsupportedCompileSdk=37`。

### 本地编译（已通过）

本机没有 Android SDK，因此按 `io.github.libxposed:api:102.0.0` /
`service:102.0.0` 的官方 javadoc 逐字复刻了 stub，再用 kotlinc 2.2.20 编译
4 个真实源文件：**0 错误**，并用 `javap` 核对了字节码（入口类继承 `XposedModule`、
`onPackageLoaded` 用 `getDefaultClassLoader()`、`onHotReloaded` 调用了
`getOldHookHandles()` / `replaceHook()`、hook 回调返回 `Chain.proceed()` 的结果而非
`Unit`）。

### 仍需在真机确认

云编译只证明「能构建、元数据与产物正确」，运行期行为要在设备上验证：
装到设备后在 LSPosed 中启用，**确认模块页不再出现废弃警告**；打开模块设置页保存 PIN，
然后热重载模块（或重启 SystemUI），确认 SIM PIN 能自动解锁。

## 热重载

`Hook` 实现了 `onHotReloaded`。这里有个容易踩的坑：**热重载会新建一个 module generation
实例，而框架不会为它重放 `onModuleLoaded` / 包生命周期回调**。因此新实例既没有缓存的
classLoader，也不能靠"重新安装"来生效 —— 必须通过框架交还的 `param.oldHookHandles`
用 `HookHandle.replaceHook(...)` 原地替换 hook（这正是 API 102 提供该 API 的原因）。

所以流程是：清空一次性标志 → 丢弃 preferences 句柄缓存 → 重新读取 PIN →
把旧 hook 换成闭包捕获新 PIN 的新 hook。**保存新 PIN 后在 LSPosed 里对模块执行热重载即可生效，
无需重启 SystemUI**；`autoHotReload=true` 让模块 APK 更新后也自动走这条路径。

RemotePreferences 的可见性语义：注入进程通过 `getRemotePreferences` 拿到的实例是
**按 group 记忆化**的（LSPosed 用 `computeIfAbsent` 缓存，不会重新拉取），框架在模块进程
写入时通过回调下发增量。因此真正让新值生效的是"重新读取 + 替换 hook 闭包"，而不是指望
旧句柄自动变新。不要在静态字段里长期缓存读到的 PIN。

## 顺带修掉的老问题

原实现的 `autoTried` 是**进程级一次性标志，且成功/失败后都不复位**。SystemUI 是长生命周期
进程，第二次弹出 PIN 界面（重插 SIM、切换飞行模式）时该标志仍为 true，于是**不再自动填充，
功能静默失效**。现在改为按 controller 实例记录（`WeakHashMap` 支撑的并发 set），
每个新的 PIN 界面都会被服务一次。

## 签名与发行版

上一版流水线只产出 **unsigned** 包（`app-release-unsigned.apk`），无法直接安装；
现在签名已完整接入构建，产物开箱可装。

### 签名方式

签名材料**不进入仓库**（`signing/` 已加入 `.gitignore`），由流水线在构建时生成：
`.github/workflows/release.yml` 与 `android.yml` 都会用 `keytool` 生成
`signing/release.jks`（RSA 4096、PKCS12、有效期 30 年），并写出同目录的
`signing/signing.properties`；`app/build.gradle.kts` 的 `signingConfigs.release`
读取该文件，因此 `:app:assembleRelease` 直接产出**已签名**的 `app-release.apk`。

两点刻意为之：

- **不用 `~/.android/debug.keystore`**：它的证书一年一换，用过期证书签出的 APK 装不上。
  自建密钥固定 30 年有效期，并在流水线里打印 `Valid from` 便于核对。
- **口令就写在公开的工作流里**，这是有意公开的：本项目不分发私钥，模块通过 LSPosed（root）
  安装而非应用商店，因此这把密钥的性质是「稳定、公开、可复现的构建身份」，不是机密。
  **请勿据此判断来源可信度**，也不要把它当成开发者身份证明。若需要私有签名，
  把 `signing.properties` 换成你自己的密钥并把 `signing/` 保留在本地即可。

### 发行版流水线

`.github/workflows/release.yml` 在推送 `v*` 标签（或手动指定 tag）时触发，步骤为：
解析版本 → 建签名密钥 → `assembleRelease` → `apksigner verify --print-certs` 校验签名 →
校验 `java_init.list` 与 dex 中的入口类 → 生成发行说明 → 以
`OAutoPIN-<version>.apk` 为附件发布 GitHub Release。

`.github/workflows/android.yml` 则只在每次推送时做 release 构建与同样的一组断言，
**不再上传 debug 产物**（debug 仅用于本地调试，不作为发行物）。

## 在 Android Studio 里继续开发

1. 安装 SDK Platform 37（`compileSdk = 37` 由 `service:102.0.0` 的 `minCompileSdk` 决定），
   然后 Sync 一次：会从 Maven Central 拉取 `io.github.libxposed:api:102.0.0`、
   `service:102.0.0` 与传递依赖 `interface:102.0.0`；
2. `./gradlew :app:assembleDebug` / `:app:assembleRelease`；
3. 也可以直接用仓库里的 `.github/workflows/android.yml` 在 Actions 上构建，无需本地环境。

## 已知注意点

- **包名已改为 `io.github.iamhcfhsgl.oautopin`**（上游为 `io.github.achyuki.oautopin`）。
  由于包名与签名都变了，这属于**全新安装**：旧版必须先卸载，`16.2.0` 无法覆盖升级。
  卸载会一并清除旧版遗留的 `/data/data/io.github.achyuki.oautopin/shared_prefs/pin.xml`。
- RemotePreferences 的组名随包名改为 `"oautopin"`（键名仍为 `"pin"`）。因为是新安装，
  没有需要迁移的旧数据；也刻意没做「读旧组名」的兼容回退 —— 否则用户保存新 PIN 后，
  旧值仍会在下次读取时被回退命中。请在设置页重新保存一次 PIN。

- 若框架未提供 remote 能力（`PROP_CAP_REMOTE` 未置位），代码会明确记录日志并放弃读取，
  不会静默失败；设置页也会显示具体原因。
- 模块只对 `com.android.systemui` 生效；若 SystemUI 存在多进程，只有能加载到
  `OplusKeyguardSimInputViewController` 的那个进程会装上 hook。
