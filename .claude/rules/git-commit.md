# Git 提交规范

> 适用于本仓库所有 `git commit` 操作。

## 1. 提交信息语言（强制）

- **标题（Description）与正文（Body）必须使用简体中文。** 禁止英文、日文等非中文。
- Conventional Commits 的 type 与 scope 仍用英文小写（`feat`/`fix`/`refactor`/`docs`/`chore`/`test`/`build`/`ci`/`perf`/`style`）。
- **禁止在提交信息中添加任何 AI 签名或尾注**，例如 `Generated with ...`、`Co-Authored-By: ...`。不要附加作者/工具/来源署名。

### 1.1 标题格式

```
<type>(<scope>): <中文描述>
```

- `<scope>`：英文小写模块/能力域，可选。本仓库常用：`plugin`、`dsl`、`task`、`proxy`、`cluster`、`bot`、`template`、`config`、`docs`、`build`、`ci`。
- `<中文描述>`：简洁陈述本次做了什么，必须中文，结尾不加句号。

### 1.2 正文格式

- 用空行与标题分隔，中文撰写，可用 `-` 列要点。
- 说明"为什么改"与"改动要点"，不逐行复述 diff。

### 1.3 示例

✅ 正确
```
feat(auth): 实现基于令牌的登录鉴权

- 新增令牌签发与校验，登录后返回访问令牌
- 受保护接口在中间件统一校验令牌，失败返回 401
- 令牌过期与刷新策略写入配置，便于按环境调整
```

❌ 错误（标题英文）
```
feat(auth): add token-based login
```

### 1.4 禁止阶段性词语（强制）

提交按**功能点**描述，不按**开发阶段**描述。commit message（标题与正文）**禁止**出现阶段 / 批次性词语：`Phase 0`、`P0` / `P1` / `P2`、`MVP`、`Sprint`、`第一期` / `本次迭代` 等。它们说的是"项目走到哪一步"而非"这次改了什么"，会随时间失效、也无法追溯到具体改动。

✅ 正确（描述功能点）
```
feat(auth): 实现令牌刷新接口
```

❌ 错误（用阶段词代替功能描述）
```
feat: 完成 MVP 第一期核心功能
chore: P1 Sprint 3 的若干功能
```

## 2. 文档入库边界（强制）

判据：**活文档（长期维护、是真源）入库；易朽稿（做完即弃）留 `.tmp/`。**

### 2.1 应当入库的耐久文档

- 产品 / 需求：`README.md`、`CHANGELOG.md`、`docs/PRD.md`（活文档，随需求变更同 PR 更新）。
- 架构：`docs/ARCHITECTURE.md`、`docs/adr/*.md`、`docs/API.md`。
- 协作治理：`docs/CONTRIBUTING.md`、`.claude/rules/*.md`。

### 2.2 严禁入库的易朽过程稿（已由 `.gitignore` 排除 `/.tmp/`）

- 实施计划 / 里程碑 / 路线图：`实施计划.md`、`PLAN.md`、`roadmap.md` 等。
- 过程性报告：`IMPLEMENTATION.md`、`执行报告.md`、`分析.md`、`audit-*.md` 等。
- AI 助手过程性笔记、交流稿、思路记录。

> 例：PRD 是活的需求规格 → 入库 `docs/`；实施计划易朽 → 留 `.tmp/`。文档与代码的同步要求见 `docs/CONTRIBUTING.md` 与 `.claude/rules/doc-sync.md`。

## 3. 最小提交粒度（强制）

- **验证门通过才提交**：`git commit` 前，本次变更必须已过验证门（判据见 `testing-and-quality.md` §1）。**门未全绿、或完成被实测推翻时，不得提交**；涉及实机 / 集成维度的，待用户确认验收通过再提交，先继续修到门过再提交。
- **独立可编译**：每个 commit 落地后代码都能编译 / 构建通过，不留"半截"提交。
- **只做一件事**：一个 commit 只对应一个功能点 / 一个修复 / 一次重构，无关改动不混入。
- **不混类型**：不在同一 commit 里混 `feat` / `fix` / `refactor`——各自独立提交（顺手发现的 bug 单独 `fix`，重构单独 `refactor`）。

