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
 * 集群场景（FR-10）的配置期校验（ADR-0008）：后端存在、必须 via、代理路由覆盖、与单后端互斥。
 * 纯函数穷举，复用 [TopologyResolver]。
 */
class ClusterValidationTest {

    private fun backend(name: String, port: Int) = BackendSpec(name).apply { this.port = port }
    private fun proxy(name: String, port: Int, vararg routes: String) =
        ProxySpec(name).apply { this.port = port; routesTo(*routes) }

    private fun clusterScenario(name: String, via: String?, vararg backends: String) =
        ScenarioSpec(name).apply { backends(*backends); this.via = via }

    @Test
    fun `合法集群场景解析通过`() {
        val t = TopologyResolver.resolve(
            listOf(backend("s1", 25565), backend("s2", 25566)),
            listOf(proxy("wf", 25577, "s1", "s2")),
            listOf(clusterScenario("x", "wf", "s1", "s2")),
        )
        assertEquals(2, t.backends.size)
    }

    @Test
    fun `集群场景缺 via 报中文错误`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565), backend("s2", 25566)),
                emptyList(),
                listOf(clusterScenario("x", null, "s1", "s2")),
            )
        }
        assertTrue(ex.message!!.contains("代理") || ex.message!!.contains("via"))
    }

    @Test
    fun `集群后端不存在报中文错误`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565)),
                listOf(proxy("wf", 25577, "s1")),
                listOf(clusterScenario("x", "wf", "s1", "s2")),
            )
        }
        assertTrue(ex.message!!.contains("s2"))
    }

    @Test
    fun `代理未路由到全部集群后端报中文错误`() {
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565), backend("s2", 25566)),
                listOf(proxy("wf", 25577, "s1")),
                listOf(clusterScenario("x", "wf", "s1", "s2")),
            )
        }
        assertTrue(ex.message!!.contains("s2") || ex.message!!.contains("路由"))
    }

    @Test
    fun `backend 与 backends 并用报中文错误`() {
        val sc = ScenarioSpec("x").apply { backend = "s1"; backends("s1", "s2"); via = "wf" }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                listOf(backend("s1", 25565), backend("s2", 25566)),
                listOf(proxy("wf", 25577, "s1", "s2")),
                listOf(sc),
            )
        }
        assertTrue(ex.message!!.contains("backend") || ex.message!!.contains("并用") || ex.message!!.contains("互斥"))
    }
}
