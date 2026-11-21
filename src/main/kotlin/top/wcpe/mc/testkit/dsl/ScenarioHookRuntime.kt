package top.wcpe.mc.testkit.dsl

import top.wcpe.mc.testkit.bot.BoundedProcessOutputPump
import top.wcpe.mc.testkit.bot.OutputBudget
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
            val pump = BoundedProcessOutputPump("hook:${logFile.name.take(100)}", process.inputStream, logFile, owner)
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

    /**
     * 按 pid 文件收尾进程：进程已退出则静默跳过。
     *
     * 与既有按 pid 收尾语义一致——清理阶段不因「本来就没起来」而报错盖住真正的失败原因。
     * 子进程树用 [ProcessHandle.descendants] 一并回收（控制面可能再拉起子进程）。
     */
    fun stopByPidFile(context: HookContext, pidFile: File) {
        if (!pidFile.isFile) {
            context.info("pid 文件不存在（${pidFile.name}），跳过收尾")
            return
        }
        val pid =
            pidFile.inputStream().use { String(it.readNBytes(32), Charsets.UTF_8) }.trim().toLongOrNull() ?: return
        if (ProcessHandle.of(pid).isEmpty) return
        check(context.outputBudget().supervisor.stopPidFile(pidFile)) {
            "pid 文件 ${pidFile.name} 对应的进程不是本次构建启动的，拒绝按 pid 收尾"
        }
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
