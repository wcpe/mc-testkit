package top.wcpe.mc.testkit.task

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** 服务端日志跟随线程名（便于线程转储里认出）。 */
internal const val SERVE_LOG_TAIL_THREAD_NAME = "mc-testkit-serve-log-tail"

/** 跟随日志的轮询间隔：读到末尾后歇一下再读（服务端仍在写）。 */
private const val LOG_TAIL_POLL_MILLIS = 300L

/** 等日志文件出现的最长时间。 */
private const val LOG_TAIL_WAIT_FILE_MILLIS = 10_000L

/** 一次性回放日志区间的字节上限（起服日志通常远小于它；超限只回放前一段并提示看原文件）。 */
private const val LOG_REGION_PRINT_LIMIT_BYTES = 4L * 1024 * 1024

/**
 * 读日志文件的 `[from, to)` 字节区间并按 UTF-8 解码（区间超出文件尾时只读到现有内容）。
 *
 * 按**字节**定位是有意的：日志是字节流，只有字节偏移才能准确跳过某个「不该出现在控制台」的区间
 * （如抓取服务端命令表时那条 `help` 的输出）。
 */
internal fun readLogRegion(logFile: File, from: Long, to: Long): String {
    if (!logFile.exists() || to <= from || from < 0) return ""
    val end = minOf(to, logFile.length())
    if (end <= from) return ""
    val buffer = ByteArray((end - from).toInt())
    RandomAccessFile(logFile, "r").use { file ->
        file.seek(from)
        file.readFully(buffer)
    }
    return String(buffer, StandardCharsets.UTF_8)
}

/**
 * 把偏移对齐到行首（向后找到最近的换行之后）。
 *
 * 用途：日志跟随按字节 skip，起点必须落在行首，否则控制台第一行会是半行残句。
 * 以文件结束位置为典型输入——日志按行落盘，末字节通常是换行，此调用退化为原值返回（O(1)）。
 * 区间 `[floor, offset)` 内找不到换行时返回 [floor]（宁可多打一行残句，也不丢内容）。
 */
internal fun alignToLineStart(logFile: File, offset: Long, floor: Long = 0): Long {
    if (!logFile.exists() || offset <= floor) return floor
    val end = minOf(offset, logFile.length())
    if (end <= floor) return floor
    val buffer = ByteArray(8192)
    var probe = end - 1
    RandomAccessFile(logFile, "r").use { file ->
        while (probe >= floor) {
            val start = maxOf(floor, probe - buffer.size + 1)
            val count = (probe - start + 1).toInt()
            file.seek(start)
            file.readFully(buffer, 0, count)
            for (index in count - 1 downTo 0) {
                if (buffer[index] == '\n'.code.toByte()) return start + index + 1
            }
            probe = start - 1
        }
    }
    return floor
}

/**
 * 一次性把日志区间 `[from, to)` 回放到控制台（serve 起服阶段那段日志）。
 *
 * 与 [startServeLogTail] 配合使用：`[0, from)` 由本函数回放，`[to, …)` 交给跟随线程，
 * 中间 `[from, to)` 是有意不回放的「隐藏窗口」（抓取命令表时服务端打印的 help）。
 *
 * @param onLine 逐行回调（已去掉行尾 `\r`）。
 * @param onTruncated 区间超过 [maxBytes] 时的中文提示回调。
 * @param maxBytes 单次回放上限（保护内存：起服日志通常远小于它）。
 */
internal fun printLogRegion(
    logFile: File,
    from: Long,
    to: Long,
    onLine: (String) -> Unit,
    onTruncated: (String) -> Unit = {},
    maxBytes: Long = LOG_REGION_PRINT_LIMIT_BYTES,
) {
    if (!logFile.exists() || to <= from) return
    val limit = from + maxBytes
    val region = readLogRegion(logFile, from, minOf(to, limit))
    if (region.isEmpty()) return
    region.lineSequence().forEach { raw ->
        val line = raw.trimEnd('\r')
        if (line.isNotEmpty()) onLine(line)
    }
    if (to > limit) {
        onTruncated("起服日志过长（超过 ${maxBytes / 1024 / 1024} MB），控制台只回放了前一段，完整内容见 ${logFile.absolutePath}")
    }
}

/**
 * 从 [startOffset] 字节起跟随服务端日志，逐行回调（daemon 线程，与 serve 挂起同生命周期）。
 *
 * 与「从头读」的差异只有起点：serve 在抓完命令表后才起跟随，起点即隐藏窗口之后，
 * 于是那几十行 `help` 不会刷进控制台，而之后的日志（玩家活动 / 切服 / 报错）照常实时可见。
 *
 * @param startOffset 起始字节偏移（须由 [alignToLineStart] 对齐到行首）。
 * @param onLine 逐行回调。
 * @param onError 收尾路径例外（文件被清理 / 进程退出）的中文 debug 记录出口。
 */
internal fun startServeLogTail(
    logFile: File,
    startOffset: Long = 0,
    onLine: (String) -> Unit,
    onError: (String) -> Unit = {},
    threadName: String = SERVE_LOG_TAIL_THREAD_NAME,
): Thread {
    val thread = Thread {
        try {
            var waited = 0L
            while (!logFile.exists() && waited < LOG_TAIL_WAIT_FILE_MILLIS && !Thread.currentThread().isInterrupted) {
                Thread.sleep(200)
                waited += 200
            }
            if (!logFile.exists()) return@Thread
            FileInputStream(logFile).use { stream ->
                // skip 是字节语义：起点已对齐到行首，故不会从半行/半个 UTF-8 字符中间开始
                var remaining = maxOf(0L, startOffset)
                while (remaining > 0) {
                    val skipped = stream.skip(remaining)
                    if (skipped <= 0) break
                    remaining -= skipped
                }
                // 按 UTF-8 读（Paper 写 UTF-8）；用平台默认字符集会把中文读乱码（实测 Windows GBK 控制台）
                val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine()
                    if (line == null) {
                        Thread.sleep(LOG_TAIL_POLL_MILLIS)
                    } else {
                        onLine(line)
                    }
                }
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            onError("serve 日志跟随结束：${ex.message ?: ex.javaClass.simpleName}")
        }
    }
    thread.isDaemon = true
    thread.name = threadName
    thread.start()
    return thread
}
