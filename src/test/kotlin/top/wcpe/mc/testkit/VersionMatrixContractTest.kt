package top.wcpe.mc.testkit

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.BackendPlatform
import top.wcpe.mc.testkit.dsl.MatrixVersionEntry
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.VersionMatrixExpander
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 版本矩阵（versionMatrix 第 6 顶层块）契约与展开测试。
 */
class VersionMatrixContractTest {

    private fun extension(): McTestkitExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        return project.extensions.getByType(McTestkitExtension::class.java)
    }

    @Test
    @DisplayName("versionMatrix DSL 应记录条目并可推导 key")
    fun recordMatrixEntriesAndDeriveKeys() {
        val ext = extension()
        ext.versionMatrix("nms") {
            platform = paper
            portBase = 26000
            botAction = "taboolib-full"
            entry("1.20.1")
            entry("1.20.6") { bot = true }
            entry("26.2", key = "262", bot = false)
        }
        val matrix = ext.declaredVersionMatrices.single()
        assertEquals("nms", matrix.name)
        assertEquals(BackendPlatform.PAPER, matrix.platform)
        assertEquals(26000, matrix.portBase)
        assertEquals("taboolib-full", matrix.botAction)
        assertEquals(listOf("1201", "1206", "262"), matrix.entries.map { it.key })
        assertEquals(listOf(false, true, false), matrix.entries.map { it.bot })
    }

    @Test
    @DisplayName("deriveKey 应把版本号压成数字短键")
    fun deriveNumericKeysFromVersions() {
        assertEquals("1201", MatrixVersionEntry.deriveKey("1.20.1"))
        assertEquals("2612", MatrixVersionEntry.deriveKey("26.1.2"))
        assertEquals("1122", MatrixVersionEntry.deriveKey("1.12.2"))
    }

    @Test
    @DisplayName("expandAll 应生成 backend 与 full/smoke 场景")
    fun expandMatrixIntoBackendsAndScenarios() {
        val ext = extension()
        ext.versionMatrix("nms") {
            entry("1.20.1")
            entry("1.20.6") { bot = true }
        }
        VersionMatrixExpander.expandAll(ext)

        assertEquals(listOf("v1201", "v1206"), ext.declaredBackends.map { it.name })
        assertEquals(listOf("1.20.1", "1.20.6"), ext.declaredBackends.map { it.version })
        assertEquals(listOf(25600, 25601), ext.declaredBackends.map { it.port })

        val scenarioNames = ext.declaredScenarios.map { it.name }.toSet()
        assertEquals(setOf("smoke-1201", "full-1206"), scenarioNames)
        val full = ext.declaredScenarios.first { it.name == "full-1206" }
        assertEquals("v1206", full.backend)
        assertEquals("Tb1206", full.botSpec?.username)
        assertEquals("matrix-bot", full.botSpec?.action)
    }

    @Test
    @DisplayName("矩阵 key 重复应在展开期中文报错")
    fun failWhenMatrixKeyDuplicated() {
        val ext = extension()
        ext.versionMatrix("nms") {
            entry("1.20.1", key = "dup")
            entry("1.20.6", key = "dup")
        }
        val ex = assertFailsWith<GradleException> { VersionMatrixExpander.expandAll(ext) }
        assertTrue(ex.message.orEmpty().contains("重复"))
    }

    @Test
    @DisplayName("空矩阵应在展开期中文报错")
    fun failWhenMatrixEmpty() {
        val ext = extension()
        ext.versionMatrix("nms")
        val ex = assertFailsWith<GradleException> { VersionMatrixExpander.expandAll(ext) }
        assertTrue(ex.message.orEmpty().contains("未声明任何版本条目"))
    }

    @Test
    @DisplayName("与既有后端撞名应在展开期中文报错")
    fun failWhenBackendNameCollides() {
        val ext = extension()
        ext.backend("v1201")
        ext.versionMatrix("nms") { entry("1.20.1") }
        val ex = assertFailsWith<GradleException> { VersionMatrixExpander.expandAll(ext) }
        assertTrue(ex.message.orEmpty().contains("已存在"))
    }

    @Test
    @DisplayName("聚合任务名应稳定")
    fun stableAggregateTaskNames() {
        assertEquals("e2eMatrixNms", McTestkitTaskNames.versionMatrix("nms"))
        assertEquals("e2eMatrixNmsSmokeOnly", McTestkitTaskNames.versionMatrixSmokeOnly("nms"))
        assertEquals("e2eMatrixMyNms", McTestkitTaskNames.versionMatrix("my-nms"))
    }

    @TempDir
    lateinit var projectDir: File

    private fun write(name: String, text: String) =
        File(projectDir, name).apply { parentFile.mkdirs() }.writeText(text)

    @Test
    @DisplayName("TestKit：versionMatrix 配置期应注册 e2eMatrixNms 聚合任务")
    fun functionalRegisterMatrixAggregateTask() {
        write("settings.gradle.kts", """rootProject.name = "consumer"""")
        write(
            "build.gradle.kts",
            """
            plugins {
                `java-library`
                id("top.wcpe.mc-testkit")
            }
            mcTestkit {
                versionMatrix("nms") {
                    entry("1.20.1")
                    entry("1.20.6") { bot = true }
                }
                dependencies {
                    pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
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
        assertTrue(result.output.contains("e2eMatrixNms"), "output should list e2eMatrixNms")
        assertTrue(result.output.contains("e2eMatrixNmsSmokeOnly"), "output should list smoke aggregate")
        assertTrue(result.output.contains("e2eSmoke1201"), "output should list smoke scenario task")
        assertTrue(result.output.contains("e2eFull1206WithBot"), "output should list full with-bot task")
    }
}
