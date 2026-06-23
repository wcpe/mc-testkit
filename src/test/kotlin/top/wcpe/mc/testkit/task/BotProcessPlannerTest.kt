package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.dsl.BotSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 单场景多 bot 展开（FR-16，ADR-0009）的纯函数穷举单测：[BotProcessPlanner.expand]。
 *
 * 覆盖向后兼容（单匿名 bot：key=action、无 BOT_INDEX）、同质复制（count=N：各唯一 username/key、index 1..N）、
 * 异质双角色（各自 action/username/key）与业务 env 透传。
 */
class BotProcessPlannerTest {

    private fun bot(role: String? = null, configure: BotSpec.() -> Unit = {}): BotSpec =
        (if (role == null) BotSpec() else BotSpec(role)).apply(configure)

    @Test
    fun `无 bot 展开为空列表`() {
        assertTrue(BotProcessPlanner.expand("smoke", emptyList()).isEmpty())
    }

    @Test
    fun `单匿名 bot 与历史行为一致（key=action、无 BOT_INDEX）`() {
        val plans = BotProcessPlanner.expand(
            "buySuccess",
            listOf(
                bot {
                    username = "BuyBot"
                    action = "buy-success"
                },
            ),
        )
        assertEquals(1, plans.size)
        val p = plans.single()
        assertEquals("buy-success", p.action)
        assertEquals("BuyBot", p.username)
        assertEquals("buy-success", p.key) // 日志/pid key 沿用 action，向后兼容
        assertNull(p.botIndex) // 单进程不下发 BOT_INDEX
    }

    @Test
    fun `单匿名 bot 无 username 无 action 全部回落场景名`() {
        val p = BotProcessPlanner.expand("crossServer", listOf(bot())).single()
        assertEquals("crossServer", p.action)
        assertEquals("crossServer", p.username)
        assertEquals("crossServer", p.key)
        assertNull(p.botIndex)
    }

    @Test
    fun `同质复制 count=N 各唯一 username key 与 BOT_INDEX 1 到 N`() {
        val plans = BotProcessPlanner.expand(
            "g16",
            listOf(
                bot {
                    username = "P"
                    action = "cross-server"
                    count = 8
                },
            ),
        )
        assertEquals(8, plans.size)
        // action 同质统一；username = 基名 + 序号；key = 基名 + -序号；index 1..N
        assertEquals(List(8) { "cross-server" }, plans.map { it.action })
        assertEquals((1..8).map { "P$it" }, plans.map { it.username })
        assertEquals((1..8).map { "cross-server-$it" }, plans.map { it.key })
        assertEquals((1..8).toList(), plans.map { it.botIndex })
    }

    @Test
    fun `异质双角色各自 action username 与 key（无 index）`() {
        val plans = BotProcessPlanner.expand(
            "gui-edit",
            listOf(
                bot("admin") {
                    username = "Admin"
                    action = "gui-admin"
                },
                bot("target") {
                    username = "Target"
                    action = "gui-target"
                },
            ),
        )
        assertEquals(2, plans.size)
        val admin = plans[0]
        assertEquals("gui-admin", admin.action)
        assertEquals("Admin", admin.username)
        assertEquals("admin", admin.key) // key 取 role
        assertNull(admin.botIndex)
        val target = plans[1]
        assertEquals("gui-target", target.action)
        assertEquals("Target", target.username)
        assertEquals("target", target.key)
        assertNull(target.botIndex)
    }

    @Test
    fun `具名角色无显式 action 时 action 回落到 role`() {
        val p = BotProcessPlanner.expand("x", listOf(bot("admin"))).single()
        assertEquals("admin", p.action) // action 默认：role
        assertEquals("admin", p.username) // username 默认：role
        assertEquals("admin", p.key)
    }

    @Test
    fun `具名角色 count 大于 1 用 role 作基名复制`() {
        val plans = BotProcessPlanner.expand("load", listOf(bot("worker") { count = 3 }))
        assertEquals(listOf("worker-1", "worker-2", "worker-3"), plans.map { it.key })
        assertEquals(listOf("worker1", "worker2", "worker3"), plans.map { it.username })
        assertEquals(listOf(1, 2, 3), plans.map { it.botIndex })
    }

    @Test
    fun `业务 env 原样透传到每个进程 plan`() {
        val plans = BotProcessPlanner.expand(
            "g16",
            listOf(
                bot {
                    username = "P"
                    action = "cross-server"
                    count = 2
                    env("SHOP", "E2E")
                },
            ),
        )
        plans.forEach { assertEquals("E2E", it.env["SHOP"]) }
    }
}
