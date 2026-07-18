package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FR-20 从消费者 Gradle 任务入口验证全部后端与代理启动路径接线。 */
class NodeRuntimeInjectionFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `新 DSL 在全部后端与代理任务路径完成 staging 和节点环境接线`() {
        val runtimeJar = createProbeJar(file("runtime-probe-sentinel.jar"))
        file("backend-plugin-sentinel.jar").writeText("backend-plugin-sentinel")
        file("proxy-plugin-sentinel.jar").writeText("proxy-plugin-sentinel")
        prepareTemplates()
        writeConsumerBuild()

        val environment = System.getenv() + mapOf(
            "MC_TESTKIT_E2E_PAPER_JAR" to runtimeJar.absolutePath,
            "MC_TESTKIT_E2E_WATERFALL_JAR" to runtimeJar.absolutePath,
            "MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR" to file("legacy-template-sentinel").absolutePath,
            "MC_TESTKIT_E2E_BACKEND_NAME" to "host-backend-sentinel",
            "NODE_SENTINEL" to "host-node-sentinel",
        )

        file("build/mc-testkit/run-proxy/stale-sentinel.txt").apply { parentFile.mkdirs() }.writeText("stale-sentinel")
        assertEquals(
            TaskOutcome.SUCCESS,
            run("e2eSingleSentinelViaPx", environment).task(":e2eSingleSentinelViaPx")?.outcome,
        )
        assertSinglePathResults()

        assertEquals(TaskOutcome.SUCCESS, run("e2eClusterSentinelCluster", environment).task(":e2eClusterSentinelCluster")?.outcome)
        assertEquals("cluster-one-node-sentinel", probe("build/mc-testkit/run-cluster-one-sentinel")["node-sentinel"])
        assertEquals("cluster-two-node-sentinel", probe("build/mc-testkit/run-cluster-two-sentinel")["node-sentinel"])
        assertBungeeAuthority(
            linkedMapOf(
                "cluster-one-sentinel" to "127.0.0.1:25566",
                "cluster-two-sentinel" to "127.0.0.1:25567",
            ),
            listOf(25577 to listOf("cluster-one-sentinel", "cluster-two-sentinel")),
        )

        assertEquals(TaskOutcome.SUCCESS, run("e2eLoadSentinelStress", environment).task(":e2eLoadSentinelStress")?.outcome)
        assertEquals("cluster-one-node-sentinel", probe("build/mc-testkit/run-cluster-one-sentinel")["node-sentinel"])
        assertEquals("proxy-node-sentinel", probe("build/mc-testkit/run-proxy")["node-sentinel"])
        assertBungeeAuthority(
            linkedMapOf(
                "cluster-one-sentinel" to "127.0.0.1:25566",
                "cluster-two-sentinel" to "127.0.0.1:25567",
            ),
            listOf(
                25577 to listOf("cluster-one-sentinel"),
                25578 to listOf("cluster-two-sentinel"),
            ),
        )

        assertEquals(TaskOutcome.SUCCESS, run("serveDevSentinel", environment).task(":serveDevSentinel")?.outcome)
        assertEquals("serve-one-node-sentinel", probe("build/mc-testkit/run")["node-sentinel"])
        assertEquals("__mc_testkit_serve__", probe("build/mc-testkit/run")["scenario"])
        assertBungeeAuthority(
            linkedMapOf("backend" to "127.0.0.1:25568"),
            listOf(25577 to listOf("backend")),
        )

