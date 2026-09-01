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
 * 压测场景（压测编排）的配置期校验（ADR-0008）：须 backends、规模/时长为正、via 可选（设了须路由覆盖）。
 * 纯函数穷举，复用 [TopologyResolver]。
 */
class StressValidationTest {

    private fun backend(name: String, port: Int) = BackendSpec(name).apply { this.port = port }
    private fun proxy(name: String, port: Int, vararg routes: String) =
        ProxySpec(name).apply {
            this.port = port
            routesTo(*routes)
        }

    private fun stressScenario(name: String, via: String?, bots: Int, duration: Long, vararg backends: String) =
        ScenarioSpec(name).apply {
            backends(*backends)
            this.via = via
            stress {
                botsPerServer = bots
                durationSeconds = duration
            }
        }

    @Test
    @DisplayName("合法压测场景经代理连接时应解析成功")
    fun resolvesValidProxiedStressScenario() {
        val t = TopologyResolver.resolve(
            listOf(backend("s1", 25565), backend("s2", 25566)),
            listOf(proxy("wf", 25577, "s1", "s2")),
            listOf(stressScenario("load", "wf", 50, 120, "s1", "s2")),
        )
        assertEquals(2, t.backends.size)
    }

    @Test
    @DisplayName("合法压测场景未配置代理时应直连解析成功")
    fun resolvesValidDirectStressScenario() {
        val t = TopologyResolver.resolve(
            listOf(backend("s1", 25565), backend("s2", 25566)),
            emptyList(),
            listOf(stressScenario("load", null, 50, 120, "s1", "s2")),
        )
        assertEquals(2, t.backends.size)
    }

    @Test
    @DisplayName("压测场景未配置后端时应抛出中文错误")
    fun rejectsStressScenarioWithoutBackends() {
        val sc = ScenarioSpec("load").apply {
            stress {
                botsPerServer = 10
                durationSeconds = 60
            }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("backends") || ex.message!!.contains("后端"))
    }

    @Test
    @DisplayName("每服机器人数非正数时应抛出中文错误")
    fun rejectsNonPositiveBotsPerServer() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565)),
                emptyList(),
                listOf(stressScenario("load", null, 0, 60, "s1")),
            )
        }
        assertTrue(ex.message!!.contains("botsPerServer"))
    }

    @Test
    @DisplayName("压测时长非正数时应抛出中文错误")
    fun rejectsNonPositiveDurationSeconds() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565)),
                emptyList(),
                listOf(stressScenario("load", null, 10, 0, "s1")),
            )
        }
        assertTrue(ex.message!!.contains("durationSeconds"))
    }

    @Test
    @DisplayName("压测代理未路由全部后端时应抛出中文错误")
    fun rejectsStressProxyMissingBackendRoute() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565), backend("s2", 25566)),
                listOf(proxy("wf", 25577, "s1")),
                listOf(stressScenario("load", "wf", 10, 60, "s1", "s2")),
            )
        }
        assertTrue(ex.message!!.contains("s2") || ex.message!!.contains("路由"))
    }
}
