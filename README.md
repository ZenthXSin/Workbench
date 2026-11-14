# Workbench

从 Rustwright 提取的通用 Kotlin 软件底座：Arc 界面、目录项目管理、文件编辑器、终端、Codex 工作台、MCP 扩展、账号与更新适配层。

本仓库可独立构建，包含通用编辑器、图片编辑/像素工具/生图、终端与工作台。RW 规则、游戏素材、原产品服务地址、OAuth 客户端配置、签名材料、资源密钥和用户数据由产品仓库维护。`io.github.zenthxsin.rustwright`、`Rw*` 类型和 `rw.*` 构建插件名称暂时保留以兼容现有 API。

## 构建和运行

需要 JDK 21；Gradle Wrapper 首次运行会下载 Gradle 和依赖。桌面入口支持 Linux/Windows，Linux 需要图形会话与 SDL/OpenGL 支持。

```sh
./gradlew :platform:desktop:workbench:installDist
./platform/desktop/workbench/build/install/workbench/bin/workbench
```

数据目录默认为 `~/.workbench`，可通过 `-Dworkbench.home=/path/to/home` 修改。

Android 需要 Android SDK 36 和 Build Tools 35.0.0。构建独立的、用 Android 调试密钥签名的可安装 APK：

```sh
./gradlew :platform:android:workbench:assembleDebug
```

APK 位于 `platform/android/workbench/build/outputs/apk/debug/workbench-debug.apk`。应用名 Workbench，包名 `io.github.workbench.debug`，数据与原产品独立。发布签名由使用此底座的产品自行提供。

基础验证：

```sh
./gradlew verifyWorkbenchIsolation :core:runtime:test :core:workspace:test :core:account:test :core:agent:test :core:codex:test :app-shared:test :modules:linux:impl:test
```

## 服务配置

默认服务域名位于保留的 `.invalid` 域下。目录项目和编辑器可本地使用；账号登录及 AI 对话需要部署兼容服务并配置自己的 OAuth 客户端。

可通过 JVM 属性或对应环境变量配置（JVM 属性优先）：

| JVM 属性 | 环境变量 | 用途 |
|---|---|---|
| `workbench.source.url` | `WORKBENCH_SOURCE_URL` | 派生产品可访问的对应源码地址 |
| `workbench.oauth.return_uri` | `WORKBENCH_OAUTH_RETURN_URI` | 产品浏览器返回 URI，默认 workbench://login |
| `workbench.api.origin` | `WORKBENCH_API_ORIGIN` | NewAPI 主入口，HTTPS |
| `workbench.api.alternate` | `WORKBENCH_API_ALTERNATE` | 同一后端的备用入口，默认主入口 |
| `workbench.oauth.client_id` | `WORKBENCH_OAUTH_CLIENT_ID` | 自行注册的 OAuth 公共客户端 ID |
| `workbench.account.group` | `WORKBENCH_ACCOUNT_GROUP` | 可选的令牌分组，默认 workbench；不存在时使用服务端默认组 |
| `workbench.benefits.prefix` | `WORKBENCH_BENEFITS_PREFIX` | 福利模型前缀，默认 workbench-free/ |
| `workbench.mirror.origin` | `WORKBENCH_MIRROR_ORIGIN` | 自建下载镜像，默认 .invalid；安装器还有公共源回退 |
| `workbench.mirror.alternate` | `WORKBENCH_MIRROR_ALTERNATE` | 下载镜像备用入口 |
| `workbench.android.signer` | `WORKBENCH_ANDROID_SIGNER` | 可选的 Android 签名 SHA-256；默认不启用额外签名绑定 |
| `workbench.android.debug_package` | `WORKBENCH_ANDROID_DEBUG_PACKAGE` | 签名绑定启用后的调试包名，默认 io.github.workbench.debug |

OAuth 本机回调为 `http://127.0.0.1:8787/oauth/callback`，返回应用 URI 为 `workbench://login`；通用 Android 入口已注册此 URI。配置可信入口时必须使用 HTTPS；`rw.devRelay` 仅供本机测试使用。

Linux 随附的 FreeType JNI 来自上游预编译产物，较旧的 glibc 系统可执行 `tools/build-host-native.sh` 用本机 FreeType 重建，再运行桌面入口。

## 扩展

通过 `RwModule` 与扩展点贡献项目类型、文件打开器、导航入口、助手工具和环境安装片段。基础装配入口是 `BaseModules.all()`；加入领域功能时由自己的产品层装配相应模块。

## 仓库架构

- `core/`、`ui/`、`app-shared/`：通用能力、API 与实现、应用宿主。
- `plugins/image/`：通用图片能力；领域提示与工具场景通过 `PicPoints.DOMAINS` 注入。
- `products/workbench/`：独立应用装配；`BaseModules.all()` 保持无插件依赖。
- `platform/`、`arc/`、`modules/linux/`：平台适配与终端环境。
- `gradle/foundation-modules.txt`：唯一模块清单；`include-foundation.settings.gradle.kts` 可供子模块消费者复用。

Rustwright 使用 Git 子模块固定引用本仓库。使用时需 `git clone --recurse-submodules`；每个消费者决定何时更新固定提交。源码仅维护在这里。

## 许可证与出处

Copyright (c) 2026 ZenthXSin and Workbench contributors.

[Workbench 项目出处](https://github.com/ZenthXSin/Workbench)。自有代码采用 **AGPL-3.0-only**，并按第 7(b) 条保留合理作者出处说明，见 [LICENSE](LICENSE) 和 [ATTRIBUTION.md](ATTRIBUTION.md)。允许免费商用；分发派生作品须按 AGPL 提供对应源码，修改版网络服务须遵守第 13 条。应用“设置 → 关于”可查看出处、许可证与源码入口。派生产品通过 `workbench.source.url` 配置实际可访问的对应源码地址。

第三方代码、字体、图标与独立可执行程序保留各自许可证，见 [NOTICE.md](NOTICE.md)。
