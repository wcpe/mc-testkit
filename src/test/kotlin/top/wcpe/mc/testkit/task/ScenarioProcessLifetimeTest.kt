package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.testkit.bot.RunOutputSupervisor
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * 场景进程寿命上限（[McTestkitTasks.scenarioProcessLifetimeMs]）回归锚。
 *
 * 进程监管的期限若短于编排自身的结果等待，长场景会在跑到一半时被判 TIMEOUT；这里钉住「结果等待 + 压测时长 + 宽限」。
 */
class ScenarioProcessLifetimeTest {

    private val slackMs = TimeUnit.SECONDS.toMillis(McTestkitTasks.PROCESS_LIFETIME_SLACK_SECONDS)

    @Test
    @DisplayName("场景进程期限覆盖 resultTimeoutSeconds 再加宽限")
    fun lifetimeOutlastsResultWait() {
        val spec = ScenarioSpec("perf").apply { resultTimeoutSeconds = 1800 }
        assertEquals(TimeUnit.SECONDS.toMillis(1800) + slackMs, McTestkitTasks.scenarioProcessLifetimeMs(spec))
    }

    @Test
    @DisplayName("压测场景的进程期限再加上压测时长")
    fun stressAddsItsDuration() {
        val spec = ScenarioSpec("stress").apply { stress { durationSeconds = 900 } }
        assertEquals(
            TimeUnit.SECONDS.toMillis(spec.resultTimeoutSeconds + 900) + slackMs,
            McTestkitTasks.scenarioProcessLifetimeMs(spec),
        )
    }

    @Test
    @DisplayName("超大结果等待封顶到监管允许的最大期限")
    fun lifetimeIsCapped() {
        val spec = ScenarioSpec("huge").apply { resultTimeoutSeconds = Long.MAX_VALUE }
        assertEquals(RunOutputSupervisor.MAX_LIFETIME_MS, McTestkitTasks.scenarioProcessLifetimeMs(spec))
    }
}
