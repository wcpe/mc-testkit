package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.dsl.BackendSpec
import top.wcpe.mc.testkit.topology.ResolvedBackend
import top.wcpe.mc.testkit.topology.TopologyResolver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 持久运行目录（`backend { runDirectory }`）的解析校验、布局覆盖与 staging 单元测试。 */
class PersistentBackendRunDirTest {

    @TempDir
    lateinit var projectDir: File

    private val workRoot: File get() = File(projectDir, "build/mc-testkit")

    @Test
    @DisplayName("相对路径应按工程目录解析，绝对路径原样采用，未声明的后端不出现")
    fun resolveRelativeAndAbsoluteDeclarations() {
        val absolute = File(projectDir, "elsewhere/s2").absolutePath
        val dirs = resolvePersistentBackendRunDirs(
            projectDir,
            workRoot,
            backends(
                BackendSpec("s1").apply { runDirectory = "run/s1" },
                BackendSpec("s2").apply { runDirectory = absolute },
                BackendSpec("s3"),
            ),
        )

        assertEquals(File(projectDir, "run/s1").canonicalFile, dirs["s1"])
        assertEquals(File(absolute).canonicalFile, dirs["s2"])
        assertFalse("s3" in dirs)
    }

    @Test
    @DisplayName("与框架工作目录重叠、是工程目录本身或其上级的声明应以中文错误拒绝")
    fun rejectDirectoriesOverlappingWorkRootOrProject() {
        listOf("build/mc-testkit/keep", "build", ".", "..").forEach { declaration ->
            val exception = assertFailsWith<GradleException>(declaration) {
                resolvePersistentBackendRunDirs(
                    projectDir,
                    workRoot,
                    backends(BackendSpec("s1").apply { runDirectory = declaration }),
                )
            }
            assertTrue("s1" in exception.message!!, exception.message)
            assertTrue("runDirectory" in exception.message!!, exception.message)
        }
    }

    @Test
    @DisplayName("两个后端指向同一目录或互相嵌套时应以中文错误拒绝")
    fun rejectSharedOrNestedDirectories() {
        listOf("run/a/" to "run/./a", "run/a" to "run/a/nested").forEach { (first, second) ->
            val exception = assertFailsWith<GradleException>("$first / $second") {
                resolvePersistentBackendRunDirs(
                    projectDir,
                    workRoot,
                    backends(
                        BackendSpec("s1").apply { runDirectory = first },
                        BackendSpec("s2").apply { runDirectory = second },
                    ),
                )
            }
            assertTrue("s1" in exception.message!! && "s2" in exception.message!!, exception.message)
        }
    }

    @Test
    @DisplayName("声明为空白的 runDirectory 应在拓扑解析时拒绝")
    fun rejectBlankDeclaration() {
        val exception = assertFailsWith<GradleException> {
            backends(BackendSpec("s1").apply { runDirectory = " " })
        }

        assertTrue("runDirectory" in exception.message!!, exception.message)
    }

    @Test
    @DisplayName("布局带覆盖时声明了的后端指向持久目录，其余后端不变")
    fun layoutUsesOverrideForDeclaredBackendOnly() {
        val persistent = File(projectDir, "run/s1")
        val layout = RunLayout(File(projectDir, "build"), File(projectDir, "gradle-home"), projectDir)
            .withBackendRunDirOverrides(mapOf("s1" to persistent))

        assertEquals(persistent, layout.backendRunDir("s1"))
        assertEquals(persistent, layout.clusterBackendRunDir("s1"))
        assertTrue(layout.isPersistentBackendRunDir("s1"))
        assertEquals(File(layout.workRoot, "run-s2"), layout.backendRunDir("s2"))
        assertFalse(layout.isPersistentBackendRunDir("s2"))
    }

