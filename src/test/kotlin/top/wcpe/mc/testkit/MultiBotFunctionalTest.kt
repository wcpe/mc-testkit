package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单场景多 bot（单场景多 bot，ADR-0009）以消费者视角驱动的 TestKit 集成测试：声明多 bot 时既有任务
 * （`e2e<Key>Cluster` / `launch<Key>Bot` / `e2e<Key>WithBot`）照常注册（不新增任务名），非法多 bot
 * 配置期中文报错。**不真跑**任务体（不下载 / 不起服 / 不起 bot）——真实多 bot 跑通属实机维度。
 */
class MultiBotFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    private fun runTasks(): String =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--all", "--stacktrace")
            .build()
            .output

    @Test
    @DisplayName("集群复制多个机器人时应沿用既有集群任务名称")
    fun registerExistingClusterTasksForReplicatedBots() {
        write("settings.gradle.kts", """rootProject.name = "multibot-cluster"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                backend("s2") { platform = paper; version = "1.20.1"; port = 25566 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1", "s2") }
                scenario("g16") {
                    backends("s1", "s2")
                    via = "wf"
                    bot { username = "P"; action = "cross-server"; count = 8 }
                }
            }
            """.trimIndent(),
        )
        val output = runTasks()
        assertTrue("e2eG16Cluster" in output, "应注册集群任务 e2eG16Cluster")
        assertTrue("stopG16Cluster" in output, "应注册集群收尾任务 stopG16Cluster")
    }

    @Test
    @DisplayName("集群多机器人任务应由集群收尾任务回收全部进程")
    fun finalizeClusterBotsWithClusterStopTask() {
        write("settings.gradle.kts", """rootProject.name = "multibot-cluster"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                backend("s2") { platform = paper; version = "1.20.1"; port = 25566 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1", "s2") }
                scenario("g16") {
                    backends("s1", "s2")
                    via = "wf"
                    bot { username = "P"; action = "cross-server"; count = 8 }
                }
            }
            """.trimIndent(),
        )
        // dry-run 构建任务图：finalizedBy 的收尾任务会被列出（收尾全部后端/代理/bot pid）
        val output = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("e2eG16Cluster", "--dry-run", "--stacktrace")
            .build()
            .output
        assertTrue(":stopG16Cluster" in output, "集群任务应 finalizedBy stopG16Cluster（收尾全部后端/代理/多 bot）\n$output")
    }

    @Test
    @DisplayName("单后端双角色机器人应注册既有启动验证与组合任务")
    fun registerExistingTasksForTwoRoleBots() {
        write("settings.gradle.kts", """rootProject.name = "multibot-gui"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                scenario("gui-edit") {
                    backend = "s1"
                    bot("admin") { username = "Admin"; action = "gui-admin" }
                    bot("target") { username = "Target"; action = "gui-target" }
                }
            }
            """.trimIndent(),
        )
        val output = runTasks()
        assertTrue("prepareE2eGuiEdit" in output, "应注册 prepareE2eGuiEdit")
        assertTrue("launchGuiEditBot" in output, "应注册 launchGuiEditBot（起多个 bot 进程）")
        assertTrue("e2eGuiEdit" in output, "应注册 e2eGuiEdit")
        assertTrue("e2eGuiEditWithBot" in output, "应注册 e2eGuiEditWithBot")
    }

    @Test
    @DisplayName("组合任务应触发双角色机器人的启动与验证任务")
    fun wireWithBotTaskToLaunchAndVerify() {
        write("settings.gradle.kts", """rootProject.name = "multibot-gui"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                scenario("gui-edit") {
                    backend = "s1"
                    bot("admin") { username = "Admin"; action = "gui-admin" }
                    bot("target") { username = "Target"; action = "gui-target" }
                }
            }
            """.trimIndent(),
        )
        val output = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("e2eGuiEditWithBot", "--dry-run", "--stacktrace")
            .build()
            .output
        assertTrue(":launchGuiEditBot " in output, "withBot 应触发 launchGuiEditBot\n$output")
        assertTrue(":e2eGuiEdit " in output, "withBot 应触发 e2eGuiEdit\n$output")
    }

    @Test
    @DisplayName("单机器人场景应继续注册既有任务以保持兼容")
    fun keepExistingTasksForSingleBotScenario() {
        write("settings.gradle.kts", """rootProject.name = "singlebot"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                scenario("buySuccess") {
                    backend = "s1"
                    bot { username = "BuyBot"; action = "buy-success" }
                }
            }
            """.trimIndent(),
        )
        val output = runTasks()
        assertTrue("launchBuySuccessBot" in output, "单 bot 仍注册 launchBuySuccessBot")
        assertTrue("e2eBuySuccessWithBot" in output, "单 bot 仍注册 e2eBuySuccessWithBot")
    }

    @Test
    @DisplayName("多个机器人缺少唯一角色名时应在配置期中文报错")
    fun rejectMultipleBotsWithoutUniqueRoles() {
        write("settings.gradle.kts", """rootProject.name = "bad-multibot"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; port = 25565 }
                scenario("x") {
                    backend = "s1"
                    bot { action = "a" }
                    bot { action = "b" }
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()
        assertTrue(
            result.output.contains("唯一角色名") || result.output.contains("匿名"),
            "多 bot 缺唯一角色名应配置期中文报错\n${result.output}",
        )
    }

    @Test
    @DisplayName("机器人展开键重复时应在配置期中文报错")
    fun rejectExpandedBotKeyCollision() {
        write("settings.gradle.kts", """rootProject.name = "key-collision"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; port = 25565 }
                scenario("x") {
                    backend = "s1"
                    bot("w") { action = "a"; count = 2 }
                    bot("w-1") { action = "b" }
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()
        assertTrue(
            result.output.contains("w-1") && result.output.contains("重复"),
            "role 唯一但展开 key 撞车应配置期中文报错\n${result.output}",
        )
    }

    @Test
    @DisplayName("合法多机器人集群配置应成功列出任务")
    fun configureMultiBotTasksSuccessfully() {
        write("settings.gradle.kts", """rootProject.name = "multibot-cluster"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("s1") { platform = paper; version = "1.20.1"; port = 25565 }
                backend("s2") { platform = paper; version = "1.20.1"; port = 25566 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1", "s2") }
                scenario("g16") {
                    backends("s1", "s2")
                    via = "wf"
                    bot { username = "P"; action = "cross-server"; count = 8 }
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("tasks", "--all", "--stacktrace")
            .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
    }
}
