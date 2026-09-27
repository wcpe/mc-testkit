package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.contract.toTaskKey
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * serve 控制台的「行级」交互能力：Tab 补全 + 命令历史。
 *
 * **为什么是行级（而不是像原版控制台那样逐键）**：Gradle 的终端由**客户端进程**持有，构建跑在
 * 守护进程里（`System.console()` 为 null），拿到的只是用户回车后的**整行**——终端自己回显、自己
 * 处理退格，也无法把终端切到原始模式。于是服务端自己那套 JLine 补全（含参数补全 / 行内编辑）
 * 在管道 stdin 下天然不可用（实测 Paper 会打印 `Advanced terminal features are not available in
 * this environment`）。可用的一条路是：用户按下的 Tab 会作为 `\t` 落进这一行，框架据此把
 * 「Tab 之前的内容」当补全请求处理。取舍与备选见 `docs/adr/0021-serve-console-line-completion.md`。
 */

/** 补全候选展示上限（超出只报数量，避免刷屏）。 */
internal const val CONSOLE_MAX_CANDIDATES = 20

/** 历史条数上限（跨轮次持久化）。 */
internal const val CONSOLE_HISTORY_LIMIT = 50

/** 从服务端 `help` 输出里最多认多少条命令（防异常输出把候选池撑爆）。 */
internal const val CONSOLE_MAX_COMMANDS = 2000

/** 抓取命令表时最多翻几页（平台若分页则逐页取，直到没有新命令）。 */
internal const val CONSOLE_DUMP_MAX_PAGES = 8

/** 抓取命令表时判定「输出写完了」的静默窗口。 */
internal const val CONSOLE_DUMP_QUIET_MILLIS = 200L

/** 抓取命令表时单页的最长等待。 */
internal const val CONSOLE_DUMP_TIMEOUT_MILLIS = 3_000L

/** 隐藏窗口自愈的最多轮次：静默点之后又冒出命令表时，继续往后吃（有界，防异常输出把等待拖长）。 */
internal const val CONSOLE_DUMP_ABSORB_ROUNDS = 4

/** 抓取命令表用的控制台命令（分页时补页码）。 */
private const val CONSOLE_HELP_COMMAND = "help"

/** ↑ / ↓ 方向键序列：终端按行转发，方向键会原样落进这一行。 */
private const val ARROW_UP = "\u001B[A"
private const val ARROW_DOWN = "\u001B[B"

/** 整行只由方向键组成（允许交错，逐个累计「往回几步」）。 */
private val ARROWS_ONLY = Regex("^(?:\u001B\\[A|\u001B\\[B)+$")

/** 日志里的 ANSI 颜色序列（代理侧控制台即使关 ANSI 也可能带色）。 */
private val ANSI_SEQUENCE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

/** `help` 输出里的命令条目：行首或空白后的 `/名字`，其后跟冒号 / 空白 / 行尾（避免把 URL 当命令）。 */
private val COMMAND_ENTRY = Regex("""(?:^|\s)/([A-Za-z0-9_-]+)(?=:|\s|$)""")

/** 一行输入的处理计划：要打印的中文反馈 + 要不要下发命令（`null` = 只打印）。 */
internal data class ConsolePlan(val feedback: List<String> = emptyList(), val command: String? = null)

/**
 * 服务端命令表抓取结果。
 *
 * @param commands 抓到的命令名（可能为空：平台控制台没有 `help` 命令，如 BungeeCord / Velocity）。
 * @param windowStart 隐藏窗口的起点（起服日志与窗口的分界：`[0, windowStart)` 该照常回放）。
 * @param tailStart 隐藏窗口之后的偏移（日志跟随从这里开始）。
 */
internal data class ServeCommandSnapshot(val commands: List<String>, val windowStart: Long, val tailStart: Long)

/**
 * 把终端来的**一整行**翻译成「打印什么 / 下发什么」（纯函数，可穷举单测）。
 *
 * 规则（与 shell 的直觉对齐）：
 * - 普通行 → 原样下发（框架不解析、不代答，命令即服务端语义）；
 * - 含 `\t` → 补全请求：唯一命中即补全并下发；多候选只列候选、**不下发**（用户正是在问"有哪些"）；
 *   一个都没命中时按「Tab 没反应 + 回车」照常下发（仅当 Tab 位于行尾，之后还有内容则不猜）；
 * - 整行只有 ↑/↓ → 历史：调出并下发（本终端无法把历史塞回编辑缓冲，取 shell 语义）；
 * - 含其它控制序列（←/→/Home/End/Delete）→ **不下发**并说明（原样下发只会让服务端报未知命令）。
 */
