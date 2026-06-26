package top.wcpe.mc.testkit.task

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import top.wcpe.mc.testkit.bot.BotConnection
import top.wcpe.mc.testkit.bot.BotLauncher
import top.wcpe.mc.testkit.bot.BotProcessContext
import top.wcpe.mc.testkit.bot.botPidFile
import top.wcpe.mc.testkit.bot.stopProcessByPidFile
import top.wcpe.mc.testkit.config.BackendBungeeCordConfig
import top.wcpe.mc.testkit.config.BackendVelocityConfig
import top.wcpe.mc.testkit.config.ProxyProtocolVersion
import top.wcpe.mc.testkit.config.ServerProperties
import top.wcpe.mc.testkit.config.StressProxyBinding
import top.wcpe.mc.testkit.config.VELOCITY_FORWARDING_SECRET_FILE
import top.wcpe.mc.testkit.config.bungeeClusterProxyConfigYml
import top.wcpe.mc.testkit.config.bungeeProxyConfigYml
import top.wcpe.mc.testkit.config.bungeeStressProxyConfigYml
import top.wcpe.mc.testkit.config.velocityProxyConfigToml
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.BotSpec
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxyPlatform
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import top.wcpe.mc.testkit.dsl.StressSpec
import top.wcpe.mc.testkit.provision.ServerJarProvisioner
import top.wcpe.mc.testkit.provision.ServerLauncher
import top.wcpe.mc.testkit.topology.ResolvedBackend
import top.wcpe.mc.testkit.topology.ResolvedProxy
import top.wcpe.mc.testkit.topology.Topology
import top.wcpe.mc.testkit.topology.TopologyResolver
import top.wcpe.mc.testkit.verify.ResultReader
import java.io.File
import java.util.concurrent.TimeUnit

/** Gradle 任务分组名（生成的 e2e 任务都归此组，`./gradlew tasks` 下成组展示）。 */
private const val TASK_GROUP = "mc-testkit e2e"

/** Gradle 属性名：覆盖机器人目录（缺省 [RunLayout.DEFAULT_BOT_DIR]）。 */
private const val BOT_DIR_PROPERTY = "mcTestkit.botDir"

/** 等结果文件写出的上限（秒）：超时仍无结果即视为桩未正常收尾 / 启动失败。 */
private const val BACKEND_WAIT_TIMEOUT_SECONDS = 600L

/** 结果文件写出后，给后端 JVM 优雅自停的窗口（秒）；到时仍未退则强杀（不空等到上限）。 */
private const val BACKEND_SELF_STOP_GRACE_SECONDS = 30L

/**
 * 任务编排装配入口（FR-04 整合器）。
 *
 * 在 [McTestkitPlugin][top.wcpe.mc.testkit.McTestkitPlugin] 的 `apply()` 末尾、`afterEvaluate` 里调用：
 * 先经 [TopologyResolver.resolve] 复用 FR-03 配置期校验（无环 / 命名 / 路由 / 端口 / 场景引用，
 * 失败抛**中文** `GradleException`），再按 `mcTestkit { }` 声明**数据驱动**注册任务（命名严格按
 * [McTestkitTaskNames]）。任务体的副作用（下载 / 起进程 / 判定）全放 `doLast`，**配置期只注册不执行**
 * （TestKit 不联网 / 不起进程仍可过任务注册与 `help`；真实跑通属 FR-08 实机维度）。
 */
object McTestkitTasks {

    /** 装配全部 e2e 任务。 */
    fun register(project: Project, extension: McTestkitExtension) {
        // ① 复用 FR-03 解析 + 配置期校验（失败即抛中文 GradleException，阻断后续注册）
        val topology = TopologyResolver.resolve(extension)

        // ①' 多 bot 展开后 key/username 唯一性校验（FR-16）：TopologyResolver 只查 role 唯一，
        // 但 role 不同的两 bot 展开后仍可能撞 key（如 bot("w"){count=2} 与 bot("w-1")），故在此用
        // BotProcessPlanner 展开真源查重，杜绝 pid 互相覆盖致收尾漏杀残留。
        extension.declaredScenarios.forEach { scenario ->
            BotProcessPlanner.firstConflict(scenario.name, scenario.botSpecs)?.let { conflict ->
                throw GradleException(
                    "mcTestkit 场景「${scenario.name}」多 bot 展开后$conflict；" +
                        "请改用唯一的角色名 / 用户名 / count 组合（注意 count>1 会派生「<角色>-<序号>」，勿与其它角色撞名）。",
                )
            }
        }

        // ② 固定名任务（npm 安装 / 缓存回写 / 清缓存）
        registerFixedTasks(project)

        // ③ 数据驱动：每个场景注册 prepare / e2e（+ bot 时 launch / withBot；+ via 时经代理任务）
        extension.declaredScenarios.forEach { scenario ->
            registerScenarioTasks(project, extension, topology, scenario)
        }
    }

    /** 注册三个固定名任务（[McTestkitTaskNames] 常量）。 */
    private fun registerFixedTasks(project: Project) {
        val layout = layoutOf(project)

        registerExec(project, McTestkitTaskNames.NPM_INSTALL_BOT) { task ->
            task.group = TASK_GROUP
            task.description = "安装机器人 mineflayer 依赖（npm install）"
            // 工作目录 / 命令在配置期定形（不执行 npm）；真正安装在任务执行期
            val botDir = layout.botDir(project.findProperty(BOT_DIR_PROPERTY)?.toString())
            task.workingDir = botDir
            task.commandLine(npmExecutable(), "install", "--no-audit", "--no-fund")
        }

        registerTask(project, McTestkitTaskNames.SYNC_RUNTIME_CACHE) { task ->
            task.group = TASK_GROUP
            task.description = "将运行库 / 下载缓存回写到持久缓存目录"
            task.doLast {
                val runDir = layout.runDir
                val cacheDir = layout.persistentServerBaseDir
                cacheDir.mkdirs()
                RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES.forEach { name ->
                    val src = File(runDir, name)
                    if (src.exists()) {
                        src.copyRecursively(File(cacheDir, name), overwrite = true)
                    }
                }
                project.logger.lifecycle("[mc-testkit] 已更新持久缓存：${cacheDir.absolutePath}")
            }
        }

        registerTask(project, McTestkitTaskNames.PURGE_RUNTIME_CACHE) { task ->
            task.group = TASK_GROUP
            task.description = "清空持久缓存目录（下次运行重新填充）"
            task.doLast {
                val cacheDir = layout.persistentServerBaseDir
                cacheDir.deleteRecursively()
                project.logger.lifecycle("[mc-testkit] 已清空持久缓存：${cacheDir.absolutePath}")
            }
        }
    }

