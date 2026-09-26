package top.wcpe.mc.testkit.task

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import top.wcpe.mc.testkit.bot.BotConnection
import top.wcpe.mc.testkit.bot.BotLauncher
import top.wcpe.mc.testkit.bot.BotProcessContext
import top.wcpe.mc.testkit.bot.botPidFile
import top.wcpe.mc.testkit.bot.stopProcessByPidFile
import top.wcpe.mc.testkit.config.BackendBungeeCordConfig
import top.wcpe.mc.testkit.config.BackendVelocityConfig
import top.wcpe.mc.testkit.config.MinecraftVersionGroup
import top.wcpe.mc.testkit.config.ProxyProtocolVersion
import top.wcpe.mc.testkit.config.StressProxyBinding
import top.wcpe.mc.testkit.config.VELOCITY_FORWARDING_SECRET_FILE
import top.wcpe.mc.testkit.config.bungeeClusterProxyConfigYml
import top.wcpe.mc.testkit.config.bungeeProxyConfigYml
import top.wcpe.mc.testkit.config.bungeeStressProxyConfigYml
import top.wcpe.mc.testkit.config.velocityForwardingModeForBackend
import top.wcpe.mc.testkit.config.velocityProxyConfigToml
import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.BotSpec
import top.wcpe.mc.testkit.dsl.HookContext
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxyPlatform
import top.wcpe.mc.testkit.dsl.ScenarioHook
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import top.wcpe.mc.testkit.dsl.ServeSpec
import top.wcpe.mc.testkit.dsl.StressSpec
import top.wcpe.mc.testkit.dsl.VersionMatrixSpec
import top.wcpe.mc.testkit.provision.JarCache
import top.wcpe.mc.testkit.provision.JavaRuntimeSelector
import top.wcpe.mc.testkit.provision.ProvisionPlatform
import top.wcpe.mc.testkit.provision.ServerJarProvisioner
import top.wcpe.mc.testkit.provision.ServerLauncher
import top.wcpe.mc.testkit.provision.WaterfallModuleProvisioner
import top.wcpe.mc.testkit.provision.backendServerArgs
import top.wcpe.mc.testkit.provision.provisionPidFile
import top.wcpe.mc.testkit.topology.ResolvedBackend
import top.wcpe.mc.testkit.topology.ResolvedProxy
import top.wcpe.mc.testkit.topology.Topology
import top.wcpe.mc.testkit.topology.TopologyResolver
import top.wcpe.mc.testkit.verify.ResultReader
import java.io.File
import java.util.concurrent.TimeUnit

/** Gradle 任务分组名（生成的 e2e 任务都归此组，`./gradlew tasks` 下成组展示）。 */
private const val TASK_GROUP = "mc-testkit e2e"

/** 持久手测（serve）任务分组名（与 e2e 测试任务分开展示，语义是「起服供人手测」而非「跑测试」）。 */
private const val SERVE_TASK_GROUP = "mc-testkit serve"

/** Gradle 属性名：覆盖机器人目录（缺省 [RunLayout.DEFAULT_BOT_DIR]）。 */
private const val BOT_DIR_PROPERTY = "mcTestkit.botDir"

/** 等结果文件写出的上限（秒）：超时仍无结果即视为桩未正常收尾 / 启动失败。 */
private const val BACKEND_WAIT_TIMEOUT_SECONDS = 600L

/** 结果文件写出后，给后端 JVM 优雅自停的窗口（秒）；到时仍未退则强杀（不空等到上限）。 */
private const val BACKEND_SELF_STOP_GRACE_SECONDS = 30L

/** 后端与代理都需关闭 ANSI，消费者节点参数在其后、Java agent 最后追加。 */
private val FRAMEWORK_JVM_ARGS =
    listOf("-Dterminal.ansi=false", "-Dnet.kyori.ansi.colorLevel=none", "-Dtaboolib.debug=true")

/**
 * 落盘一个服务端 / 代理进程的收尾凭证：pid 文件（当前一轮的快路径）+ 进程台账（跨轮次的兜底路径）。
 *
 * 两处都写是有意的冗余，各管一段生命周期，缺一不可（见 [ProcessLedger] 与 [stopTrackedProcesses]）：
 * - **pid 文件**是当前一轮的凭证，被停任务优先读取；但它会被下一轮起服覆盖、随运行目录清理消失。
 * - **台账**落结果目录（无任何清理点，随 `build/` 走），故上一轮被强杀留下的进程仍能被找回来。
 *
 * @param ctx 任务执行上下文（提供结果目录）。
 * @param pidFile 本轮的 pid 文件（位置随调用方，集群 / 代理落结果目录、单 serve 落运行目录）。
 * @param ledgerKey 台账 key（`backend/<名>` 等，同一逻辑进程重复登记即覆盖为最新一轮）。
 * @param process 刚起的进程。
 * @param port 监听端口（进台账，供收尾后复验端口是否真的释放）。
 */
private fun recordProcessedPid(
    ctx: TaskExecutionContext,
    pidFile: File,
    ledgerKey: String,
    process: Process,
    port: Int,
) {
    pidFile.apply { parentFile?.mkdirs() }.writeText(process.pid().toString())
    ProcessLedger.record(ctx.layout.resultsDir, ledgerKey, process.pid(), port)
}

/**
 * 起 bot 前等后端 / 代理端口可连的就绪门上限（秒）。
 *
 * 这是**确定性就绪门**而非定时等待：等多久取决于进程何时真正接受连接（快环境几秒、慢 CI 久些），
 * 到点才放 bot——不靠 bot 盲重试去赛进程启动，慢 runner 上也稳；端口迟迟不开（启动失败）则到此上限报错。
 */
private const val PORT_READINESS_TIMEOUT_SECONDS = 300L

/**
 * 任务执行期上下文快照（配置缓存兼容）。
 *
 * 注册期（配置期）从 [Project] 一次性提取动作执行所需的全部**可序列化**值；任务动作闭包（doLast /
 * shutdown hook）只捕获本对象与拓扑 / 声明数据（均已实现 Serializable），**绝不捕获 `Project`**——
 * 否则 Gradle 9.x 配置缓存存储阶段报 `cannot serialize DefaultProject`、构建失败（退出码非零）。
 *
 * 执行期按名读环境变量统一走 [readEnv]（`System.getenv`，与 Gradle `providers.environmentVariable`
 * 取值等价），避免捕获 `ProviderFactory`；相对路径解析基准与日志器同样在此固化。
 *
 * @property logger Gradle 日志器（注册期自 `project.logger` 提取）。
 * @property layout 运行目录布局（注册期自 project.layout / gradle / 根工程解析）。
 * @property projectDir 消费工程目录（节点资源相对路径的解析基准）。
 * @property botDir 机器人目录（注册期按 Gradle 属性 `mcTestkit.botDir` 解析定形）。
 * @property dependencies 依赖注入声明快照（注册期自 DSL 提取；执行期解析 jar）。
 */
internal class TaskExecutionContext(
    val logger: Logger,
    val layout: RunLayout,
    val projectDir: File,
    val botDir: File,
    val dependencies: DependencyDeclarations,
) : java.io.Serializable {
    /** 任务执行期按名读环境变量（不捕获任何 Gradle 对象，兼容配置缓存序列化）。 */
    fun readEnv(name: String): String? = System.getenv(name)

    /** 中文 lifecycle 日志（统一 `[mc-testkit]` 前缀）。 */
    fun info(message: String) {
        logger.lifecycle("[mc-testkit] $message")
    }

    /** 中文 warn 日志（统一 `[mc-testkit]` 前缀）。 */
    fun warn(message: String) {
        logger.warn("[mc-testkit] $message")
    }
}

/**
 * 任务编排装配入口（任务自动编排 整合器）。
 *
 * 在 [McTestkitPlugin][top.wcpe.mc.testkit.McTestkitPlugin] 的 `apply()` 末尾、`afterEvaluate` 里调用：
 * 先经 [TopologyResolver.resolve] 复用 拓扑 DSL 配置期校验（无环 / 命名 / 路由 / 端口 / 场景引用，
 * 失败抛**中文** `GradleException`），再按 `mcTestkit { }` 声明**数据驱动**注册任务（命名严格按
 * [McTestkitTaskNames]）。任务体的副作用（下载 / 起进程 / 判定）全放 `doLast`，**配置期只注册不执行**
 * （TestKit 不联网 / 不起进程仍可过任务注册与 `help`；真实跑通属 首个消费者验证 实机维度）。
 */
object McTestkitTasks {

    /** 装配全部 e2e 任务。 */
    fun register(project: Project, extension: McTestkitExtension) {
        // ① 复用 拓扑 DSL 解析 + 配置期校验（失败即抛中文 GradleException，阻断后续注册）
        val topology = TopologyResolver.resolve(extension)

        // ①' 多 bot 展开后 key/username 唯一性校验（单场景多 bot）：TopologyResolver 只查 role 唯一，
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
        // serve 的可选 bot（serve 人机混场）同样查展开后 key/username 唯一，杜绝 pid 互相覆盖致收尾漏杀残留
        extension.declaredServes.forEach { serve ->
            BotProcessPlanner.firstConflict(serve.name, serve.botSpecs)?.let { conflict ->
                throw GradleException(
                    "mcTestkit serve「${serve.name}」多 bot 展开后$conflict；" +
                        "请改用唯一的角色名 / 用户名 / count 组合（注意 count>1 会派生「<角色>-<序号>」，勿与其它角色撞名）。",
                )
            }
        }

        // ② 配置期一次性提取动作执行上下文快照（全部可序列化）：动作闭包捕获 ctx 而非 Project，
        //    兼容 Gradle 9.x 配置缓存（捕获 Project 会在存储阶段报 cannot serialize DefaultProject）
        val ctx = executionContextOf(project, extension)

        // ②' Maven 坐标来源（依赖插件 + 服务端/代理 jar）：只传给需要它们的任务注册点，
        //     避免无关任务触发仓库访问；服务端坐标在注册期先查镜像，命中即不创建解析配置
        val mavenSources = MavenCoordinateSources.of(
            project = project,
            cache = JarCache(layoutOf(project).jarCacheRoot),
            pluginCoordinates = extension.declaredDependencies.mavenPlugins.toList(),
            serverCoordinates = serverCoordinatesOf(extension),
        )

        // ③ 固定名任务（npm 安装 / 缓存回写 / 清缓存）
        registerFixedTasks(project, ctx)

        // ④ 数据驱动：每个场景注册 prepare / e2e（+ bot 时 launch / withBot；+ via 时经代理任务）
        extension.declaredScenarios.forEach { scenario ->
            registerScenarioTasks(project, ctx, topology, scenario, mavenSources)
        }

        // ⑤ 持久手测：每个 serve 注册 serve<Key> + stop<Key>Serve（持久手测 serve，ADR-0011）
        extension.declaredServes.forEach { serve ->
            registerServeTasks(project, ctx, topology, serve, mavenSources)
        }

        // ⑥ 版本矩阵聚合：e2eMatrix<Key> / e2eMatrix<Key>SmokeOnly + mustRunAfter 串行链
        extension.declaredVersionMatrices.forEach { matrix ->
            registerVersionMatrixAggregate(project, matrix)
        }
    }

    /**
     * 为版本矩阵注册串行聚合任务。
     *
     * 场景任务本身已由 [registerScenarioTasks] 注册；此处只挂聚合 dependsOn 与 mustRunAfter 链，
     * 避免 `org.gradle.parallel=true` 时多个 Paper 端口/下载缓存踩踏。
     */
    private fun registerVersionMatrixAggregate(
        project: Project,
        matrix: VersionMatrixSpec,
    ) {
        val orderedTaskNames = matrix.entries.map { entry ->
            val scenarioName = if (entry.bot) "full-${entry.key}" else "smoke-${entry.key}"
            if (entry.bot) {
                McTestkitTaskNames.withBot(scenarioName)
            } else {
                McTestkitTaskNames.verify(scenarioName)
            }
        }
        val smokeTaskNames = matrix.entries.filter { !it.bot }.map { entry ->
            McTestkitTaskNames.verify("smoke-${entry.key}")
        }

        fun chainSerial(names: List<String>) {
            for (i in 1 until names.size) {
                project.tasks.named(names[i]).configure { mustRunAfter(names[i - 1]) }
            }
        }

        registerTask(project, McTestkitTaskNames.versionMatrix(matrix.name)) { task ->
            task.group = TASK_GROUP
            task.description =
                "串行跑版本矩阵「${matrix.name}」全部场景（${orderedTaskNames.size} 个）"
            task.dependsOn(orderedTaskNames)
        }
        chainSerial(orderedTaskNames)

        if (smokeTaskNames.isNotEmpty()) {
            registerTask(project, McTestkitTaskNames.versionMatrixSmokeOnly(matrix.name)) { task ->
                task.group = TASK_GROUP
                task.description =
                    "串行跑版本矩阵「${matrix.name}」smoke 子集（${smokeTaskNames.size} 个，无 bot）"
                task.dependsOn(smokeTaskNames)
            }
            chainSerial(smokeTaskNames)
        }
    }