internal fun planConsoleLine(rawLine: String, commands: List<String>, history: List<String>): ConsolePlan {
    // 注意：不能先 trimEnd()——行尾那个 Tab 正是「补全请求」的信号，裁掉它补全就识别不出来了
    val line = rawLine.trimEnd(' ')
    if (line.isBlank()) {
        // 空行：原版控制台只是重画提示符；这里什么都不做，也不给服务端发空命令
        return ConsolePlan()
    }
    if (ARROWS_ONLY.matches(line)) return planHistoryNavigation(line, history)
    if (line.indexOf('\u001B') >= 0) {
        return ConsolePlan(
            listOf(
                "本终端不支持左右键 / Home / End / Delete 编辑（Gradle 控制台按行转发、不做行内编辑），" +
                    "该行未下发；请用退格修改，或 Ctrl+U 清行后重输。",
            ),
        )
    }
    val tabIndex = line.indexOf('\t')
    return if (tabIndex >= 0) planCompletion(line, tabIndex, commands, history) else ConsolePlan(command = line)
}

/** 历史导航：↑ 往回、↓ 往前，净位移决定调出第几条。 */
private fun planHistoryNavigation(line: String, history: List<String>): ConsolePlan {
    var depth = 0
    var index = 0
    while (index < line.length) {
        when {
            line.startsWith(ARROW_UP, index) -> {
                depth++
                index += ARROW_UP.length
            }

            line.startsWith(ARROW_DOWN, index) -> {
                depth--
                index += ARROW_DOWN.length
            }

            else -> index++
        }
    }
    if (history.isEmpty()) return ConsolePlan(listOf("还没有历史命令。"))
    if (depth <= 0) {
        return ConsolePlan(listOf("已在最新命令之后（↑ 调出更早的命令，历史共 ${history.size} 条）。"))
    }
    val recalled = history.getOrNull(depth - 1)
        ?: return ConsolePlan(listOf("没有更早的历史命令了（历史上限 $CONSOLE_HISTORY_LIMIT 条）。"))
    return ConsolePlan(
        feedback = listOf("↺ 历史（上 $depth 条）：$recalled"),
        // 终端不给我们编辑缓冲，故语义取「调出并下发」——与 shell 里「↑ + 回车 = 重跑上一条」一致
        command = recalled,
    )
}

/** 补全请求（Tab）：见 [planConsoleLine] 的规则说明。 */
private fun planCompletion(line: String, tabIndex: Int, commands: List<String>, history: List<String>): ConsolePlan {
    val prefix = line.substring(0, tabIndex).trim()
    val tail = line.substring(tabIndex).trim('\t').trim()
    val candidates = completeCandidates(prefix, commands, history)
    if (candidates.isEmpty()) {
        return when {
            tail.isNotEmpty() -> ConsolePlan(listOf("没有匹配的补全候选，且 Tab 之后还有内容——该行未下发（先补全再回车）。"))
            prefix.isEmpty() -> ConsolePlan(
                listOf("暂无补全候选：还没拿到服务端命令表（该平台控制台可能没有 help 命令），也没有历史命令。"),
            )
            // Tab 一个都没补到：等价 shell 里「Tab 没反应 + 回车」，把用户敲的内容照常下发
            else -> ConsolePlan(command = prefix)
        }
    }
    if (candidates.size == 1 && tail.isEmpty()) {
        return ConsolePlan(listOf("Tab 补全并发下：${candidates.single()}"), command = candidates.single())
    }
    val header = buildString {
        append("补全候选（${candidates.size} 个")
        if (candidates.size > CONSOLE_MAX_CANDIDATES) append("，只列前 $CONSOLE_MAX_CANDIDATES")
        append("）：")
    }
    return ConsolePlan(
        feedback = listOf(header) + candidates.take(CONSOLE_MAX_CANDIDATES).map { "  $it" } +
            listOf("该行未下发：请按候选补全后重发。"),
    )
}

