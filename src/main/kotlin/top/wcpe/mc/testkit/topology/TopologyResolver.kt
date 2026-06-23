package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec

/**
 * 拓扑解析器（FR-03）：把 `mcTestkit { }` 的冻结声明解析为内存 [Topology]。
 *
 * 全程**纯函数**——只依赖入参（specs），不读环境变量 / 文件系统 / 全局状态，相同输入恒得相同
 * 输出，便于穷举测试。职责仅「校验 + 端口推导 + 装配模型」：不注册任务（FR-04）、不调用
 * 下载/运行（FR-02）、不固化环境契约（FR-05）。
 *
 * 任何配置期不一致一律抛 [GradleException]，消息为**中文**、指出「缺什么 / 撞哪个 / 怎么补」
 * （对齐 docs/API.md §2 错误约定）。平台 P1 范围由冻结枚举类型在编译期保证，运行期不再写不可达
 * 的范围校验。
 */
object TopologyResolver {

    /** 从 `mcTestkit { }` 扩展解析拓扑（FR-04 编排走的真实入口）。 */
    fun resolve(extension: McTestkitExtension): Topology =
        resolve(
            extension.declaredBackends,
            extension.declaredProxies,
            extension.declaredScenarios,
        )

    /**
     * 从声明列表解析拓扑（纯函数核心）。
     *
     * 顺序：① 校验节点命名 → ② 校验代理路由 → ③ 校验场景引用 → ④ 推导端口 →
     * ⑤ 校验端口不冲突 → ⑥ 装配 [Topology]。
     */
    fun resolve(
        backends: List<BackendSpec>,
        proxies: List<ProxySpec>,
        scenarios: List<ScenarioSpec>,
    ): Topology {
        validateNames(backends, proxies)
        validateRoutes(backends, proxies)
        validateScenarioRefs(backends, proxies, scenarios)

        val resolvedBackends = backends.mapIndexed { index, spec ->
            ResolvedBackend(
                name = spec.name,
                platform = spec.platform,
                version = spec.version,
                port = spec.port ?: (TopologyDefaults.BACKEND_BASE_PORT + index),
            )
        }
        val resolvedProxies = proxies.mapIndexed { index, spec ->
            ResolvedProxy(
                name = spec.name,
                platform = spec.platform,
                port = spec.port ?: (TopologyDefaults.PROXY_BASE_PORT + index),
                routes = spec.routes,
            )
        }

        validatePorts(resolvedBackends, resolvedProxies)

        return Topology(backends = resolvedBackends, proxies = resolvedProxies)
    }

    /** 校验：后端名 / 代理名非空、各自唯一、且后端名与代理名不相撞（二者共用 routesTo / via 引用命名空间）。 */
    private fun validateNames(backends: List<BackendSpec>, proxies: List<ProxySpec>) {
        backends.forEach { spec ->
            if (spec.name.isBlank()) {
                throw GradleException("mcTestkit 后端节点名不能为空白，请为 backend(...) 提供非空名称。")
            }
        }
        proxies.forEach { spec ->
            if (spec.name.isBlank()) {
                throw GradleException("mcTestkit 代理节点名不能为空白，请为 proxy(...) 提供非空名称。")
            }
        }

        val duplicateBackend = firstDuplicate(backends.map { it.name })
        if (duplicateBackend != null) {
            throw GradleException("mcTestkit 后端名重复：「$duplicateBackend」声明了多次，后端名必须唯一。")
        }
        val duplicateProxy = firstDuplicate(proxies.map { it.name })
        if (duplicateProxy != null) {
            throw GradleException("mcTestkit 代理名重复：「$duplicateProxy」声明了多次，代理名必须唯一。")
        }

        val backendNames = backends.map { it.name }.toSet()
        val collision = proxies.map { it.name }.firstOrNull { it in backendNames }
        if (collision != null) {
            throw GradleException(
                "mcTestkit 节点名冲突：「$collision」同时被用作后端名与代理名；" +
                    "后端与代理共用引用命名空间，请改用不同名称。",
            )
        }
    }

