package top.wcpe.mc.testkit.provision

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** 当前是否为 Windows 平台（决定 java 可执行名）。 */
private val isWindows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Paper 的 paperclip 引导入口。
 *
 * paperclip 会自行下载并装载运行库（且会重定位类），编排器替它拼 classpath 反而会打断引导流程，
 * 故识别到该入口时一律保留 `java -jar` 原路径。
 */
private const val PAPERCLIP_MAIN_CLASS = "io.papermc.paperclip.Main"

/**
 * 按 Minecraft 版本选择后端程序参数。
 *
 * Paper/CraftBukkit **1.12.x 及更早**不识别 `--nogui`（启动即打印 help 并退出）；
 * **1.13+** 与 **26.x 新版号方案**（无 `1.` 前缀，如 `26.2`）使用 `--nogui` 关闭图形界面。
 *
 * @param minecraftVersion 如 `1.12.2` / `1.20.1` / `26.2`
 */
fun backendServerArgs(minecraftVersion: String): List<String> {
    val parts = minecraftVersion.trim().split('.')
    val major = parts.getOrNull(0)?.toIntOrNull()
    // 新版号方案（26.x 起）：第一段是年份，不是 1.x 的 minor——一律现代参数
    if (major != null && major >= 26) {
        return listOf("--nogui")
    }
    // 旧方案 1.x：第二段 ≤12 为 1.12 及更早；解析失败时保守使用现代 --nogui
    val minor = parts.getOrNull(1)?.toIntOrNull()
    return if (minor != null && minor <= 12) emptyList() else listOf("--nogui")
}

/**
 * 当前 JVM 自带的 `java` 可执行路径（跨平台：Windows 为 `java.exe`）。
 *
 * 用运行编排插件的同一 JDK 起服务端 / 代理，避免依赖 PATH 上的 `java`（可移植 / 可控）。
 */
internal fun javaExecutable(): String {
    val javaHome = System.getProperty("java.home")
    val name = if (isWindows) "java.exe" else "java"
    val candidate = File(javaHome, "bin").resolve(name)
    return if (candidate.isFile) candidate.absolutePath else name
}

/** 某进程 key 的 pid 文件：`<key>.pid`（供收尾按 pid 杀；与 FR-06 同款思路，本包自带不改 `bot/`）。 */
fun provisionPidFile(runDirectory: File, key: String): File = File(runDirectory, "$key.pid")

/** 某进程 key 的 classpath 启动器：`.mc-testkit-<key>-classpath.jar`（仅运行库场景生成，见 [ServerLauncher]）。 */
internal fun provisionClasspathFile(runDirectory: File, key: String): File =
    File(runDirectory, ".mc-testkit-$key-classpath.jar")

/**
 * 精简的服务端 / 代理启动助手（FR-02）。
 *
 * 只做一件事：给定 jar + 运行目录 + JVM 参数，以**子进程**启动 server/proxy，返回 [Process]，
 * 并把 pid 落盘供收尾（按 pid 杀，保证不残留占端口）。不做就绪等待、不做前台 / 后台编排、
 * 不做集群批量与收尾接线——那是 FR-04 整合器的事（本包只提供启动原语）。
 *
 * 自包含构件（Paper / 代理 jar）走 `java <jvmArgs> -jar <jar> <serverArgs>`；
 * 运行目录下存在 `libraries/` 的 thin jar 构件（如部分 Folia / Forge 系）走
 * `java <jvmArgs> -cp <启动器 jar> <Main-Class> <serverArgs>`，启动器 jar 只带一份
 * `Class-Path` 清单，把服务端 jar 与全部运行库接进来（同时规避 Windows 命令行长度限制）。
 *
 * 在 [runDirectory] 运行（cwd）。日志重定向到运行目录下 `<key>.log`，合并 stderr。
 * 不在此连真服 / 判定（结果以桩写出的结果文件为权威，见 verify/）。
 */
object ServerLauncher {

    /**
     * 后台启动一个服务端 / 代理进程。
     *
     * @param jar 要运行的 jar（由 [ServerJarProvisioner] 解析得到）。
     * @param runDirectory 运行目录（进程 cwd；不存在则创建）。
     * @param key 进程标识（作日志 `<key>.log` / pid `<key>.pid` 的 key，如后端 / 代理节点名）。
     * @param jvmArgs 追加的 JVM 参数（如 `-Xmx1G`；放在 `-jar` / `-cp` 之前）。
     * @param serverArgs 追加的程序参数（如 Paper 的 `--nogui`；放在 jar / 主类之后）。
     * @param environment 追加 / 覆盖的环境变量。
     * @param javaPath 指定 `java` 可执行路径（FR-21 多版本 Java 选择）；null 时用当前 JVM（[javaExecutable]）。
     * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
     * @return 已启动的 [Process]。
     */
    fun launch(
        jar: File,
        runDirectory: File,
        key: String,
        jvmArgs: List<String> = emptyList(),
        serverArgs: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        javaPath: String? = null,
        logger: (String) -> Unit = {},
    ): Process {
        require(jar.isFile) { "要运行的 jar 不存在：${jar.absolutePath}。" }
        runDirectory.mkdirs()

        val logFile = File(runDirectory, "$key.log")
        val pidFile = provisionPidFile(runDirectory, key)
        // 清理上轮残留日志 / pid / classpath 启动器，避免读到旧运行的输出或误用旧依赖清单
        if (logFile.exists()) logFile.delete()
        if (pidFile.exists()) pidFile.delete()
        provisionClasspathFile(runDirectory, key).takeIf(File::exists)?.delete()

        val command = buildCommand(jar, runDirectory, key, jvmArgs, serverArgs, javaPath, logger)

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(runDirectory)
        // 先把 stderr 并入 stdout，再把 stdout 追加到日志文件——故进程 stdout + stderr 都落 <key>.log，
        // 子进程崩溃栈也不会丢（两者顺序不可颠倒：redirectErrorStream 使 redirectError 失效，须靠 stdout 落盘）。
        processBuilder.redirectErrorStream(true)
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        processBuilder.environment().putAll(environment)

        val process = processBuilder.start()
        // pid 落盘失败则进程无法被按 pid 收尾——宁可立即强杀刚起的进程，也不留下无法收尾的孤儿（收尾红线）
        try {
            pidFile.writeText(process.pid().toString())
        } catch (ex: Exception) {
            process.destroyForcibly()
            throw IllegalStateException("无法写入 pid 文件 ${pidFile.absolutePath}，已强制结束刚启动的进程以免残留。", ex)
        }
        logger("已启动进程：key=$key pid=${process.pid()} jar=${jar.name} log=${logFile.absolutePath}")
        return process
    }

