package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 待测插件**产物来源**（`dependencies { pluginUnderTest(from = …) }`，ADR-0025）的 TestKit 集成测试。
 *
 * 以真实消费者视角驱动插件，覆盖 ADR-0025 的三条对外行为：
 * 1. 产物来源被框架**自动接线**：产物任务进入 prepare / e2e / serve 的任务图，且声明来源后**不**进入
 *    自测模式——工程即使没有 `jar` 任务也不输出「没有 jar 任务」告警；
 * 2. 与字符串形式 `pluginUnderTest = "…"` **互斥**，配置期以中文错误失败；
 * 3. 产物来源解析出的**绝对路径**确实是待测插件路径（只读访问器 `declaredDependencies.pluginUnderTest`），
 *    且工程存在 `jar` 任务时也不会被自测模式顶替。
 *
 * 只验证**配置期与任务图**契约：用 `--dry-run` / `help` 触发配置，不真跑 prepare / e2e / serve
 * （不下载、不起服、不连服）——真实注入与起服判定属「首个消费者验证」实机维度。
 */
class PluginUnderTestArtifactSourceFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** 产物来源任务的落点文件名：fixture 里显式钉死，测试侧才能断言到确定的绝对路径。 */
    private val shadowJarFileName = "consumer-under-test-shadow.jar"

    /** 应用 java 插件时本模块 `jar` 任务的落点文件名（自测模式会回退到它）。 */
    private val selfModuleJarFileName = "consumer-self-module.jar"

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    /** 产物来源任务的绝对落点（与 fixture 的 `destinationDirectory` + `archiveFileName` 一致）。 */
    private fun shadowJarArtifact(): File = File(projectDir, "build/libs/$shadowJarFileName")

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments.toList() + "--stacktrace")

    /**
     * 取构建输出里由消费者脚本打印的观测值（形如 `MC_TESTKIT_UNDER_TEST_PATH=<值>`）。
     *
     * 取法说明：TestKit 在**构建外部**，拿不到 Gradle 扩展对象，故让 fixture 在
     * `gradle.projectsEvaluated { }` 里打印待断言的值，再从 `BuildResult.output`（含 stdout）里取。
     * 用 `substringAfter` 而非整行匹配，可容忍控制台给该行加的任何前缀。
     */
    private fun observedValue(output: String, key: String): String? =
        output.lineSequence().firstOrNull { key in it }?.substringAfter(key)?.trim()

    /**
     * 写入「**没有** java 插件（故本工程没有 jar 任务）+ 自定义产 jar 任务 shadowJar」的消费者工程。
     *
     * 不应用 java 插件是刻意的：只有工程确实没有 `jar` 任务时，自测模式的「没有 jar 任务 → 跳过自测注入」
     * 告警才会出现，于是「不出现告警」这条断言才真正证明产物来源**没有**把流程带进自测回退（ADR-0025 §五）。
     * 未应用 java 插件，故产物任务用 `Jar` 任务类型手工构造（`destinationDirectory` + `archiveFileName`
     * 显式钉死，保证解析出恰好一个产物且路径可断言）。
     */
    private fun writeArtifactSourceConsumerWithoutJarTask() {
        write("settings.gradle.kts", """rootProject.name = "artifact-source-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                id("top.wcpe.mc-testkit")
            }

            // 待测产物的来源任务：手工构造一个产 jar 的任务。
            // 本工程未应用 java 插件，故不存在自测模式回退所用的 jar 任务（这正是本用例要断言的场景）。
            tasks.register<Jar>("shadowJar") {
                archiveFileName.set("$shadowJarFileName")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
            }

            mcTestkit {
                backend("s1") {
                    platform = paper
                    version = "1.20.1"
                    port = 25565
                }
                scenario("smoke")
                serve("dev") {
                    backend = "s1"
                }
                dependencies {
                    // ADR-0025 推荐形态：传携带任务信息的 TaskProvider，框架据此自动接线
                    pluginUnderTest(from = tasks.named("shadowJar"))
                }
            }

            // 配置末段打印前提（本工程是否真有 jar 任务），供测试侧校验断言前提成立
            gradle.projectsEvaluated {
                println("MC_TESTKIT_HAS_JAR_TASK=" + (tasks.findByName("jar") != null))
            }
            """.trimIndent(),
        )
    }

    @Test
    @DisplayName("声明产物来源后 prepare / e2e / serve 任务图应含产物任务且不输出「没有 jar 任务」告警")
    fun wireArtifactSourceTaskAndSkipSelfJarFallback() {
        writeArtifactSourceConsumerWithoutJarTask()

        // dry-run：只构任务图、按依赖顺序打印将执行的任务，不执行任务体（不下载、不起服）
        val output = runner("e2eSmoke", "serveDev", "--dry-run").build().output

        // 前提校验：本工程确实没有 jar 任务——否则「无告警」断言同义反复（自测回退本就不告警）
        assertTrue(
            "MC_TESTKIT_HAS_JAR_TASK=false" in output,
            "fixture 前提不成立：该工程应无 jar 任务\n---- 输出 ----\n$output",
        )

        val artifactIdx = output.indexOf(":shadowJar ")
        val prepareIdx = output.indexOf(":prepareE2eSmoke ")
        val verifyIdx = output.indexOf(":e2eSmoke ")
        val serveIdx = output.indexOf(":serveDev ")

        assertTrue(
            artifactIdx >= 0,
            "产物任务 :shadowJar 应进入任务图（声明来源即自动接线）\n---- 输出 ----\n$output",
        )
        assertTrue(prepareIdx >= 0, "dry-run 应列出 :prepareE2eSmoke\n---- 输出 ----\n$output")
        assertTrue(verifyIdx >= 0, "dry-run 应列出 :e2eSmoke\n---- 输出 ----\n$output")
        assertTrue(serveIdx >= 0, "dry-run 应列出 :serveDev\n---- 输出 ----\n$output")
        assertTrue(
            artifactIdx < prepareIdx,
            "产物任务应先于 prepareE2eSmoke 产出\n---- 输出 ----\n$output",
        )
        assertTrue(
            artifactIdx < verifyIdx,
            "产物任务应先于 e2eSmoke 产出\n---- 输出 ----\n$output",
        )
        assertTrue(
            artifactIdx < serveIdx,
            "产物任务应先于 serveDev 产出\n---- 输出 ----\n$output",
        )

        // 约定：有产物来源时不进入自测模式——既不打「没有 jar 任务 → 跳过自测注入」告警，
        // 也不把任务接到 jar 上（任务图里不该出现 :jar）
        assertFalse(
            "没有 jar 任务" in output,
            "声明产物来源后不应输出自测模式告警\n---- 输出 ----\n$output",
        )
        assertFalse(
            "跳过自测注入" in output,
            "声明产物来源后不应进入自测回退\n---- 输出 ----\n$output",
        )
        assertFalse(
            ":jar " in output,
            "声明产物来源后任务图不应含 :jar\n---- 输出 ----\n$output",
        )
    }

    /**
     * 写入**同时**声明字符串形式与产物来源形式的消费者工程（互斥场景）。
     *
     * 除「两种形式各写一次」外拓扑完全合法，故构建一旦失败，失败原因只能是互斥声明本身
     * （`applyPluginUnderTestDeclaration` 在 afterEvaluate 里先于拓扑校验执行）。
     */
    private fun writeMutuallyExclusiveConsumer() {
        write("settings.gradle.kts", """rootProject.name = "artifact-source-conflict-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                id("top.wcpe.mc-testkit")
            }

            tasks.register<Jar>("shadowJar") {
                archiveFileName.set("$shadowJarFileName")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
            }

            mcTestkit {
                backend("s1") {
                    platform = paper
                    version = "1.20.1"
                    port = 25565
                }
                scenario("smoke")
                dependencies {
                    pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
                    pluginUnderTest(from = tasks.named("shadowJar"))
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    @DisplayName("同时声明字符串形式与产物来源时应于配置期以中文互斥错误失败")
    fun rejectDeclaringBothStringAndArtifactSource() {
        writeMutuallyExclusiveConsumer()

        // 配置期失败：`tasks` 无任务体副作用，仍失败即证明报错发生在声明解析（afterEvaluate）而非任务执行
        val result = runner("tasks").buildAndFail()

        assertTrue(
            "二者互斥" in result.output,
            "应报出「二者互斥」的中文错误\n---- 输出 ----\n${result.output}",
        )
        assertTrue(
            "请只保留一种声明" in result.output,
            "互斥报错应给出「请只保留一种声明」的处置指引\n---- 输出 ----\n${result.output}",
        )
    }

    /**
     * 写入「应用 java 插件（本工程有 jar 任务）+ 自定义产 jar 任务 shadowJar」的消费者工程。
     *
     * 应用 java 插件对应 ADR-0025 的真实动因：消费方此前为让自测模式不选中 `jar`，只好写
     * `jar { enabled = false }`。本用例断言声明产物来源后取到的是 shadowJar 产物、**不是**本模块 jar 产物，
     * 且未进入自测模式——即那处补丁可以被删掉。
     *
     * 观测值取法：TestKit 在构建外部拿不到扩展对象，故由消费者脚本在 `gradle.projectsEvaluated { }`
     * （所有 afterEvaluate 之后，插件已把解析结果写回 `pluginUnderTest`）里打印
     * `declaredDependencies.pluginUnderTest` 与 `selfJar`，测试侧再从构建输出里取。
     */
    private fun writeJavaConsumerWithArtifactSource() {
        write("settings.gradle.kts", """rootProject.name = "artifact-source-java-consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                java
                id("top.wcpe.mc-testkit")
            }

            // 本模块自身 jar 产物（自测模式的回退目标）：显式钉名，便于断言「没有被选中」
            tasks.named<Jar>("jar") {
                archiveFileName.set("$selfModuleJarFileName")
            }

            val shadowJar = tasks.register<Jar>("shadowJar") {
                archiveFileName.set("$shadowJarFileName")
                destinationDirectory.set(layout.buildDirectory.dir("libs"))
            }

            mcTestkit {
                backend("s1") {
                    platform = paper
                    version = "1.20.1"
                    port = 25565
                }
                scenario("smoke")
                dependencies {
                    pluginUnderTest(from = shadowJar)
                }
            }

            gradle.projectsEvaluated {
                val deps = mcTestkit.declaredDependencies
                println("MC_TESTKIT_UNDER_TEST_PATH=" + deps.pluginUnderTest)
                println("MC_TESTKIT_UNDER_TEST_SELF_JAR=" + deps.selfJar)
            }
            """.trimIndent(),
        )
    }

    @Test
    @DisplayName("声明产物来源后待测插件路径应为产物绝对路径且不取本模块 jar 产物")
    fun resolveArtifactSourceAsPluginUnderTestPath() {
        writeJavaConsumerWithArtifactSource()

        val result = runner("help").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome, "配置期应无报错且 help 成功")

        val resolvedPath = assertNotNull(
            observedValue(result.output, "MC_TESTKIT_UNDER_TEST_PATH="),
            "应能从消费者工程读到已解析的待测插件路径\n---- 输出 ----\n${result.output}",
        )
        val selfJarMarker = assertNotNull(
            observedValue(result.output, "MC_TESTKIT_UNDER_TEST_SELF_JAR="),
            "应能从消费者工程读到自测模式标记\n---- 输出 ----\n${result.output}",
        )

        assertEquals(
            shadowJarArtifact().absolutePath,
            resolvedPath,
            "待测插件路径应为产物来源解析出的绝对路径",
        )
        assertTrue(
            resolvedPath != File(projectDir, "build/libs/$selfModuleJarFileName").absolutePath,
            "声明产物来源后不应回退到本模块 jar 产物：$resolvedPath",
        )
        assertEquals("false", selfJarMarker, "声明产物来源后不应标记为自测模式")
    }
}
