# 随附的第三方可执行文件（src/main/jniLibs）

以独立程序形式随 App 分发（单纯聚合），不与 Rustwright 代码链接。二进制取自 Termux 官方仓库
`packages.termux.dev/apt/termux-main`（2026-10-24）。

| 文件 | 项目 | 版本 | 许可证 | 源码 |
|---|---|---|---|---|
| `libproot.so`、`libproot-loader*.so` | PRoot（Termux 分支） | 5.1.107.100 | GPL-2.0 | https://github.com/termux/proot ，构建配方 https://github.com/termux/termux-packages/tree/master/packages/proot |
| `libtalloc.so` | talloc（Samba） | 2.5.0 | LGPL-3.0 | https://www.samba.org/ftp/talloc/ ，配方 termux-packages/packages/libtalloc |
| `libtermux.so` | Termux terminal-emulator 的 PTY JNI（`native/termux/termux.c`，本仓 `build.sh` 编译） | — | Apache-2.0 | https://github.com/termux/termux-app/tree/master/terminal-emulator |
| `libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |

对应源码归档、固定版本的完整 Termux 构建框架与配方随仓库提供，见 `sources/README.md` 与 `sources/MANIFEST.json`。完整许可证见 `licenses/`。

公开来源：https://github.com/ZenthXSin/Workbench/tree/main/platform/android/third_party/sources

talloc 的库文件采用 LGPL-3.0；其源码包和构建辅助文件另含 GPL-3.0 代码，保留原包内声明。
未对上述二进制做任何修改，仅改名为 `lib*.so` 以便 Android 打包到可执行的 nativeLibraryDir。
