package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import top.wcpe.mc.testkit.bot.LEDGER_KEY_BOT
import top.wcpe.mc.testkit.bot.LEDGER_KEY_HOOK
import top.wcpe.mc.testkit.bot.OutputBudget
import top.wcpe.mc.testkit.bot.ProcessIdentity
import top.wcpe.mc.testkit.bot.ProcessLedger
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.driver.LEDGER_KEY_DRIVER
import java.io.File
import java.util.*

/** 每个顶层 Gradle 构建一份（含配置缓存复用时的任务副本）；结果目录的接手规则见 [RunOutputState]。 */
/** 生成一个新的 console 命令通道令牌（SecureRandom 24 字节 hex）；每次构建由 [RunOutputService] 持有一份。 */
internal fun generateCommandChannelToken(): String {
    val bytes = ByteArray(24)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

abstract class RunOutputService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val budget: OutputBudget by lazy { OutputBudget.fromConfig() }
    private val runId = UUID.randomUUID().toString()

    /**
     * 本次构建的 console 命令通道令牌（SecureRandom 24 字节 hex），惰性生成一次。
     *
     * 放在构建服务而非任务上下文：配置缓存复用时每个任务拿到的是**各自反序列化的**上下文副本，
     * 字段级令牌会让「起后端的任务」与「起 bot 的任务」各自摇出不同令牌、同一通道全部被拒；
     * 构建服务实例在同一次顶层构建内对全部任务副本唯一，令牌因此天然对齐。
     * 令牌不进日志、不落盘（只经 env 下发给桩与 bot 进程）。
     */
    @Volatile
    private var commandChannelTokenValue: String? = null

    /** 返回本次构建的通道令牌（同一次构建内恒定）。 */
    @Synchronized
    fun commandChannelToken(): String =
        commandChannelTokenValue ?: generateCommandChannelToken().also { commandChannelTokenValue = it }
    private val states = linkedSetOf<File>()

    /** 本次接手的状态文件。拒绝接手的保持原样，拒绝就会持续到隐患消除，而不是只挡住一次构建。 */
    private val owned = linkedSetOf<File>()

    @Synchronized
    fun attach(resultsDir: File): OutputBudget {
        require(budget.runLimit >= 32768) { "构建级输出预算至少要留 32 KiB 给运行元数据" }
        val state = File(resultsDir, RunOutputState.FILE_NAME)
        if (states.add(state)) {
            resultsDir.mkdirs()
            val writers by lazy { liveWriters(resultsDir) }
            when (val prior = RunOutputState.read(state) { writers }) {
                is RunOutputState.Prior.Refused -> {
                    logger.error(
                        "[mc-testkit] 结果目录 ${resultsDir.absolutePath} 的上一次构建可能仍有进程在写输出（${prior.detail}），本次不接手。" +
                            remedyHint(prior.remedy, state) { writers },
                    )
                    budget.fail("RUN_FAIL prior-output-owner-not-successful ${prior.detail}")
                }

                is RunOutputState.Prior.ContainedFailure -> {
                    logger.warn(
                        "[mc-testkit] 结果目录 ${resultsDir.absolutePath} 的上一次构建（run=${prior.runId}）以 ${prior.reason} 失败，" +
                            "它起的受监管进程都已确认结束，本次照常接手；场景结果文件开跑前会清掉，不会沿用上一次的结果。",
                    )
                    own(state)
                }

                is RunOutputState.Prior.Abandoned -> {
                    logger.warn(
                        "[mc-testkit] 结果目录 ${resultsDir.absolutePath} 的上一次构建（run=${prior.runId}）没收尾就结束了（构建进程被强杀、断电，或收尾前出错），" +
                            "进程台账里也没有它留下的仍在写日志的 bot 或钩子进程，本次照常接手；场景结果文件开跑前会清掉，不会沿用上一次的结果。",
                    )
                    own(state)
                }

                RunOutputState.Prior.None, RunOutputState.Prior.ClosedOk -> own(state)
            }
        }
        return budget
    }

    private fun own(state: File) {
        state.writeText(RunOutputState.running(runId, ProcessIdentity.current()))
        owned += state
    }

    /** 进程台账里身份核对得上、仍存活、又不归本次构建监管的 bot 与钩子进程：往结果目录写日志的只有它们。 */
    private fun liveWriters(resultsDir: File): List<String> = ProcessLedger.records(resultsDir)
        .filter {
            it.key.startsWith("$LEDGER_KEY_BOT/") ||
                it.key.startsWith("$LEDGER_KEY_HOOK/") ||
                it.key.startsWith("$LEDGER_KEY_DRIVER/")
        }
        .filter { !budget.supervisor.owns(it.pid) && it.matchesLiveProcess() }
        .map { it.key }

    private fun remedyHint(remedy: RunOutputState.Remedy, state: File, writers: () -> List<String>): String {
        val stop = "./gradlew ${McTestkitTaskNames.STOP_PROCESSES}"
        return when (remedy) {
            RunOutputState.Remedy.STOP_PROCESSES -> {
                val listed = writers().takeIf { it.isNotEmpty() }?.let { "台账里仍存活的进程：${it.joinToString("、")}。" }.orEmpty()
                "先跑 $stop（或对应的 stop<场景>Cluster、stop<场景>Stress、stop<名字>Serve 等，它们都不受这道检查拦截）：" +
                    "它们按进程台账收掉之前的构建留下的进程，也收掉这份记录里收尾时没停下的进程；这些进程都结束后，下一次构建自己接手。" +
                    listed
            }

            RunOutputState.Remedy.WAIT_OWNER ->
                "持有这份记录的构建 JVM 还在运行（多为同一工程的另一个构建）：等它结束后重跑。确认没有构建在跑、只是 Gradle 守护进程卡住时，" +
                    "先 ./gradlew --stop 停掉守护进程，之后的构建会按进程台账判断能否接手。"

            RunOutputState.Remedy.DELETE_STATE ->
                "这份记录无法确认上一次构建的进程是否都已结束（旧版本写下的 RUNNING、live=unknown 或读不懂）：" +
                    "先跑 $stop 收掉台账里能找到的进程，确认没有进程还在写这个目录后，删掉 ${state.absolutePath} 再重跑。"
        }
    }

    fun checkHealthy() {
        try {
            budget.checkHealthy()
        } catch (ex: IllegalStateException) {
            throw GradleException(ex.message ?: "RUN_FAIL", ex)
        }
    }

    override fun close() {
        val supervisor = budget.supervisor
        supervisor.close()
        val truncated = budget.truncatedSinks()
        if (truncated > 0) {
            logger.warn(
                "[mc-testkit] 本次构建有 $truncated 个 bot / 钩子日志超过单个日志上限 ${budget.perSinkLimit} 字节被截断（日志末尾有 [OUTPUT_LIMIT] 行），" +
                    "判定不受影响；要看完整日志，调大 mc.testkit.bot.log.limit.bytes 或 MC_TESTKIT_E2E_BOT_LOG_LIMIT_BYTES。",
            )
        }
        val record = RunOutputState.closed(runId, budget.usedBytes(), budget.failureReason(), supervisor.survivors())
        owned.forEach { it.writeText(record) }
        checkHealthy()
    }

    private companion object {
        val logger = Logging.getLogger(RunOutputService::class.java)
    }
}
