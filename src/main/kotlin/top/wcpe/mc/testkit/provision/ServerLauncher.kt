package top.wcpe.mc.testkit.provision

import java.io.File

/** 当前是否为 Windows 平台（决定 java 可执行名）。 */
private val isWindows: Boolean
    get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

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

/**
 * 精简的服务端 / 代理启动助手（FR-02）。
 *
 * 只做一件事：给定 jar + 运行目录 + JVM 参数，以**子进程**启动 server/proxy，返回 [Process]，
 * 并把 pid 落盘供收尾（按 pid 杀，保证不残留占端口）。不做就绪等待、不做前台 / 后台编排、
 * 不做集群批量与收尾接线——那是 FR-04 整合器的事（本包只提供启动原语）。
 *
 * 命令形如 `java <jvmArgs> -jar <jar> <serverArgs>`，在 [runDirectory] 运行（cwd）。
 * 日志重定向到运行目录下 `<key>.log`，合并 stderr。不在此连真服 / 判定（结果以桩写出的
 * 结果文件为权威，见 verify/）。
 */
object ServerLauncher {

    /**
     * 后台启动一个服务端 / 代理进程。
     *
     * @param jar 要运行的 jar（由 [ServerJarProvisioner] 解析得到）。
     * @param runDirectory 运行目录（进程 cwd；不存在则创建）。
     * @param key 进程标识（作日志 `<key>.log` / pid `<key>.pid` 的 key，如后端 / 代理节点名）。
     * @param jvmArgs 追加的 JVM 参数（如 `-Xmx1G`；放在 `-jar` 之前）。
     * @param serverArgs 追加的程序参数（如 Paper 的 `--nogui`；放在 jar 之后）。
     * @param environment 追加 / 覆盖的环境变量。
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
        logger: (String) -> Unit = {},
    ): Process {
        require(jar.isFile) { "要运行的 jar 不存在：${jar.absolutePath}。" }
        runDirectory.mkdirs()

        val logFile = File(runDirectory, "$key.log")
        val pidFile = provisionPidFile(runDirectory, key)
        // 清理上轮残留日志 / pid，避免读到旧运行的输出
        if (logFile.exists()) logFile.delete()
        if (pidFile.exists()) pidFile.delete()

        val command = buildList {
            add(javaExecutable())
            addAll(jvmArgs)
            add("-jar")
            add(jar.absolutePath)
            addAll(serverArgs)
        }

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
}
