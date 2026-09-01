package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ServerLauncher] 启动助手单元测试（内置下载与运行）。
 *
 * 不连真实服务端 / 代理（那属 首个消费者验证 实机维度）。这里用一个**自带 main 的极小可运行 jar**
 * （现造一个会立即退出的 JVM 程序）验证：以子进程 `java -jar` 启动、写 pid 文件、日志落盘、
 * cwd 为运行目录、进程能正常起止。覆盖跨平台进程启动 / pid 落盘这一高风险区的正常路径。
 *
 * 命令组装的分支（自包含 jar / 运行库 classpath / paperclip / 清单损坏）经 `internal` 的
 * `buildCommand` 直接断言，不起子进程，便于穷举且不受进程时序影响。
 */
class ServerLauncherTest {

    @Test
    @DisplayName("解析 pid 文件时应使用服务键命名")
    fun resolvePidFileUsingServerKey() {
        val dir = File("build/test-run-dir")
        assertEquals(File(dir, "s1.pid"), provisionPidFile(dir, "s1"))
    }

    @Test
    @DisplayName("解析 classpath 启动器文件时应使用服务键命名并以点号开头")
    fun resolveClasspathFileUsingServerKey() {
        val dir = File("build/test-run-dir")
        assertEquals(File(dir, ".mc-testkit-s1-classpath.jar"), provisionClasspathFile(dir, "s1"))
    }

    @Test
    @DisplayName("1.12.x 后端程序参数不应含 --nogui；1.13+ 与 26.x 应含")
    fun backendServerArgsForLegacyPaperOmitNogui() {
        assertEquals(emptyList(), backendServerArgs("1.12.2"))
        assertEquals(emptyList(), backendServerArgs("1.8.8"))
        assertEquals(listOf("--nogui"), backendServerArgs("1.13.2"))
        assertEquals(listOf("--nogui"), backendServerArgs("1.20.1"))
        // 新版号方案：26.2 的第二段是补丁/次版本，不能按 1.x 的 minor≤12 误判成旧服
        assertEquals(listOf("--nogui"), backendServerArgs("26.2"))
        assertEquals(listOf("--nogui"), backendServerArgs("26.1.2"))
    }

    @Test
    @DisplayName("解析 Java 可执行文件时应返回有效名称")
    fun resolveExistingJavaExecutable() {
        val exe = javaExecutable()
        // 要么是 java.home/bin 下存在的绝对路径，要么回退为裸名 java/java.exe
        assertTrue(exe.endsWith("java") || exe.endsWith("java.exe"), "应为 java 可执行：$exe")
    }

