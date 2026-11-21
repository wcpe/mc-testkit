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
        check(states.size < 4 || state in states) { "同一次构建最多接手 4 个结果目录" }
        if (states.add(state)) {
            resultsDir.mkdirs()
            when (val prior = RunOutputState.read(state)) {
                is RunOutputState.Prior.Refused -> {
                    logger.error(
                        "[mc-testkit] 结果目录 ${resultsDir.absolutePath} 的上一次构建可能仍有进程在写输出（${prior.detail}），" +
                            "本次不接手。确认那次构建起的 bot 与钩子进程都已结束后，删掉 ${state.absolutePath} 再重跑。",
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
        val record = RunOutputState.closed(runId, budget.usedBytes(), budget.failureReason(), supervisor.survivors())
        owned.forEach { it.writeText(record) }
        checkHealthy()
    }

    private companion object {
        val logger = Logging.getLogger(RunOutputService::class.java)
    }
}
