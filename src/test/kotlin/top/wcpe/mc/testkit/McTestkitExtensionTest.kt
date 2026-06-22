package top.wcpe.mc.testkit
import top.wcpe.mc.testkit.dsl.McTestkitExtension

import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.mc.testkit.dsl.BackendPlatform
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
    fun `应用插件后创建 mcTestkit 扩展`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        val ext = project.extensions.findByName("mcTestkit")
        assertNotNull(ext)
        assertTrue(ext is McTestkitExtension)
    }

    @Test
    fun `DSL 记录后端声明（名称 平台 版本 端口）`() {
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
    fun `DSL 记录代理声明与路由`() {
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
    fun `DSL 记录场景与可选机器人驱动`() {
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
    fun `后端默认平台为 paper 端口未声明时为 null（留待 FR-03 拓扑解析推导）`() {
        val ext = extension()
        ext.backend("s1")
        val s1 = ext.declaredBackends.single()
        assertEquals(BackendPlatform.PAPER, s1.platform)
        assertNull(s1.port)
    }

    @Test
    fun `dependencies 块记录待测插件注入`() {
        val ext = extension()
        ext.dependencies {
            pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
            plugin("SampleLib")
        }
        assertEquals("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR", ext.declaredDependencies.pluginUnderTest)
        assertEquals(listOf("SampleLib"), ext.declaredDependencies.plugins)
    }
}
