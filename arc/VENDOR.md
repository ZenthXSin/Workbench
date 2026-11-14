# Vendored Arc

- 上游：https://github.com/Anuken/Arc
- 基线提交：`7445105cd2`（master）
- 许可证：Apache-2.0（见 `LICENSE`）

只收录 Rustwright 需要的模块：`arc-core`、`extensions/{freetype,flabel,tiled}`、`backends/backend-android`，
以及 `natives/android` 下的预编译 `libarc.so`、`libarc-freetype.so`。构建脚本改为 Kotlin DSL，源码保持上游包名 `arc.*`。

二开改动需在提交信息中标注 `arc:`，便于日后与上游比对。
