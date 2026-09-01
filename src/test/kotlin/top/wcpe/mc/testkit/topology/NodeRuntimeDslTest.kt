package top.wcpe.mc.testkit.topology

import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** 多版本代理与诊断编排 节点运行时 DSL 到不可变拓扑模型的验收测试。 */
class NodeRuntimeDslTest {

    @Test
    @DisplayName("节点运行时声明应原样解析到后端与代理模型")
    fun resolvesNodeRuntimeDeclarations() {
        val topology = TopologyResolver.resolve(
            backends = listOf(
                BackendSpec("backend").apply {
                    jvmArg("-Dbackend.sentinel=true")
                    javaAgent("BACKEND_AGENT")
                },
            ),
            proxies = listOf(
                ProxySpec("velocity").apply {
                    routesTo("backend")
                    version = "4.1.0"
                    javaVersion = 25
                    jvmArg("-Dproxy.sentinel=true")
                    javaAgent("PROXY_AGENT")
                },
            ),
            scenarios = emptyList(),
        )

        assertEquals(listOf("-Dbackend.sentinel=true"), topology.backends.single().jvmArgs)
        assertEquals(listOf("BACKEND_AGENT"), topology.backends.single().javaAgents)
        assertEquals("4.1.0", topology.proxies.single().version)
        assertEquals(25, topology.proxies.single().javaVersion)
        assertEquals(listOf("-Dproxy.sentinel=true"), topology.proxies.single().jvmArgs)
        assertEquals(listOf("PROXY_AGENT"), topology.proxies.single().javaAgents)
    }
}
