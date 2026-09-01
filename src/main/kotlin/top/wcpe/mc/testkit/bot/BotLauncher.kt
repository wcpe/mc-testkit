package top.wcpe.mc.testkit.bot

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

/** 某机器人 key 的 pid 文件：`bot-<key>.pid`（供 [stopProcessByPidFile] 收尾）。 */
fun botPidFile(resultsDir: File, key: String): File = File(resultsDir, "bot-$key.pid")

/**
 * 机器人子进程启动器（机器人驱动与结果判定，编排模型见 ADR-0004）。
 *
 * 把 mineflayer 机器人作为**后台进程**拉起：`node <脚本>` 在机器人工作目录运行、合并 stderr、
 * 日志 append 到 `bot-<key>.log`、pid 写 `bot-<key>.pid` 供收尾按 pid 杀（保证不残留占端口）。
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
     * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
     * @return 已启动的 [Process]。
     */
    fun launch(
        context: BotProcessContext,
        action: String,
        environment: Map<String, String>,
        logger: (String) -> Unit = {},
    ): Process {
        val logFile = botLogFile(context.resultsDir, action)
        val pidFile = botPidFile(context.resultsDir, action)
        logFile.parentFile?.mkdirs()
        // 清理上轮残留日志 / pid，避免读到旧场景结果
        if (logFile.exists()) logFile.delete()
        if (pidFile.exists()) pidFile.delete()

        val processBuilder = ProcessBuilder(nodeCommand(), context.botScript.absolutePath)
        processBuilder.directory(context.botDir)
        processBuilder.redirectErrorStream(true)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        processBuilder.environment().putAll(environment)

        val process = processBuilder.start()
        pidFile.writeText(process.pid().toString())
        logger(
            "已启动 mineflayer 机器人：action=$action pid=${process.pid()} " +
                "log=${logFile.absolutePath}",
        )
        return process
    }
}
