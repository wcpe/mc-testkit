package top.wcpe.mc.testkit
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 单场景多 bot（FR-16，ADR-0009）的 DSL 形态契约测试：bot 列表、具名角色、count，
 * 以及 `botSpec` 向后兼容（= 首个）。
 */
class MultiBotContractTest {

    private fun extension(): McTestkitExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        return project.extensions.getByType(McTestkitExtension::class.java)
    }

    @Test
    @DisplayName("单个匿名机器人应写入列表并保持旧属性兼容")
    fun recordAnonymousBotAndKeepLegacyBotSpec() {
        val ext = extension()
        ext.scenario("buy") {
            backend = "s1"
            bot {
                username = "BuyBot"
                action = "buy-success"
            }
        }
        val sc = ext.declaredScenarios.single()
        assertEquals(1, sc.botSpecs.size)
        assertNull(sc.botSpecs.single().role)
        // botSpec 向后兼容：返回首个
        assertSame(sc.botSpecs.first(), sc.botSpec)
        assertEquals("BuyBot", sc.botSpec?.username)
    }

    @Test
    @DisplayName("机器人数量应被记录且未命名角色应保持为空")
    fun recordBotCountWithoutRole() {
        val ext = extension()
        ext.scenario("g16") {
            backends("s1", "s2")
            via = "wf"
            bot {
                username = "P"
                action = "cross-server"
                count = 8
            }
        }
        val bot = ext.declaredScenarios.single().botSpecs.single()
        assertEquals(8, bot.count)
        assertEquals("cross-server", bot.action)
        assertNull(bot.role)
    }

    @Test
    @DisplayName("同一场景的多个具名机器人应按声明顺序记录")
    fun recordMultipleNamedBotsInDeclarationOrder() {
        val ext = extension()
        ext.scenario("gui-edit") {
            backend = "s1"
            bot("admin") {
                username = "Admin"
                action = "gui-admin"
            }
            bot("target") {
                username = "Target"
                action = "gui-target"
            }
        }
        val sc = ext.declaredScenarios.single()
        assertEquals(2, sc.botSpecs.size)
        assertEquals(listOf("admin", "target"), sc.botSpecs.map { it.role })
        assertEquals(listOf("gui-admin", "gui-target"), sc.botSpecs.map { it.action })
        // botSpec 向后兼容：返回首个（admin）
        assertEquals("admin", sc.botSpec?.role)
    }

    @Test
    @DisplayName("未声明机器人时列表应为空且旧属性应为空")
    fun keepBotCollectionsEmptyWhenNotDeclared() {
        val ext = extension()
        ext.scenario("smoke")
        val sc = ext.declaredScenarios.single()
        assertEquals(0, sc.botSpecs.size)
        assertNull(sc.botSpec)
    }

    @Test
    @DisplayName("未指定机器人数量时应默认为一")
    fun defaultBotCountToOne() {
        val ext = extension()
        ext.scenario("buy") {
            backend = "s1"
            bot { action = "a" }
        }
        assertEquals(1, ext.declaredScenarios.single().botSpecs.single().count)
    }
}
