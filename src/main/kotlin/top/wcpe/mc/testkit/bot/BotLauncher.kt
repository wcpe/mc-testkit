package top.wcpe.mc.testkit.bot

import top.wcpe.mc.testkit.contract.McTestkitPidFile
import java.io.File

/** 机器人进程启动所需的固定路径上下文（哪儿启、启哪个脚本、日志 / pid 落哪儿）。 */
data class BotProcessContext(
    /** 机器人工作目录（npm / node 的 cwd）。 */
    val botDir: File,
    /** 机器人入口脚本（如 mineflayer 的 `connectAndWait.js`）。 */
    val botScript: File,
    /** 结果目录（写机器人日志 `bot-<key>.log` 与 pid `bot-<key>.pid`）。 */
    val resultsDir: File,
)

/** 某机器人 key 的日志文件：`bot-<key>.log`。 */
fun botLogFile(resultsDir: File, key: String): File = File(resultsDir, "bot-$key.log")

/**
 * 某机器人 key 的 pid 文件：`bot-<key>.pid`，只供排查。收尾不认它：本次构建的 bot 由进程监管停，
 * 之前的构建留下的按进程台账（[ProcessLedger]）核对身份后停，只认 pid 会在 pid 复用时误杀。
 */
fun botPidFile(resultsDir: File, key: String): File = File(resultsDir, "bot-$key.pid")

/**
 * 一次启动后的可观句柄：进程本体 + 输出泵 + 本 sink 的日志文件。
 *
 * 为什么要连泵一起返回：**泵必须活得和进程一样久**。如果启动方把泵丢进线程就不管了，
 * 任务结束（或 Gradle daemon 退出）时读端消失，子进程会写满管道缓冲区后**永久阻塞**。
 * 因此收尾方必须能拿到泵并等它结束。
 *
 * @property process 已启动的子进程。
 * @property pump 输出泵（已在本对象创建时启在独立线程上）。
 * @property logFile 本 sink 的日志文件。
 */
