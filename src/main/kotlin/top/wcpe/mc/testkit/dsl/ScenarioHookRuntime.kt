package top.wcpe.mc.testkit.dsl

import top.wcpe.mc.testkit.bot.BoundedProcessOutputPump
import top.wcpe.mc.testkit.bot.LEDGER_KEY_HOOK
import top.wcpe.mc.testkit.bot.OutputBudget
import top.wcpe.mc.testkit.bot.ProcessLedger
import top.wcpe.mc.testkit.bot.stopProcessTree
import top.wcpe.mc.testkit.contract.McTestkitPidFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * 场景钩子的运行时支撑：起后台进程、等端口 / 日志就绪、发 HTTP、按 pid 收尾。
 *
 * 这些是钩子的**副作用实现**，与 DSL 声明分离（[ScenarioHooks] 只承载声明值）。
 * 独立成对象便于单测直接驱动，不必构造 Gradle 环境。
 */
internal object ScenarioHookRuntime {

    /** 判定是否 Windows（命令包装与收尾方式不同）。 */
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * 以后台方式启动进程，stdout/stderr 并入 [logFile]。
     *
     * 命令经 shell 包装：Windows 上直接 `ProcessBuilder` 起 `.bat` / `.exe` 会失败（需 `cmd /c`），
     * 与既有 [top.wcpe.mc.testkit.provision.ServerLauncher] 处理服务端启动的方式保持一致。
     *
     * [lifetimeMs] 是交给 run 进程监管的寿命上限（场景钩子取 [HookContext.processLifetimeMs]）；null 表示不设，
     * 由收尾钩子或构建结束收尾。[cleanup] 为 true 表示收尾进程（场景后钩子），本次构建已判失败时照样启动。
     */
    fun startBackground(
        command: List<String>,
        env: Map<String, String>,
        workDir: File,
        logFile: File,
        budget: OutputBudget? = null,
        lifetimeMs: Long? = null,
        cleanup: Boolean = false,
    ): Process {
        // 没有预算就拒绝启动；null 绝不表示不限输出。
        val owner = requireNotNull(budget) { "钩子进程必须交给本次构建的输出预算启动" }
        if (!cleanup) owner.checkHealthy()
        logFile.parentFile?.mkdirs()
        workDir.mkdirs()
        val pb = ProcessBuilder(shellWrap(command)).directory(workDir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val process = pb.start()
        try {
            val pump = BoundedProcessOutputPump("hook:${logFile.name.take(100)}", process.inputStream, logFile, owner, processAlive = { process.isAlive })
            val handle = top.wcpe.mc.testkit.bot.BotProcessHandle(process, pump, logFile)
            val thread = Thread({ pump.run() }, "mc-testkit-hook-pump").apply { isDaemon = true }
            handle.pumpThread = thread
            owner.supervisor.register(handle, lifetimeMs, cleanup)
            thread.start()
            return process
        } catch (ex: Exception) {
            owner.fail("RUN_FAIL hook-start ${ex.javaClass.simpleName}")
            process.destroyForcibly()
            throw ex
        }
    }

    /** 按当前平台包装命令：Windows 走 `cmd /c`，其余直接执行。 */
    fun shellWrap(command: List<String>): List<String> =
        if (isWindows) listOf("cmd", "/c") + command else command

    /**
     * 等某 TCP 端口可连（服务端 / 控制面就绪的最常用判据）。
     *
     * 用轮询而非 `ServerSocket` 绑定探测：只判断「能否连上」，不影响目标进程。
     */
    fun awaitPort(context: HookContext, port: Int, timeoutMs: Long, label: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) {
                context.info("$label 就绪（端口 $port 可连）")
                return
            }
            Thread.sleep(500)
        }
        throw IllegalStateException("$label 在 ${timeoutMs}ms 内未就绪（端口 $port 不可连）")
    }

    /** 等日志文件出现指定子串（应用级就绪判据，比端口更贴近「真的起来了」）。 */
    fun awaitLog(context: HookContext, logFile: File, pattern: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (logFile.isFile && logFile.readText().contains(pattern)) {
                context.info("日志已命中就绪标记「$pattern」")
                return
            }
            Thread.sleep(500)
        }
        throw IllegalStateException(
            "日志 ${logFile.absolutePath} 在 ${timeoutMs}ms 内未命中「$pattern」" +
                "（文件${if (logFile.isFile) "存在" else "不存在"}）",
        )
    }

    /** 探测端口是否可连（短超时，避免拖慢轮询）。 */
    fun portOpen(port: Int): Boolean =
        try {
            Socket("127.0.0.1", port).use { true }
        } catch (_: IOException) {
            false
        }

    /** 钩子进程在进程台账里的 key：`hook/<pid 文件相对结果目录的路径>`。 */
    fun ledgerKey(resultsDir: File, pidFile: File): String =
        "$LEDGER_KEY_HOOK/" + (pidFile.relativeToOrNull(resultsDir)?.invariantSeparatorsPath ?: pidFile.absolutePath)

    /**
     * 按 pid 文件收尾进程：进程已退出则静默跳过。
     *
     * 与既有按 pid 收尾语义一致——清理阶段不因「本来就没起来」而报错盖住真正的失败原因。
     * 本次构建起的交给进程监管停（连同观察到的子孙进程）；之前的构建留下的按进程台账核对身份后连同子孙进程一起停；
     * 对不上的（pid 已被别的进程复用，或台账里没有）不碰，只记一行日志。
     */
    fun stopByPidFile(context: HookContext, pidFile: File) {
        if (!pidFile.isFile) {
            context.info("pid 文件不存在（${pidFile.name}），跳过收尾")
            return
        }
        // pid 文件格式见 contract/McTestkitPidFile（首行 pid、次行启动时刻）。按行取首行，
        // 不按固定字节数读取：位数变化时读死 N 字节会拿到半截数字并静默跳过收尾。
        val pid = McTestkitPidFile.readPid(pidFile) ?: return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (context.outputBudget().supervisor.stopPidFile(pidFile)) return
        val key = ledgerKey(context.resultsDir, pidFile)
        val record = ProcessLedger.records(context.resultsDir).firstOrNull { it.key == key && it.pid == pid }
        if (record == null || !record.matchesLiveProcess()) {
            context.info("pid 文件 ${pidFile.name} 里的进程（pid=$pid）对不上本工具登记的钩子进程（pid 可能已被复用），不按 pid 收尾")
            return
        }
        stopProcessTree(pid, handle, context::info)
        ProcessLedger.unrecord(context.resultsDir, key)
        context.info("已按进程台账收掉之前的构建留下的钩子进程（${pidFile.name}，pid=$pid）")
    }

    /**
     * 发一次 HTTP 请求，返回 (状态码, 响应正文)。
     *
     * 用 `HttpURLConnection`（Java 8 可用）：本插件的最低运行 JDK 不因钩子而抬高。
     */
    fun httpCall(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            if (!headers.containsKey("Content-Type")) conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (!body.isNullOrEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to text
        } finally {
            conn.disconnect()
        }
    }
}
