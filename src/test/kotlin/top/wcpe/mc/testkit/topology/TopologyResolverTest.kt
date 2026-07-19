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
 * 拓扑解析器（FR-03）单元测试：穷举端口推导与配置期校验。
 *
 * 直接构造冻结 spec（无需 Project），验证纯函数 [TopologyResolver.resolve] 的输入→输出
 * 与各类校验失败的中文错误。
 */
class TopologyResolverTest {

    // ── 测试辅助：直接构造冻结 spec ──

    private fun backend(name: String, port: Int? = null): BackendSpec =
        BackendSpec(name).apply { this.port = port }

    private fun proxy(name: String, port: Int? = null, vararg routesTo: String): ProxySpec =
        ProxySpec(name).apply {
            this.port = port
            routesTo(*routesTo)
        }

    private fun scenario(name: String, backend: String? = null, via: String? = null): ScenarioSpec =
        ScenarioSpec(name).apply {
            this.backend = backend
            this.via = via
        }

    private fun resolve(
        backends: List<BackendSpec> = emptyList(),
        proxies: List<ProxySpec> = emptyList(),
        scenarios: List<ScenarioSpec> = emptyList(),
    ): Topology = TopologyResolver.resolve(backends, proxies, scenarios)

    // ── happy path ──

    @Test
    @DisplayName("单后端未配置代理时应构建仅含该后端的拓扑")
    fun resolvesSingleBackendWithoutProxy() {
        val topology = resolve(backends = listOf(backend("s1", port = 25565)))
        assertEquals(1, topology.backends.size)
        assertTrue(topology.proxies.isEmpty())
        val s1 = topology.backends.single()
        assertEquals("s1", s1.name)
        assertEquals(25565, s1.port)
    }

    @Test
    @DisplayName("代理连接多个后端时应构建路由正确的拓扑")
    fun resolvesProxyWithMultipleBackendRoutes() {
        val topology = resolve(
            backends = listOf(backend("s1", port = 25565), backend("s2", port = 25566)),
            proxies = listOf(proxy("wf", port = 25577, routesTo = arrayOf("s1", "s2"))),
        )
        assertEquals(2, topology.backends.size)
        val wf = topology.proxies.single()
        assertEquals("wf", wf.name)
        assertEquals(25577, wf.port)
        assertEquals(listOf("s1", "s2"), wf.routes)
    }

    @Test
    @DisplayName("相同输入重复解析时应返回相等结果")
    fun returnsEqualResultsForSameInput() {
        val backends = listOf(backend("s1"), backend("s2"))
        val proxies = listOf(proxy("wf", routesTo = arrayOf("s1", "s2")))
        val first = TopologyResolver.resolve(backends, proxies, emptyList())
        val second = TopologyResolver.resolve(backends, proxies, emptyList())
        assertEquals(first, second)
    }

    // ── 端口推导 ──

    @Test
    @DisplayName("后端未声明端口时应按基数和声明序号推导")
    fun derivesBackendPortsByDeclarationOrder() {
        val topology = resolve(backends = listOf(backend("s1"), backend("s2"), backend("s3")))
        assertEquals(
            listOf(25565, 25566, 25567),
            topology.backends.map { it.port },
        )
    }

    @Test
    @DisplayName("代理未声明端口时应按基数和声明序号推导")
    fun derivesProxyPortsByDeclarationOrder() {
        val topology = resolve(
            backends = listOf(backend("s1", port = 25565)),
            proxies = listOf(
                proxy("p1", routesTo = arrayOf("s1")),
                proxy("p2", routesTo = arrayOf("s1")),
            ),
        )
        assertEquals(
            listOf(25577, 25578),
            topology.proxies.map { it.port },
        )
    }

    @Test
    @DisplayName("显式端口应优先保留且不被推导端口覆盖")
    fun preservesExplicitPortOverDerivedPort() {
        val topology = resolve(
            backends = listOf(
                backend("s1", port = 30000),
                backend("s2"),
            ),
        )
        // s1 保留显式 30000；s2 取后端基数 + 其在后端中的序号(1) = 25566
        assertEquals(30000, topology.backends.first { it.name == "s1" }.port)
        assertEquals(25566, topology.backends.first { it.name == "s2" }.port)
    }

    // ── 校验：命名 ──

