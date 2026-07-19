# 渠道返回码映射层 — 设计文档

日期：2026-07-19
状态：已实现（覆盖 R5b，含 C2 修正）
分支：`feat/pay-sre-observability`

## 1. 背景

R5b 前，payment-service 对渠道返回码的处理是隐式的：

```java
if (response.result() == ChannelResult.SUCCESS) payment.markSuccess(...);
else if (response.result() == ChannelResult.FAILED) payment.markFailed(...);
```

这把映射"channel code → 业务终态"绑死在 `response.result()` 上，无法：

1. 通过 Spring 配置（进而 Nacos）调整允许集。
2. 在不重启服务的情况下回放"映射配置错误"故障。
3. 区分"渠道真实超时"与"配置错误地把某码映射成失败"。

## 2. 设计

### 2.1 映射层位置

`ChannelReturnCodeMapping` 是 `payment-service` 应用层的新组件，位于 `ChannelClient`（HTTP 出站）之后、`PaymentOrder`（状态机）之前。

```
ChannelClient.pay()  →  ChannelPaymentResponse(result, channelCode, …)
        ↓
ChannelReturnCodeMapping.map(channelCode)  →  Result(kind, channelResult)
        ↓
PaymentApplicationService / UnknownPaymentSyncService
        ↓
PaymentOrder.markSuccess / markFailed / markUnknown
```

映射层不接触 ChannelResult.TIMEOUT 本身；调用方先做 `response.result() == TIMEOUT` 的快速短路，再走映射。

### 2.2 接口契约

```java
public interface ChannelReturnCodeMapping {
    Result map(String channelCode);
    enum ResultKind { MAPPED_SUCCESS, MAPPED_FAILURE, UNMAPPED }
    record Result(ChannelResult channelResult, ResultKind kind) { … }
    final class Fixed implements ChannelReturnCodeMapping {
        public Fixed(Set<String> successCodes, Set<String> failureCodes);
        // successCodes ∩ failureCodes 必须为空，否则构造失败
    }
}
```

**关键不变量（来自监工 C2）**：

> 映射未命中一律保持/进入 UNKNOWN（reasonCode `CHANNEL_CODE_UNMAPPED`）。
> `fallback-result` 配置项已移除。映射只能"知之为知之"，不可"猜"成 FAILED。

也就是说：`map("UNKNOWN_CODE")` 不返回 `ChannelResult.FAILED`，而是 `ResultKind.UNMAPPED`。调用方翻译为：

```java
case UNMAPPED -> payment.markUnknown("CHANNEL_CODE_UNMAPPED", clock.instant());
```

`markUnknown` 让支付留在 `PaymentStatus.UNKNOWN`，状态机、Runbook、Control Plane 都按已有路径处理（UNKNOWN 聚合告警、四眼审批关单）。不存在"猜中"或"猜错"两条路径——只有"已知"与"未知"。

### 2.3 配置形态

```yaml
paysre:
  channel:
    code-mapping:
      success-codes: "00"
      failure-codes: "51,05,96"
```

- 默认值：`00` 视为成功，`51/05/96` 视为失败；其余进入 UNMAPPED。
- 在 `nacos` profile 下可通过 Nacos dataId `payment-service.yml` 的 `paysre.channel.code-mapping.*` 覆盖。
- **没有** `fallback-result` 配置项——这是 C2 的硬约束：映射永远不应"猜"成终态。

### 2.4 同步受理与 state-sync 路径一致性

两条入口（`PaymentApplicationService.accept` 与 `UnknownPaymentSyncService.sync`）调用同一个 `map(channelCode)`：

| 渠道返回 | 映射结果 | `accept` | `sync` |
|---|---|---|---|
| `result=SUCCESS, code=00` | MAPPED_SUCCESS | `markSuccess` | `confirmUnknownSuccess` |
| `result=SUCCESS, code=51` | MAPPED_FAILURE | `markFailed` | `confirmUnknownFailure` |
| `result=SUCCESS, code=E9`（新增故障） | UNMAPPED | `markUnknown("CHANNEL_CODE_UNMAPPED")` | 保持 UNKNOWN，返回 `STILL_UNKNOWN` |
| `result=TIMEOUT`（任何 code） | （不调用映射） | `markUnknown("CHANNEL_TIMEOUT")` | 保持 UNKNOWN |
| `result=FAILED, code=00`（配置错误） | MAPPED_SUCCESS | `markSuccess` | `confirmUnknownSuccess` |

最后一行是 `CHANNEL_CODE_MAPPING_ERROR` 根因的故障源：渠道实际失败但代码 00 被错配到 success，payment 会被乐观地标记为 SUCCESS，运营侧需要识别并回滚配置。

### 2.5 根因策略

`ChannelCodeMappingErrorPolicy`：

- `allowedRunbooks() == ∅`：advisory-only，没有自动修复路径。
- `requiresHumanReview() == true`：必须人工核对 Nacos 配置 diff。
- `validateConclusion` 拒绝任何非空的 `recommendedRunbook`。

策略由 R4 引入的 `RootCausePolicyCatalog.defaults()` 自动注册，控制面代码无需改动。

### 2.6 模拟器新增故障

`FaultType.NONE` 已是合法值；R5b 复用了它（场景 `channel-code-mapping-error-v1.yaml` 的 `fault.type: NONE`），不需新增 `FaultType` 枚举条目。但 channel-simulator 需要一种手段让 channel 返回 mapping 表外的代码：

- 在现有 `outcomeFor(NONE)` 上扩展：返回 `(SUCCESS, "00")`。
- 在场景 YAML 的 `expected.requiredEvidenceTypes` 中允许 `CHANNEL_FINAL_STATE` 携带 `code=00`。
- 映射层错配发生在 payment-service 侧而非 channel 侧，模拟器保持不变。

未来要演练 channel 返回 mapping 外的码，新增 `FaultType.UNKNOWN_CODE` 或在 R5c 中加场景。

### 2.7 验收

- `ChannelReturnCodeMappingTest` 覆盖三态：成功码、失败码、未映射码（不猜成 FAILED）。
- 构造校验：success 与 failure 集合重叠时抛 `IllegalArgumentException`。
- `PaymentApplicationServiceTest` 新增单测：channel 返回 code=`E9` → payment 状态为 UNKNOWN，事件 reasonCode=`CHANNEL_CODE_UNMAPPED`。
- `UnknownPaymentSyncServiceTest` 新增单测：channel 同步返回 code=`E9` → `PaymentSyncOutcome.STILL_UNKNOWN`。
- e2e：`fault-scenarios/channel-code-mapping-error-v1.yaml` 通过参数化 `FaultScenarioE2ETest` 时走 advisory 分支；`POST /api/incidents/{id}/runbook-executions` → 409 `ADVISORY_NO_RUNBOOK`；`POST /api/incidents/{id}/resolution` → 200 `RESOLVED`。

## 3. 已知边界

- 不重做 R5d 的多渠道路由——R5b 仍假设单 `CHANNEL_A`。
- 不引入动态加载机制：配置变更通过 Spring 重启或 Nacos 推送（`nacos` profile）即可。
- 不修改 MiniMax 模型逻辑——策略目录已让 MiniMax 系统提示自动获得新根因枚举。