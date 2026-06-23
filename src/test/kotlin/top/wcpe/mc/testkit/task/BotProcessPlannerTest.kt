package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.dsl.BotSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `混合：具名 count 复制 + 另一具名单进程，顺序与序号正确`() {
        val plans = BotProcessPlanner.expand(
            "raid",
            listOf(
                bot("worker") { count = 3 },
                bot("boss") { username = "Boss" },
            ),
        )
        assertEquals(4, plans.size)
        assertEquals(listOf("worker-1", "worker-2", "worker-3", "boss"), plans.map { it.key })
        assertEquals(listOf("worker1", "worker2", "worker3", "Boss"), plans.map { it.username })
        assertEquals(listOf(1, 2, 3, null), plans.map { it.botIndex })
    }

    // ── extraEnvironments：每进程「追加 env」契约（FR-16 核心：唯一名 / 序号 / 共享 env）──

    @Test
    fun `单 bot 不强制下发 BOT_USERNAME 与 BOT_INDEX（保留消费方 override，向后兼容）`() {
        val plans = BotProcessPlanner.expand(
            "buy",
            listOf(
                bot {
                    username = "BuyBot"
                    action = "buy"
                },
            ),
        )
        val env = BotProcessPlanner.extraEnvironments(plans).single()
        assertFalse(env.containsKey(McTestkitEnv.BOT_USERNAME), "单进程不应强制下发 BOT_USERNAME（留给 BotConnection 走消费方 override）")
        assertFalse(env.containsKey(McTestkitEnv.BOT_INDEX), "单进程不应下发 BOT_INDEX")
    }

    @Test
    fun `多 bot 每进程强制下发各自唯一 BOT_USERNAME`() {
        val plans = BotProcessPlanner.expand(
            "gui-edit",
            listOf(
                bot("admin") { username = "Admin" },
                bot("target") { username = "Target" },
            ),
        )
        val envs = BotProcessPlanner.extraEnvironments(plans)
        assertEquals("Admin", envs[0][McTestkitEnv.BOT_USERNAME])
        assertEquals("Target", envs[1][McTestkitEnv.BOT_USERNAME])
    }

    @Test
    fun `同质复制每进程下发唯一 username 与 BOT_INDEX`() {
        val plans = BotProcessPlanner.expand(
            "g16",
            listOf(
                bot {
                    username = "P"
                    action = "cross-server"
                    count = 3
                },
            ),
        )
        val envs = BotProcessPlanner.extraEnvironments(plans)
        assertEquals(listOf("P1", "P2", "P3"), envs.map { it[McTestkitEnv.BOT_USERNAME] })
        assertEquals(listOf("1", "2", "3"), envs.map { it[McTestkitEnv.BOT_INDEX] })
    }

    @Test
    fun `共享 env（如 CLUSTER_BACKENDS）合入每个进程`() {
        val plans = BotProcessPlanner.expand(
            "g16",
            listOf(
                bot {
                    username = "P"
                    action = "cross-server"
                    count = 2
                },
            ),
        )
        val envs = BotProcessPlanner.extraEnvironments(plans, mapOf(McTestkitEnv.CLUSTER_BACKENDS to "s1,s2"))
        envs.forEach { assertEquals("s1,s2", it[McTestkitEnv.CLUSTER_BACKENDS]) }
    }

    @Test
    fun `多 bot 时强制唯一名盖过业务 env 里的单值 BOT_USERNAME`() {
        // 消费方在 bot env 里塞了单值 BOT_USERNAME：多进程时须被强制唯一名覆盖（先写业务 env、再写强制名）
        val plans = BotProcessPlanner.expand(
            "g16",
            listOf(
                bot {
                    username = "P"
                    action = "cross-server"
                    count = 2
                    env(McTestkitEnv.BOT_USERNAME, "Fixed")
                },
            ),
        )
        val envs = BotProcessPlanner.extraEnvironments(plans)
        assertEquals(listOf("P1", "P2"), envs.map { it[McTestkitEnv.BOT_USERNAME] })
    }
}
