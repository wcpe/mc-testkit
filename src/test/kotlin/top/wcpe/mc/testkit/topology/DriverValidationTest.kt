package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * driver 车道（`scenario { driver { } }`）声明的配置期校验：command 必填非空白、
 * killTimeoutSeconds >0、多 driver 的 role 唯一；当前只支持直连单后端场景——
 * 经代理 / 集群 / 压测声明 driver 一律中文报错，不静默忽略。
 */
class DriverValidationTest {

    private fun backend(name: String, port: Int) = BackendSpec(name).apply { this.port = port }

    private fun scenarioWithDriver(name: String) =
        ScenarioSpec(name).apply {
            backend = "s1"
            driver {
                command = "node"
                args("driver/main.js", "arg1")
                env("CALCITE_MC_VERSION", "1.20.1")
            }
        }

    @Test
    @DisplayName("合法 driver 声明应解析成功并保留 command / args / env")
    fun acceptsValidDriver() {
        val scenario = scenarioWithDriver("calcite-pilot")
        TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        val spec = scenario.driverSpecs.first()
        assertEquals("node", spec.command)
        assertEquals(listOf("driver/main.js", "arg1"), spec.args)
        assertEquals("1.20.1", spec.env["CALCITE_MC_VERSION"])
        assertEquals(15L, spec.killTimeoutSeconds)
    }

    @Test
    @DisplayName("driver 缺 command 时应抛出中文错误")
    fun rejectsBlankCommand() {
        val scenario = ScenarioSpec("smoke").apply {
            backend = "s1"
            driver { args("x.js") }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        }
        assertTrue(ex.message!!.contains("command"), ex.message)
    }

    @Test
    @DisplayName("driver 的 killTimeoutSeconds 非正数时应抛出中文错误")
    fun rejectsNonPositiveKillTimeout() {
        val scenario = ScenarioSpec("smoke").apply {
            backend = "s1"
            driver { command = "node"; killTimeoutSeconds = 0 }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        }
        assertTrue(ex.message!!.contains("killTimeoutSeconds"), ex.message)
    }

    @Test
    @DisplayName("多 driver 角色名重复时应抛出中文错误")
    fun rejectsDuplicateRole() {
        val scenario = ScenarioSpec("smoke").apply {
            backend = "s1"
            driver("probe") { command = "node" }
            driver("probe") { command = "java" }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        }
        assertTrue(ex.message!!.contains("driver 角色名重复"), ex.message)
    }

    @Test
    @DisplayName("经代理场景声明 driver 时应抛出中文错误")
    fun rejectsDriverWithVia() {
        val scenario = ScenarioSpec("smoke").apply {
            backend = "s1"
            via = "wf"
            driver { command = "node" }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565)),
                listOf(ProxySpec("wf").apply { port = 25577; routesTo("s1") }),
                listOf(scenario),
            )
        }
        assertTrue(ex.message!!.contains("driver"), ex.message)
    }

    @Test
    @DisplayName("集群场景声明 driver 时应抛出中文错误")
    fun rejectsDriverOnCluster() {
        val scenario = ScenarioSpec("smoke").apply {
            backends("s1", "s2")
            via = "wf"
            driver { command = "node" }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565), backend("s2", 25566)),
                listOf(ProxySpec("wf").apply { port = 25577; routesTo("s1", "s2") }),
                listOf(scenario),
            )
        }
        assertTrue(ex.message!!.contains("driver"), ex.message)
    }

    @Test
    @DisplayName("压测场景声明 driver 时应抛出中文错误")
    fun rejectsDriverOnStress() {
        val scenario = ScenarioSpec("smoke").apply {
            backends("s1")
            driver { command = "node" }
            stress {
                botsPerServer = 2
                durationSeconds = 60
            }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565)),
                emptyList(),
                listOf(scenario),
            )
        }
        assertTrue(ex.message!!.contains("driver"), ex.message)
    }
}
