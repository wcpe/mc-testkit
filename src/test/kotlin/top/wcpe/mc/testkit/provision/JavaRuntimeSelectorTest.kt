package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [JavaRuntimeSelector] 环境变量解析 + 回退单元测试（FR-21）。
 *
 * 验证版本段变量覆盖 > `JAVA_HOME` 回退 > 当前 JVM 回退的优先级链。
 * 用替身取值器（不读 `System.getenv`），零环境依赖、确定性强。
 */
class JavaRuntimeSelectorTest {

    @Test
    @DisplayName("版本段变量存在时应返回该 Java home")
    fun versionedEnvTakesPrecedence() {
        val fakeHome = Files.createTempDirectory("mc-testkit-jdk17").toString()
        val readEnv: (String) -> String? = { name ->
            when (name) {
                JavaRuntimeSelector.ENV_PREFIX + "1_17" -> fakeHome
                JavaRuntimeSelector.JAVA_HOME_ENV -> "/some/other/java"
                else -> null
            }
        }
        assertEquals(fakeHome, JavaRuntimeSelector.javaHome("1.17.1", readEnv))
    }

    @Test
    @DisplayName("1.18 后端可使用 Java 17 专属环境变量")
    fun minecraft118UsesJava17Environment() {
        val fakeHome = Files.createTempDirectory("mc-testkit-jdk17").toString()

        val resolved = JavaRuntimeSelector.javaHome("1.18.1") { name ->
            if (name == JavaRuntimeSelector.ENV_PREFIX + "17") fakeHome else null
        }

        assertEquals(fakeHome, resolved)
    }

    @Test
    @DisplayName("版本段变量为空时应回退到 JAVA_HOME")
    fun blankVersionedEnvFallsBackToJavaHome() {
        val readEnv: (String) -> String? = { name ->
            when (name) {
                JavaRuntimeSelector.ENV_PREFIX + "1_7" -> "  "
                JavaRuntimeSelector.JAVA_HOME_ENV -> "/opt/java8"
                else -> null
            }
        }
        assertEquals("/opt/java8", JavaRuntimeSelector.javaHome("1.7.10", readEnv))
    }

    @Test
    @DisplayName("版本段变量与 JAVA_HOME 均未设时应返回 null（由调用方回退当前 JVM）")
    fun noEnvReturnsNull() {
        val readEnv: (String) -> String? = { null }
        assertEquals(null, JavaRuntimeSelector.javaHome("1.20.1", readEnv))
    }

    @Test
    @DisplayName("executable 应在版本段变量指向的 Java home 下找到 bin/java 可执行文件")
    fun executableResolvesFromVersionedEnv() {
        // 构造一个含 bin/java（或 java.exe）的假 JDK 目录
        val fakeHome = Files.createTempDirectory("mc-testkit-fakejdk").toFile()
        val binDir = File(fakeHome, "bin").apply { mkdirs() }
        val exeName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        File(binDir, exeName).writeText("#!/bin/sh\nexit 0\n")

        val readEnv: (String) -> String? = { name ->
            when (name) {
                JavaRuntimeSelector.ENV_PREFIX + "1_8" -> fakeHome.absolutePath
                else -> null
            }
        }
        val exe = JavaRuntimeSelector.executable("1.8.8", readEnv)
        assertTrue(exe.endsWith(exeName), "应解析到 $exeName，实际：$exe")
        assertTrue(File(exe).isFile, "解析的 java 可执行文件应存在：$exe")
    }

    @Test
    @DisplayName("executable 在无任何 env 时应回退到当前 JVM 的 java 可执行文件")
    fun executableFallsBackToCurrentJvm() {
        val readEnv: (String) -> String? = { null }
        val exe = JavaRuntimeSelector.executable("1.21.1", readEnv)
        assertNotNull(exe)
        // 当前 JVM 的 java 可执行名：java 或 java.exe
        assertTrue(exe.endsWith("java") || exe.endsWith("java.exe"), "应回退到 java 可执行：$exe")
    }

    @Test
    @DisplayName("8 个代表版本的版本段变量名应正确拼接")
    fun versionSegmentEnvNamesForAllRepresentativeVersions() {
        val cases = mapOf(
            "1.7.10" to "MC_TESTKIT_JAVA_HOME_1_7",
            "1.8.8" to "MC_TESTKIT_JAVA_HOME_1_8",
            "1.12.2" to "MC_TESTKIT_JAVA_HOME_1_12",
            "1.16.5" to "MC_TESTKIT_JAVA_HOME_1_16",
            "1.17.1" to "MC_TESTKIT_JAVA_HOME_1_17",
            "1.19.4" to "MC_TESTKIT_JAVA_HOME_1_19",
            "1.20.1" to "MC_TESTKIT_JAVA_HOME_1_20",
            "1.21.1" to "MC_TESTKIT_JAVA_HOME_1_21",
        )
        cases.forEach { (version, expectedEnv) ->
            val captured = mutableListOf<String>()
            val readEnv: (String) -> String? = { name ->
                captured.add(name)
                null
            }
            JavaRuntimeSelector.javaHome(version, readEnv)
            assertTrue(expectedEnv in captured, "$version 应查询 $expectedEnv，实际查询了：$captured")
        }
    }

    @Test
    @DisplayName("显式 Java 主版本应只接受对应环境变量中的可执行文件")
    fun requiredExecutableForMajorUsesDedicatedEnvironmentVariable() {
        val fakeHome = Files.createTempDirectory("mc-testkit-jdk25").toFile()
        val executable = File(fakeHome, "bin").apply { mkdirs() }.resolve(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
        ).apply { writeText("java-sentinel") }

        val resolved = JavaRuntimeSelector.requiredExecutableForMajor(25) { name ->
            if (name == "MC_TESTKIT_JAVA_HOME_25") fakeHome.absolutePath else null
        }

        assertEquals(executable.absolutePath, resolved)
    }

    @Test
    @DisplayName("显式 Java 主版本缺少专属环境变量时应立即中文失败")
    fun requiredExecutableForMajorRejectsMissingDedicatedEnvironmentVariable() {
        val exception = assertFailsWith<IllegalStateException> {
            JavaRuntimeSelector.requiredExecutableForMajor(25) { null }
        }

        assertTrue(exception.message!!.contains("MC_TESTKIT_JAVA_HOME_25"))
        assertTrue(exception.message!!.contains("Java 25"))
    }
}
