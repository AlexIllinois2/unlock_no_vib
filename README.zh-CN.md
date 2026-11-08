# Unlock No Vib

[English](README.md) | [简体中文](README.zh-CN.md)

关闭 Redmi Note 8 Pro 锁屏上「解锁成功 / 解锁失败」的反馈振动。效果与情景模式（响铃 / 静音 / 振动 / 勿扰）无关，重启后持久生效。

- 实测设备：Redmi Note 8 Pro（`begonia`）
- 实测系统：Project Infinity X，Android 16，`BP4A.251205.006`
- Root 环境：MagiskSU + Vector（`org.matrix.vector`，兼容 LSPosed）
- 模块包名：`dev.n8p.unlockvibfix`

## 原理

解锁反馈振动由三条相互独立的路径产生，均无 `Settings` / `DeviceConfig` 开关，因此全部在源头拦截：

| # | 进程 | Hook 目标 | 处理方式 | 起始版本 |
| --- | --- | --- | --- | --- |
| 1 | `com.android.systemui` | `com.google.android.msdl.domain.MSDLPlayerImpl#playToken` | 仅丢弃 `MSDLToken.UNLOCK` 与 `MSDLToken.FAILURE`，其余 token（按键、拖拽指示等）保留 | Android 16 |
| 2 | `com.android.systemui` | `android.view.View#performHapticFeedback` | 丢弃私有常量 `10004` / `10005`，即 `KeyguardRootViewBinder` 的成功 / 失败触感 | Android 15 |
| 3 | `system_server` | `com.android.server.biometrics.sensors.AcquisitionClient#vibrateSuccess` | 直接返回，不播放人脸 / 指纹认证成功的 CLICK | Android 12 |
| 4 | `system_server` | `com.android.server.biometrics.sensors.AcquisitionClient#vibrateError` | 直接返回，不播放认证失败的 CLICK（Android 14 起上游已移除，自动跳过） | Android 12–13 |

每条 hook 都独立安装：类不存在时用 `findClassIfExists` 判空并记录 `skipped`，方法不存在时根据 `hookAllMethods` 返回集合为空记录 `skipped`，任一 hook 失败都不会影响其余 hook。SystemUI 的两个 hook 还各自包在独立的 `try/catch` 中，因此 Android 15 上缺失的 MSDL 类不会连带跳过 `View` hook。

说明：

- 路径 1 使用 `VibrationAttributes.USAGE_HARDWARE_FEEDBACK`，不受 `mode_ringer` / `zen_mode` 影响，这正是原始振动与情景模式无关的原因。
- 路径 2 由 `Settings.System.fp_success_vibrate` / `fp_error_vibrate` 控制，但代码内默认值为 `1`（开启），删除设置也会重新启用，因此模块额外拦截 `10004` / `10005`。
- 路径 3 / 4 的振动 reason 形如 `FaceAuthenticationClient::success` / `FingerprintDetectClient::success`，由构造参数 `mShouldVibrate` 决定。

## 兼容性

依据 AOSP 上游源码核对（`platform/frameworks/libs/systemui/msdllib`、`platform/frameworks/base` 的 `HapticFeedbackConstants` 与 `biometrics/sensors`）以及本机实测：

| Android 版本 | Hook 1 MSDL | Hook 2 10004/10005 | Hook 3 vibrateSuccess | Hook 4 vibrateError | 状态 |
| --- | --- | --- | --- | --- | --- |
| 16（AOSP 派生，含本机 PI X） | 可用 | 可用 | 可用 | 方法不存在（跳过） | **实测通过** |
| 15（AOSP 派生） | 类不存在（跳过） | 可用 | 可用 | 方法不存在（跳过） | 推断可用 |
| 14（AOSP 派生） | 类不存在（跳过） | 常量不存在，hook 照常安装但不匹配 | 可用 | 方法不存在（跳过） | 推断部分可用 |
| 12–13（AOSP 派生） | 类不存在（跳过） | 常量不存在，hook 照常安装但不匹配 | 可用 | 可用 | 推断部分可用 |
| ≤ 11 | 类不存在（跳过） | — | 包不存在（跳过） | 包不存在（跳过） | 不适用 |

补充说明：

