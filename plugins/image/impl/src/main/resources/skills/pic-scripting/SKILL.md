---
name: 脚本编写指南
description: RustPic 脚本工具（create_script 触发的 workspace TypeScript 脚本）的编写规范：导出结构、execute 返回值约定、可用运行时类与安全红线。
---

> Workbench 迁移说明：用户最新要求优先。旧工具名是历史流程参考，实际以当前已注册工具为准。视觉理解由当前 Codex 多模态模型完成；本地像素分析用 inspect_image/read_image_region，图层编辑用 create_pixel_document/observe_pixel_document/apply_pixel_actions，栅格变换用 edit_image，SVG 保存用 generate_svg。脚本与文件操作使用 Codex 原生工具，技能按 Codex 原生发现加载；不得声称存在旧 RustPic 的面板、脚本管理或审批接口。


# 脚本编写指南

当需要用 `create_script` 创建自定义的、可复用计算工具时，按本指南编写 TypeScript 脚本。脚本会被编译为 JS 后作为 Agent 工具注册，用户批准后即可调用。

## 零、脚本管理工具

除 `create_script` 外，脚本系统还提供以下工具：

- `list_scripts`：列出工作区全部脚本（名称、描述、启用状态、编译错误），创建/修改前先查看现状。
- `view_script_source`：查看已有脚本的完整源码，便于理解或复用实现。
- `set_script_enabled`：启用/禁用已有脚本（禁用后不再注册为 Agent 工具，需用户批准）。
- `delete_script`：永久删除脚本并注销其工具（需用户批准，不可恢复）。

## 一、导出结构

每个脚本是一个 `.ts` 文件，顶层导出以下声明：

```typescript
declare var name: string;          // 工具名，作脚本文件名/工具标识（ASCII、字母数字与连字符下划线为佳）
declare var description: string;   // 何时使用该工具的描述，会显示在脚本列表与工具 schema 中
declare var parameters: object;    // JSON Schema 参数对象
declare function execute(ctx: any, args: any): any; // 工具执行函数
```

- **必须导出** `name`、`description`、`parameters` 和 `execute`。
- `description` 为空时脚本无法注册为工具（会报“脚本未导出工具定义”）。
- `parameters` 必须是 `{ "type": "object", "properties": {...} }` 形态的 JSON Schema；不提供则默认空对象 schema。
- 脚本允许顶层代码直接执行，也可只导出工具定义。

## 二、execute 返回

返回值必须是一个对象，携带回给 Agent 与用户的内容：

```typescript
declare interface ScriptResult {
    toAgent: string;   // 给 Agent 的文本结果
    userReply?: string; // （可选）直接展示给用户的文本
    endTurn?: boolean;  // （可选）为 true 时结束本轮 Agent 思考
}
```

```typescript
execute = async (ctx, args) => {
    const sum = (args.a ?? 0) + (args.b ?? 0);
    return { toAgent: String(sum), userReply: `两数之和：${sum}` };
};
```

`ctx` 提供调用上下文字段：

```typescript
declare interface ScriptCtx {
    userId: string;
    conversationId: string;
}
```

## 三、可用运行时类

脚本 scope 自动注入一组 JDK 类，可直接使用，无需 import：

- 文件系统：`File`、`Path`、`Files`、`StandardCopyOption`
- 时间：`LocalDateTime`、`LocalDate`、`LocalTime`、`Instant`、`Duration`、`Period`
- 集合与基础：`ArrayList`、`HashMap`、`HashSet`、`StringBuilder`、`UUID`

用法（TS）：

```typescript
var file = new File(ctx.conversationId + ".json");
// 或 File(ctx.conversationId + ".json")
```

完整、逐类的字段/构造方法/方法签名可用技能 `invoke_skill(name="runtime-api")` 按组拉取（runtime-api 分组随脚本功能启用后在技能管理中可见、可开关）。

## 四、脚本运行能力与安全红线

- 脚本拥有文件访问与完整 Java/TypeScript 能力，**只应编写用户理解并信任的脚本**。
- 严禁存储 token、密码或密钥。
- 脚本与工具一样需要用户批准后才生效；不要让脚本偷偷执行高风险操作。
- 建议保持纯计算/可复现逻辑，把面向用户的说明放在 `userReply`，把给 Agent 的结果放在 `toAgent`。