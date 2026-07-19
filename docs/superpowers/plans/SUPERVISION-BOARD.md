# 监工看板（SUPERVISION BOARD）

> 本文件由桌面端监工会话维护，用于协调 VS Code 实施会话的阶段任务。
> 实施会话：每完成一个阶段（提交并推送后），读取本文件领取下一阶段任务；
> 若「纠正意见」有未关闭项，优先处理。除本监工外请勿改写本文件（可在文末「实施会话回执」区追加留言）。
> 需求全集见 `2026-07-19-roadmap-requirements.md`（R1–R9）。

最后更新：2026-07-19 23:00（监工）

## 阶段验收记录

| 阶段 | 状态 | 备注 |
|---|---|---|
| R1 Phase 4 切片一收尾 | ✅ 已验收 | 22:38 三个提交，含 e2e 更名与文档 |
| R4 根因策略目录化 | ✅ 代码已验收 | 22:47 两个提交；MiniMax 目录派生做得好；**文档缺口见 C1** |
| R5a 渠道拒绝尖峰 | ✅ 已验收 | 提交 `0016d73` 隔离 worktree 全量回归 BUILD SUCCESS（控制面 131 测试全绿）；23:05 监工验收 |

## 纠正意见（处理后在回执区确认）

- **C2 状态更新（23:10 监工）**：观察到实施会话已在按本意见重构（`ResultKind.UNMAPPED` 显式语义、移除 fallback 配置，方向正确）。
  监工已顺手将 `PaymentServiceApplication` 的 mapping bean 改为两参构造（与新签名一致）,如与你的实现冲突以你的为准。
  提交后监工将验收：unmapped 在受理与 state-sync 两条路径都保持 UNKNOWN 且 reasonCode 为 `CHANNEL_CODE_UNMAPPED`、原 `resultFor("ZZ")==FAILED` 测试断言已移除。

- **C2（设计，阻塞 R5b 验收）**：`PaymentServiceApplication` 中
  `paysre.channel.code-mapping.fallback-result` 默认值为 `FAILED`——未映射的渠道返回码会被**猜测**成失败终态。
  这违反项目核心不变量"没有证据就不改状态"：渠道实际成功但返回了映射表外的码时，本地置 FAILED 属于资损语义。
  要求：映射未命中一律**保持/进入 UNKNOWN**（reasonCode `CHANNEL_CODE_UNMAPPED`），同步受理与 state-sync 两条路径一致；
  `fallback-result` 配置项应移除或仅允许 `UNKNOWN` 类语义，不允许配置成任何终态。相应单测覆盖未映射码路径。

- **C1（流程）**：R4 与 R5a 未按仓库惯例先行/补写设计文档。请补一份
  `docs/superpowers/specs/2026-07-19-root-cause-policy-and-decline-spike-design.md`，
  覆盖：策略目录接口与注册方式、advisory-only Runbook 的语义（`ADVISORY_NO_RUNBOOK` 拒绝路径）、
  失败率告警的窗口/阈值如何与 Incident 5 分钟聚合窗口协调、DECLINE_ALL 场景的 e2e 回放顺序约束。
  写完提交（`docs:` 前缀），无需等监工确认即可继续当前阶段。

## 当前分配：R5b 返回码配置错误

**目标**：payment-service 引入渠道返回码映射层（配置驱动），并以"映射配置错误"作为可注入、可调查、可处置的新故障场景。

**范围与验收标准**：
1. 设计文档先行（`specs/2026-07-19-channel-code-mapping-design.md`）：映射层位置（建议在 payment-service 应用层，渠道响应 → 业务终态的显式映射表）、配置形态（Spring 配置属性，键可被 Nacos 下发覆盖）、错配语义（未知/错配码如何进入 UNKNOWN 或告警）。
2. 模拟器：新故障类型让渠道返回**允许列表之外的返回码**（如 `E9`），同步响应正常返回（不超时）。
3. payment-service：映射未命中 → 支付进入 UNKNOWN（reasonCode `CHANNEL_CODE_UNMAPPED`），不允许猜测终态；结构化日志与指标照常。
4. 根因 `CHANNEL_CODE_MAPPING_ERROR` 注册进策略目录（advisory-only 即可，处置=人工修配置后重放 state-sync 或直接关单，按你的设计判断并写进 spec）。
5. 场景 `fault-scenarios/channel-code-mapping-error-v1.yaml`（含 expected + remediation 段）+ 评分器兼容 + e2e 目录纳入（注意与前两个场景的回放顺序与聚合键隔离）。
6. 全量 `mvn clean verify`（本地排除 `PostgreSqlMigrationTest`）绿后分逻辑提交并推送。

**边界**：不做多渠道路由（那是 R5d）；不改 MiniMax prompt 之外的模型逻辑（策略目录应已让其自动获得新根因）。

## 排队中

- R5c 回调丢失（前置：异步回调通道设计，spec 先行，动工前等监工确认设计）
- R5d 路由异常（前置：多渠道路由设计，同上）
- R2 CI 验收 / R3 凭据联调：等外部条件（用户提供 Nacos 地址与 MiniMax key）
- R6–R9：见需求文档

## 实施会话回执（实施会话可在此追加）

（空）
