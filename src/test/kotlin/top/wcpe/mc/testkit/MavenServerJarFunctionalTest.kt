package top.wcpe.mc.testkit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 服务端 jar 按 Maven 坐标解析的功能测试（`backend { mavenServer(...) }`）。
 *
 * 覆盖两件事：
 * 1. **端到端注入**：本地仓库里的「假服务端」jar 被解析、**镜像**进
 *    `<gradleUserHome>/caches/mc-testkit-jars/maven/<group 路径>/<artifact>/<version>/…`，
 *    且内容与仓库制品逐字节一致——用探针 jar 充当服务端，故无需真实 Paper 即可跑完整 `e2e<Key>`。
 * 2. **注册期分流（配置缓存可判别）**：镜像命中时**不创建解析配置**（配置缓存可存储、不触仓库）；
 *    镜像缺失时创建解析配置，坐标不可解析即在配置缓存存储期暴露失败。
 */
class MavenServerJarFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** TestKit 的 Gradle 用户主目录（镜像落点相对它推导）；放 build/ 下避免 @TempDir 清理与守护进程锁冲突。 */
    private val testKitDir: File = File("build/maven-server-test-kit-${System.nanoTime()}").canonicalFile

    private fun file(relativePath: String): File = File(projectDir, relativePath).apply { parentFile?.mkdirs() }

    private fun write(name: String, text: String) = file(name).writeText(text)

    /** 该坐标在 TestKit Gradle 用户主目录下的镜像路径（与 JarCache.mavenJarFile 同布局）。 */
    private fun mirrorOf(coordinate: String): File {
        val (group, artifact, version) = coordinate.split(':')
        return File(testKitDir, "caches/mc-testkit-jars/maven")
            .resolve(group.replace('.', '/'))
            .resolve(artifact)
            .resolve(version)
            .resolve("$artifact-$version.jar")
    }

    /** 在测试用本地仓库里造一个最小可解析制品（POM + jar 字节）。 */
    private fun publishFakeArtifact(group: String, artifact: String, version: String, jarBytes: ByteArray) {
        val artifactDir = "local-repo/${group.replace('.', '/')}/$artifact/$version"
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
        file("$artifactDir/$artifact-$version.jar").writeBytes(jarBytes)
    }

    /**
     * 造一个「假服务端」探针 jar：`Main-Class` 指向 [MavenServerProbeMain]。
     *
     * 该探针写框架结果文件为 PASS 后短暂存活，故 `e2e<Key>` 能判定通过——无需真实 Paper 服务端。
     */
    private fun createProbeServerJar(): ByteArray {
        val classPath = listOf(codeSourceFile(MavenServerProbeMain::class.java), codeSourceFile(Unit::class.java))
            .map { it.toURI().toString() }
            .joinToString(" ")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = MavenServerProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        val target = File.createTempFile("fake-server-", ".jar").apply { deleteOnExit() }
        JarOutputStream(target.outputStream(), manifest).use { }
        return target.readBytes()
    }

    private fun codeSourceFile(clazz: Class<*>): File = File(clazz.protectionDomain.codeSource.location.toURI())

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withPluginClasspath()
            .withArguments(arguments.toList())

    // ── 端到端注入 ──

    @Test
    @DisplayName("mavenServer 坐标应被解析、镜像进缓存，并以镜像启动服务端")
    fun resolveMavenServerCoordinateIntoMirrorAndRunBackend() {
        val coordinate = "com.example:fake-server:1.0.0"
        val probeBytes = createProbeServerJar()
        publishFakeArtifact("com.example", "fake-server", "1.0.0", probeBytes)
        val port = allocatePort()
        writeConsumerBuild(
            coordinate = coordinate,
            port = port,
            extraBackendLines = "",
        )

        val result = runner("e2eSmoke").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":e2eSmoke")?.outcome, result.output)
        val mirror = mirrorOf(coordinate)
        assertTrue(
            mirror.isFile,
            "坐标 jar 应被镜像进 <mc-testkit-jars>/maven/...；实际：$mirror\n---- 输出 ----\n${result.output}",
        )
        assertTrue(probeBytes.contentEquals(mirror.readBytes()), "镜像应与仓库制品逐字节一致")
    }

    @Test
    @DisplayName("镜像命中后重跑应直接复用镜像且不重新解析坐标")
    fun reuseMirrorOnSecondRun() {
        val coordinate = "com.example:fake-server:1.0.0"
        publishFakeArtifact("com.example", "fake-server", "1.0.0", createProbeServerJar())
        val port = allocatePort()
        writeConsumerBuild(coordinate = coordinate, port = port, extraBackendLines = "")

        runner("e2eSmoke").build()
        val mirror = mirrorOf(coordinate)
        assertTrue(mirror.isFile, "首轮应产生镜像")
        // 删掉本地仓库：若第二轮仍能跑通且**配置缓存可存储**，说明它走的是镜像、没再创建解析配置
        // （对比 missingMirrorWithUnresolvableCoordinate... 用例：那里镜像缺失 → 创建配置 → 存储期失败）。
        // 加 --configuration-cache 才有判别力：仅凭「跑通」不够——Gradle 自身 modules-2 可能兜底解析。
        File(projectDir, "local-repo").deleteRecursively()

        val second = runner("e2eSmoke", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, second.task(":e2eSmoke")?.outcome, second.output)
        assertTrue(
            second.output.contains("Configuration cache entry stored"),
            "镜像命中时不该创建解析配置，配置缓存应可存储（仓库已删，若重新解析必失败）\n" +
                "---- 输出 ----\n${second.output}",
        )
        assertTrue(mirror.isFile, "第二轮应继续复用镜像")
    }

    @Test
    @DisplayName("设置 *_JAR 覆盖时应优先于 mavenServer 且不产生镜像")
    fun preferJarOverrideOverMavenServerCoordinate() {
        val coordinate = "com.example:fake-server:1.0.0"
        publishFakeArtifact("com.example", "fake-server", "1.0.0", createProbeServerJar())
        val port = allocatePort()
        writeConsumerBuild(coordinate = coordinate, port = port, extraBackendLines = "")
        // 显式覆盖为中性的假 jar（同样由探针充当），并指向覆盖路径
        val overrideJar = file("override-server.jar").apply { writeBytes(createProbeServerJar()) }

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(testKitDir)
            .withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("MC_TESTKIT_E2E_PAPER_JAR" to overrideJar.absolutePath))
            .withArguments("e2eSmoke")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":e2eSmoke")?.outcome, result.output)
        assertFalse(
            mirrorOf(coordinate).exists(),
            "*_JAR 覆盖优先时不应解析坐标、自然也不该产生镜像",
        )
    }

    // ── 注册期分流（配置缓存可判别）──

    @Test
    @DisplayName("镜像预置时声明 mavenServer 应不创建解析配置，配置缓存可存储")
    fun presetMirrorAvoidsCreatingResolutionConfiguration() {
        val coordinate = "com.example:never-resolvable:9.9.9"
        // 仓库为空 → 该坐标无法解析；同时**预置镜像**，使注册期走"命中"分支
        file("local-repo/.keep").writeText("")
        mirrorOf(coordinate).apply {
            parentFile.mkdirs()
            writeText("preset-mirror-bytes")
        }
        writeConsumerBuild(coordinate = coordinate, port = allocatePort(), extraBackendLines = "")

        val result = runner("prepareE2eSmoke", "--configuration-cache").build()

        assertTrue(
            result.output.contains("Configuration cache entry stored"),
            "镜像命中时不该创建解析配置，配置缓存应可存储（不触仓库）\n---- 输出 ----\n${result.output}",
        )
        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareE2eSmoke")?.outcome, result.output)
    }

    /**
     * 捕获范围配对判别：**同一坐标、同一构建**，仅任务不同——
     * - `prepareE2e<Key>` 只注入插件、不起服务端 → 不捕获服务端坐标 → 不可解析也不影响它；
     * - `e2e<Key>` 要起服务端 → 捕获服务端坐标 → 配置缓存存储期即解析 → 不可解析即失败。
     *
     * 两条配对可证否"捕获范围"被无声放大（若 prepare 也捕获服务端坐标，第一条会失败）。
     */
    @Test
    @DisplayName("镜像缺失时 prepare 不解析服务端坐标（证明捕获范围未放大）")
    fun prepareDoesNotResolveServerCoordinateWhenMirrorMissing() {
        val coordinate = "com.example:never-resolvable:9.9.9"
        file("local-repo/.keep").writeText("") // 仓库为空且镜像缺失 → 该坐标必然无法解析
        writeConsumerBuild(coordinate = coordinate, port = allocatePort(), extraBackendLines = "")

        val result = runner("prepareE2eSmoke", "--configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareE2eSmoke")?.outcome, result.output)
        assertTrue(
            result.output.contains("Configuration cache entry stored"),
            "prepare 只注入插件、不起服务端，故不该为服务端坐标建解析配置\n---- 输出 ----\n${result.output}",
        )
    }

    @Test
    @DisplayName("镜像缺失时起服任务应在配置缓存存储期因坐标不可解析而失败（配对判别）")
    fun serverTaskFailsAtConfigurationCacheStoreWhenMirrorMissing() {
        val coordinate = "com.example:never-resolvable:9.9.9"
        file("local-repo/.keep").writeText("") // 与上一条同坐标：仅任务不同
        writeConsumerBuild(coordinate = coordinate, port = allocatePort(), extraBackendLines = "")

        val result = runner("e2eSmoke", "--configuration-cache").buildAndFail()

        assertTrue(
            result.output.contains("never-resolvable") || result.output.contains("Could not find"),
            "起服任务捕获服务端坐标，故不可解析应在存储期暴露\n---- 输出 ----\n${result.output}",
        )
    }

    /** 最小消费方工程：单个后端 + 单个无 bot 场景，服务端 jar 来自给定坐标。 */
    private fun writeConsumerBuild(coordinate: String, port: Int, extraBackendLines: String) {
        write("settings.gradle.kts", """rootProject.name = "maven-server-sentinel"""")
        write(
            "build.gradle.kts",
            """
            plugins { id("top.wcpe.mc-testkit") }
            repositories {
                maven { url = uri(layout.projectDirectory.dir("local-repo")) }
            }
            mcTestkit {
                backend("s1") {
                    port = $port
                    mavenServer("$coordinate")
                    env("PROBE_PORTS", "$port")
                    env("PROBE_EXIT_MILLIS", "15000")
                    $extraBackendLines
                }
                scenario("smoke") { backend = "s1" }
            }
            """.trimIndent(),
        )
    }

    /** 在保留段内挑一个当前空闲端口，避开内核动态分配池与常见业务端口。 */
    private fun allocatePort(): Int {
        var candidate = 26100
        while (candidate <= 26600) {
            try {
                ServerSocket(candidate).use { return it.localPort }
            } catch (ignored: java.io.IOException) {
                candidate++
            }
        }
        error("无法在 26100-26600 内分配到空闲端口")
    }
}

/**
 * 测试用「假服务端」：监听哨兵端口、写框架结果文件为 PASS、短暂存活后自停。
 *
 * 与 [NodeRuntimeProbeMain] 同思路，但**独立一份**以让本测试自足（不依赖那个测试类的私有脚手架）。
 */
object MavenServerProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sockets = System.getenv("PROBE_PORTS").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { ServerSocket(it) }
        try {
            writeResultFile()
            Thread.sleep(System.getenv("PROBE_EXIT_MILLIS")?.toLongOrNull() ?: 500L)
        } finally {
            sockets.forEach(ServerSocket::close)
        }
    }

    private fun writeResultFile() {
        val resultPath = System.getenv("MC_TESTKIT_E2E_RESULT_FILE")?.takeIf(String::isNotBlank) ?: return
        val path = Path.of(resultPath)
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            "status=PASS\nmessage=maven-server-probe\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
