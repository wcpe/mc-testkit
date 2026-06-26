# ADR-0010：Velocity modern forwarding 实现机制（共享 secret + 单端口 → 不支持压测钉服）

## 状态
已接受（补充 [ADR-0004](0004-orchestration-model.md) 编排模型、[ADR-0008](0008-cluster-and-stress-dsl.md) 集群/压测；不取代）

## 背景
[ADR-0003](0003-p1-platform-scope.md) 把 Velocity 列入支持的代理平台，FR-02 也能下载并运行 Velocity，但「**经 Velocity 跑通场景**」所需的 forwarding 配置一直**未生成**——编排在单后端 / 集群 / 压测三处对 Velocity 都只 `project.logger.warn` 不写配置（`docs/specs/fr-08-first-consumer.md` 亦记「Velocity modern-forwarding 配置未生成」）。结果是 Velocity「声明支持但实际跑不起来」，与 PRD/ARCHITECTURE 的平台承诺存在漂移。

Velocity 与 BungeeCord 系机制本质不同，必须单独决策怎么实现：
- 配置是 **TOML**（`velocity.toml`）而非 BungeeCord 的 `config.yml`。
- 转发用 **modern forwarding**：代理与后端共享一个 secret，后端据此校验代理转发的真实玩家身份 / UUID。
- Velocity 是**单端口**代理（一个 `bind`），靠内置 `/server` 命令切服、`try` 列表定落地 / fallback 顺序；**没有** BungeeCord 的「N-listener 一端口对一后端」。
- Velocity 版本是其**自有版本号**（如 `3.3.0-SNAPSHOT`），与 MC 版本无关。

## 决策
实现 Velocity modern forwarding，纳入既有编排模型：
- **代理侧**（`config/VelocityProxyConfig.velocityProxyConfigToml`）：写 `velocity.toml`——`player-info-forwarding-mode = "modern"` + `forwarding-secret-file` + N 个具名 server + `try`（首个默认服、其余 fallback）+ 离线 `online-mode = false`；另写 `forwarding.secret` 文件。
- **后端侧**（`config/BackendVelocityConfig`）：`config/paper-global.yml` 写 `proxies.velocity.{enabled=true, online-mode=false, secret=<共享>}` + `server.properties` 离线；**不写** `spigot.yml settings.bungeecord`（BungeeCord 模式专属，与 Velocity 互斥）。
- **共享 secret**：代理 `forwarding.secret` 与后端 `velocity.secret` 取同一值 `McTestkitDefaults.VELOCITY_FORWARDING_SECRET`（localhost 临时测试环境的固定值，非安全敏感）。
- **Velocity 版本**：用 `McTestkitDefaults.VELOCITY_VERSION`（自有版本号，非后端 MC 版本），env `MC_TESTKIT_E2E_VELOCITY_VERSION` 仍可覆盖。
- **支持范围**：单后端经代理、集群（`/server` 切换 + `try` fallback，含 [FR-15](0008-cluster-and-stress-dsl.md) 崩溃接管）。**不支持压测钉服模型**——Velocity 单端口无法「一端口对一后端」钉服，故 `stress + via=velocity` **配置期中文报错**（不静默），压测须改用 Waterfall / BungeeCord 或直连。

## 理由
- Velocity 是官方主推的现代代理，补齐它让 ADR-0003 划定的三代理平台真正齐全、消除「声明支持却跑不通」的漂移。
- modern forwarding（共享 secret）是 Velocity 离线转发玩家身份的**唯一安全**方式（legacy 仅 BungeeCord 系、bungeeguard 需额外插件）。
- 单端口是 Velocity 的架构事实；压测「N-listener 钉服」是 BungeeCord 特性，无法平移。与其用 `forced-hosts`（按虚拟主机名而非端口路由）硬凑出钉服、增加复杂度，不如**明确不做并在配置期报错**——守 scope-discipline 不镀金、让失败早且可读。
- secret 用固定测试值保持简单（YAGNI）：自举跑在 localhost 临时目录，secret 不具安全意义；真有需要再加 env 覆盖。

## 后果
- 正面：三代理平台（Velocity/Waterfall/BungeeCord）全部可跑；Velocity 单后端 / 集群 / 崩溃接管纳入自举实机 E2E 矩阵。
- 约束：压测维度**必须**用 Waterfall / BungeeCord 或直连（经 Velocity 配置期即报错）；Velocity 缺省版本独立于 MC 版本、随上游 SNAPSHOT 演进需单独维护。
- 实现：新增 `VelocityProxyConfig` / `BackendVelocityConfig` 与 BungeeCord 系并列；YAML 深合并 helper 抽到 `config/YamlEditing.kt` 供二者共用（去重）。环境契约（FR-05）从「BungeeCord 三件套」扩为「BungeeCord 三件套 / Velocity 两件套」二选一（按代理平台）。

## 备选方案
- **继续不实现（保持 warn）**：与 PRD/ADR-0003 的平台承诺长期漂移，且用户明确要求补齐——否决。
- **用 forced-hosts 给 Velocity 做压测钉服**：按虚拟主机名路由，需 bot 用不同 hostname 连接，复杂且偏离「一端口对一后端」语义，非必需——YAGNI 否决，改为配置期报错。
- **legacy / bungeeguard forwarding**：modern 是 Velocity 推荐且最安全的方式，legacy 仅为兼容老 BungeeCord、bungeeguard 需额外插件——否决。
