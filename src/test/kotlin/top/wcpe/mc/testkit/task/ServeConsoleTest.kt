package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * serve 控制台行级能力（[planConsoleLine] / [completeCandidates] / [parseServerCommandNames] /
 * [ServeConsoleHistory] / [captureServerCommands]）单元测试。
 *
 * 覆盖用户实测的两个症状：① 按 Tab 什么都不发生（补全缺失）；② Tab 会作为制表符混进命令导致服务端
 * 报未知命令。全部为纯函数 / 临时文件，不起真实服务端（真实起服属实机维度）。
 */
class ServeConsoleTest {

    private fun tempFile(name: String = "serve-console-history.txt"): File =
        File(File("build/test-serve-console-${System.nanoTime()}").apply { mkdirs() }, name)

    // ---------- 普通行 ----------

    @Test
    @DisplayName("普通行应原样下发，框架不解析不代答")
    @Timeout(30)
    fun sendPlainLineAsIs() {
        val plan = planConsoleLine("say 你好 世界", commands = emptyList(), history = emptyList())
        assertEquals("say 你好 世界", plan.command)
        assertTrue(plan.feedback.isEmpty(), "普通行不该有多余反馈：${plan.feedback}")
    }

    @Test
    @DisplayName("空行应什么都不做（不下发空命令）")
    @Timeout(30)
    fun ignoreBlankLine() {
        val plan = planConsoleLine("   ", commands = emptyList(), history = emptyList())
        assertNull(plan.command)
        assertTrue(plan.feedback.isEmpty())
    }

    // ---------- Tab 补全 ----------

    @Test
    @DisplayName("Tab 唯一命中应补全并把补全结果下发")
    @Timeout(30)
    fun completeAndSendOnSingleCandidate() {
        val plan = planConsoleLine("whi\t", commands = listOf("whitelist", "whisper"), history = emptyList())
        assertNull(plan.command, "两个候选时不该下发")

        val unique = planConsoleLine("whit\t", commands = listOf("whitelist", "whisper"), history = emptyList())
        assertEquals("whitelist", unique.command)
        assertTrue(unique.feedback.single().contains("补全"), "应说明已补全：${unique.feedback}")
    }

    @Test
    @DisplayName("Tab 多候选应只列候选并不下发该行（用户正是在问有哪些）")
    @Timeout(30)
    fun listCandidatesWithoutSendingWhenAmbiguous() {
        val plan = planConsoleLine("whi\t", commands = listOf("whitelist", "whisper"), history = emptyList())
        assertNull(plan.command)
        assertTrue(plan.feedback.first().contains("补全候选（2 个"), "应报候选数：${plan.feedback.first()}")
        assertTrue(plan.feedback.any { it.trim() == "whitelist" } && plan.feedback.any { it.trim() == "whisper" })
        assertTrue(plan.feedback.last().contains("未下发"), "应说明该行未下发：${plan.feedback.last()}")
    }

    @Test
    @DisplayName("Tab 候选超上限时只列前若干条并报总数")
    @Timeout(30)
    fun capDisplayedCandidates() {
        val commands = (1..40).map { "cmd$it" }
        val plan = planConsoleLine("cmd\t", commands = commands, history = emptyList())
        assertNull(plan.command)
        assertTrue(plan.feedback.first().contains("40 个"), "应报总数：${plan.feedback.first()}")
        assertTrue(plan.feedback.first().contains("只列前 $CONSOLE_MAX_CANDIDATES"))
        assertEquals(CONSOLE_MAX_CANDIDATES + 2, plan.feedback.size, "表头 + 前 N 条 + 尾注")
    }

    @Test
    @DisplayName("Tab 位于行尾且无候选时应照常下发（等价 shell 里 Tab 没补到 + 回车）")
    @Timeout(30)
    fun sendTypedTextWhenNothingToComplete() {
        val plan = planConsoleLine("list\t", commands = emptyList(), history = emptyList())
        assertEquals("list", plan.command)
    }

    @Test
    @DisplayName("Tab 之后还有内容时不得猜、不得下发（否则会丢掉用户敲的后半截）")
    @Timeout(30)
    fun neverSendWhenTabHasTrailingText() {
        val plan = planConsoleLine("say hi\tthere", commands = listOf("say"), history = emptyList())
        assertNull(plan.command)
        assertTrue(plan.feedback.last().contains("未下发"))
    }

