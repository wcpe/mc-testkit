package top.wcpe.mc.testkit.bot

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * 台账里一类进程记录的路径 key（同一 key 重复登记视为「同一逻辑进程的新一轮」，覆盖旧记录）：
 * `backend/<名>`、`proxy/<名>`、`bot/<日志 key>`、`hook/<pid 文件相对结果目录的路径>`。
 */
internal const val LEDGER_KEY_BACKEND = "backend"
internal const val LEDGER_KEY_PROXY = "proxy"
internal const val LEDGER_KEY_BOT = "bot"
internal const val LEDGER_KEY_HOOK = "hook"

/**
 * 进程台账（进程生命周期与收尾 高风险区）：把「本工具起过哪些进程」记到**不被运行目录清理触及**的文件，
 * 使跨轮次逃逸的进程仍能被停止任务收尾。
 *
 * **为什么需要**：pid 文件（`<运行目录>/<key>.pid` / `results/<kind>-<name>.pid`）是「当前一轮」的凭证——
 * 每次起服都会先删旧 pid 再写新的（见 `ServerLauncher.launch`），运行目录清理还会整片删掉它。上一轮
 * Gradle 会话若被强杀（shutdown hook 与 `finally` 都没跑到），进程存活而 pid 记录已随下一轮消失，
 * 于是 `stop<Key>Serve` 按 pid 收尾时遇到「pid 文件不存在」→ 静默 no-op，**残留进程永远收尾不掉**，
 * 且会占着端口让后续每一轮起服都以 `bind(..) failed` 失败。
 *
 * 台账落 `<结果目录>/process-ledger.properties`：该目录不参与运行目录清理，且随 `build/` 一起被
 * `clean` 清掉（与「运行目录可清理重建」的既有语义一致，不引入新的持久化面）。
 *
 * **安全边界**：收尾前核对候选进程的命令行与启动时刻是否都与登记时一致（见
 * [ProcessRecord.matchesLiveProcess]），确认确实是本工具起的那个服务端 / 代理 / 机器人 / 钩子进程，
 * pid 复用（旧 pid 被无关进程占用）时**拒绝杀**并清理陈旧记录——收尾不得误伤用户自己的进程。
 *
 * 用 `java.util.Properties` 存（JDK 原生、无第三方依赖、人可读可手工删）而非 JSON：字段少且扁平，
 * 引 JSON 解析属镀金。读改写整个文件，故各方法在本 JVM 内互斥。
 */
internal object ProcessLedger {

    /** 台账文件名（落结果目录）。 */
    const val FILE_NAME = "process-ledger.properties"

    /** 参数字段在 Properties 中的分隔符（命令参数本身不会由框架拼接 shell）。 */
    private const val ARGUMENT_SEPARATOR = "\u001F"

    /**
     * 台账文件路径。
     *
     * @param resultsDir 结果目录（`<buildDir>/mc-testkit/results`）。
     */
    fun file(resultsDir: File): File = File(resultsDir, FILE_NAME)

    /**
     * 登记一个进程（幂等：同一 key 重复登记即覆盖，代表该逻辑进程的最新一轮）。
     *
     * **启动时刻就地抓取身份**（命令行 + 启动时刻），不由调用方传——两个原因：
     * ① 命令行随启动形态而变（自包含 jar 含 jar 路径，thin jar 只含 classpath 启动器路径），
     *    调用方拿着 jar 路径去猜会漏配 thin jar 场景；② 启动后命令行不变，就地抓取即可精确对应。
     *
     * 写失败不抛——台账是**收尾兜底**的增强，不该因磁盘问题让正常起服失败（此时 pid 文件与
     * shutdown hook 仍在，收尾不受影响）。
     *
     * @param resultsDir 结果目录。
     * @param key 逻辑进程标识（如 `backend/paper1201`、`proxy/wf`、`bot/idle`、`hook/hook-full.pid`）。
     * @param pid 进程号。
     * @param port 监听端口（0 表示无端口，如机器人）。
     */
    @Synchronized
    fun record(resultsDir: File, key: String, pid: Long, port: Int) {
        runCatching {
            val handle = ProcessHandle.of(pid).orElse(null)
            val info = handle?.info()
            val commandLine = info?.commandLine()?.orElse("").orEmpty()
            val command = info?.command()?.orElse("").orEmpty()
            val arguments = info?.arguments()?.map { it.toList() }?.orElse(emptyList()).orEmpty()
            val startedAtMillis = info?.startInstant()?.map { it.toEpochMilli() }?.orElse(0L) ?: 0L
            val file = file(resultsDir)
            file.parentFile?.mkdirs()
            // 读改写整个过程持跨进程文件锁：`@Synchronized` 只挡本 JVM 内的线程。
            // Gradle 可能派多个 worker 进程，两个进程各读旧快照再整表写回时，后写者会冲掉前者的记录
            // —— 丢掉的那条记录对应的进程从此再也收不回来，而且丢记录本身没有任何日志。
            updateLocked(file) { properties ->
                properties[propKey(key, "pid")] = pid.toString()
                properties[propKey(key, "port")] = port.toString()
                properties[propKey(key, "commandLine")] = commandLine
                properties[propKey(key, "command")] = command
                properties[propKey(key, "arguments")] = arguments.joinToString(ARGUMENT_SEPARATOR)
                properties[propKey(key, "startedAtMillis")] = startedAtMillis.toString()
                properties[propKey(key, "recordedAt")] = Instant.now().toString()
                true
            }
        }
    }

