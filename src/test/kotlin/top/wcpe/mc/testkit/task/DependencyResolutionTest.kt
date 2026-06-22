package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.dsl.DependenciesSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [resolveDependencyJars] 纯函数单测（FR-04）：声明值「环境变量名或路径」解析、缺失中文报错。
 *
 * 用临时文件 + 替身取值器穷举，零 Gradle Project / 零网络。
 */
class DependencyResolutionTest {

    @TempDir
    lateinit var tmp: File

    private fun jar(name: String): File =
        File(tmp, name).apply { writeText("fake-jar") }

    @Test
    fun `按环境变量名解析到 jar 路径`() {
        val underTest = jar("plugin.jar")
        val deps = DependenciesSpec().apply {
            pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        }
        val env = mapOf("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" to underTest.absolutePath)

        val resolved = resolveDependencyJars(deps) { env[it] }

        assertEquals(1, resolved.size)
        assertEquals(underTest.absolutePath, resolved.single().jar.absolutePath)
        assertTrue(resolved.single().underTest, "pluginUnderTest 应标记为 underTest")
    }

    @Test
    fun `声明值本身是路径时直接采用`() {
        val sampleLib = jar("SampleLib.jar")
        val deps = DependenciesSpec().apply {
            plugin(sampleLib.absolutePath)
        }

        // 取值器对该路径返回 null（不是环境变量名），应回退到把声明值当路径
        val resolved = resolveDependencyJars(deps) { null }

        assertEquals(1, resolved.size)
        assertEquals(sampleLib.absolutePath, resolved.single().jar.absolutePath)
        assertEquals(false, resolved.single().underTest)
    }

    @Test
    fun `被测插件在前 依赖按声明顺序在后`() {
        val underTest = jar("under-test.jar")
        val depA = jar("A.jar")
        val depB = jar("B.jar")
        val deps = DependenciesSpec().apply {
            pluginUnderTest = underTest.absolutePath
            plugin(depA.absolutePath)
            plugin(depB.absolutePath)
        }

        val resolved = resolveDependencyJars(deps) { null }

        assertEquals(
            listOf(underTest.absolutePath, depA.absolutePath, depB.absolutePath),
            resolved.map { it.jar.absolutePath },
        )
        assertTrue(resolved.first().underTest)
    }

    @Test
    fun `缺失 jar 抛中文错误 含缺项名与环境变量提示`() {
        val deps = DependenciesSpec().apply {
            pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        }
        // 取值器对该环境变量名返回 null（未提供），路径也指向不存在文件
        val ex = assertFailsWith<GradleException> {
            resolveDependencyJars(deps) { null }
        }
        assertTrue("缺少必需的依赖注入" in ex.message!!, ex.message)
        assertTrue("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" in ex.message!!, ex.message)
        // 像环境变量名的声明应给出「可经环境变量提供」的提示
        assertTrue("可经环境变量" in ex.message!!, ex.message)
    }

    @Test
    fun `环境变量指向不存在文件时也算缺失`() {
        val deps = DependenciesSpec().apply {
            pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        }
        val env = mapOf("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" to File(tmp, "nope.jar").absolutePath)

        assertFailsWith<GradleException> {
            resolveDependencyJars(deps) { env[it] }
        }
    }

    @Test
    fun `无任何声明时返回空 不报错`() {
        val resolved = resolveDependencyJars(DependenciesSpec()) { null }
        assertTrue(resolved.isEmpty())
    }
}
