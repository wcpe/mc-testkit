# ADR-0003：平台范围——Paper/Folia 后端 + Velocity/Waterfall/BungeeCord 代理

## 状态
已被 [ADR-0013](0013-spigot-backend-support.md) 取代（平台范围改为后端 Paper/Folia/Spigot + 三代理；Bukkit/Sponge 仍不列入计划）

## 背景
MC 服务端 / 代理种类繁多：后端有 Paper/Folia/Spigot/Bukkit/Sponge，代理有 Velocity/Waterfall/BungeeCord。各自「下载即跑」难度差别很大：Paper/Folia 与 Velocity/Waterfall/BungeeCord 都有官方可直接下载的产物；Spigot/Bukkit 没有官方可直接下载的产物（需 BuildTools 本地构建），Sponge 有独立的下载 / 运行方式。需要先界定本工具覆盖哪些平台。

## 决策
平台范围 = **后端 Paper / Folia**、**代理 Velocity / Waterfall / BungeeCord**，由内置下载 / 运行模块（[ADR-0001](0001-gradle-plugin-and-self-provisioning.md)）直接覆盖。**Spigot / Bukkit / Sponge 不列入计划**（不实现、不预留分支）。

## 理由
- Paper/Folia + 三代理的「下载即跑」成本最低，能把端到端链路最快立起来并以首个接入项目跑通验证。
- Spigot/Bukkit/Sponge 各自的下载 / 构建是独立的额外工程（BuildTools 本地构建、Sponge 专用渠道），与本工具目标不匹配；Paper 向下兼容 Spigot/Bukkit 插件 API，多数被测插件用 Paper 后端即可覆盖主链路。

## 后果
- 无法测「仅 Spigot/Bukkit/Sponge 才暴露」的差异（接受——这些平台不在目标内）。
- DSL 的 `platform` 维度只实现 Paper/Folia（后端）与三代理；**不为未列入计划的平台预留空壳分支 / 字段**（scope-discipline）。若将来确需某平台，再走新 ADR 评估。

## 备选方案
- **覆盖全平台（含 Sponge / Spigot / Bukkit）**：覆盖最全但周期最长、风险最大，且与目标不匹配，落选。