    /** 校验：每个代理至少路由到一个后端，且路由目标后端均存在。 */
    private fun validateRoutes(backends: List<BackendSpec>, proxies: List<ProxySpec>) {
        val backendNames = backends.map { it.name }.toSet()
        proxies.forEach { proxy ->
            if (proxy.routes.isEmpty()) {
                throw GradleException(
                    "mcTestkit 代理「${proxy.name}」未声明任何路由；请用 routesTo(\"<后端名>\") 指定其转发目标。",
                )
            }
            val missing = proxy.routes.firstOrNull { it !in backendNames }
            if (missing != null) {
                throw GradleException(
                    "mcTestkit 代理「${proxy.name}」路由目标后端「$missing」不存在；" +
                        "请检查 routesTo(...) 的名称或先用 backend(\"$missing\") 声明该后端。",
                )
            }
        }
    }

    /**
     * 校验：场景引用的后端（backend / backends）与代理（via）均存在。多后端场景按类型分两支
     * （FR-10/11，ADR-0008）：① 集群（声明 backends、无 stress）须有 via 且代理路由覆盖全部后端；
     * ② 压测（声明 stress）须有 ≥1 backends、botsPerServer / durationSeconds 为正，via 可选（设了则
     * 须路由覆盖）。单后端 backend 与多后端 backends 互斥。多 bot 声明（FR-16）由 [validateScenarioBots] 校验。
     */
    private fun validateScenarioRefs(
        backends: List<BackendSpec>,
        proxies: List<ProxySpec>,
        scenarios: List<ScenarioSpec>,
    ) {
        val backendNames = backends.map { it.name }.toSet()
        val proxyNames = proxies.map { it.name }.toSet()
        scenarios.forEach { scenario ->
            val hasBackends = scenario.backendRefs.isNotEmpty()
            val isStress = scenario.stressSpec != null
            val isCluster = hasBackends && !isStress

            // 多 bot / 复制份数声明合法性（FR-16，ADR-0009）
            validateScenarioBots(scenario, isStress)

            // 单后端 backend 与多后端 backends 互斥（集群 / 压测都用 backends）
            if (hasBackends && scenario.backend != null) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」不可同时用 backend = 与 backends(...)；" +
                        "集群 / 压测场景请只用 backends(...)。",
                )
            }

            // 压测场景须用 backends 声明 ≥1 后端，且规模 / 时长为正
            if (isStress) {
                if (!hasBackends) {
                    throw GradleException(
                        "mcTestkit 压测场景「${scenario.name}」须用 backends(...) 声明至少一个后端" +
                            "（每服起 stress.botsPerServer 个 bot 钉服施压）。",
                    )
                }
                val stress = scenario.stressSpec!!
                if (stress.botsPerServer <= 0) {
                    throw GradleException(
                        "mcTestkit 压测场景「${scenario.name}」的 stress.botsPerServer 必须 >0（每服 bot 进程数）。",
                    )
                }
                if (stress.durationSeconds <= 0) {
                    throw GradleException(
                        "mcTestkit 压测场景「${scenario.name}」的 stress.durationSeconds 必须 >0（持续压测秒数）。",
                    )
                }
            }

