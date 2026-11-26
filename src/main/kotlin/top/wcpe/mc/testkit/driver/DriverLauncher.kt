package top.wcpe.mc.testkit.driver

import top.wcpe.mc.testkit.bot.BotProcessHandle
import top.wcpe.mc.testkit.bot.BoundedProcessOutputPump
import top.wcpe.mc.testkit.bot.OutputBudget
import top.wcpe.mc.testkit.bot.ProcessLedger
import top.wcpe.mc.testkit.contract.McTestkitPidFile
import java.io.File

/** 某 driver key 的日志文件：`driver-<key>.log`。 */
fun driverLogFile(resultsDir: File, key: String): File = File(resultsDir, "driver-$key.log")

/** 某 driver key 的 pid 文件：`driver-<key>.pid`（同 bot：只供排查，收尾走进程监管 / 台账）。 */
fun driverPidFile(resultsDir: File, key: String): File = File(resultsDir, "driver-$key.pid")

/** 进程台账里 driver 的 key 前缀。 */
const val LEDGER_KEY_DRIVER = "driver"

/**
 * 通用进程驱动启动器（driver 车道：编排像管 bot 一样管真客户端 / 外部驱动进程，编排模型见 ADR-0004）。
 *
 * 把消费方自带的任意命令作为**后台进程**拉起：`<command> <args...>` 在声明的工作目录运行、合并
 * stderr、日志写 `driver-<key>.log`、pid 写 `driver-<key>.pid`，并登记进程台账 `driver/<key>`——
 * 构建被强杀时，之后的 stop 任务据此核对身份后收尾。输出经 [BoundedProcessOutputPump] 计限额，
 * 与 bot 同预算同口径（理由见 [top.wcpe.mc.testkit.bot.BotLauncher] 类注释，不再用内核重定向）。
 *
 * 生命周期语义（A 方案实证口径）：场景结束必收尾——监管 [top.wcpe.mc.testkit.bot.RunOutputSupervisor]
 * 先温和 destroy、限时未退升级强杀；driver 提前退出（「完成即退出」由 driver 自己兑现，如真客户端
 * 观测端口由开转关后退 0）只记日志，判定权仍在结果文件。
 */
object DriverLauncher {

    /**
     * 启动一个 driver 进程。
     *
     * @param key 日志 / pid / 台账的 key（须为文件名安全字符）。
     * @param command 可执行命令（如 `node` / `java` / 绝对路径）。
     * @param args 命令参数（已按声明顺序展开）。
     * @param workDir 进程工作目录。
     * @param environment 传给进程的环境变量（契约项 + 消费方业务项，调用方已装配）。
     * @param resultsDir 结果目录（写 `driver-<key>.log` / `driver-<key>.pid`）。
     * @param budget 本 run 的输出字节预算（**必填**，同 [top.wcpe.mc.testkit.bot.BotLauncher.launch]）。
     * @param lifetimeMs 进程寿命上限（毫秒），交给进程监管兜底；null 表示不设（serve 随会话收尾）。
     * @param logger 中文分级日志输出（默认 no-op）。
     * @return 已启动的句柄（复用 [BotProcessHandle]：进程 + 泵 + 日志，供收尾方等待泵结束）。
     */
    fun launch(
        key: String,
        command: String,
        args: List<String>,
        workDir: File,
        environment: Map<String, String>,
        resultsDir: File,
        budget: OutputBudget,
        lifetimeMs: Long?,
        logger: (String) -> Unit = {},
    ): BotProcessHandle {
        budget.checkHealthy()
        require(key.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "driver 日志 key 只能由字母、数字、下划线、点和连字符组成（1 到 100 个字符），实际为「$key」"
        }
        require(workDir.isDirectory) {
            "driver「$key」的工作目录不存在或不是目录：${workDir.absolutePath}"
        }
        val logFile = driverLogFile(resultsDir, key)
        val pidFile = driverPidFile(resultsDir, key)
        logFile.parentFile?.mkdirs()
        // 清理上轮残留日志 / pid，避免读到旧场景结果
        if (logFile.exists()) logFile.delete()
        if (pidFile.exists()) pidFile.delete()

        val processBuilder = ProcessBuilder(listOf(command) + args)
        processBuilder.directory(workDir)
        // 合并 stderr 到 stdout：崩溃栈不丢。**不重定向到文件**——改由泵读管道并计限额。
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().putAll(environment)

        val process = try {
            processBuilder.start()
        } catch (ex: Exception) {
            // 命令不存在 / 不可执行 / 权限不足：报出命令本身让用户知道是哪条起不来
            throw java.io.IOException("driver「$key」启动失败：$command ${ex.message}", ex)
        }
        try {
            // 带身份写入 pid（pid + 启动时刻），供排查与任何按 pid 的收尾路径核对
            McTestkitPidFile.write(pidFile, process.pid())
            ProcessLedger.record(resultsDir, "$LEDGER_KEY_DRIVER/$key", process.pid(), 0)

            val pump = BoundedProcessOutputPump(
                label = "driver-$key",
                input = process.inputStream,
                logFile = logFile,
                budget = budget,
                onTruncated = { logger("[OUTPUT_LIMIT] driver=$key $it") },
                processAlive = { process.isAlive },
            )
            val handle = BotProcessHandle(process = process, pump = pump, logFile = logFile)

            // 泵跑在守护线程：任务 / daemon 退出时不会阻止 JVM 关机，但收尾方应主动等它结束。
            val pumpThread = Thread({ handle.pump.run() }, "mc-testkit-driver-pump-$key")
            pumpThread.isDaemon = true
            handle.pumpThread = pumpThread
            try {
                budget.supervisor.register(handle, lifetimeMs)
                budget.supervisor.bindPidFile(process, pidFile)
                pumpThread.start()
            } catch (ex: Exception) {
                budget.fail("RUN_FAIL driver-start ${ex.javaClass.simpleName}")
                process.destroyForcibly()
                throw ex
            }

            logger(
                "已启动进程驱动：key=$key pid=${process.pid()} command=$command ${args.joinToString(" ")} " +
                    "log=${logFile.absolutePath} 单输出上限=${budget.perSinkLimit} " +
                    "run 已用=${budget.usedBytes()}/${budget.runLimit}",
            )
            return handle
        } catch (ex: Exception) {
            budget.fail("IOFAIL driver-launch ${ex.javaClass.simpleName}")
            process.destroyForcibly()
            throw ex
        }
    }
}