    /**
     * 收集全部**服务端 / 代理 jar 的 Maven 坐标**声明（后端在前、代理在后，各自按声明顺序）。
     *
     * 去重后返回：同一坐标（如两个后端共用同一服务端构件）只需一份镜像与一份解析配置。
     */
    private fun serverCoordinatesOf(extension: McTestkitExtension): List<String> =
        (
            extension.declaredBackends.mapNotNull { it.mavenServer } +
                extension.declaredProxies.mapNotNull { it.mavenServer }
            ).distinct()

    /** 配置期一次性提取动作执行上下文快照（全部可序列化，供动作闭包安全捕获，兼容配置缓存）。 */
    private fun executionContextOf(project: Project, extension: McTestkitExtension): TaskExecutionContext {
        val layout = layoutOf(project)
        return TaskExecutionContext(
            logger = project.logger,
            layout = layout,
            projectDir = project.projectDir,
            botDir = layout.botDir(project.findProperty(BOT_DIR_PROPERTY)?.toString()),
            dependencies = DependencyDeclarations(
                pluginUnderTest = extension.declaredDependencies.pluginUnderTest,
                plugins = extension.declaredDependencies.plugins.toList(),
                pluginUnderTestSelfJar = extension.declaredDependencies.selfJar,
                mavenPlugins = extension.declaredDependencies.mavenPlugins.toList(),
            ),
        )
    }