    /** 为单个场景注册其全部任务。 */
    private fun registerScenarioTasks(
        project: Project,
        extension: McTestkitExtension,
        topology: Topology,
        scenario: ScenarioSpec,
    ) {
        // 压测场景（声明 stress）走 N 服 × M bot 钉服编排（FR-11，ADR-0008）：不生成单后端 / 集群任务
        scenario.stressSpec?.let { stress ->
            val stressBackends = scenario.backendRefs.map { name ->
                topology.backends.first { it.name == name } // 已由 TopologyResolver 校验存在
            }
            val proxy = scenario.via?.let { via -> topology.proxies.first { it.name == via } } // 压测 via 可选
            // Velocity 单端口、无「N-listener 一端口对一后端」钉服能力（见 ADR-0010）：压测经 Velocity 配置期即报错，不静默
            if (proxy?.platform == ProxyPlatform.VELOCITY) {
                throw GradleException(
                    "mcTestkit 压测场景「${scenario.name}」不支持经 Velocity 代理：Velocity 是单端口代理，无法做" +
                        "「N-listener 一端口对一后端」钉服。请改用 Waterfall / BungeeCord 代理，或去掉 via 直连后端。",
                )
            }
            registerStressTask(project, extension, scenario, stress, stressBackends, proxy)
            return
        }

        // 集群场景（声明 backends、无 stress）走多后端切换编排（FR-10，ADR-0008）：不生成单后端任务
        if (scenario.backendRefs.isNotEmpty()) {
            val clusterBackends = scenario.backendRefs.map { name ->
                topology.backends.first { it.name == name } // 已由 TopologyResolver 校验存在
            }
            val proxy = topology.proxies.first { it.name == scenario.via } // 集群必有 via 且已校验存在
            registerClusterTask(project, extension, scenario, clusterBackends, proxy)
            return
        }

        val backend = resolveScenarioBackend(topology, scenario)
        val prepareName = McTestkitTaskNames.prepare(scenario.name)

        // prepare：准备运行目录（清理保留缓存、写 eula、最小 server.properties、注入插件 jar）
        val prepare = registerTask(project, prepareName) { task ->
            task.group = TASK_GROUP
            task.description = "准备场景 ${scenario.name} 的运行目录（注入插件、写配置）"
            task.doLast {
                prepareRunDirectory(project, extension, backend, layoutOf(project).runDir)
            }
        }

        // e2e（直连后端）：prepare → 前台起后端（自停 waitFor）→ 读结果文件判定
        // bot 必须先于后端跑完连上，故先注册 launch（若有），再在 verify 注册块里 mustRunAfter，避免回头 configure。
        val hasBots = scenario.botSpecs.isNotEmpty()
        val launch: TaskProvider<DefaultTask>? = if (hasBots) {
            registerTask(project, McTestkitTaskNames.launchBot(scenario.name)) { task ->
                task.group = TASK_GROUP
                task.description = "启动场景 ${scenario.name} 的 mineflayer 机器人（声明多 bot 时起多个进程）"
                task.dependsOn(prepare, McTestkitTaskNames.NPM_INSTALL_BOT)
                task.doLast {
                    launchScenarioBots(project, scenario, backendPort = backend.port, protocolVersion = null)
                }
            }
        } else {
            null
        }

        val verify = registerTask(project, McTestkitTaskNames.verify(scenario.name)) { task ->
            task.group = TASK_GROUP
            task.description = "运行场景 ${scenario.name}（直连后端），读结果判 PASS/FAIL"
            task.dependsOn(prepare)
            // 有 bot 时 verify 须在 launch 之后（机器人先连上，后端跑完才有结果）
            if (launch != null) {
                task.mustRunAfter(launch)
            }
            task.doLast {
                try {
                    runBackendForeground(project, backend, scenario.name)
                    verifyScenarioResult(project, scenario.name)
                } finally {
                    // 成功 / 失败都收尾全部 bot（多 bot 防残留；单 bot 已自停为安全 no-op）
                    if (hasBots) stopScenarioBots(project, scenario)
                }
            }
        }

        // 有 bot 的场景：一键「启动机器人 + 验证」
        if (launch != null) {
            registerTask(project, McTestkitTaskNames.withBot(scenario.name)) { task ->
                task.group = TASK_GROUP
                task.description = "一键运行场景 ${scenario.name}：启动机器人 + 验证"
                task.dependsOn(launch, verify)
            }
        }

        // 经代理的场景：e2e<Key>Via<Proxy>
        scenario.via?.let { viaName ->
            val proxy = topology.proxies.firstOrNull { it.name == viaName }
                ?: throw GradleException(
                    "mcTestkit 场景「${scenario.name}」引用的代理「$viaName」不存在（应在 TopologyResolver 已拦截）。",
                )
            registerViaProxyTask(project, scenario, backend, proxy)
        }
    }

    /** 注册经代理任务：prepare → 起代理（后台）→ 起 bot（经代理端口、固定协议版本）→ 前台起后端 → 验证 → finalizedBy 停代理。 */
    private fun registerViaProxyTask(
        project: Project,
        scenario: ScenarioSpec,
        backend: ResolvedBackend,
        proxy: ResolvedProxy,
    ) {
        val layout = layoutOf(project)
        val prepareName = McTestkitTaskNames.prepare(scenario.name)
        val proxyPidFile = layout.proxyPidFile(proxy.name)
        val isBungeeMode = proxy.platform != ProxyPlatform.VELOCITY

        // 停代理任务（收尾用，单独可调；经代理任务 finalizedBy 它，保证不残留占端口）
        // 多个经代理场景共用同名停代理任务，故先查是否已注册，未注册才注册（按代理名唯一）。
        val stopProxyName = "stopProxy" + proxy.name.replaceFirstChar { it.uppercaseChar() }
        if (stopProxyName !in project.tasks.names) {
            registerTask(project, stopProxyName) { task ->
                task.group = TASK_GROUP
                task.description = "停止代理 ${proxy.name}（按 pid 收尾）"
                task.doLast {
                    stopProcessByPidFile(proxyPidFile) { project.logger.lifecycle("[mc-testkit] $it") }
                }
            }
        }

        registerTask(project, McTestkitTaskNames.verifyVia(scenario.name, proxy.name)) { task ->
            task.group = TASK_GROUP
            task.description = "经代理 ${proxy.name} 运行场景 ${scenario.name}（bot 连代理 → 后端 → 判定）"
            task.dependsOn(prepareName)
            // 正常 / 失败 / 中断三路径都收尾停代理（finalizedBy）；任务体内再加 try/finally 双保险
            task.finalizedBy(stopProxyName)
            task.doLast {
                var proxyProcess: Process? = null
                try {
                    // ① 后端切到代理模式：BungeeCord 系走三件套，Velocity 走 modern forwarding 两件套（含共享 secret）
                    if (isBungeeMode) {
                        BackendBungeeCordConfig.apply(layout.runDir)
                    } else {
                        BackendVelocityConfig.apply(layout.runDir)
                    }
                    // ② 后台起代理（写 pid 供收尾）
                    proxyProcess = startProxyBackground(project, proxy, backend)
                    // ③ 起全部 bot：经代理端口进服，协议版本固定为后端版本（环境契约 FR-05；多 bot 各唯一名）
                    launchScenarioBots(
                        project,
                        scenario,
                        backendPort = proxy.port,
                        protocolVersion = ProxyProtocolVersion.forBackend(backend.version),
                    )
                    // ④ 前台起后端（自停 waitFor）
                    runBackendForeground(project, backend, scenario.name)
                    // ⑤ 只认结果文件判定
                    verifyScenarioResult(project, scenario.name)
                } finally {
                    // 双保险：先按 pid 收尾全部 bot（多 bot 防残留），再收尾代理（即便 finalizedBy 未触发）
                    if (scenario.botSpecs.isNotEmpty()) stopScenarioBots(project, scenario)
                    proxyProcess?.let { stopProcessQuietly(project, it, proxyPidFile) }
                }
            }
        }
    }