/**
 * 补全候选（纯函数）。
 *
 * 两类输入、两类候选：
 * - **还没敲空格**（在敲命令名）：候选 = 服务端命令表 + 你自己用过命令的首词；
 * - **已敲到参数**（含空格）：候选 = 你自己用过的整行——参数补全（玩家名 / 世界名等）要靠服务端
 *   自己的命令树，管道 stdin 下服务端给不出（它没有终端），故这里退化为「你的历史用法」。
 */
internal fun completeCandidates(prefix: String, commands: List<String>, history: List<String>): List<String> {
    val text = prefix.trim()
    if (text.isEmpty()) return commands.sorted()
    if (text.contains(' ')) {
        return history.filter { it.startsWith(text) && it.length > text.length }.distinct().sorted()
    }
    val byCommand = commands.filter { it.startsWith(text) }
    val byHistory = history.map { it.substringBefore(' ') }.filter { it.startsWith(text) && it.length > text.length }
    return (byCommand + byHistory).distinct().sorted()
}

/**
 * 从服务端 `help` 输出里提取命令名（纯函数，best-effort）。
 *
 * 实测（2026-09-26）：Paper 1.20.1 的控制台 `help` **一次**打印全表（含别名，91 条），每条形如
 * `[00:00:00 INFO]: /advancement: A Mojang provided command.`；BungeeCord 26.1 与 Velocity 3.4.0
 * 的控制台**没有** help 命令（回 `Command not found` / `此命令不存在。`），此时结果为空，
 * Tab 补全退化为只用历史命令。
 */
internal fun parseServerCommandNames(dump: String): List<String> {
    if (dump.isEmpty()) return emptyList()
    val names = LinkedHashSet<String>()
    ANSI_SEQUENCE.replace(dump, "").lineSequence().forEach { line ->
        COMMAND_ENTRY.findAll(line).forEach { match ->
            if (names.size < CONSOLE_MAX_COMMANDS) names += match.groupValues[1]
        }
    }
    return names.sorted()
}

/**
 * serve 控制台的命令历史，跨轮次持久化（同一 serve 名下次再跑还能 ↑ 调出来）。
 *
 * 落 `<结果目录>/serve-console-history-<serve>.txt`：结果目录不参与运行目录清理（与进程台账同处），
 * 故历史能跨轮次存活；文件缺失 / 读失败一律当空历史（不阻断 serve）。
 */
internal class ServeConsoleHistory(private val file: File, private val limit: Int = CONSOLE_HISTORY_LIMIT) {

    /** 旧 → 新。 */
    private val entries = ArrayList<String>()

    /** 近 → 远的历史（↑ 取第 1 条即最近一条）。 */
    fun recent(): List<String> = entries.asReversed().toList()

