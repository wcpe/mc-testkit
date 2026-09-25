# 运维手册：mc-testkit

> 本项目是一个 Gradle 插件（开发期工具），不部署到服务器。这里给「构建 / 发布 / 升级 / 回滚 / 排障」操作指南，以及它作为工具运行时的常见故障处置。运维方式变化时更新。

## 1. 构建与发布

> 完整发版流程见 `docs/CONTRIBUTING.md` §8。**发布由 CI 完成**——本地不再手工 `publish`、不手工建 Release。

### 1.1 日常构建与校验（本地）

- **构建**：`./gradlew build`（含 ktlint 风格检查 + 全部单元 / TestKit 测试）
- **测试**：`./gradlew test`
- **本地校验**：`./gradlew :tasks` / `:help`（确认插件配置期无错、任务注册正常）
- 交付物 = 发布到 Maven 的 Gradle 插件构件（插件 jar + 插件标记），源码即真源（git）。

### 1.2 发布（打 tag 触发 CI）

1. **发版 PR**：把 `CHANGELOG.md` 的 `## [未发布]` 段定稿为 `## [X.Y.Z] - YYYY-MM-DD`，根 `VERSION` 改成 `X.Y.Z`；PR 经 CI 全绿后合入 `master`（master 禁直接推送）。
2. **打 tag**：`git tag vX.Y.Z && git push origin vX.Y.Z`
3. **CI 自动完成**（[.github/workflows/release.yml](../.github/workflows/release.yml)）：校验 tag 与 `VERSION` 一致、`CHANGELOG` 有该段 → 重跑验证门 → 发布构件到 maven.wcpe.top → 建 GitHub Release（正文取 CHANGELOG 该段）。

**发布凭据**（只存 GitHub，不入库）：

| 名称 | 承载位置 | 用途 |
|---|---|---|
| `WCPE_MAVEN_USERNAME` | 仓库 Settings → Environments → **release** → secrets | 发布到 maven.wcpe.top 的账号 |
| `WCPE_MAVEN_PASSWORD` | 同上 | 对应密码 / 访问令牌 |

本地开发若需手工试验 `./gradlew publish`，可经 `~/.gradle/gradle.properties` 或同名环境变量提供这两个值（构建脚本两者都读），但**正式发布一律走 CI**。

### 1.3 CI / 实机 E2E

| 工作流 | 触发 | 内容 |
|---|---|---|
| `ci.yml` | 每个 PR + `master` 推送 | 插件构建 + ktlint + 单元/TestKit；模板 bot 静态检查。**是合并门禁**（master 分支保护的必需检查）。**不**拉起真实服务端 |
| `release.yml` | **推 `v*` tag** | 校验版本一致 → 重跑验证门 → 发布到 maven.wcpe.top → 建 GitHub Release |
| `e2e.yml` | **仅手动**（Actions → E2E → Run workflow） | 并行矩阵：Paper 8 代表版本 `e2eSmoke` + Waterfall 全场景 / BungeeCord 集群 / Velocity 代理 / Folia 烟雾 |

实机 E2E 会下载服务端/代理 jar；工作流用 `actions/cache` 复用 `~/.gradle/caches/mc-testkit-jars`。**发版前应手动跑一遍 E2E**（`testing-and-quality.md`）。矩阵与缓存设计见 `docs/specs/e2e-parallel-matrix.md`。

## 2. 升级

- 遵循 SemVer。消费方升级只需改其 `plugins { id("top.wcpe.mc-testkit") version "X.Y.Z" }`。
- 破坏性变更（DSL/任务名/环境变量）升 major，并在 `CHANGELOG.md` 写明迁移步骤。

## 3. 数据备份与恢复

- 本项目**无持久化数据**：唯一真源是 git 仓库的源码与文档。
- "备份" = 远程仓库；"恢复" = 重新 clone。运行期产物（运行目录/日志/结果/缓存）均可重建，不需备份。

### 恢复演练
- 不涉及有状态数据，无需恢复演练。验证"可重建"即：clean 后重新构建并跑一次 E2E 能通过。

## 4. 回滚

- 代码回滚优先 `git revert`（经 PR 合入，不改写已 push 历史）。
- 已发布版本有问题：消费方降回上一个良好版本；必要时从发布 tag 切 `hotfix/*` 出补丁版（补丁版同样走「发版 PR → 打 tag → CI 发布」）。
- **Maven 已发布构件不可覆盖**：修好问题只能发新版本号（如 `0.12.1`），不要试图重推同一个 tag / 版本号。

## 5. 排障

### 5.1 本项目构建 / 发布
- 配置期报缺依赖/路径：按中文报错补齐对应环境变量或 jar 路径。
- **release 工作流报「缺少发布凭据」**：在仓库 Settings → Environments → `release` 配置 secrets `WCPE_MAVEN_USERNAME` / `WCPE_MAVEN_PASSWORD`（见 §1.2）。
- **release 工作流报 tag 与 VERSION 不一致**：先把 `VERSION` 改成该版本号并经 PR 合入 `master`，再重新打 tag（不要移动已推送的 tag，改发新版本号）。
- **release 工作流报 CHANGELOG 缺该版本段**：把 `## [未发布]` 段定稿为 `## [X.Y.Z] - YYYY-MM-DD` 后合入，再打 tag。
- **PR 合并按钮不可用**：看分支保护的必需检查是否全绿（`构建与测试（插件）` / `静态检查（模板 bot）`）；检查名若被改名需同步更新分支保护规则。
- 发布失败（网络 / 仓库拒绝）：检查 maven.wcpe.top 可达性与凭据权限（`maven-releases` 仓库的写权限）。

### 5.2 作为工具运行 E2E 时（消费方常见故障）
- **反复下载服务端/代理**：检查内置下载缓存目录是否被清；首次需联网，之后复用。
- **服务端起不来且报 `ClassNotFoundException`**：先看运行目录的 `<key>.log`，再看启动日志里的选路行（`按自包含 jar 启动` / `按运行库 classpath 启动`）。运行目录带注入运行库目录 `server-libraries/` 的构件走后者，依赖由 `.mc-testkit-<key>-classpath.jar` 的清单给出；该清单是**启动时刻**的快照，运行库有变动需重跑任务重建。paperclip 自有的 `libraries/` 不参与 classpath（它是服务端自己的运行库目录，跨轮保留只为省重复下载）。
- **机器人连不上**：看机器人日志（端口未就绪/被踢）；经代理时确认机器人协议版本已固定为后端版本（编排默认处理），以及后端 BungeeCord 模式配置已生效。
- **端口占用 / 残留进程**：上一轮异常中断可能残留后台代理/后端 JVM 或机器人进程，按运行目录/端口清掉再重跑。
- **依赖服务不可用**：被测插件依赖的数据库/Redis 等需先就绪（如本地容器已启动、端口/凭据匹配）。
- 详细排障随 `template/` 的 README 提供（针对具体场景）。
