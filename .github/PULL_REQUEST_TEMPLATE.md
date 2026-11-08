<!--
master 受分支保护、改动按性质分级（ADR-0028）：
  - 代码改动（feat/fix/refactor 等，或触碰 src/、构建脚本、CI、bot-core/、harness-core/、template/）
    必须走本 PR；CI（`.github/workflows/ci.yml`）两个必需检查全绿方可合并；合并只用 squash。
  - 发版提交（只 bump VERSION + 定稿 CHANGELOG）与纯文档更新可直接推 master，无需 PR。
发版另经打 `vX.Y.Z` tag 触发 release.yml。

本 PR 的标题（即 squash 后的提交标题）与描述会经 GitHub 自动汇总进下次发布的 Release 正文
（ADR-0019），故标题请写清改了什么（照仓库的中文 Conventional Commits 约定即可）。
-->

## 变更说明
<!-- 这次改了什么、为什么。关联需求（PRD）或 Issue。 -->

## 类型
- [ ] feat 新功能
- [ ] fix 修复
- [ ] refactor 重构
- [ ] revert 回滚
- [ ] docs / chore / 其他

## 防漂移自检（见 docs/CONTRIBUTING.md 与 .claude/rules/）
- [ ] 方向一致：已读相关 PRD / ARCHITECTURE / ADR，未静默违背既定决策
- [ ] 范围合规：未夹带 P2/P3 能力或镀金（scope-discipline）
- [ ] 测试：新增 / 改的行为有测试，相关测试全绿（验证门通过）
- [ ] 文档同步：受影响的 PRD / ARCHITECTURE / API 已改到一致（doc-sync）
- [ ] 架构决策：如有，已写新 ADR（推翻旧决策则标记取代，不删）
- [ ] CHANGELOG：用户可见变更已记入未发布段
- [ ] 提交规范：中文 Conventional Commits、无 AI 署名

## 本 PR 是否为发版 PR
<!-- 普通情况：发版不需要 PR（发版提交可直接推 master）。仅当本 PR 同时包含代码改动又要同时发版时才勾“是”。 -->
- [ ] 不是
- [ ] 是 → 合入 master 后由维护者打 `vX.Y.Z` tag，CI 自动发布到 maven.wcpe.top 并建 GitHub Release（正文由 PR 自动生成；本 PR 无需手工发布）

## 破坏性变更 / 迁移
<!-- 如有对外 API / 配置 / 数据模型的破坏性变更，写明影响与迁移步骤；否则填"无"。 -->
