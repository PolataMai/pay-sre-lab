# Nacos Config Center Design

日期：2026-07-17
状态：已实施
前置：`docs/superpowers/specs/2026-07-17-minimax-investigation-model-design.md`

## 1. 目标

1. 三个应用（payment-service、channel-simulator、sre-control-plane）接入 Nacos 配置中心，可从远端拉取覆盖配置（典型用例：把 `MINIMAX_API_KEY`、调查模型开关等敏感/环境配置放到 Nacos，而不是本地 env 文件）。
2. **Nacos 连接信息（地址、命名空间、账号密码）绝不进 git**：仓库中只允许出现 `${NACOS_*}` 环境变量占位符与 `<REPLACE_...>` 模板。
3. 不接 Nacos 时（CI、本地 `mvn verify`、默认 Compose）行为与现状完全一致，不需要任何外部服务。
4. Nacos 已启用但不可达/未配置地址时 fail-fast，拒绝以"半配置"状态启动。

**非目标**：服务注册发现、配置动态热刷新（`@RefreshScope`）、Nacos 本身的部署。

## 2. 方案选择

| 方案 | 说明 | 结论 |
|---|---|---|
| A. Spring Cloud Alibaba Nacos Config，import 模式 + profile 门控（选定） | 官方 `2025.1.0.0` 显式支持 Spring Boot 4.x / Spring Cloud 2025.1.x；用 `spring.config.import: nacos:` 无 bootstrap 接入；整套配置放在 `application-nacos.yml`，仅当激活 `nacos` profile 时生效 | 标准、维护成本最低；profile 门控让未启用时零行为差异 |
| B. 手写 EnvironmentPostProcessor 调 Nacos OpenAPI | 依赖最小，风格贴近项目 | 否决：重造轮子（登录态、YAML 合并、优先级），SCA 官方已支持 Boot 4 |
| C. `SPRING_CONFIG_IMPORT` 纯环境变量注入 | 仓库零改动 | 否决：结构不可见、不可测试，用户每次要拼完整 import 串 |

## 3. 版本事实（Maven Central 已验证）

- `com.alibaba.cloud:spring-cloud-alibaba-dependencies:2025.1.0.0`（latest/release，官方发布说明标注 "Support Spring Boot 4.x and Spring Cloud 2025.1.x"）。
- `org.springframework.cloud:spring-cloud-dependencies:2025.1.2`（2025.1 线最新补丁）。

## 4. 设计

### 4.1 依赖

根 POM `dependencyManagement` 导入上述两个 BOM；三个服务模块各加 `spring-cloud-starter-alibaba-nacos-config`。

### 4.2 Profile 门控

每个服务新增 `application-nacos.yml`（提交到 git，只有占位符）：

```yaml
spring:
  config:
    import: "nacos:${NACOS_DATA_ID:<service-name>.yml}?group=${NACOS_GROUP:pay-sre-lab}"
  cloud:
    nacos:
      server-addr: ${NACOS_SERVER_ADDR}
      username: ${NACOS_USERNAME:}
      password: ${NACOS_PASSWORD:}
      config:
        namespace: ${NACOS_NAMESPACE:}
```

- `NACOS_SERVER_ADDR` 故意**没有默认值**：profile 激活但没给地址 → 占位符解析失败 → 启动失败（fail-fast）。
- 远端 dataId 默认 `<service-name>.yml`，group 默认 `pay-sre-lab`，均可用环境变量覆盖。
- import 进来的远端配置优先级高于本地 `application.yml`，因此 Nacos 里可以直接覆盖 `paysre.investigation.*` 等任意配置。

每个服务的主 `application.yml` 增加一行：

```yaml
spring:
  cloud:
    nacos:
      config:
        import-check:
          enabled: false
```

这是 starter 在 classpath 上而未声明 import 时不阻断启动的官方开关；`nacos` profile 未激活时整个集成保持休眠。

### 4.3 秘密不进 git

- 真实连接信息只放两处之一：shell 环境变量，或 `deploy/.env`（`.gitignore` 已忽略 `.env` 与 `deploy/.env`）。
- `deploy/env.example` 只含 `<REPLACE_...>` 模板与空值。
- `deploy/compose.yaml` 对三个服务透传 `SPRING_PROFILES_ACTIVE` 与 `NACOS_*`，默认为空/关闭。
- 新增回归测试 `NacosConfigurationTest`（e2e-tests）作为持续防线：断言三个 `application-nacos.yml` 的 server-addr 严格等于 `${NACOS_SERVER_ADDR}`、凭据字段只允许环境占位符、`env.example` 中 NACOS 值只允许空或 `<...>` 模板、compose 完成透传。任何人往 git 里写死 Nacos 地址/密码都会在 `mvn verify` 直接红。

### 4.4 启用方式（用户拿到地址后）

```bash
# deploy/.env（gitignored）
SPRING_PROFILES_ACTIVE=nacos
NACOS_SERVER_ADDR=<host:8848>
NACOS_NAMESPACE=<namespace-id，公共命名空间留空>
NACOS_GROUP=pay-sre-lab
NACOS_USERNAME=<username>
NACOS_PASSWORD=<password>
```

在 Nacos 中按 group `pay-sre-lab` 创建三个 dataId：`payment-service.yml`、`channel-simulator.yml`、`sre-control-plane.yml`（YAML 格式，内容为需要覆盖的 Spring 配置）。

## 5. 测试计划

1. `NacosConfigurationTest`（新增）：上文 4.3 的防泄漏与结构断言。
2. 三个应用既有 Spring 上下文测试：验证 starter 在 classpath 上、profile 未激活时应用照常启动（休眠路径回归）。
3. 全量 `mvn clean verify` 无回归。
4. 真实连通性验收依赖用户提供的 Nacos 地址，属于后续手工步骤（本设计的 fail-fast 行为保证配错时无法带病启动）。
