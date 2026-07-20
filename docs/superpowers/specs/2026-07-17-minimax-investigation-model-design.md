# MiniMax Investigation Model Design

日期：2026-07-17
状态：已实施
前置：`docs/architecture/observability-evidence-plane.md`、`docs/superpowers/specs/2026-07-16-pay-sre-lab-design.md`

## 1. 目标

把真实 LLM 引入调查环，同时不破坏基础阶段的所有安全不变量：

1. 新增一个 `InvestigationModel` 实现，通过 MiniMax 的 OpenAI 兼容 Chat Completions API 决定下一步动作。
2. `StubInvestigationModel` 保持默认，CI 与回归评测完全不变。
3. API key、Base URL、模型名全部通过环境变量注入；仓库中只保留占位符。
4. 模型出错、超时、返回非结构化内容时全部 fail-closed：转 `NEEDS_HUMAN`，绝不猜测。

**非目标**：流式输出、多轮对话状态、Prompt 缓存、多模型路由、评测基准扩展。

## 2. 方案选择

| 方案 | 说明 | 结论 |
|---|---|---|
| A. Spring AI MiniMax starter | 引入 `spring-ai-starter-model-minimax`，用 `ChatModel` 抽象 | 否决：Spring AI 想接管工具执行环，而本项目的编排器必须拥有循环（审计、fail-closed、12 次上限）；依赖重、HTTP 边界不透明 |
| B. 手写有界 RestClient 适配器（选定） | 直接实现 OpenAI 兼容协议 `POST {base}/v1/chat/completions`，复用项目既有 `HttpXxxReadClient` 惯例 | 完全掌控超时/重试/解析；`MockRestServiceServer` 可测；同时兼容国际站与国内站 Base URL |
| C. Anthropic 兼容端点 | MiniMax 也提供 Anthropic 格式 | 否决：OpenAI 格式的 tools/tool_calls 与本项目"每步一个工具调用"的决策模型最贴合，且生态资料最全 |

## 3. 协议事实（来自 MiniMax 官方文档）

- 国际站 Base URL：`https://api.minimax.io/v1`；国内站：`https://api.minimaxi.com/v1`。端点均为 OpenAI 兼容的 `/chat/completions`。
- 认证：`Authorization: Bearer <MINIMAX_API_KEY>`。
- 可用模型：`MiniMax-M2`、`MiniMax-M2.1`、`MiniMax-M2.5`、`MiniMax-M2.7`（含 highspeed 变体）、`MiniMax-M3`。默认取 `MiniMax-M2`。
- 工具调用：标准 OpenAI `tools` / `tool_choice` / `message.tool_calls`（`function.arguments` 是 JSON 字符串）。已废弃的 `function_call` 参数会被拒绝。
- 生成参数：`temperature`（默认 1.0，官方对 M2 推荐 1.0/top_p 0.95）、`max_completion_tokens`。
- 已知怪癖：出错时可能返回 HTTP 200 + 非零 `base_resp.status_code`，必须显式检查。

## 4. 架构

新增包 `io.paysre.control.investigation.minimax`，四个协作单元：

```
InvestigationOrchestrator ──> InvestigationModel（接口，不变）
                                   │
                     MiniMaxInvestigationModel      ← 决策语义层
                        │                 │
              MiniMaxChatClient(接口)  MiniMaxInvestigationTools（静态工具目录）
                        │
              HttpMiniMaxChatClient    ← 有界 HTTP 协议层
```

### 4.1 MiniMaxChatClient / HttpMiniMaxChatClient

- 接口：`MiniMaxAssistantTurn complete(String systemPrompt, String userPayload, ArrayNode tools)`。
- `MiniMaxAssistantTurn(String content, List<MiniMaxToolCall> toolCalls)`；`MiniMaxToolCall(String id, String name, JsonNode arguments)`（HTTP 层负责把 `function.arguments` 字符串解析成 JsonNode，解析失败即抛错）。
- 请求体：`model`、两条消息（system + user）、`tools`、`tool_choice:"auto"`、`temperature`、`max_completion_tokens`。
- 错误全部映射为 `MiniMaxModelException(code)`：`MINIMAX_HTTP_<status>`、`MINIMAX_UNREACHABLE`、`MINIMAX_EMPTY_RESPONSE`、`MINIMAX_BASE_RESP_<code>`、`MINIMAX_MALFORMED_TOOL_ARGUMENTS`。
- 超时由注入的 RestClient 承担（connect 2s，read 默认 PT45S，可配）。

### 4.2 MiniMaxInvestigationModel（无状态单发决策）

`decide(context)` 每次都是一个全新的单轮请求——不维护对话历史，全部状态都从 `InvestigationContext` 重建进 user message。这规避了 MiniMax 多轮 function-call 必须回传完整 assistant 消息的要求，也让编排器的超时重试天然幂等。

