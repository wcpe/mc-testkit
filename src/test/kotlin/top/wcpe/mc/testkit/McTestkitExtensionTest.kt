package top.wcpe.mc.testkit
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendPlatform
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxyPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * mcTestkit DSL 形态（FR-01）单元测试：用 ProjectBuilder 应用插件、调用 DSL、读回声明。
 *
 * FR-01 只验证「DSL 形态 + 声明被忠实记录」；拓扑解析/端口推导/校验属 FR-03，不在此测。
 */
class McTestkitExtensionTest {

    private fun extension(): McTestkitExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        return project.extensions.getByType(McTestkitExtension::class.java)
    }

    @Test
    @DisplayName("应用插件后应创建 mcTestkit 扩展")
    fun createMcTestkitExtensionWhenPluginApplied() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        val ext = project.extensions.findByName("mcTestkit")
        assertNotNull(ext)
        assertTrue(ext is McTestkitExtension)
    }

    @Test
    @DisplayName("后端 DSL 应完整记录名称平台版本与端口")
    fun recordBackendDeclarationFromDsl() {
        val ext = extension()
        ext.backend("s1") {
            platform = paper
            version = "1.20.1"
            port = 25565
        }
        assertEquals(1, ext.declaredBackends.size)
        val s1 = ext.declaredBackends.single()
        assertEquals("s1", s1.name)
        assertEquals(BackendPlatform.PAPER, s1.platform)
        assertEquals("1.20.1", s1.version)
        assertEquals(25565, s1.port)
    }

    @Test
    @DisplayName("代理 DSL 应记录代理声明与路由目标")
    fun recordProxyDeclarationAndRoutesFromDsl() {
        val ext = extension()
        ext.backend("s1")
        ext.proxy("wf") {
            platform = waterfall
            port = 25577
            routesTo("s1")
        }
        val wf = ext.declaredProxies.single()
        assertEquals("wf", wf.name)
        assertEquals(ProxyPlatform.WATERFALL, wf.platform)
        assertEquals(25577, wf.port)
        assertEquals(listOf("s1"), wf.routes)
    }

    @Test
    @DisplayName("场景 DSL 应记录场景与可选机器人配置")
    fun recordScenarioAndOptionalBotFromDsl() {
        val ext = extension()
        ext.scenario("smoke")
        ext.scenario("buySuccess") {
            backend = "s1"
            bot {
                username = "BuyBot"
                action = "buy-success"
            }
        }
        assertEquals(2, ext.declaredScenarios.size)
        val smoke = ext.declaredScenarios.first { it.name == "smoke" }
        assertNull(smoke.botSpec)
        val buy = ext.declaredScenarios.first { it.name == "buySuccess" }
        assertEquals("s1", buy.backend)
        assertEquals("BuyBot", buy.botSpec?.username)
        assertEquals("buy-success", buy.botSpec?.action)
    }

    @Test
    @DisplayName("未指定后端平台与端口时应默认使用 Paper 且端口为空")
    fun usePaperAndNullPortAsBackendDefaults() {
        val ext = extension()
        ext.backend("s1")
        val s1 = ext.declaredBackends.single()
        assertEquals(BackendPlatform.PAPER, s1.platform)
        assertNull(s1.port)
    }

    @Test
    @DisplayName("依赖 DSL 应记录待测插件与附加插件")
    fun recordPluginDependenciesFromDsl() {
        val ext = extension()
        ext.dependencies {
            pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
            plugin("SampleLib")
        }
        assertEquals("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR", ext.declaredDependencies.pluginUnderTest)
        assertEquals(listOf("SampleLib"), ext.declaredDependencies.plugins)
    }
}
