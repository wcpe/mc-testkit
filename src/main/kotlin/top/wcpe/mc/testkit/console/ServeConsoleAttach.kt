package top.wcpe.mc.testkit.console

import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

/**
 * 附加控制台客户端：在**你自己的终端**里运行它，把这个终端接到 serve 里那个服务端的原生控制台上。
 *
 * 它只做三件事：把本终端切到原始模式、把字节在两个方向搬运、退出时把终端恢复。**编辑器与补全完全由
 * 服务端的 JLine 提供**——因此 Tab 补全（含参数）、↑↓ 历史、←→ 行编辑、颜色、Ctrl+C 停服都是原版行为，
 * 与「服务端真的站在一台终端前」完全一致。
 *
 * 为什么要有这么一个客户端进程：把终端切到原始模式、并且搬运按键的，必须是**直接跑在开发者终端里的
 * 进程**——Gradle 守护进程拿不到终端（实测见 ADR-0022），所以 serve 本身做不到，只能提供端点 + 打印命令。
 *
 * 用法（serve 就绪提示里会打印可直接粘贴的完整命令）：
 * ```
 * java -cp "<插件 jar>:<kotlin-stdlib jar>" top.wcpe.mc.testkit.console.ServeConsoleAttach --port <端口> --token <令牌>
 * ```
 * 断开：`Ctrl+]`（只断开附加，服务端继续运行）；要停服请在该控制台里敲 `stop`，或另跑 `stop<Key>Serve`。
 */
object ServeConsoleAttach {

    /** 断开附加而不触发服务端行为：Ctrl+]（0x1D）——终端世界里罕见，MC 控制台也不用它。 */
    private const val DETACH_BYTE = 0x1D

    /** 尺寸上报失败时的占位值（0 = 服务端跳过尺寸同步）。 */
    private const val UNKNOWN_SIZE = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val options = parseOptions(args)
        if (options == null) {
            println(USAGE)
            exitProcess(2)
        }

        // 终端设置先存后改：无论退出路径如何，都要恢复，绝不能把用户的终端留在原始模式
        val saved = stty(listOf("-g"))?.trim()
        if (saved.isNullOrEmpty()) {
            println("附加控制台客户端需要 POSIX 终端：读不到终端设置（stty -g 失败）。请在真实终端里运行本命令，不要重定向 / 走管道。")
            exitProcess(1)
        }

        var failed = false
        try {
            if (stty(listOf("raw", "-echo")) == null) {
                println("无法把终端切换到原始模式（stty raw -echo 失败）。")
                failed = true
                return
            }
            failed = !bridge(options)
        } finally {
            stty(listOf(saved))
            println()
            println(if (failed) "附加中断；服务端仍在运行。" else "已断开附加控制台；服务端仍在运行（要停服可敲 stop 或跑 stop<Key>Serve）。")
        }
        if (failed) exitProcess(1)
    }

    /** 连接端点、握手、双向搬运；返回是否正常结束（服务端退出或用户 Ctrl+] 都算正常）。 */
    private fun bridge(options: Options): Boolean {
        val (rows, cols) = terminalSize()
        Socket().use { socket ->
            return runCatching {
                socket.connect(InetSocketAddress(options.host, options.port), CONNECT_TIMEOUT_MILLIS)
                val toServer = socket.getOutputStream()
                toServer.write("$ATTACH_HANDSHAKE_PREFIX ${options.token} $cols $rows\n".toByteArray())
                toServer.flush()

                val answer = readLine(socket.getInputStream())
                if (answer == null || !answer.startsWith("OK")) {
                    println("附加被拒绝：${answer?.removePrefix("REFUSED: ") ?: "服务端未应答"}")
                    return false
                }

                val fromServer = socket.getInputStream()
                val printer = Thread { runCatching { copyAll(fromServer, System.out) } }.apply {
                    isDaemon = true
                    name = "mc-testkit-attach-print"
                    start()
                }

                println("已接管本终端：Tab 补全 / ↑↓ 历史 / ←→ 编辑 / 颜色均为服务端原生行为；Ctrl+] 断开附加（服务端继续运行）。")
                var detached = false
                val buffer = ByteArray(4096)
                while (!detached) {
                    val read = System.`in`.read(buffer)
                    if (read <= 0) break
                    // 逐字节过滤断开键：命中即停，不把它送进服务端
                    var end = read
                    for (index in 0 until read) {
                        if ((buffer[index].toInt() and 0xFF) == DETACH_BYTE) {
                            end = index
                            detached = true
                            break
                        }
                    }
                    if (end > 0) {
                        toServer.write(buffer, 0, end)
                        toServer.flush()
                    }
                }
                runCatching { socket.close() }
                printer.join(500)
                true
            }.getOrElse { ex ->
                println("附加控制台失败：${ex.message ?: ex.javaClass.simpleName}")
                false
            }
        }
    }

    private fun copyAll(from: java.io.InputStream, to: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = from.read(buffer)
            if (read <= 0) return
            to.write(buffer, 0, read)
            to.flush()
        }
    }

    /** 读一行（不缓冲后续内容）。 */
    private fun readLine(input: java.io.InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < 512) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray())
            if (next == '\n'.code) return String(bytes.toByteArray())
            bytes.add(next.toByte())
        }
        return null
    }

    /** 本终端尺寸（行、列）；拿不到就给 [UNKNOWN_SIZE]（服务端会跳过尺寸同步）。 */
    private fun terminalSize(): Pair<Int, Int> {
        val output = stty(listOf("size"))?.trim().orEmpty()
        val parts = output.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val rows = parts.getOrNull(0)?.toIntOrNull() ?: UNKNOWN_SIZE
        val cols = parts.getOrNull(1)?.toIntOrNull() ?: UNKNOWN_SIZE
        return rows to cols
    }

    /**
     * 对**本终端**执行一次 `stty`（stdin 继承，使 stty 操作的就是这个终端设备），并返回其标准输出。
     *
     * stdin 必须继承：`stty` 作用在它 stdin 指向的终端上；换成管道就会去操作「不是终端」的 fd 而失败。
     */
    private fun stty(args: List<String>): String? = runCatching {
        val process = ProcessBuilder(listOf("stty") + args)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.takeIf { process.exitValue() == 0 }
    }.getOrNull()

    private fun parseOptions(args: Array<String>): Options? {
        var host = "127.0.0.1"
        var port: Int? = null
        var token: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--host" -> host = args.getOrNull(++index) ?: return null
                "--port" -> port = args.getOrNull(++index)?.toIntOrNull() ?: return null
                "--token" -> token = args.getOrNull(++index) ?: return null
                else -> return null
            }
            index++
        }
        return if (port == null || token.isNullOrBlank()) null else Options(host, port, token)
    }

    private const val CONNECT_TIMEOUT_MILLIS = 5_000

    /** 类路径分隔符（`USAGE` 里要用，故先于它初始化）。 */
    private val SEPARATOR = java.io.File.pathSeparator

    private val USAGE = """
        用法：java -cp "<插件 jar>$SEPARATOR<kotlin-stdlib jar>" ${ServeConsoleAttach::class.java.name} --port <端口> --token <令牌> [--host 127.0.0.1]

        作用：把本终端接到 serve 里服务端的原生控制台（Tab 补全 / ↑↓ 历史 / ←→ 编辑 / 颜色）。
        断开：Ctrl+]（服务端继续运行；要停服请在该控制台敲 stop，或另跑 stop<Key>Serve）。

        提示：serve 的就绪提示里会打印可直接粘贴的完整命令，一般不需要自己拼。
    """.trimIndent()

    private data class Options(val host: String, val port: Int, val token: String)
}