- **System prompt**（静态英文）：角色 = 支付 SRE 调查员；每轮必须恰好调用一个工具；把 `ConclusionValidator` 的全部规则教给模型（结论须引用 ≥3 条真实 evidenceId 且含 INCIDENT_IMPACT；高置信结论需要指标证据 + ≥2 类交易证据；影响笔数/金额/币种必须逐字复制 INCIDENT_IMPACT 证据；runbook 允许列表只有 `query-and-sync-unknown-payments`；`requiresHumanReview` 必须为 true；证据不足时用 `escalate_to_human`）。
- **User payload**（JSON）：incident（id/title/status）、seed（代表交易、渠道、时间窗）、已有 evidence（id、类型、工具、采集时间、规范化 content——单条序列化超过 16 KiB 字符时截断并标注）、既往 toolResults（工具名、成败、errorCode、evidenceIds）、`lastValidationError`。
- **决策映射**（取第一条 tool_call）：

| 模型返回 | 映射 |
|---|---|
| 6 个网关工具之一 | `CallTool(name, arguments)` 原样透传（参数校验与未知参数拒绝交给 ToolGateway，保留审计链） |
| 未知工具名 | 同样透传 —— 网关按设计拒绝并审计 `UNKNOWN_TOOL` |
| `conclude_investigation` | 组装 `InvestigationConclusion`；`incidentId` 取自 context（不信任模型）；`rootCause`/金额/币种解析失败即抛错 |
| `escalate_to_human` | `Escalate(reason)` |
| 无 tool_call / 纯文本 | 抛 `MiniMaxModelException` → 编排器 escalate（fail-closed） |

### 4.3 虚拟工具目录（MiniMaxInvestigationTools）

8 个 OpenAI 格式 function schema：6 个网关工具（参数模式与各 `ToolHandler` 输入 record 逐字段对齐，枚举取自 `MetricSignal`/`LogEvent`/`LogLevel`，时间为 ISO-8601 字符串，`step` 为 ISO-8601 Duration）+ 2 个决策工具：

- `conclude_investigation(rootCause enum, confidence 0..1, evidenceIds string[≥3], affectedPaymentCount int≥0, affectedAmount decimal-string, currency ISO4217, recommendedRunbook enum, requiresHumanReview bool)`
- `escalate_to_human(reason string)`

### 4.4 装配与配置

`ControlPlaneApplication.investigationModel` 按 `paysre.investigation.model` 切换：

- `stub`（默认）→ `StubInvestigationModel`，行为与现状完全一致。
- `minimax` → 校验 api-key 非空（缺失则启动即失败，提示填 `MINIMAX_API_KEY`），构建带 Bearer 头的有界 RestClient。
- 其它值 → 启动失败。

```yaml
paysre:
  investigation:
    model: ${INVESTIGATION_MODEL:stub}          # stub | minimax
    model-timeout: ${INVESTIGATION_MODEL_TIMEOUT:PT10S}
    minimax:
      base-url: ${MINIMAX_BASE_URL:https://api.minimax.io/v1}
      api-key: ${MINIMAX_API_KEY:}
      model: ${MINIMAX_MODEL:MiniMax-M2}
      temperature: ${MINIMAX_TEMPERATURE:1.0}
      max-completion-tokens: ${MINIMAX_MAX_COMPLETION_TOKENS:4096}
      read-timeout: ${MINIMAX_READ_TIMEOUT:PT45S}
```

`deploy/env.example` 提供占位符（`MINIMAX_API_KEY=<REPLACE_WITH_YOUR_MINIMAX_API_KEY>`）；`deploy/compose.yaml` 对 sre-control-plane 透传上述变量。启用 minimax 时应把 `INVESTIGATION_MODEL_TIMEOUT` 提到 ≥ read-timeout + 缓冲（建议 PT60S），否则编排器会先于 HTTP 超时截断。

## 5. 安全不变量（不变 + 新增）

- 模型仍然只能经由 ToolGateway 触达系统；适配器不新增任何出站能力（只连 MiniMax 端点本身）。
- 12 次调用上限、120 秒截止、连续空结果与结论校验双失败的 fail-closed 逻辑全部由编排器保持，适配器任何异常都落入既有 escalate 路径。
- API key 只存在于环境变量；日志与异常信息不回显 key 与响应正文。
- CI 不依赖外部网络：默认 stub，MiniMax 路径全部用 MockRestServiceServer 单测覆盖。

## 6. 测试计划

1. `HttpMiniMaxChatClientTest`：请求形状（路径、Bearer 头、model/tools/tool_choice）、tool_calls 解析、HTTP 4xx/5xx、空 choices、非零 base_resp、非法 arguments —— 全部映射到带 code 的异常。
2. `MiniMaxInvestigationModelTest`：网关工具透传、未知工具透传、conclude 映射（incidentId 来自 context、Money/枚举解析）、escalate 映射、无 tool_call 抛错、prompt 中携带 evidence 与 `lastValidationError`。
3. `ControlPlaneApplicationTest` 补充装配断言：`investigationModel` 直调工厂方法验证 stub/minimax/非法值/缺 key 四条路径。
4. 既有全量 `mvn clean verify` 不回归。
