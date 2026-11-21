package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import top.wcpe.mc.testkit.bot.OutputBudget
import java.io.File
import java.util.*

/** 每个顶层 Gradle 构建一份（含配置缓存复用时的任务副本）；结果目录的接手规则见 [RunOutputState]。 */
abstract class RunOutputService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val budget: OutputBudget by lazy { OutputBudget.fromConfig() }
    private val runId = UUID.randomUUID().toString()
    private val states = linkedSetOf<File>()

    /** 本次接手的状态文件。拒绝接手的保持原样，拒绝就会持续到隐患消除，而不是只挡住一次构建。 */
    private val owned = linkedSetOf<File>()

    @Synchronized
    fun attach(resultsDir: File): OutputBudget {
        require(budget.runLimit >= 32768) { "构建级输出预算至少要留 32 KiB 给运行元数据" }
        val state = File(resultsDir, RunOutputState.FILE_NAME)
        if (states.add(state)) {
            resultsDir.mkdirs()
            when (val prior = RunOutputState.read(state)) {
                is RunOutputState.Prior.Refused -> {
                    logger.error(
                        "[mc-testkit] 结果目录 ${resultsDir.absolutePath} 的上一次构建可能仍有进程在写输出（${prior.detail}），" +
                            "本次不接手。先跑对应的 stop 任务（stop<场景>Cluster、stop<场景>Stress、stop<名字>Serve 等，" +
                            "它们不受这道检查拦截）收掉残留进程；确认没有进程还在写这个目录后，删掉 ${state.absolutePath} 再重跑。",
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

                RunOutputState.Prior.None, RunOutputState.Prior.ClosedOk -> own(state)
            }
        }
        return budget
    }

    private fun own(state: File) {
        state.writeText(RunOutputState.running(runId))
        owned += state
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
