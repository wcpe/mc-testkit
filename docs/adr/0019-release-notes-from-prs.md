# ADR-0019：Release 正文改为「GitHub 从 PR 自动生成」（补充 ADR-0018）

## 状态
已接受

## 背景
[ADR-0018](0018-ci-release-and-pr-gating.md) 把发布改为「打 tag 触发 CI」时，选择了**取 `CHANGELOG` 该版本段作为 Release 正文**：CI 用 `.github/scripts/release_notes.py` 从 `CHANGELOG.md` 抽段、拼上标题后 `gh release create --notes-file`。

实践一版（v0.13.0）后暴露两个问题：

1. **正文与 PR 描述重复且易漂移**：Release 面向的读者想知道「这个版本相对上个版本变了什么、由哪些改动组成」，而这些信息**已经写在各个 PR 里**（标题 + 描述 + 评审）。从 CHANGELOG 抄一遍等于同一事实有两处表述，二者会随手工编辑逐渐不一致——正是 `doc-sync` 要避免的「同一事实多处真源」。
2. **CHANGELOG 缺段会卡住发布，代价不对等**：原实现在 `CHANGELOG` 无该版本段时**直接失败**。但 tag 一旦推送就不能「改完重推」（Maven 构件不可覆盖），为了一个文档段落阻断发布不成比例；且此时已不产出「空正文」（正文改由 PR 生成后与该检查无关）。

GitHub 原生提供该能力：`gh release create --generate-notes`（REST API `POST /releases/generate-notes`），按 tag 区间汇总该区间内合并的 PR 与贡献者，并**自动识别上一个 tag** 作比较基准。

## 决策
**Release 正文由 GitHub 从 PR 自动生成**，不再从 `CHANGELOG` 抽取：

1. `release.yml` 的建 Release 步骤改为 `gh release create "$TAG" --title "$TAG" --generate-notes --verify-tag`。
2. 删除 `.github/scripts/release_notes.py`（随抽取方案一并废弃，不留死代码）。
3. **`CHANGELOG` 缺该版本段由「失败」降为「告警」**：不阻断发布，但仍提示补上——定稿 `CHANGELOG` 依旧是发版流程的步骤要求（ADR-0018 决策 2 不变），只是把关位置从 CI 移到发版 PR 的模板勾选与评审。
4. **`CHANGELOG.md` 与 Release 正文分工明确**：前者是仓库内**手写的活文档**（写明「为什么这么改」，面向读代码库的人），后者是**按 PR 自动汇总的发布说明**（面向升级消费方）；两者都保留，但不互相复制。

ADR-0018 的其余决策（分支保护、PR 门禁、tag 触发、发布凭据只存 GitHub、验证门前置）**均不变**。

## 理由
- **单一真源**：改动说明的真源是 PR（它已被评审、且是合入的唯一入口）；Release 从 PR 汇总，不再维护第二份手写副本。
- **不卡发布**：文档问题不该阻断不可回退的发布动作；降为告警后，`CHANGELOG` 仍旧被流程要求着，只是不再拥有「一票否决」的代价。
- **零额外维护**：不再需要解析脚本与「拼标题」的约定；自动生成还能顺带列出贡献者与完整 compare 链接。
- **失败模式更好**：自动生成即使在大区间上也只是「列得长一点」，不会产出空正文。

## 后果
- **正面**：Release 正文自动、与 PR 一致、含贡献者与 compare 链接；`release.yml` 少一个步骤与一个脚本；文档问题不再阻断发布。
- **约束**：
  - **PR 标题即 Release 条目**：自动生成按 PR 标题罗列，故 PR 标题要写清改了什么（本仓库本就要求中文 Conventional Commits，天然满足）。
  - 自动生成的内容是「PR 列表 + 完整变更链接」，**不写「为什么」**——那仍在 `CHANGELOG` 与 PR 描述里，属有意分工。
  - 若正文需人工润色，可事后 `gh release edit vX.Y.Z --notes-file <文件>` 覆盖（只影响该 Release 正文，不影响已发布构件）。
- **不做**：
  - 不自动**分类**（如按 `feat`/`fix` 分组）——GitHub 的分类能力依赖标签，本仓库工作在 PR 标题的 type 前缀上，不额外引入标签体系。
  - 不保留 `CHANGELOG` 抽取脚本作为「备用路径」——已无引用，留着即死代码（YAGNI），需要时可从 git 历史取回。

## 备选方案
- **维持从 CHANGELOG 抽取**：单一事实两处表述、且文档问题会阻塞发布（本次要解决的两个问题）。落选。
- **两者都放（自动生成 + CHANGELOG 段拼接）**：正文冗长且重复，读者更差。落选。
- **纯手写 Release 正文**：回到 ADR-0018 已否定的「靠人记步骤」，且无法解决漂移。落选。
- **用 `--notes-start-tag` 显式指定基准 tag**：实测自动识别上一 tag 已正确（`v0.12.0...v0.13.0`），无需显式传递；留作将来 tag 命名异常时的逃生口。
