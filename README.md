# Unlock No Vib

[English](README.md) | [简体中文](README.zh-CN.md)

A Vector / LSPosed module that disables the lock-screen **unlock success / unlock failure** haptic feedback. It works regardless of the ringer mode (ring / silent / vibrate / do-not-disturb) and persists across reboots.

- Tested device: Redmi Note 8 Pro (`begonia`)
- Tested ROM: Project Infinity X, Android 16, `BP4A.251205.006`
- Root stack: MagiskSU + Vector (`org.matrix.vector`, LSPosed-compatible)
- Module package: `dev.n8p.unlockvibfix`

## How it works

The feedback is produced by three independent code paths. None of them is exposed through `Settings` or `DeviceConfig`, so every one is suppressed at its source:

| # | Process | Hook target | Behavior | Available since |
| --- | --- | --- | --- | --- |
| 1 | `com.android.systemui` | `com.google.android.msdl.domain.MSDLPlayerImpl#playToken` | Drops only `MSDLToken.UNLOCK` and `MSDLToken.FAILURE`; all other tokens (key presses, drag indicators, …) are kept | Android 16 |
| 2 | `com.android.systemui` | `android.view.View#performHapticFeedback` | Drops the private constants `10004` / `10005` used by `KeyguardRootViewBinder` for success / error | Android 15 |
| 3 | `system_server` | `com.android.server.biometrics.sensors.AcquisitionClient#vibrateSuccess` | Returns immediately: no CLICK after a successful face / fingerprint authentication | Android 12 |
| 4 | `system_server` | `com.android.server.biometrics.sensors.AcquisitionClient#vibrateError` | Returns immediately: no CLICK after a failed authentication. Removed upstream in Android 14, where it is skipped automatically | Android 12–13 |

Every hook is installed independently. A missing class is handled by `findClassIfExists` and logged as `skipped`; a missing method is detected from the empty result of `hookAllMethods` and also logged as `skipped`. One failing hook never prevents the others from being applied. The two SystemUI hooks additionally live in separate `try/catch` blocks, so the missing MSDL class on Android 15 cannot skip the `View` hook.

Notes:

- Path 1 uses `VibrationAttributes.USAGE_HARDWARE_FEEDBACK`. It is not affected by `mode_ringer` / `zen_mode`, which is exactly why the original vibration ignored the ringer mode.
- Path 2 is gated by `Settings.System.fp_success_vibrate` / `fp_error_vibrate`, but the in-code default is `1` (enabled), so deleting the settings re-enables it. The module therefore intercepts `10004` / `10005` as well.
- Path 3 / 4 use vibration reasons such as `FaceAuthenticationClient::success` / `FingerprintDetectClient::success`, controlled by the `mShouldVibrate` constructor flag.

## Compatibility

Based on AOSP upstream sources (`platform/frameworks/libs/systemui/msdllib`, `HapticFeedbackConstants` and `biometrics/sensors` in `platform/frameworks/base`) and on-device testing:

| Android version | Hook 1 MSDL | Hook 2 10004/10005 | Hook 3 vibrateSuccess | Hook 4 vibrateError | Status |
| --- | --- | --- | --- | --- | --- |
| 16 (AOSP-based, incl. this PI X build) | available | available | available | method absent (skipped) | **verified on device** |
| 15 (AOSP-based) | class absent (skipped) | available | available | method absent (skipped) | inferred |
| 14 (AOSP-based) | class absent (skipped) | constants absent, hook installs but never matches | available | method absent (skipped) | partially inferred |
| 12–13 (AOSP-based) | class absent (skipped) | constants absent, hook installs but never matches | available | available | partially inferred |
| ≤ 11 | class absent (skipped) | — | package absent (skipped) | package absent (skipped) | not applicable |

Additional details:

- The MSDL library and the SystemUI-side `BouncerHapticPlayer` only exist on Android 16 (main); `android-15.0.0_r1` does not have this path yet.
- `10004` / `10005` are defined in `android.view.HapticFeedbackConstants` (`BIOMETRIC_CONFIRM` / `BIOMETRIC_REJECT`, `@hide`) and were introduced in Android 15. The source explicitly marks them as private constants that may be renumbered. AOSP-based ROMs such as LineageOS and crDroid reuse the same values; OEM ROMs are not guaranteed.
- `AcquisitionClient` was introduced in Android 12 and `vibrateSuccess()` has kept a stable signature from 12 through 16. `vibrateError()` exists only on 12/13 and was removed in 14. Android 12 uses `AudioAttributes` while 14+ uses `HARDWARE_FEEDBACK`, which does not affect the hook.
- "Inferred" means the behavior was checked against source code only, without running the module on that Android version. OEM ROMs (MIUI/HyperOS, One UI, …) may implement their own vibration paths and are out of scope.

## Build

Requirements: JDK and the Android SDK (build-tools 36.0.0, platforms/android-36). The SDK path is defined at the top of `build.sh` (`/home/v/Android/Sdk` by default); adjust it if needed.

```bash
./build.sh
# output: out/unlockvibfix.apk
```

`build.sh` resolves all paths from its own directory (`ROOT=$(cd "$(dirname "$0")" && pwd)`), so it can be built from any working directory without editing relative paths.

## Install and enable

```bash
adb install -r out/unlockvibfix.apk

# Enable the module and set its scope ("system" is the scope name of system_server)
su -c '/data/adb/lspd/cli modules enable dev.n8p.unlockvibfix'
su -c '/data/adb/lspd/cli scope set dev.n8p.unlockvibfix com.android.systemui/0 system/0'
```

Reboot (or restart system_server / SystemUI) to apply the hooks. Then check:

```bash
su -c 'dumpsys vibrator_manager | grep -c "usage: HARDWARE_FEEDBACK"'
su -c 'grep UnlockVibFix /data/adb/lspd/log/modules_*.log'
```

On Android 16 the summary normally looks like:

```
UnlockVibFix: hooks in android: installed=1, skipped=1
UnlockVibFix: hooks in com.android.systemui: installed=4, skipped=0
```

A `skipped` entry means the corresponding method does not exist on this system (for example `AcquisitionClient#vibrateError` on Android 16). This is expected and is not a silent failure.

## Disable and uninstall

```bash
su -c '/data/adb/lspd/cli modules disable dev.n8p.unlockvibfix'   # disable; original behavior returns after reboot
adb uninstall dev.n8p.unlockvibfix                                 # uninstall
```

Leave `Settings.System.fp_success_vibrate` / `fp_error_vibrate` deleted (`null`); path 2 is already covered by the module.

## Verification

| Scenario | HARDWARE_FEEDBACK entries |
| --- | --- |
| Without the module (2026-11-08 13:04) | 3 (face-success CLICK, SystemUI failure and success tones) |
| Module installed, ring / silent / vibrate / DND rounds | 0 |
| After a full reboot (module reloaded automatically) | 0 |
| Compatibility build, rebuilt + reinstalled + reboot regression (2026-11-08 14:11–14:14) | 0 |

After reboot, fingerprint success / failure trigger `suppressed device entry haptic 10004` / `10005` and the unlock triggers `suppressed MSDL UNLOCK`; `dumpsys vibrator_manager` contains no `usage: HARDWARE_FEEDBACK` entry. Raw records are kept in [`doc/`](doc/).

## Layout

```
AndroidManifest.xml    module declaration (xposedmodule / minVersion 93)
build.sh               build script (javac + d8 + aapt2 + apksigner)
assets/xposed_init     entry class: dev.n8p.unlockvibfix.Module
src/                   module source
stubs/                 compile-time Xposed API stubs (not packaged into the APK)
doc/                   verification records (dumpsys vibrator_manager excerpts)
```

`out/` and `debug.keystore` are build output / local signing material and are git-ignored. The signing certificate matches the installed build (`SHA-256 8c7a1b39…62c84`), so the APK can be installed over the existing one.