            // 单后端引用存在
            val backendRef = scenario.backend
            if (backendRef != null && backendRef !in backendNames) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」引用的后端「$backendRef」不存在；" +
                        "请检查 backend = 的名称或先声明该后端。",
                )
            }

            // 多后端（集群 / 压测）各后端均存在
            if (hasBackends) {
                val missing = scenario.backendRefs.firstOrNull { it !in backendNames }
                if (missing != null) {
                    throw GradleException(
                        "mcTestkit 多后端场景「${scenario.name}」引用的后端「$missing」不存在；" +
                            "请检查 backends(...) 的名称或先声明该后端。",
                    )
                }
            }

            // 代理 via 存在
            val viaRef = scenario.via
            if (viaRef != null && viaRef !in proxyNames) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」引用的代理「$viaRef」不存在；" +
                        "请检查 via = 的名称或先声明该代理。",
                )
            }

            // 集群必须经代理（压测的 via 可选）
            if (isCluster && viaRef == null) {
                throw GradleException(
                    "mcTestkit 集群场景「${scenario.name}」必须经代理（请声明 via = \"<代理名>\"）——" +
                        "bot 经代理在后端间 /server 切换需要代理。",
                )
            }

            // via 若设，须路由覆盖全部 backends（集群必经此校验；压测仅在设了 via 时）
            if (hasBackends && viaRef != null) {
                val proxy = proxies.first { it.name == viaRef }
                val notRouted = scenario.backendRefs.firstOrNull { it !in proxy.routes }
                if (notRouted != null) {
                    throw GradleException(
                        "mcTestkit 多后端场景「${scenario.name}」的代理「$viaRef」未路由到后端「$notRouted」；" +
                            "请让 proxy(\"$viaRef\") routesTo(...) 覆盖全部后端。",
                    )
                }
            }
        }
    }

    /**
     * 校验单场景的 bot 声明（FR-16，ADR-0009）：① 每个 bot 的 `count >= 1`；② 同场景声明 ≥2 个 bot 时
     * 每个须有**唯一** `role`（匿名 `bot { }` 只能一个，否则多进程无法区分）；③ 压测场景禁 `count>1` /
     * 多 bot（规模用 `stress { botsPerServer }` 表达，避免与「同质钉服」语义混淆）。一律中文报错。
     */
    private fun validateScenarioBots(scenario: ScenarioSpec, isStress: Boolean) {
        val bots = scenario.botSpecs

        bots.forEach { bot ->
            if (bot.count < 1) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」的 bot${bot.role?.let { "（角色 $it）" } ?: ""} count 必须 >=1，" +
                        "当前为 ${bot.count}。",
                )
            }
        }

        // 压测：规模由 botsPerServer 表达，bot 不支持 count / 多 bot（ADR-0009 与压测划清边界）
        if (isStress && (bots.size > 1 || bots.any { it.count > 1 })) {
            throw GradleException(
                "mcTestkit 压测场景「${scenario.name}」的 bot 不支持 count / 多 bot（规模请用 stress { botsPerServer } 表达）。",
            )
        }

        // 多 bot：每个须有唯一 role（异质角色或便于多进程区分）
        if (bots.size > 1) {
            val anonymous = bots.count { it.role == null }
            if (anonymous > 0) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」声明了多个 bot，每个须有唯一角色名 bot(\"<角色>\") { }；" +
                        "匿名 bot { } 只能声明一个。",
                )
            }
            val duplicateRole = firstDuplicate(bots.mapNotNull { it.role })
            if (duplicateRole != null) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」的 bot 角色名重复：「$duplicateRole」声明了多次，多 bot 的角色名必须唯一。",
                )
            }
        }
    }

    /** 校验：所有已解析端口（后端 + 代理）全局不冲突（含显式与推导相撞的情形）。 */
    private fun validatePorts(backends: List<ResolvedBackend>, proxies: List<ResolvedProxy>) {
        // 端口 → 占用它的节点名（便于在冲突消息里指出是谁撞谁）
        val owners = LinkedHashMap<Int, String>()
        (backends.map { it.name to it.port } + proxies.map { it.name to it.port }).forEach { (node, port) ->
            val existing = owners[port]
            if (existing != null) {
                throw GradleException(
                    "mcTestkit 端口冲突：端口 $port 同时被「$existing」与「$node」占用；" +
                        "请为其一显式指定不同的 port。",
                )
            }
            owners[port] = node
        }
    }

    /** 返回首个重复出现的元素（保持首次重复的发现顺序），无重复返回 null。 */
    private fun firstDuplicate(values: List<String>): String? {
        val seen = HashSet<String>()
        values.forEach { value ->
            if (!seen.add(value)) {
                return value
            }
        }
        return null
    }
}
