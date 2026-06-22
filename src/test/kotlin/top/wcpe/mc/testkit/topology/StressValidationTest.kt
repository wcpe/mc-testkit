package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 压测场景（FR-11）的配置期校验（ADR-0008）：须 backends、规模/时长为正、via 可选（设了须路由覆盖）。
 * 纯函数穷举，复用 [TopologyResolver]。
 */
class StressValidationTest {

    private fun backend(name: String, port: Int) = BackendSpec(name).apply { this.port = port }
    private fun proxy(name: String, port: Int, vararg routes: String) =
        ProxySpec(name).apply { this.port = port; routesTo(*routes) }

    private fun stressScenario(name: String, via: String?, bots: Int, duration: Long, vararg backends: String) =
        ScenarioSpec(name).apply {
            backends(*backends)
            this.via = via
            stress { botsPerServer = bots; durationSeconds = duration }
        }

    @Test
    fun `合法压测场景经代理解析通过`() {
        val t = TopologyResolver.resolve(
            listOf(backend("s1", 25565), backend("s2", 25566)),
            listOf(proxy("wf", 25577, "s1", "s2")),
            listOf(stressScenario("load", "wf", 50, 120, "s1", "s2")),
        )
        assertEquals(2, t.backends.size)
    }

    @Test
    fun `合法压测场景直连（无 via）解析通过`() {
        val t = TopologyResolver.resolve(
            listOf(backend("s1", 25565), backend("s2", 25566)),
            emptyList(),
            listOf(stressScenario("load", null, 50, 120, "s1", "s2")),
        )
        assertEquals(2, t.backends.size)
    }

    @Test
    fun `压测场景无 backends 报中文错误`() {
        val sc = ScenarioSpec("load").apply { stress { botsPerServer = 10; durationSeconds = 60 } }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("backends") || ex.message!!.contains("后端"))
    }

    @Test
    fun `botsPerServer 非正报中文错误`() {
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
    fun `durationSeconds 非正报中文错误`() {
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
    fun `压测设了 via 但路由未覆盖全部后端报中文错误`() {
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
