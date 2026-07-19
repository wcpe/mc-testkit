package top.wcpe.mc.testkit.topology

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.McTestkitPlugin
import top.wcpe.mc.testkit.dsl.BackendPlatform
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.ProxyPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 拓扑解析从 `mcTestkit { }` 扩展入口（FR-03）的集成视角测试。
 *
 * 用 ProjectBuilder 应用插件、走 DSL 声明，再经 [TopologyResolver.resolve] 扩展重载解析，
 * 验证平台 / 版本 / 端口推导被正确带入模型——这是 FR-04 编排将走的真实入口。
 */
class TopologyFromExtensionTest {

    private fun extension(): McTestkitExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        return project.extensions.getByType(McTestkitExtension::class.java)
    }

    @Test
    @DisplayName("扩展声明代理和双后端时应解析出完整拓扑")
    fun resolvesCompleteTopologyFromExtension() {
        val ext = extension()
        ext.backend("s1") {
            platform = paper
            version = "1.20.1"
        }
        ext.backend("s2") {
            platform = folia
        }
        ext.proxy("wf") {
            platform = waterfall
            routesTo("s1", "s2")
        }

        val topology = TopologyResolver.resolve(ext)

        val s1 = topology.backends.first { it.name == "s1" }
        assertEquals(BackendPlatform.PAPER, s1.platform)
        assertEquals("1.20.1", s1.version)
        assertEquals(25565, s1.port)

        val s2 = topology.backends.first { it.name == "s2" }
        assertEquals(BackendPlatform.FOLIA, s2.platform)
        assertEquals(25566, s2.port)

        val wf = topology.proxies.single()
        assertEquals(ProxyPlatform.WATERFALL, wf.platform)
        assertEquals(25577, wf.port)
        assertEquals(listOf("s1", "s2"), wf.routes)
    }
}