    /**
     * 组装服务端启动命令。
     *
     * 自包含 jar 走 `java -jar`；thin jar（有 `Main-Class` 且运行目录下有 `libraries/`）改为
     * 经启动器 jar 传完整 classpath。选路结果写入 [logger]，便于排查“服务端为何没起来”。
     */
    internal fun buildCommand(
        jar: File,
        runDirectory: File,
        key: String,
        jvmArgs: List<String>,
        serverArgs: List<String>,
        javaPath: String?,
        logger: (String) -> Unit = {},
    ): List<String> {
        val executable = javaPath ?: javaExecutable()
        val mainClass = readMainClass(jar)
        val libraries = runtimeLibraries(runDirectory)
        // 无入口类 / 无运行库时按自包含 jar 处理；paperclip 必须自己引导（见 PAPERCLIP_MAIN_CLASS）
        if (mainClass == null || mainClass == PAPERCLIP_MAIN_CLASS || libraries.isEmpty()) {
            logger("按自包含 jar 启动：key=$key 主类=${mainClass ?: "无"} 运行库=${libraries.size} 个")
            return buildList {
                add(executable)
                addAll(jvmArgs)
                add("-jar")
                add(jar.absolutePath)
                addAll(serverArgs)
            }
        }

        val classpathJar = writeClasspathManifest(runDirectory, key, listOf(jar) + libraries)
        logger("按运行库 classpath 启动：key=$key 主类=$mainClass 运行库=${libraries.size} 个")
        return buildList {
            add(executable)
            addAll(jvmArgs)
            add("-cp")
            add(classpathJar.absolutePath)
            add(mainClass)
            addAll(serverArgs)
        }
    }

    /**
     * 收集运行目录 `libraries/` 下的运行库 jar（thin jar 服务端把依赖拆到这里）。
     *
     * 按相对路径升序排序，保证多次运行与多机器之间的装载顺序稳定（出现同名类时不靠运气）。
     */
    private fun runtimeLibraries(runDirectory: File): List<File> {
        val librariesDirectory = File(runDirectory, "libraries").takeIf(File::isDirectory) ?: return emptyList()
        return librariesDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy { it.relativeTo(runDirectory).path.replace(File.separatorChar, '/') }
            .toList()
    }

    /**
     * 读取服务端 jar 的 `Main-Class`。
     *
     * 读不出清单（非 zip / 损坏 / 无清单项）时返回 null，交由 `java -jar` 原路径让子进程自己去报错——
     * 编排器不该因为读不出清单就拒绝启动一个原本能启动的 jar。
     */
    private fun readMainClass(jar: File): String? = try {
        JarFile(jar).use { file ->
            file.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS)?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (_: IOException) {
        null
    }

    /** 写入只含清单的 classpath 启动器，清单中的路径相对该文件所在运行目录解析。 */
    private fun writeClasspathManifest(runDirectory: File, key: String, entries: List<File>): File {
        val launcher = provisionClasspathFile(runDirectory, key)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ") { entry ->
                classpathEntry(runDirectory, entry)
            }
        }
        JarOutputStream(launcher.outputStream(), manifest).use { /* 只需清单，classpath 由 JVM 解析 */ }
        return launcher
    }

    /**
     * 单个 `Class-Path` 条目：同盘文件用相对路径，缓存 jar 位于其它盘符时退回 file URL
     * （[File.toURI] 已完成百分号编码）。
     *
     * 清单的 `Class-Path` 以空格分隔且**没有转义机制**，路径含空格 / 中文时必须按 UTF-8 百分号编码，
     * 否则 JVM 会在空格处截断条目，表现为莫名的 `ClassNotFoundException`。
     */
    private fun classpathEntry(runDirectory: File, entry: File): String {
        val relative = relativeClasspath(runDirectory, entry)
        return if (relative == null) entry.toURI().toString() else percentEncode(relative)
    }

    /** 运行库相对运行目录的路径（`/` 分隔）；跨盘符等无法相对化时返回 null。 */
    private fun relativeClasspath(runDirectory: File, entry: File): String? = try {
        runDirectory.toPath().toAbsolutePath().normalize()
            .relativize(entry.toPath().toAbsolutePath().normalize())
            .toString()
            .replace(File.separatorChar, '/')
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 按 UTF-8 百分号编码相对路径，保留 `/` 分隔符。 */
    private fun percentEncode(relativePath: String): String =
        URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%2F", "/")
}