- Hook 1 的 MSDL 库与 SystemUI 侧 `BouncerHapticPlayer` 只在 Android 16（main）存在；`android-15.0.0_r1` 尚无该路径。
- Hook 2 的 `10004` / `10005` 定义在 `android.view.HapticFeedbackConstants`（`BIOMETRIC_CONFIRM` / `BIOMETRIC_REJECT`，`@hide`），自 Android 15 引入，注释明确其为可重排的私有常量；非 PI X 的 AOSP 派生 ROM（LineageOS、crDroid 等）沿用同一 AOSP 数值，但 OEM ROM 不保证。
- Hook 3 / 4 的 `AcquisitionClient` 自 Android 12 引入，`vibrateSuccess()` 签名在 12–16 稳定；`vibrateError()` 仅存在于 12 / 13，14 起移除。Android 12 使用 `AudioAttributes`，14+ 改为 `HARDWARE_FEEDBACK`，但不影响 hook。
- 表中「推断」表示仅做过源码核对、未在对应版本实机验证；OEM ROM（MIUI / HyperOS、One UI 等）可能另有振动实现，不在保证范围内。

## 构建

依赖：JDK、Android SDK（build-tools 36.0.0、platforms/android-36）。脚本开头定义 SDK 路径（默认 `/home/v/Android/Sdk`），按需修改。

```bash
./build.sh
# 产物：out/unlockvibfix.apk
```

`build.sh` 以脚本自身所在目录为根（`ROOT=$(cd "$(dirname "$0")" && pwd)`），因此在任意工作目录下都能直接构建，无需修改相对路径。

## 安装与启用

```bash
adb install -r out/unlockvibfix.apk

# 启用模块并设置作用域（system_server 的作用域名为 system）
su -c '/data/adb/lspd/cli modules enable dev.n8p.unlockvibfix'
su -c '/data/adb/lspd/cli scope set dev.n8p.unlockvibfix com.android.systemui/0 system/0'
```

启用后需重启系统（或重启 system_server / SystemUI）使 hook 生效。验证加载情况：

```bash
su -c 'dumpsys vibrator_manager | grep -c "usage: HARDWARE_FEEDBACK"'
su -c 'grep UnlockVibFix /data/adb/lspd/log/modules_*.log'
```

Android 16 上的汇总日志形如：

```
UnlockVibFix: hooks in android: installed=1, skipped=1
UnlockVibFix: hooks in com.android.systemui: installed=4, skipped=0
```

`skipped` 表示对应方法在该系统上不存在（例如 Android 16 的 `AcquisitionClient#vibrateError`），属预期行为，不会静默失败。

## 停用与卸载

```bash
su -c '/data/adb/lspd/cli modules disable dev.n8p.unlockvibfix'   # 停用，重启后恢复原行为
adb uninstall dev.n8p.unlockvibfix                                 # 卸载
```

`Settings.System.fp_success_vibrate` / `fp_error_vibrate` 保持删除状态（值 `null`）即可；路径 2 已由模块兜底。

## 验证结论

| 场景 | HARDWARE_FEEDBACK 条目 |
| --- | --- |
| 未装模块（2026-11-08 13:04） | 3 条（人脸成功 CLICK、SystemUI 失败音、成功音） |
| 装模块后，振动 / 响铃 / 静音 / 勿扰四轮 | 0 条 |
| 完整重启后（模块自动重新加载） | 0 条 |
| 兼容版重新编译安装 + 重启后回归（2026-11-08 14:11–14:14） | 0 条 |

重启后指纹成功 / 失败分别触发 `suppressed device entry haptic 10004` / `10005`，锁屏解锁触发 `suppressed MSDL UNLOCK`，`dumpsys vibrator_manager` 中无任何 `usage: HARDWARE_FEEDBACK` 记录。原始记录见 [`doc/`](doc/)。

## 目录

```
AndroidManifest.xml    模块声明（xposedmodule / minVersion 93）
build.sh               构建脚本（javac + d8 + aapt2 + apksigner）
assets/xposed_init     入口类：dev.n8p.unlockvibfix.Module
src/                   模块源码
stubs/                 编译期 Xposed API 桩（不进 APK）
doc/                   验证记录（dumpsys vibrator_manager 摘要）
```

`out/` 与 `debug.keystore` 为构建产物 / 本地签名材料，已被 git 忽略。签名证书与设备上已安装版本一致（`SHA-256 8c7a1b39…62c84`），可直接覆盖安装。