    /**
     * 注册集群任务（FR-10，ADR-0008）：N 后端**全部后台**起 + 代理（单 listener + N server）+ 切换 bot →
     * 以结果文件为权威完成信号轮询 → 判定；正常/失败/中断三路径都 finalizedBy + try/finally 双保险收尾
     * 全部后端 + 代理，端口干净（高风险区）。
     */
    private fun registerClusterTask(
        project: Project,
        extension: McTestkitExtension,
        scenario: ScenarioSpec,
        clusterBackends: List<ResolvedBackend>,
        proxy: ResolvedProxy,
    ) {
        val layout = layoutOf(project)
        val stopName = McTestkitTaskNames.stopCluster(scenario.name)
        // 全部 bot 的 pid key（多 bot 各一支；停任务据此按 pid 收尾，防 straggler 残留）
        val botKeys = BotProcessPlanner.expand(scenario.name, scenario.botSpecs).map { it.key }

        // 停集群任务：按 pid 收尾全部后端 + 代理 + 全部 bot（单独可调；集群任务 finalizedBy 它）
        registerTask(project, stopName) { task ->
            task.group = TASK_GROUP
            task.description = "停止集群场景 ${scenario.name} 的全部后端、代理与机器人（按 pid 收尾）"
            task.doLast {
                clusterBackends.forEach { backend ->
                    stopProcessByPidFile(layout.clusterBackendPidFile(backend.name)) { project.logger.lifecycle("[mc-testkit] $it") }
                }
                stopProcessByPidFile(layout.proxyPidFile(proxy.name)) { project.logger.lifecycle("[mc-testkit] $it") }
                botKeys.forEach { key ->
                    stopProcessByPidFile(botPidFile(layout.resultsDir, key)) { project.logger.lifecycle("[mc-testkit] $it") }
                }
            }
        }

        registerTask(project, McTestkitTaskNames.cluster(scenario.name)) { task ->
            task.group = TASK_GROUP
            task.description =
                "集群运行场景 ${scenario.name}：${clusterBackends.size} 后端 + 代理 ${proxy.name}（bot /server 切换）→ 判定"
            if (scenario.botSpecs.isNotEmpty()) {
                task.dependsOn(McTestkitTaskNames.NPM_INSTALL_BOT)
            }
            // 正常 / 失败 / 中断三路径都收尾（finalizedBy）；任务体内再 try/finally 双保险
            task.finalizedBy(stopName)
            task.doLast {
                val backendProcesses = LinkedHashMap<String, Process>()
                var proxyProcess: Process? = null
                val botProcesses = mutableListOf<Process>()
                try {
                    layout.resultsDir.mkdirs()
                    val resultFile = File(layout.resultsDir, McTestkitResultFile.fileName(scenario.name))
                    if (resultFile.exists()) resultFile.delete() // 清上轮结果，避免误判

                    // ① 每后端独立运行目录 prepare + BungeeCord 模式 + 后台起（同 SCENARIO / RESULT_FILE）
                    clusterBackends.forEach { backend ->
                        val runDir = layout.clusterBackendRunDir(backend.name)
                        prepareRunDirectory(project, extension, backend, runDir)
                        // 后端切到代理模式：Velocity 走 modern forwarding 两件套，否则 BungeeCord 三件套
                        if (proxy.platform == ProxyPlatform.VELOCITY) {
                            BackendVelocityConfig.apply(runDir)
                        } else {
                            BackendBungeeCordConfig.apply(runDir)
                        }
                        backendProcesses[backend.name] =
                            startBackendBackground(project, backend, runDir, scenario.name, resultFile)
                    }
                    // ② 后台起集群代理（单 listener + N 具名 server）
                    proxyProcess = startClusterProxyBackground(project, proxy, clusterBackends)
                    // ③ 起全部 bot：经代理端口，CLUSTER_BACKENDS 下发 /server 切换目标（每个 bot 都能切），
                    //    协议版本固定为后端版本；多 bot 各唯一 username、同质复制下发 BOT_INDEX（FR-16）
                    botProcesses += launchScenarioBots(
                        project,
                        scenario,
                        backendPort = proxy.port,
                        protocolVersion = ProxyProtocolVersion.forBackend(clusterBackends.first().version),
                        sharedExtraEnv = mapOf(
                            McTestkitEnv.CLUSTER_BACKENDS to clusterBackends.joinToString(",") { it.name },
                        ),
                    )
                    // ④ 轮询结果文件（任一桩写出即完成）
                    awaitClusterResult(project, resultFile, scenario.name)
                    // ⑤ 只认结果文件判定
                    verifyScenarioResult(project, scenario.name)
                } finally {
                    // 双保险收尾：全部 bot（自停兜底）+ 后端 + 代理（即便 finalizedBy 未触发）
                    botProcesses.forEach { destroyProcessQuietly(project, it) }
                    backendProcesses.forEach { (name, proc) ->
                        stopProcessQuietly(project, proc, layout.clusterBackendPidFile(name))
                    }
                    proxyProcess?.let { stopProcessQuietly(project, it, layout.proxyPidFile(proxy.name)) }
                }
            }
        }
    }

