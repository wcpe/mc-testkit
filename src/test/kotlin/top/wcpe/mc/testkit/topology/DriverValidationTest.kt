package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import top.wcpe.mc.testkit.task.McTestkitTasks.driverKey
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
        assertEquals(emptyList(), spec.argsFiles)
        assertEquals(false, spec.launchAfterReady)
    }

    @Test
    @DisplayName("argsFromFile 与 launchAfterReady 声明应被保留")
    fun acceptsArgsFromFileAndStagedLaunch() {
        val scenario = ScenarioSpec("lan").apply {
            backend = "s1"
            driver("host") { command = "java" }
            driver("join") {
                command = "java"
                launchAfterReady = true
                argsFromFile("build/lan/join-args.txt")
            }
        }
        TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(scenario))
        val join = scenario.driverSpecs.last()
        assertEquals(true, join.launchAfterReady)
        assertEquals(listOf("build/lan/join-args.txt"), join.argsFiles)
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
            driver {
                command = "node"
                killTimeoutSeconds = 0
            }
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
                listOf(
                    ProxySpec("wf").apply {
                        port = 25577
                        routesTo("s1")
                    },
                ),
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
                listOf(
                    ProxySpec("wf").apply {
                        port = 25577
                        routesTo("s1", "s2")
                    },
                ),
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

    @Test
    @DisplayName("driverKey 必带场景名——两个场景同名角色不会撞产物文件名")
    fun driverKeyCarriesScenarioName() {
        val scenarioA = ScenarioSpec("alpha").apply { backend = "s1"; driver { command = "node" } }
        val scenarioB = ScenarioSpec("beta").apply { backend = "s1"; driver { command = "node" } }
        val keyA = driverKey(scenarioA, scenarioA.driverSpecs[0], 0)
        val keyB = driverKey(scenarioB, scenarioB.driverSpecs[0], 0)
        assertEquals("alpha", keyA)
        assertEquals("beta", keyB)
        assertTrue(keyA != keyB, "两场景的 driver key 必须不同")
    }

    @Test
    @DisplayName("driverKey 多 driver 未命角色时按序号区分")
    fun driverKeyFallsBackToIndex() {
        val scenario = ScenarioSpec("smoke").apply {
            backend = "s1"
            driver { command = "node" }
            driver { command = "node" }
        }
        val first = driverKey(scenario, scenario.driverSpecs[0], 0)
        val second = driverKey(scenario, scenario.driverSpecs[1], 1)
        assertEquals("smoke-1", first)
        assertEquals("smoke-2", second)
    }
}
