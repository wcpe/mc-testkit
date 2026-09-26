package top.wcpe.mc.testkit.task

import java.io.File
import java.time.Instant
import java.util.Properties

/** 台账里一类进程记录的路径 key（同一 key 重复登记视为「同一逻辑进程的新一轮」，覆盖旧记录）。 */
internal const val LEDGER_KEY_BACKEND = "backend"
internal const val LEDGER_KEY_PROXY = "proxy"
internal const val LEDGER_KEY_BOT = "bot"

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
 * [ProcessRecord.matchesLiveProcess]），确认确实是本工具起的那个服务端 / 代理，
 * pid 复用（旧 pid 被无关进程占用）时**拒绝杀**并清理陈旧记录——收尾不得误伤用户自己的进程。
 *
 * 用 `java.util.Properties` 存（JDK 原生、无第三方依赖、人可读可手工删）而非 JSON：字段少且扁平，
 * 引 JSON 解析属镀金。
 */
internal object ProcessLedger {

    /** 台账文件名（落结果目录）。 */
    const val FILE_NAME = "process-ledger.properties"

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
     * @param key 逻辑进程标识（如 `backend/paper1201`、`proxy/wf`、`bot/idle`）。
     * @param pid 进程号。
     * @param port 监听端口（0 表示无端口，如机器人）。
     */
    fun record(resultsDir: File, key: String, pid: Long, port: Int) {
        runCatching {
            val handle = ProcessHandle.of(pid).orElse(null)
            val commandLine = handle?.info()?.commandLine()?.orElse("").orEmpty()
            val startedAtMillis = handle?.info()?.startInstant()?.map { it.toEpochMilli() }?.orElse(0L) ?: 0L
            val file = file(resultsDir)
            file.parentFile?.mkdirs()
            val properties = load(file)
            properties[propKey(key, "pid")] = pid.toString()
            properties[propKey(key, "port")] = port.toString()
            properties[propKey(key, "commandLine")] = commandLine
            properties[propKey(key, "startedAtMillis")] = startedAtMillis.toString()
            properties[propKey(key, "recordedAt")] = Instant.now().toString()
            file.outputStream().use { properties.store(it, LEDGER_HEADER) }
        }
    }

    /**
     * 注销一个进程（正常收尾后调用，避免台账无限增长）。
     *
     * 与 [record] 同款：失败不抛。
     */
    fun unrecord(resultsDir: File, key: String) {
        runCatching {
            val file = file(resultsDir)
            if (!file.isFile) return
            val properties = load(file)
            val removed = properties.keys.map { it.toString() }
                .filter { it.startsWith("$key.") }
                .onEach { properties.remove(it) }
            if (removed.isEmpty()) return
            file.outputStream().use { properties.store(it, LEDGER_HEADER) }
        }
    }

    /**
     * 读取全部台账记录（供停任务遍历兜底收尾）。
     *
     * 文件缺失 / 内容损坏时返回空表（台账是兜底增强，不该因它读不出来而让收尾失败）。
     */
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
        val startedAtMillis = properties[propKey(key, "startedAtMillis")]?.toString()?.trim()?.toLongOrNull() ?: 0L
        return ProcessRecord(key = key, pid = pid, port = port, commandLine = commandLine, startedAtMillis = startedAtMillis)
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
        mc-testkit 进程台账：记录本工具起过的服务端 / 代理 / 机器人进程，供停任务跨轮次兜底收尾。
        可安全手工删除：删除后仅失去「收尾上一轮逃逸进程」的能力，不影响后续起服。
    """.trimIndent()
}

/**
 * 台账里的一条进程记录。
 *
 * @property key 逻辑进程标识（如 `backend/paper1201`）。
 * @property pid 进程号。
 * @property port 监听端口（0 = 无端口，如机器人）。
 * @property commandLine 登记时的完整命令行（收尾前核对身份用）。
 * @property startedAtMillis 登记时该进程的启动时刻（毫秒；0 = 取不到，此时退化为只比命令行）。
 */
internal data class ProcessRecord(
    val key: String,
    val pid: Long,
    val port: Int,
    val commandLine: String,
    val startedAtMillis: Long,
) {
    /** 进程是否仍存活。 */
    fun isAlive(): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /**
     * 该 pid 当前是否**仍属于本工具起的那一个进程**（决定能不能杀）。
     *
     * pid 会被操作系统复用，故不能只看「pid 存在就杀」——两道核对：
     * 1. **命令行相同**：进程已被回收成别的程序时，pid 的存活状态与命令行都对不上，直接挡住。
     * 2. **启动时刻相同**：挡住「同一 pid 上又起了一个命令行一模一样的进程」（例如用户在同一 jar 上
     *    另开了一个服务端）。只有启动时刻也对得上，才是当初登记的那一个进程实例。
     *
     * 任一项取不到（读不到命令行 / 启动时刻）即判否：宁可漏收尾（停任务会提示按端口手工排查），
     * 也不误杀用户自己的进程。
     */
    fun matchesLiveProcess(): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        val info = handle.info()
        val liveCommandLine = info.commandLine().orElse("")
        if (commandLine.isBlank() || liveCommandLine != commandLine) {
            return false
        }
        // 启动时刻是我们登记时的快照；进程换了一茬（pid 复用 + 同命令行）时它必然不同
        val liveStartedAt = info.startInstant().map { it.toEpochMilli() }.orElse(0L)
        return startedAtMillis != 0L && liveStartedAt == startedAtMillis
    }
}
