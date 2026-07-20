# InvestigationModel SPI — Registration Contract

日期：2026-07-19
状态：R9 Phase 5 社区化
分支：`feat/pay-sre-observability`

## 1. 背景

R9 要求把"模型适配"显式化为 SPI（Service Provider Interface），让社区贡献者能
在不修改 `sre-control-plane` 主体的前提下接入新的调查模型——例如：
- 不同厂商的 OpenAI 兼容 API
- 本地 Ollama 推理
- 规则引擎
- 集成测试用 Stub

## 2. 现状（已具备 SPI 形态）

`io.paysre.control.investigation.InvestigationModel` 本身就是一个函数式接口：

```java
@FunctionalInterface
public interface InvestigationModel {
    InvestigationDecision decide(InvestigationContext context);
}
```

`InvestigationContext` 把完整状态（Incident + Seed + 已采集 Evidence + 工具结果 + 上一次校验错误）作为不可变快照传入；`InvestigationDecision` 是封闭类型（`CallTool` / `Conclude` / `Escalate`），模型只能走工具调用或下结论两条路径。

贡献者只需实现 `decide(InvesigationContext)`，把 `MiniMaxInvestigationModel` 或 `StubInvestigationModel` 当作参考实现即可。

## 3. 注册机制

`ControlPlaneApplication.investigationModel(...)` 是唯一的注册点，按 `paysre.investigation.model` 配置切换：

| 值 | 实现 |
|---|---|
| `stub` | `StubInvestigationModel(objectMapper, clock)` |
| `minimax` | `MiniMaxInvestigationModel(chatClient, objectMapper, policies)` |

新增模型（如 `ollama`）时按以下三步提交：

1. 在 `io.paysre.control.investigation` 包（或社区的 `io.paysre.contrib.investigation.*` 包）下新增 `XxxInvestigationModel implements InvestigationModel`。
2. 在 `ControlPlaneApplication.investigationModel(...)` 增 `case "xxx" -> new XxxInvestigationModel(...)`，注入它需要的依赖。
3. 在 `application.yml` 与 README 增加 `paysre.investigation.model: xxx` 的说明。

**不要**修改 `InvestigationModel`、`InvestigationContext`、`InvestigationDecision`——它们是稳定契约。模型对 `CallTool` 之后的工具结果、下一次校验错误等都有完整可见性，但**写路径与调查路径完全隔离**：调查模型调用 `conclude_investigation` 写入的结论必须先经过 `ConclusionValidator` 校验，校验通过后由 `ActionGuard` 决定 Runbook 才能执行；模型本身不接触任何写工具。

## 4. 配套扩展点

- **策略目录**：`RootCausePolicyCatalog`（R4）随 `defaults()` 暴露；新模型若要支持同一根因家族，无需额外注册。
- **工具 schema**：`MiniMaxInvestigationTools.catalog(mapper, policies)` 派生 `conclude_investigation` 的枚举；新模型如需不同 schema（纯文本对话、function calling 变体等），在自己的适配器里实现即可，控制面只读 `InvestigationDecision`。
- **工具结果**：`ToolResult` 已经是统一格式（name, success, evidenceIds, duration, errorCode），新模型按同一形状消费即可。

## 5. 不变量

- `decide` **必须**是纯函数（同样的 context 必须产生同样的 decision）。MiniMax 在
  System prompt 里硬编码"每轮注入完整状态"就是为了这个不变量。
- `decide` **不能**直接修改 payment 状态、发起 Runbook 或调用 Action Guard——
  所有写动作必须经过 `InvestigationOrchestrator` 落库后由四眼审批触发。
- 任何工具调用都必须返回 `CallTool(toolName, args)`，其中 `toolName` 在
  `ToolGateway` 允许列表内；越界调用会被网关拒绝并写入审计。

## 6. 待办

- R8 完成后在 `docs/superpowers/reports/` 加一行说明 "models: stub (default),
  minimax (config-gated)"。
- 后续 R6 控制台把模型选择暴露到运维面板；本规范不需要等 R6。
- 长期：把 InvestigationModel 注册搬到 `META-INF/services` 的 `ServiceLoader`
  上，避免主模块改 switch——R9 的 Phase 5.5 任务。