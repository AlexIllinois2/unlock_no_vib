# Unlock Vibration Fix（模块工程）

关闭 Redmi Note 8 Pro 锁屏「解锁成功 / 解锁失败」反馈振动的 Vector / LSPosed 模块。

- 模块包名：`dev.n8p.unlockvibfix`
- 入口类：`dev.n8p.unlockvibfix.Module`（`assets/xposed_init`）
- 实测环境：Project Infinity X / Android 16 / `BP4A.251205.006`

完整原理、兼容性矩阵与验证结论见项目根目录的 [README.md](../../README.md)。

## 构建

```bash
./build.sh          # 产物：out/unlockvibfix.apk
```

脚本以自身目录为根（`ROOT=$(cd "$(dirname "$0")" && pwd)`），不依赖调用时的当前目录；SDK 路径在脚本开头，按需修改。

## 安装与启用

```bash
adb install -r out/unlockvibfix.apk
su -c '/data/adb/lspd/cli modules enable dev.n8p.unlockvibfix'
su -c '/data/adb/lspd/cli scope set dev.n8p.unlockvibfix com.android.systemui/0 system/0'
```

启用后重启系统使 hook 生效。

## 目录

```
AndroidManifest.xml    模块声明
build.sh               构建脚本（javac + d8 + aapt2 + apksigner）
assets/xposed_init     入口类名
src/                   模块源码
stubs/                 编译期 Xposed API 桩（不进 APK）
doc/                   验证记录（dumpsys vibrator_manager 摘要）
```

`out/` 与 `debug.keystore` 为构建产物 / 本地签名材料，已忽略；签名证书 SHA-256 与已安装版本一致（`8c7a1b39…62c84`），可直接覆盖安装。