    @Test
    @DisplayName("已敲到参数位置时按历史整行补全（服务端参数补全在管道 stdin 下拿不到）")
    @Timeout(30)
    fun completeArgumentFromHistory() {
        val plan = planConsoleLine(
            "op Al\t",
            commands = listOf("op"),
            history = listOf("op Alex", "list"),
        )
        assertEquals("op Alex", plan.command)
    }

    @Test
    @DisplayName("命令名候选应同时来自服务端命令表与历史首词，去重且有序")
    @Timeout(30)
    fun mergeCommandAndHistoryCandidates() {
        val candidates = completeCandidates(
            prefix = "lo",
            commands = listOf("locate", "lodestone"),
            history = listOf("lodestone foo", "list", "locate bar"),
        )
        assertEquals(listOf("locate", "lodestone"), candidates)
    }

    @Test
    @DisplayName("空行按 Tab 应列出全部命令表")
    @Timeout(30)
    fun listAllCommandsOnEmptyPrefix() {
        assertEquals(listOf("a", "b"), completeCandidates("", listOf("b", "a"), emptyList()))
    }

    // ---------- 历史 ----------

    @Test
    @DisplayName("↑ 应调出上一条并下发（shell 语义），↑↑ 更早一条")
    @Timeout(30)
    fun recallHistoryOnArrowUp() {
        val history = listOf("stop", "list") // 近 → 远
        val one = planConsoleLine("\u001B[A", commands = emptyList(), history = history)
        assertEquals("stop", one.command)
        assertTrue(one.feedback.single().contains("历史"), "应说明调出的是历史：${one.feedback}")

        val two = planConsoleLine("\u001B[A\u001B[A", commands = emptyList(), history = history)
        assertEquals("list", two.command)
    }

    @Test
    @DisplayName("↑ 后 ↓ 净位移为零时不下发")
    @Timeout(30)
    fun ignoreArrowRoundTrip() {
        val plan = planConsoleLine("\u001B[A\u001B[B", commands = emptyList(), history = listOf("stop"))
        assertNull(plan.command)
        assertTrue(plan.feedback.single().contains("最新命令之后"))
    }

    @Test
    @DisplayName("没有历史 / 越界时应给出中文说明且不下发")
    @Timeout(30)
    fun explainWhenHistoryUnavailable() {
        assertTrue(
            planConsoleLine("\u001B[A", commands = emptyList(), history = emptyList()).let { it.command == null && it.feedback.single().contains("还没有历史") },
        )
        val overflow = planConsoleLine("\u001B[A\u001B[A", commands = emptyList(), history = listOf("stop"))
        assertNull(overflow.command)
        assertTrue(overflow.feedback.single().contains("没有更早"))
    }

    @Test
    @DisplayName("其它控制序列（←/→/Home/End）应不下发并说明本终端不支持行内编辑")
    @Timeout(30)
    fun refuseUnsupportedEditSequences() {
        listOf("\u001B[D", "\u001B[C", "\u001B[H", "\u001B[3~", "abc\u001B[Dd").forEach { line ->
            val plan = planConsoleLine(line, commands = listOf("op"), history = emptyList())
            assertNull(plan.command, "不应把 escape 字节当命令下发：$line")
            assertTrue(plan.feedback.single().contains("不支持"), "应说明原因：${plan.feedback}")
        }
    }

    @Test
    @DisplayName("历史应跨轮次持久化，相邻重复不记，且超上限丢最旧")
    @Timeout(30)
    fun persistHistoryAcrossRounds() {
        val file = tempFile()
        val history = ServeConsoleHistory(file).load()
        history.record("list")
        history.record("list") // 相邻重复不记
        history.record("stop")

        val reloaded = ServeConsoleHistory(file).load()
        assertEquals(listOf("stop", "list"), reloaded.recent(), "近 → 远")

        val capped = ServeConsoleHistory(file, limit = 2).load()
        capped.record("say hi")
        capped.record("op Steve")
        assertEquals(listOf("op Steve", "say hi"), ServeConsoleHistory(file, limit = 2).load().recent())
    }

