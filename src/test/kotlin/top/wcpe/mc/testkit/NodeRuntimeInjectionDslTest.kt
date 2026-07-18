package top.wcpe.mc.testkit

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.topology.TopologyResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** FR-20 节点级 DSL 与解析模型测试。 */
class NodeRuntimeInjectionDslTest {

    @Test
    fun `后端与代理节点声明进入解析模型且重复 env 后值覆盖`() {
        val extension = McTestkitExtension().apply {
            backend("backend-sentinel") {
                env("NODE_SENTINEL", "backend-first")
                env("NODE_SENTINEL", "backend-last")
                templateDirectory("backend-template-sentinel")
            }
            proxy("proxy-sentinel") {
                routesTo("backend-sentinel")
                plugin("proxy-plugin-a-sentinel.jar")
                plugin("proxy-plugin-b-sentinel.jar")
                env("NODE_SENTINEL", "proxy-sentinel")
                templateDirectory("proxy-template-sentinel")
            }
        }

        val topology = TopologyResolver.resolve(extension)
        val backend = topology.backends.single()
        val proxy = topology.proxies.single()

        assertEquals(mapOf("NODE_SENTINEL" to "backend-last"), backend.environment)
        assertEquals("backend-template-sentinel", backend.templateDirectory)
        assertEquals(listOf("proxy-plugin-a-sentinel.jar", "proxy-plugin-b-sentinel.jar"), proxy.plugins)
        assertEquals(mapOf("NODE_SENTINEL" to "proxy-sentinel"), proxy.environment)
        assertEquals("proxy-template-sentinel", proxy.templateDirectory)
    }

    @Test
    fun `旧 DSL 不声明节点运行时字段时解析为空且 dependencies 语义不变`() {
        val extension = McTestkitExtension().apply {
            backend("backend-sentinel")
            proxy("proxy-sentinel") { routesTo("backend-sentinel") }
            dependencies {
                pluginUnderTest = "backend-plugin-sentinel.jar"
                plugin("backend-dependency-sentinel.jar")
            }
        }

        val topology = TopologyResolver.resolve(extension)

        assertTrue(topology.backends.single().environment.isEmpty())
        assertEquals(null, topology.backends.single().templateDirectory)
        assertTrue(topology.proxies.single().plugins.isEmpty())
        assertTrue(topology.proxies.single().environment.isEmpty())
        assertEquals(null, topology.proxies.single().templateDirectory)
        assertEquals("backend-plugin-sentinel.jar", extension.declaredDependencies.pluginUnderTest)
        assertEquals(listOf("backend-dependency-sentinel.jar"), extension.declaredDependencies.plugins)
    }

    @Test
    fun `节点 env 名为空白时配置期中文失败`() {
        val exception = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("backend-sentinel").apply { env(" ", "value-sentinel") }),
                proxies = emptyList(),
                scenarios = emptyList(),
            )
        }

        assertTrue(exception.message!!.contains("环境变量名"))
        assertTrue(exception.message!!.contains("不能为空"))
        assertTrue(exception.message!!.contains("backend-sentinel"))
    }

    @Test
    fun `节点 env 名任意大小写命中框架前缀时配置期中文失败`() {
        val backendException = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(
                    BackendSpec("backend-sentinel").apply {
                        env("mc_testkit_e2e_backend_name", "value-sentinel")
                    },
                ),
                proxies = emptyList(),
                scenarios = emptyList(),
            )
        }
        val proxyException = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("backend-sentinel")),
                proxies = listOf(
                    ProxySpec("proxy-sentinel").apply {
                        routesTo("backend-sentinel")
                        env("Mc_TestKit_E2E_Node", "value-sentinel")
                    },
                ),
                scenarios = emptyList(),
            )
        }

        assertTrue(backendException.message!!.contains("保留前缀"))
        assertTrue(proxyException.message!!.contains("保留前缀"))
    }

    @Test
    fun `节点 env 名含非法等号时配置期中文失败`() {
        val exception = assertFailsWith<GradleException> {
            TopologyResolver.resolve(
                backends = listOf(BackendSpec("backend-sentinel").apply { env("BAD=NAME", "value-sentinel") }),
                proxies = emptyList(),
                scenarios = emptyList(),
            )
        }

        assertTrue(exception.message!!.contains("非法"))
        assertTrue(exception.message!!.contains("BAD=NAME"))
    }
}
