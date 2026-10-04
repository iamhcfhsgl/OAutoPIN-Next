# OAutoPIN
ColorOS 开机自动解锁 SIM 卡 PIN

- 先在 app 内设定 PIN
- 仅在 ColorOS16 经过测试，其余自测
- 仅适配了单 SIM 卡或双卡单锁
- 仅作为自用，无 bug 不更新

## 模块形态

本版本已从 legacy Xposed API 迁移到 **libxposed 公共 API 102**，并要求框架支持该 API：

- 目标作用域：`com.android.systemui`
- 声明方式：`META-INF/xposed/{java_init.list,module.prop,scope.list}`
- PIN 存放于框架自有的 **RemotePreferences**（不再使用 `XSharedPreferences` /
  `MODE_WORLD_READABLE`，也不再产生 others 可读的 preference 文件）
- 保存新 PIN 后可通过 LSPosed 的**热重载**生效，无需重启 SystemUI

改动原因、验证方式与构建注意点见 [MIGRATION.md](MIGRATION.md)。
云端构建流水线见 [.github/workflows/android.yml](.github/workflows/android.yml)。

> 注意：升级到本版本后，旧版本写入的
> `/data/data/io.github.achyuki.oautopin/shared_prefs/pin.xml` 不会自动迁移，
> 请在设置页重新保存一次 PIN，并清除一次模块数据以移除遗留的可读文件。