    @Test
    @DisplayName("历史文件缺失或损坏时应按空历史处理，不抛")
    @Timeout(30)
    fun tolerateMissingOrBrokenHistoryFile() {
        val missing = ServeConsoleHistory(tempFile("absent.txt")).load()
        assertTrue(missing.recent().isEmpty())

        val broken = tempFile("broken.txt").apply { writeText("\n\n   \n") }
        assertTrue(ServeConsoleHistory(broken).load().recent().isEmpty())
    }

    @Test
    @DisplayName("历史文件路径应按 serve 名派生且文件名安全")
    @Timeout(30)
    fun deriveHistoryFilePath() {
        val file = ServeConsoleHistory.fileFor(File("build/mc-testkit/results"), "dev-2")
        assertEquals("serve-console-history-Dev2.txt", file.name)
    }

    // ---------- 服务端命令表解析 ----------

    @Test
    @DisplayName("应从 Paper 的 help 输出里提取命令名（含别名、忽略 ANSI 与 URL）")
    @Timeout(30)
    fun parsePaperHelpDump() {
        val dump = listOf(
            "[00:09:31 INFO]: \u001B[38;5;11m--------- \u001B[38;5;15mHelp: Index \u001B[38;5;11m---------------------------\u001B[0m",
            "[00:09:31 INFO]: /advancement: A Mojang provided command.",
            "[00:09:31 INFO]: /stop: A Mojang provided command.",
            "[00:09:31 INFO]: /xp: A Mojang provided command.",
            "[00:09:31 INFO]: /w: A Mojang provided command.",
            "[00:09:31 INFO]: Use /help [n] to get page n of help.",
            "[00:09:31 INFO]: 见 https://example.com:8080/docs/index.html 的说明",
            "[00:09:31 INFO]: 这与命令无关",
        ).joinToString("\n")

        assertEquals(listOf("advancement", "help", "stop", "w", "xp"), parseServerCommandNames(dump))
    }

    @Test
    @DisplayName("平台没有 help 命令（BungeeCord / Velocity）时提取结果为空，不抛")
    @Timeout(30)
    fun parseEmptyDumpOnPlatformsWithoutHelp() {
        assertTrue(parseServerCommandNames("").isEmpty())
        assertTrue(parseServerCommandNames("[00:00:01 INFO]: Command not found\n").isEmpty())
        assertTrue(parseServerCommandNames("此命令不存在。\n").isEmpty())
    }

    // ---------- 命令表抓取 ----------

    @Test
    @DisplayName("抓取命令表：解析出一页命令、窗口起点即抓取起点、跟随起点对齐到行首")
    @Timeout(60)
    fun captureCommandsFromHelpOutput() {
        val logFile = tempFile("paper.log")
        logFile.writeText("[00:00:00 INFO]: Starting server\n")
        val windowStart = logFile.length()
        var asked = 0

        val snapshot = captureServerCommands(
            logFile = logFile,
            fromOffset = windowStart,
            writeCommand = { line ->
                asked++
                // 模拟服务端：收到 help 就在日志里追加一页命令表（第二页无新命令 → 抓取应收工）
                logFile.appendText("[00:00:01 INFO]: /advancement: A Mojang provided command.\n")
                logFile.appendText("[00:00:01 INFO]: /stop: A Mojang provided command. ($line)\n")
            },
            quietMillis = 50,
            timeoutMillis = 2_000,
        )

        assertEquals(listOf("advancement", "stop"), snapshot.commands)
        assertEquals(windowStart, snapshot.windowStart)
        assertEquals(logFile.length(), snapshot.tailStart, "跟随起点应是窗口结束（末行完整）")
        assertEquals(2, asked, "第一页有收获 → 应再翻一页；第二页无新命令 → 收工")
    }

    @Test
    @DisplayName("抓取命令表：平台没有 help 时只试一页并返回空候选")
    @Timeout(60)
    fun captureYieldsEmptyWhenHelpMissing() {
        val logFile = tempFile("bungee.log")
        logFile.writeText("[00:00:00 INFO]: Listening on 0.0.0.0:25577\n")
        var asked = 0

        val snapshot = captureServerCommands(
            logFile = logFile,
            fromOffset = logFile.length(),
            writeCommand = {
                asked++
                logFile.appendText("[00:00:01 INFO]: Command not found\n")
            },
            quietMillis = 50,
            timeoutMillis = 2_000,
        )

        assertTrue(snapshot.commands.isEmpty())
        assertEquals(1, asked, "没有 help 的平台不该反复翻页")
    }

