# 功能规格：E2E 并行矩阵与 jar 缓存

> 状态：已交付　·　关联：CI/E2E 工作流（非插件运行时 FR）　·　分支：master（用户确认不建 worktree）

## Report

**What was built** — `e2e.yml` 改为 **仅 `workflow_dispatch`** 触发，去掉 `v*` tag 自动跑。拆成两类并行 job：`version-smoke` 对 Paper 8 代表版本（1.7.10–1.21.1）各起一 runner 跑 `e2eSmoke`；`scenario` 按 suite 矩阵并行跑 Waterfall 全场景 / BungeeCord 集群 / Velocity 代理 / Folia 烟雾。每个 job 用 `actions/cache` 复用 `~/.gradle/caches/mc-testkit-jars`，并按版本注入 `MC_TESTKIT_JAVA_HOME_<段>`（legacy→8，1.21/Velocity→21，默认 JAVA_HOME=17）。`ci.yml` 仍只做构建 + 单元/TestKit。

**Verification** — 结构校验：dispatch-only、≥2 处 jar cache、8 版本与 4 suite 齐全、双矩阵 `fail-fast: false`；子代理审查三维均 PASS。未在本机跑实机 E2E（需 Actions 手动触发）。

**Journey log** —
1. `gradle/actions/setup-gradle` 对自定义 jar 缓存目录命中不稳；改显式 `actions/cache` + 按版本/suite 分 key、`restore-keys` 跨 job 共享。
2. 多 `setup-java` 后 `JAVA_HOME` 落在最后一次安装；须逐次捕获路径，最后钉回 17，服务端经 env 段变量选 8/21。
3. 不做 8 版本×全场景笛卡尔积：版本面用烟雾，代理编排面钉在 1.20.1，避免 runner 分钟与限流爆炸。

## 1. 背景与目标

当前 `e2e.yml` 单 job 串行跑完全部场景（默认 Paper 1.20.1 + 若干代理旁路），首跑要下大量服务端/代理 jar，且「所有支持版本」未纳入自动矩阵；`gradle/actions/setup-gradle` 对 `~/.gradle/caches/mc-testkit-jars` 命中不稳定，日志里每次仍见下载。

目标：
1. **显式缓存** `~/.gradle/caches/mc-testkit-jars`，重复 E2E 少打下载源。
2. **仅手动触发**（去掉 `v*` tag 自动跑）。
3. **触发时并行验证全部支持面**：Paper 8 代表版本烟雾 + 既有代理/场景矩阵（用 GitHub Actions `strategy.matrix` 拆 job 并行，而非一个 runner 串到底）。

`ci.yml` 保持只做构建 + 风格 + 单元/TestKit，不拉起真实服务端。

## 2. 需求

- 范围内：
  - `e2e.yml` `on:` 仅 `workflow_dispatch`。
  - 各 E2E job 使用 `actions/cache` 缓存 `~/.gradle/caches/mc-testkit-jars`。
  - **version-smoke 矩阵**：8 个 `MinecraftVersionGroup.REPRESENTATIVE_VERSIONS` 各一 job 跑 `e2eSmoke`（1.7.10 插件侧自动跳过 bot）。
  - **scenario 矩阵**：在默认 1.20.1（Folia 除外）上并行覆盖现有代理×场景路径（Waterfall 全套 / BungeeCord 集群 / Velocity 经代理+集群 / Folia 烟雾）。
  - 按版本注入 `MC_TESTKIT_JAVA_HOME_<版本段>`（legacy→8，1.17–1.20→17，1.21→21；Velocity 代理仍需 `MC_TESTKIT_JAVA_HOME_21`）。
  - 文档：OPERATIONS §1.1、README、CHANGELOG。
- 不做：
  - 不改插件运行时 / DSL。
  - 不把 E2E 挂到每个 PR/push。
  - 不做 8 版本 × 全场景笛卡尔积（过重）。
  - 不改 `ci.yml` 触发策略（仅修正过时注释）。

## 3. 设计

### 3.1 触发与缓存

- `on.workflow_dispatch` only。
- 版本 job cache key 含 `matrix.version`，场景 job 含 `matrix.suite`；`restore-keys` 前缀 `mc-testkit-jars-${{ runner.os }}-` 允许跨 job 命中公共 jar。

### 3.2 Job 拓扑

| Job | 并行维度 | 内容 |
|---|---|---|
| `version-smoke` | matrix.version = 8 代表版本 | 仅 `e2eSmoke` |
| `scenario` | matrix.suite ∈ {waterfall-full, bungeecord-cluster, velocity-proxy, folia-smoke} | 复用消费者生成逻辑，按 suite 跑对应步骤 |

每个 job 自包含：checkout → 多 JDK → cache → setup-gradle → `publishToMavenLocal` → harness jar → bot deps（folia 可省）→ 生成消费者 → 执行 → 归档 artifacts。

### 3.3 Java 运行时注入

| MC 版本 | 启动用 JDK | 环境变量 |
|---|---|---|
| 1.7.10 / 1.8.8 / 1.12.2 / 1.16.5 | 8 | `MC_TESTKIT_JAVA_HOME_1_7` 等 |
| 1.17.1 / 1.19.4 / 1.20.1 | 17 | 默认 `JAVA_HOME`（17） |
| 1.21.1 | 21 | `MC_TESTKIT_JAVA_HOME_1_21` |
| Velocity 代理 | 21 | `MC_TESTKIT_JAVA_HOME_21` + DSL `javaVersion = 21` |

Gradle 本身用 17；每次 `setup-java` 后立刻捕获 `$JAVA_HOME`，最后一次安装后钉回 17。

## 4. 任务

- [x] T1 规格（本文件）
- [x] T2 重写 `e2e.yml`
- [x] T3 校验 YAML + 文档同步
- [x] T4 审查

## 5. 验收

- 手动触发后出现：8× Paper 烟雾 + 4× 场景 suite，彼此并行（`fail-fast: false`）。
- 日志在 cache hit 时不再重复下载已缓存 build 的 jar（仍可能一次 API 解析 latest build）。
- 无 tag / push 自动触发 E2E。
- `ci.yml` 触发策略不变（仍 push/PR 构建测试）。

## 6. 风险

- 公共缓存并发写入：`actions/cache` 对同一 key 只有首次成功 save；restore-keys 仍可复用。
- PaperMC 对 latest 解析仍有少量 API 调用；频率已由「仅手动」约束。
- 矩阵并行会提高 runner 分钟消耗（有意用时间换验证面）。
