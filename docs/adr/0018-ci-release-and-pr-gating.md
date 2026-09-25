# ADR-0018：发布流程改为「分支保护 + PR 门禁 + 打 tag 触发 CI 发布」

## 状态
已接受

## 背景
此前发布**全靠手工**：

- `master` 无分支保护，可（且事实上经常）直接推送；
- CI（`.github/workflows/ci.yml`）只跑构建与测试，**没有任何发布步骤**；
- 发版 = 本地 `./gradlew publish`（凭据走本机 Gradle 属性）+ 本地 `git tag` + 手工在 GitHub 建 Release 并手抄 CHANGELOG 正文。

这带来三类问题：

1. **门禁可绕过**：直推 `master` 的提交不经过 PR 与检查；已发生过「发布提交触发的 master 构建在 ktlint 上失败、该版本处于门禁红状态」的情况——发布产物**没有**被验证门背书。
2. **发布不可复现、靠人记步骤**：版本号 bump、CHANGELOG 定稿、打 tag、发布构件、写 Release 正文是五个手工动作，顺序与内容全凭记忆；漏一步就产出不一致的状态（例如 tag 与 `VERSION` 不符）。
3. **凭据散在本机**：发布凭据存在个人 `~/.gradle/gradle.properties`，谁的本机有凭据谁才能发版，且无法审计发布行为。

约束与驱动因素：

- 版本号唯一真源是根 `VERSION` 文件（[architecture-invariants §3](../../.claude/rules/architecture-invariants.md)），发布必须与它一致。
- GitHub Actions 的硬约束：**用 `GITHUB_TOKEN` 创建的 tag 不会触发其它 workflow**（防递归）。故「CI 自己打 tag 再自动发布」需要额外长期 PAT。
- 发布凭据属机密，只能存 GitHub secret，不得入库（`SECURITY.md`）。

## 决策

把发布改为**「分支保护 + PR 门禁 + 打 `vX.Y.Z` tag 触发 CI 发布」**：

1. **`master` 开启分支保护**：禁止直接推送；改动必须经 PR；合并前必需状态检查（`构建与测试（插件）`、`静态检查（模板 bot）`）必须全绿。
2. **版本号仍以根 `VERSION` 为唯一真源**：发版 PR 里 bump `VERSION` 并把 CHANGELOG 未发布段定稿为 `## [X.Y.Z] - YYYY-MM-DD`。
3. **发布由 `release.yml` 在 tag 推送时执行**：校验 tag 与 `VERSION` 一致且 CHANGELOG 有该段 → **重跑一遍验证门** → 用 `release` environment 的 secrets 发布构件到 maven.wcpe.top → 取 CHANGELOG 该段作正文建 GitHub Release。
4. **tag 由人打，发布动作全自动**：不使用 PAT、不引入额外长期凭据——需要人工判断的只有「何时发、发哪个版本」。
5. **发布凭据只存 GitHub**：`release` environment 的 `WCPE_MAVEN_USERNAME` / `WCPE_MAVEN_PASSWORD`；本地不再作为正式发布路径。

## 理由

- **门禁不可绕过**：分支保护把「PR + 检查绿」变成合并的硬前提，消除「直推绕过验证门」；release 里再跑一遍 `build`，兜底覆盖「tag 打在未经 CI 的提交上」。
- **发布可复现、步骤固化在代码里**：流程写成 `release.yml`（而不是 wiki / 记忆里的五步），版本一致性、CHANGELOG 段落、正文生成都是脚本化断言；不一致**在发布前**失败——Maven 构件不可覆盖，事后纠正成本高，前置校验是必须的。
- **零额外凭据**：`GITHUB_TOKEN` 无法自触发是平台硬约束，引入 PAT 会多一份长期密钥的轮换与泄露面；「人打 tag」这一步成本极低（一条命令），却换来不需要 PAT。
- **凭据集中可审计**：发布凭据离开个人机器，进入 environment secret；环境还可挂人工审批与环境保护规则。
- **与本项目既有不变量一致**：不新增版本真源（仍 `VERSION`），不引入新语言 / 新栈，纯 CI 配置与文档调整。

## 后果

- **正面**：任何人（含 CI）都不能绕过门禁改动 `master`；发版从五个手工步骤收敛为「合并发版 PR + 打 tag」；发布行为集中在 `release.yml`，可审阅、可回滚（改 workflow）。
- **约束**：
  - **必需检查名即契约**：`构建与测试（插件）` / `静态检查（模板 bot）` 是被分支保护引用的名字，**改名必须同步更新仓库分支保护规则**，否则 PR 会永久卡在「等待检查」。已在这两个 job 处加注释提醒。
  - **Maven 已发布构件不可覆盖**：版本号/ tag 发错只能发新版本，不能重推同名构件；校验前置正是为此。
  - 维护者需有权限打 tag（仓库写权限）。
  - `hotfix/*` 同样走「发版 PR + tag」路径，不能再「本地直接发补丁版」。
- **不做**：
  - **不做「合并即自动发版」**（需 PAT，且自动推断版本号脆弱）。
  - **不自动发快照**：`VERSION` 为 `-SNAPSHOT` 时可手工 `./gradlew publish` 到 `maven-snapshots`，但 CI 不自动执行（避免每次推送都产生快照流量，需要时再按 ADR 增补）。
  - 不引入 release-drafter 之类的自动 CHANGELOG 生成——本项目的 CHANGELOG 是**手写的活文档**（写明为什么改），不从 commit message 自动生成。

## 备选方案

- **合并即自动发版（CI 打 tag 再触发发布）**：需长期 PAT（`GITHUB_TOKEN` 建的 tag 不触发 workflow）；且「PR 合入 → 自动推断下一个版本号」要么解析 CHANGELOG 猜、要么引入额外语义化版本工具，脆弱且难审计。落选。
- **维持手工发布 + 只加分支保护**：分支保护解决「绕过门禁」，但发布仍是五个手工步骤、凭据仍在本机，问题 2、3 未解。落选。
- **用 PAT 打 tag 全自动**：见第一条，多一份长期密钥的轮换与泄露面，收益（省一条命令）不匹配。落选。
- **每次推送 master 都发快照 + tag 才发正式版**：快照流量与仓库占用上升，而本项目消费方都是显式版本引用、无快照需求。落选（保留为需要时的手工动作）。