    @Test
    @DisplayName("后端名称重复时应抛出中文错误")
    fun rejectsDuplicateBackendNames() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1"), backend("s1")))
        }
        assertTrue(ex.message!!.contains("后端"))
        assertTrue(ex.message!!.contains("s1"))
    }

    @Test
    @DisplayName("代理名称重复时应抛出中文错误")
    fun rejectsDuplicateProxyNames() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(
                    proxy("wf", routesTo = arrayOf("s1")),
                    proxy("wf", routesTo = arrayOf("s1")),
                ),
            )
        }
        assertTrue(ex.message!!.contains("代理"))
        assertTrue(ex.message!!.contains("wf"))
    }

    @Test
    @DisplayName("后端与代理名称冲突时应抛出中文错误")
    fun rejectsBackendAndProxyNameCollision() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("node")),
                proxies = listOf(proxy("node", routesTo = arrayOf("node"))),
            )
        }
        assertTrue(ex.message!!.contains("node"))
    }

    @Test
    @DisplayName("后端名称为空白时应抛出中文错误")
    fun rejectsBlankBackendName() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("  ")))
        }
        assertTrue(ex.message!!.contains("名"))
    }

    // ── 校验：路由 ──

    @Test
    @DisplayName("路由目标后端不存在时应抛出中文错误")
    fun rejectsMissingRouteBackend() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(proxy("wf", routesTo = arrayOf("s2"))),
            )
        }
        assertTrue(ex.message!!.contains("wf"))
        assertTrue(ex.message!!.contains("s2"))
    }

    @Test
    @DisplayName("代理未配置任何路由时应抛出中文错误")
    fun rejectsProxyWithoutRoutes() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(proxy("wf")),
            )
        }
        assertTrue(ex.message!!.contains("wf"))
    }

    // ── 校验：端口冲突 ──

    @Test
    @DisplayName("两个后端显式端口冲突时应抛出中文错误")
    fun rejectsDuplicateExplicitBackendPorts() {
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1", port = 25565), backend("s2", port = 25565)))
        }
        assertTrue(ex.message!!.contains("25565"))
        assertTrue(ex.message!!.contains("端口"))
    }

    @Test
    @DisplayName("后端与代理端口冲突时应抛出中文错误")
    fun rejectsBackendAndProxyPortCollision() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1", port = 25577)),
                proxies = listOf(proxy("wf", port = 25577, routesTo = arrayOf("s1"))),
            )
        }
        assertTrue(ex.message!!.contains("25577"))
    }

    @Test
    @DisplayName("显式端口与推导端口冲突时应抛出中文错误")
    fun rejectsExplicitAndDerivedPortCollision() {
        // s1 显式 25566；s2 推导 = 基数 25565 + 序号1 = 25566，相撞
        val ex = assertFailsWith<GradleException> {
            resolve(backends = listOf(backend("s1", port = 25566), backend("s2")))
        }
        assertTrue(ex.message!!.contains("25566"))
    }

    // ── 校验：场景引用 ──

    @Test
    @DisplayName("场景引用不存在的后端时应抛出中文错误")
    fun rejectsScenarioReferencingMissingBackend() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                scenarios = listOf(scenario("buy", backend = "sX")),
            )
        }
        assertTrue(ex.message!!.contains("buy"))
        assertTrue(ex.message!!.contains("sX"))
    }

    @Test
    @DisplayName("场景引用不存在的代理时应抛出中文错误")
    fun rejectsScenarioReferencingMissingProxy() {
        val ex = assertFailsWith<GradleException> {
            resolve(
                backends = listOf(backend("s1")),
                proxies = listOf(proxy("wf", routesTo = arrayOf("s1"))),
                scenarios = listOf(scenario("buy", via = "pX")),
            )
        }
        assertTrue(ex.message!!.contains("buy"))
        assertTrue(ex.message!!.contains("pX"))
    }

    @Test
    @DisplayName("场景引用存在的后端与代理时应解析成功")
    fun resolvesScenarioWithExistingBackendAndProxy() {
        val topology = resolve(
            backends = listOf(backend("s1", port = 25565)),
            proxies = listOf(proxy("wf", port = 25577, routesTo = arrayOf("s1"))),
            scenarios = listOf(scenario("buy", backend = "s1", via = "wf")),
        )
        assertEquals(1, topology.backends.size)
        assertEquals(1, topology.proxies.size)
    }
}