        assertEquals(TaskOutcome.SUCCESS, run("serveClusterDevSentinel", environment).task(":serveClusterDevSentinel")?.outcome)
        assertEquals("serve-one-node-sentinel", probe("build/mc-testkit/run-serve-one-sentinel")["node-sentinel"])
        assertEquals("serve-two-node-sentinel", probe("build/mc-testkit/run-serve-two-sentinel")["node-sentinel"])
        assertEquals("proxy-node-sentinel", probe("build/mc-testkit/run-proxy")["node-sentinel"])
        assertBungeeAuthority(
            linkedMapOf(
                "serve-one-sentinel" to "127.0.0.1:25568",
                "serve-two-sentinel" to "127.0.0.1:25569",
            ),
            listOf(25577 to listOf("serve-one-sentinel", "serve-two-sentinel")),
        )
    }

    @Test
    fun `集群资源预检失败时不清理先声明后端且不启动任何节点`() {
        write("settings.gradle.kts", """rootProject.name = "preflight-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("first-sentinel") {
                    port = 25601
                    templateDirectory("first-template-sentinel")
                }
                backend("missing-sentinel") {
                    port = 25602
                    templateDirectory("missing-template-sentinel")
                }
                proxy("px-sentinel") {
                    port = 25611
                    routesTo("first-sentinel", "missing-sentinel")
                }
                scenario("preflight-sentinel") {
                    backends("first-sentinel", "missing-sentinel")
                    via = "px-sentinel"
                }
            }
            """.trimIndent(),
        )
        file("first-template-sentinel").mkdirs()
        val marker = file("build/mc-testkit/run-first-sentinel/keep-sentinel.txt").apply {
            parentFile.mkdirs()
            writeText("keep-sentinel")
        }

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("e2ePreflightSentinelCluster", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("后端") && result.output.contains("missing-sentinel"), result.output)
        assertTrue(result.output.contains("目录") && result.output.contains("实际解析路径"), result.output)
        assertTrue(marker.isFile, "预检失败前不得清理先声明后端运行目录")
        assertFalse(file("build/mc-testkit/run-first-sentinel/runtime-probe.properties").exists())
    }

    @Test
    fun `混合大小写保留前缀在 TestKit 配置期失败`() {
        write("settings.gradle.kts", """rootProject.name = "guard-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("backend-sentinel") {
                    env("Mc_TestKit_E2E_Custom", "value-sentinel")
                }
                scenario("smoke-sentinel")
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("保留前缀"), result.output)
    }

    @Test
    fun `子工程旧模板 env 相对路径仍按显式可控 Gradle 工作目录解析`() {
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "legacy-relative-root-sentinel"
            include("consumer")
            """.trimIndent(),
        )
        write(
            "build.gradle.kts",
            """
            tasks.register("recordGradleWorkingDirectory") {
                doLast {
                    val target = layout.buildDirectory.file("gradle-working-directory.txt").get().asFile
                    target.parentFile.mkdirs()
                    target.writeText(java.io.File(".").canonicalPath)
                }
            }
            """.trimIndent(),
        )
        write(
            "consumer/build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("legacy-relative-backend-sentinel")
                scenario("legacy-relative-sentinel")
            }
            """.trimIndent(),
        )
        val testKitDirectory = File("build/node-runtime-injection-test-kit-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDirectory)
            .withPluginClasspath()
            .withArguments("recordGradleWorkingDirectory", "--stacktrace")
            .build()
        val gradleWorkingDirectory = File(file("build/gradle-working-directory.txt").readText())
        assertTrue(gradleWorkingDirectory.canonicalPath.startsWith(testKitDirectory.canonicalPath))
        File(gradleWorkingDirectory, "legacy-relative-template-sentinel/root-working-directory-sentinel.txt").apply {
            parentFile.mkdirs()
            writeText("root-working-directory-sentinel")
        }
        val environment = System.getenv() + mapOf(
            "MC_TESTKIT_E2E_SERVER_TEMPLATE_DIR" to "legacy-relative-template-sentinel",
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDirectory)
            .withPluginClasspath()
            .withEnvironment(environment)
            .withArguments(":consumer:prepareE2eLegacyRelativeSentinel", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:prepareE2eLegacyRelativeSentinel")?.outcome)
        assertTrue(file("consumer/build/mc-testkit/run/root-working-directory-sentinel.txt").isFile)
    }

    @Test
    fun `旧 DSL 任务名保持兼容且公共任务不出现 provide`() {
        write("settings.gradle.kts", """rootProject.name = "legacy-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("backend-sentinel")
                proxy("proxy-sentinel") { routesTo("backend-sentinel") }
                scenario("legacy-sentinel") {
                    backend = "backend-sentinel"
                    via = "proxy-sentinel"
                }
                dependencies {
                    pluginUnderTest = "backend-plugin-sentinel.jar"
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
        assertTrue(result.output.contains("prepareE2eLegacySentinel"))
        assertTrue(result.output.contains("e2eLegacySentinel"))
        assertTrue(result.output.contains("e2eLegacySentinelViaProxySentinel"))
        assertFalse(Regex("(?i)\\bprovide").containsMatchIn(result.output), result.output)
    }

    private fun assertSinglePathResults() {
        val backendProbe = probe("build/mc-testkit/run")
        val proxyProbe = probe("build/mc-testkit/run-proxy")
        val serverProperties = Properties().apply {
            file("build/mc-testkit/run/server.properties").inputStream().use(::load)
        }

        assertEquals("single-node-sentinel", backendProbe["node-sentinel"])
        assertEquals("single-sentinel", backendProbe["backend-name"])
        assertEquals("single-sentinel", backendProbe["scenario"])
        assertEquals("proxy-node-sentinel", proxyProbe["node-sentinel"])
        assertEquals("host-backend-sentinel", proxyProbe["backend-name"])
        assertTrue(file("build/mc-testkit/run/node-template-sentinel.txt").isFile)
        assertFalse(file("build/mc-testkit/run/legacy-template-sentinel.txt").exists())
        assertEquals("25565", serverProperties.getProperty("server-port"))
        assertTrue(file("build/mc-testkit/run/plugins/plugin-under-test.jar").isFile)
        assertTrue(file("build/mc-testkit/run-proxy/plugins/proxy-plugin-sentinel.jar").isFile)
        assertFalse(file("build/mc-testkit/run-proxy/plugins/plugin-under-test.jar").exists())
        assertTrue(file("build/mc-testkit/run-proxy/proxy-template-sentinel.txt").isFile)
        assertFalse(file("build/mc-testkit/run-proxy/stale-sentinel.txt").exists())
        assertBungeeAuthority(
            linkedMapOf("backend" to "127.0.0.1:25565"),
            listOf(25577 to listOf("backend")),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertBungeeAuthority(
        expectedServers: LinkedHashMap<String, String>,
        expectedListeners: List<Pair<Int, List<String>>>,
    ) {
        val root = Yaml().load<Map<String, Any?>>(file("build/mc-testkit/run-proxy/config.yml").readText())
        val servers = root.getValue("servers") as Map<String, Map<String, Any?>>
        val listeners = root.getValue("listeners") as List<Map<String, Any?>>

        assertEquals(expectedServers.keys.toList(), servers.keys.toList())
        expectedServers.forEach { (name, address) -> assertEquals(address, servers.getValue(name)["address"]) }
        assertEquals(expectedListeners.size, listeners.size)
        expectedListeners.zip(listeners).forEach { (expected, listener) ->
            assertEquals("0.0.0.0:${expected.first}", listener["host"])
            assertEquals(expected.second, listener["priorities"])
        }
    }

    private fun prepareTemplates() {
        file("backend-template-sentinel/server.properties").writeText("server-port=1\ndifficulty=hard\n")
        file("backend-template-sentinel/node-template-sentinel.txt").writeText("node-template-sentinel")
        file("legacy-template-sentinel/legacy-template-sentinel.txt").writeText("legacy-template-sentinel")
        file("proxy-template-sentinel/config.yml").writeText("template-config-sentinel")
        file("proxy-template-sentinel/proxy-template-sentinel.txt").writeText("proxy-template-sentinel")
        file("proxy-template-sentinel/plugins/proxy-plugin-sentinel.jar").writeText("template-plugin-sentinel")
    }

    private fun writeConsumerBuild() {
        val backendPluginPath = file("backend-plugin-sentinel.jar").invariantSeparatorsPath
        write("settings.gradle.kts", """rootProject.name = "runtime-injection-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            mcTestkit {
                backend("single-sentinel") {
                    port = 25565
                    env("NODE_SENTINEL", "single-node-sentinel")
                    env("PROBE_PORTS", "25565")
                    env("PROBE_EXIT_MILLIS", "500")
                    templateDirectory("backend-template-sentinel")
                }
                backend("cluster-one-sentinel") {
                    port = 25566
                    env("NODE_SENTINEL", "cluster-one-node-sentinel")
                    env("PROBE_PORTS", "25566")
                    env("PROBE_EXIT_MILLIS", "3000")
                    templateDirectory("backend-template-sentinel")
                }
                backend("cluster-two-sentinel") {
                    port = 25567
                    env("NODE_SENTINEL", "cluster-two-node-sentinel")
                    env("PROBE_PORTS", "25567")
                    env("PROBE_EXIT_MILLIS", "3000")
                    templateDirectory("backend-template-sentinel")
                }
                backend("serve-one-sentinel") {
                    port = 25568
                    env("NODE_SENTINEL", "serve-one-node-sentinel")
                    env("PROBE_PORTS", "25568")
                    env("PROBE_EXIT_MILLIS", "2000")
                    templateDirectory("backend-template-sentinel")
                }
                backend("serve-two-sentinel") {
                    port = 25569
                    env("NODE_SENTINEL", "serve-two-node-sentinel")
                    env("PROBE_PORTS", "25569")
                    env("PROBE_EXIT_MILLIS", "5000")
                    templateDirectory("backend-template-sentinel")
                }
                proxy("px") {
                    port = 25577
                    routesTo(
                        "single-sentinel",
                        "cluster-one-sentinel",
                        "cluster-two-sentinel",
                        "serve-one-sentinel",
                        "serve-two-sentinel",
                    )
                    plugin("proxy-plugin-sentinel.jar")
                    env("NODE_SENTINEL", "proxy-node-sentinel")
                    env("PROBE_PORTS", "25577,25578")
                    env("PROBE_EXIT_MILLIS", "3000")
                    templateDirectory("proxy-template-sentinel")
                }
                scenario("single-sentinel") {
                    backend = "single-sentinel"
                    via = "px"
                }
                scenario("cluster-sentinel") {
                    backends("cluster-one-sentinel", "cluster-two-sentinel")
                    via = "px"
                }
                scenario("load-sentinel") {
                    backends("cluster-one-sentinel", "cluster-two-sentinel")
                    via = "px"
                    stress {
                        botsPerServer = 1
                        durationSeconds = 1
                    }
                }
                serve("dev-sentinel") {
                    backend = "serve-one-sentinel"
                    via = "px"
                }
                serve("cluster-dev-sentinel") {
                    backends("serve-one-sentinel", "serve-two-sentinel")
                    via = "px"
                }
                dependencies {
                    pluginUnderTest = "$backendPluginPath"
                }
            }
            """.trimIndent(),
        )
    }

    private fun run(task: String, environment: Map<String, String>) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withEnvironment(environment)
            .withArguments(task, "--stacktrace")
            .build()

    private fun probe(relativeDirectory: String): Map<String, String> {
        val properties = Properties().apply {
            file("$relativeDirectory/runtime-probe.properties").inputStream().use(::load)
        }
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    private fun createProbeJar(target: File): File {
        val classPath = listOf(codeSourceFile(NodeRuntimeProbeMain::class.java), codeSourceFile(Unit::class.java))
            .map { it.toURI().toString() }
            .joinToString(" ")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = NodeRuntimeProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { }
        return target
    }

    private fun codeSourceFile(clazz: Class<*>): File = File(clazz.protectionDomain.codeSource.location.toURI())

    private fun write(name: String, text: String) {
        file(name).writeText(text)
    }

    private fun file(relativePath: String): File = File(projectDir, relativePath).apply { parentFile?.mkdirs() }
}

/** 测试用极小节点进程：记录环境、监听哨兵端口、按框架结果路径写 PASS 后短暂存活。 */
object NodeRuntimeProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sockets = openSentinelPorts(System.getenv("PROBE_PORTS"))
        try {
            writeProbeFile()
            writeResultFile()
            Thread.sleep(System.getenv("PROBE_EXIT_MILLIS")?.toLongOrNull() ?: 500L)
        } finally {
            sockets.forEach(ServerSocket::close)
        }
    }

    private fun openSentinelPorts(rawPorts: String?): List<ServerSocket> =
        rawPorts.orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map(::ServerSocket)

    private fun writeProbeFile() {
        val content = buildString {
            appendLine("node-sentinel=${System.getenv("NODE_SENTINEL").orEmpty()}")
            appendLine("backend-name=${System.getenv("MC_TESTKIT_E2E_BACKEND_NAME").orEmpty()}")
            appendLine("scenario=${System.getenv("MC_TESTKIT_E2E_SCENARIO").orEmpty()}")
        }
        Files.writeString(
            Path.of("runtime-probe.properties"),
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun writeResultFile() {
        val resultPath = System.getenv("MC_TESTKIT_E2E_RESULT_FILE")?.takeIf(String::isNotBlank) ?: return
        val path = Path.of(resultPath)
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            "status=PASS\nmessage=runtime-probe-sentinel\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
