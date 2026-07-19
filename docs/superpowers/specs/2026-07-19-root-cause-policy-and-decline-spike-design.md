# 根因策略目录与渠道拒绝尖峰 — 设计文档

日期：2026-07-19
状态：已实现（覆盖 R4 + R5a）
分支：`feat/pay-sre-observability`

## 1. 背景

R4 之前，`ConclusionValidator` 与 `StubInvestigationModel` 都把"哪种根因 + 哪个 Runbook + 是否需要人工复核"硬编码进同一段 if/else。R5 起每次新增故障家族都要改两份主体代码，与"故障库目录化"的方向冲突。

R5a 是这条路径的第一个新家族：渠道拒绝尖峰（DECLINE_ALL），运营上是观察类信号而非可执行 Runbook。两种新形态迫使策略目录必须支持：

1. 多个策略并存，每个策略独立给出"推荐 Runbook 集合 + 强制人工复核 + 证据充分性规则"。
2. 某些根因根本不能推荐 Runbook（advisory-only），且 ActionGuard 要在策略之前就拒绝执行。

## 2. 设计

### 2.1 `RootCausePolicy` 接口

```java
public interface RootCausePolicy {
    RootCauseCode rootCause();
    Set<String> allowedRunbooks();  // 空集 = advisory-only
    boolean requiresHumanReview();
    void validateConclusion(IncidentContext ctx,
                            InvestigationConclusion conclusion,
                            List<Evidence> referenced);
}
```

- `allowedRunbooks()` 返回空集时，结论 `recommendedRunbook` 必须为空串（或 null），否则策略自身 `validateConclusion` 抛 `InvalidConclusionException`。
- 通用结构性校验（incident 匹配、confidence ∈ [0,1]、受影响笔数 ≥ 0、Evidence 唯一性、影响金额匹配）保留在 `ConclusionValidator` 主体里。
- 证据充分性规则（INCIDENT_IMPACT 必选、高置信度下需要 metric + 2 类交易证据）由各策略在 `validateConclusion` 中按需实现。

### 2.2 `RootCausePolicyCatalog`

注册表：

```java
public static RootCausePolicyCatalog defaults() {
    return new RootCausePolicyCatalog(Map.of(
        CHANNEL_TIMEOUT_RESPONSE_LOST, new ChannelTimeoutResponseLostPolicy(),
        CHANNEL_DECLINE_SPIKE,        new ChannelDeclineSpikePolicy(),
        CHANNEL_CODE_MAPPING_ERROR,   new ChannelCodeMappingErrorPolicy()));
}
```

- `forRootCause(RootCauseCode)` 未命中抛 `InvalidConclusionException("unsupported root cause: …")`，绝不静默放行。
- `describe()` 输出确定性格式（按 rootCause 字典序），被 MiniMax 系统提示引用；新增策略自动出现在提示中。
- Builder 强制至少注册一条策略，构造期间拒绝空集合。

### 2.3 Advisory-only Runbook 拒绝路径

```
IncidentController.Proposal
  → ActionGuard.authorizeProposal
      ├─ 检查 incident / conclusion 存在性
      ├─ 检查 status == MITIGATION_PROPOSED
      ├─ 检查 conclusion.recommendedRunbook 为空 → ADVISORY_NO_RUNBOOK  ← 新增，放最前
      ├─ 检查 runbook 在全局允许列表 → RUNBOOK_NOT_ALLOWED
      ├─ 检查 runbook == conclusion.recommendedRunbook → RUNBOOK_MISMATCH
      └─ …
```

ADVISORY_NO_RUNBOOK 顺序在全局允许列表之前：任何人哪怕提供合法 Runbook 给 advisory 结论，依然得到最准确的拒绝码。

### 2.4 关单状态转移

`InvestigationOrchestrator` 在 `conclude` 后：

```java
if (validated.recommendedRunbook() == null || validated.recommendedRunbook().isEmpty()) {
    incident.markMitigated(now);  // advisory：直接 MITIGATED
} else {
    incident.markMitigationProposed(now);  // 走 Runbook 路径
}
```

