# PaySRE Lab 后续安排需求文档

日期：2026-07-19
状态：待评审
分支基线：`feat/pay-sre-observability`（[PR #1](https://github.com/PolataMai/pay-sre-lab/pull/1) 开向 `main`）

## 1. 背景与当前状态

截至本文档撰写，项目已完成：

| 能力 | 状态 |
|---|---|
| 可观测证据平面（Prometheus/Loki/Tempo + 有界只读工具 + Evidence/审计） | 已合入分支，PR #1 待 CI 验收 |
| MiniMax 真实调查模型适配（OpenAI 兼容，占位 key） | 同上 |
| Nacos 配置中心（profile 门控，连接信息不入库） | 同上 |
| Phase 3 受控处置（four-eyes 审批、Action Guard、query-and-sync Runbook） | 同上 |
| Phase 4 切片一：故障库 1→2（`TIMEOUT_BUT_FAILED`）、处置评分、Incident 关单、e2e 参数化 | **代码完成，未提交**（见 R1） |

## 2. 需求列表

### R1（P0）Phase 4 切片一收尾

**需求**：完成进行中代码的收尾并入 PR。
**内容**：
1. `.github/workflows/ci.yml` 与 README 中 `-Dtest=ChannelTimeoutButSuccessE2ETest` 更名为 `FaultScenarioE2ETest`；README「可重复场景」改写为故障目录（两个场景 + 处置/关单链路）。
2. 全量 `mvn clean verify`（本地排除 Docker 拉镜像用例）回归。
3. 按仓库惯例分逻辑提交并推送更新 PR #1。

**验收**：本地测试全绿（预计 ≥180 个）；PR 包含渠道新故障类型、场景 YAML×2（含 `remediation` 段）、`ScenarioEvaluator` 处置评分、`POST /api/incidents/{id}/resolution` 关单、参数化 e2e。

### R2（P0）CI 全链路验收与合并

**需求**：PR #1 的 GitHub Actions（Java 21 + Docker Compose）跑通完整链路后合并 `main`。
**内容**：两个故障场景在同一 Compose 栈顺序回放：注入 → 遥测摄取 → 调查评分 → 提案/审批/执行 → 支付收敛（SUCCESS / FAILED 两个方向）→ 关单。CI 失败时按上传的 Compose 日志修复。
**验收**：CI 全绿，PR 合并，`main` 即为可复现基线。
**依赖**：R1。本机 Docker Hub 拉取受限，CI 是唯一权威验收环境。

### R3（P0）外部凭据联调（需要用户提供输入）

| 子项 | 用户提供 | 动作 | 验收 |
|---|---|---|---|
| R3a Nacos | Nacos 地址/命名空间/账号 | 填 `deploy/.env`（gitignored），`SPRING_PROFILES_ACTIVE=nacos` 起栈 | 三服务从 Nacos 拉到配置；缺地址 fail-fast 行为复核 |
| R3b MiniMax | `MINIMAX_API_KEY` | `INVESTIGATION_MODEL=minimax` 起栈，重放两个故障场景 | 真实模型完成调查，结论通过验证器，评分与 Stub 基线对比留档 |

**说明**：凭据只进 `deploy/.env` 或环境变量，`NacosConfigurationTest` 持续保证不入 git。

### R4（P1）结论校验策略目录化（故障库继续扩展的前置）

**需求**：把 `ConclusionValidator` / `StubInvestigationModel` / MiniMax system prompt 中硬编码的单根因（`CHANNEL_TIMEOUT_RESPONSE_LOST`）与单 Runbook 允许列表，重构为按根因索引的策略目录：`根因 → {允许的 Runbook 集合, 是否强制人工复核, 证据充分性规则}`。
**验收**：新增一个根因只需注册一条策略 + 一个场景 YAML，不再改动验证器主体；既有两场景回归不变。

### R5（P1）故障库第二轮扩展

按依赖的基础设施从轻到重排序：

| 子项 | 新故障 | 需要新增的基础设施 | 新根因（示例） |
|---|---|---|---|
| R5a | 渠道拒绝尖峰（`DECLINE_ALL`） | 失败率告警规则（`payment_attempt_outcome_total`）、advisory 型 Runbook（仅建议、不可执行） | `CHANNEL_DECLINE_SPIKE` |
| R5b | 返回码配置错误 | payment-service 渠道返回码映射层（配置驱动，可被 Nacos 下发） | `CHANNEL_CODE_MAPPING_ERROR` |
| R5c | 回调丢失 / 重复回调 | 渠道异步回调通道 + 幂等消费 | `CHANNEL_CALLBACK_LOST` 等 |
| R5d | 路由异常 | 多渠道路由（当前硬编码 CHANNEL_A） | `ROUTING_MISCONFIGURED` |

**验收**（每个子项相同）：模拟器故障类型 + 场景 YAML（含 ground truth 与 remediation 段）+ 调查链路单测 + e2e 目录自动纳入。
**依赖**：R4；R5c/R5d 各含一次独立的基础设施设计（走 brainstorm→spec 流程）。

### R6（P2）事故控制台（Incident Console）

**需求**：React + TypeScript + Vite 前端（设计文档 §14 已预留）：Incident 列表/详情、Evidence 链与哈希展示、调查进度（SSE）、Runbook 提案/审批操作台、审计流水。
**验收**：本地 Compose 附带 console 服务；完成一次全场景演练可全程不碰 curl。
**依赖**：R2 合并后的稳定 API；建议在 R4 之后做，避免策略模型返工。

### R7（P2）故障注入 UI 与场景管理

**需求**：控制台内的故障注入页（选择渠道/类型/概率/时长），场景 YAML 的导入导出。
**依赖**：R6。

### R8（P2）PaySRE Benchmark 首版报告

**需求**：多场景 × 多模型（Stub / MiniMax 不同型号）评分矩阵：根因命中率、证据召回、处置正确率、平均工具调用数、时延与 token 成本；输出 markdown 报告并可复现。
**依赖**：R3b、R5（至少 4 个场景）后价值最大。

### R9（P2）Phase 5 社区化

场景贡献规范（YAML schema + 校验器）、模型适配 SPI（`InvestigationModel` 之上的注册机制）、只读工具以 MCP 形式暴露。**依赖**：R8 前后皆可，建议 Benchmark 定型后再冻结贡献规范。

## 3. 里程碑建议

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| M1（本周） | R1 + R2 + R3 | PR 合并、真实模型与 Nacos 联调各留一份验收记录 |
| M2 | R4 + R5a + R5b | 故障库 ≥4 场景，策略目录化落地 |
| M3 | R5c/R5d + R6 + R7 | 控制台可演示，异步/路由故障入库 |
| M4 | R8 + R9 | 首版 Benchmark 报告发布 |

## 4. 风险与依赖

1. **本机 Docker 网络**：镜像拉取不稳定，所有 Compose 验收以 CI 为准；如需本地全链路，建议配置镜像加速器（用户决策）。
2. **告警面扩展**（R5a）：失败率告警的窗口与阈值需要与 5 分钟聚合窗口联合调参，避免场景回放互相污染。
3. **真实模型不确定性**（R3b/R8）：MiniMax 输出波动会引入评分抖动，Benchmark 需固定 prompt 版本并多次采样。
4. **外部输入阻塞**：R3 完全依赖用户提供的 Nacos 地址与 MiniMax key，在此之前 M1 无法关闭。