    @Test
    @DisplayName("启动不存在的 jar 时应抛出中文错误")
    fun rejectMissingJarWithChineseError() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ServerLauncher.launch(
                jar = File("build/no-such.jar"),
                runDirectory = File("build/test-run-x"),
                key = "s1",
            )
        }
        assertTrue(ex.message!!.contains("不存在"), "应提示 jar 不存在：${ex.message}")
    }

    @Test
    @DisplayName("启动可运行 jar 时应写入 pid 文件与日志")
    fun launchRunnableJarAndWritePidAndLog() {
        val workDir = File("build/test-launch-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))

        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = workDir,
            key = "node1",
            serverArgs = listOf("ok"),
        )
        try {
            // pid 文件写入且内容为该进程 pid
            val pidFile = provisionPidFile(workDir, "node1")
            assertTrue(pidFile.isFile, "pid 文件应写入：${pidFile.absolutePath}")
            assertEquals(process.pid().toString(), pidFile.readText().trim())

            // 进程应能在合理时间内自行退出（极小程序立即退出）
            val exited = process.waitFor(30, TimeUnit.SECONDS)
            assertTrue(exited, "极小程序应已退出")
            assertEquals(0, process.exitValue())

            // 日志文件落在运行目录、且捕获到程序输出
            val logFile = File(workDir, "node1.log")
            assertTrue(logFile.isFile, "日志文件应落在运行目录：${logFile.absolutePath}")
            assertTrue(logFile.readText().contains("MC_TESTKIT_LAUNCH_OK"), "应捕获到子进程输出")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    @DisplayName("记录启动日志时不应泄露环境变量值")
    fun avoidLoggingEnvironmentVariableValues() {
        val workDir = File("build/test-launch-env-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))
        val messages = mutableListOf<String>()
        val environmentValue = "environment-value-sentinel"

        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = workDir,
            key = "node-env",
            environment = mapOf("NODE_ENV_SENTINEL" to environmentValue),
            logger = messages::add,
        )
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertTrue(messages.isNotEmpty())
            assertTrue(messages.none { environmentValue in it }, "ServerLauncher 日志不得打印环境变量值")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    @DisplayName("启动器应把用户 JVM 参数置于 jar 之前并传入子进程")
    fun launchPassesConfiguredJvmArgumentsToChildProcess() {
        val workDir = File("build/test-launch-jvm-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))

        val process = ServerLauncher.launch(
            jar = jar,
            runDirectory = workDir,
            key = "node-jvm",
            jvmArgs = listOf("-Dmc.testkit.jvm.sentinel=enabled"),
        )
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertTrue(
                File(workDir, "node-jvm.log").readText().contains("jvm=enabled"),
                "子进程应读取到启动器输入的 JVM 参数",
            )
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    @DisplayName("服务端运行库存在时应使用完整 classpath 启动非自包含服务端 jar")
    fun launchServerJarWithRuntimeLibraries() {
        val workDir = File("build/test-launch-libraries-${System.nanoTime()}").apply { mkdirs() }
        val serverJar = createThinServerLayout(workDir)

        val process = ServerLauncher.launch(serverJar, workDir, "server")
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "服务端探针应已退出")
            assertEquals(0, process.exitValue())
            assertTrue(File(workDir, "server.log").readText().contains("MC_TESTKIT_LAUNCH_OK"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    @DisplayName("运行目录与运行库名含空格时仍应经 classpath 启动成功")
    fun launchServerJarFromPathContainingSpaces() {
        val workDir = File("build/test-launch 空格 目录-${System.nanoTime()}").apply { mkdirs() }
        val serverJar = createThinServerLayout(workDir, probeLibraryName = "probe 副本.jar")

        val process = ServerLauncher.launch(serverJar, workDir, "server")
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "服务端探针应已退出")
            val log = File(workDir, "server.log").readText()
            assertEquals(0, process.exitValue(), "空格路径不得截断 Class-Path：$log")
            assertTrue(log.contains("MC_TESTKIT_LAUNCH_OK"), "应捕获到子进程输出：$log")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    @DisplayName("记录启动日志时应说明按运行库 classpath 启动")
    fun logRuntimeClasspathLaunchMode() {
        val workDir = File("build/test-command-log-${System.nanoTime()}").apply { mkdirs() }
        val serverJar = createThinServerLayout(workDir)
        val messages = mutableListOf<String>()

        ServerLauncher.buildCommand(serverJar, workDir, "server", emptyList(), emptyList(), null, messages::add)

        assertTrue(messages.isNotEmpty(), "应输出选路日志")
        assertTrue(messages.any { "运行库" in it }, "应说明按运行库 classpath 启动：$messages")
    }

    @Test
    @DisplayName("组装命令时无运行库目录应沿用 java -jar 原路径")
    fun buildCommandKeepsJarModeWithoutLibraries() {
        val workDir = File("build/test-command-plain-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))

        val command = ServerLauncher.buildCommand(jar, workDir, "node", listOf("-Xmx1G"), listOf("--nogui"), null)

        assertEquals(listOf(javaExecutable(), "-Xmx1G", "-jar", jar.absolutePath, "--nogui"), command)
        assertFalse(provisionClasspathFile(workDir, "node").exists(), "未启用 classpath 启动器时不应生成它")
    }

    @Test
    @DisplayName("组装命令时无 Main-Class 的 jar 应沿用 java -jar 原路径")
    fun buildCommandKeepsJarModeWithoutMainClass() {
        val workDir = File("build/test-command-nomain-${System.nanoTime()}").apply { mkdirs() }
        val jar = File(workDir, "no-main.jar").apply { parentFile?.mkdirs() }
        JarOutputStream(jar.outputStream()).use { /* 无清单，仅一个空 jar */ }

        val command = ServerLauncher.buildCommand(jar, workDir, "node", emptyList(), emptyList(), null)

        assertTrue(command.contains("-jar"), "无入口类时应按自包含 jar 启动：$command")
    }

    @Test
    @DisplayName("组装命令时清单不可读的 jar 应退回 java -jar 而非抛异常")
    fun buildCommandFallsBackToJarModeWhenManifestUnreadable() {
        val workDir = File("build/test-command-broken-${System.nanoTime()}").apply { mkdirs() }
        val jar = File(workDir, "broken.jar").apply {
            parentFile?.mkdirs()
            writeText("这不是一个 zip 文件")
        }

        val command = ServerLauncher.buildCommand(jar, workDir, "node", emptyList(), emptyList(), null)

        assertTrue(command.contains("-jar"), "读不出清单时应退回 -jar 让子进程自行报错：$command")
    }

    @Test
    @DisplayName("组装命令时 paperclip 主入口应保留 java -jar 引导流程")
    fun buildCommandKeepsJarModeForPaperclipMain() {
        val workDir = File("build/test-command-paperclip-${System.nanoTime()}").apply { mkdirs() }
        createThinServerLayout(workDir)
        val paperclipJar = createMainOnlyJar(File(workDir, "paperclip.jar"), "io.papermc.paperclip.Main")

        val command = ServerLauncher.buildCommand(paperclipJar, workDir, "paperclip", emptyList(), emptyList(), null)

        assertTrue(command.contains("-jar"), "paperclip 必须自己引导，不得替它拼 classpath：$command")
        assertEquals(paperclipJar.absolutePath, command[command.indexOf("-jar") + 1])
        assertFalse(provisionClasspathFile(workDir, "paperclip").exists(), "paperclip 场景不应生成 classpath 启动器")
    }

    @Test
    @DisplayName("组装命令时运行库存在应以启动器 jar 传完整 classpath")
    fun buildCommandUsesRuntimeClasspathWhenLibrariesPresent() {
        val workDir = File("build/test-command-libraries-${System.nanoTime()}").apply { mkdirs() }
        val serverJar = createThinServerLayout(workDir)

        val command = ServerLauncher.buildCommand(serverJar, workDir, "server", emptyList(), emptyList(), null)

        assertEquals(
            listOf(
                javaExecutable(),
                "-cp",
                provisionClasspathFile(workDir, "server").absolutePath,
                LaunchProbeMain::class.java.name,
            ),
            command,
            "thin jar 应经启动器 jar 传 classpath 并显式给出主类",
        )
        val classpath = assembleClassPath(serverJar, workDir)
        assertEquals("server.jar", classpath.first(), "服务端 jar 必须排在 classpath 首位：$classpath")
        assertTrue(classpath.contains("libraries/probe.jar"), "运行库应接进 classpath：$classpath")
        assertTrue(classpath.contains("libraries/kotlin-stdlib.jar"), "运行库应接进 classpath：$classpath")
    }

    @Test
    @DisplayName("组装命令时 Class-Path 条目的空格应按 UTF-8 百分号编码")
    fun buildCommandEncodesSpacesInClasspathEntries() {
        val workDir = File("build/test-command 空格 目录-${System.nanoTime()}").apply { mkdirs() }
        val serverJar = createThinServerLayout(workDir, probeLibraryName = "probe 副本.jar")

        val classpath = assembleClassPath(serverJar, workDir)

        assertTrue(classpath.any { "%20" in it }, "空格必须编码为 %20，否则 JVM 会截断条目：$classpath")
        assertTrue(classpath.none { " " in it }, "Class-Path 条目不得含未编码空格：$classpath")
        val decoded = classpath.map { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        assertTrue(decoded.contains("libraries/probe 副本.jar"), "编码后必须能还原原运行库路径：$classpath")
    }

    @Test
    @DisplayName("组装命令时运行库应按相对路径升序排列且多次调用结果一致")
    fun buildCommandSortsLibrariesDeterministically() {
        val workDir = File("build/test-command-order-${System.nanoTime()}").apply { mkdirs() }
        val librariesDir = File(workDir, "libraries").apply { mkdirs() }
        val serverJar = createMainOnlyJar(File(workDir, "server.jar"))
        listOf("c.jar", "a.jar", "b.jar").forEach { createProbeLibraryJar(File(librariesDir, it)) }

        val first = assembleClassPath(serverJar, workDir)
        val second = assembleClassPath(serverJar, workDir)

        assertEquals(listOf("server.jar", "libraries/a.jar", "libraries/b.jar", "libraries/c.jar"), first)
        assertEquals(first, second, "装载顺序必须稳定，不能依赖文件系统返回顺序")
    }

    @Test
    @DisplayName("按自包含 jar 启动时不应残留上轮的 classpath 启动器")
    fun launchRemovesStaleClasspathLauncherWhenSelfContained() {
        val workDir = File("build/test-launch-stale-${System.nanoTime()}").apply { mkdirs() }
        val jar = createImmediateExitJar(File(workDir, "hello.jar"))
        val staleLauncher = provisionClasspathFile(workDir, "node").apply { writeText("上一轮残留") }
        assertTrue(staleLauncher.isFile, "前置条件：存在上一轮残留的启动器")

        val process = ServerLauncher.launch(jar = jar, runDirectory = workDir, key = "node")
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "极小程序应已退出")
            assertFalse(staleLauncher.exists(), "本轮走 -jar 路径时不得残留旧启动器，避免误用旧依赖清单")
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * 铺出 thin jar 服务端布局：运行目录下只有清单的 `server.jar`，依赖拆到 `libraries/`。
     *
     * @param probeLibraryName 探针库文件名（可含空格，用于覆盖路径编码场景）。
     */
    private fun createThinServerLayout(workDir: File, probeLibraryName: String = "probe.jar"): File {
        workDir.mkdirs()
        val librariesDir = File(workDir, "libraries").apply { mkdirs() }
        val serverJar = createMainOnlyJar(File(workDir, "server.jar"))
        createProbeLibraryJar(File(librariesDir, probeLibraryName))
        Files.copy(codeSourceFile(Unit::class.java).toPath(), File(librariesDir, "kotlin-stdlib.jar").toPath())
        return serverJar
    }

    /** 创建只有 Main-Class 清单的服务端 jar，入口类由运行库提供。 */
    private fun createMainOnlyJar(target: File, mainClass: String = LaunchProbeMain::class.java.name): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { }
        return target
    }

    /** 创建运行库 jar，把探针入口类放入 libraries 目录模拟 Paper/Folia 依赖布局。 */
    private fun createProbeLibraryJar(target: File): File {
        val resourceName = LaunchProbeMain::class.java.name.replace('.', '/') + ".class"
        val bytes = LaunchProbeMain::class.java.classLoader.getResourceAsStream(resourceName)!!.use { it.readBytes() }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream()).use { output ->
            output.putNextEntry(java.util.jar.JarEntry(resourceName))
            output.write(bytes)
            output.closeEntry()
        }
        return target
    }

    /** 走一遍命令组装，并读出生成的启动器清单里的 `Class-Path` 条目。 */
    private fun assembleClassPath(serverJar: File, workDir: File, key: String = "server"): List<String> {
        ServerLauncher.buildCommand(serverJar, workDir, key, emptyList(), emptyList(), null)
        return readClassPath(provisionClasspathFile(workDir, key))
    }

    /** 读取启动器 jar 清单里的 `Class-Path` 条目（空格分隔）。 */
    private fun readClassPath(launcher: File): List<String> = JarFile(launcher).use { jar ->
        val raw = jar.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH)
            ?: error("启动器缺少 Class-Path 清单项：${launcher.absolutePath}")
        raw.split(" ").filter { it.isNotEmpty() }
    }

    /**
     * 现造一个会立即退出的可运行 jar：Manifest 的 `Main-Class` 指向本测试模块已编译的辅助入口
     * [LaunchProbeMain]，并把其 .class 打入 jar。
     *
     * [LaunchProbeMain] 是 Kotlin 代码，运行时需 kotlin-stdlib；故同时把 stdlib 与本测试模块的
     * classes 目录经 `Class-Path` 清单项接进来（从入口类与某 Kotlin 类的代码源定位），使
     * `java -jar` 能加载到入口与其依赖，独立子进程内正常退出（退出码 0）。
     */
    private fun createImmediateExitJar(target: File): File {
        val entryClassRoot = codeSourceFile(LaunchProbeMain::class.java)
        val kotlinStdlibJar = codeSourceFile(Unit::class.java)
        val classPath = listOf(entryClassRoot, kotlinStdlibJar)
            .map { it.toURI().toString() }
            .joinToString(" ")

        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = LaunchProbeMain::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { /* 仅清单即可，入口经 Class-Path 加载 */ }
        return target
    }

    /** 定位某类的代码源（.class 所在的目录或 jar 文件）。 */
    private fun codeSourceFile(clazz: Class<*>): File =
        File(clazz.protectionDomain.codeSource.location.toURI())
}

/**
 * 测试辅助入口：作为临时可运行 jar 的 `Main-Class`。
 *
 * 打印约定字符串后立即退出（退出码 0），供 [ServerLauncherTest] 验证子进程启动 / 日志捕获 / 收尾。
 */
object LaunchProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("MC_TESTKIT_LAUNCH_OK args=${args.joinToString(",")} jvm=${System.getProperty("mc.testkit.jvm.sentinel")}")
    }
}
