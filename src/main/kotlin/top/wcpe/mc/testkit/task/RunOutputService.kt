package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import top.wcpe.mc.testkit.bot.OutputBudget
import java.io.File
import java.util.*

/** One service per top-level Gradle build, including configuration-cache task copies. */
abstract class RunOutputService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val budget: OutputBudget by lazy { OutputBudget.fromConfig() }
    private val runId = UUID.randomUUID().toString()
    private val states = linkedSetOf<File>()

    @Synchronized
    fun attach(resultsDir: File): OutputBudget {
        require(budget.runLimit >= 32768) { "Build output budget must reserve bounded run metadata (minimum 32 KiB)" }
        check(
            states.size < 4 || File(
                resultsDir,
                "output-run.state"
            ) in states
        ) { "Too many result directories in one build" }
        val state = File(resultsDir, "output-run.state")
        if (states.add(state)) {
            resultsDir.mkdirs()
            // Refuse an interrupted/failed prior invocation rather than reusing its PASS files.
            if (state.isFile && state.length() > 0) {
                val old = state.inputStream().use { String(it.readNBytes(1024), Charsets.UTF_8) }
                if (!old.startsWith("CLOSED_OK\n")) {
                    budget.fail("RUN_FAIL prior-output-owner-not-successful")
                }
            }
            state.writeText("RUNNING\n$runId\n")
        }
        return budget
    }

    fun checkHealthy() {
        try {
            budget.checkHealthy()
        } catch (ex: IllegalStateException) {
            throw GradleException(ex.message ?: "RUN_FAIL", ex)
        }
    }

    override fun close() {
        budget.supervisor.close()
        states.forEach { state ->
            state.writeText(
                "${if (budget.failureReason() == null) "CLOSED_OK" else "RUN_FAIL"}\n$runId\n" +
                    "bytes=${budget.usedBytes()}\n${budget.failureReason().orEmpty().take(384)}\n"
            )
        }
        checkHealthy()
    }
}
