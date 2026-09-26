package top.wcpe.mc.testkit.dsl

import java.io.File

/**
 * 场景钩子的执行上下文（传给 [ScenarioSpec.beforeScenario] / [ScenarioSpec.afterScenario]）。
 *
 * 只暴露**可序列化**的数据快照（文件路径、场景名、日志回调），不含 `Project` / `Task` 等 Gradle 对象——
 * 钩子会被任务动作闭包捕获，而配置缓存要求整个捕获图可序列化。
 *
 * @property scenarioName 当前场景名（与 DSL 声明、桩的 ScenarioName、bot action 三处一致）。
 * @property resultsDir 结果目录（桩写 `<场景名>.properties` 的落点）。
 * @property backendRunDirs 后端名 → 运行目录（钩子可向其中写配置、读日志）。
 * @property proxyRunDir 代理运行目录；场景未经代理时为 null。
 * @property log 中文日志回调（统一 `[mc-testkit]` 前缀）。
 */
class HookContext(
    val scenarioName: String,
    val resultsDir: File,
    val backendRunDirs: Map<String, File>,
    val proxyRunDir: File?,
    private val log: (String) -> Unit,
) : java.io.Serializable {

    /** 记一条 lifecycle 日志。 */
    fun info(message: String) = log(message)

    /** 取某后端的运行目录；未声明的后端名会抛出带说明的异常。 */
    fun backendRunDir(name: String): File =
        backendRunDirs[name] ?: throw IllegalArgumentException(
            "场景「$scenarioName」的钩子引用了未声明的后端「$name」；已声明：" +
                (backendRunDirs.keys.sorted().joinToString(", ").ifEmpty { "(无)" }),
        )
}

/**
 * 场景生命周期钩子动作。
 *
 * 实现 [java.io.Serializable] 的原因与 [BotSpec] 一致：注册期声明定形后被任务动作闭包捕获，
 * 配置缓存要求捕获图可序列化（不得含 `Project`，故用具名类型而非 lambda——Kotlin 的 SAM
 * 转换产物不是 `Serializable`，见 ADR-0017 同款约束）。
 *
 * 用法：
 * ```kotlin
 * scenario("full") {
 *     beforeScenario(ExecHook(listOf("/path/to/control-plane"), env = mapOf("DB" to "sqlite")))
 * }
 * ```
 */
fun interface ScenarioHook : java.io.Serializable {
    /** 执行钩子；抛异常即视为该钩子失败（`afterScenario` 仍会被执行）。 */
    fun run(context: HookContext)
}

/**
 * 执行一条外部命令的钩子（起控制面、清理等）。
 *
 * 进程以**后台**方式启动并立即返回（不阻塞场景），pid 落盘以便 `afterScenario` 经 [StopPidHook] 收尾。
 *
 * @property command 命令行（首元素为可执行文件；经 shell 包装以兼容 Windows 的 `.bat` / `.exe`）。
 * @property env 追加给子进程的环境变量（覆盖同名宿主变量）。
 * @property workingDirectory 工作目录；null 表示运行目录下的 `hooks/<场景名>` 子目录。
 * @property logFileName 日志文件名（落在 [HookContext.resultsDir] 下）；null 表示 `hook-<场景名>.log`。
 * @property pidFileName pid 文件名（落在 [HookContext.resultsDir] 下）；null 表示 `hook-<场景名>.pid`。
 * @property readyPort 就绪门：等该 TCP 端口可连（0 表示不等）。
 * @property readyLogPattern 就绪门：等日志出现该子串（空表示不等）。
 * @property readyTimeoutMs 就绪门超时毫秒数。
 */
