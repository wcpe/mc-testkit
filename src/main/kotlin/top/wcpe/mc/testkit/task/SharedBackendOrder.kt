package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import top.wcpe.mc.testkit.topology.Topology

/**
 * 计算「共用同一后端的场景」之间应存在的顺序约束（纯函数，便于穷举测试，ADR-0026）。
 *
 * 一个后端名对应唯一的运行目录（`run-<backendName>`）与端口，故共用该后端的场景必须串行：否则它们
 * 会并发往同一目录复制服务端运行库（实测 `FileAlreadyExistsException`）、在对方服务端运行期间清空
 * 该目录，或争同一端口。**不同后端之间不加任何约束**——它们有独立目录与端口，可以安全并行。
 *
 * 返回「任务名 → 它必须排在其后的任务名」，调用侧逐条挂 `mustRunAfter` 即可。
 *
 * 规则只有一条：**组内相邻两个场景，后一个场景的全部运行期任务排在前一个场景的全部运行期任务之后**
 * （见 [scenarioRuntimeTaskNames]）。取全组合而非只牵 `prepare`，是因为踩踏不止发生在两个 prepare 之间：
 * 只按 `prepare → prepare` 串行时，`e2eA prepareE2eB` 这条命令里 B 的 prepare 仍会在 A 的服务端
 * 运行期间清空同一运行目录。全组合让「B 的任何一步都不与 A 的任何一步重叠」，是唯一没有缝隙的规则。
 * 机器人（`launch<Key>Bot`）也在运行期任务集内，故「后一个场景的机器人要等前一个场景跑完」由同一条
 * 规则覆盖——否则它会提前启动、就绪超时先于自己的服务端到来而退出。
 *
 * @param topology 已解析的拓扑（后端名、代理路由）
 * @param scenarios 按**声明顺序**排列的场景
 */
fun sharedBackendOrderEdges(
    topology: Topology,
    scenarios: List<ScenarioSpec>,
): Map<String, List<String>> {
    val edges = LinkedHashMap<String, MutableList<String>>()

    scenariosByBackend(topology, scenarios).values.forEach { group ->
        if (group.size < 2) return@forEach
        group.zipWithNext { previous, next ->
            val previousTasks = scenarioRuntimeTaskNames(previous)
            if (previousTasks.isEmpty()) return@zipWithNext
            scenarioRuntimeTaskNames(next).forEach { nextTask ->
                edges.getOrPut(nextTask) { mutableListOf() } += previousTasks
            }
        }
    }

    return edges
}

/** 后端名 → 该后端上的场景（保持声明顺序）。 */
private fun scenariosByBackend(
    topology: Topology,
    scenarios: List<ScenarioSpec>,
): Map<String, List<ScenarioSpec>> = scenarios
    .mapNotNull { scenario ->
        val backendName = backendNameOfScenario(topology, scenario) ?: return@mapNotNull null
        backendName to scenario
    }
    .groupBy({ it.first }, { it.second })

/**
 * 场景的**运行期任务名**：会写同一后端运行目录 / 占同一端口 / 依赖其进程状态的全部任务，
 * 按固定顺序返回。未注册的名字由调用侧按 `project.tasks.names` 过滤（各场景形态注册的任务不同）。
 *
 * - `prepare`：清理并重建运行目录（`cleanRunDirPreservingRuntimeCaches`）、注入插件；
 * - `launch<Key>Bot`：机器人进程，须与后端进程的时间窗对齐；
 * - 各终态入口：直连判定、一键带机器人、集群、压测，以及声明 `via` 时的经代理判定。
 *
 * 场景前 / 后钩子不列入：它们由上述任务 `dependsOn` / `finalizedBy` 带出，且可能刻意要「跨场景共存」
 * （例如共用同一个控制面进程），框架不替消费方决定它们的串行关系。
 */
fun scenarioRuntimeTaskNames(scenario: ScenarioSpec): List<String> = buildList {
    add(McTestkitTaskNames.prepare(scenario.name))
    add(McTestkitTaskNames.launchBot(scenario.name))
    add(McTestkitTaskNames.verify(scenario.name))
    add(McTestkitTaskNames.withBot(scenario.name))
    add(McTestkitTaskNames.cluster(scenario.name))
    add(McTestkitTaskNames.stress(scenario.name))
    // 经代理场景除 e2e<Key>Via<Proxy> 外仍会注册并跑直连的 e2e<Key>，两者写同一运行目录，都要在链上
    scenario.via?.let { proxy -> add(McTestkitTaskNames.verifyVia(scenario.name, proxy)) }
}

/**
 * 场景所属后端名：显式 `backend` > 经代理路由的第一个后端 > 首个声明的后端。
 *
 * 取不到任何后端时返回 null（拓扑无后端的错误由拓扑解析报出，这里不重复报）。
 *
 * **经代理的路由分支只是保守归组**：单场景经代理时框架实际起的是
 * [McTestkitTasks.resolveScenarioBackend] 选出的「显式 backend 或首个后端」，并不按代理路由挑。
 * 归组取路由的第一个后端，是为了代理路由与首个后端不一致时也能把它们串起来——把一个不会并发的
 * 组合多串一次只会慢，漏串才会踩踏。集群 / 压测场景会用到全部 `backendRefs`，同理取首个已足够保守
 * （`mustRunAfter` 只保证顺序，不改变执行内容）。
 */
fun backendNameOfScenario(topology: Topology, scenario: ScenarioSpec): String? {
    val viaProxy = scenario.via
        ?.let { proxyName -> topology.proxies.firstOrNull { it.name == proxyName } }
    return scenario.backend
        ?: viaProxy?.routes?.firstOrNull()
        ?: topology.backends.firstOrNull()?.name
}
