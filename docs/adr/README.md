# 架构决策记录（ADR）

记录本项目的重大架构决策：背景、决策、理由、后果与被否的备选。每条决策一页，便于后来者理解"为什么是这样"。

| 编号 | 决策 | 状态 |
|---|---|---|
| [0001](0001-gradle-plugin-and-self-provisioning.md) | 以 Gradle 插件形态构建，自实现服务端/代理的下载与运行 | 已接受 |
| [0002](0002-plugin-and-template-only.md) | 本期只做编排插件 + 脚手架模板，不发布共享桩/机器人库 | 已接受 |
| [0003](0003-p1-platform-scope.md) | 平台范围：Paper/Folia 后端 + Velocity/Waterfall/BungeeCord 代理（不含 Spigot/Bukkit/Sponge）| 已接受 |
| [0004](0004-orchestration-model.md) | 前台被测后端 + 后台代理/集群 + pid 收尾 + 环境契约固化 | 已接受 |
| [0005](0005-kotlin-language-version.md) | Kotlin 语言/API 版本锁 1.9，兼容 K1 与 K2 | 已接受 |
| [0006](0006-public-contract-conventions.md) | 对外契约命名约定（env 前缀 / 任务命名 / 控制协议 / 结果文件） | 已接受 |
| [0008](0008-cluster-and-stress-dsl.md) | 集群/压测以「扩展 scenario 块」表达，编排走「后台多后端 + 轮询结果文件」（补充 0006/0004） | 已接受 |

> 模板：状态 / 背景 / 决策 / 理由 / 后果 / 备选方案。

> **别慌通读**：ADR 有意稀少（只为重大决策写），理解现状看 [`../ARCHITECTURE.md`](../ARCHITECTURE.md)，ADR 只按需查"为什么"；被取代的归档不打扰，当前架构 = 未取代的活跃集。增长过快是滥写信号——日常变更归 PRD 状态列 + CHANGELOG。
