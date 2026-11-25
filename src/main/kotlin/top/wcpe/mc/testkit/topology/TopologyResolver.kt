package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitRunDirectories
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.contract.toTaskKey
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import top.wcpe.mc.testkit.dsl.ServeSpec

/**
 * 拓扑解析器（拓扑 DSL）：把 `mcTestkit { }` 的冻结声明解析为内存 [Topology]。
 *
 * 全程**纯函数**——只依赖入参（specs），不读环境变量 / 文件系统 / 全局状态，相同输入恒得相同
 * 输出，便于穷举测试。职责仅「校验 + 端口推导 + 装配模型」：不注册任务（任务自动编排）、不调用
 * 下载/运行（内置下载与运行）、不固化环境契约。
 *
 * 任何配置期不一致一律抛 [GradleException]，消息为**中文**、指出「缺什么 / 撞哪个 / 怎么补」
 * （对齐 docs/API.md §2 错误约定）。平台 P1 范围由冻结枚举类型在编译期保证，运行期不再写不可达
 * 的范围校验。
 */
object TopologyResolver {

    /** 从 `mcTestkit { }` 扩展解析拓扑（任务自动编排 编排走的真实入口）。 */
    fun resolve(extension: McTestkitExtension): Topology =
        resolve(
            extension.declaredBackends,
            extension.declaredProxies,
            extension.declaredScenarios,
            extension.declaredServes,
        )

    /**
     * 从声明列表解析拓扑（纯函数核心）。
     *
     * 顺序：① 校验节点命名 → ② 校验代理路由 → ③ 校验场景引用 → ③' 校验 serve 引用 → ④ 推导端口 →
     * ⑤ 校验端口不冲突 → ⑥ 装配 [Topology]。`serves` 默认空（向后兼容仅声明场景的旧调用）。
     */
    fun resolve(
        backends: List<BackendSpec>,
        proxies: List<ProxySpec>,
        scenarios: List<ScenarioSpec>,
        serves: List<ServeSpec> = emptyList(),
    ): Topology {
        validateNames(backends, proxies)
        validateNodeEnvironments(backends, proxies)
        validateNodeRuntimeDeclarations(proxies, backends)
        validateRoutes(backends, proxies)
        validateScenarioNames(scenarios)
        validateScenarioRefs(backends, proxies, scenarios)
        validateServeRefs(backends, proxies, serves)

        val resolvedBackends = backends.mapIndexed { index, spec ->
            ResolvedBackend(
                name = spec.name,
                platform = spec.platform,
                version = spec.version,
                javaVersion = spec.javaVersion,
                port = spec.port ?: (TopologyDefaults.BACKEND_BASE_PORT + index),
                jvmArgs = spec.jvmArgs,
                javaAgents = spec.javaAgents,
                environment = spec.environment,
                templateDirectory = spec.templateDirectoryDeclaration,
                mavenServer = spec.mavenServer,
                runDirectory = spec.runDirectory,
            )
        }
        val resolvedProxies = proxies.mapIndexed { index, spec ->
            ResolvedProxy(
                name = spec.name,
                platform = spec.platform,
                version = spec.version?.trim()?.takeIf { it.isNotEmpty() },
                javaVersion = spec.javaVersion,
                port = spec.port ?: (TopologyDefaults.PROXY_BASE_PORT + index),
                routes = spec.routes,
                plugins = spec.plugins,
                jvmArgs = spec.jvmArgs,
                javaAgents = spec.javaAgents,
                environment = spec.environment,
                templateDirectory = spec.templateDirectoryDeclaration,
                mavenServer = spec.mavenServer,
            )
        }

        validatePorts(resolvedBackends, resolvedProxies)

        return Topology(backends = resolvedBackends, proxies = resolvedProxies)
    }

