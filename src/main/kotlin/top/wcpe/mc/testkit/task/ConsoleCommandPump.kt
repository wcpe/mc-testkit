package top.wcpe.mc.testkit.task

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** 控制台命令转发线程名（便于线程转储里认出）。 */
internal const val CONSOLE_PUMP_THREAD_NAME = "mc-testkit-console-pump"

/**
 * 把 [source] 的输入**逐行**转发到 [sink]（保持输入顺序），用于把开发者在 Gradle 终端敲的
 * 控制台命令投给前台被 `serve` 挂住的服务端 / 代理进程的 stdin。
 *
 * 之所以需要它：`ProcessBuilder` 只把子进程 stdout/stderr 接到日志文件，**stdin 没有任何接线**，
 * 终端输入不会自己到达服务端控制台——serve 挂住后敲 `stop` / `say` 全无反应，只能另开终端跑
 * `stop<Key>Serve` 或 Ctrl+C（[ServerLauncher] 不设 stdin 重定向，见其 launch）。
 *
 * 结束路径都是**安静**的，不刷屏、不抛：
 * - 来源 EOF（`./gradlew serveDev < /dev/null`、CI 无终端、Gradle 会话结束）：正常返回；
 * - 目标进程已退出（写其 stdin 必然 `Stream closed` / `Broken pipe`）：正常返回（收尾路径）；
 * - 线程被 [Thread.interrupt]：正常返回。
 * 只有**目标仍存活**时的 IO 失败才是真问题，经 [logger] 中文告警。
 *
 * 线程为 daemon（不阻塞 JVM 退出）。注意：阻塞在 `readLine` 上时不受 interrupt 影响，
 * 故线程可能在收尾后仍短暂留在读上，直到下一次输入或流关闭自行退出——daemon 属性使其无害。
 *
 * @param source 命令来源（serve 传 `System.in`）。
 * @param sink 目标进程 stdin（`process.outputStream`）。
 * @param threadName 线程名。
 * @param logger 中文告警输出（真实错误才调用）。
 * @param targetAlive 目标是否仍存活（决定 IO 失败是"正常收尾"还是"真问题"）。
 * @return 已启动的守护线程（调用方在收尾时 interrupt）。
 */
internal fun startConsoleCommandPump(
    source: InputStream,
    sink: java.io.OutputStream,
    threadName: String = CONSOLE_PUMP_THREAD_NAME,
    logger: (String) -> Unit = {},
    targetAlive: () -> Boolean = { true },
): Thread {
    val thread = Thread {
        val writer = OutputStreamWriter(sink, StandardCharsets.UTF_8)
        try {
            val reader = BufferedReader(InputStreamReader(source, StandardCharsets.UTF_8))
            while (!Thread.currentThread().isInterrupted) {
                // EOF：无终端 / 输入结束——正常结束（不视为错误）
                val line = reader.readLine() ?: break
                if (!targetAlive()) break
                // 逐行落盘并 flush：控制台命令须即时到达，不能攒在缓冲里
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: IOException) {
            // 目标已退出时写入必失败（Stream closed / Broken pipe）——属收尾正常路径；
            // 仅在目标仍存活时才是真问题（值得告警）。
            if (targetAlive()) {
                logger("控制台命令转发中断：${ex.message ?: ex.javaClass.simpleName}")
            }
        } finally {
            // 不关闭 sink：它归子进程所有，进程收尾时随进程一起归还，关它反而干扰正常收尾路径
            runCatching { writer.flush() }
        }
    }
    thread.isDaemon = true
    thread.name = threadName
    thread.start()
    return thread
}
