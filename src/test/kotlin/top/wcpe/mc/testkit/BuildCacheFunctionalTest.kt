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
 * Gradle 构建缓存兼容性集成测试（消费方开启 `--build-cache` 的回归锚）。
 *
 * 本插件注册的 prepare / e2e / serve / stop 等任务全是副作用生命周期任务，无稳定可缓存产物。
 * 注册原语统一 `outputs.upToDateWhen { false }`，保证开启构建缓存时任务仍真实执行，
 * 不得出现 `FROM-CACHE` / `UP_TO_DATE` 假跳过（假绿或残留进程）。
 *
 * 覆盖方式：消费者临时工程启用**本地** build cache（目录落在工程内，不污染本机 `~/.gradle`），
 * 以全注册点拓扑 + 无副作用的 `stopDevServe` 触发完整任务图执行。
 */
class BuildCacheFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    /** 启用工程内本地 Build Cache（不写开发者全局配置）。 */
    private fun writeLocalBuildCacheSettings() {
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "build-cache-consumer"
            buildCache {
                local {
                    directory = File(rootDir, ".gradle/build-cache")
                    isEnabled = true
                }
            }
            """.trimIndent(),
        )
    }

    /** 全注册点拓扑：与配置缓存用例同源，保证副作用任务图完整进断言。 */
    private fun writeFullTopologyConsumerBuild() {
        write(
            "build.gradle.kts",
            """
            plugins {
                java
                id("top.wcpe.mc-testkit")
            }
            mcTestkit {
                backend("s1") { port = 25565 }
                backend("s2") { port = 25566 }
                proxy("wf") { platform = waterfall; port = 25577; routesTo("s1", "s2") }
                scenario("buy") {
                    backend = "s1"
                    bot { username = "Buyer"; action = "buy" }
                }
                scenario("buyVia") {
                    backend = "s1"; via = "wf"
                    bot { username = "Bot2"; action = "buy" }
                }
                scenario("clusterCross") {
                    backends("s1", "s2"); via = "wf"
                    bot { username = "CrossBot"; action = "cross" }
                }
                scenario("loadStress") {
                    backends("s1", "s2"); via = "wf"
                    stress { botsPerServer = 1; durationSeconds = 1 }
                }
                serve("dev") {
                    backend = "s1"; via = "wf"
                    bot { username = "Filler"; action = "idle" }
                }
                serve("clusterDev") { backends("s1", "s2"); via = "wf" }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            // 不设 withTestKitDir：TestKit 守护进程会锁住目录，导致 @TempDir 清理失败；
            // 本地构建缓存已由 settings.gradle.kts 的 buildCache.local 隔离在工程内。
            .withArguments(arguments.toList() + "--build-cache")

    @Test
    @DisplayName("消费方开启构建缓存后 stopDevServe 应真实执行且不被构建缓存跳过")
    fun stopDevServeMustNotBeFromCacheWhenBuildCacheEnabled() {
        writeLocalBuildCacheSettings()
        writeFullTopologyConsumerBuild()

        val firstRun = runner("stopDevServe").build()
        assertTrue(
            firstRun.output.contains("BUILD SUCCESSFUL"),
            "开启 --build-cache 时构建应成功\n---- 输出 ----\n${firstRun.output}",
        )
        val outcome = firstRun.task(":stopDevServe")?.outcome
        assertEquals(
            TaskOutcome.SUCCESS,
            outcome,
            "副作用任务 stopDevServe 须真实执行（不得 FROM-CACHE / UP-TO-DATE / SKIPPED）",
        )

        // 第二次运行：即便本地缓存已有条目，副作用任务仍须执行
        val secondRun = runner("stopDevServe").build()
        val secondOutcome = secondRun.task(":stopDevServe")?.outcome
        assertEquals(
            TaskOutcome.SUCCESS,
            secondOutcome,
            "第二次运行副作用任务仍须真实执行（不得 FROM-CACHE / UP-TO-DATE / SKIPPED）",
        )
    }
}