✅ 正确（拆成独立、各自可编译、各做一件事）
```
feat(user): 用户列表支持按角色过滤
fix(auth): 修复令牌过期判断的边界问题
refactor(parser): 提取校验逻辑为独立纯函数
```

❌ 错误（一个 commit 混了功能 + 修复 + 重构）
```
feat: 加角色过滤，顺便修个登录 bug 并重构校验
```

❌ 错误（半截、单独不可编译）
```
feat(api): 加查询端点（handler 还没接，编译不过）
```

## 4. 分支与发布（强制）

**`master` 受分支保护、改动按性质分级**（[ADR-0028](../../docs/adr/0028-pr-gating-by-change-nature.md) 取代 ADR-0018 决策 1）：

- **必须走 PR**：一切**代码改动**——`feat` / `fix` / `refactor` / `perf` / `test` / `build` / `ci`，以及任何触碰 `src/**`、`*.gradle.kts`、`.github/workflows/**`、`bot-core/**`、`harness-core/**`、`template/**` 的提交。PR 的必需状态检查（`构建与测试（插件）`、`静态检查（模板 bot）`）全绿才能合并；合并方式**只允许 squash**（禁 merge / rebase，见下）。
- **可直接推 `master`（免 PR）**——仅限两类结构性低风险改动：
  1. **发版提交**：只含根 `VERSION` 的 bump + `CHANGELOG.md` 段落定稿（`chore(release): 发布 X.Y.Z`，单条提交）。
  2. **纯文档更新**：只改 Markdown（`*.md`、`docs/**`、`.claude/rules/**`），不碰代码 / 构建脚本 / CI / 配置。
  **一个提交里只要含代码文件，就退回 PR 路径**——「顺手带一点代码」不算纯文档。
- 直推后 `ci.yml` 仍会在 `master` 上跑，但**只是事后告警**，不再是合并前置条件；红了立即修。

详见 `docs/CONTRIBUTING.md` §8 与 [ADR-0028](../../docs/adr/0028-pr-gating-by-change-nature.md)（取代 ADR-0018 决策 1；其余分支保护决策不变）。

因此在本仓库工作时：

- **不要直接 `git push origin master`**；代码改动落在短生命周期分支（`fix/*`、`feat/*`、`ci/*`、`docs/*`、`chore/*` 等）后开 PR。只有**发版提交**与**纯文档更新**可直接推（见上）。
- **合并只用 squash，禁止 merge / rebase 合并**：仓库已关掉 `allow_merge_commit` 与 `allow_rebase_merge`，并开启分支保护的 **required linear history**。合并命令用 `gh pr merge <n> --squash --subject "<type>(<scope>): <中文描述> (#<n>)" --body "<总结>"`（网页端则确认默认标题）——squash 的提交标题会成为 Release 正文的条目，须写清改了什么并带 PR 编号。
- **不要本地发布版本**：不跑 `./gradlew publish` 发正式版、不手工建 GitHub Release（正文由 GitHub 从 PR 自动生成，见 [ADR-0019](../../docs/adr/0019-release-notes-from-prs.md)）。
- **发版 = 发版提交 + 打 tag**：发版提交可直接推 `master`（免 PR），里面 bump 根 `VERSION` 并把 CHANGELOG 未发布段定稿为 `## [X.Y.Z] - YYYY-MM-DD`；推送后打 `vX.Y.Z` tag，其余由 `.github/workflows/release.yml` 自动完成（校验版本一致 → 重跑验证门 → 发布到 maven.wcpe.top → 建 Release，正文由 PR 自动生成）。
- **CI 工作流里 job 的 `name:` 即分支保护的必需检查名**：改名必须同步更新仓库分支保护规则，否则 PR 会永久卡在「等待检查」。

## 5. 其他约束

- 禁止跳过 hooks（`--no-verify`）。禁止对已 push 的提交 `--amend`。
- 提交前确认未包含 `.env` / 凭据 / 大型二进制。
