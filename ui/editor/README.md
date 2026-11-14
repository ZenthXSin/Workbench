# 通用编辑器

完整编辑界面在 `ui/editor/impl`；底座不依赖 RW、图片插件或产品装配。

| 抽象契约 | 默认实现 | 用途 |
|---|---|---|
| `core/editor/api: TextDocument` | `core/editor/impl: CodeDocument` | 文本存储、增量修改、位置换算、撤销和重做 |
| `core/editor/api: FileDraftStore` | `core/editor/impl: MemoryFileDraftStore` | 有界草稿、原子保存、外部修改冲突检测 |
| `ui/editor/api: EditorSupport` | `ui/editor/impl: BuiltinEditorSupport` | 高亮、补全、结构、字段控件、诊断、格式化、辅助操作和设计器 |
| `ui/editor/api: EditorAssistance` | `ui/editor/impl: WordEditorAssistance` | 候选列表、幽灵字、显式补全、Tab 接受和会话清理 |

通用界面保留多标签、标签分组、书签、项目文件树、结构导航与折叠、行操作、缩进、撤销/重做、复制粘贴、正则/全词/大小写搜索替换、捕获组、问题分组与跳转、字段抽屉、符号抽屉、只读、换行、缩放和输入法支持。Markdown 可预览；图片和二进制文件可作为预览标签打开。

默认语言实现提供基础代码高亮、项目内词补全、配置字段控件和重复字段检查。专用语言能力通过 `EditorPoints.SUPPORTS` 注册 `EditorContribution(id, accepts, create)`，打开文件时按扩展点顺序选择。无匹配插件时使用默认实现。

RW 实现位于 `plugins/rw/modcraft/impl/RwEditorSupport.kt`。原版知识库补全、字段类型和资源选择器、INI 检查器、原版资源、单位/建筑模板、设计器及画面同步仍由 RW 插件提供；同一套通用界面负责承载它们。`IniToolPage` 仅保留兼容入口。通用入口和 RW 入口共享插件提供的草稿服务。

API 模块不依赖实现模块；`ui/editor` 是供旧调用方迁移使用的装配模块。`verifyWorkbenchIsolation` 检查底座与业务插件隔离及编辑器 API 的实现依赖。

验证：核心与界面单元测试，以及 `:platform:desktop:workbench:editorSmokeTest`（需要显示服务；Linux 可用 Xvfb）。较旧 glibc 主机可使用导出包的 `tools/build-host-native.sh` 重建 FreeType JNI。

## 实现职责

`EditorPage` 负责页面装配、导航和状态桥接。其余实现按生命周期和数据边界组合：

| 组件 | 职责 |
|---|---|
| `core/editor/impl: ConfigurationFields` | 段落/字段定位与文本修改，不依赖界面 |
| `EditorFieldPanel` | 字段控件、候选内容、详情抽屉及延迟重建 |
| `EditorDiagnostics` / `EditorDiagnosticMarks` | 后台诊断的取消、结果有效性和文字标记 |
| `EditorDraftPersistence` | 不可变快照、草稿保留、保存和缓冲变更通知 |
| `EditorPreview` | 图片加载、纹理释放和 Markdown 预览 |
| `EditorTabs` / `EditorTabStrip` | 打开文件集合与保存后关闭 / 标签展示 |
| `TextOutline` / `GenericCodeHighlighter` / `ConfigurationDiagnostics` / `BuiltinFieldControls` | 默认语言能力的独立实现 |

插件通过 `EditorSupport.configurationFields(file)` 声明配置字段编辑能力，界面不再判断 INI 后缀。`RwEditorSupport` 将资源索引/字段控件、模板创建和设计器/画面同步分别委托给 `RwEditorFields`、`RwEditorCreation` 和 `RwEditorVisuals`。
