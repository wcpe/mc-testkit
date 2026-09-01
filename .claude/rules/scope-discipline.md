# 范围纪律（防范围漂移 / 镀金）

> 依据 `docs/PRD.md` 的分期。**只做当前阶段该做的，不提前做、不顺手做。**经用户确认，FR-22 是当前授权范围。

## 1. 第一期（MVP）只做
- FR-01 Gradle 插件骨架（`top.wcpe.mc-testkit` / `mcTestkit { }` DSL / 发布 maven.wcpe.top）
- FR-02 内置下载与运行：自实现下载并运行 Paper/Folia/Spigot 后端与 Velocity/Waterfall/BungeeCord 代理（Spigot 见 ADR-0013）
- FR-03 声明式拓扑 DSL（单后端 / 代理 + N 后端）
- FR-04 任务自动编排（prepare / 启动机器人 / runServer / proxy / cluster / verify / 缓存回写）
- FR-05 固化环境契约（机器人协议版本固定 / paper-global 代理在线模式 / BungeeCord 后端配置 / 依赖数据源·Redis 注入校验）
- FR-06 机器人驱动 + 结果判定（启动机器人 / 控制协议 / 读结果判 PASS·FAIL）
- FR-07 `template/` 脚手架（桩骨架 + 机器人内核 + 示例场景 + 复制说明）
- FR-08 以首个接入项目作消费者跑通（smoke + 经 Waterfall 代理购买）

> 此清单是"该做什么"的权威边界，凡不在其中的能力都属越界。

## 2. MVP 严禁出现（属后续阶段）
- Bukkit / Sponge 后端支持（不列入计划，见 ADR-0013）——含任何 `platform = bukkit/sponge` 的实现或占位分支。**Spigot 已纳入范围**（ADR-0013 取代 ADR-0003 的平台范围条款），不再是越界能力。
- 可发布的共享 Kotlin 桩基类库 / npm 机器人包（FR-09）。
- 真实游戏客户端（Fabric/Forge mod 客户端）驱动。

一旦在代码 / DSL / 文档里看到上述能力的提前实现或占位 → **删除，或停下来问**，不得镀金。

## 3. 不为未来预留空壳
- 不写"以后可能用"的抽象、配置项、接口、字段。需要时再加。
- 后续平台能力到时再加：`platform` 维度可保持可扩展，但**当前只实现 Paper/Folia/Spigot + 三代理**，不预置其他平台空壳。新增 / 移除平台须走 ADR（Spigot 的先例见 ADR-0013）。

## 4. 越界先问
- 若某任务看起来需要某个后续阶段能力才能完成 → **停止并向用户确认**，不自行扩大范围。
- 简洁方案优先：实现远多于必要（如 200 行 vs 50 行）时重写。资深工程师会觉得过度复杂的，就是过度。