    /**
     * 注销一个进程（正常收尾后调用，避免台账无限增长）。
     *
     * 与 [record] 同款：失败不抛。
     */
    @Synchronized
    fun unrecord(resultsDir: File, key: String) {
        runCatching {
            val file = file(resultsDir)
            if (!file.isFile) return
            updateLocked(file) { properties ->
                val removed = properties.keys.map { it.toString() }
                    .filter { it.startsWith("$key.") }
                    .onEach { properties.remove(it) }
                removed.isNotEmpty()
            }
        }
    }

    /**
     * 在跨进程文件锁的保护下读改写台账。
     *
     * 锁文件是 `<台账>.lock`：不锁台账本身，因为台账是用 `Properties.store` 整表重写的
     * （先截断再写），锁一个会被截断的文件没有意义。
     *
     * ## 拿不到锁时为什么不无锁写入
     *
     * 台账的读改写是「读全表 → 改自己那条 → 整表写回」。没有锁时两个进程会各自拿旧快照写回，
     * 后写者把前者的记录整条冲掉——**被冲掉的那条记录对应的进程从此再也收不回来**，而且丢记录
     * 本身毫无日志。这正是加锁要防的事，所以「拿不到锁就无锁写」等于没加锁：并发恰好是最需要
     * 互斥的时刻。
     *
     * 因此改为**阻塞式加锁 + 有限重试**：正常的写耗时是毫秒级，短暂等待即可拿到；真的等不到
     * （持锁进程卡死，或平台不支持文件锁）时**放弃本次写入并告警**，而不是破坏别人的记录。
     * 放弃写入的代价只是少一条兜底记录，进程本身仍由 pid 文件与本构建的监管负责收尾，
     * 不会因此逃逸。
     *
     * @param file 台账文件
     * @param mutate 就地修改属性表；返回 true 表示需要写回
     */
    private fun updateLocked(file: File, mutate: (Properties) -> Boolean) {
        val lockFile = File(file.parentFile, file.name + ".lock")
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            channel = runCatching {
                FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }.getOrNull() ?: return warnSkipped(file, "无法打开锁文件")
            lock = awaitLock(channel) ?: return warnSkipped(file, "等待 ${LOCK_RETRY_BUDGET_MS}ms 仍未拿到锁")
            val properties = load(file)
            if (!mutate(properties)) return
            file.outputStream().use { properties.store(it, LEDGER_HEADER) }
        } finally {
            runCatching { lock?.release() }
            runCatching { channel?.close() }
        }
    }

    /**
     * 在有限预算内反复尝试拿排他锁（`tryLock` 不阻塞，故自旋重试而不是 `lock()` 死等——
     * 后者在持锁进程卡死时会永久阻塞构建）。
     *
     * @return 拿到的锁；预算耗尽或平台抛异常（如不支持文件锁）时返回 null
     */
    private fun awaitLock(channel: FileChannel): FileLock? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_RETRY_BUDGET_MS)
        while (System.nanoTime() < deadline) {
            val attempt = runCatching { channel.tryLock() }
            if (attempt.isFailure) return null // 平台不支持文件锁：不值得继续重试
            val lock = attempt.getOrNull()
            if (lock != null) return lock
            Thread.sleep(LOCK_RETRY_INTERVAL_MS)
        }
        return null
    }

    /** 本次写入被放弃时告警（说明后果与不影响范围，避免用户以为进程逃逸了）。 */
    private fun warnSkipped(file: File, why: String) {
        warn(
            "[mc-testkit] 未能写入进程台账 ${file.absolutePath}：$why。" +
                "本次不记录这条进程信息——它不会因此逃逸（pid 文件与本构建的进程监管照常负责收尾），" +
                "但跨构建按台账兜底时找不回它。若频繁出现，检查是否有进程长时间占用该台账的锁。",
        )
    }

    /**
     * 读取全部台账记录（供停任务遍历兜底收尾）。
     *
     * 文件缺失 / 内容损坏时返回空表（台账是兜底增强，不该因它读不出来而让收尾失败）。
     */
    @Synchronized
    fun records(resultsDir: File): List<ProcessRecord> {
        val file = file(resultsDir)
        if (!file.isFile) {
            return emptyList()
        }
        val properties = load(file)
        return properties.keys.map { it.toString() }
            .filter { it.endsWith(".pid") }
            .mapNotNull { key -> recordOf(properties, key.removeSuffix(".pid")) }
            .distinctBy { it.key }
    }

    /**
     * 从属性表解析单条记录；缺 `pid` 即视为无效记录（返回 null）。
     *
     * 命令行 / 启动时刻缺失时**保留记录但不可匹配**（[ProcessRecord.matchesLiveProcess] 会返回 false）：
     * 它们仍可用于人工排查（记录里有 key 与端口），只是永远不会成为收尾对象——这比直接丢弃更安全也更有用。
     */
    private fun recordOf(properties: Properties, key: String): ProcessRecord? {
        val pid = properties[propKey(key, "pid")]?.toString()?.trim()?.toLongOrNull() ?: return null
        val port = properties[propKey(key, "port")]?.toString()?.trim()?.toIntOrNull() ?: 0
        val commandLine = properties[propKey(key, "commandLine")]?.toString().orEmpty()
        val command = properties[propKey(key, "command")]?.toString().orEmpty()
        val arguments = properties[propKey(key, "arguments")]?.toString()
            ?.split(ARGUMENT_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val startedAtMillis = properties[propKey(key, "startedAtMillis")]?.toString()?.trim()?.toLongOrNull() ?: 0L
        return ProcessRecord(
            key = key,
            pid = pid,
            port = port,
            commandLine = commandLine,
            startedAtMillis = startedAtMillis,
            command = command,
            arguments = arguments,
        )
    }

    /** 读属性表；文件损坏时返回空表（不抛）。 */
    private fun load(file: File): Properties = Properties().apply {
        if (file.isFile) {
            runCatching { file.inputStream().use { load(it) } }
        }
    }

    private fun propKey(key: String, field: String): String = "$key.$field"

    /** 台账文件头（人看：说明这文件是什么、能不能手工删）。 */
    private val LEDGER_HEADER = """
        mc-testkit 进程台账：记录本工具起过的服务端 / 代理 / 机器人 / 钩子进程，供停任务跨轮次兜底收尾。
        可安全手工删除：删除后仅失去「收尾上一轮逃逸进程」的能力，不影响后续起服。
    """.trimIndent()

    /** 等锁的总预算：正常写是毫秒级，2 秒足够跨过任何一次正常竞争的写窗口。 */
    private const val LOCK_RETRY_BUDGET_MS = 2_000L

    /** 两次尝试之间的间隔。 */
    private const val LOCK_RETRY_INTERVAL_MS = 10L

    /**
     * 告警出口。默认落 stderr（`bot/` 不依赖 Gradle API），任务侧可在 `apply` 时接到
     * `project.logger`，让告警进 Gradle 日志而不是混在 stderr 里。
     */
    @Volatile
    private var warningSink: (String) -> Unit = { System.err.println(it) }

    /** 任务侧注入告警出口（可选；不注入也能工作）。 */
    fun useWarningSink(sink: (String) -> Unit) {
        warningSink = sink
    }

    private fun warn(message: String) = warningSink(message)
}