这样 advisory 场景走 `POST /api/incidents/{id}/resolution` 关单，与受控处置走 Runbook 路径完全分离。

### 2.5 DECLINE_ALL 渠道故障

`FaultType` 加 `DECLINE_ALL`，`ChannelSimulationService` 用统一的 `outcomeFor(FaultType)` 助手返回 `(ChannelResult, channelCode)`，避免在原 if/else 里加分支：

```java
private ChannelOutcome outcomeFor(FaultType fault) {
    return switch (fault) {
        case TIMEOUT_BUT_SUCCESS -> new ChannelOutcome(SUCCESS, "00");
        case TIMEOUT_BUT_FAILED  -> new ChannelOutcome(FAILED,  "51");
        case DECLINE_ALL         -> new ChannelOutcome(FAILED,  "05");
        case NONE                -> new ChannelOutcome(SUCCESS, "00");
    };
}
```

`channel-simulator` 现有测试已覆盖三种路径。

### 2.6 失败率告警与 Incident 聚合窗口的协调

新增 `ChannelDeclineRateHigh` 告警：

```yaml
expr: |
  sum by (service,channel) (rate(payment_attempt_outcome_total{status="FAILED"}[1m]))
    /
  clamp_min(sum by (service,channel) (rate(payment_attempt_outcome_total[1m])), 0.001)
    > 0.20
for: 15s
```

- `for: 15s` 与现有 `PaymentUnknownHigh`（for: 5s）、`ChannelTimeoutRateHigh`（for: 15s）保持一致粒度。
- 窗口 1 分钟短于 Incident 5 分钟聚合窗口，避免单笔失败率尖峰反复触发聚合；同窗口下 R5a 的 `fault-scenarios/channel-decline-spike-v1.yaml`（PT2M 时长，1.00 概率，5 笔支付）能稳定触发该告警。
- `alertname: ChannelDeclineRateHigh` 与现有 `PaymentUnknownHigh` 共用 `service: payment-service` + `channel` 维度，AlertWebhookController 走原聚合路径，Incident 标识符形如 `payment-service:ChannelDeclineRateHigh:CHANNEL_A`。

## 3. 回放顺序约束

`FaultScenarioE2ETest` 是参数化的，按 `ValueSource` 顺序枚举目录中所有 YAML。R5a 引入 advisory 路径后：

- 受控处置场景（timeout-but-*）走 `remediateAndResolve`：四眼审批 → Runbook 执行 → 评分 → 关单。
- advisory 场景（decline-spike、code-mapping-error）走 `resolveAsAdvisory`：runbook proposal 返回 409/ADVISORY_NO_RUNBOOK → 直接关单。

两种路径互斥，由 `groundTruth.expected().advisory()` 分支。**顺序约束**：advisory 场景必须先关单再让下一个场景复用同一渠道；否则后续场景的 Incident 会与上一场景聚合。当前 `resolveAsAdvisory` 在每场景结束后调用 `POST /api/incidents/{id}/resolution`，满足约束。

## 4. 验收

- 现有 R1 两个场景回归不变（受控处置路径不变）。
- R5a `channel-decline-spike-v1` 在 compose e2e 中：`POST /api/incidents/{id}/runbook-executions` → 409 `ADVISORY_NO_RUNBOOK`；`POST /api/incidents/{id}/resolution` → 200，status = `RESOLVED`。
- 单元层面：`ChannelDeclineSpikePolicyTest` + `RootCausePolicyCatalogTest`（含 `describe()` 断言）+ `ActionGuardTest.refusesRunbookProposalForAdvisoryConclusions`。
- 工具 schema：`MiniMaxInvestigationToolCatalogTest.catalogDefaultsEnumerateEveryRegisteredRootCause` 校验 `rootCause` enum 同时含 `CHANNEL_DECLINE_SPIKE` 与 `CHANNEL_TIMEOUT_RESPONSE_LOST`。