package top.wcpe.mc.testkit

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.topology.TopologyResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 节点运行时注入 节点级 DSL 与解析模型测试。 */
class NodeRuntimeInjectionDslTest {

    @Test
    @DisplayName("节点运行时声明应进入解析模型且重复环境变量使用后值")
    fun resolveNodeRuntimeFieldsAndUseLatestEnvironmentValue() {
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
    @DisplayName("旧 DSL 未声明节点运行时字段时应保持空值与依赖语义")
    fun preserveLegacyDslSemanticsWithoutNodeRuntimeFields() {
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
    @DisplayName("节点环境变量名为空白时应在配置期中文失败")
    fun rejectBlankNodeEnvironmentVariableName() {
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
    @DisplayName("节点环境变量名忽略大小写命中保留前缀时应中文失败")
    fun rejectReservedNodeEnvironmentVariablePrefixIgnoringCase() {
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
    @DisplayName("节点环境变量名包含等号时应在配置期中文失败")
    fun rejectEqualsSignInNodeEnvironmentVariableName() {
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

    @Test
    @DisplayName("mavenPlugin 声明应进入坐标访问器且与路径/被测声明并列共存")
    fun declareMavenPluginAlongsideLegacyDeclarations() {
        val extension = McTestkitExtension().apply {
            dependencies {
                pluginUnderTest = "under-test-sentinel.jar"
                plugin("legacy-dependency-sentinel.jar")
                mavenPlugin("com.example:foo-plugin:1.2.0")
                mavenPlugin("com.example:bar-plugin:2.0.0-SNAPSHOT")
            }
        }

        val dependencies = extension.declaredDependencies
        // 既有语义不变（纯加法）：被测声明与路径声明照旧
        assertEquals("under-test-sentinel.jar", dependencies.pluginUnderTest)
        assertEquals(listOf("legacy-dependency-sentinel.jar"), dependencies.plugins)
        // 新增维度：坐标按声明顺序进入独立访问器
        assertEquals(
            listOf("com.example:foo-plugin:1.2.0", "com.example:bar-plugin:2.0.0-SNAPSHOT"),
            dependencies.mavenPlugins,
        )
    }

    @Test
    @DisplayName("mavenPlugin 声明非法坐标时应在配置期中文失败")
    fun rejectInvalidMavenPluginCoordinateAtConfigurationTime() {
        val exception = assertFailsWith<GradleException> {
            McTestkitExtension().apply {
                dependencies { mavenPlugin("com.example:foo") }
            }
        }

        assertTrue(exception.message!!.contains("Maven 坐标"))
        assertTrue(exception.message!!.contains("group:artifact:version"))
    }

    @Test
    @DisplayName("mavenServer 声明应进入后端与代理的解析模型")
    fun declareMavenServerOnBackendAndProxy() {
        val extension = McTestkitExtension().apply {
            backend("backend-sentinel") { mavenServer("io.papermc.paper:paper:1.12.2") }
            proxy("proxy-sentinel") {
                routesTo("backend-sentinel")
                mavenServer("com.example:my-waterfall:1.20")
            }
        }

        val topology = TopologyResolver.resolve(extension)

        assertEquals("io.papermc.paper:paper:1.12.2", topology.backends.single().mavenServer)
        assertEquals("com.example:my-waterfall:1.20", topology.proxies.single().mavenServer)
    }

    @Test
    @DisplayName("未声明 mavenServer 时后端与代理的坐标应保持为空")
    fun leaveMavenServerNullWhenNotDeclared() {
        val extension = McTestkitExtension().apply {
            backend("backend-sentinel")
            proxy("proxy-sentinel") { routesTo("backend-sentinel") }
        }

        val topology = TopologyResolver.resolve(extension)

        assertEquals(null, topology.backends.single().mavenServer)
        assertEquals(null, topology.proxies.single().mavenServer)
    }

    @Test
    @DisplayName("mavenServer 声明非法坐标时应在配置期中文失败")
    fun rejectInvalidMavenServerCoordinateAtConfigurationTime() {
        val exception = assertFailsWith<GradleException> {
            McTestkitExtension().apply {
                backend("backend-sentinel") { mavenServer("com.example:foo") }
            }
        }

        assertTrue(exception.message!!.contains("Maven 坐标"))
        assertTrue(exception.message!!.contains("group:artifact:version"))
    }
}
