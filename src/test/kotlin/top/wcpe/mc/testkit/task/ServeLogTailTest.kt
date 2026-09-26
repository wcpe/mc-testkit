package top.wcpe.mc.testkit.task

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * serve 日志跟随与区间回放（[startServeLogTail] / [printLogRegion] / [alignToLineStart]）单元测试。
 *
 * 这一层服务的场景：抓服务端命令表时 `help` 的输出要留在日志里但**不刷进控制台**，于是日志被切成
 * 「起服段（回放）+ 隐藏窗口（不回放）+ 跟随段（实时）」；偏移必须落在行首，否则控制台第一行是半句。
 */
class ServeLogTailTest {

    private fun tempFile(name: String): File =
        File(File("build/test-serve-log-tail-${System.nanoTime()}").apply { mkdirs() }, name)

    @Test
    @DisplayName("行首对齐：已对齐时原值返回；落在半行时回退到该行行首")
    @Timeout(30)
    fun alignToLineStart() {
        val file = tempFile("align.log").apply { writeText("abc\ndef\n") }
        assertEquals(file.length(), alignToLineStart(file, file.length()), "末字节是换行，无需回退")
        assertEquals(4L, alignToLineStart(file, 6), "应在第二行行内 → 回退到第二行行首")
        assertEquals(0L, alignToLineStart(file, 2), "区间内无换行 → 回退到下限")
        assertEquals(4L, alignToLineStart(file, 6, floor = 4), "下限已是行首 → 直接返回下限")

        val noNewline = tempFile("nonl.log").apply { writeText("abc") }
        assertEquals(0L, alignToLineStart(noNewline, 3), "整文件没有换行 → 回退到 0")
    }

    @Test
    @DisplayName("区间回放：只打该区间、去掉行尾 \\r、空区间不回调")
    @Timeout(30)
    fun printLogRegionOnly() {
        val file = tempFile("region.log").apply { writeText("boot-1\r\nboot-2\nhelp-1\nhelp-2\nlive\n") }
        val printed = mutableListOf<String>()
        printLogRegion(file, from = 0, to = "boot-1\r\nboot-2\n".length.toLong(), onLine = { printed += it })
        assertEquals(listOf("boot-1", "boot-2"), printed, "只回放起服段，且去掉 \\r")

        val empty = mutableListOf<String>()
        printLogRegion(file, from = 5, to = 5, onLine = { empty += it })
        assertTrue(empty.isEmpty())
        printLogRegion(tempFile("absent.log"), from = 0, to = 10, onLine = { empty += it })
        assertTrue(empty.isEmpty(), "文件不存在时安静返回")
    }

    @Test
    @DisplayName("区间超过上限时只回放前一段并中文提示（完整内容看日志文件）")
    @Timeout(30)
    fun printLogRegionTruncatesHugeRegion() {
        val file = tempFile("huge.log").apply {
            writeText((1..200).joinToString("\n") { "line-$it" } + "\n")
        }
        val printed = mutableListOf<String>()
        val notes = mutableListOf<String>()
        // 上限 100 字节 → 只回放前 100 字节（不足 200 行）
        printLogRegion(
            file,
            from = 0,
            to = file.length(),
            onLine = { printed += it },
            onTruncated = { notes += it },
            maxBytes = 100,
        )
        assertTrue(printed.isNotEmpty() && printed.size < 200, "应只回放一段，实际 ${printed.size} 行")
        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("完整内容见"), "提示应指出完整内容位置：${notes.single()}")
    }

    @Test
    @DisplayName("日志跟随：从给定偏移起只回调新增行，daemon 线程")
    @Timeout(60)
    fun followFromOffsetPicksUpOnlyNewLines() {
        val file = tempFile("tail.log").apply { writeText("boot-1\nboot-2\n") }
        val offset = file.length()
        val printed = java.util.Collections.synchronizedList(mutableListOf<String>())

        val tail = startServeLogTail(file, startOffset = offset, onLine = { printed += it })
        try {
            assertTrue(tail.isDaemon, "跟随线程必须为 daemon（否则 serve 收尾后 JVM 无法退出）")
            assertEquals(SERVE_LOG_TAIL_THREAD_NAME, tail.name)

            file.appendText("live-1\n")
            assertTrue(waitUntil { printed.contains("live-1") }, "应读到偏移之后新增的行：$printed")
            assertEquals(listOf("live-1"), printed, "偏移之前的内容不重复回放")
        } finally {
            tail.interrupt()
        }
    }

    @Test
    @DisplayName("日志跟随：起点从半行中间给定时按字节跳过（调用方负责对齐，这里锁定字节语义）")
    @Timeout(60)
    fun followUsesByteOffsetSemantics() {
        val file = tempFile("tail-bytes.log").apply { writeText("前一行\n目标行\n") }
        val targetOffset = "前一行\n".toByteArray(Charsets.UTF_8).size.toLong()
        val printed = java.util.Collections.synchronizedList(mutableListOf<String>())

        val tail = startServeLogTail(file, startOffset = targetOffset, onLine = { printed += it })
        try {
            assertTrue(waitUntil { printed.contains("目标行") }, "多字节字符下按字节跳过仍应落在行首：$printed")
            assertEquals(listOf("目标行"), printed)
        } finally {
            tail.interrupt()
        }
    }

    @Test
    @DisplayName("日志跟随：文件还没出现时等待，出现后正常跟随")
    @Timeout(60)
    fun waitForLogFileToAppear() {
        val file = tempFile("late.log")
        val printed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tail = startServeLogTail(file, onLine = { printed += it })
        try {
            Thread.sleep(300)
            file.writeText("late-1\n")
            assertTrue(waitUntil { printed.contains("late-1") }, "文件出现后应开始跟随：$printed")
        } finally {
            tail.interrupt()
        }
    }

    /** 轮询等待条件成立（上限 10s），避免用固定 sleep 造成偶发失败。 */
    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
