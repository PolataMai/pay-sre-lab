# Controlled Remediation Design (Phase 3 First Slice)

日期：2026-07-17
状态：已实施
前置：`docs/superpowers/specs/2026-07-16-pay-sre-lab-design.md` 第 24 节 Phase 3

## 1. 目标

把调查结论推荐的 `query-and-sync-unknown-payments` Runbook 从"一个字符串"变成可执行、受控、可审计的处置闭环：

1. 结论产生后由人发起 Runbook 提案，第二个人审批（four-eyes：审批人 ≠ 提案人），审批通过即受控执行。
2. 执行内容：对 Incident 影响面中的每笔 UNKNOWN 支付，让 payment-service 主动向渠道查询终态并按状态机收敛（SUCCESS/FAILED），查询不到终态则保持 UNKNOWN。
3. Action Guard 是唯一的写路径闸门：Runbook 允许列表、Incident 状态、结论匹配、审批完备性逐项校验，所有尝试（含拒绝）全量审计。
4. 执行成功 → Incident `MITIGATED`；执行失败 → `NEEDS_HUMAN`（fail-closed）。

**非目标**：配置回滚与切流 Runbook、补偿事务、多步 Runbook 引擎、SSE 进度、审批 UI、LLM 参与任何写路径。

## 2. 关键设计决策

| 决策 | 理由 |
|---|---|
| 同步动作收在 payment-service（`POST /api/payments/{id}/state-sync`），由它自己调渠道查询并走状态机 | 业务裁决留在支付边界内；控制面只能"触发同步"，不能直接推送目标状态——控制面被攻破也无法伪造支付终态 |
| Runbook 目标支付列表来自结论引用的 `INCIDENT_IMPACT` Evidence 的 `paymentIds` | 处置范围与调查证据严格一致，不允许执行期临时圈定范围 |
| 审批即执行（approve 触发同步执行，有界超时） | MVP 最小 API 面；执行状态机仍完整记录 RUNNING/SUCCEEDED/FAILED |
| 渠道返回 TIMEOUT 或 404 → 支付保持 UNKNOWN，计入 `stillUnknown` | 与调查阶段同样的不变量：没有证据就不改状态 |
| Action Guard 审计独立表 `action_audit`，与只读工具审计分离 | 写路径审计的查询与保留策略将来会不同 |

## 3. payment-service 变更

1. `PaymentOrder.confirmUnknownFailure(channelCode, now)`：UNKNOWN → FAILED，事件源 `CHANNEL_QUERY`（与既有 `confirmUnknownSuccess` 对称）。
2. `ChannelClient` 新增 `query(paymentId)`（渠道既有 `GET /api/channel/payments/{id}`）；404 映射为 `ChannelStateMissingException`，网关超时（504 CHANNEL_TIMEOUT）映射为查询超时。
3. 新应用服务 `UnknownPaymentSyncService.sync(paymentId)`：
   - 支付不存在 → 404。
   - 状态非 UNKNOWN → 幂等 no-op，outcome `NOT_UNKNOWN`（携带当前状态）。
   - 渠道 SUCCESS → `confirmUnknownSuccess`；FAILED → `confirmUnknownFailure`；outcome `SYNCED`。
   - 渠道 TIMEOUT / 无记录 → 保持 UNKNOWN，outcome `STILL_UNKNOWN`。
   - 复用既有遥测：状态变化照常发 `PAYMENT_STATE_CHANGED` 结构化日志与指标。
4. REST：`POST /api/payments/{paymentId}/state-sync` → `{paymentId, previousStatus, currentStatus, channelResult, outcome}`。

## 4. sre-control-plane 变更

1. 迁移 `V4__runbook_execution.sql`：
   - `runbook_execution(execution_id PK, incident_id, runbook, status, requested_by, approved_by NULL, result NULL, error NULL, created_at, updated_at, version)`
   - `action_audit(audit_id PK, incident_id, execution_id NULL, action, actor, allowed, reason_code NULL, occurred_at)`
2. 领域 `RunbookExecution`：状态机 `PENDING_APPROVAL → RUNNING → SUCCEEDED | FAILED`；非法迁移抛错。
3. `ActionGuard`（唯一写路径闸门）：
   - propose 校验：Runbook 在允许列表（当前仅 `query-and-sync-unknown-payments`）；Incident 存在且 `MITIGATION_PROPOSED`；已存在通过校验的结论且 `recommendedRunbook` 匹配、`requiresHumanReview=true`；无进行中的执行。
   - approve 校验：执行存在且 `PENDING_APPROVAL`；审批人 ≠ 提案人（four-eyes）。
   - 每次校验（通过/拒绝）写一条 `action_audit`。
4. `PaymentWriteClient` / `HttpPaymentWriteClient`：调 payment-service state-sync，2s 连接 / 5s 读超时，错误映射为带 code 的异常。
5. `QueryAndSyncUnknownPaymentsRunbook`：
   - 目标 = 结论引用的 `INCIDENT_IMPACT` Evidence content 的 `paymentIds`（缺失/为空 → 执行失败）。
   - 逐笔调用 write client，聚合 `{synced, stillUnknown, alreadyFinal, failed}`。
   - 判定：任何一笔调用异常 → FAILED；否则 SUCCEEDED（`stillUnknown` 非零也算成功执行，结果如实记录，由人决定是否重跑）。
6. `RunbookExecutionService`：propose / approve（approve 通过 Guard 后置 RUNNING，在有界执行器内跑 Runbook，60 秒上限）；SUCCEEDED → `incident.markMitigated`；FAILED 或超时 → `incident.markNeedsHuman`。
7. `Incident.markMitigated(now)`：`MITIGATION_PROPOSED → MITIGATED`。
8. REST `RunbookController`：
   - `POST /api/incidents/{id}/runbook-executions` `{runbook, requestedBy}` → 202 执行单。
   - `POST /api/incidents/{id}/runbook-executions/{executionId}/approval` `{approver}` → 200 执行结果。
   - `GET /api/incidents/{id}/runbook-executions` → 列表。

## 5. 安全不变量（新增）

- 写路径与 LLM 完全隔离：Runbook 提案与审批只能来自 REST API 的人类身份字段，调查模型无法触达 ActionGuard。
- 控制面永远不直接写支付状态；它只能触发 payment-service 的自证同步。
- 每一次写路径尝试（包括被拒绝的）都有 `action_audit` 记录。
- 处置范围锁定为结论引用的影响面 Evidence，执行期不重新计算。

## 6. 测试计划

- payment-service：`PaymentOrderTest`（FAILED 分支与非法迁移）、`UnknownPaymentSyncServiceTest`（SUCCESS/FAILED/TIMEOUT/缺记录/非 UNKNOWN/不存在）、`ChannelHttpAdapterTest`（query 映射）、`PaymentControllerTest`（新端点）。
- sre-control-plane：`RunbookExecutionTest`（状态机）、`ActionGuardTest`（逐项拒绝 + 审计）、`QueryAndSyncUnknownPaymentsRunbookTest`（证据圈定、聚合、失败）、`RunbookExecutionServiceTest`（four-eyes、成功→MITIGATED、失败→NEEDS_HUMAN）、`HttpPaymentWriteClientTest`、`JdbcRunbookExecutionRepositoryTest`（H2）、`RunbookControllerTest`。
- 全量 `mvn clean verify`（本地排除 Docker 拉镜像用例）无回归。