    /** 注册三个固定名任务（[McTestkitTaskNames] 常量）。 */
    private fun registerFixedTasks(project: Project, ctx: TaskExecutionContext) {
        val layout = ctx.layout

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
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val cacheDir = ctx.layout.persistentServerBaseDir
                cacheDir.mkdirs()
                // 汇总所有后端运行目录（含历史共享 run/ 与新的 run-<backend>/）
                val runDirs = (
                    ctx.layout.workRoot.listFiles()?.filter {
                        it.isDirectory && (it.name == "run" || it.name.startsWith("run-"))
                    } ?: emptyList()
                    ) + listOf(ctx.layout.runDir)
                runDirs.distinctBy { it.absolutePath }.forEach { runDir ->
                    RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES.forEach { name ->
                        val src = File(runDir, name)
                        if (src.exists()) {
                            src.copyRecursively(File(cacheDir, name), overwrite = true)
                        }
                    }
                }
                ctx.info("已更新持久缓存：${cacheDir.absolutePath}")
            }
        }

        registerTask(project, McTestkitTaskNames.PURGE_RUNTIME_CACHE) { task ->
            task.group = TASK_GROUP
            task.description = "清空持久缓存目录（下次运行重新填充）"
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val cacheDir = ctx.layout.persistentServerBaseDir
                cacheDir.deleteRecursively()
                ctx.info("已清空持久缓存：${cacheDir.absolutePath}")
            }
        }
    }

    /** 场景钩子任务引用（供各场景路径接线）。 */
    private class ScenarioHookTasks(
        val before: TaskProvider<DefaultTask>?,
        val ready: TaskProvider<DefaultTask>?,
        val after: TaskProvider<DefaultTask>?,
    )

    /**
     * 注册场景的钩子任务（各形态共用）。
     *
     * - `before<Key>Scenario`：**不自行接线**——各场景路径按自己的前置任务接它（直连 / 经代理接 `prepare`，
     *   集群接其自身的集群任务）。此前这里硬接 `prepareE2e<Key>` 是错的：集群场景**不生成**该任务，
     *   导致集群场景声明 `beforeScenario` 时报 `Task with path 'prepareE2e…' not found`。
     * - `after<Key>Scenario`：不自动挂载，由各场景任务 `finalizedBy`（三路径都执行）
     *
     * `readyScenario` 不在任务层实现——它需要「节点已就绪」这一时刻，而该时刻只存在于集群任务的执行体内
     * （端口就绪门之后），故由集群任务在体内直接调用，此处不生成任务。
     */
    private fun registerScenarioHookTasks(
        project: Project,
        ctx: TaskExecutionContext,
        topology: Topology,
        scenario: ScenarioSpec,
    ): ScenarioHookTasks {
        // 钩子上下文的后端集合：集群用背的 backends，其余用单后端（与场景实际起服的节点一致）
        val backendNames = if (scenario.backendRefs.isNotEmpty()) {
            scenario.backendRefs
        } else {
            listOfNotNull(resolveScenarioBackend(topology, scenario).name)
        }
        val proxyName = scenario.via
        val hookCtx = hookContext(ctx, scenario.name, backendNames, proxyName)

        val before = if (scenario.beforeHooks.isNotEmpty()) {
            registerTask(project, McTestkitTaskNames.beforeScenario(scenario.name)) { task ->
                task.group = TASK_GROUP
                task.description = "执行场景 ${scenario.name} 的场景前钩子（起外部依赖、等就绪）"
                task.doLast {
                    // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                    runBeforeHooks(ctx, scenario.beforeHooks, hookCtx)
                }
            }
        } else {
            null
        }

        val after = if (scenario.afterHooks.isNotEmpty()) {
            registerTask(project, McTestkitTaskNames.afterScenario(scenario.name)) { task ->
                task.group = TASK_GROUP
                task.description = "执行场景 ${scenario.name} 的场景后钩子（收尾外部依赖）"
                task.doLast {
                    // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                    runAfterHooks(ctx, scenario.afterHooks, hookCtx)
                }
            }
        } else {
            null
        }

        return ScenarioHookTasks(before = before, ready = null, after = after)
    }

    /**
     * 校验场景钩子与场景形态的兼容性（配置期，失败抛中文异常）。
     *
     * `beforeScenario` / `afterScenario` 在全部形态下都支持；`readyScenario` 需要「节点已就绪」这一明确时刻，
     * 目前仅**集群场景**提供，故在其他形态下声明会报错——不静默忽略。
     */
    private fun validateScenarioHooks(scenario: ScenarioSpec) {
        if (scenario.readyHooks.isEmpty()) return
        val isCluster = scenario.backendRefs.isNotEmpty() && scenario.stressSpec == null
        if (isCluster) return
        val shape = when {
            scenario.stressSpec != null -> "压测场景"
            scenario.backendRefs.isNotEmpty() -> "集群场景"
            scenario.via != null -> "经代理场景"
            else -> "直连后端场景"
        }
        throw GradleException(
            "mcTestkit 场景「${scenario.name}」是$shape，不支持 readyScenario 钩子：该钩子需要「全部节点已就绪」" +
                "这一明确时刻，目前仅集群场景（同时声明 backends 且经代理）提供。" +
                "请改用 beforeScenario（服务端启动前）或 afterScenario（场景结束后），" +
                "或把场景改造为集群形态。",
        )
    }

    /** 为单个场景注册其全部任务。 */
    private fun registerScenarioTasks(
        project: Project,
        ctx: TaskExecutionContext,
        topology: Topology,
        scenario: ScenarioSpec,
        mavenSources: MavenCoordinateSources,
    ) {
        // 钩子与场景形态的兼容性在**配置期**校验：不支持的组合直接报错，不静默忽略
        // （静默忽略会让消费方以为初始化生效了，实际没跑，排查成本极高）。
        validateScenarioHooks(scenario)

        // 钩子任务在所有场景形态下统一注册（早于各路径的分发）：
        // 直连 / 经代理 / 集群 / 压测四条路径都要能声明 beforeScenario 与 afterScenario，
        // 故在此注册一次并向下传递任务引用，避免各路径各自注册造成命名冲突或漏挂。
        val hookTasks = registerScenarioHookTasks(project, ctx, topology, scenario)

        // 压测场景（声明 stress）走 N 服 × M bot 钉服编排（压测编排，ADR-0008）：不生成单后端 / 集群任务
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
            registerStressTask(project, ctx, scenario, stress, stressBackends, proxy, mavenSources, hookTasks)
            return
        }

        // 集群场景（声明 backends、无 stress）走多后端切换编排（集群编排，ADR-0008）：不生成单后端任务
        if (scenario.backendRefs.isNotEmpty()) {
            val clusterBackends = scenario.backendRefs.map { name ->
                topology.backends.first { it.name == name } // 已由 TopologyResolver 校验存在
            }
            val proxy = topology.proxies.first { it.name == scenario.via } // 集群必有 via 且已校验存在
            registerClusterTask(project, ctx, scenario, clusterBackends, proxy, mavenSources, hookTasks)
            return
        }

        val backend = resolveScenarioBackend(topology, scenario)
        val viaProxy = scenario.via?.let { via -> topology.proxies.first { it.name == via } }
        val prepareName = McTestkitTaskNames.prepare(scenario.name)

        // prepare：先预检本任务涉及的后端资源，再准备后端运行目录。
        // 注：经代理任务的代理资源由其自身预检（动作闭包间不再共享预检结果——跨任务共享可变状态
        //   不兼容 Gradle 配置缓存，且代理资源缺失仍会在经代理任务执行期得到同样的中文报错）。
        // prepare 只注入插件、不起服务端，故只捕获依赖插件坐标（见 pluginsOnly 的说明）。
        val prepareSources = mavenSources.pluginsOnly()
        val prepare = registerTask(project, prepareName) { task ->
            task.group = TASK_GROUP
            task.description = "准备场景 ${scenario.name} 的运行目录（注入插件、写配置）"
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val runtime = preflightRuntimeForTask(ctx, listOf(backend), mavenSources = prepareSources)
                clearPreviousScenarioResult(ctx.layout.resultsDir, scenario.name)
                val backendRunDir = ctx.layout.backendRunDir(backend.name)
                prepareRunDirectory(ctx, backend, backendRunDir, runtime.backends.getValue(backend.name))
                if (viaProxy?.platform == ProxyPlatform.VELOCITY) {
                    BackendVelocityConfig.apply(backendRunDir, backend.version)
                }
            }
        }

        // e2e（直连后端）：prepare → 前台起后端（自停 waitFor）→ 读结果文件判定
        // bot 必须先于后端跑完连上，故先注册 launch（若有），再在 verify 注册块里 mustRunAfter，避免回头 configure。
        val hasBots = scenario.botSpecs.isNotEmpty()
        // 钩子任务已在场景分发处统一注册（registerScenarioHookTasks），此处只接线
        val beforeHooks = hookTasks.before
        val afterHooks = hookTasks.after

        val launch: TaskProvider<DefaultTask>? = if (hasBots) {
            registerTask(project, McTestkitTaskNames.launchBot(scenario.name)) { task ->
                task.group = TASK_GROUP
                task.description = "启动场景 ${scenario.name} 的 mineflayer 机器人（声明多 bot 时起多个进程）"
                task.dependsOn(prepare, McTestkitTaskNames.NPM_INSTALL_BOT)
                beforeHooks?.let { task.dependsOn(it) }
                task.doLast {
                    // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                    launchScenarioBots(
                        ctx,
                        scenario,
                        backendVersion = backend.version,
                        backendPort = backend.port,
                        protocolVersion = null,
                    )
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
            // 无 bot 时没有 launch 任务承载「场景前」时序，钩子改挂到 verify 之前
            if (launch == null) beforeHooks?.let { task.dependsOn(it) }
            // 收尾：场景后钩子在判定结束后执行（含失败路径）
            afterHooks?.let { task.finalizedBy(it) }
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                try {
                    runBackendForeground(ctx, backend, scenario.name, mavenSources)
                    verifyScenarioResult(ctx, scenario.name)
                } finally {
                    // 成功 / 失败都收尾全部 bot（多 bot 防残留；单 bot 已自停为安全 no-op）
                    if (hasBots) stopScenarioBots(ctx, scenario)
                }
            }
        }

        // 自测模式（pluginUnderTest 未声明，默认注入本模块 jar 产物）：prepare / e2e 自动依赖 `jar`
        // 任务，保证测之前插件已打包。**不能依赖 taboolibMainTask**——它只是 `jar` 的 finalizer，
        // 单独调度不会带上 `jar`（CI 全新检出时 prepare 前置校验即失败）。
        // 经代理任务 dependsOn(prepareName) 传递获得同一依赖，无需重复接线。
        if (ctx.dependencies.pluginUnderTestSelfJar) {
            val jarTask = project.tasks.named("jar")
            // TaskProvider 无 dependsOn 方法；此处任务刚注册、仍在 afterEvaluate 内，get() 实现安全
            prepare.get().dependsOn(jarTask)
            verify.get().dependsOn(jarTask)
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
        viaProxy?.let { proxy ->
            registerViaProxyTask(project, ctx, scenario, backend, proxy, mavenSources, hookTasks)
        }
    }

    /** 注册经代理任务：prepare → 起代理（后台）→ 起 bot（经代理端口、固定协议版本）→ 前台起后端 → 验证 → finalizedBy 停代理。 */
    private fun registerViaProxyTask(
        project: Project,
        ctx: TaskExecutionContext,
        scenario: ScenarioSpec,
        backend: ResolvedBackend,
        proxy: ResolvedProxy,
        mavenSources: MavenCoordinateSources,
        hookTasks: ScenarioHookTasks,
    ) {
        val layout = ctx.layout
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
                    // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                    val summary = stopTrackedProcesses(ctx.layout.resultsDir, listOf(proxyPidFile), ctx::info)
                    reportStopOutcome(
                        ctx,
                        summary,
                        listOf(proxy.port),
                        "上一轮残留代理进程仍占端口（pid 记录可能已随运行目录清理丢失）",
                    )
                }
            }
        }

        registerTask(project, McTestkitTaskNames.verifyVia(scenario.name, proxy.name)) { task ->
            task.group = TASK_GROUP
            task.description = "经代理 ${proxy.name} 运行场景 ${scenario.name}（bot 连代理 → 后端 → 判定）"
            task.dependsOn(prepareName)
            // 正常 / 失败 / 中断三路径都收尾停代理（finalizedBy）；任务体内再加 try/finally 双保险
            task.finalizedBy(stopProxyName)
            // 钩子接线：场景前须先于起代理，场景后与停代理同层收尾
            hookTasks.before?.let { task.dependsOn(it) }
            hookTasks.after?.let { task.finalizedBy(it) }
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                var proxyProcess: Process? = null
                try {
                    // 代理资源由本任务自足预检（与 prepare 不共享可变状态，兼容配置缓存）
                    val runtime = preflightRuntimeForTask(ctx, listOf(backend), listOf(proxy), mavenSources)
                    // ⓪ 端口预检：代理与后端端口被占即中文失败（见 requirePortsAvailable 的说明）
                    requirePortsAvailable(
                        listOf(
                            PortTarget(proxy.port, "代理 ${proxy.name}"),
                            PortTarget(backend.port, "后端 ${backend.name}"),
                        ),
                        stopTaskName = stopProxyName,
                    )
                    // ① 后端切到代理模式：BungeeCord 系走三件套，Velocity 走 modern forwarding 两件套（含共享 secret）
                    val backendRunDir = layout.backendRunDir(backend.name)
                    if (isBungeeMode) {
                        BackendBungeeCordConfig.apply(backendRunDir, backend.version)
                    } else {
                        BackendVelocityConfig.apply(backendRunDir, backend.version)
                    }
                    // ② 后台起代理（写 pid 供收尾）
                    proxyProcess = startProxyBackground(
                        ctx,
                        proxy,
                        backend,
                        runtime.proxies.getValue(proxy.name),
                        scenario.name,
                        mavenSources,
                    )
                    // ③ 起全部 bot：经代理端口进服，协议版本固定为后端版本（环境契约；多 bot 各唯一名）
                    launchScenarioBots(
                        ctx,
                        scenario,
                        backendVersion = backend.version,
                        backendPort = proxy.port,
                        protocolVersion = ProxyProtocolVersion.forBackend(backend.version),
                    )
                    // ④ 前台起后端（自停 waitFor）
                    runBackendForeground(ctx, backend, scenario.name, mavenSources)
                    // ⑤ 只认结果文件判定
                    verifyScenarioResult(ctx, scenario.name)
                } finally {
                    // 双保险：先按 pid 收尾全部 bot（多 bot 防残留），再收尾代理（即便 finalizedBy 未触发）
                    if (scenario.botSpecs.isNotEmpty()) stopScenarioBots(ctx, scenario)
                    proxyProcess?.let { stopProcessQuietly(ctx, it, proxyPidFile) }
                }
            }
        }
    }

    /**
     * 注册集群任务（集群编排，ADR-0008）：N 后端**全部后台**起 + 代理（单 listener + N server）+ 切换 bot →
     * 以结果文件为权威完成信号轮询 → 判定；正常/失败/中断三路径都 finalizedBy + try/finally 双保险收尾
     * 全部后端 + 代理，端口干净（高风险区）。
     */
    private fun registerClusterTask(
        project: Project,
        ctx: TaskExecutionContext,
        scenario: ScenarioSpec,
        clusterBackends: List<ResolvedBackend>,
        proxy: ResolvedProxy,
        mavenSources: MavenCoordinateSources,
        hookTasks: ScenarioHookTasks,
    ) {
        val layout = ctx.layout
        val stopName = McTestkitTaskNames.stopCluster(scenario.name)
        // 全部 bot 的 pid key（多 bot 各一支；停任务据此按 pid 收尾，防 straggler 残留）
        val botKeys = BotProcessPlanner.expand(scenario.name, scenario.botSpecs).map { it.key }

        // 停集群任务：按 pid 收尾全部后端 + 代理 + 全部 bot（单独可调；集群任务 finalizedBy 它）
        registerTask(project, stopName) { task ->
            task.group = TASK_GROUP
            task.description = "停止集群场景 ${scenario.name} 的全部后端、代理与机器人（按 pid 收尾）"
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容配置缓存
                val summary = stopTrackedProcesses(
                    layout.resultsDir,
                    clusterBackends.map { layout.clusterBackendPidFile(it.name) } +
                        layout.proxyPidFile(proxy.name) +
                        botKeys.map { botPidFile(layout.resultsDir, it) },
                    ctx::info,
                )
                reportStopOutcome(
                    ctx,
                    summary,
                    clusterBackends.map { it.port } + proxy.port,
                    "上一轮残留进程仍占端口" +
                        "（多为前一次构建被强杀、pid 记录已随运行目录清理丢失）",
                )
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
            // 场景后钩子（统一注册）同样挂 finalizedBy：与集群收尾同层，三路径都执行
            hookTasks.after?.let { task.finalizedBy(it) }
            // 场景前/就绪后钩子由统一注册的 before 任务与体内调用承载；集群路径需在起服前显式等 before
            hookTasks.before?.let { task.dependsOn(it) }
            val clusterHookCtx = hookContext(ctx, scenario.name, clusterBackends.map { it.name }, proxy.name)
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val backendProcesses = LinkedHashMap<String, Process>()
                var proxyProcess: Process? = null
                val botProcesses = mutableListOf<Process>()
                try {
                    val runtime = preflightRuntimeForTask(ctx, clusterBackends, listOf(proxy), mavenSources)
                    layout.resultsDir.mkdirs()
                    val resultFile = File(layout.resultsDir, McTestkitResultFile.fileName(scenario.name))
                    if (resultFile.exists()) resultFile.delete() // 清上轮结果，避免误判

                    // ⓪ 端口预检：任一后端 / 代理端口被占即中文失败，避免「就绪门被上一轮残留进程误判为已就绪
                    //    → 新进程 bind 失败」白等一轮（进程生命周期与收尾 高风险区）
                    requirePortsAvailable(
                        clusterBackends.map { PortTarget(it.port, "集群后端 ${it.name}") } +
                            PortTarget(proxy.port, "集群代理 ${proxy.name}"),
                        stopTaskName = stopName,
                    )
                    // 场景前钩子已由 before<Key>Scenario 任务承载（task.dependsOn 保证在起服前执行），
                    // 故此处不再重复调用——重复执行会让「起控制面」这类非幂等钩子起两份进程。

                    // ① 每后端独立运行目录 prepare + BungeeCord 模式 + 后台起（同 SCENARIO / RESULT_FILE）
                    val sameVersionPredecessors = sameVersionStartupPredecessors(
                        clusterBackends.map { backend -> backend.name to backend.version },
                    )
                    clusterBackends.forEach { backend ->
                        sameVersionPredecessors[backend.name]?.let { predecessor ->
                            val prior = clusterBackends.first { candidate -> candidate.name == predecessor }
                            awaitPortOpen(ctx, prior.port, "同版本集群后端 ${prior.name}")
                            copyRuntimeCaches(
                                layout.clusterBackendRunDir(prior.name),
                                layout.clusterBackendRunDir(backend.name),
                            )
                        }
                        val runDir = layout.clusterBackendRunDir(backend.name)
                        prepareRunDirectory(ctx, backend, runDir, runtime.backends.getValue(backend.name))
                        if (proxy.platform == ProxyPlatform.VELOCITY) {
                            BackendVelocityConfig.apply(runDir, backend.version)
                        } else {
                            BackendBungeeCordConfig.apply(runDir, backend.version)
                        }
                        backendProcesses[backend.name] =
                            startBackendBackground(ctx, backend, runDir, scenario.name, resultFile, mavenSources)
                    }
                    // ② 后台起集群代理（单 listener + N 具名 server）
                    proxyProcess = startClusterProxyBackground(
                        ctx,
                        proxy,
                        clusterBackends,
                        runtime.proxies.getValue(proxy.name),
                        scenario.name,
                        resultFile,
                        mavenSources,
                    )
                    // ②' 确定性就绪门：等全部后端 + 代理端口可连再起 bot（不靠 bot 盲重试赛慢启动，慢 CI 上稳）
                    clusterBackends.forEach { awaitPortOpen(ctx, it.port, "集群后端 ${it.name}") }
                    awaitPortOpen(ctx, proxy.port, "集群代理 ${proxy.name}")
                    // ②'' 节点就绪后钩子：服务端已可连，此时做依赖服务端的初始化
                    //      （注册审批、造数、下发配置）——必须先于 bot 启动，否则 bot 会被未审批的鉴权拒绝
                    runReadyHooks(ctx, scenario.readyHooks, clusterHookCtx)
                    // ③ 起全部 bot：经代理端口，CLUSTER_BACKENDS 下发 /server 切换目标（每个 bot 都能切），
                    //    协议版本固定为后端版本；多 bot 各唯一 username、同质复制下发 BOT_INDEX（单场景多 bot）
                    botProcesses += launchScenarioBots(
                        ctx,
                        scenario,
                        backendVersion = clusterBackends.first().version,
                        backendPort = proxy.port,
                        protocolVersion = ProxyProtocolVersion.forBackend(clusterBackends.first().version),
                        sharedExtraEnv = mapOf(
                            McTestkitEnv.CLUSTER_BACKENDS to clusterBackends.joinToString(",") { it.name },
                        ),
                    )
                    // ④ 轮询结果文件（任一桩写出即完成）
                    awaitClusterResult(ctx, resultFile, scenario.name)
                    // ⑤ 只认结果文件判定
                    verifyScenarioResult(ctx, scenario.name)
                } finally {
                    // 双保险收尾：全部 bot（自停兜底）+ 后端 + 代理（即便 finalizedBy 未触发）
                    botProcesses.forEach { destroyProcessQuietly(ctx, it) }
                    backendProcesses.forEach { (name, proc) ->
                        stopProcessQuietly(ctx, proc, layout.clusterBackendPidFile(name))
                    }
                    proxyProcess?.let { stopProcessQuietly(ctx, it, layout.proxyPidFile(proxy.name)) }
                }
            }
        }
    }

    /** 后台起一个集群后端（不等自停，pid 落结果目录供收尾）；同 SCENARIO / RESULT_FILE 交接 env。 */
    private fun startBackendBackground(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        runDir: File,
        scenario: String,
        resultFile: File,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): Process {
        val layout = ctx.layout
        val jar = resolveBackendJar(ctx, backend, sources)
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = runDir,
            key = backend.name,
            jvmArgs = backendJvmArgs(ctx, backend),
            serverArgs = backendServerArgs(backend.version),
            environment = mergeNodeEnvironment(
                backend.environment,
                mapOf(
                    McTestkitEnv.SCENARIO to scenario,
                    McTestkitEnv.RESULT_FILE to resultFile.absolutePath,
                    McTestkitEnv.BACKEND_NAME to backend.name,
                ),
            ),
            javaPath = resolveBackendJava(ctx, backend),
            logger = { ctx.info(it) },
        )
        recordProcessedPid(
            ctx,
            layout.clusterBackendPidFile(backend.name),
            "$LEDGER_KEY_BACKEND/${backend.name}",
            process,
            backend.port,
        )
        ctx.info("已后台启动集群后端 ${backend.name} pid=${process.pid()} 端口=${backend.port}")
        return process
    }

    /** 后台起集群代理（单 listener + N 具名 server，供 bot /server 切换）。 */
    private fun startClusterProxyBackground(
        ctx: TaskExecutionContext,
        proxy: ResolvedProxy,
        clusterBackends: List<ResolvedBackend>,
        resources: ProxyRuntimeResources,
        scenario: String? = null,
        resultFile: File? = null,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): Process {
        val layout = ctx.layout
        val proxyRunDir = layout.proxyRunDir
        val requestedVersion = proxyDownloadVersion(proxy, clusterBackends.first().version)
        stageProxyRuntime(
            proxyRunDir,
            resources,
            writeFrameworkConfiguration = {
                writeClusterProxyConfiguration(ctx, proxyRunDir, proxy, clusterBackends)
            },
            preparePlatformRuntime = {
                provisionWaterfallModulesIfNeeded(ctx, proxy.platform, requestedVersion, proxyRunDir)
            },
            logger = { ctx.info(it) },
        )
        val jar = resolveNodeJar(ctx, proxy.platform.name.lowercase(), requestedVersion, proxy.mavenServer, sources)
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = proxyRunDir,
            key = proxy.name,
            jvmArgs = proxyJvmArgs(ctx, proxy),
            environment = mergeNodeEnvironment(
                proxy.environment,
                if (scenario != null && resultFile != null) {
                    mapOf(
                        McTestkitEnv.SCENARIO to scenario,
                        McTestkitEnv.RESULT_FILE to resultFile.absolutePath,
                    )
                } else {
                    emptyMap()
                },
            ),
            javaPath = resolveProxyJava(ctx, proxy),
            logger = { ctx.info(it) },
        )
        recordProcessedPid(ctx, layout.proxyPidFile(proxy.name), "$LEDGER_KEY_PROXY/${proxy.name}", process, proxy.port)
        ctx.info(
            "已启动集群代理 ${proxy.name} pid=${process.pid()} 监听端口=${proxy.port}" +
                "（servers: ${clusterBackends.joinToString(",") { it.name }}）",
        )
        return process
    }

    /**
     * 确定性就绪门：轮询等某端口可 TCP 连接再返回（Paper 在启动末尾才绑监听端口，端口可连≈服务端就绪、桩已起）。
     *
     * 用于在起 bot **之前**确认后端 / 代理真正接受连接，避免 bot 在进程尚未就绪时盲目重试去赛启动——慢 CI
     * （多服顺序起服、CPU 紧张）上靠拉长超时碰运气不稳，靠就绪门则确定性等到位再连。端口迟迟不开则报错收尾。
     */
    /**
     * 执行「节点就绪后」钩子链：服务端 / 代理端口已可连、机器人尚未启动。
     *
     * 与 [runBeforeHooks] 同样是「失败即抛出」（场景判失败），区别只在时序。
     */
    private fun runReadyHooks(ctx: TaskExecutionContext, hooks: List<ScenarioHook>, hookCtx: HookContext) {
        if (hooks.isEmpty()) return
        ctx.info("执行节点就绪后钩子（${hooks.size} 条）")
        hooks.forEach { it.run(hookCtx) }
    }

    /**
     * 构造场景钩子的执行上下文（保存可序列化快照：目录 + 场景名，不含 Gradle 对象）。
     *
     * 后端目录只放「本场景实际涉及的」后端，避免钩子误引用未起服的后端目录。
     */
    private fun hookContext(
        ctx: TaskExecutionContext,
        scenario: String,
        backendNames: List<String>,
        proxyName: String?,
    ): HookContext {
        val layout = ctx.layout
        val dirs = backendNames.associateWith { layout.clusterBackendRunDir(it) }
        val proxyDir = proxyName?.let { layout.proxyRunDir }
        return HookContext(
            scenarioName = scenario,
            resultsDir = layout.resultsDir,
            backendRunDirs = dirs,
            proxyRunDir = proxyDir,
            log = { ctx.info(it) },
        )
    }

    /**
     * 执行「场景前」钩子链。
     *
     * 某条钩子失败即抛出——这样场景判失败，且编排侧的 `finally` / `finalizedBy` 会照常触发
     * [runAfterHooks]，保证已起的外部进程仍被收尾（收尾不被失败路径跳过）。
     */
    private fun runBeforeHooks(ctx: TaskExecutionContext, hooks: List<ScenarioHook>, hookCtx: HookContext) {
        if (hooks.isEmpty()) return
        ctx.info("执行场景前钩子（${hooks.size} 条）")
        hooks.forEach { it.run(hookCtx) }
    }

    /**
     * 执行「场景后」钩子链（收尾语义）。
     *
     * **不在失败时短路**：某条收尾钩子抛异常只记 warn 并继续执行后续钩子——收尾阶段的异常不得
     * 阻断其余资源的清理，也不得掩盖场景本身的判定失败（与既有「按 pid 收尾静默跳过」一致）。
     */
    private fun runAfterHooks(ctx: TaskExecutionContext, hooks: List<ScenarioHook>, hookCtx: HookContext) {
        if (hooks.isEmpty()) return
        ctx.info("执行场景后钩子（${hooks.size} 条）")
        hooks.forEach { hook ->
            try {
                hook.run(hookCtx)
            } catch (ex: Exception) {
                ctx.warn("场景后钩子执行失败（不阻断其余收尾）：${ex.message}")
            }
        }
    }

    private fun awaitPortOpen(
        ctx: TaskExecutionContext,
        port: Int,
        label: String,
        timeoutSeconds: Long = PORT_READINESS_TIMEOUT_SECONDS,
    ) {
        val deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 2000)
                }
                ctx.info("$label 端口 $port 已就绪")
                return
            } catch (ex: Exception) {
                Thread.sleep(1000)
            }
        }
        throw GradleException(
            "$label 端口 $port 在 ${timeoutSeconds}s 内未就绪（进程启动失败 / 过慢）；将收尾全部进程。",
        )
    }

    /** 轮询等集群结果文件写出（任一桩写出即完成）；超时仍无即抛中文错误（收尾由 finally / finalizedBy 兜）。 */
    private fun awaitClusterResult(ctx: TaskExecutionContext, resultFile: File, scenario: String) {
        ctx.info("集群场景 $scenario 已全部起服，等待桩写出结果文件…")
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
     * 注册压测任务（压测编排，ADR-0008）：N 后端**全部后台** + 代理（N-listener 钉服）或直连 + 每服 M 个
     * bot 进程钉本服持续随机施压 → 等**全部 per-server 结果文件** → 聚合判定（任一缺失 / FAIL 即失败并
     * 报哪服）；正常 / 失败 / 中断三路径都 finalizedBy + try/finally 双保险收尾全部后端 + 代理 + bot，
     * 端口干净（高风险区）。业务不变量（不超卖等）由消费方桩查共享 DB 自行判，框架只收集 + 聚合。
     */
    private fun registerStressTask(
        project: Project,
        ctx: TaskExecutionContext,
        scenario: ScenarioSpec,
        stress: StressSpec,
        stressBackends: List<ResolvedBackend>,
        proxy: ResolvedProxy?,
        mavenSources: MavenCoordinateSources,
        hookTasks: ScenarioHookTasks,
    ) {
        val layout = ctx.layout
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
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val summary = stopTrackedProcesses(
                    layout.resultsDir,
                    stressBackends.map { layout.clusterBackendPidFile(it.name) } +
                        (proxy?.let { listOf(layout.proxyPidFile(it.name)) } ?: emptyList()) +
                        botKeys.map { botPidFile(layout.resultsDir, it) },
                    ctx::info,
                )
                val stressPorts = stressBackends.map { it.port } +
                    (proxy?.let { p -> stressBackends.indices.map { index -> p.port + index } } ?: emptyList())
                reportStopOutcome(
                    ctx,
                    summary,
                    stressPorts,
                    "上一轮残留进程仍占端口（多为前一次构建被强杀、pid 记录已随运行目录清理丢失）",
                )
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
            // 钩子接线：场景前须先于起服，场景后与压测收尾同层
            hookTasks.before?.let { task.dependsOn(it) }
            hookTasks.after?.let { task.finalizedBy(it) }
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val backendProcesses = LinkedHashMap<String, Process>()
                val botProcesses = mutableListOf<Process>()
                var proxyProcess: Process? = null
                try {
                    val runtime = preflightRuntimeForTask(
                        ctx,
                        stressBackends,
                        proxy?.let(::listOf) ?: emptyList(),
                        mavenSources,
                    )
                    layout.resultsDir.mkdirs()
                    // 清上轮 per-server 结果，避免误判
                    stressBackends.forEach { backend ->
                        stressResultFile(layout, scenario.name, backend.name).takeIf { it.exists() }?.delete()
                    }

                    // ⓪ 端口预检：后端端口 + 代理各 listener 端口（后者按基数 + 序号推导）被占即中文失败
                    requirePortsAvailable(
                        stressBackends.map { PortTarget(it.port, "压测后端 ${it.name}") } +
                            if (proxy != null) {
                                stressBackends.mapIndexed { index, backend ->
                                    PortTarget(proxy.port + index, "压测代理 listener->${backend.name}")
                                }
                            } else {
                                emptyList()
                            },
                        stopTaskName = stopName,
                    )

                    // ① 每后端独立运行目录 prepare（+ BungeeCord 模式 if via）+ 后台起（同 SCENARIO、各自 per-server RESULT_FILE）
                    stressBackends.forEach { backend ->
                        val runDir = layout.clusterBackendRunDir(backend.name)
                        prepareRunDirectory(ctx, backend, runDir, runtime.backends.getValue(backend.name))
                        if (proxy != null) BackendBungeeCordConfig.apply(runDir, backend.version)
                        backendProcesses[backend.name] =
                            startBackendBackground(
                                ctx,
                                backend,
                                runDir,
                                scenario.name,
                                stressResultFile(layout, scenario.name, backend.name),
                                mavenSources,
                            )
                    }

                    // ② 计算钉服绑定（listener 端口 = 代理端口基数 + 序号）；若经代理则后台起 N-listener 钉服代理
                    val bindings = stressBackends.mapIndexed { index, backend ->
                        StressProxyBinding(backend.name, "127.0.0.1:${backend.port}", (proxy?.port ?: 0) + index)
                    }
                    if (proxy != null) {
                        proxyProcess = startStressProxyBackground(
                            ctx,
                            proxy,
                            proxyDownloadVersion(proxy, stressBackends.first().version),
                            bindings,
                            runtime.proxies.getValue(proxy.name),
                            mavenSources,
                        )
                    }

                    // ②' 确定性就绪门：等后端（+ 代理各 listener）端口可连再起 bot（不靠盲重试赛慢启动，慢 CI 上稳）
                    stressBackends.forEach { awaitPortOpen(ctx, it.port, "压测后端 ${it.name}") }
                    if (proxy != null) {
                        bindings.forEach { awaitPortOpen(ctx, it.listenPort, "压测代理 listener->${it.backendName}") }
                    }

                    // ③ 每服起 M 个 bot 钉本服（via 用对应 listener 端口、直连用后端端口；协议版本经代理固定为后端版本）
                    scenario.botSpec?.let { bot ->
                        stressBackends.forEachIndexed { index, backend ->
                            val botPort = if (proxy != null) bindings[index].listenPort else backend.port
                            val protocolVersion = if (proxy != null) ProxyProtocolVersion.forBackend(backend.version) else null
                            botProcesses += launchStressBotsForServer(
                                ctx,
                                stress,
                                bot,
                                action,
                                index + 1,
                                botPort,
                                protocolVersion,
                                backend.version,
                            )
                        }
                    }

                    // ④ 等全部 per-server 结果文件写出（桩到 duration 末聚合写出）
                    awaitAllStressResults(ctx, layout, scenario.name, stressBackends, stress.durationSeconds)

                    // ⑤ 聚合判定：每服结果文件都须 PASS（只认结果文件，业务不变量由消费方桩在其中体现）
                    verifyStressResults(ctx, layout, scenario.name, stressBackends)
                } finally {
                    // 双保险收尾：全部 bot（自停兜底）+ 全部后端 + 代理
                    botProcesses.forEach { destroyProcessQuietly(ctx, it) }
                    backendProcesses.forEach { (name, proc) -> stopProcessQuietly(ctx, proc, layout.clusterBackendPidFile(name)) }
                    proxy?.let { p -> proxyProcess?.let { stopProcessQuietly(ctx, it, layout.proxyPidFile(p.name)) } }
                }
            }
        }
    }

    /** 后台起压测 N-listener 钉服代理（一端口对一后端，bot 连某端口钉死在对应后端）。 */
    private fun startStressProxyBackground(
        ctx: TaskExecutionContext,
        proxy: ResolvedProxy,
        proxyVersion: String,
        bindings: List<StressProxyBinding>,
        resources: ProxyRuntimeResources,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): Process {
        val layout = ctx.layout
        val proxyRunDir = layout.proxyRunDir
        stageProxyRuntime(
            proxyRunDir,
            resources,
            writeFrameworkConfiguration = {
                writeStressProxyConfiguration(proxyRunDir, proxy, bindings)
            },
            preparePlatformRuntime = {
                provisionWaterfallModulesIfNeeded(ctx, proxy.platform, proxyVersion, proxyRunDir)
            },
            logger = { ctx.info(it) },
        )
        val jar = resolveNodeJar(ctx, proxy.platform.name.lowercase(), proxyVersion, proxy.mavenServer, sources)
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = proxyRunDir,
            key = proxy.name,
            jvmArgs = proxyJvmArgs(ctx, proxy),
            environment = mergeNodeEnvironment(proxy.environment, emptyMap()),
            javaPath = resolveProxyJava(ctx, proxy),
            logger = { ctx.info(it) },
        )
        recordProcessedPid(ctx, layout.proxyPidFile(proxy.name), "$LEDGER_KEY_PROXY/${proxy.name}", process, proxy.port)
        ctx.info(
            "已启动压测代理 ${proxy.name} pid=${process.pid()} " +
                "listeners=${bindings.joinToString(",") { "${it.listenPort}->${it.backendName}" }}",
        )
        return process
    }

    /** 为某服后台起 M 个压测 bot 进程（各唯一名 / 唯一 log·pid key / BOT_INDEX / 共享 seed / duration）。 */
    private fun launchStressBotsForServer(
        ctx: TaskExecutionContext,
        stress: StressSpec,
        bot: BotSpec,
        action: String,
        serverIndex: Int,
        botPort: Int,
        protocolVersion: String?,
        backendVersion: String,
    ): List<Process> {
        if (!MinecraftVersionGroup.isBotSupported(backendVersion)) {
            ctx.warn("$backendVersion 不支持 bot E2E，仅验服务端拉起（跳过压测 bot 启动）")
            return emptyList()
        }
        val layout = ctx.layout
        val botDir = ctx.botDir
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
            val environment = connection.toEnvironment(extraEnv, ctx::readEnv)
            processes += BotLauncher.launch(
                context = BotProcessContext(botDir = botDir, botScript = botScript, resultsDir = layout.resultsDir),
                action = key,
                environment = environment,
                logger = { ctx.info(it) },
            )
        }
        ctx.info("已为服 s$serverIndex 启动 ${stress.botsPerServer} 个压测 bot（连端口=$botPort）")
        return processes
    }

    /** 轮询等全部 per-server 结果文件写出（桩到 duration 末聚合写出）；超时仍缺则抛中文错误并报哪服。 */
    private fun awaitAllStressResults(
        ctx: TaskExecutionContext,
        layout: RunLayout,
        scenario: String,
        backends: List<ResolvedBackend>,
        durationSeconds: Long,
    ) {
        ctx.info("压测场景 $scenario 已全部起服，持续 ${durationSeconds}s，等待各服桩写出结果文件…")
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
        ctx: TaskExecutionContext,
        layout: RunLayout,
        scenario: String,
        backends: List<ResolvedBackend>,
    ) {
        backends.forEach { backend ->
            val result = ResultReader.read(layout.resultsDir, "$scenario-${backend.name}")
            ctx.info("压测服 ${backend.name} 通过：${result.message}")
        }
        ctx.info("压测场景 $scenario 全部 ${backends.size} 服聚合判定通过。")
    }

    /** 压测某服某 bot 的 log/pid key：`<action>-s<serverIndex>-<botIndex>`。 */
    private fun stressBotKey(action: String, serverIndex: Int, botIndex: Int): String =
        "$action-s$serverIndex-$botIndex"

    /** 压测某服的 per-server 结果文件（`<scenario>-<backendName>.properties`）。 */
    private fun stressResultFile(layout: RunLayout, scenario: String, backendName: String): File =
        File(layout.resultsDir, McTestkitResultFile.fileName("$scenario-$backendName"))

    /** 温和销毁一个进程（压测 bot 收尾双保险；pid 文件由停任务按 pid 清理，这里只灭进程）。 */
    /**
     * 打印一次停任务收尾的结果，并在「声明过的端口仍被占用」时给出可操作提示。
     *
     * **为什么必须说话**：收尾此前一律静默成功，用户无法区分「本来就没起 / 已干净收尾」与
     * 「pid 记录丢失、进程逃逸到上一轮」——后者会让下一轮起服以 `bind(..) failed` 失败，
     * 而用户在上一步看不到任何线索（本函数正是为此存在）。收尾仍**不因此判失败**：
     * 端口可能是用户自己手工起的服务端，任务无权替用户下结论。
     *
     * @param ctx 任务执行上下文。
     * @param summary [stopTrackedProcesses] 的收尾摘要。
     * @param declaredPorts 本任务声明过的端口（收尾后复验是否真的释放）。
     * @param escapeHint 端口仍未释放时的补充说明（各任务按自身语义给出）。
     */
    private fun reportStopOutcome(
        ctx: TaskExecutionContext,
        summary: StopSummary,
        declaredPorts: List<Int>,
        escapeHint: String,
    ) {
        ctx.info("收尾完成：结束 ${summary.stopped} 个进程（其中台账兜底 ${summary.ledgerStopped} 个）")
        if (summary.ledgerStopped > 0) {
            ctx.info(
                "注意：本次收尾了 ${summary.ledgerStopped} 个**上一轮**残留的进程——" +
                    "前一次构建应是被强制中断、未走到收尾（否则不会留到本轮）。",
            )
        }
        if (summary.ledgerSkipped > 0) {
            ctx.warn(
                "跳过 ${summary.ledgerSkipped} 条台账记录：其 pid 已被其它进程占用（命令行与登记特征不符）。" +
                    "已清理这些陈旧记录以免误杀；若仍有残留，请按端口手工排查。",
            )
        }
        val remaining = portsStillOccupied(declaredPorts.distinct())
        if (remaining.isEmpty()) {
            return
        }
        ctx.warn(
            "收尾后以下端口仍被占用：${remaining.joinToString(", ")}。$escapeHint。" +
                "请按端口查明占用进程（Windows：netstat -ano | findstr :<端口>；" +
                "Linux：ss -ltnp | grep :<端口>）后手工结束。",
        )
        if (summary.pidFileMissing > 0) {
            ctx.warn(
                "本次有 ${summary.pidFileMissing} 个节点没有 pid 文件：可能是该节点没起来（正常），" +
                    "也可能是它的进程逃逸到上一轮且台账已丢失（那时只能按上面的端口命令手工清理）。",
            )
        }
    }

    private fun destroyProcessQuietly(ctx: TaskExecutionContext, process: Process) {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS) && process.isAlive) {
                    process.destroyForcibly()
                }
            }
        } catch (ex: Exception) {
            ctx.warn("收尾机器人进程时异常（已忽略）：${ex.message}")
        }
    }

    /**
     * 注册持久手测（serve）任务（持久手测 serve，ADR-0011）：`serve<Key>` 前台起后端（声明 via 则先后台起代理）、
     * 注入插件、下发哨兵场景使桩空闲，**挂住**到用户手动停（Ctrl+C → JVM shutdown hook 收尾；或单独跑
     * `stop<Key>Serve` 按 pid 兜底）。serve 不判 PASS/FAIL（不绕过结果文件自判，架构不变量 §3）。
     */
    private fun registerServeTasks(
        project: Project,
        ctx: TaskExecutionContext,
        topology: Topology,
        serve: ServeSpec,
        mavenSources: MavenCoordinateSources,
    ) {
        // 声明 backends(...) 即集群 serve；否则单后端 serve（持久手测 serve）
        if (serve.backendRefs.isNotEmpty()) {
            registerClusterServeTasks(project, ctx, topology, serve, mavenSources)
        } else {
            registerSingleServeTasks(project, ctx, topology, serve, mavenSources)
        }
    }

    /** 单后端 serve（持久手测 serve）：起单后端（+ 可选经代理）挂住，`stop<Key>Serve` 按 pid 收尾后端（+ 代理）。 */
    private fun registerSingleServeTasks(
        project: Project,
        ctx: TaskExecutionContext,
        topology: Topology,
        serve: ServeSpec,
        mavenSources: MavenCoordinateSources,
    ) {
        val backend = resolveServeBackend(topology, serve)
        val proxy = serve.via?.let { via -> topology.proxies.first { it.name == via } } // 已由 TopologyResolver 校验存在 + 路由
        val stopName = McTestkitTaskNames.stopServe(serve.name)
        // 可选 bot（serve 人机混场）的 pid key（停任务据此按 pid 收尾，防 straggler 残留）
        val botKeys = BotProcessPlanner.expand(serve.name, serve.botSpecs).map { it.key }

        // 停 serve 任务：按 pid 收尾后端（+ 代理 + 全部 bot）的兜底（另一终端停 / Ctrl+C 没清干净时用）
        registerTask(project, stopName) { task ->
            task.group = SERVE_TASK_GROUP
            task.description =
                "停止 serve「${serve.name}」的后端${proxy?.let { " 与代理 ${it.name}" } ?: ""}${if (botKeys.isNotEmpty()) " 与机器人" else ""}（按 pid 收尾）"
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val layout = ctx.layout
                val summary = stopTrackedProcesses(
                    layout.resultsDir,
                    listOf(provisionPidFile(layout.backendRunDir(backend.name), backend.name)) +
                        (proxy?.let { listOf(layout.proxyPidFile(it.name)) } ?: emptyList()) +
                        botKeys.map { botPidFile(layout.resultsDir, it) },
                    ctx::info,
                )
                reportStopOutcome(
                    ctx,
                    summary,
                    listOfNotNull(backend.port, proxy?.port),
                    "上一轮残留进程仍占端口。serve 挂住期间最易被强杀（关终端 / 杀 daemon），" +
                        "那时它的 pid 文件会随运行目录清理丢失，只能按端口手工清理",
                )
            }
        }

        val serveTask = registerTask(project, McTestkitTaskNames.serve(serve.name)) { task ->
            task.group = SERVE_TASK_GROUP
            task.description =
                "持久起 serve「${serve.name}」：后端 ${backend.name}${proxy?.let { " 经代理 ${it.name}" } ?: " 直连"}" +
                (if (serve.botSpecs.isNotEmpty()) " + ${botKeys.size} bot" else "") + "，挂住供真人客户端手测（Ctrl+C 停）"
            // 起 bot 需先装好 mineflayer 依赖（serve 人机混场）
            if (serve.botSpecs.isNotEmpty()) {
                task.dependsOn(McTestkitTaskNames.NPM_INSTALL_BOT)
            }
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                serveForeground(ctx, backend, proxy, serve.name, serve.botSpecs, mavenSources)
            }
        }

        // 自测模式：serve 自行预检并注入插件，同样须等本模块 jar 产出后再预检
        // （与 prepare / e2e 的自动接线同源，覆盖 MCE 式「serve 挂住手测」消费形态）
        if (ctx.dependencies.pluginUnderTestSelfJar) {
            serveTask.get().dependsOn(project.tasks.named("jar"))
        }
    }

    /** 解析 serve 起哪个后端：显式 `backend =` 引用 > 首个声明的后端（单后端默认）。 */
    private fun resolveServeBackend(topology: Topology, serve: ServeSpec): ResolvedBackend {
        val ref = serve.backend
        if (ref != null) {
            return topology.backends.firstOrNull { it.name == ref }
                ?: throw GradleException(
                    "mcTestkit serve「${serve.name}」引用的后端「$ref」不存在（应在 TopologyResolver 已拦截）。",
                )
        }
        return topology.backends.firstOrNull()
            ?: throw GradleException(
                "mcTestkit serve「${serve.name}」无可用后端：请用 backend(\"...\") 至少声明一个后端。",
            )
    }

    /**
     * serve 任务体（持久手测 serve）：prepare →（via 则起代理）→ 前台起后端（下发哨兵场景使桩空闲）→ 等就绪打印连接信息
     * → **阻塞挂住**到后端退出 / 手动停 → 双保险收尾。注册 JVM shutdown hook 应对 Ctrl+C / 中断时收尾子进程
     * （高风险区：进程全灭 / 端口不漏 / 跨平台 pid 收尾，配套 `stop<Key>Serve` 兜底）。
     */
    private fun serveForeground(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        proxy: ResolvedProxy?,
        serveName: String,
        botSpecs: List<BotSpec> = emptyList(),
        mavenSources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ) {
        val layout = ctx.layout
        val backendRunDir = layout.backendRunDir(backend.name)
        val runtime = preflightRuntimeForTask(ctx, listOf(backend), proxy?.let(::listOf) ?: emptyList(), mavenSources)
        // ⓪ 端口预检：serve 是「挂住等真人连」的长生命周期形态，端口被上一轮残留进程占住时最易误判
        //    （就绪门报「已就绪」→ 真人连上去连的其实是旧服务端），故起服前先拦住
        requirePortsAvailable(
            listOfNotNull(
                proxy?.let { PortTarget(it.port, "代理 ${it.name}") },
                PortTarget(backend.port, "后端 ${backend.name}"),
            ),
            stopTaskName = McTestkitTaskNames.stopServe(serveName),
        )
        // ① 准备运行目录（注入被测 + 依赖插件，含桩；桩由哨兵场景置空闲）
        prepareRunDirectory(ctx, backend, backendRunDir, runtime.backends.getValue(backend.name))

        var proxyProcess: Process? = null
        var backendProcess: Process? = null
        var logTail: Thread? = null
        var consolePump: Thread? = null
        val botProcesses = mutableListOf<Process>()
        // Ctrl+C / JVM 退出兜底：收尾 bot + 后端 + 代理（幂等、吞异常，与 finally 双保险）。先注册以覆盖整段生命周期。
        // 注：hook 在执行期创建、不进配置缓存序列化图；即便被序列化，ctx 亦可序列化（不含 Project）。
        val shutdownHook = Thread {
            botProcesses.forEach { destroyProcessQuietly(ctx, it) }
            backendProcess?.let { destroyProcessQuietly(ctx, it) }
            proxyProcess?.let { destroyProcessQuietly(ctx, it) }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            // ② 经代理：写后端代理模式配置 + 后台起代理 + 等代理端口可连
            if (proxy != null) {
                if (proxy.platform == ProxyPlatform.VELOCITY) {
                    BackendVelocityConfig.apply(backendRunDir, backend.version)
                } else {
                    BackendBungeeCordConfig.apply(backendRunDir, backend.version)
                }
                proxyProcess = startProxyBackground(ctx, proxy, backend, runtime.proxies.getValue(proxy.name), sources = mavenSources)
                awaitPortOpen(ctx, proxy.port, "代理 ${proxy.name}")
            }
            // ③ 前台起后端：下发哨兵场景 id 使桩空闲、不关服（ADR-0011），不下发 RESULT_FILE（serve 不判定）
            backendProcess = startServeBackend(ctx, backend, backendRunDir, mavenSources)
            // ④ 等后端端口就绪，打印连接信息
            awaitPortOpen(ctx, backend.port, "后端 ${backend.name}")
            val connectPort = proxy?.port ?: backend.port
            ctx.info(
                "✅ serve「$serveName」已就绪：请用 Minecraft ${backend.version} 客户端连接 127.0.0.1:$connectPort" +
                    (proxy?.let { "（经代理 ${it.name}）" } ?: "（直连后端 ${backend.name}）") +
                    "。停止：本终端 Ctrl+C，或另跑 ./gradlew ${McTestkitTaskNames.stopServe(serveName)}",
            )
            ctx.info("可直接在本终端输入服务端控制台命令（如 stop / say hello / op <玩家>），回车即发往后端 ${backend.name}。")
            // ⑤ 可选起 bot（serve 人机混场）：把环境驱到某状态（造数据 / 模拟其他玩家），但**不**据结果文件收尾——挂住人机混场。
            //    经代理则协议版本固定为后端版本（环境契约），连端口同真人（connectPort）。
            if (botSpecs.isNotEmpty()) {
                val protocolVersion = proxy?.let { ProxyProtocolVersion.forBackend(backend.version) }
                botProcesses += launchBots(
                    ctx,
                    serveName,
                    botSpecs,
                    backendVersion = backend.version,
                    backendPort = connectPort,
                    protocolVersion = protocolVersion,
                )
                ctx.info("serve「$serveName」已起 ${botProcesses.size} 个 bot（人机混场，不判定）")
            }
            // ⑥ 后端日志流到控制台（手测需可见启动 / 玩家活动）
            logTail = startServeLogTail(ctx, File(backendRunDir, "${backend.name}.log"))
            // ⑥' 终端输入 → 后端控制台 stdin（手测需能敲 stop / say 等命令；只 serve 接，E2E 自动化不接）
            consolePump = startConsoleCommandPump(
                source = System.`in`,
                sink = backendProcess.outputStream,
                logger = { ctx.warn(it) },
                targetAlive = { backendProcess.isAlive },
            )
            // ⑦ 阻塞挂住：等后端进程退出（用户在服务端控制台 stop / kill / Ctrl+C）
            backendProcess.waitFor()
            ctx.info("serve「$serveName」后端已退出，收尾。")
        } finally {
            // shutdown hook 收尾后移除（若 JVM 正在退出 removeShutdownHook 会抛，runCatching 吞掉）
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            logTail?.interrupt()
            consolePump?.interrupt()
            // 三重收尾兜底（即便 shutdown hook 未触发）：bot（自停兜底 + 按 pid）+ 后端 + 代理，删 pid
            botProcesses.forEach { destroyProcessQuietly(ctx, it) }
            if (botSpecs.isNotEmpty()) stopBots(ctx, serveName, botSpecs)
            backendProcess?.let { stopProcessQuietly(ctx, it, provisionPidFile(backendRunDir, backend.name)) }
            proxy?.let { p -> proxyProcess?.let { stopProcessQuietly(ctx, it, layout.proxyPidFile(p.name)) } }
        }
    }

    /** 前台起 serve 后端：下发哨兵场景 id 使桩空闲（不关服）+ BACKEND_NAME；不下发 RESULT_FILE（serve 不判定）。 */
    private fun startServeBackend(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        runDir: File,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): Process {
        val jar = resolveBackendJar(ctx, backend, sources)
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = runDir,
            key = backend.name,
            jvmArgs = backendJvmArgs(ctx, backend),
            serverArgs = backendServerArgs(backend.version),
            environment = mergeNodeEnvironment(
                backend.environment,
                mapOf(
                    // 哨兵场景：告诉桩进入空闲（不驱动 / 不关服，ADR-0011）；serve 不判定故不下发 RESULT_FILE
                    McTestkitEnv.SCENARIO to McTestkitContract.SERVE_SCENARIO_ID,
                    McTestkitEnv.BACKEND_NAME to backend.name,
                ),
            ),
            javaPath = resolveBackendJava(ctx, backend),
            logger = { ctx.info(it) },
        )
        // 单 serve 的 pid 文件由 ServerLauncher 写在运行目录内（`run-<后端>/<后端>.pid`），
        // 而运行目录每轮清理、pid 文件也会被下一轮起服先删——跨轮次逃逸的进程只能靠台账找回，
        // 故此处必须同时登记台账（集群 / 代理路径同理，见 recordProcessedPid）。
        recordProcessedPid(
            ctx,
            provisionPidFile(runDir, backend.name),
            "$LEDGER_KEY_BACKEND/${backend.name}",
            process,
            backend.port,
        )
        ctx.info("已起 serve 后端 ${backend.name} pid=${process.pid()} 端口=${backend.port}（桩空闲、不判定）")
        return process
    }

    /**
     * 起后台守护线程把 serve 后端日志文件 `tail` 到 Gradle 控制台（手测需可见服务端启动 / 玩家活动）。
     * daemon 线程（不阻塞 JVM 退出）、可被 interrupt 终止；日志文件迟迟不出现则放弃（不致命）。
     */
    private fun startServeLogTail(ctx: TaskExecutionContext, logFile: File): Thread {
        val thread = Thread {
            try {
                var waited = 0
                while (!logFile.exists() && waited < 50 && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(200)
                    waited++
                }
                if (!logFile.exists()) return@Thread
                // 后端日志按 UTF-8 读（Paper 写 UTF-8）；用平台默认字符集会把中文等非 ASCII 读乱码（实测 Windows GBK 控制台）
                logFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine()
                        if (line == null) {
                            Thread.sleep(300)
                        } else {
                            ctx.info("[后端] $line")
                        }
                    }
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (ex: Exception) {
                ctx.logger.debug("[mc-testkit] serve 日志 tail 结束：${ex.message}")
            }
        }
        thread.isDaemon = true
        thread.name = "mc-testkit-serve-log-tail"
        thread.start()
        return thread
    }

    /**
     * 集群 serve：N 后端 + 代理整套挂起，真人经代理 `/server` 切服手测。复用 集群编排
     * （`startClusterProxyBackground` / `awaitPortOpen`）+ 持久手测 serve serve 挂起 / 三重收尾；各后端桩经哨兵空闲。
     */
    private fun registerClusterServeTasks(
        project: Project,
        ctx: TaskExecutionContext,
        topology: Topology,
        serve: ServeSpec,
        mavenSources: MavenCoordinateSources,
    ) {
        val clusterBackends = serve.backendRefs.map { name -> topology.backends.first { it.name == name } } // 已校验存在
        val proxy = topology.proxies.first { it.name == serve.via } // 集群 serve 必有 via 且已校验路由覆盖
        val stopName = McTestkitTaskNames.stopServe(serve.name)
        // 可选 bot（serve 人机混场）的 pid key（停任务据此按 pid 收尾，防 straggler 残留）
        val botKeys = BotProcessPlanner.expand(serve.name, serve.botSpecs).map { it.key }

        registerTask(project, stopName) { task ->
            task.group = SERVE_TASK_GROUP
            task.description =
                "停止集群 serve「${serve.name}」的全部后端、代理 ${proxy.name}${if (botKeys.isNotEmpty()) " 与机器人" else ""}（按 pid 收尾）"
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                val layout = ctx.layout
                val summary = stopTrackedProcesses(
                    layout.resultsDir,
                    clusterBackends.map { layout.clusterBackendPidFile(it.name) } +
                        layout.proxyPidFile(proxy.name) +
                        botKeys.map { botPidFile(layout.resultsDir, it) },
                    ctx::info,
                )
                reportStopOutcome(
                    ctx,
                    summary,
                    clusterBackends.map { it.port } + proxy.port,
                    "上一轮残留进程仍占端口。serve 挂住期间最易被强杀（关终端 / 杀 daemon），" +
                        "那时它的 pid 文件会随运行目录清理丢失，只能按端口手工清理",
                )
            }
        }

        registerTask(project, McTestkitTaskNames.serve(serve.name)) { task ->
            task.group = SERVE_TASK_GROUP
            task.description =
                "持久起集群 serve「${serve.name}」：${clusterBackends.size} 后端 + 代理 ${proxy.name}" +
                (if (serve.botSpecs.isNotEmpty()) " + ${botKeys.size} bot" else "") + "，真人经代理 /server 切服手测（Ctrl+C 停）"
            if (serve.botSpecs.isNotEmpty()) {
                task.dependsOn(McTestkitTaskNames.NPM_INSTALL_BOT)
            }
            task.doLast {
                // 动作闭包只捕获可序列化上下文快照（不含 Project），兼容 Gradle 配置缓存
                serveClusterForeground(ctx, clusterBackends, proxy, serve.name, serve.botSpecs, mavenSources)
            }
        }
    }

    /**
     * 集群 serve 任务体（集群 serve）：每后端独立运行目录 prepare + 代理模式 + 后台起（哨兵桩空闲）→ 后台起集群代理
     * → 等全部端口就绪打印连接信息 → **阻塞挂住**（等代理进程，某后端宕仍挂便于看 fallback）到手动停 → 三重收尾。
     */
    private fun serveClusterForeground(
        ctx: TaskExecutionContext,
        clusterBackends: List<ResolvedBackend>,
        proxy: ResolvedProxy,
        serveName: String,
        botSpecs: List<BotSpec> = emptyList(),
        mavenSources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ) {
        val layout = ctx.layout
        val runtime = preflightRuntimeForTask(ctx, clusterBackends, listOf(proxy), mavenSources)
        val backendProcesses = LinkedHashMap<String, Process>()
        var proxyProcess: Process? = null
        var logTail: Thread? = null
        var consolePump: Thread? = null
        val botProcesses = mutableListOf<Process>()
        // Ctrl+C / JVM 退出兜底（执行期创建，不进配置缓存序列化图；ctx 可序列化）
        val shutdownHook = Thread {
            botProcesses.forEach { destroyProcessQuietly(ctx, it) }
            backendProcesses.values.forEach { destroyProcessQuietly(ctx, it) }
            proxyProcess?.let { destroyProcessQuietly(ctx, it) }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            // ⓪ 端口预检：同一轮内后端与代理端口撞车由拓扑解析期拦截，此处拦的是**跨轮次残留进程**占端口
            //    （持久 serve 挂住时间最长，最易留下未收尾进程；见 requirePortsAvailable）
            requirePortsAvailable(
                clusterBackends.map { PortTarget(it.port, "集群后端 ${it.name}") } +
                    PortTarget(proxy.port, "集群代理 ${proxy.name}"),
                stopTaskName = McTestkitTaskNames.stopServe(serveName),
            )
            // ① 每后端独立运行目录 prepare + 代理模式配置 + 后台起（哨兵场景使桩空闲）
            clusterBackends.forEach { backend ->
                val runDir = layout.clusterBackendRunDir(backend.name)
                prepareRunDirectory(ctx, backend, runDir, runtime.backends.getValue(backend.name))
                if (proxy.platform == ProxyPlatform.VELOCITY) {
                    BackendVelocityConfig.apply(runDir, backend.version)
                } else {
                    BackendBungeeCordConfig.apply(runDir, backend.version)
                }
                backendProcesses[backend.name] = startServeClusterBackend(ctx, backend, runDir, mavenSources)
            }
            // ② 后台起集群代理（单 listener + N 具名 server，供真人 /server 切换）
            proxyProcess = startClusterProxyBackground(
                ctx,
                proxy,
                clusterBackends,
                runtime.proxies.getValue(proxy.name),
                sources = mavenSources,
            )
            // ③ 就绪门：等全部后端 + 代理端口可连
            clusterBackends.forEach { awaitPortOpen(ctx, it.port, "集群后端 ${it.name}") }
            awaitPortOpen(ctx, proxy.port, "集群代理 ${proxy.name}")
            // ④ 打印连接信息（连代理端口、/server 切换目标）
            ctx.info(
                "✅ serve「$serveName」集群已就绪：请用 Minecraft ${clusterBackends.first().version} 客户端连接 127.0.0.1:${proxy.port}" +
                    "（经代理 ${proxy.name}），可 /server 切换：${clusterBackends.joinToString(", ") { it.name }}。" +
                    "。停止：本终端 Ctrl+C，或另跑 ./gradlew ${McTestkitTaskNames.stopServe(serveName)}",
            )
            ctx.info("可直接在本终端输入**代理**控制台命令（如 end / glist / send <玩家> <服>），回车即发往代理 ${proxy.name}；切服另用游戏内 /server。")
            // ⑤ 可选起 bot（serve 人机混场）：经代理端口、CLUSTER_BACKENDS 下发 /server 切换目标（每个 bot 都能切），
            //    协议版本固定为后端版本；把环境驱到某状态但**不**据结果文件收尾——挂住人机混场。
            if (botSpecs.isNotEmpty()) {
                botProcesses += launchBots(
                    ctx,
                    serveName,
                    botSpecs,
                    backendVersion = clusterBackends.first().version,
                    backendPort = proxy.port,
                    protocolVersion = ProxyProtocolVersion.forBackend(clusterBackends.first().version),
                    sharedExtraEnv = mapOf(
                        McTestkitEnv.CLUSTER_BACKENDS to clusterBackends.joinToString(",") { it.name },
                    ),
                )
                ctx.info("集群 serve「$serveName」已起 ${botProcesses.size} 个 bot（人机混场，不判定）")
            }
            // ⑥ 代理日志流到控制台（手测看切服 / 转发）
            logTail = startServeLogTail(ctx, File(layout.proxyRunDir, "${proxy.name}.log"))
            // ⑥' 终端输入 → 代理控制台 stdin（集群 serve 阻塞在代理上，真人的入口也是代理；只 serve 接，E2E 自动化不接）
            consolePump = startConsoleCommandPump(
                source = System.`in`,
                sink = proxyProcess.outputStream,
                logger = { ctx.warn(it) },
                targetAlive = { proxyProcess.isAlive },
            )
            // ⑦ 阻塞挂住：等代理进程退出（代理是真人入口；某后端宕仍挂着便于看崩溃接管 fallback）
            proxyProcess.waitFor()
            ctx.info("serve「$serveName」集群代理已退出，收尾。")
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            logTail?.interrupt()
            consolePump?.interrupt()
            // 三重收尾兜底：bot（自停兜底 + 按 pid）+ 全部后端 + 代理，删 pid
            botProcesses.forEach { destroyProcessQuietly(ctx, it) }
            if (botSpecs.isNotEmpty()) stopBots(ctx, serveName, botSpecs)
            backendProcesses.forEach { (name, proc) -> stopProcessQuietly(ctx, proc, layout.clusterBackendPidFile(name)) }
            proxyProcess?.let { stopProcessQuietly(ctx, it, layout.proxyPidFile(proxy.name)) }
        }
    }

    /** 后台起一个集群 serve 后端：下发哨兵场景使桩空闲 + BACKEND_NAME；pid 落结果目录供收尾。不下发 RESULT_FILE。 */
    private fun startServeClusterBackend(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        runDir: File,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): Process {
        val layout = ctx.layout
        val jar = resolveBackendJar(ctx, backend, sources)
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = runDir,
            key = backend.name,
            jvmArgs = backendJvmArgs(ctx, backend),
            serverArgs = backendServerArgs(backend.version),
            environment = mergeNodeEnvironment(
                backend.environment,
                mapOf(
                    McTestkitEnv.SCENARIO to McTestkitContract.SERVE_SCENARIO_ID,
                    McTestkitEnv.BACKEND_NAME to backend.name,
                ),
            ),
            javaPath = resolveBackendJava(ctx, backend),
            logger = { ctx.info(it) },
        )
        recordProcessedPid(
            ctx,
            layout.clusterBackendPidFile(backend.name),
            "$LEDGER_KEY_BACKEND/${backend.name}",
            process,
            backend.port,
        )
        ctx.info("已起集群 serve 后端 ${backend.name} pid=${process.pid()} 端口=${backend.port}（桩空闲、不判定）")
        return process
    }

    // ── 任务体的实际副作用实现（均在 doLast 内被调用，配置期不执行）──

    /** 使用已预检资源准备后端运行目录，避免目录清理后才发现其它节点资源缺失。 */
    private fun prepareRunDirectory(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        runDir: File,
        resources: BackendRuntimeResources,
    ) {
        ctx.layout.resultsDir.mkdirs()
        stageBackendRuntime(runDir, backend, resources) { ctx.info(it) }
    }

    /**
     * 前台起后端：provision 解析 jar → ServerLauncher 起子进程 → 等待自停（桩跑完关服）。
     *
     * 后端 BungeeCord 模式配置（经代理必需）由调用方在起后端前另行 [BackendBungeeCordConfig.apply]，
     * 本函数只负责"起后端 + 等自停"，不掺配置逻辑。
     *
     * 起服前做端口预检（见 [requirePortsAvailable]）：本函数是「前台起后端」的唯一入口，在此设闸可让
     * 所有调用方（直连 e2e / 经代理）自动获得保护，不必各自复制。
     *
     * @param stopTaskName 报错文案里建议执行的收尾任务名；直连 e2e 无配套停任务故为 null。
     */
    private fun runBackendForeground(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        scenario: String,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
        stopTaskName: String? = null,
    ) {
        val layout = ctx.layout
        val runDir = layout.backendRunDir(backend.name)
        requirePortsAvailable(listOf(PortTarget(backend.port, "后端 ${backend.name}")), stopTaskName = stopTaskName)
        val jar = resolveBackendJar(ctx, backend, sources)
        // 桩↔编排交接：下发场景与结果文件绝对路径（= verify 读取处），桩据此选场景并写到对齐位置
        val resultFilePath = File(layout.resultsDir, McTestkitResultFile.fileName(scenario)).absolutePath
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = runDir,
            key = backend.name,
            jvmArgs = backendJvmArgs(ctx, backend),
            serverArgs = backendServerArgs(backend.version),
            environment = mergeNodeEnvironment(
                backend.environment,
                mapOf(
                    McTestkitEnv.SCENARIO to scenario,
                    McTestkitEnv.RESULT_FILE to resultFilePath,
                    McTestkitEnv.BACKEND_NAME to backend.name,
                ),
            ),
            javaPath = resolveBackendJava(ctx, backend),
            logger = { ctx.info(it) },
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
                ctx.info(
                    "后端 ${backend.name} 结果已写出，但 JVM 未在 ${BACKEND_SELF_STOP_GRACE_SECONDS}s 内自停" +
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

    /** 后台起代理：统一 staging → provision 解析代理 jar → ServerLauncher 起子进程。 */
    private fun startProxyBackground(
        ctx: TaskExecutionContext,
        proxy: ResolvedProxy,
        backend: ResolvedBackend,
        resources: ProxyRuntimeResources,
        scenario: String? = null,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): Process {
        val layout = ctx.layout
        val proxyRunDir = layout.proxyRunDir
        val requestedVersion = proxyDownloadVersion(proxy, backend.version)
        stageProxyRuntime(
            proxyRunDir,
            resources,
            writeFrameworkConfiguration = {
                writeSingleProxyConfiguration(ctx, proxyRunDir, proxy, backend)
            },
            preparePlatformRuntime = {
                provisionWaterfallModulesIfNeeded(ctx, proxy.platform, requestedVersion, proxyRunDir)
            },
            logger = { ctx.info(it) },
        )
        val jar = resolveNodeJar(ctx, proxy.platform.name.lowercase(), requestedVersion, proxy.mavenServer, sources)
        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = proxyRunDir,
            key = proxy.name,
            jvmArgs = proxyJvmArgs(ctx, proxy),
            environment = mergeNodeEnvironment(
                proxy.environment,
                scenario?.let {
                    mapOf(
                        McTestkitEnv.SCENARIO to it,
                        McTestkitEnv.RESULT_FILE to File(layout.resultsDir, McTestkitResultFile.fileName(it)).absolutePath,
                    )
                } ?: emptyMap(),
            ),
            javaPath = resolveProxyJava(ctx, proxy),
            logger = { ctx.info(it) },
        )
        layout.proxyPidFile(proxy.name).apply { parentFile?.mkdirs() }.writeText(process.pid().toString())
        ctx.info("已启动代理 ${proxy.name} pid=${process.pid()} 监听端口=${proxy.port}（转发到后端 ${backend.name}:${backend.port}）")
        return process
    }

    /**
     * 起场景声明的全部 bot 进程（单场景多 bot）：按 [BotProcessPlanner.expand] 逐 plan 起进程。
     *
     * 进程数 >1 时**强制**下发唯一 `BOT_USERNAME`（末位合入，盖过消费方单值 override 以保证唯一）；
     * 同质复制（`count>1`）下发 `BOT_INDEX`（1..N）；并合入 [sharedExtraEnv]（如集群的 `CLUSTER_BACKENDS`，
     * 使每个 bot 都能经代理 `/server` 切换）。单 bot 时不强制 username（保留消费方 override，向后兼容）。
     *
     * **版本范围校验（多版本服务端拉起）**：[backendVersion] < 1.8（即 1.7.10）时跳过 bot 启动 + 日志告警，
     * 场景仍继续（不因无 bot 判 FAIL）。
     *
     * @return 全部已起进程（供调用方按需收尾；亦各自写了 `bot-<key>.pid` 供按 pid 收尾）。
     */
    private fun launchScenarioBots(
        ctx: TaskExecutionContext,
        scenario: ScenarioSpec,
        backendVersion: String,
        backendPort: Int,
        protocolVersion: String?,
        sharedExtraEnv: Map<String, String> = emptyMap(),
    ): List<Process> =
        launchBots(ctx, scenario.name, scenario.botSpecs, backendVersion, backendPort, protocolVersion, sharedExtraEnv)

    /**
     * 起一组 bot 进程（单场景多 bot 多 bot 展开 + 每进程 env 装配；**场景与 serve 共用**，serve 人机混场）。
     *
     * 按 [BotProcessPlanner.expand] 逐 plan 起进程；进程数 >1 强制唯一 `BOT_USERNAME`、同质复制下发 `BOT_INDEX`、
     * 合入 [sharedExtraEnv]（如集群 `CLUSTER_BACKENDS`，使每个 bot 都能经代理 `/server` 切换）。
     *
     * **版本范围校验（多版本服务端拉起）**：[backendVersion] < 1.8（即 1.7.10）时跳过 bot 启动 + 日志告警，
     * 场景仍继续（不因无 bot 判 FAIL）。
     */
    private fun launchBots(
        ctx: TaskExecutionContext,
        name: String,
        botSpecs: List<BotSpec>,
        backendVersion: String,
        backendPort: Int,
        protocolVersion: String?,
        sharedExtraEnv: Map<String, String> = emptyMap(),
    ): List<Process> {
        if (!MinecraftVersionGroup.isBotSupported(backendVersion)) {
            ctx.warn("$backendVersion 不支持 bot E2E，仅验服务端拉起（跳过 bot 启动）")
            return emptyList()
        }
        val plans = BotProcessPlanner.expand(name, botSpecs)
        // 每进程「追加 env」（唯一名 / 序号 / 共享 env）由纯函数装配，便于穷举单测
        val environments = BotProcessPlanner.extraEnvironments(plans, sharedExtraEnv)
        return plans.zip(environments).map { (plan, extraEnv) ->
            launchBotProcess(
                ctx,
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
    private fun stopScenarioBots(ctx: TaskExecutionContext, scenario: ScenarioSpec) =
        stopBots(ctx, scenario.name, scenario.botSpecs)

    /** 按 plan key 收尾一组 bot 的 pid 文件（**场景与 serve 共用**，serve 人机混场；单 bot / 已自停为安全 no-op）。 */
    private fun stopBots(ctx: TaskExecutionContext, name: String, botSpecs: List<BotSpec>) {
        val resultsDir = ctx.layout.resultsDir
        BotProcessPlanner.expand(name, botSpecs).forEach { plan ->
            stopProcessByPidFile(botPidFile(resultsDir, plan.key)) { ctx.info(it) }
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
        ctx: TaskExecutionContext,
        action: String,
        username: String,
        key: String,
        backendPort: Int,
        protocolVersion: String?,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process {
        val layout = ctx.layout
        val botDir = ctx.botDir
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
        val receiptFile = File(layout.resultsDir, "bot-$key.receipt.jsonl")
        val environment = connection.toEnvironment(
            extraEnvironment = extraEnv + mapOf(
                McTestkitEnv.BOT_RECEIPT_FILE to receiptFile.absolutePath,
            ),
            override = ctx::readEnv,
        )
        return BotLauncher.launch(
            context = BotProcessContext(botDir = botDir, botScript = botScript, resultsDir = layout.resultsDir),
            action = key,
            environment = environment,
            logger = { ctx.info(it) },
        )
    }

    /** 只认结果文件判定（[ResultReader]）：缺失或失败抛中文错误，通过则打印 message。 */
    private fun verifyScenarioResult(ctx: TaskExecutionContext, scenario: String) {
        val result = ResultReader.read(ctx.layout.resultsDir, scenario)
        ctx.info("E2E 场景 $scenario 通过：${result.message}")
    }

    // ── 任务注册原语（用显式 Java API + Action，避免 kotlin-dsl 扩展在插件源里的重载歧义）──

    /**
     * 注册一个 [DefaultTask] 并配置之，返回 [TaskProvider]（懒注册：不立即创建任务）。
     *
     * 用「先 `register(name, type)` 再 `provider.configure(Action)`」两步式——避开 kotlin-dsl 扩展与
     * Gradle 多个 `register` 重载在插件 `src/main/kotlin` 里的解析歧义，显式可控。
     *
     * 统一声明 [outputsUpToDateWhenNever]：本插件任务全为副作用生命周期任务（起服 / 写运行目录 /
     * 收尾 / 回写缓存），不得因构建缓存或输入未变而跳过。
     */
    private fun registerTask(
        project: Project,
        name: String,
        configure: (Task) -> Unit,
    ): TaskProvider<DefaultTask> {
        val provider = project.tasks.register(name, DefaultTask::class.java)
        provider.configure(object : Action<DefaultTask> {
            override fun execute(task: DefaultTask) {
                outputsUpToDateWhenNever(task)
                configure(task)
            }
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
            override fun execute(task: Exec) {
                outputsUpToDateWhenNever(task)
                configure(task)
            }
        })
        return provider
    }

    /**
     * 声明副作用任务永不因「输出未变」跳过（兼容消费方开启 `--build-cache`）。
     *
     * e2e / serve / stop / sync 等任务无稳定可缓存产物，类型亦非 `@CacheableTask`；
     * 本声明额外钉死「不得 UP-TO-DATE」。若未来误改任务类型，构建缓存兼容集成测试会拦。
     */
    private fun outputsUpToDateWhenNever(task: Task) {
        task.outputs.upToDateWhen { false }
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

    /**
     * 一次性预检任务涉及的全部节点模板、代理插件与后端 dependencies（执行期 env 经 [TaskExecutionContext.readEnv]）。
     *
     * Maven 坐标在**这里**（执行期）才落成 jar：逐个坐标解析失败即抛中文错误（[resolveMavenCoordinates]），
     * 解析成功的结果注入 [preflightNodeRuntime] 与路径 / 环境变量声明合并校验。坐标声明为空时零开销。
     */
    private fun preflightRuntimeForTask(
        ctx: TaskExecutionContext,
        backends: List<ResolvedBackend>,
        proxies: List<ResolvedProxy> = emptyList(),
        mavenSources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): NodeRuntimePreflight = preflightNodeRuntime(
        projectDirectory = ctx.projectDir,
        dependencies = ctx.dependencies,
        resolvedCoordinates = resolveMavenCoordinates(mavenSources.pluginCoordinates, mavenSources::pluginJarOf),
        backends = backends,
        proxies = proxies,
        readEnv = ctx::readEnv,
    )

    /**
     * 解析单个后端或代理平台 jar，统一复用现有 provision 模块。
     *
     * 传 [mavenCoordinate] 时由 provisioner 按「`*_JAR` 覆盖 > Maven 坐标（含镜像命中）> 内置下载」
     * 裁决来源；坐标来源经 [sources] 惰性取得（`*_JAR` 覆盖时不会被求值，故不会触碰依赖解析）。
     */
    private fun resolveNodeJar(
        ctx: TaskExecutionContext,
        platform: String,
        version: String,
        mavenCoordinate: String? = null,
        sources: MavenCoordinateSources = MavenCoordinateSources.EMPTY,
    ): File {
        val provisioner = ServerJarProvisioner.create(ctx.layout.jarCacheRoot, ctx::readEnv)
        val mavenServer = mavenCoordinate?.let(sources::serverJarOf)
        return provisioner.resolve(platform, version, mavenServer) { ctx.info(it) }
    }

    /** 解析后端服务端 jar（后端起服路径统一入口；`*_JAR` 覆盖 > Maven 坐标 > 内置下载）。 */
    private fun resolveBackendJar(
        ctx: TaskExecutionContext,
        backend: ResolvedBackend,
        sources: MavenCoordinateSources,
    ): File = resolveNodeJar(
        ctx = ctx,
        platform = backend.platform.name.lowercase(),
        version = backend.version,
        mavenCoordinate = backend.mavenServer,
        sources = sources,
    )

    /**
     * 解析后端应用的 `java` 可执行路径（多版本服务端拉起）。
     *
     * 显式声明了 [ResolvedBackend.javaVersion] 时走强制路径：必须由
     * `MC_TESTKIT_JAVA_HOME_<主版本>` 精确提供（不回退 `JAVA_HOME` / 当前 JVM），
     * 用于低版本服务端在插件运行于新 JVM 时锁定旧 JRE（如 1.16.5 的 patcher 拒绝 Java 17+）。
     * 未声明时维持既有解析链：`MC_TESTKIT_JAVA_HOME_<版本段>` > `JAVA_HOME` > 当前 JVM。
     */
    private fun resolveBackendJava(ctx: TaskExecutionContext, backend: ResolvedBackend): String =
        backend.javaVersion
            ?.let { javaVersion -> JavaRuntimeSelector.requiredExecutableForMajor(javaVersion, ctx::readEnv) }
            ?: JavaRuntimeSelector.executable(backend.version, ctx::readEnv)

    /** 显式代理 Java 主版本必须由专属环境变量提供，未声明时保留当前 JVM 行为。 */
    private fun resolveProxyJava(ctx: TaskExecutionContext, proxy: ResolvedProxy): String? =
        proxy.javaVersion?.let { javaVersion ->
            JavaRuntimeSelector.requiredExecutableForMajor(javaVersion, ctx::readEnv)
        }

    /** 所有后端启动入口共用的 JVM 参数拼装，避免各任务路径漂移。 */
    private fun backendJvmArgs(ctx: TaskExecutionContext, backend: ResolvedBackend): List<String> =
        composeNodeJvmArgs(
            FRAMEWORK_JVM_ARGS,
            backend.jvmArgs,
            resolveNodeJavaAgents(
                ctx.projectDir,
                "后端",
                backend.name,
                backend.javaAgents,
                ctx::readEnv,
            ),
        )

    /** 所有代理启动入口共用的 JVM 参数拼装，避免各任务路径漂移。 */
    private fun proxyJvmArgs(ctx: TaskExecutionContext, proxy: ResolvedProxy): List<String> =
        composeNodeJvmArgs(
            FRAMEWORK_JVM_ARGS,
            proxy.jvmArgs,
            resolveNodeJavaAgents(
                ctx.projectDir,
                "代理",
                proxy.name,
                proxy.javaAgents,
                ctx::readEnv,
            ),
        )

    /** 跨平台 npm 可执行名（Windows 为 `npm.cmd`）。 */
    private fun npmExecutable(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"

    /** 写单后端代理的框架权威配置。 */
    private fun writeSingleProxyConfiguration(
        ctx: TaskExecutionContext,
        runDirectory: File,
        proxy: ResolvedProxy,
        backend: ResolvedBackend,
    ) {
        when (proxy.platform) {
            ProxyPlatform.WATERFALL, ProxyPlatform.BUNGEECORD -> File(runDirectory, "config.yml").writeText(
                bungeeProxyConfigYml(proxy.port, "127.0.0.1:${backend.port}"),
            )
            ProxyPlatform.VELOCITY -> writeVelocityProxyFiles(
                ctx,
                runDirectory,
                proxy.port,
                listOf(backend.name to "127.0.0.1:${backend.port}"),
                backend.version,
            )
        }
    }

    /** 写集群代理的框架权威配置。 */
    private fun writeClusterProxyConfiguration(
        ctx: TaskExecutionContext,
        runDirectory: File,
        proxy: ResolvedProxy,
        backends: List<ResolvedBackend>,
    ) {
        val servers = backends.map { it.name to "127.0.0.1:${it.port}" }
        when (proxy.platform) {
            ProxyPlatform.WATERFALL, ProxyPlatform.BUNGEECORD -> File(runDirectory, "config.yml").writeText(
                bungeeClusterProxyConfigYml(proxy.port, servers),
            )
            ProxyPlatform.VELOCITY -> writeVelocityProxyFiles(
                ctx,
                runDirectory,
                proxy.port,
                servers,
                backends.first().version,
            )
        }
    }

    /** 写压测代理的框架权威配置；Velocity 已在配置期拦截。 */
    private fun writeStressProxyConfiguration(
        runDirectory: File,
        proxy: ResolvedProxy,
        bindings: List<StressProxyBinding>,
    ) {
        if (proxy.platform == ProxyPlatform.VELOCITY) {
            error("压测不支持经 Velocity 代理（单端口无法钉服）：应在配置期已拦截，不应到此。")
        }
        File(runDirectory, "config.yml").writeText(bungeeStressProxyConfigYml(bindings))
    }

    /**
     * 代理下载版本：Velocity 用自有版本号（[McTestkitDefaults.VELOCITY_VERSION]，非 MC 版本），
     * 其余（Waterfall/BungeeCord）取后端 MC 版本（Waterfall 由 provision 层再归一为 major.minor）。
     * env `*_VERSION` 覆盖在 provision 层仍优先（见 ServerJarProvisioner.resolveVersion）。
     */
    private fun proxyDownloadVersion(proxy: ResolvedProxy, backendVersion: String): String =
        proxy.version ?: if (proxy.platform == ProxyPlatform.VELOCITY) McTestkitDefaults.VELOCITY_VERSION else backendVersion

    /** Waterfall 旧模块自下载仍打已 sunset 的 v2 API，启动前用 Fill v3 预置模块。 */
    private fun provisionWaterfallModulesIfNeeded(
        ctx: TaskExecutionContext,
        platform: ProxyPlatform,
        requestedVersion: String,
        proxyRunDir: File,
    ) {
        if (platform != ProxyPlatform.WATERFALL) return
        val waterfall = ProvisionPlatform.WATERFALL
        if (ctx.readEnv(waterfall.jarEnv).isNullOrBlank().not()) {
            ctx.info("使用 ${waterfall.jarEnv} 覆盖 Waterfall jar，跳过模块预下载")
            return
        }
        val version = ctx.readEnv(requireNotNull(waterfall.versionEnv))?.takeIf { it.isNotBlank() }
            ?: requestedVersion
        WaterfallModuleProvisioner().provision(waterfall.downloadVersion(version), proxyRunDir) {
            ctx.info(it)
        }
    }

    /**
     * 写 Velocity 代理运行目录的两个文件：`velocity.toml`（按后端版本选择 forwarding + N server + try）+
     * `forwarding.secret`（modern 模式与后端共享；legacy 保留该文件但不使用）。
     *
     * @param servers 有序 (server 名, 地址) 列表，首个为默认落地服、全部入 try 作 fallback（崩溃接管）。
     */
    private fun writeVelocityProxyFiles(
        ctx: TaskExecutionContext,
        proxyRunDir: File,
        listenPort: Int,
        servers: List<Pair<String, String>>,
        backendVersion: String,
    ) {
        File(proxyRunDir, "velocity.toml").writeText(
            velocityProxyConfigToml(listenPort, servers, velocityForwardingModeForBackend(backendVersion)),
        )
        File(proxyRunDir, VELOCITY_FORWARDING_SECRET_FILE).writeText(McTestkitDefaults.VELOCITY_FORWARDING_SECRET)
        ctx.info(
            "已写 Velocity 代理配置：velocity.toml + $VELOCITY_FORWARDING_SECRET_FILE" +
                "（servers: ${servers.joinToString(",") { it.first }}）",
        )
    }

    /** 温和停一个进程并删除其 pid 文件（try/finally 双保险用，吞掉收尾异常不影响主流程结论）。 */
    private fun stopProcessQuietly(ctx: TaskExecutionContext, process: Process, pidFile: File) {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(15, TimeUnit.SECONDS) && process.isAlive) {
                    process.destroyForcibly()
                }
            }
        } catch (ex: Exception) {
            ctx.warn("收尾进程时异常（已忽略）：${ex.message}")
        } finally {
            if (pidFile.exists()) pidFile.delete()
        }
    }
}

/** 每次单后端场景启动前清理旧结果，结果文件只能代表当前进程。 */
internal fun clearPreviousScenarioResult(resultsDir: File, scenario: String) {
    val resultFile = File(resultsDir, McTestkitResultFile.fileName(scenario))
    if (resultFile.exists() && !resultFile.delete()) {
        throw GradleException("无法删除上轮场景结果文件：${resultFile.absolutePath}")
    }
}

/** 同一 Minecraft 版本的后端首次启动会准备共享运行库，后续节点须等前一节点就绪。 */
internal fun sameVersionStartupPredecessors(nodes: List<Pair<String, String>>): Map<String, String> {
    val latestByVersion = LinkedHashMap<String, String>()
    val predecessors = LinkedHashMap<String, String>()
    nodes.forEach { (name, version) ->
        latestByVersion[version]?.let { predecessor -> predecessors[name] = predecessor }
        latestByVersion[version] = name
    }
    return predecessors
}

/** 将已就绪后端的运行库复制给同版本后继节点，避免 Paper 重复下载同一官方基础 jar。 */
internal fun copyRuntimeCaches(sourceRunDirectory: File, targetRunDirectory: File) {
    RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES.forEach { name ->
        val source = File(sourceRunDirectory, name)
        if (source.exists()) source.copyRecursively(File(targetRunDirectory, name), overwrite = true)
    }
}