    /**
     * 显式 Java 主版本必须为正数，避免把明显错误的声明拖到启动期。
     *
     * 后端的 runDirectory 若声明则不得为空白；路径本身（与工作目录重叠、后端间共用等）要按工程目录解析后才能判，
     * 在注册期校验。
     */
    private fun validateNodeRuntimeDeclarations(proxies: List<ProxySpec>, backends: List<BackendSpec>) {
        proxies.forEach { proxy ->
            if (proxy.javaVersion != null && proxy.javaVersion!! <= 0) {
                throw GradleException("mcTestkit 代理「${proxy.name}」的 javaVersion 必须为正整数。")
            }
        }
        backends.forEach { backend ->
            if (backend.javaVersion != null && backend.javaVersion!! <= 0) {
                throw GradleException("mcTestkit 后端「${backend.name}」的 javaVersion 必须为正整数。")
            }
            if (backend.runDirectory?.isBlank() == true) {
                throw GradleException(
                    "mcTestkit 后端「${backend.name}」的 runDirectory 不能为空白；不需要持久运行目录时去掉该声明即可。",
                )
            }
        }
    }

    /** 校验：后端名 / 代理名非空、各自唯一、后端名不与代理名或保留字相撞（共用运行目录命名空间）。 */
    private fun validateNames(backends: List<BackendSpec>, proxies: List<ProxySpec>) {
        backends.forEach { spec ->
            if (spec.name.isBlank()) {
                throw GradleException("mcTestkit 后端节点名不能为空白，请为 backend(...) 提供非空名称。")
            }
            // 后端运行目录是 run-<后端名>，代理目录固定 run-proxy；名为 proxy 的后端会与代理
            // 抢同一目录（代理铺目录时整删该目录），故在配置期拒绝，不等到运行期踩。
            // 判定走 isReservedBackendName（大小写不敏感 + 去首尾空白）：撞名与否由文件系统决定
            // ——Windows / macOS 上 run-Proxy 与 run-proxy 本就是同一目录。
            if (McTestkitRunDirectories.isReservedBackendName(spec.name)) {
                throw GradleException(
                    "mcTestkit 后端名「${spec.name}」是保留字，请改用其它名称。" +
                        "后端运行目录为 run-<后端名>，而代理运行目录固定为 ${McTestkitRunDirectories.PROXY_RUN_DIR_NAME}——" +
                        "该名会让二者指向同一目录，代理铺运行目录时会整删后端已备好的内容。",
                )
            }
        }
        proxies.forEach { spec ->
            if (spec.name.isBlank()) {
                throw GradleException("mcTestkit 代理节点名不能为空白，请为 proxy(...) 提供非空名称。")
            }
        }

        // 大小写不敏感：run-S1 与 run-s1 在 macOS / Windows 上指向同一目录，会互相清空对方的
        // 模板 / 配置 / 注入插件。保留字 proxy 的判定已用 ignoreCase，这里跟上同一口径。
        val duplicateBackend = firstDuplicateIgnoringCase(backends.map { it.name })
        if (duplicateBackend != null) {
            throw GradleException(
                "mcTestkit 后端名重复（大小写不敏感）：「$duplicateBackend」与另一个后端折成同一个名字，" +
                    "它们的运行目录会落在同一处并互相清空。后端名必须唯一。",
            )
        }
        val duplicateProxy = firstDuplicateIgnoringCase(proxies.map { it.name })
        if (duplicateProxy != null) {
            throw GradleException(
                "mcTestkit 代理名重复（大小写不敏感）：「$duplicateProxy」与另一个代理折成同一个名字，代理名必须唯一。",
            )
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

    /** 校验节点环境变量名与值可被子进程环境接受，且消费方不能覆盖框架保留前缀。 */
    private fun validateNodeEnvironments(backends: List<BackendSpec>, proxies: List<ProxySpec>) {
        backends.forEach { validateNodeEnvironment("后端", it.name, it.environment) }
        proxies.forEach { validateNodeEnvironment("代理", it.name, it.environment) }
    }

    private fun validateNodeEnvironment(nodeType: String, nodeName: String, environment: Map<String, String>) {
        environment.forEach { (name, value) ->
            if (name.isBlank()) {
                throw GradleException("mcTestkit $nodeType「$nodeName」的环境变量名不能为空白；请为 env(name, value) 提供非空名称。")
            }
            if ('=' in name || '\u0000' in name || '\u0000' in value) {
                throw GradleException("mcTestkit $nodeType「$nodeName」的环境变量「$name」含非法字符；名称不得含等号或空字符，值不得含空字符。")
            }
            if (name.startsWith(McTestkitEnv.PREFIX, ignoreCase = true)) {
                throw GradleException(
                    "mcTestkit $nodeType「$nodeName」的环境变量「$name」命中框架保留前缀 ${McTestkitEnv.PREFIX}；" +
                        "请改用业务自有名称，框架环境变量由 mc-testkit 权威写入。",
                )
            }
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
     * 校验：场景名本身合法且互不冲突。
     *
     * 后端名、代理名、serve 名都有完整的配置期校验（空白 / 唯一 / 折成任务名后冲突），
     * 唯独场景名此前完全没有——于是三种声明错误都在远离根因的地方炸开：
     *
     * 1. **重名**：不报中文错，而是在 `tasks.register("prepareE2eXxx")` 时抛英文
     *    `Cannot add task '...' as a task with that name already exists`；
     * 2. **折叠冲突**：`scenario("a-b")` 与 `scenario("aB")` 经 `toTaskKey()` 都折成 `AB`，同样撞任务名；
     * 3. **空名 / 含路径分隔符**：`scenario("")` 让任务名退化成 `e2e`、结果文件名退化成 `.properties`；
     *    `scenario("a/b")` 会让桩把结果写到不存在的子目录（写失败 → 只报「未写出结果」）。
     *
     * 另外同名场景共用 `<场景>.properties`，会互相覆盖判定——所以重名必须在配置期拦住。
     * 大小写不敏感：macOS / Windows 的文件系统对 `A.properties` 与 `a.properties` 视作同一个。
     */
    private fun validateScenarioNames(scenarios: List<ScenarioSpec>) {
        scenarios.forEach { scenario ->
            val name = scenario.name
            if (name.isBlank()) {
                throw GradleException(
                    "mcTestkit 场景名不能为空白：它会被折成任务名与结果文件名，空名会让两者都退化成无意义的形态。",
                )
            }
            if (name.contains('/') || name.contains('\\')) {
                throw GradleException(
                    "mcTestkit 场景「$name」含路径分隔符：它会被用作结果文件名，含分隔符会让桩写到不存在的子目录。" +
                        "请改用 kebab-case（如 buy-success）。",
                )
            }
        }
        firstDuplicateIgnoringCase(scenarios.map { it.name })?.let { name ->
            throw GradleException(
                "mcTestkit 场景名「$name」重复（大小写不敏感）：同名场景共用同一个结果文件，会互相覆盖判定。",
            )
        }
        firstDuplicate(scenarios.map { it.name.toTaskKey() })?.let { key ->
            val colliding = scenarios.filter { it.name.toTaskKey() == key }.map { it.name }
            throw GradleException(
                "mcTestkit 场景名 $colliding 折成任务名后都是「${scenarioTaskKeyPrefix(key)}」，会撞任务名。" +
                    "请改用折名后仍互不相同的名字（避免只靠大小写或分隔符区分）。",
            )
        }
        // 聚合入口名也要查重：场景「login」声明 driver 生成 e2eLoginWithDriver，
        // 与场景「loginWithDriver」的直连验证任务撞名——中缀不同也得拦下
        val generated = mutableMapOf<String, String>()
        scenarios.forEach { scenario ->
            val names = mutableListOf(McTestkitTaskNames.verify(scenario.name))
            if (scenario.botSpecs.isNotEmpty()) names += McTestkitTaskNames.withBot(scenario.name)
            if (scenario.driverSpecs.isNotEmpty()) names += McTestkitTaskNames.withDriver(scenario.name)
            names.forEach { taskName ->
                val other = generated.putIfAbsent(taskName, scenario.name)
                if (other != null && other != scenario.name) {
                    throw GradleException(
                        "mcTestkit 场景「$other」与「${scenario.name}」生成的任务名都是「$taskName」，会撞任务名。" +
                            "请改用不会与聚合入口撞名的场景名。",
                    )
                }
            }
        }
    }

    /**
     * 校验：场景引用的后端（backend / backends）与代理（via）均存在。多后端场景按类型分两支
     * （集群编排/压测编排，ADR-0008）：① 集群（声明 backends、无 stress）须有 via 且代理路由覆盖全部后端；
     * ② 压测（声明 stress）须有 ≥1 backends、botsPerServer / durationSeconds 为正，via 可选（设了则
     * 须路由覆盖）。单后端 backend 与多后端 backends 互斥。多 bot 声明（单场景多 bot）由 [validateScenarioBots] 校验。
     * 各类场景的 resultTimeoutSeconds 均须为正。
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

            // 多 bot / 复制份数声明合法性（单场景多 bot，ADR-0009）
            validateScenarioBots(scenario, isStress)

            // driver 车道声明合法性：命令必填、killTimeout >0、role 唯一；
            // 当前只支持直连单后端场景（经代理 / 集群 / 压测的驱动接线另行扩展，报中文错不静默）
            validateScenarioDrivers(scenario, isStress, hasBackends)

            if (scenario.resultTimeoutSeconds <= 0) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」的 resultTimeoutSeconds 必须 >0（等桩写出结果文件的上限秒数）。",
                )
            }

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

            // 单后端场景设了 via，则该代理须路由到这个后端。
            //
            // 此前这条只在校验集群（backends 覆盖全部）与单后端 serve 时做，单后端场景完全没查：
            // proxy("wf") { routesTo("s2") } + backend("s1") + scenario { backend = "s1"; via = "wf" }
            // 配置期静默通过，运行期 bot 被代理以
            // 「Could not connect to a default or fallback server」反复踢掉直到超时——
            // 报错与真实原因（路由没配）毫无关系，只能靠人对着两份声明比对。
            if (!hasBackends && viaRef != null) {
                val target = scenario.backend ?: backends.firstOrNull()?.name
                if (target != null) {
                    val proxy = proxies.first { it.name == viaRef }
                    if (target !in proxy.routes) {
                        throw GradleException(
                            "mcTestkit 场景「${scenario.name}」的代理「$viaRef」未路由到后端「$target」；" +
                                "该后端起服后 bot 会被代理以「找不到可用的默认服 / 回退服」踢掉。" +
                                "请让 proxy(\"$viaRef\") routesTo(\"$target\")，或去掉本场景的 via。",
                        )
                    }
                }
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
     * 校验单场景的 bot 声明（单场景多 bot，ADR-0009）：① 每个 bot 的 `count >= 1`；② 同场景声明 ≥2 个 bot 时
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

    /**
     * 校验 driver 车道声明（driver 车道：编排像管 bot 一样管真客户端 / 外部驱动进程）：
     * ① command 必填非空白；② killTimeoutSeconds >0；③ 多 driver 的 role 唯一；
     * ④ 当前只支持**直连单后端**场景——经代理 / 集群 / 压测的驱动接线另行扩展，
     * 在配置期报中文错，不静默忽略声明。一律中文报错。
     */
    private fun validateScenarioDrivers(scenario: ScenarioSpec, isStress: Boolean, hasBackends: Boolean) {
        val drivers = scenario.driverSpecs
        if (drivers.isEmpty()) return
        drivers.forEach { driver ->
            if (driver.command.isNullOrBlank()) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」的 driver${driver.role?.let { "「$it」" } ?: ""} 缺少 command；" +
                        "请给出可执行命令（如 command = \"node\"）。",
                )
            }
            if (driver.killTimeoutSeconds <= 0) {
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」的 driver${driver.role?.let { "「$it」" } ?: ""} 的 killTimeoutSeconds 必须 >0" +
                        "（温和收尾等待上限，超时升级强杀）。",
                )
            }
        }
        val duplicateRole = firstDuplicate(drivers.mapNotNull { it.role })
        if (duplicateRole != null) {
            throw GradleException(
                "mcTestkit 场景「${scenario.name}」的 driver 角色名重复：「$duplicateRole」声明了多次，多 driver 的角色名必须唯一。",
            )
        }
        when {
            isStress -> throw GradleException(
                "mcTestkit 压测场景「${scenario.name}」暂不支持 driver 车道（驱动进程的生命周期与钉服 bot 编排不同，另行扩展）；请去掉 driver 声明。",
            )
            hasBackends -> throw GradleException(
                "mcTestkit 集群场景「${scenario.name}」暂不支持 driver 车道（多后端驱动编排另行扩展）；请去掉 driver 声明。",
            )
            scenario.via != null -> throw GradleException(
                "mcTestkit 场景「${scenario.name}」暂不支持经代理的 driver 车道（另行扩展）；请去掉 via 或 driver 声明。",
            )
        }
    }

    /**
     * 校验持久手测（serve）声明（持久手测 serve，ADR-0011）：① serve 名非空；② serve 名唯一、且折成任务名
     * `serve<Key>` 后不相撞；③ 显式 `backend` 引用存在，无显式 backend 时拓扑须至少有一个后端可默认；
     * ④ `via` 代理存在，且设了 via 则该代理须路由到 serve 的目标后端。一律中文报错。
     */
    private fun validateServeRefs(
        backends: List<BackendSpec>,
        proxies: List<ProxySpec>,
        serves: List<ServeSpec>,
    ) {
        val backendNames = backends.map { it.name }.toSet()
        val proxyNames = proxies.map { it.name }.toSet()

        serves.forEach { serve ->
            if (serve.name.isBlank()) {
                throw GradleException("mcTestkit serve 名不能为空白，请为 serve(...) 提供非空名称。")
            }
        }
        val duplicateServe = firstDuplicate(serves.map { it.name })
        if (duplicateServe != null) {
            throw GradleException("mcTestkit serve 名重复：「$duplicateServe」声明了多次，serve 名必须唯一。")
        }
        // serve 名折成任务名后不得相撞（如 "dev" 与 "Dev" 都折成 serveDev）
        val duplicateKey = firstDuplicate(serves.map { it.name.toTaskKey() })
        if (duplicateKey != null) {
            throw GradleException(
                "mcTestkit serve 名折成任务名后冲突：多个 serve 折成同一 <Key>「$duplicateKey」（serve<Key> 会撞名）；请改用不同名称。",
            )
        }

        serves.forEach { serve ->
            // serve 可选 bot 的多 bot 声明合法性（serve 人机混场，规则同场景 单场景多 bot）
            validateServeBots(serve)
            val hasBackends = serve.backendRefs.isNotEmpty()
            // 单后端 backend 与多后端 backends 互斥（集群 serve 只用 backends）
            if (hasBackends && serve.backend != null) {
                throw GradleException(
                    "mcTestkit serve「${serve.name}」不可同时用 backend = 与 backends(...)；集群 serve 请只用 backends(...)。",
                )
            }
            if (hasBackends) {
                validateClusterServe(serve, backendNames, proxyNames, proxies)
            } else {
                validateSingleServe(serve, backendNames, proxyNames, proxies, backends)
            }
        }
    }

    /** 校验 serve 的可选 bot 声明（serve 人机混场）：每个 `count >= 1`；多 bot 须各有唯一 `role`（匿名只能一个）。 */
    private fun validateServeBots(serve: ServeSpec) {
        val bots = serve.botSpecs
        bots.forEach { bot ->
            if (bot.count < 1) {
                throw GradleException(
                    "mcTestkit serve「${serve.name}」的 bot${bot.role?.let { "（角色 $it）" } ?: ""} count 必须 >=1，当前为 ${bot.count}。",
                )
            }
        }
        if (bots.size > 1) {
            if (bots.any { it.role == null }) {
                throw GradleException(
                    "mcTestkit serve「${serve.name}」声明了多个 bot，每个须有唯一角色名 bot(\"<角色>\") { }；匿名 bot { } 只能声明一个。",
                )
            }
            val duplicateRole = firstDuplicate(bots.mapNotNull { it.role })
            if (duplicateRole != null) {
                throw GradleException(
                    "mcTestkit serve「${serve.name}」的 bot 角色名重复：「$duplicateRole」声明了多次，多 bot 的角色名必须唯一。",
                )
            }
        }
    }

    /** 集群 serve：各后端存在、须有 via、via 路由覆盖全部后端。 */
    private fun validateClusterServe(
        serve: ServeSpec,
        backendNames: Set<String>,
        proxyNames: Set<String>,
        proxies: List<ProxySpec>,
    ) {
        val missing = serve.backendRefs.firstOrNull { it !in backendNames }
        if (missing != null) {
            throw GradleException(
                "mcTestkit 集群 serve「${serve.name}」引用的后端「$missing」不存在；请检查 backends(...) 的名称或先声明该后端。",
            )
        }
        val viaRef = serve.via
            ?: throw GradleException(
                "mcTestkit 集群 serve「${serve.name}」必须经代理（请声明 via = \"<代理名>\"）——真人经代理 /server 在后端间切换需要代理。",
            )
        if (viaRef !in proxyNames) {
            throw GradleException(
                "mcTestkit 集群 serve「${serve.name}」引用的代理「$viaRef」不存在；请检查 via = 的名称或先声明该代理。",
            )
        }
        val proxy = proxies.first { it.name == viaRef }
        val notRouted = serve.backendRefs.firstOrNull { it !in proxy.routes }
        if (notRouted != null) {
            throw GradleException(
                "mcTestkit 集群 serve「${serve.name}」的代理「$viaRef」未路由到后端「$notRouted」；" +
                    "请让 proxy(\"$viaRef\") routesTo(...) 覆盖全部后端。",
            )
        }
    }

    /** 单后端 serve（持久手测 serve）：backend 引用存在（或有可默认后端）、via 存在且路由到目标后端。 */
    private fun validateSingleServe(
        serve: ServeSpec,
        backendNames: Set<String>,
        proxyNames: Set<String>,
        proxies: List<ProxySpec>,
        backends: List<BackendSpec>,
    ) {
        val backendRef = serve.backend
        if (backendRef != null && backendRef !in backendNames) {
            throw GradleException(
                "mcTestkit serve「${serve.name}」引用的后端「$backendRef」不存在；请检查 backend = 的名称或先声明该后端。",
            )
        }
        if (backendRef == null && backends.isEmpty()) {
            throw GradleException(
                "mcTestkit serve「${serve.name}」无可用后端：请用 backend(\"...\") 声明至少一个后端，或用 serve 的 backend = 指定。",
            )
        }

        val viaRef = serve.via
        if (viaRef != null && viaRef !in proxyNames) {
            throw GradleException(
                "mcTestkit serve「${serve.name}」引用的代理「$viaRef」不存在；请检查 via = 的名称或先声明该代理。",
            )
        }
        // 设了 via，则该代理须路由到 serve 的目标后端（显式 backend 或默认首个）
        if (viaRef != null) {
            val targetBackend = backendRef ?: backends.first().name
            val proxy = proxies.first { it.name == viaRef }
            if (targetBackend !in proxy.routes) {
                throw GradleException(
                    "mcTestkit serve「${serve.name}」的代理「$viaRef」未路由到后端「$targetBackend」；" +
                        "请让 proxy(\"$viaRef\") routesTo(\"$targetBackend\")。",
                )
            }
        }
    }

    /** 校验：所有已解析端口（后端 + 代理）全局不冲突（含显式与推导相撞的情形）。 */
    private fun validatePorts(backends: List<ResolvedBackend>, proxies: List<ResolvedProxy>) {
        // 先查范围：不查的话 port = 70000 / 0 / -1 配置期全部放行，直到起服时 bind 失败——
        // 而 PortGuard 的 canBind 会吞掉 IllegalArgumentException，最终被翻译成
        // 「端口 70000 已被占用」，给用户完全错误的排查方向。
        (backends.map { "后端「${it.name}」" to it.port } + proxies.map { "代理「${it.name}」" to it.port })
            .forEach { (node, port) ->
                if (port !in VALID_PORT_RANGE) {
                    throw GradleException(
                        "mcTestkit $node 的 port=$port 不在合法范围 ${VALID_PORT_RANGE.first}..${VALID_PORT_RANGE.last}。" +
                            "端口须为 1..65535 的整数；0 与负数无法 bind，超出 65535 同样无效。",
                    )
                }
            }
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

    /** 合法 TCP 端口范围（1..65535）；0 与负数无法 bind，超出 65535 无效。 */
    private val VALID_PORT_RANGE = 1..65535

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

    /**
     * 大小写不敏感版的 [firstDuplicate]。
     *
     * 后端 / 代理名与场景名都要用它：macOS 与 Windows 的文件系统对 `A` 与 `a` 视作同一个，
     * 而 `run-A` / `run-a`（或 `A.properties` / `a.properties`）在那些平台上会指向同一份文件，
     * 随后互相清空模板 / 配置 / 写坏判定。保留字 `proxy` 的判定已用 `trim()+ignoreCase`，此处跟上。
     */
    private fun firstDuplicateIgnoringCase(values: List<String>): String? {
        val seen = HashMap<String, String>()
        values.forEach { value ->
            val normalized = value.trim().lowercase()
            val previous = seen.putIfAbsent(normalized, value)
            if (previous != null) {
                return value
            }
        }
        return null
    }

    /** 场景折名在任务名里的实际形态（`prepareE2e` + key），用于报错时说清会撞哪个名字。 */
    private fun scenarioTaskKeyPrefix(key: String): String = "prepareE2e$key"
}
