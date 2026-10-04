# OAutoPIN → libxposed 迁移说明

本次改动把模块从 **legacy Xposed API（`de.robv.android.xposed:api:82`）** 迁移到
**libxposed 公共 API 102**，并彻底移除 `XSharedPreferences` / `MODE_WORLD_READABLE`，
以消除 LSPosed 模块页针对新式 XSharedPreferences 的废弃警告（该机制计划于 2.3.0 移除）。

基线：`achyuki/OAutoPIN@275367d`（upstream main）。

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
| `app/build.gradle.kts` | 去掉 `de.robv.android.xposed:api`，改用 `io.github.libxposed:api` + `io.github.libxposed:service`；`compileSdk` 由 36 提到 **37** |
| `app/proguard-rules.pro` | keep 规则改为保护**新的**入口类（见下） |
| `settings.gradle.kts` | 移除只服务于 legacy API 的 `https://api.xposed.info/` 仓库 |

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
app/src/main/resources/META-INF/xposed/java_init.list   -> io.github.achyuki.oautopin.Hook
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

## R8 / release 构建

入口类由框架按名反射加载（名字写在 `java_init.list` 里），而 release 开了
`isMinifyEnabled = true`，因此 `app/proguard-rules.pro` 里必须保住这个类名：

```proguard
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule { public <init>(); }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.annotation.**
```

原来的规则 `-keep class io.github.achyuki.oautopin.Hook { *; }` 仍然有效且更保守，
故一并保留。装成 release 包后请确认 APK 内
`META-INF/xposed/java_init.list` 的内容仍是完整的类名。

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

## 验证

本机没有 Android SDK / Gradle 缓存，**改动未经完整编译验证**（仅对 API 签名做过交叉核对）。
请在 Android Studio 中：

1. 安装 SDK Platform 37，然后 Sync 一次（会从 Maven Central 拉取
   `io.github.libxposed:api:102.0.0`、`service:102.0.0` 与传递依赖 `interface:102.0.0`）；
2. `./gradlew :app:assembleDebug`，再 `./gradlew :app:assembleRelease` 验证 R8 规则；
3. 装到设备后在 LSPosed 中启用，**确认模块页不再出现废弃警告**；
4. 打开模块设置页输入 PIN 保存，然后热重载模块（或重启 SystemUI），确认 SIM PIN 自动解锁。

## 已知注意点

- **升级后请清一次模块数据**：设备上由旧版本写入的
  `/data/data/io.github.achyuki.oautopin/shared_prefs/pin.xml` 不会被升级删除，
  它可能仍是 others 可读。清除数据或重装即可移除。
- RemotePreferences 的组名与键名保持原来的 `"pin"` / `"pin"`，便于与旧实现对照；
  但旧文件里的值不会自动迁移，需要在新版设置页重新保存一次。
- 若框架未提供 remote 能力（`PROP_CAP_REMOTE` 未置位），代码会明确记录日志并放弃读取，
  不会静默失败；设置页也会显示具体原因。
- 模块只对 `com.android.systemui` 生效；若 SystemUI 存在多进程，只有能加载到
  `OplusKeyguardSimInputViewController` 的那个进程会装上 hook。