    /** 读盘；缺失 / 读失败按空历史处理（不抛）。 */
    fun load(): ServeConsoleHistory {
        entries.clear()
        runCatching {
            if (!file.isFile) return@runCatching
            file.readLines(StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeLast(limit)
                .forEach { entries += it }
        }
        return this
    }

    /** 记一条并落盘（相邻重复不记，与 shell 的历史一致）。 */
    fun record(command: String) {
        val text = command.trim()
        if (text.isEmpty() || entries.lastOrNull() == text) return
        entries += text
        while (entries.size > limit) entries.removeAt(0)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(entries.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
        }
    }

    companion object {
        /** 历史文件路径（serve 名折成文件名安全形态）。 */
        fun fileFor(resultsDir: File, serveName: String): File =
            File(resultsDir, "serve-console-history-${serveName.toTaskKey()}.txt")
    }
}

/**
 * serve 控制台会话：把 [planConsoleLine] 的结论落到「打印 + 下发 + 记历史」。
 *
 * 只被转发线程调用（单线程），故历史无需额外同步。
 */
internal class ServeConsoleSession(
    private val commands: List<String>,
    private val history: ServeConsoleHistory,
    private val feedback: (String) -> Unit,
) : ConsoleLineHandler {

    override fun onLine(line: String): String? {
        val plan = planConsoleLine(line, commands, history.recent())
        plan.feedback.forEach(feedback)
        val command = plan.command ?: return null
        history.record(command)
        return command
    }
}

/**
 * 抓取服务端命令表（供 Tab 补全）：发一条 `help`（平台若分页则继续翻页），把输出从日志里读出来解析。
 *
 * **为什么借日志**：管道 stdin 下服务端没有终端，它自己的补全接口不可用；`help` 是唯一能一次性
 * 拿到「原版 + Bukkit + 插件」命令的通道。输出**不回放到控制台**——调用方按返回的窗口边界回放
 * `[0, windowStart)` 并从 [ServeCommandSnapshot.tailStart] 起跟随日志，于是几十行 help 不会刷屏，
 * Tab 却立刻有权威候选。
 *
 * 失败一律退化为「没有命令候选」（返回空列表），不抛：补全是便利能力，不该阻断 serve。
 *
 * @param fromOffset 抓取窗口的起点（通常取抓取前的日志长度）。
 * @param writeCommand 往服务端控制台写一行命令（调用方接到子进程 stdin）。
 */
internal fun captureServerCommands(
    logFile: File,
    fromOffset: Long,
    writeCommand: (String) -> Unit,
    quietMillis: Long = CONSOLE_DUMP_QUIET_MILLIS,
    timeoutMillis: Long = CONSOLE_DUMP_TIMEOUT_MILLIS,
    maxPages: Int = CONSOLE_DUMP_MAX_PAGES,
): ServeCommandSnapshot {
    val windowStart = alignToLineStart(logFile, fromOffset)
    val names = LinkedHashSet<String>()
    var offset = windowStart
    for (page in 1..maxPages) {
        // 写失败（目标进程已退出 / stdin 关闭）不抛：按「没有候选」收工
        runCatching { writeCommand(if (page == 1) CONSOLE_HELP_COMMAND else "$CONSOLE_HELP_COMMAND $page") }
            .onFailure { return ServeCommandSnapshot(emptyList(), windowStart, alignToLineStart(logFile, offset, windowStart)) }
        val end = awaitLogQuiet(logFile, offset, quietMillis, timeoutMillis)
        val pageNames = parseServerCommandNames(readLogRegion(logFile, offset, end))
        val before = names.size
        names += pageNames
        offset = alignToLineStart(logFile, end, windowStart)
        // 该页没有命令条目（平台没有 help 命令）或没带来新命令（已列全）→ 收工
        if (pageNames.isEmpty() || names.size == before) break
    }
    // 收尾再等一次「静默」：负载高的机器上，最后一页的输出可能在单页超时之后才写完——若不补等，
    // 跟随起点会落在窗口中间，剩下的 help 行就会漏进控制台（实测出现过）。
    var windowEnd = awaitLogQuiet(logFile, offset, quietMillis, timeoutMillis)
    // 自愈：即使等过静默，dump 也可能**断续**写入（Paper 的异步日志 + 负载），静默点之后又冒出几行命令表。
    // 故再检查静默点之后是否还有命令表形态的行，有就把窗口继续往后吃（有界轮次），避免漏表刷进控制台。
    var round = 0
    while (round < CONSOLE_DUMP_ABSORB_ROUNDS) {
        val length = if (logFile.exists()) logFile.length() else windowEnd
        val extra = readLogRegion(logFile, windowEnd, length)
        if (extra.isBlank() || parseServerCommandNames(extra).isEmpty()) break
        windowEnd = awaitLogQuiet(logFile, windowEnd, quietMillis, timeoutMillis)
        round++
    }
    return ServeCommandSnapshot(names.toList(), windowStart, alignToLineStart(logFile, windowEnd, windowStart))
}

/** 等日志「静下来」：长度在 [quietMillis] 内不再增长即认为本次输出写完（或到 [timeoutMillis] 超时）。 */
private fun awaitLogQuiet(logFile: File, fromOffset: Long, quietMillis: Long, timeoutMillis: Long): Long {
    val started = System.currentTimeMillis()
    var lastLength = -1L
    var lastChangeAt = started
    while (true) {
        val length = if (logFile.exists()) logFile.length() else 0L
        if (length != lastLength) {
            lastLength = length
            lastChangeAt = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        if ((length > fromOffset && now - lastChangeAt >= quietMillis) || now - started >= timeoutMillis) {
            return length
        }
        Thread.sleep(50)
    }
}
