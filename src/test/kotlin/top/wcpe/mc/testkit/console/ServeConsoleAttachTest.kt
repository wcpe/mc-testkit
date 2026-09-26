package top.wcpe.mc.testkit.console

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 附加控制台（[ServeConsoleHost] / [ConsoleOutput] / [AttachCommand]）单元测试。
 *
 * 这些用例覆盖「端点与会话」和「终端流清洗」两层的可穷举部分：握手校验、单会话约束、双向字节桥、
 * 令牌随机性，以及 PTY 终端流（ANSI 颜色 + `\r` 重绘 + `>` 提示符残留）还原成日志 / 控制台文本。
 * 真机维度（真实服务端 + 真实终端 attach）不在此处，见 fr-25 的验收标准。
 */
class ServeConsoleAttachTest {

    // ---------- 终端流清洗 ----------

    @Test
    @DisplayName("日志文本：去掉 ANSI 与 \\r，保留全部可读内容")
    @Timeout(30)
    fun cleanForLog() {
        // 取自真实 Paper 在 PTY 下的终端流（含颜色、清行、CR 重绘）
        val raw = "\u001B[38;5;7mwhitelist\u001B[3m\u001B[38;5;9m<--[HERE]\u001B[0m\r\u001B[K[01:11:50 INFO]: There are 0 of a max of 20 players online: "
        val cleaned = ConsoleOutput.forLog(raw)
        assertFalse(cleaned.contains("\u001B"), "不该残留 ANSI：$cleaned")
        assertFalse(cleaned.contains("\r"), "不该残留 CR：$cleaned")
        assertTrue(cleaned.contains("whitelist"), "内容应保留：$cleaned")
        assertTrue(cleaned.contains("[01:11:50 INFO]: There are 0 of a max of 20 players online: "))
    }

    @Test
    @DisplayName("控制台视图：去掉提示符残留，纯提示符行不打印")
    @Timeout(30)
    fun cleanForView() {
        assertEquals(
            "[01:11:50 INFO]: There are 0 of a max of 20 players online: ",
            ConsoleOutput.forView(">....\u001B[K[01:11:50 INFO]: There are 0 of a max of 20 players online: \r"),
        )
        assertEquals(null, ConsoleOutput.forView(">....\r\u001B[K"), "纯提示符行不该打出来")
        assertEquals(null, ConsoleOutput.forView("   "))
        // 不以 `>` 开头的行原样保留（不该误伤正常日志）
        assertEquals("...loading plugins", ConsoleOutput.forView("...loading plugins"))
    }

    @Test
    @DisplayName("OSC 与双字符转义也要清洗（不止颜色）")
    @Timeout(30)
    fun stripOtherEscapeForms() {
        val cleaned = ConsoleOutput.forLog("\u001B]0;title\u0007\u001B=\u001B(B[12:00:00 INFO]: done")
        assertEquals("[12:00:00 INFO]: done", cleaned)
    }

    @Test
    @DisplayName("真实样本：JLine 的 `ESC M` 与增量回显退格都应被还原成最终文本")
    @Timeout(30)
    fun cleanRealJlineSamples() {
        // 取自真实 Paper 的 PTY 日志（清洗盲点就是这两个样本暴露出来的）
        assertEquals("list", ConsoleOutput.forLog("\u001BM\u001BM\u0008lis\u0008\u0008\u0008list"))
        assertEquals(
            "save-off         say              scoreboard       setblock         stop             summon",
            ConsoleOutput.forLog("save-off         say              scoreboard       setblock         stop             summon\u001BM\u001BM"),
        )
    }

    // ---------- attach 命令 ----------

    @Test
    @DisplayName("attach 命令应含类名、端口、令牌，且类路径与 java 路径整体加引号")
    @Timeout(30)
    fun buildAttachCommand() {
        val text = AttachCommand.text(port = 40201, token = "abc123", host = "127.0.0.1")
        assertTrue(text.startsWith("\""), "应使用当前 JVM 的绝对 java 路径（用户 PATH 里未必有 java）：$text")
        assertTrue(text.contains("\" -cp \""), "类路径应加引号（可能含空格）：$text")
        assertTrue(text.contains(ServeConsoleAttach::class.java.name))
        assertTrue(text.contains("--port 40201") && text.contains("--token abc123"))
        assertTrue(text.contains("--host 127.0.0.1"))
        assertTrue(text.contains(AttachCommand.defaultJavaExecutable()), "应指向当前 JVM：$text")
    }

