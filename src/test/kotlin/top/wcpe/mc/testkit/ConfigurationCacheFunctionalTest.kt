package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gradle 配置缓存兼容性集成测试（配置缓存缺陷 0.8.1 回归锚）。
 *
 * 缺陷背景：任务动作闭包（doLast）捕获了 `Project`，Gradle 9.x 配置缓存**存储阶段**报
 * `cannot serialize object of type ...DefaultProject`，构建退出码非零（任务执行本身正常）。
 *
 * 覆盖方式：配置缓存存储会序列化**全部已注册任务**的动作闭包（不止被请求执行的任务），故在本测试
 * 中一次性声明覆盖全部注册点的拓扑（单后端场景 / 经代理场景 / 集群 / 压测 / 单 serve / 集群 serve /
 * 可选 bot），再以无副作用的 `stopDevServe` 收尾任务配合 `--configuration-cache` 触发完整存储——
 * 若任一注册点的动作闭包捕获图含 `Project`（或其它不可序列化对象），存储即失败、构建报错。
 */
class ConfigurationCacheFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    /** 全注册点拓扑：覆盖 prepare / 经代理 / 集群 / 压测 / 单 serve / 集群 serve / bot / 收尾任务。 */
    private fun writeFullTopologyConsumerBuild() {
        write("settings.gradle.kts", """rootProject.name = "config-cache-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                // 应用 java 插件使本工程存在 jar 任务 → 自测模式（0.9.0/0.9.1）激活：
                // pluginUnderTest 未声明时框架自动取本模块 jar 产物，并把 prepareE2e* / e2e* /
                // serve* 自动依赖到 jar 任务——本测试同时覆盖「自测模式接线」下的配置缓存存储。
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
            .withArguments(arguments.toList() + "--configuration-cache")

    @Test
    @DisplayName("全部注册点的任务动作闭包应兼容 Gradle 配置缓存（存储 + 重用）")
    fun storeAndReuseConfigurationCacheWithFullTopology() {
        writeFullTopologyConsumerBuild()

        // 第一次运行：配置缓存**存储**阶段序列化全部注册任务的动作闭包；捕获 Project 会在此报错
        val firstRun = runner("stopDevServe").build()
        assertTrue(
            firstRun.output.contains("Configuration cache entry stored"),
            "配置缓存应成功存储（动作闭包捕获图不得含 Project）\n---- 输出 ----\n${firstRun.output}",
        )

        // 第二次运行：配置缓存**重用**（反序列化动作闭包并执行），验证恢复后的闭包可正常工作
        val secondRun = runner("stopDevServe").build()
        assertTrue(
            secondRun.output.contains("Reusing configuration cache"),
            "第二次运行应重用配置缓存\n---- 输出 ----\n${secondRun.output}",
        )
    }
}
