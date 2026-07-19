# 监工看板（SUPERVISION BOARD）

> 本文件由桌面端监工会话维护，用于协调 VS Code 实施会话的阶段任务。
> 实施会话：每完成一个阶段（提交并推送后），读取本文件领取下一阶段任务；
> 若「纠正意见」有未关闭项，优先处理。除本监工外请勿改写本文件（可在文末「实施会话回执」区追加留言）。
> 需求全集见 `2026-07-19-roadmap-requirements.md`（R1–R9）。

最后更新：2026-07-19 23:20（监工）

## 阶段验收记录

| 阶段 | 状态 | 备注 |
|---|---|---|
| R1 Phase 4 切片一收尾 | ✅ 已验收 | 22:38 三个提交，含 e2e 更名与文档 |
| R4 根因策略目录化 | ✅ 代码已验收 | 22:47 两个提交；MiniMax 目录派生做得好；**文档缺口见 C1** |
| R5b 返回码配置错误 | ✅ 代码已验收 | C2 修复 `295525b` 验收通过；HEAD 隔离全量回归 BUILD SUCCESS（207 测试）；遗留待决项 D1 |
| R5a 渠道拒绝尖峰 | ✅ 已验收 | 提交 `0016d73` 隔离 worktree 全量回归 BUILD SUCCESS（控制面 131 测试全绿）；23:05 监工验收 |

## 纠正意见（处理后在回执区确认）

- **C1（流程）**：✅ 已关闭（23:20 监工）——两份设计文档已补齐并提交。
- **C2（设计）**：✅ 已关闭（23:20 监工）——`295525b` 验收通过：两条路径 unmapped 均保持 UNKNOWN
  且 reasonCode `CHANNEL_CODE_UNMAPPED`，fallback 配置面清零，危险测试断言已移除，
  `recordUnmappedCode` 审计事件是超出要求的好设计。隔离全量回归绿。

## 待决项（实施会话选择后在回执区说明）

- **D1（R5a/R5b 共同缺口）**：`FaultScenarioE2ETest` 的场景列表只有两个 timeout 场景，
  `channel-decline-spike-v1` 与 `channel-code-mapping-error-v1` 未纳入 e2e 回放。二选一：
  (a) 补 advisory 场景的 e2e 分支流程（结论无 runbook → 跳过提案/审批直接关单；
      code-mapping-error 的错配注入方案需要设计，如 compose 环境变量 override）；
  (b) 在两份 spec 的「已知限制」中明确记录不纳入的原因与后续计划，并把 (a) 登记为 R8 前必须补的条目。
  监工倾向 (a)，但接受有据的 (b)。

## 当前分配：R5c 回调丢失（spec 先行，设计需监工确认后动工）

**目标**：为渠道引入异步回调通道，并以"回调丢失/重复回调"作为新故障族。

**第一步（本阶段只做这步）**：写设计文档 `specs/2026-07-19-channel-callback-design.md`，覆盖：
1. 回调通道形态：channel-simulator 主动 POST payment-service 回调端点（同 Compose 网络 HTTP 即可，不引入 MQ）；
   回调时机（渠道终态落定后异步推送）、重试策略、签名/幂等键。
2. payment-service 回调消费：幂等处理（重复回调不重复迁移状态）、与既有状态机的交互
   （PROCESSING/UNKNOWN 收到回调如何收敛；终态支付收到矛盾回调如何告警而不改状态）。
3. 新故障类型：`CALLBACK_LOST`（渠道不发回调）、`CALLBACK_DUPLICATED`（重复发）；
   与既有 TIMEOUT_BUT_* 的组合语义。
4. 新根因与策略注册、告警面、场景 Ground Truth 草案、e2e 回放方式。
5. 明确非目标（MQ、真实签名体系）。

**验收**：spec 提交后在看板回执区留言"R5c spec 待审"，监工审阅通过后再开始编码。

## 排队中

- R5d 路由异常（前置：多渠道路由设计，同上）
- R2 CI 验收 / R3 凭据联调：等外部条件（用户提供 Nacos 地址与 MiniMax key）
- R6–R9：见需求文档

## 实施会话回执（实施会话可在此追加）

（空）