    @Test
    @DisplayName("类路径应含客户端所在构件与 kotlin-stdlib（否则客户端起不来）")
    @Timeout(30)
    fun classpathContainsClientAndStdlib() {
        val entries = AttachCommand.classpathEntries()
        assertTrue(entries.isNotEmpty(), "类路径不该为空")
        assertTrue(
            entries.any { it.name.contains("kotlin-stdlib") },
            "应含 kotlin-stdlib（否则 java -cp 起不来客户端）：${entries.map { it.name }}",
        )
        // 客户端所在构件：测试期是 classes 目录、发布后是插件 jar——两种形态都应在列且在磁盘上存在
        val clientEntry = entries.firstOrNull { entry ->
            ServeConsoleAttach::class.java.protectionDomain.codeSource.location.toURI().let { it.path.contains(entry.name) || entry.path.contains(it.path) }
        }
        assertTrue(clientEntry != null, "应含客户端所在构件：${entries.map { it.absolutePath }}")
        assertTrue(entries.all { it.exists() }, "类路径条目都应存在：${entries.map { it.absolutePath }}")
    }

    // ---------- 端点与会话 ----------

    @Test
    @DisplayName("握手：格式非法 / 令牌不符应被拒绝，且不建立会话")
    @Timeout(60)
    fun refuseBadHandshake() {
        val warnings = mutableListOf<String>()
        val serverInput = ByteArrayOutputStream()
        val host = ServeConsoleHost(
            token = "good-token",
            serverStdin = { serverInput },
            serverPid = { null },
            info = {},
            warn = warnings::add,
        )
        host.start()
        try {
            assertTrue(connect(host.port, "GARBAGE\n").startsWith("REFUSED"), "格式非法应被拒")
            assertTrue(connect(host.port, "MC_TESTKIT_ATTACH bad-token 80 24\n").contains("令牌不符"))
            assertFalse(host.attached, "被拒的连接不该建立会话")
            assertTrue(warnings.any { it.contains("令牌不符") }, "应中文告警：$warnings")
        } finally {
            host.stop()
        }
    }

    @Test
    @DisplayName("握手通过后双向搬运字节；同一时刻只允许一个会话")
    @Timeout(60)
    fun bridgeBytesAndAllowSingleSession() {
        val serverInput = ByteArrayOutputStream()
        val infos = mutableListOf<String>()
        val host = ServeConsoleHost(
            token = "tok",
            serverStdin = { serverInput },
            serverPid = { null },
            info = infos::add,
            warn = {},
        )
        host.start()
        try {
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", host.port), 5_000)
                client.getOutputStream().write("MC_TESTKIT_ATTACH tok 120 30\n".toByteArray())
                client.getOutputStream().flush()
                assertEquals("OK", readLine(client))

                // 客户端 → 服务端 stdin
                client.getOutputStream().write("list\n".toByteArray())
                client.getOutputStream().flush()
                assertTrue(waitUntil { serverInput.toString().contains("list") }, "按键应到达服务端 stdin")

                // 服务端输出 → 客户端（原样，含转义）
                val chunk = "> \u001B[31mhi\u001B[0m".toByteArray()
                assertTrue(waitUntil { host.attached })
                host.pushServerOutput(chunk)
                assertTrue(waitUntil { readAvailable(client).isNotEmpty() }, "服务端输出应推给客户端")

                // 第二个会话被拒
                assertTrue(connect(host.port, "MC_TESTKIT_ATTACH tok 80 24\n").contains("只允许一个"), "应拒绝并发会话")
            }
            assertTrue(waitUntil { !host.attached }, "客户端断开后会话应释放：$infos")
        } finally {
            host.stop()
        }
    }

    @Test
    @DisplayName("令牌应随机（不同实例不同）")
    @Timeout(30)
    fun randomToken() {
        val tokens = (1..5).map { ServeConsoleHost.randomToken() }
        assertEquals(5, tokens.toSet().size)
        assertTrue(tokens.all { it.length == 32 }, "应为 32 位十六进制：$tokens")
        assertNotEquals(tokens[0], tokens[1])
    }

    private fun connect(port: Int, handshake: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().write(handshake.toByteArray())
        socket.getOutputStream().flush()
        readLine(socket)
    }

    private fun readLine(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ArrayList<Byte>()
        while (bytes.size < 512) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            bytes.add(next.toByte())
        }
        return String(bytes.toByteArray())
    }

    private fun readAvailable(socket: Socket): String {
        val available = socket.getInputStream().available()
        if (available <= 0) return ""
        val buffer = ByteArray(available)
        socket.getInputStream().read(buffer)
        return String(buffer)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