    @Test
    @DisplayName("持久目录二次 staging 应保留世界、插件数据与自放 jar，只替换框架注入的 jar 并整理框架产物")
    fun persistentStagingKeepsStateAndReplacesInjectedJars() {
        val runDir = File(projectDir, "run/s1")
        val backend = persistentBackend()
        val underTest = file("build/libs/under-test.jar", "under-test-v1")
        val dependencyV1 = file("libs/dep-1.0.jar", "dep-v1")

        stageBackendRuntime(runDir, backend, resources(underTest to true, dependencyV1 to false), persistent = true)

        file("run/s1/world/level.dat", "world-sentinel")
        file("run/s1/plugins/Dep/config.yml", "plugin-data-sentinel")
        file("run/s1/plugins/user-placed.jar", "user-jar-sentinel")
        file("run/s1/server-libraries/stale.jar", "stale-library")
        file("run/s1/s1.pid", "12345")
        file("run/s1/spigot.yml", "settings:\n  bungeecord: true\n")
        file("run/s1/config/paper-global.yml", "proxies:\n  velocity:\n    enabled: true\n    secret: sentinel\n")
        underTest.writeText("under-test-v2")
        val dependencyV2 = file("libs/dep-2.0.jar", "dep-v2")

        stageBackendRuntime(runDir, backend, resources(underTest to true, dependencyV2 to false), persistent = true)

        assertEquals("world-sentinel", File(runDir, "world/level.dat").readText())
        assertEquals("plugin-data-sentinel", File(runDir, "plugins/Dep/config.yml").readText())
        assertEquals("user-jar-sentinel", File(runDir, "plugins/user-placed.jar").readText())
        assertEquals("under-test-v2", File(runDir, "plugins/$UNDER_TEST_TARGET_FILE_NAME").readText())
        assertEquals("dep-v2", File(runDir, "plugins/dep-2.0.jar").readText())
        assertFalse(File(runDir, "plugins/dep-1.0.jar").exists(), "上一轮注入、本轮不再声明的依赖 jar 应被移除")
        assertFalse(File(runDir, "server-libraries").exists(), "注入运行库目录每轮由消费方重建，不应跨轮保留")
        assertFalse(File(runDir, "s1.pid").exists())
        val spigot = File(runDir, "spigot.yml").readText()
        assertTrue("bungeecord: false" in spigot, spigot)
        val paperGlobal = File(runDir, "config/paper-global.yml").readText()
        assertTrue("enabled: false" in paperGlobal, paperGlobal)
        assertTrue("secret: sentinel" in paperGlobal, paperGlobal)
        assertTrue("server-port=${backend.port}" in File(runDir, "server.properties").readText())
        assertEquals(
            listOf(UNDER_TEST_TARGET_FILE_NAME, "dep-2.0.jar"),
            File(runDir, INJECTED_PLUGINS_MANIFEST).readLines().filter { it.isNotBlank() },
        )
    }

    @Test
    @DisplayName("非持久目录 staging 仍按原口径清空，且不写注入清单")
    fun nonPersistentStagingStillCleansAndWritesNoManifest() {
        val runDir = File(projectDir, "build/mc-testkit/run-s1")
        file("build/mc-testkit/run-s1/world/level.dat", "world-sentinel")
        val backend = backends(BackendSpec("s1")).single()

        stageBackendRuntime(runDir, backend, resources(file("libs/dep-1.0.jar", "dep-v1") to false))

        assertFalse(File(runDir, "world").exists())
        assertFalse(File(runDir, INJECTED_PLUGINS_MANIFEST).exists())
        assertTrue(File(runDir, "plugins/dep-1.0.jar").isFile)
    }

    @Test
    @DisplayName("注入清单被改坏时也只会删 plugins 下的 jar，不越界")
    fun manifestCannotEscapePluginsDirectory() {
        val runDir = File(projectDir, "run/s1")
        val outside = file("outside.jar", "outside-sentinel")
        file("run/s1/$INJECTED_PLUGINS_MANIFEST", "../../outside.jar\n..\\..\\outside.jar\n")

        stageBackendRuntime(runDir, persistentBackend(), resources(), persistent = true)

        assertEquals("outside-sentinel", outside.readText())
    }

    private fun persistentBackend(): ResolvedBackend =
        backends(BackendSpec("s1").apply { runDirectory = "run/s1" }).single()

    private fun backends(vararg specs: BackendSpec): List<ResolvedBackend> =
        TopologyResolver.resolve(backends = specs.toList(), proxies = emptyList(), scenarios = emptyList()).backends

    private fun resources(vararg jars: Pair<File, Boolean>) = BackendRuntimeResources(
        templateDirectory = null,
        dependencyJars = jars.map { (jar, underTest) -> ResolvedPluginJar(jar.name, jar, underTest) },
    )

    private fun file(relativePath: String, content: String): File =
        File(projectDir, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }
}
