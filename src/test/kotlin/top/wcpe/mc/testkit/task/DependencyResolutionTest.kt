package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [resolveDependencyJars] 纯函数单测（任务自动编排）：声明值「环境变量名或路径」解析、缺失中文报错。
 *
 * 用临时文件 + 替身取值器穷举，零 Gradle Project / 零网络。
 */
class DependencyResolutionTest {

    @TempDir
    lateinit var tmp: File

    private fun jar(name: String): File =
        File(tmp, name).apply { writeText("fake-jar") }

    private fun declarations(
        pluginUnderTest: String? = null,
        vararg plugins: String,
    ): DependencyDeclarations = DependencyDeclarations(pluginUnderTest, plugins.toList())

    @Test
    @DisplayName("依赖声明为环境变量名时应解析到对应 jar 路径")
    fun resolveDependencyFromEnvironmentVariable() {
        val underTest = jar("plugin.jar")
        val deps = declarations(pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR")
        val env = mapOf("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" to underTest.absolutePath)

        val resolved = resolveDependencyJars(deps) { env[it] }

        assertEquals(1, resolved.size)
        assertEquals(underTest.absolutePath, resolved.single().jar.absolutePath)
        assertTrue(resolved.single().underTest, "pluginUnderTest 应标记为 underTest")
    }

    @Test
    @DisplayName("依赖声明本身为路径时应直接采用该 jar")
    fun resolveDependencyFromLiteralPath() {
        val sampleLib = jar("SampleLib.jar")
        val deps = declarations(plugins = arrayOf(sampleLib.absolutePath))

        // 取值器对该路径返回 null（不是环境变量名），应回退到把声明值当路径
        val resolved = resolveDependencyJars(deps) { null }

        assertEquals(1, resolved.size)
        assertEquals(sampleLib.absolutePath, resolved.single().jar.absolutePath)
        assertEquals(false, resolved.single().underTest)
    }

    @Test
    @DisplayName("解析多个依赖时应将被测插件置前并保持其余声明顺序")
    fun resolveDependenciesKeepsUnderTestFirstAndDeclarationOrder() {
        val underTest = jar("under-test.jar")
        val depA = jar("A.jar")
        val depB = jar("B.jar")
        val deps = declarations(
            pluginUnderTest = underTest.absolutePath,
            depA.absolutePath,
            depB.absolutePath,
        )

        val resolved = resolveDependencyJars(deps) { null }

        assertEquals(
            listOf(underTest.absolutePath, depA.absolutePath, depB.absolutePath),
            resolved.map { it.jar.absolutePath },
        )
        assertTrue(resolved.first().underTest)
    }

    @Test
    @DisplayName("缺少依赖 jar 时应抛出包含缺项名和环境变量提示的中文错误")
    fun resolveMissingDependencyThrowsChineseGuidance() {
        val deps = declarations(pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR")
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
    @DisplayName("环境变量指向不存在文件时应将依赖判定为缺失")
    fun resolveMissingEnvironmentTargetAsAbsent() {
        val deps = declarations(pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR")
        val env = mapOf("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" to File(tmp, "nope.jar").absolutePath)

        assertFailsWith<GradleException> {
            resolveDependencyJars(deps) { env[it] }
        }
    }

    @Test
    @DisplayName("没有任何依赖声明时应返回空列表且不报错")
    fun resolveNoDependencyDeclarationsReturnsEmptyList() {
        val resolved = resolveDependencyJars(declarations()) { null }
        assertTrue(resolved.isEmpty())
    }

    // ── 自测模式（pluginUnderTestSelfJar）──

    private fun selfDeclarations(jarPath: String): DependencyDeclarations =
        DependencyDeclarations(pluginUnderTest = jarPath, plugins = emptyList(), pluginUnderTestSelfJar = true)

    @Test
    @DisplayName("自测模式应默认采用声明值（本模块 jar 产物路径）")
    fun resolveSelfJarDefaultsToDeclaredJarPath() {
        val underTest = jar("self-module-1.0.0.jar")
        // 无任何环境变量 → 回退声明值本身（jar 产物路径）
        val resolved = resolveDependencyJars(selfDeclarations(underTest.absolutePath)) { null }

        assertEquals(1, resolved.size)
        assertEquals(underTest.absolutePath, resolved.single().jar.absolutePath)
        assertTrue(resolved.single().underTest)
    }

    @Test
    @DisplayName("自测模式下覆盖环境变量应优先于 jar 产物路径")
    fun resolveSelfJarPrefersOverrideEnvironmentVariable() {
        val builtJar = jar("self-module-1.0.0.jar")
        val injectedJar = jar("ci-injected.jar")
        val deps = selfDeclarations(builtJar.absolutePath)
        val env = mapOf("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR" to injectedJar.absolutePath)

        val resolved = resolveDependencyJars(deps) { env[it] }

        assertEquals(1, resolved.size)
        assertEquals(injectedJar.absolutePath, resolved.single().jar.absolutePath)
    }

    @Test
    @DisplayName("自测模式缺 jar 时应报指路明确的中文错误而非通用缺失错误")
    fun resolveSelfJarMissingGivesGuidedError() {
        val deps = selfDeclarations(File(tmp, "not-built-yet.jar").absolutePath)

        val ex = assertFailsWith<GradleException> {
            resolveDependencyJars(deps) { null }
        }
        assertTrue("自测模式" in ex.message!!, ex.message)
        assertTrue("gradlew jar" in ex.message!!, ex.message)
        assertTrue("pluginUnderTest" in ex.message!!, ex.message)
    }

    // ── Maven 坐标（mavenPlugin）──

    private fun coordinateDeclarations(
        pluginUnderTest: String? = null,
        vararg plugins: String,
        mavenPlugins: List<String> = emptyList(),
    ): DependencyDeclarations =
        DependencyDeclarations(pluginUnderTest, plugins.toList(), mavenPlugins = mavenPlugins)

    @Test
    @DisplayName("坐标声明解析成功时应按制品名注入且标记为非被测插件")
    fun resolveCoordinateDeclarationInjectsResolvedJar() {
        val resolvedJar = jar("foo-plugin-1.2.0.jar")
        val deps = coordinateDeclarations(mavenPlugins = listOf("com.example:foo-plugin:1.2.0"))

        val resolved = resolveDependencyJars(deps, mapOf("com.example:foo-plugin:1.2.0" to resolvedJar)) { null }

        assertEquals(1, resolved.size)
        assertEquals("com.example:foo-plugin:1.2.0", resolved.single().declaration)
        assertEquals(resolvedJar.absolutePath, resolved.single().jar.absolutePath)
        assertEquals(false, resolved.single().underTest, "坐标来源的插件不应标记为被测插件")
    }

    @Test
    @DisplayName("坐标与路径声明混用时应保持被测插件置前、路径随后、坐标最后")
    fun resolveCoordinatesCoexistWithPathDeclarationsInOrder() {
        val underTest = jar("under-test.jar")
        val libA = jar("libA.jar")
        val libB = jar("libB.jar")
        val coordinateA = jar("coordA-1.0.0.jar")
        val coordinateB = jar("coordB-1.0.0.jar")
        val coordinateAKey = "com.example:coordA:1.0.0"
        val coordinateBKey = "com.example:coordB:1.0.0"
        val deps = coordinateDeclarations(
            underTest.absolutePath,
            libA.absolutePath,
            libB.absolutePath,
            mavenPlugins = listOf(coordinateAKey, coordinateBKey),
        )

        val resolved = resolveDependencyJars(
            deps,
            mapOf(coordinateAKey to coordinateA, coordinateBKey to coordinateB),
        ) { null }

        assertEquals(
            listOf(
                underTest.absolutePath,
                libA.absolutePath,
                libB.absolutePath,
                coordinateA.absolutePath,
                coordinateB.absolutePath,
            ),
            resolved.map { it.jar.absolutePath },
            "顺序应为：被测插件 → 路径 / 环境变量声明 → Maven 坐标声明",
        )
        assertTrue(resolved.first().underTest)
        assertEquals(listOf(false, false), resolved.takeLast(2).map { it.underTest })
    }

    @Test
    @DisplayName("坐标未进入解析结果时应报中文缺失错误并给出可能原因")
    fun resolveMissingCoordinateReportsChineseGuidance() {
        val deps = coordinateDeclarations(mavenPlugins = listOf("com.example:ghost:1.0.0"))

        val ex = assertFailsWith<GradleException> {
            resolveDependencyJars(deps, emptyMap()) { null }
        }
        assertTrue("缺少必需的依赖注入" in ex.message!!, ex.message)
        assertTrue("com.example:ghost:1.0.0" in ex.message!!, ex.message)
        assertTrue("坐标拼写错误" in ex.message!!, "报错应给出可能原因：${ex.message}")
        assertTrue("RepositoriesMode.PREFER_SETTINGS" in ex.message!!, "报错应点明仓库来源约定：${ex.message}")
        assertTrue("凭据缺失" in ex.message!!, ex.message)
    }

    @Test
    @DisplayName("坐标解析出的文件不存在时应同样判为缺失")
    fun resolveCoordinatePointingToMissingFileIsAbsent() {
        val deps = coordinateDeclarations(mavenPlugins = listOf("com.example:ghost:1.0.0"))

        assertFailsWith<GradleException> {
            resolveDependencyJars(
                deps,
                mapOf("com.example:ghost:1.0.0" to File(tmp, "deleted.jar")),
            ) { null }
        }
    }

    @Test
    @DisplayName("全部坐标解析成功时应返回坐标到 jar 的映射")
    fun resolveMavenCoordinatesReturnsCoordinateToJarMap() {
        val first = jar("first-1.0.0.jar")
        val second = jar("second-1.0.0.jar")

        val resolved = resolveMavenCoordinates(
            listOf("com.example:first:1.0.0", "com.example:second:1.0.0"),
        ) { coordinate -> if (coordinate.contains("first")) first else second }

        assertEquals(
            linkedMapOf("com.example:first:1.0.0" to first, "com.example:second:1.0.0" to second),
            resolved,
        )
    }

    @Test
    @DisplayName("无坐标声明时应返回空映射且不调用取值器")
    fun resolveMavenCoordinatesWithoutDeclarationsReturnsEmpty() {
        val resolved = resolveMavenCoordinates(emptyList()) { error("不应被调用") }
        assertTrue(resolved.isEmpty())
    }

    @Test
    @DisplayName("坐标解析失败时应抛含坐标、Gradle 原因与仓库来源约定的中文错误")
    fun resolveMavenCoordinatesFailureReportsCoordinateAndReason() {
        val succeeded = jar("ok-1.0.0.jar")

        val ex = assertFailsWith<GradleException> {
            resolveMavenCoordinates(
                listOf("com.example:ghost:1.0.0", "com.example:ok:1.0.0"),
            ) { coordinate ->
                if (coordinate == "com.example:ok:1.0.0") {
                    succeeded
                } else {
                    throw IllegalStateException("Could not find com.example:ghost:1.0.0.")
                }
            }
        }
        assertTrue("com.example:ghost:1.0.0" in ex.message!!, ex.message)
        assertTrue("Could not find" in ex.message!!, "报错应带上 Gradle 给出的原因：${ex.message}")
        assertTrue("RepositoriesMode.PREFER_SETTINGS" in ex.message!!, ex.message)
        assertTrue("凭据" in ex.message!!, ex.message)
        assertFalse("com.example:ok:1.0.0" in ex.message!!, "已解析成功的坐标不应出现在失败报错里：${ex.message}")
    }

    @Test
    @DisplayName("多行 Gradle 报错应取点明制品的那行而非泛泛的 resolve 首行")
    fun failureReasonPrefersArtifactSpecificLineOverGenericFirstLine() {
        // 与真实 Gradle 消息同形：首行泛泛、第二行点明制品、其后是搜索位置噪音
        val gradleLikeMessage = listOf(
            "Could not resolve all files for configuration ':detachedConfiguration1'.",
            "> Could not find io.papermc.paper:paper:99.99.99.",
            "  Searched in the following locations:",
            "    - https://repo.example/io/papermc/paper/paper/99.99.99/paper-99.99.99.pom",
            "  Required by:",
            "      project :",
        ).joinToString("\n")

        val ex = assertFailsWith<GradleException> {
            resolveMavenCoordinates(listOf("io.papermc.paper:paper:99.99.99")) {
                throw IllegalStateException(gradleLikeMessage)
            }
        }

        assertTrue("Could not find io.papermc.paper:paper:99.99.99." in ex.message!!, ex.message)
        assertFalse(
            "Could not resolve all files" in ex.message!!,
            "报错原因不应是泛泛的 resolve 首行，而应点明找不到哪个制品：${ex.message}",
        )
        assertFalse(
            "Searched in the following locations" in ex.message!!,
            "搜索位置清单属噪音，不应进入报错：${ex.message}",
        )
    }

    @Test
    @DisplayName("真实 Gradle 的嵌套异常应穿透 cause 链取到点明制品的那行")
    fun failureReasonTraversesCauseChain() {
        // 真实形态：顶层只有泛泛信息，制品级原因挂在 cause 上
        val top = IllegalStateException("Could not resolve all files for configuration ':detachedConfiguration1'.")
        top.initCause(IllegalStateException("Could not find io.papermc.paper:paper:99.99.99."))

        val ex = assertFailsWith<GradleException> {
            resolveMavenCoordinates(listOf("io.papermc.paper:paper:99.99.99")) { throw top }
        }

        assertTrue(
            "Could not find io.papermc.paper:paper:99.99.99." in ex.message!!,
            "原因应穿透 cause 链，指向具体制品：${ex.message}",
        )
        assertFalse("Could not resolve all files" in ex.message!!, ex.message)
    }

    // ── 目标文件名冲突预检（API.md §1）──

    @Test
    @DisplayName("两份声明解析为同一目标文件名时应以中文错误拒绝并列出冲突声明")
    fun rejectDeclarationsResolvingToSameTargetFileName() {
        val first = File(tmp, "first").apply { mkdirs() }.let { File(it, "same-plugin.jar").apply { writeText("a") } }
        val second = File(tmp, "second").apply { mkdirs() }.let { File(it, "same-plugin.jar").apply { writeText("b") } }
        val deps = declarations(plugins = arrayOf(first.absolutePath, second.absolutePath))

        val ex = assertFailsWith<GradleException> { resolveDependencyJars(deps) { null } }

        assertTrue("目标文件名冲突" in ex.message!!, ex.message)
        assertTrue("same-plugin.jar" in ex.message!!, ex.message)
        // 报错须点出是哪两份声明撞的，否则消费方无从定位
        assertTrue(first.absolutePath in ex.message!!, ex.message)
        assertTrue(second.absolutePath in ex.message!!, ex.message)
    }

    @Test
    @DisplayName("路径声明与坐标声明解析为同一目标文件名时应同样被拒")
    fun rejectPathAndCoordinateCollidingOnTargetFileName() {
        val byPath = jar("foo-plugin-1.2.0.jar")
        val byCoordinate = File(tmp, "repo").apply { mkdirs() }
            .let { File(it, "foo-plugin-1.2.0.jar").apply { writeText("from-repo") } }
        val coordinate = "com.example:foo-plugin:1.2.0"
        val deps = coordinateDeclarations(plugins = arrayOf(byPath.absolutePath), mavenPlugins = listOf(coordinate))

        val ex = assertFailsWith<GradleException> {
            resolveDependencyJars(deps, mapOf(coordinate to byCoordinate)) { null }
        }

        assertTrue("目标文件名冲突" in ex.message!!, ex.message)
        assertTrue("foo-plugin-1.2.0.jar" in ex.message!!, ex.message)
    }

    @Test
    @DisplayName("被测插件改名后与依赖插件同名为 plugin-under-test.jar 时应被拒")
    fun rejectDependencyCollidingWithUnderTestTargetFileName() {
        val underTest = jar("under-test.jar")
        val colliding = jar(UNDER_TEST_TARGET_FILE_NAME)
        val deps = declarations(pluginUnderTest = underTest.absolutePath, plugins = arrayOf(colliding.absolutePath))

        val ex = assertFailsWith<GradleException> { resolveDependencyJars(deps) { null } }

        assertTrue("目标文件名冲突" in ex.message!!, ex.message)
        assertTrue(UNDER_TEST_TARGET_FILE_NAME in ex.message!!, ex.message)
    }

    @Test
    @DisplayName("目标文件名仅大小写不同时应判为冲突（与注入侧同口径）")
    fun rejectTargetFileNamesDifferingOnlyByCase() {
        val upper = jar("Collide.jar")
        val lower = jar("collide.jar")
        val deps = declarations(plugins = arrayOf(upper.absolutePath, lower.absolutePath))

        assertFailsWith<GradleException> { resolveDependencyJars(deps) { null } }
    }

    @Test
    @DisplayName("目标文件名各异时应通过预检且不误报冲突")
    fun acceptDistinctTargetFileNames() {
        val underTest = jar("under-test.jar")
        val libA = jar("libA.jar")
        val libB = jar("libB.jar")
        val deps = declarations(pluginUnderTest = underTest.absolutePath, libA.absolutePath, libB.absolutePath)

        val resolved = resolveDependencyJars(deps) { null }

        assertEquals(3, resolved.size, "文件名各异时不应误报冲突")
    }
}