    @Test
    @DisplayName("抓取命令表：末行不完整时跟随起点回退到该行行首（不丢内容）")
    @Timeout(60)
    fun captureAlignsTailStartToLineStart() {
        val logFile = tempFile("partial.log")
        logFile.writeText("[00:00:00 INFO]: boot\n")
        val partialLineStart = logFile.length()

        val snapshot = captureServerCommands(
            logFile = logFile,
            fromOffset = partialLineStart,
            writeCommand = {
                // 模拟「写到一半」的日志：没有结尾换行
                logFile.appendText("[00:00:01 INFO]: 半行")
            },
            quietMillis = 50,
            timeoutMillis = 2_000,
        )

        assertEquals(partialLineStart, snapshot.tailStart, "应回退到半行行首")
    }

    @Test
    @DisplayName("抓取命令表：写命令失败也不抛（补全是便利能力，不阻断 serve）")
    @Timeout(60)
    fun captureToleratesWriteFailure() {
        val logFile = tempFile("err.log")
        logFile.writeText("boot\n")
        val snapshot = captureServerCommands(
            logFile = logFile,
            fromOffset = logFile.length(),
            writeCommand = { error("stdin 已关闭") },
            quietMillis = 20,
            timeoutMillis = 300,
        )
        assertTrue(snapshot.commands.isEmpty())
    }

    @Test
    @DisplayName("抓取命令表：dump 断续写入（负载高）时隐藏窗口应自愈，不漏表进控制台")
    @Timeout(60)
    fun captureAbsorbsTricklingDump() {
        val logFile = tempFile("trickle.log")
        logFile.writeText("[00:00:00 INFO]: boot\n")
        val windowStart = logFile.length()

        val snapshot = captureServerCommands(
            logFile = logFile,
            fromOffset = windowStart,
            writeCommand = {
                // 第一段：立刻写一半
                logFile.appendText("[00:00:01 INFO]: /advancement: A Mojang provided command.\n")
                // 第二段：隔得比静默窗口更久才写——单靠「等静默」会在这里提前收手，剩下的一半就会漏进控制台
                Thread.sleep(300)
                logFile.appendText("[00:00:02 INFO]: /xp: A Mojang provided command.\n")
            },
            quietMillis = 80,
            timeoutMillis = 2_000,
        )

        val hidden = readLogRegion(logFile, windowStart, snapshot.tailStart)
        assertTrue(hidden.contains("/advancement:"), "第一段应在隐藏窗口内：$hidden")
        assertTrue(hidden.contains("/xp:"), "断续写入的第二段也应被吸收进隐藏窗口：$hidden")
        assertEquals(
            "",
            parseServerCommandNames(readLogRegion(logFile, snapshot.tailStart, logFile.length())).joinToString(),
            "跟随起点之后不该再有命令表内容（否则会刷进控制台）",
        )
        assertEquals(listOf("advancement", "xp"), snapshot.commands)
    }

    @Test
    @DisplayName("会话应把反馈打印出去、把下发命令记入历史、多候选时拦下不下发")
    @Timeout(30)
    fun sessionPrintsFeedbackAndRecordsHistory() {
        val file = tempFile("session-history.txt")
        val printed = mutableListOf<String>()
        val session = ServeConsoleSession(
            commands = listOf("stop", "say"),
            history = ServeConsoleHistory(file).load(),
            feedback = { printed += it },
        )

        assertEquals("stop", session.onLine("stop"), "普通行应原样下发")
        assertEquals("say", session.onLine("sa\t"), "唯一命中应补全并下发")
        assertNull(session.onLine("s\t"), "多候选（say / stop）应拦下不下发")

        assertEquals(listOf("say", "stop"), ServeConsoleHistory(file).load().recent(), "只记真正下发的命令")
        assertTrue(printed.any { it.contains("补全候选") }, "反馈应打印：$printed")
    }
}