internal data class ProcessRecord(
    val key: String,
    val pid: Long,
    val port: Int,
    val commandLine: String,
    val startedAtMillis: Long,
    /** 结构化命令名（Windows 下比完整 commandLine 更稳定）。 */
    val command: String = "",
    /** 结构化参数（Windows 下比完整 commandLine 更稳定）。 */
    val arguments: List<String> = emptyList(),
) {
    /** 进程是否仍存活。 */
    fun isAlive(): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /** 当前进程是否 Windows（Windows 的命令行 / 启动时刻信息可能比 Unix 不完整）。 */
    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** 比较命令行时折叠 Windows 的大小写与多余空白，避免同一进程快照格式差异导致误拒杀。 */
    private fun normalizeCommandLine(value: String): String =
        if (isWindows) value.trim().replace(Regex("\\s+"), " ").lowercase() else value

    /** 比较 Windows 结构化参数时只折叠大小写，保留参数内部空格语义。 */
    private fun normalizeArgument(value: String): String = if (isWindows) value.lowercase() else value

    fun matchesLiveProcess(): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        val info = handle.info()
        val liveCommandLine = info.commandLine().orElse("")
        val lineMatches = if (commandLine.isNotBlank()) {
            liveCommandLine.isNotBlank() && normalizeCommandLine(liveCommandLine) == normalizeCommandLine(commandLine)
        } else {
            true
        }
        if (!lineMatches) return false
        if (command.isNotBlank()) {
            val liveCommand = info.command().orElse("")
            val liveArguments = info.arguments().map { it.toList() }.orElse(emptyList())
            if (
                liveCommand.isBlank() ||
                normalizeCommandLine(liveCommand) != normalizeCommandLine(command) ||
                liveArguments.map(::normalizeArgument) != arguments.map(::normalizeArgument)
            ) {
                return false
            }
        }
        val liveStartedAt = info.startInstant().map { it.toEpochMilli() }.orElse(0L)
        return startedAtMillis != 0L && liveStartedAt != 0L && liveStartedAt == startedAtMillis
    }
}
