package top.wcpe.mc.testkit.topology

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.dsl.ProxySpec
import top.wcpe.mc.testkit.dsl.ScenarioSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 单场景多 bot（单场景多 bot，ADR-0009）的配置期校验：count≥1、多 bot 须各有唯一 role、压测禁 count/多 bot。
 * 纯函数穷举，复用 [TopologyResolver]。
 */
class MultiBotValidationTest {

    private fun backend(name: String, port: Int) = BackendSpec(name).apply { this.port = port }
    private fun proxy(name: String, port: Int, vararg routes: String) =
        ProxySpec(name).apply {
            this.port = port
            routesTo(*routes)
        }

    @Test
    @DisplayName("单后端配置双角色机器人时应解析成功")
    fun resolvesTwoRoleBotsOnSingleBackend() {
        val sc = ScenarioSpec("gui-edit").apply {
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
        val t = TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        assertEquals(1, t.backends.size)
    }

    @Test
    @DisplayName("集群按 count 复制同质机器人时应解析成功")
    fun resolvesHomogeneousClusterBotsByCount() {
        val sc = ScenarioSpec("g16").apply {
            backends("s1", "s2")
            via = "wf"
            bot {
                username = "P"
                action = "cross-server"
                count = 8
            }
        }
        val t = TopologyResolver.resolve(
            listOf(backend("s1", 25565), backend("s2", 25566)),
            listOf(proxy("wf", 25577, "s1", "s2")),
            listOf(sc),
        )
        assertEquals(2, t.backends.size)
    }

    @Test
    @DisplayName("单个匿名机器人应保持向后兼容并解析成功")
    fun resolvesSingleAnonymousBotForBackwardCompatibility() {
        val sc = ScenarioSpec("buy").apply {
            backend = "s1"
            bot {
                username = "BuyBot"
                action = "buy-success"
            }
        }
        TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
    }

    @Test
    @DisplayName("多个机器人包含匿名角色时应抛出中文错误")
    fun rejectsMultipleBotsContainingAnonymousRole() {
        val sc = ScenarioSpec("x").apply {
            backend = "s1"
            bot { action = "a" }
            bot("named") { action = "b" }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("唯一角色名") || ex.message!!.contains("匿名"))
    }

    @Test
    @DisplayName("多个机器人角色名重复时应抛出中文错误")
    fun rejectsDuplicateBotRoleNames() {
        val sc = ScenarioSpec("x").apply {
            backend = "s1"
            bot("dup") { action = "a" }
            bot("dup") { action = "b" }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("角色名重复"))
    }

    @Test
    @DisplayName("机器人 count 非正数时应抛出中文错误")
    fun rejectsNonPositiveBotCount() {
        val sc = ScenarioSpec("x").apply {
            backend = "s1"
            bot {
                action = "a"
                count = 0
            }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("count"))
    }

    @Test
    @DisplayName("压测场景的机器人配置 count 时应抛出中文错误")
    fun rejectsStressScenarioWithBotCount() {
        val sc = ScenarioSpec("load").apply {
            backends("s1")
            stress {
                botsPerServer = 10
                durationSeconds = 60
            }
            bot {
                action = "stress"
                count = 3
            }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("botsPerServer") || ex.message!!.contains("count"))
    }

    @Test
    @DisplayName("压测场景配置多个机器人时应抛出中文错误")
    fun rejectsStressScenarioWithMultipleBots() {
        val sc = ScenarioSpec("load").apply {
            backends("s1")
            stress {
                botsPerServer = 10
                durationSeconds = 60
            }
            bot("a") { action = "x" }
            bot("b") { action = "y" }
        }
        val ex = assertFailsWith<GradleException> {
            TopologyResolver.resolve(listOf(backend("s1", 25565)), emptyList(), listOf(sc))
        }
        assertTrue(ex.message!!.contains("不支持") || ex.message!!.contains("botsPerServer"))
    }
}
