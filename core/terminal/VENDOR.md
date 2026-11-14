# 第三方代码：Termux terminal-emulator

- 来源：https://github.com/termux/termux-app `terminal-emulator/`，提交 `fd2ab4fc7209eec3348aff738689dc0a2a10d14a`（2026-04-09，加入 Sixel/位图支持之前的版本）
- 许可证：Apache-2.0（termux-app 的 LICENSE.md 将 `terminal-emulator`、`terminal-view` 列为 Apache-2.0 例外；原始代码来自 jackpal/Android-Terminal-Emulator）
- 保留包名 `com.termux.terminal`（JNI 函数名依赖它）

## 修改（Apache-2.0 第 4 条要求注明）
- 去掉 Android 依赖：`KeyHandler` 改用 `KeyCodes`（新增，数值同 android.view.KeyEvent）；`TerminalColors` 用位运算取 RGB；
  `TerminalEmulator` 用 `java.util.Base64`；`Logger` 无客户端时输出到 stderr
- `JNI`、`ByteQueue` 改为 public；`JNI` 加载失败时回退到 classpath 中的 `natives/<os>-<arch>/libtermux.so`（桌面开发版）
- `TerminalSessionClient` 精简为光标样式 + 日志回调
- 未带入 `TerminalSession`、`AndroidUtils`（改由 `io.github.zenthxsin.rustwright.core.terminal.PtySession` 实现）
- JNI 源码 `termux.c` 放在 `platform/android/native/termux/`（库名 `libtermux.so`）