class BotProcessHandle(
    val process: Process,
    val pump: BoundedProcessOutputPump,
    val logFile: File,
) {
    /**
     * 泵线程。由 [BotLauncher.launch] 在启动后填入；收尾方用它 [awaitPump]。
     *
     * **为什么必须能拿到并等它**：泵是子进程 stdout 的唯一读者。任务结束或
     * Gradle daemon 退出时若读端先消失，而子进程还活着，子进程写满管道缓冲区后
     * 会**永久阻塞**——既不退也不报错，只能靠下一轮台账清理。
     */
    @Volatile
    internal var pumpThread: Thread? = null

    /**
     * 有界等待泵线程结束。
     *
     * 泵是 daemon 线程，正常情况下随进程退出很快结束；但若子进程有大量残留输出，
     * 泵还要 drain。这里只等有界时间，**不因它卡住整个收尾**。
     *
     * @return true 表示已确认泵已结束；false 表示等待超时（泵可能仍在 drain）
     */
    fun awaitPump(timeoutSeconds: Long): Boolean {
        require(timeoutSeconds in 1..5) { "等待泵线程的期限须在 1 到 5 秒之间，实际为 $timeoutSeconds" }
        val thread = pumpThread ?: return true
        return try {
            thread.join(timeoutSeconds * 1000)
            !thread.isAlive
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 本日志是否已因超过单个日志上限被截断。 */
    fun isTruncated(): Boolean = pump.isTruncated()

    /** 本 sink 已写出的正文字节数。 */
    fun writtenBytes(): Long = pump.writtenBytes()

    /**
     * 收尾本进程：先温和，超时强杀；**只收尾本句柄自己的进程**，不按可复用 pid 误杀。
     */
    fun stop(logger: (String) -> Unit = {}) {
        val supervisor = pump.budget.supervisor
        supervisor.stop(process)
        if (!supervisor.awaitStopped(process, 3000)) pump.budget.fail("RUN_FAIL stop-deadline")
        if (!awaitPump(3)) pump.budget.fail("RUN_FAIL pump-stop-deadline")
        logger("已结束机器人进程 pid=${process.pid()} log=${logFile.name}")
    }
}

/**
 * 机器人子进程启动器（机器人驱动与结果判定，编排模型见 ADR-0004）。
 *
 * 把 mineflayer 机器人作为**后台进程**拉起：`node <脚本>` 在机器人工作目录运行、合并 stderr、
 * 日志写 `bot-<key>.log`、pid 写 `bot-<key>.pid`，并登记进程台账 `bot/<key>`：构建被强杀时，
 * 之后的 stop 任务据此核对身份后收尾。
 *
 * ## 为什么不再用 `Redirect.appendTo(File)`
 *
 * 那个重定向由**内核直接写文件**，JVM 侧完全不经过数据流——**没有任何位置可以计数、
 * 限流或中断**。一次 E2E 事故里 9 个 bot 日志合计 130 GB（单文件 14.7 GB），而这条路径
 * 从结构上就无法阻止。
 *
 * 现在改为 `Redirect.PIPE` + [BoundedProcessOutputPump]：固定 buffer 增量读、
 * 按 [OutputBudget] 限额落盘：单个日志超限只截断（写一行 `[OUTPUT_LIMIT]` 标记并告警），
 * 整次构建超限才判失败并停掉全部受监管进程。
 *
 * ## 预算必须显式传入
 *
 * [launch] 的 `budget` 参数**没有默认值**：这迫使每个调用点表态。若给默认值（或允许 null
 * 表示不限），既有的调用方会在无意中保留无限旧路——事故就是这么发生的。
 *
 * 同一 run 内多次 [launch] 应传**同一个** [OutputBudget] 实例：预算按 run 累计，
 * 不因再次 launch 而重置。
 *
 * 不在此连真服 / 判定——那是机器人 Node 内核（模板脚手架 template/）与桩写出的结果文件（[top.wcpe.mc.testkit.verify.ResultReader]）的事。
 */
object BotLauncher {

    /**
     * 启动一个机器人进程。
     *
     * 场景 action 经环境变量 `MC_TESTKIT_E2E_BOT_ACTION` 传给机器人（由 [BotConnection.toEnvironment] 写入），
     * 机器人内核据此分发场景驱动；action 同时作日志 / pid 的 key。
     *
     * @param context 路径上下文。
     * @param action 控制动作 / 场景 id：作日志 / pid 的 key（场景传递走 env，见上）。
     * @param environment 传给进程的环境变量（由 [BotConnection.toEnvironment] 构建）。
     * @param budget 本 run 的输出字节预算（**必填**，见类注释）。
     * @param lifetimeMs 进程寿命上限（毫秒），交给本 run 的进程监管兜底。场景调用方须给不短于编排自身结果等待的值，
     *   否则监管会抢在编排之前把仍在正常运行的 bot 判 TIMEOUT；null 表示不设（serve 人机混场随会话收尾）。
     * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
     * @return 已启动的句柄（含进程与泵，供收尾方等待泵结束）。
     */
    fun launch(
        context: BotProcessContext,
        action: String,
        environment: Map<String, String>,
        budget: OutputBudget,
        lifetimeMs: Long?,
        logger: (String) -> Unit = {},
    ): BotProcessHandle {
        budget.checkHealthy()
        require(action.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "机器人日志 key 只能由字母、数字、下划线、点和连字符组成（1 到 100 个字符），实际为「$action」"
        }
        val logFile = botLogFile(context.resultsDir, action)
        val pidFile = botPidFile(context.resultsDir, action)
        logFile.parentFile?.mkdirs()
        // 清理上轮残留日志 / pid，避免读到旧场景结果
        if (logFile.exists()) logFile.delete()
        if (pidFile.exists()) pidFile.delete()

        val processBuilder = ProcessBuilder(nodeCommand(), context.botScript.absolutePath)
        processBuilder.directory(context.botDir)
        // 合并 stderr 到 stdout：崩溃栈不丢。**不重定向到文件**——改由泵读管道并计限额。
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().putAll(environment)

        val process = processBuilder.start()
        try {
            // 带身份写入 pid（pid + 启动时刻），供排查与任何按 pid 的收尾路径核对
            McTestkitPidFile.write(pidFile, process.pid())
            ProcessLedger.record(context.resultsDir, "$LEDGER_KEY_BOT/$action", process.pid(), 0)

            val pump = BoundedProcessOutputPump(
                label = action,
                input = process.inputStream,
                logFile = logFile,
                budget = budget,
                onTruncated = { logger("[OUTPUT_LIMIT] action=$action $it") },
                processAlive = { process.isAlive },
            )
            val handle = BotProcessHandle(process = process, pump = pump, logFile = logFile)

            // 泵跑在守护线程：任务 / daemon 退出时不会阻止 JVM 关机，但收尾方应主动等它结束。
            val pumpThread = Thread({ handle.pump.run() }, "mc-testkit-bot-pump-$action")
            pumpThread.isDaemon = true
            handle.pumpThread = pumpThread
            try {
                budget.supervisor.register(handle, lifetimeMs)
                budget.supervisor.bindPidFile(process, pidFile)
                pumpThread.start()
            } catch (ex: Exception) {
                budget.fail("RUN_FAIL bot-start ${ex.javaClass.simpleName}")
                process.destroyForcibly()
                throw ex
            }

            logger(
                "已启动 mineflayer 机器人：action=$action pid=${process.pid()} " +
                    "log=${logFile.absolutePath} 单输出上限=${budget.perSinkLimit} " +
                    "run 已用=${budget.usedBytes()}/${budget.runLimit}",
            )
            return handle
        } catch (ex: Exception) {
            budget.fail("IOFAIL bot-launch ${ex.javaClass.simpleName}")
            process.destroyForcibly()
            throw ex
        }
    }
}