class ExecHook(
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDirectory: String? = null,
    private val logFileName: String? = null,
    private val pidFileName: String? = null,
    private val readyPort: Int = 0,
    private val readyLogPattern: String = "",
    private val readyTimeoutMs: Long = 60_000,
) : ScenarioHook, java.io.Serializable {

    override fun run(context: HookContext) {
        require(command.isNotEmpty()) { "ExecHook 的 command 不得为空" }
        val workDir = workingDirectory?.let { File(it) }
            ?: File(context.resultsDir, "hooks/${context.scenarioName}").apply { mkdirs() }
        workDir.mkdirs()
        val logFile = File(context.resultsDir, logFileName ?: "hook-${context.scenarioName}.log")
        val pidFile = File(context.resultsDir, pidFileName ?: "hook-${context.scenarioName}.pid")

        context.info("执行钩子命令：${command.first()}（日志 ${logFile.name}）")
        val process = ScenarioHookRuntime.startBackground(command, env, workDir, logFile)
        pidFile.writeText(process.pid().toString())

        if (readyPort > 0) {
            ScenarioHookRuntime.awaitPort(context, readyPort, readyTimeoutMs, "钩子端口 $readyPort")
        }
        if (readyLogPattern.isNotEmpty()) {
            ScenarioHookRuntime.awaitLog(context, logFile, readyLogPattern, readyTimeoutMs)
        }
    }
}

/**
 * 按 pid 文件收尾进程的钩子（`afterScenario` 清理控制面等）。
 *
 * 复用编排既有的按 pid 收尾语义：进程已退出则静默跳过，避免「清理时报错盖住真正失败」。
 *
 * @property pidFileName pid 文件名（落在 [HookContext.resultsDir] 下）。
 */
class StopPidHook(private val pidFileName: String) : ScenarioHook, java.io.Serializable {
    override fun run(context: HookContext) {
        val pidFile = File(context.resultsDir, pidFileName)
        ScenarioHookRuntime.stopByPidFile(context, pidFile)
    }
}

/**
 * 发一次 HTTP 请求的钩子（调控制面 admin API：登录、审批、下发配置等）。
 *
 * 用 JDK 自带的 `HttpURLConnection`（非 `java.net.http`）：前者在 Java 8 即可用，不抬高本插件的
 * 最低运行 JDK；后者需 Java 11+。
 *
 * @property method HTTP 方法（GET / POST / PUT / DELETE 等）。
 * @property url 请求地址。
 * @property headers 请求头（`Content-Type` 缺省为 `application/json`）。
 * @property body 请求体；null 表示无体。
 * @property expectStatus 期望状态码（0 表示不校验）。
 * @property timeoutMs 连接与读超时毫秒数。
 */
class HttpHook(
    private val method: String,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val body: String? = null,
    private val expectStatus: Int = 0,
    private val timeoutMs: Long = 10_000,
) : ScenarioHook, java.io.Serializable {

    override fun run(context: HookContext) {
        val (status, text) = ScenarioHookRuntime.httpCall(method, url, headers, body, timeoutMs)
        if (expectStatus in 1..999 && status != expectStatus) {
            throw IllegalStateException(
                "钩子 HTTP 请求失败：$method $url 期望状态 $expectStatus，实际 $status；响应前 500 字：${text.take(500)}",
            )
        }
        context.info("钩子 HTTP $method $url → $status")
    }
}

/**
 * 等待若干毫秒的钩子（沉降等待：等异步模块就绪、等名册聚合等）。
 *
 * @property millis 等待毫秒数。
 * @property reason 等待原因（写入日志，便于排障）。
 */
class SleepHook(private val millis: Long, private val reason: String = "") : ScenarioHook, java.io.Serializable {
    override fun run(context: HookContext) {
        context.info("钩子等待 ${millis}ms${if (reason.isEmpty()) "" else "（$reason）"}")
        Thread.sleep(millis)
    }
}

/**
 * 按顺序执行多个钩子；任一失败即抛出（`afterScenario` 链由编排侧保证仍执行）。
 *
 * 用途：`beforeScenario` 常需「起进程 → 等就绪 → 登录 → 审批」多步串联。
 */
class HookChain(private val hooks: List<ScenarioHook>) : ScenarioHook, java.io.Serializable {
    override fun run(context: HookContext) {
        hooks.forEach { it.run(context) }
    }
}