    /** 后台起一个集群后端（不等自停，pid 落结果目录供收尾）；同 SCENARIO / RESULT_FILE 交接 env。 */
    private fun startBackendBackground(
        project: Project,
        backend: ResolvedBackend,
        runDir: File,
        scenario: String,
        resultFile: File,
    ): Process {
        val layout = layoutOf(project)
        val provisioner = ServerJarProvisioner.create(layout.jarCacheRoot) {
            project.providers.environmentVariable(it).orNull
        }
        val jar = provisioner.resolve(backend.platform.name.lowercase(), backend.version) {
            project.logger.lifecycle("[mc-testkit] $it")
        }
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = runDir,
            key = backend.name,
            jvmArgs = listOf("-Dterminal.ansi=false", "-Dnet.kyori.ansi.colorLevel=none"),
            serverArgs = listOf("--nogui"),
            environment = mapOf(
                McTestkitEnv.SCENARIO to scenario,
                McTestkitEnv.RESULT_FILE to resultFile.absolutePath,
                McTestkitEnv.BACKEND_NAME to backend.name,
            ),
            logger = { project.logger.lifecycle("[mc-testkit] $it") },
        )
        layout.clusterBackendPidFile(backend.name).apply { parentFile?.mkdirs() }
            .writeText(process.pid().toString())
        project.logger.lifecycle(
            "[mc-testkit] 已后台启动集群后端 ${backend.name} pid=${process.pid()} 端口=${backend.port}",
        )
        return process
    }

    /** 后台起集群代理（单 listener + N 具名 server，供 bot /server 切换）。 */
    private fun startClusterProxyBackground(
        project: Project,
        proxy: ResolvedProxy,
        clusterBackends: List<ResolvedBackend>,
    ): Process {
        val layout = layoutOf(project)
        val proxyRunDir = layout.proxyRunDir.apply { mkdirs() }
        when (proxy.platform) {
            ProxyPlatform.WATERFALL, ProxyPlatform.BUNGEECORD ->
                File(proxyRunDir, "config.yml").writeText(
                    bungeeClusterProxyConfigYml(
                        listenPort = proxy.port,
                        backends = clusterBackends.map { it.name to "127.0.0.1:${it.port}" },
                    ),
                )
            ProxyPlatform.VELOCITY ->
                writeVelocityProxyFiles(project, proxyRunDir, proxy.port, clusterBackends.map { it.name to "127.0.0.1:${it.port}" })
        }
        val provisioner = ServerJarProvisioner.create(layout.jarCacheRoot) {
            project.providers.environmentVariable(it).orNull
        }
        val jar = provisioner.resolve(proxy.platform.name.lowercase(), proxyDownloadVersion(proxy.platform, clusterBackends.first().version)) {
            project.logger.lifecycle("[mc-testkit] $it")
        }
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = proxyRunDir,
            key = proxy.name,
            logger = { project.logger.lifecycle("[mc-testkit] $it") },
        )
        layout.proxyPidFile(proxy.name).apply { parentFile?.mkdirs() }.writeText(process.pid().toString())
        project.logger.lifecycle(
            "[mc-testkit] 已启动集群代理 ${proxy.name} pid=${process.pid()} 监听端口=${proxy.port}" +
                "（servers: ${clusterBackends.joinToString(",") { it.name }}）",
        )
        return process
    }

    /** 轮询等集群结果文件写出（任一桩写出即完成）；超时仍无即抛中文错误（收尾由 finally / finalizedBy 兜）。 */
    private fun awaitClusterResult(project: Project, resultFile: File, scenario: String) {
        project.logger.lifecycle("[mc-testkit] 集群场景 $scenario 已全部起服，等待桩写出结果文件…")
        val deadlineMs = System.currentTimeMillis() + BACKEND_WAIT_TIMEOUT_SECONDS * 1000L
        while (!resultFile.exists() && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(2000)
        }
        if (!resultFile.exists()) {
            throw GradleException(
                "集群场景 $scenario 在 ${BACKEND_WAIT_TIMEOUT_SECONDS}s 内未写出结果文件" +
                    "（桩可能未正常收尾 / 后端启动失败 / bot 切换未触达）；将收尾全部进程。",
            )
        }
    }

    /**
     * 注册压测任务（FR-11，ADR-0008）：N 后端**全部后台** + 代理（N-listener 钉服）或直连 + 每服 M 个
     * bot 进程钉本服持续随机施压 → 等**全部 per-server 结果文件** → 聚合判定（任一缺失 / FAIL 即失败并
     * 报哪服）；正常 / 失败 / 中断三路径都 finalizedBy + try/finally 双保险收尾全部后端 + 代理 + bot，
     * 端口干净（高风险区）。业务不变量（不超卖等）由消费方桩查共享 DB 自行判，框架只收集 + 聚合。
     */
    private fun registerStressTask(
        project: Project,
        extension: McTestkitExtension,
        scenario: ScenarioSpec,
        stress: StressSpec,
        stressBackends: List<ResolvedBackend>,
        proxy: ResolvedProxy?,
    ) {
        val layout = layoutOf(project)
        val stopName = McTestkitTaskNames.stopStress(scenario.name)
        val action = scenario.botSpec?.action ?: scenario.name

        // 全部 bot 的 log/pid key（停任务据此按 pid 收尾，防 straggler 残留）
        val botKeys = stressBackends.indices.flatMap { idx ->
            (1..stress.botsPerServer).map { i -> stressBotKey(action, idx + 1, i) }
        }

        // 停压测任务：按 pid 收尾全部后端 + 代理 + 全部 bot（单独可调；压测任务 finalizedBy 它）
        registerTask(project, stopName) { task ->
            task.group = TASK_GROUP
            task.description = "停止压测场景 ${scenario.name} 的全部后端、代理与机器人（按 pid 收尾）"
            task.doLast {
                stressBackends.forEach { backend ->
                    stopProcessByPidFile(layout.clusterBackendPidFile(backend.name)) { project.logger.lifecycle("[mc-testkit] $it") }
                }
                proxy?.let { stopProcessByPidFile(layout.proxyPidFile(it.name)) { project.logger.lifecycle("[mc-testkit] $it") } }
                botKeys.forEach { key ->
                    stopProcessByPidFile(botPidFile(layout.resultsDir, key)) { project.logger.lifecycle("[mc-testkit] $it") }
                }
            }
        }

        registerTask(project, McTestkitTaskNames.stress(scenario.name)) { task ->
            task.group = TASK_GROUP
            task.description =
                "压测场景 ${scenario.name}：${stressBackends.size} 服 × ${stress.botsPerServer} bot 钉服持续 ${stress.durationSeconds}s" +
                (proxy?.let { "（经代理 ${it.name} N-listener 钉服）" } ?: "（直连后端）")
            if (scenario.botSpec != null) {
                task.dependsOn(McTestkitTaskNames.NPM_INSTALL_BOT)
            }
            // 正常 / 失败 / 中断三路径都收尾（finalizedBy）；任务体内再 try/finally 双保险
            task.finalizedBy(stopName)
            task.doLast {
                val backendProcesses = LinkedHashMap<String, Process>()
                val botProcesses = mutableListOf<Process>()
                var proxyProcess: Process? = null
                try {
                    layout.resultsDir.mkdirs()
                    // 清上轮 per-server 结果，避免误判
                    stressBackends.forEach { backend ->
                        stressResultFile(layout, scenario.name, backend.name).takeIf { it.exists() }?.delete()
                    }

                    // ① 每后端独立运行目录 prepare（+ BungeeCord 模式 if via）+ 后台起（同 SCENARIO、各自 per-server RESULT_FILE）
                    stressBackends.forEach { backend ->
                        val runDir = layout.clusterBackendRunDir(backend.name)
                        prepareRunDirectory(project, extension, backend, runDir)
                        if (proxy != null) BackendBungeeCordConfig.apply(runDir)
                        backendProcesses[backend.name] =
                            startBackendBackground(project, backend, runDir, scenario.name, stressResultFile(layout, scenario.name, backend.name))
                    }

                    // ② 计算钉服绑定（listener 端口 = 代理端口基数 + 序号）；若经代理则后台起 N-listener 钉服代理
                    val bindings = stressBackends.mapIndexed { index, backend ->
                        StressProxyBinding(backend.name, "127.0.0.1:${backend.port}", (proxy?.port ?: 0) + index)
                    }
                    if (proxy != null) {
                        proxyProcess = startStressProxyBackground(project, proxy, bindings, stressBackends.first().version)
                    }

                    // ③ 每服起 M 个 bot 钉本服（via 用对应 listener 端口、直连用后端端口；协议版本经代理固定为后端版本）
                    scenario.botSpec?.let { bot ->
                        stressBackends.forEachIndexed { index, backend ->
                            val botPort = if (proxy != null) bindings[index].listenPort else backend.port
                            val protocolVersion = if (proxy != null) ProxyProtocolVersion.forBackend(backend.version) else null
                            botProcesses += launchStressBotsForServer(project, stress, bot, action, index + 1, botPort, protocolVersion)
                        }
                    }

                    // ④ 等全部 per-server 结果文件写出（桩到 duration 末聚合写出）
                    awaitAllStressResults(project, layout, scenario.name, stressBackends, stress.durationSeconds)

                    // ⑤ 聚合判定：每服结果文件都须 PASS（只认结果文件，业务不变量由消费方桩在其中体现）
                    verifyStressResults(project, layout, scenario.name, stressBackends)
                } finally {
                    // 双保险收尾：全部 bot（自停兜底）+ 全部后端 + 代理
                    botProcesses.forEach { destroyProcessQuietly(project, it) }
                    backendProcesses.forEach { (name, proc) -> stopProcessQuietly(project, proc, layout.clusterBackendPidFile(name)) }
                    proxy?.let { p -> proxyProcess?.let { stopProcessQuietly(project, it, layout.proxyPidFile(p.name)) } }
                }
            }
        }
    }

    /** 后台起压测 N-listener 钉服代理（一端口对一后端，bot 连某端口钉死在对应后端）。 */
    private fun startStressProxyBackground(
        project: Project,
        proxy: ResolvedProxy,
        bindings: List<StressProxyBinding>,
        proxyVersion: String,
    ): Process {
        val layout = layoutOf(project)
        val proxyRunDir = layout.proxyRunDir.apply { mkdirs() }
        when (proxy.platform) {
            ProxyPlatform.WATERFALL, ProxyPlatform.BUNGEECORD ->
                File(proxyRunDir, "config.yml").writeText(bungeeStressProxyConfigYml(bindings))
            // 压测经 Velocity 已在配置期被拦截（见 registerScenarioTasks 的 stress 分支）；此处兜底，正常不可达
            ProxyPlatform.VELOCITY ->
                error("压测不支持经 Velocity 代理（单端口无法钉服）：应在配置期已拦截，不应到此。")
        }
        val provisioner = ServerJarProvisioner.create(layout.jarCacheRoot) {
            project.providers.environmentVariable(it).orNull
        }
        val jar = provisioner.resolve(proxy.platform.name.lowercase(), proxyVersion) {
            project.logger.lifecycle("[mc-testkit] $it")
        }
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = proxyRunDir,
            key = proxy.name,
            logger = { project.logger.lifecycle("[mc-testkit] $it") },
        )
        layout.proxyPidFile(proxy.name).apply { parentFile?.mkdirs() }.writeText(process.pid().toString())
        project.logger.lifecycle(
            "[mc-testkit] 已启动压测代理 ${proxy.name} pid=${process.pid()} " +
                "listeners=${bindings.joinToString(",") { "${it.listenPort}->${it.backendName}" }}",
        )
        return process
    }

    /** 为某服后台起 M 个压测 bot 进程（各唯一名 / 唯一 log·pid key / BOT_INDEX / 共享 seed / duration）。 */
    private fun launchStressBotsForServer(
        project: Project,
        stress: StressSpec,
        bot: BotSpec,
        action: String,
        serverIndex: Int,
        botPort: Int,
        protocolVersion: String?,
    ): List<Process> {
        val layout = layoutOf(project)
        val botDir = layout.botDir(project.findProperty(BOT_DIR_PROPERTY)?.toString())
        val botScript = File(botDir, RunLayout.BOT_SCRIPT_RELATIVE)
        if (!botScript.isFile) {
            throw GradleException(
                "未找到机器人入口脚本：${botScript.absolutePath}。请把 template/bot 照抄到 $botDir（或用 -P$BOT_DIR_PROPERTY=<目录> 指定机器人目录）。",
            )
        }
        // 用户名基（Minecraft 离线名 ≤16 字符，bot 数多时请用短基名避免超限）
        val baseName = bot.username ?: action
        val processes = mutableListOf<Process>()
        for (i in 1..stress.botsPerServer) {
            val key = stressBotKey(action, serverIndex, i)
            val username = "${baseName}_s${serverIndex}_$i"
            val connection = BotConnection(action = action, username = username, port = botPort, version = protocolVersion)
            // 强制唯一名（经 extraEnv 末位合入，覆盖消费方单值 BOT_USERNAME override）+ 压测维度 env
            val extraEnv = bot.env + mapOf(
                McTestkitEnv.BOT_USERNAME to username,
                McTestkitEnv.BOT_INDEX to i.toString(),
                McTestkitEnv.STRESS_RANDOM_SEED to stress.randomSeed.toString(),
                McTestkitEnv.STRESS_DURATION_SECONDS to stress.durationSeconds.toString(),
            )
            val environment = connection.toEnvironment(extraEnv) { project.providers.environmentVariable(it).orNull }
            processes += BotLauncher.launch(
                context = BotProcessContext(botDir = botDir, botScript = botScript, resultsDir = layout.resultsDir),
                action = key,
                environment = environment,
                logger = { project.logger.lifecycle("[mc-testkit] $it") },
            )
        }
        project.logger.lifecycle("[mc-testkit] 已为服 s$serverIndex 启动 ${stress.botsPerServer} 个压测 bot（连端口=$botPort）")
        return processes
    }

    /** 轮询等全部 per-server 结果文件写出（桩到 duration 末聚合写出）；超时仍缺则抛中文错误并报哪服。 */
    private fun awaitAllStressResults(
        project: Project,
        layout: RunLayout,
        scenario: String,
        backends: List<ResolvedBackend>,
        durationSeconds: Long,
    ) {
        project.logger.lifecycle(
            "[mc-testkit] 压测场景 $scenario 已全部起服，持续 ${durationSeconds}s，等待各服桩写出结果文件…",
        )
        // 等待上限 = 压测时长 + 宽限（桩在 duration 末才聚合写出）
        val deadlineMs = System.currentTimeMillis() + (durationSeconds + BACKEND_WAIT_TIMEOUT_SECONDS) * 1000L
        while (backends.any { !stressResultFile(layout, scenario, it.name).exists() } &&
            System.currentTimeMillis() < deadlineMs
        ) {
            Thread.sleep(3000)
        }
        val missing = backends.filter { !stressResultFile(layout, scenario, it.name).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "压测场景 $scenario 有服未在时限内写出结果文件：${missing.joinToString(",") { it.name }}" +
                    "（桩可能未正常收尾 / 后端启动失败 / bot 未连上）；将收尾全部进程。",
            )
        }
    }

    /** 聚合判定：每服 per-server 结果文件都须 PASS（只认结果文件，[ResultReader] 缺失/非 PASS 抛中文错误）。 */
    private fun verifyStressResults(
        project: Project,
        layout: RunLayout,
        scenario: String,
        backends: List<ResolvedBackend>,
    ) {
        backends.forEach { backend ->
            val result = ResultReader.read(layout.resultsDir, "$scenario-${backend.name}")
            project.logger.lifecycle("[mc-testkit] 压测服 ${backend.name} 通过：${result.message}")
        }
        project.logger.lifecycle("[mc-testkit] 压测场景 $scenario 全部 ${backends.size} 服聚合判定通过。")
    }

    /** 压测某服某 bot 的 log/pid key：`<action>-s<serverIndex>-<botIndex>`。 */
    private fun stressBotKey(action: String, serverIndex: Int, botIndex: Int): String =
        "$action-s$serverIndex-$botIndex"

    /** 压测某服的 per-server 结果文件（`<scenario>-<backendName>.properties`）。 */
    private fun stressResultFile(layout: RunLayout, scenario: String, backendName: String): File =
        File(layout.resultsDir, McTestkitResultFile.fileName("$scenario-$backendName"))

    /** 温和销毁一个进程（压测 bot 收尾双保险；pid 文件由停任务按 pid 清理，这里只灭进程）。 */
    private fun destroyProcessQuietly(project: Project, process: Process) {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS) && process.isAlive) {
                    process.destroyForcibly()
                }
            }
        } catch (ex: Exception) {
            project.logger.warn("[mc-testkit] 收尾机器人进程时异常（已忽略）：${ex.message}")
        }
    }

    // ── 任务体的实际副作用实现（均在 doLast 内被调用，配置期不执行）──

    /** 准备运行目录：清理（保留缓存）、建结果目录、写 eula、最小 server.properties、注入插件 jar。 */
    private fun prepareRunDirectory(
        project: Project,
        extension: McTestkitExtension,
        backend: ResolvedBackend,
        runDir: File,
    ) {
        val layout = layoutOf(project)
        val resultsDir = layout.resultsDir

        cleanRunDirPreservingRuntimeCaches(runDir)
        runDir.mkdirs()
        resultsDir.mkdirs()

        // 服务端模板 seeding：提供 SERVER_TEMPLATE_DIR 时把模板（依赖插件配置 / 运行库基线）铺进运行目录
        // （排除世界与日志，避免世界锁与陈旧日志）。这样依赖插件有其配置，被测插件能正常启动。
        project.providers.environmentVariable(McTestkitEnv.SERVER_TEMPLATE_DIR).orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { templatePath ->
                val templateDir = File(templatePath)
                if (!templateDir.isDirectory) {
                    throw GradleException("服务端模板目录不存在：$templatePath（${McTestkitEnv.SERVER_TEMPLATE_DIR}）")
                }
                project.copy(
                    object : Action<org.gradle.api.file.CopySpec> {
                        override fun execute(spec: org.gradle.api.file.CopySpec) {
                            spec.from(templateDir)
                            spec.into(runDir)
                            spec.exclude("world/**", "world_nether/**", "world_the_end/**", "logs/**")
                        }
                    },
                )
                project.logger.lifecycle("[mc-testkit] 已铺服务端模板：$templatePath → ${runDir.name}")
            }

        val pluginsDir = File(runDir, "plugins").apply { mkdirs() }

        // 写 eula + 最小可启动 server.properties（端口取后端解析端口、离线、关安全档案）
        File(runDir, "eula.txt").writeText("eula=true\n")
        // 端口 / 离线 / 关安全档案是 E2E 必须强制项；难度默认 peaceful 保护测试玩家不被怪物 / 环境杀，
        // 但仅在消费方模板未指定 difficulty 时才默认（模板设了则保留其值，FR-13）。
        // 注：online-mode=false 是「离线 bot 入服」的通用基线，在此设；经代理（BungeeCord 模式）时
        // BackendBungeeCordConfig 会再确认它（三件套之一），二者值一致，非冲突。
        val serverPropsOverrides =
            linkedMapOf(
                ServerProperties.SERVER_PORT to backend.port.toString(),
                ServerProperties.ONLINE_MODE to "false",
                ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            )
        if (!ServerProperties.load(runDir).containsKey(ServerProperties.DIFFICULTY)) {
            serverPropsOverrides[ServerProperties.DIFFICULTY] = "peaceful"
        }
        ServerProperties.edit(runDir, serverPropsOverrides)

        // 注入待测插件 jar 与依赖插件 jar（dependencies{} 声明、env / 路径解析；缺失抛中文错误）
        val resolvedJars = resolveDependencyJars(extension.declaredDependencies) {
            project.providers.environmentVariable(it).orNull
        }
        resolvedJars.forEach { resolved ->
            val targetName = if (resolved.underTest) "plugin-under-test.jar" else resolved.jar.name
            resolved.jar.copyTo(File(pluginsDir, targetName), overwrite = true)
            project.logger.lifecycle("[mc-testkit] 已注入插件：${resolved.jar.name} → plugins/$targetName")
        }
    }

    /**
     * 前台起后端：provision 解析 jar → ServerLauncher 起子进程 → 等待自停（桩跑完关服）。
     *
     * 后端 BungeeCord 模式配置（经代理必需）由调用方在起后端前另行 [BackendBungeeCordConfig.apply]，
     * 本函数只负责"起后端 + 等自停"，不掺配置逻辑。
     */
    private fun runBackendForeground(project: Project, backend: ResolvedBackend, scenario: String) {
        val layout = layoutOf(project)
        val runDir = layout.runDir
        val provisioner = ServerJarProvisioner.create(layout.jarCacheRoot) {
            project.providers.environmentVariable(it).orNull
        }
        val jar = provisioner.resolve(backend.platform.name.lowercase(), backend.version) {
            project.logger.lifecycle("[mc-testkit] $it")
        }
        // 桩↔编排交接：下发场景与结果文件绝对路径（= verify 读取处），桩据此选场景并写到对齐位置
        val resultFilePath = File(layout.resultsDir, McTestkitResultFile.fileName(scenario)).absolutePath
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = runDir,
            key = backend.name,
            jvmArgs = listOf("-Dterminal.ansi=false", "-Dnet.kyori.ansi.colorLevel=none"),
            serverArgs = listOf("--nogui"),
            environment = mapOf(
                McTestkitEnv.SCENARIO to scenario,
                McTestkitEnv.RESULT_FILE to resultFilePath,
                McTestkitEnv.BACKEND_NAME to backend.name,
            ),
            logger = { project.logger.lifecycle("[mc-testkit] $it") },
        )
        // 等被测后端跑完：以「桩写出结果文件」为权威完成信号（结果文件是真源，见 verify/），
        // 而非死等 JVM 退出——真实后端的依赖（数据源 / Redis 连接池等非守护线程）常使 JVM 在
        // Bukkit.shutdown 后仍不退出。结果出现后给一小段优雅自停窗口，仍不退则强杀，避免空等到超时。
        val resultFile = File(layout.resultsDir, McTestkitResultFile.fileName(scenario))
        val deadlineMs = System.currentTimeMillis() + BACKEND_WAIT_TIMEOUT_SECONDS * 1000L
        while (process.isAlive && !resultFile.exists() && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(2000)
        }
        if (resultFile.exists() && process.isAlive) {
            // 结果已写出：给优雅自停窗口；到时仍活则强杀（非守护线程卡住 JVM 时不空等到超时）
            if (!process.waitFor(BACKEND_SELF_STOP_GRACE_SECONDS, TimeUnit.SECONDS) && process.isAlive) {
                project.logger.lifecycle(
                    "[mc-testkit] 后端 ${backend.name} 结果已写出，但 JVM 未在 ${BACKEND_SELF_STOP_GRACE_SECONDS}s 内自停" +
                        "（常因依赖连接池非守护线程），强制结束，不影响结果判定。",
                )
                process.destroyForcibly()
                process.waitFor(15, TimeUnit.SECONDS)
            }
        }
        if (!resultFile.exists()) {
            if (process.isAlive) process.destroyForcibly()
            throw GradleException(
                "后端 ${backend.name} 在 ${BACKEND_WAIT_TIMEOUT_SECONDS}s 内未写出结果文件（桩可能未正常收尾 / 启动失败）；已强制结束。",
            )
        }
    }

    /** 后台起代理：写代理配置 → provision 解析代理 jar → ServerLauncher 起子进程（pid 落结果目录供收尾）。 */
    private fun startProxyBackground(
        project: Project,
        proxy: ResolvedProxy,
        backend: ResolvedBackend,
    ): Process {
        val layout = layoutOf(project)
        val proxyRunDir = layout.proxyRunDir.apply { mkdirs() }

        // 写代理配置（监听端口 + 转发到后端地址）。BungeeCord 系（Waterfall/BungeeCord）写 config.yml；
        // Velocity 走不同配置（modern forwarding），本期 FR-08 验收路径以 Waterfall 为准，Velocity 留 FR-08。
        when (proxy.platform) {
            ProxyPlatform.WATERFALL, ProxyPlatform.BUNGEECORD ->
                File(proxyRunDir, "config.yml").writeText(
                    bungeeProxyConfigYml(listenPort = proxy.port, backendAddress = "127.0.0.1:${backend.port}"),
                )
            ProxyPlatform.VELOCITY ->
                writeVelocityProxyFiles(project, proxyRunDir, proxy.port, listOf(backend.name to "127.0.0.1:${backend.port}"))
        }

        val provisioner = ServerJarProvisioner.create(layout.jarCacheRoot) {
            project.providers.environmentVariable(it).orNull
        }
        // 代理下载版本：BungeeCord 系取后端版本（与经代理机器人协议版本同源；Waterfall 经 provision 层归一为
        // major.minor，避免带补丁号 404）；Velocity 用自有版本号（非 MC 版本，见 [proxyDownloadVersion]）。
        // env `*_VERSION` 覆盖仍优先（见 ServerJarProvisioner.resolveVersion）。
        val jar = provisioner.resolve(proxy.platform.name.lowercase(), proxyDownloadVersion(proxy.platform, backend.version)) {
            project.logger.lifecycle("[mc-testkit] $it")
        }
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = proxyRunDir,
            key = proxy.name,
            logger = { project.logger.lifecycle("[mc-testkit] $it") },
        )
        // pid 同时落结果目录（停代理任务 / try-finally 据此收尾）
        layout.proxyPidFile(proxy.name).apply { parentFile?.mkdirs() }.writeText(process.pid().toString())
        project.logger.lifecycle(
            "[mc-testkit] 已启动代理 ${proxy.name} pid=${process.pid()} 监听端口=${proxy.port}（转发到后端 ${backend.name}:${backend.port}）",
        )
        return process
    }

    /**
     * 起场景声明的全部 bot 进程（FR-16）：按 [BotProcessPlanner.expand] 逐 plan 起进程。
     *
     * 进程数 >1 时**强制**下发唯一 `BOT_USERNAME`（末位合入，盖过消费方单值 override 以保证唯一）；
     * 同质复制（`count>1`）下发 `BOT_INDEX`（1..N）；并合入 [sharedExtraEnv]（如集群的 `CLUSTER_BACKENDS`，
     * 使每个 bot 都能经代理 `/server` 切换）。单 bot 时不强制 username（保留消费方 override，向后兼容）。
     *
     * @return 全部已起进程（供调用方按需收尾；亦各自写了 `bot-<key>.pid` 供按 pid 收尾）。
     */
    private fun launchScenarioBots(
        project: Project,
        scenario: ScenarioSpec,
        backendPort: Int,
        protocolVersion: String?,
        sharedExtraEnv: Map<String, String> = emptyMap(),
    ): List<Process> {
        val plans = BotProcessPlanner.expand(scenario.name, scenario.botSpecs)
        // 每进程「追加 env」（唯一名 / 序号 / 共享 env）由纯函数装配，便于穷举单测
        val environments = BotProcessPlanner.extraEnvironments(plans, sharedExtraEnv)
        return plans.zip(environments).map { (plan, extraEnv) ->
            launchBotProcess(
                project,
                action = plan.action,
                username = plan.username,
                key = plan.key,
                backendPort = backendPort,
                protocolVersion = protocolVersion,
                extraEnv = extraEnv,
            )
        }
    }

    /** 按 plan key 收尾场景全部 bot 的 pid 文件（单 bot / 已自停为安全 no-op，[stopProcessByPidFile]）。 */
    private fun stopScenarioBots(project: Project, scenario: ScenarioSpec) {
        val layout = layoutOf(project)
        BotProcessPlanner.expand(scenario.name, scenario.botSpecs).forEach { plan ->
            stopProcessByPidFile(botPidFile(layout.resultsDir, plan.key)) { project.logger.lifecycle("[mc-testkit] $it") }
        }
    }

    /**
     * 起一个 bot 进程：env 由 [BotConnection] 建（含协议版本固定），[BotLauncher] 后台拉起。
     *
     * @param action 机器人场景分发动作（写入 `BOT_ACTION`，机器人内核据此分发）。
     * @param key 日志 / pid 唯一 key（`bot-<key>.log` / `bot-<key>.pid`，多 bot 时区分各进程）。
     * @return 已启动的进程（供调用方按需收尾）。
     */
    private fun launchBotProcess(
        project: Project,
        action: String,
        username: String,
        key: String,
        backendPort: Int,
        protocolVersion: String?,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process {
        val layout = layoutOf(project)
        val botDir = layout.botDir(project.findProperty(BOT_DIR_PROPERTY)?.toString())
        val botScript = File(botDir, RunLayout.BOT_SCRIPT_RELATIVE)
        if (!botScript.isFile) {
            throw GradleException(
                "未找到机器人入口脚本：${botScript.absolutePath}。请把 template/bot 照抄到 $botDir（或用 -P$BOT_DIR_PROPERTY=<目录> 指定机器人目录）。",
            )
        }
        val connection = BotConnection(
            action = action,
            username = username,
            port = backendPort,
            version = protocolVersion,
        )
        // 业务 bot env（scenario { bot { env(...) } } 声明）作为追加项，可覆盖通用项
        val environment = connection.toEnvironment(extraEnvironment = extraEnv) {
            project.providers.environmentVariable(it).orNull
        }
        return BotLauncher.launch(
            context = BotProcessContext(botDir = botDir, botScript = botScript, resultsDir = layout.resultsDir),
            action = key,
            environment = environment,
            logger = { project.logger.lifecycle("[mc-testkit] $it") },
        )
    }

    /** 只认结果文件判定（[ResultReader]）：缺失或失败抛中文错误，通过则打印 message。 */
    private fun verifyScenarioResult(project: Project, scenario: String) {
        val result = ResultReader.read(layoutOf(project).resultsDir, scenario)
        project.logger.lifecycle("[mc-testkit] E2E 场景 $scenario 通过：${result.message}")
    }

    // ── 任务注册原语（用显式 Java API + Action，避免 kotlin-dsl 扩展在插件源里的重载歧义）──

    /**
     * 注册一个 [DefaultTask] 并配置之，返回 [TaskProvider]（懒注册：不立即创建任务）。
     *
     * 用「先 `register(name, type)` 再 `provider.configure(Action)`」两步式——避开 kotlin-dsl 扩展与
     * Gradle 多个 `register` 重载在插件 `src/main/kotlin` 里的解析歧义，显式可控。
     */
    private fun registerTask(
        project: Project,
        name: String,
        configure: (Task) -> Unit,
    ): TaskProvider<DefaultTask> {
        val provider = project.tasks.register(name, DefaultTask::class.java)
        provider.configure(object : Action<DefaultTask> {
            override fun execute(task: DefaultTask) = configure(task)
        })
        return provider
    }

    /** 注册一个 [Exec] 任务并配置之，返回 [TaskProvider]（两步式，理由同 [registerTask]）。 */
    private fun registerExec(
        project: Project,
        name: String,
        configure: (Exec) -> Unit,
    ): TaskProvider<Exec> {
        val provider = project.tasks.register(name, Exec::class.java)
        provider.configure(object : Action<Exec> {
            override fun execute(task: Exec) = configure(task)
        })
        return provider
    }

    // ── 小工具 ──

    /** 解析场景运行于哪个后端：显式 `backend =` 引用 > 首个声明的后端（单后端默认）。 */
    private fun resolveScenarioBackend(topology: Topology, scenario: ScenarioSpec): ResolvedBackend {
        val ref = scenario.backend
        if (ref != null) {
            return topology.backends.firstOrNull { it.name == ref }
                ?: throw GradleException(
                    "mcTestkit 场景「${scenario.name}」引用的后端「$ref」不存在（应在 TopologyResolver 已拦截）。",
                )
        }
        return topology.backends.firstOrNull()
            ?: throw GradleException(
                "mcTestkit 场景「${scenario.name}」无可用后端：请用 backend(\"...\") 至少声明一个后端。",
            )
    }

    /** 用 Gradle `project` 解析三个根目录构造 [RunLayout]（不写死本机绝对路径）。 */
    private fun layoutOf(project: Project): RunLayout = RunLayout(
        buildDir = project.layout.buildDirectory.get().asFile,
        gradleUserHome = project.gradle.gradleUserHomeDir,
        rootDir = project.rootProject.projectDir,
    )

    /** 跨平台 npm 可执行名（Windows 为 `npm.cmd`）。 */
    private fun npmExecutable(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"

    /**
     * 代理下载版本：Velocity 用自有版本号（[McTestkitDefaults.VELOCITY_VERSION]，非 MC 版本），
     * 其余（Waterfall/BungeeCord）取后端 MC 版本（Waterfall 由 provision 层再归一为 major.minor）。
     * env `*_VERSION` 覆盖在 provision 层仍优先（见 ServerJarProvisioner.resolveVersion）。
     */
    private fun proxyDownloadVersion(platform: ProxyPlatform, backendVersion: String): String =
        if (platform == ProxyPlatform.VELOCITY) McTestkitDefaults.VELOCITY_VERSION else backendVersion

    /**
     * 写 Velocity 代理运行目录的两个文件：`velocity.toml`（modern forwarding + N server + try）+
     * `forwarding.secret`（共享 secret，与后端 paper-global velocity.secret 同值，见 [BackendVelocityConfig]）。
     *
     * @param servers 有序 (server 名, 地址) 列表，首个为默认落地服、全部入 try 作 fallback（FR-15）。
     */
    private fun writeVelocityProxyFiles(
        project: Project,
        proxyRunDir: File,
        listenPort: Int,
        servers: List<Pair<String, String>>,
    ) {
        File(proxyRunDir, "velocity.toml").writeText(velocityProxyConfigToml(listenPort, servers))
        File(proxyRunDir, VELOCITY_FORWARDING_SECRET_FILE).writeText(McTestkitDefaults.VELOCITY_FORWARDING_SECRET)
        project.logger.lifecycle(
            "[mc-testkit] 已写 Velocity 代理配置：velocity.toml + $VELOCITY_FORWARDING_SECRET_FILE" +
                "（servers: ${servers.joinToString(",") { it.first }}）",
        )
    }

    /** 温和停一个进程并删除其 pid 文件（try/finally 双保险用，吞掉收尾异常不影响主流程结论）。 */
    private fun stopProcessQuietly(project: Project, process: Process, pidFile: File) {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(15, TimeUnit.SECONDS) && process.isAlive) {
                    process.destroyForcibly()
                }
            }
        } catch (ex: Exception) {
            project.logger.warn("[mc-testkit] 收尾进程时异常（已忽略）：${ex.message}")
        } finally {
            if (pidFile.exists()) pidFile.delete()
        }
    }
}
