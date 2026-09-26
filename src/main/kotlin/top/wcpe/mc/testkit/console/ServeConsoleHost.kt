package top.wcpe.mc.testkit.console

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** 附加控制台的握手首行前缀（框架 ↔ 客户端契约，随插件版本走）。 */
internal const val ATTACH_HANDSHAKE_PREFIX = "MC_TESTKIT_ATTACH"

/** 握手行长度上限（防呆：令牌 + 尺寸足够短，超长直接拒）。 */
private const val HANDSHAKE_MAX_BYTES = 256

/** 握手等待上限（毫秒）：连上却不发握手的连接不该占着会话。 */
private const val HANDSHAKE_TIMEOUT_MILLIS = 5_000

/**
 * 附加控制台端点（服务端侧）：在回环地址上开一个端口，把一个**已经连着终端的客户端**桥接到
 * PTY 里那个服务端的 stdin/stdout 上。
 *
 * 为什么需要它：让服务端真正拿到终端要靠 PTY（见 [PseudoTerminal]），而「把终端切到原始模式、
 * 搬运按键」这件事只能由开发者自己终端里的进程做——Gradle 守护进程拿不到终端（实测见 ADR-0022）。
 * 端点只做**字节搬运**，不理解、不改写任何内容：补全 / 历史 / 行编辑全部由服务端的 JLine 完成。
 *
 * 安全与收敛：只绑回环地址；握手须带**随机令牌**；同一时刻只服务**一个**会话（两个终端抢同一个 pty
 * 只会互相踩）。
 */
internal class ServeConsoleHost(
    val token: String,
    private val serverStdin: () -> OutputStream?,
    /** 兼容旧调用方的进程信息参数；尺寸同步不再通过进程查询实现。 */
    @Suppress("UNUSED_PARAMETER")
    serverPid: (() -> Long?)? = null,
    private val info: (String) -> Unit,
    private val warn: (String) -> Unit,
    /** 由调用方提供的 PTY 尺寸回调，参数顺序为行、列。 */
    private val resizeTerminal: ((rows: Int, cols: Int) -> Boolean)? = null,
) {

    private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** 监听端口（回环地址上的随机端口）。 */
    val port: Int get() = serverSocket.localPort

    private val writeLock = Any()

    @Volatile
    private var session: Session? = null

    /** 是否已有终端 attach（没有时服务端输出仍照常落日志 / 进控制台视图）。 */
    val attached: Boolean get() = session != null

    /** 起接受连接的守护线程。 */
    fun start(): Thread {
        val thread = Thread {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                // 每个连接单独处理，避免一个慢连接堵住「已有一个会话」的拒绝路径
                Thread { handle(socket) }.apply {
                    isDaemon = true
                    name = "mc-testkit-attach-session"
                    start()
                }
            }
        }
        thread.isDaemon = true
        thread.name = "mc-testkit-attach-accept"
        thread.start()
        return thread
    }

    /** 把服务端的原始输出片段推给已 attach 的终端（没有会话时静默丢弃）。 */
    fun pushServerOutput(chunk: ByteArray) {
        session?.let { current ->
            runCatching {
                synchronized(current) {
                    current.clientOutput.write(chunk)
                    current.clientOutput.flush()
                }
            }.onFailure { endSession(current, "写入附加控制台失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** 收尾：断开会话并停止监听（幂等）。 */
    fun stop() {
        session?.let { endSession(it, null) }
        runCatching { serverSocket.close() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            val handshake = readHandshake(socket.getInputStream())
            val fields = handshake?.trim()?.split(Regex("\\s+")).orEmpty()
            if (fields.size < 2 || fields[0] != ATTACH_HANDSHAKE_PREFIX) {
                refuse(socket, "握手格式非法")
                return
            }
            if (fields[1] != token) {
                refuse(socket, "令牌不符")
                return
            }
            val existing = session
            if (existing != null) {
                refuse(socket, "已有一个附加控制台会话（同一时刻只允许一个终端 attach）")
                return
            }
            val cols = fields.getOrNull(2)?.toIntOrNull() ?: 0
            val rows = fields.getOrNull(3)?.toIntOrNull() ?: 0
            applyTerminalSize(cols, rows)

            socket.soTimeout = 0
            val current = Session(socket)
            session = current
            socket.getOutputStream().write("OK\n".toByteArray())
            socket.getOutputStream().flush()
            info("附加控制台已连入（$cols×$rows）：本终端现在就是服务端的原生控制台；Ctrl+] 断开（服务端继续运行）")

            // 客户端 → 服务端 stdin：只搬字节，不做任何解析
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            while (!socket.isClosed) {
                val read = input.read(buffer)
                if (read <= 0) break
                val sink = serverStdin() ?: break
                val delivered = runCatching {
                    synchronized(writeLock) {
                        sink.write(buffer, 0, read)
                        sink.flush()
                    }
                }.isSuccess
                if (!delivered) break
            }
            endSession(current, "附加控制台客户端已断开（服务端继续运行）")
        } catch (ex: Exception) {
            warn("附加控制台会话异常：${ex.message ?: ex.javaClass.simpleName}")
            runCatching { socket.close() }
        }
    }

    private fun endSession(current: Session, message: String?) {
        if (session !== current) return
        session = null
        runCatching { current.clientSocket.close() }
        if (message != null) info(message)
    }

    private fun refuse(socket: Socket, reason: String) {
        warn("拒绝附加控制台连接（$reason）")
        runCatching {
            socket.getOutputStream().write("REFUSED: $reason\n".toByteArray())
            socket.getOutputStream().flush()
        }
        runCatching { socket.close() }
    }

    /** 按 attach 终端的真实尺寸调用 PTY 回调；没有回调时只保留字节桥。 */
    private fun applyTerminalSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val resize = resizeTerminal ?: return
        val resized = runCatching { resize(rows, cols) }.getOrDefault(false)
        if (!resized) {
            warn("未能把 pty 尺寸同步为 $cols×$rows：终端折行可能不准")
        }
    }

    /** 只读到换行为止的握手读取：不能上 BufferedReader（它会把后续原始字节一起缓冲掉）。 */
    private fun readHandshake(input: InputStream): String? {
        val bytes = ArrayList<Byte>(HANDSHAKE_MAX_BYTES)
        while (bytes.size < HANDSHAKE_MAX_BYTES) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray())
            if (next == '\n'.code) return String(bytes.toByteArray())
            bytes.add(next.toByte())
        }
        return null
    }

    private class Session(val clientSocket: Socket) {
        val clientOutput: OutputStream = clientSocket.getOutputStream()
    }

    companion object {
        /** 生成随机令牌（附加控制台端点只在回环地址，令牌挡住本机其它进程误连 / 抢会话）。 */
        fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
