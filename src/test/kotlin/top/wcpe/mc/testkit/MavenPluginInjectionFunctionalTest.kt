package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Maven 坐标来源的功能测试（依赖注入 DSL + 服务端/代理 jar）。
 *
 * 用**测试用本地仓库目录**造假制品（手写 POM + jar 字节），全程不依赖公网或真实私有仓库：
 * - `dependencies { mavenPlugin(...) }`：坐标落成后端运行目录 `plugins/<制品名>.jar`；
 * - `backend { mavenServer(...) }`：坐标落成**服务端 jar 镜像**（`<mc-testkit-jars>/maven/...`）。
 */
class MavenPluginInjectionFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun file(relativePath: String): File = File(projectDir, relativePath).apply { parentFile?.mkdirs() }

    private fun write(name: String, text: String) = file(name).writeText(text)

    /**
     * 在测试用本地仓库里造一个最小可解析制品：`<repo>/<group>/<artifact>/<version>/<artifact>-<version>.{pom,jar}`。
     *
     * POM 只声明自身坐标（坐标为不可传递解析，故不含依赖也无妨），jar 内容无关紧要——
     * 本测试只验证「坐标 → 本地 jar → 注入 plugins/」这条链路。
     */
    private fun publishFakeArtifact(
        repoDirectory: String,
        group: String,
        artifact: String,
        version: String,
        jarContent: String,
    ): File {
        val artifactDir = "$repoDirectory/${group.replace('.', '/')}/$artifact/$version"
        write(
            "$artifactDir/$artifact-$version.pom",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent(),
        )
        val jar = file("$artifactDir/$artifact-$version.jar")
        jar.writeText(jarContent)
        return jar
    }

    private fun writeConsumerBuild(coordinateLines: String) {
        write("settings.gradle.kts", """rootProject.name = "maven-plugin-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            repositories {
                maven { url = uri(layout.projectDirectory.dir("local-repo")) }
            }
            mcTestkit {
                backend("maven-backend-sentinel") {
                    port = 25701
                    templateDirectory("maven-template-sentinel")
                }
                scenario("maven-sentinel") { backend = "maven-backend-sentinel" }
                dependencies {
            $coordinateLines
                }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments.toList())

    @Test
    @DisplayName("mavenPlugin 声明的坐标应变成本地 jar 并注入后端运行目录 plugins/")
    fun injectMavenPluginCoordinateIntoBackendPluginsDirectory() {
        publishFakeArtifact(
            repoDirectory = "local-repo",
            group = "com.example",
            artifact = "foo-plugin",
            version = "1.2.0",
            jarContent = "fake-maven-plugin-jar",
        )
        writeConsumerBuild("""        mavenPlugin("com.example:foo-plugin:1.2.0")""")
        file("maven-template-sentinel/server.properties").writeText("server-port=1\n")

        val result = runner("prepareE2eMavenSentinel", "--stacktrace").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareE2eMavenSentinel")?.outcome)
        val injected = file("build/mc-testkit/run-maven-backend-sentinel/plugins/foo-plugin-1.2.0.jar")
        assertTrue(
            injected.isFile,
            "坐标解析出的 jar 应按 Maven 制品名注入 plugins/；实际目录内容：" +
                file("build/mc-testkit/run-maven-backend-sentinel/plugins").listFiles()?.joinToString(),
        )
        assertEquals("fake-maven-plugin-jar", injected.readText(), "注入的应是仓库里那个制品的字节")
    }

    @Test
    @DisplayName("mavenPlugin 与路径声明混用时应一并注入且都能落盘")
    fun injectMavenPluginAlongsidePathDeclaration() {
        publishFakeArtifact(
            repoDirectory = "local-repo",
            group = "com.example",
            artifact = "foo-plugin",
            version = "1.2.0",
            jarContent = "fake-maven-plugin-jar",
        )
        // 路径声明与既有惯例一致地用绝对路径（`dependencies { plugin(path) }` 的相对路径按 Gradle 工作目录解析）
        val pathDeclaredJar = file("path-declared-plugin.jar").apply { writeText("path-declared-plugin-jar") }
        writeConsumerBuild(
            """
                    mavenPlugin("com.example:foo-plugin:1.2.0")
                    plugin("${pathDeclaredJar.invariantSeparatorsPath}")
            """.trimIndent(),
        )
        file("maven-template-sentinel/server.properties").writeText("server-port=1\n")

        val result = runner("prepareE2eMavenSentinel", "--stacktrace").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareE2eMavenSentinel")?.outcome)
        val pluginsDirectory = file("build/mc-testkit/run-maven-backend-sentinel/plugins")
        assertEquals("fake-maven-plugin-jar", File(pluginsDirectory, "foo-plugin-1.2.0.jar").readText())
        assertEquals("path-declared-plugin-jar", File(pluginsDirectory, "path-declared-plugin.jar").readText())
    }

    @Test
    @DisplayName("坐标在仓库中不存在时应以中文报错指明该坐标")
    fun reportChineseErrorWhenCoordinateCannotBeResolved() {
        // 仓库为空 → 该坐标无法解析
        file("local-repo/.keep").writeText("")
        writeConsumerBuild("""        mavenPlugin("com.example:ghost-plugin:9.9.9")""")
        file("maven-template-sentinel/server.properties").writeText("server-port=1\n")

        val result = runner("prepareE2eMavenSentinel", "--stacktrace").buildAndFail()

        assertTrue(
            result.output.contains("com.example:ghost-plugin:9.9.9"),
            "报错应指明无法解析的坐标\n---- 输出 ----\n${result.output}",
        )
        // 断言**抽出的原因行**本身（真实 Gradle 异常是嵌套 cause 链，原因必须被穿透取出，
        // 不能只显示泛泛的 "Could not resolve all files..."）
        assertTrue(
            result.output.contains("Could not find com.example:ghost-plugin:9.9.9"),
            "报错应带上点明制品的原因行（穿透 cause 链）\n---- 输出 ----\n${result.output}",
        )
        assertFalse(
            result.output.contains("Could not resolve all files"),
            "报错原因不应是泛泛的 resolve 首行\n---- 输出 ----\n${result.output}",
        )
        assertTrue(
            result.output.contains("RepositoriesMode.PREFER_SETTINGS"),
            "报错应点明仓库来源约定（消费方可能只有 settings 级仓库生效）\n---- 输出 ----\n${result.output}",
        )
        assertFalse(
            file("build/mc-testkit/run-maven-backend-sentinel/plugins").exists(),
            "坐标解析失败不得留下半铺的运行目录",
        )
    }

    @Test
    @DisplayName("坐标与路径声明撞同一目标文件名时应在铺运行目录前中文失败")
    fun rejectCollidingTargetFileNamesBeforeStagingRunDirectory() {
        publishFakeArtifact(
            repoDirectory = "local-repo",
            group = "com.example",
            artifact = "foo-plugin",
            version = "1.2.0",
            jarContent = "fake-maven-plugin-jar",
        )
        // 路径声明刻意用同名文件（foo-plugin-1.2.0.jar）与坐标撞名
        val colliding = file("collide/foo-plugin-1.2.0.jar").apply { writeText("path-declared") }
        writeConsumerBuild(
            """
                    mavenPlugin("com.example:foo-plugin:1.2.0")
                    plugin("${colliding.invariantSeparatorsPath}")
            """.trimIndent(),
        )
        file("maven-template-sentinel/server.properties").writeText("server-port=1\n")

        val result = runner("prepareE2eMavenSentinel", "--stacktrace").buildAndFail()

        assertTrue(
            result.output.contains("目标文件名冲突"),
            "撞名应在预检期以中文错误拒绝（API.md §1）\n---- 输出 ----\n${result.output}",
        )
        assertTrue(result.output.contains("foo-plugin-1.2.0.jar"), result.output)
        assertFalse(
            file("build/mc-testkit/run-maven-backend-sentinel").exists(),
            "预检失败须发生在铺运行目录之前（不留半铺目录）",
        )
    }

    // ── 配置缓存兼容（依赖插件 Maven 坐标）──
    private fun configurationCacheRunner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(arguments.toList() + "--configuration-cache")

    @Test
    @DisplayName("声明 mavenPlugin 后配置缓存应可存储与重用，且不需要依赖的任务不解析坐标")
    fun storeAndReuseConfigurationCacheWithoutResolvingCoordinatesForUnrelatedTasks() {
        // 坐标指向空仓库（必然解析不到）：只要调度的是不需要依赖注入的任务，就不该去解析它——
        // 否则配置缓存存储期即报错、或配置期就发网络请求。
        file("local-repo/.keep").writeText("")
        write("settings.gradle.kts", """rootProject.name = "maven-plugin-config-cache-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            repositories {
                maven { url = uri(layout.projectDirectory.dir("local-repo")) }
            }
            mcTestkit {
                backend("maven-backend-sentinel") { port = 25701 }
                scenario("maven-sentinel") { backend = "maven-backend-sentinel" }
                serve("dev") { backend = "maven-backend-sentinel" }
                dependencies {
                    mavenPlugin("com.example:never-resolvable:9.9.9")
                }
            }
            """.trimIndent(),
        )

        val firstRun = configurationCacheRunner("stopDevServe").build()
        assertTrue(
            firstRun.output.contains("Configuration cache entry stored"),
            "声明 mavenPlugin 后配置缓存仍应成功存储（不需要依赖的任务不解析坐标）\n---- 输出 ----\n${firstRun.output}",
        )

        val secondRun = configurationCacheRunner("stopDevServe").build()
        assertTrue(
            secondRun.output.contains("Reusing configuration cache"),
            "第二次运行应重用配置缓存\n---- 输出 ----\n${secondRun.output}",
        )
    }

    @Test
    @DisplayName("需要依赖注入的任务在配置缓存下应可存储、重用并完成注入")
    fun injectCoordinateUnderConfigurationCacheStoreAndReuse() {
        publishFakeArtifact(
            repoDirectory = "local-repo",
            group = "com.example",
            artifact = "foo-plugin",
            version = "1.2.0",
            jarContent = "fake-maven-plugin-jar",
        )
        writeConsumerBuild("""        mavenPlugin("com.example:foo-plugin:1.2.0")""")
        file("maven-template-sentinel/server.properties").writeText("server-port=1\n")

        val firstRun = configurationCacheRunner("prepareE2eMavenSentinel").build()
        assertTrue(
            firstRun.output.contains("Configuration cache entry stored"),
            "配置缓存应成功存储\n---- 输出 ----\n${firstRun.output}",
        )
        assertEquals(TaskOutcome.SUCCESS, firstRun.task(":prepareE2eMavenSentinel")?.outcome)

        val injected = file("build/mc-testkit/run-maven-backend-sentinel/plugins/foo-plugin-1.2.0.jar")
        assertTrue(injected.isFile, "首次运行即应把坐标解析出的 jar 注入 plugins/")
        assertTrue(injected.delete(), "删除注入物，验证重用配置缓存时不依赖上一轮残留")

        val secondRun = configurationCacheRunner("prepareE2eMavenSentinel").build()
        assertTrue(
            secondRun.output.contains("Reusing configuration cache"),
            "第二次运行应重用配置缓存\n---- 输出 ----\n${secondRun.output}",
        )
        assertEquals(TaskOutcome.SUCCESS, secondRun.task(":prepareE2eMavenSentinel")?.outcome)
        assertEquals(
            "fake-maven-plugin-jar",
            injected.readText(),
            "重用配置缓存后仍应重新注入坐标解析出的 jar",
        )
    }
}
