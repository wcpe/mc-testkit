package top.wcpe.mc.testkit
import org.gradle.testfixtures.ProjectBuilder
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
    fun `单匿名 bot 记录到 botSpecs 且 role 为 null（向后兼容）`() {
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
    fun `具名角色与 count 被记录`() {
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
    fun `同场景多个具名 bot 按声明顺序记录`() {
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
    fun `无 bot 时 botSpecs 为空且 botSpec 为 null`() {
        val ext = extension()
        ext.scenario("smoke")
        val sc = ext.declaredScenarios.single()
        assertEquals(0, sc.botSpecs.size)
        assertNull(sc.botSpec)
    }

    @Test
    fun `count 默认为 1`() {
        val ext = extension()
        ext.scenario("buy") {
            backend = "s1"
            bot { action = "a" }
        }
        assertEquals(1, ext.declaredScenarios.single().botSpecs.single().count)
    }
}
