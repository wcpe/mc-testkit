package top.wcpe.mc.testkit.bot

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

/** 某机器人 key 的 pid 文件：`bot-<key>.pid`（供 [stopProcessByPidFile] 收尾）。 */
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
    internal val overrunFlag = AtomicBoolean(false)

    /** 本 sink 是否已因超限 / 输出 IO 问题停止写正文。 */
    fun isOverrun(): Boolean = overrunFlag.get() || pump.isOverrun()

    /** 本 sink 已写出的正文字节数。 */
    fun writtenBytes(): Long = pump.writtenBytes()

    /**
     * 收尾本进程：先温和，超时强杀；**只收尾本句柄自己的进程**，不按可复用 pid 误杀。
     */
    fun stop(logger: (String) -> Unit = {}) {
        val pid = process.pid()
        process.destroy()
        runCatching { process.waitFor(STOP_GRACEFUL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(STOP_GRACEFUL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
        }
        logger("已结束机器人进程 pid=$pid log=${logFile.name}")
    }

    private companion object {
        const val STOP_GRACEFUL_TIMEOUT_SECONDS = 15L
    }
}

/**
 * 机器人子进程启动器（机器人驱动与结果判定，编排模型见 ADR-0004）。
 *
 * 把 mineflayer 机器人作为**后台进程**拉起：`node <脚本>` 在机器人工作目录运行、合并 stderr、
 * 日志写 `bot-<key>.log`、pid 写 `bot-<key>.pid` 供收尾按 pid 杀（保证不残留占端口）。
 *
 * ## 为什么不再用 `Redirect.appendTo(File)`
 *
 * 那个重定向由**内核直接写文件**，JVM 侧完全不经过数据流——**没有任何位置可以计数、
 * 限流或中断**。一次 E2E 事故里 9 个 bot 日志合计 130 GB（单文件 14.7 GB），而这条路径
 * 从结构上就无法阻止。
 *
 * 现在改为 `Redirect.PIPE` + [BoundedProcessOutputPump]：固定 buffer 增量读、
 * 按 [OutputBudget] 限额落盘，超限写一行有界 `[OUTPUT_LIMIT]` 标记并使任务失败。
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
     * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
     * @return 已启动的句柄（含进程与泵，供收尾方等待泵结束）。
     */
    fun launch(
        context: BotProcessContext,
        action: String,
        environment: Map<String, String>,
        budget: OutputBudget,
        logger: (String) -> Unit = {},
    ): BotProcessHandle {
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
        pidFile.writeText(process.pid().toString())

        val pump = BoundedProcessOutputPump(
            label = action,
            input = process.inputStream,
            logFile = logFile,
            budget = budget,
            onOverrun = { reason -> logger("[OUTPUT_LIMIT] action=$action $reason") },
        )
        val handle = BotProcessHandle(process = process, pump = pump, logFile = logFile)

        // 泵跑在守护线程：任务 / daemon 退出时不会阻止 JVM 关机，但收尾方应主动等它结束。
        val pumpThread = Thread({ handle.pump.run() }, "mc-testkit-bot-pump-$action")
        pumpThread.isDaemon = true
        pumpThread.start()

        logger(
            "已启动 mineflayer 机器人：action=$action pid=${process.pid()} " +
                "log=${logFile.absolutePath} 单输出上限=${budget.perSinkLimit} " +
                "run 已用=${budget.usedBytes()}/${budget.runLimit}",
        )
        return handle
    }
}
