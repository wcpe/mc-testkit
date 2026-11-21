package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 场景结果等待上限 `resultTimeoutSeconds` 的默认值与配置期校验：缺省沿用 600 秒；单后端 / 集群 / 压测
 * 三类场景声明非正数都在配置期中文报错。
 */
class ScenarioResultTimeoutValidationTest {

    private fun backend(name: String, port: Int) = BackendSpec(name).apply { this.port = port }

    private fun proxy(name: String, port: Int, vararg routes: String) =
        ProxySpec(name).apply {
            this.port = port
            routesTo(*routes)
        }

    private fun singleBackendScenario(name: String, timeoutSeconds: Long) =
        ScenarioSpec(name).apply {
            backend = "s1"
            resultTimeoutSeconds = timeoutSeconds
        }

    @Test
    @DisplayName("未声明时结果等待上限沿用 600 秒")
    fun defaultsToSixHundredSeconds() {
        assertEquals(600L, McTestkitDefaults.RESULT_TIMEOUT_SECONDS)
        assertEquals(McTestkitDefaults.RESULT_TIMEOUT_SECONDS, ScenarioSpec("smoke").resultTimeoutSeconds)
    }

    @Test
    @DisplayName("声明为正数的场景应解析成功并保留该值")
    fun acceptsPositiveTimeout() {
        val scenario = singleBackendScenario("long-load", 1500)
        TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        assertEquals(1500L, scenario.resultTimeoutSeconds)
    }

    @Test
    @DisplayName("单后端场景声明非正数时应抛出中文错误")
    fun rejectsNonPositiveTimeoutOnSingleBackendScenario() {
        listOf(0L, -1L).forEach { value ->
            val ex = assertFailsWith<GradleException> {
                TopologyResolver.resolve(
                    listOf(backend("s1", 25565)),
                    emptyList(),
                    listOf(singleBackendScenario("smoke", value)),
                )
            }
            assertTrue(ex.message!!.contains("resultTimeoutSeconds"), ex.message)
            assertTrue(ex.message!!.contains("smoke"), ex.message)
        }
    }

    @Test
    @DisplayName("集群场景声明非正数时应抛出中文错误")
    fun rejectsNonPositiveTimeoutOnClusterScenario() {
        val scenario = ScenarioSpec("cross").apply {
            backends("s1", "s2")
            via = "wf"
            resultTimeoutSeconds = 0
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565), backend("s2", 25566)),
                listOf(proxy("wf", 25577, "s1", "s2")),
                listOf(scenario),
            )
        }
        assertTrue(ex.message!!.contains("resultTimeoutSeconds"), ex.message)
    }

    @Test
    @DisplayName("压测场景声明非正数时应抛出中文错误")
    fun rejectsNonPositiveTimeoutOnStressScenario() {
        val scenario = ScenarioSpec("load").apply {
            backends("s1")
            stress {
                botsPerServer = 1
                durationSeconds = 60
            }
            resultTimeoutSeconds = -5
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        }
        assertTrue(ex.message!!.contains("resultTimeoutSeconds"), ex.message)
    }
}
