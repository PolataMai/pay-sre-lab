# Fault Library Expansion Design (Phase 4 First Slice)

日期：2026-07-17
状态：已实施
前置：`docs/superpowers/specs/2026-07-17-controlled-remediation-design.md`

## 1. 目标

把"故障场景"从单个硬编码样例变成可增长的目录，并让评测覆盖处置结果而不止调查结论：

1. 新故障类型 `TIMEOUT_BUT_FAILED`：渠道同步超时丢失响应，但渠道终态是**失败**——与既有 `TIMEOUT_BUT_SUCCESS` 同属 `CHANNEL_TIMEOUT_RESPONSE_LOST` 根因，处置时 UNKNOWN 必须收敛到 FAILED 而不是 SUCCESS。这是第一个真正行使 `confirmUnknownFailure` 分支的场景。
2. 场景 Ground Truth 增加 `remediation` 段：期望的 Runbook 执行状态与支付最终状态，评分器新增处置维度。
3. e2e 纵向切片参数化为遍历故障目录，并把链路延伸到"提案 → four-eyes 审批 → 受控执行 → 支付收敛 → Incident 关单"。
4. 补齐 Incident 生命周期缺口：新增**关单（resolution）**——`MITIGATED` / `NEEDS_HUMAN` 的 Incident 由人确认关闭进入 `RESOLVED`。这也是两个场景能在同一 Compose 栈上顺序回放的前提（告警聚合窗口 5 分钟内只跳过 `RESOLVED` 的 Incident）。

**非目标**：需要新基础设施的故障（回调丢失/重复消息需要异步通道，路由异常需要多渠道路由，账务延迟需要账务域）、事故控制台、Benchmark 报告、按场景独立渠道。

## 2. 设计

### 2.1 channel-simulator

`FaultType` 增加 `TIMEOUT_BUT_FAILED`。`ChannelSimulationService.process` 按命中的故障类型决定终态与响应：

| 类型 | 渠道终态 | channelCode | 同步行为 |
|---|---|---|---|
| 无故障 | SUCCESS | `00` | 正常返回 |
| `TIMEOUT_BUT_SUCCESS` | SUCCESS | `00` | 持久化终态后抛 504 |
| `TIMEOUT_BUT_FAILED` | FAILED | `51`（余额不足语义） | 持久化终态后抛 504 |

### 2.2 故障目录与评分

- 新增 `fault-scenarios/channel-timeout-but-failed-v1.yaml`（seed 20260717）；两个场景 YAML 的 `expected` 段都新增：

```yaml
remediation:
  runbookExecutionStatus: SUCCEEDED
  finalPaymentStatus: SUCCESS   # 失败场景为 FAILED
```

- `ScenarioGroundTruth` 解析 `expected.remediation`（必填）。
- `ScenarioEvaluator` 新增 `evaluateRemediation(expected, actual)`：`RemediationScore(executionStatusCorrect, allPaymentsConverged)`；既有调查评分 API 不变。

### 2.3 Incident 关单

- `Incident.markResolved(now)`。
- `ActionGuard.authorizeResolution(incidentId, resolvedBy)`：actor 非空；Incident 存在；状态为 `MITIGATED` 或 `NEEDS_HUMAN`（其余 `INCIDENT_NOT_RESOLVABLE`）。每次尝试写 `action_audit`（action `INCIDENT_RESOLVED`）。
- `IncidentLifecycleController`：`POST /api/incidents/{id}/resolution` `{resolvedBy}` → `{incidentId, status}`。关单是人的确认动作，走与 Runbook 相同的 Guard 审计路径；调查模型无法触达。

### 2.4 e2e 纵向切片

`ChannelTimeoutButSuccessE2ETest` 更名为 `FaultScenarioE2ETest`，`@ParameterizedTest` 遍历目录中的两个场景，每个场景在调查评分后追加：

1. `POST runbook-executions`（提案人 `sre-primary`）→ 202 PENDING_APPROVAL。
2. `POST .../approval`（审批人 `sre-secondary`）→ 200，断言 `runbookExecutionStatus` 与 ground truth 一致、`synced == traffic.payments`。
3. 逐笔 `GET /api/payments/{id}`，断言状态等于 `finalPaymentStatus`；Prometheus `payment_unknown_current` 回落到 0（在摄取超时内轮询）。
4. `POST resolution`（`sre-primary`）→ 200 `RESOLVED`，为下一个场景腾出聚合键。

CI 与 README 的 `-Dtest` 引用同步更名。

## 3. 测试计划

- `ChannelSimulationServiceTest`：TIMEOUT_BUT_FAILED 的终态/编码/超时行为。
- `ScenarioEvaluatorTest`：新场景 YAML 装载、处置评分通过/失败两侧。
- `ActionGuardTest`：resolution 的允许（MITIGATED/NEEDS_HUMAN）与拒绝（DETECTED 等、匿名）。
- `IncidentLifecycleControllerTest`：端点与错误映射。
- 全量 `mvn clean verify`（本地排除 Docker 拉镜像用例）无回归；完整两场景回放以 CI Compose 结果为权威。
