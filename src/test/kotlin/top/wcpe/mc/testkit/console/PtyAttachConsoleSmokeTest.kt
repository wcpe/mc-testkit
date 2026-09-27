package top.wcpe.mc.testkit.console

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.spi.SystemStream
import org.jline.terminal.spi.TerminalProvider
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import top.wcpe.mc.testkit.provision.PseudoTerminal
import top.wcpe.mc.testkit.provision.ServerLauncher
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 附加控制台的 **PTY 冒烟测试**：真起一个带 JLine 控制台的桩服务端放进 PTY（Windows 走 ConPTY、
 * Unix 走系统 PTY），再用 [ServeConsoleHost] 端点把字节桥过去，验证三件事：
 *
 * 1. PTY 里的子进程拿到的是**真终端**——JLine 能识别终端类型（ConPTY 未生效会退化成 `dumb`，
 *    服务端的补全 / 历史 / 行编辑随之不可用，正是 ADR-0023 要根治的形态）；
 * 2. 命令**往返可达**：端点写入 → 子进程 stdin → 子进程 stdout → 端点 → 客户端；
 * 3. `Tab` **原样送达**子进程（PTY 模式下的补全由服务端 JLine 负责，框架不得吃掉或改写它）。
 *
 * 为什么要真起进程：`PtyProcessBuilder` 的原生后端（ConPTY / WinPty / Unix PTY）在 CI 上是否真能
 * 分配终端，只有真跑才知道；纯 mock 覆盖不了 pty4j 的原生加载与 Windows 控制台路径。真实 Paper 的
 * 端到端（含 JLine 补全渲染）仍在手动 E2E，CI 只做这一层冒烟。
 */
class PtyAttachConsoleSmokeTest {

    @Test
    @Timeout(180)
    @DisplayName("PTY 附加控制台冒烟：桩服务端拿到真终端，命令与 Tab 原样往返")
    fun attachConsoleRoundTripThroughRealPty() {
        assumeTrue(PseudoTerminal.isAvailable(), "当前环境没有可用的 pty4j 原生后端")
        val workDir = File("build/test-pty-attach-smoke-${System.nanoTime()}").apply { mkdirs() }
        val jar = createStubConsoleJar(File(workDir, "stub-console.jar"))

        val process = ServerLauncher.launch(jar, workDir, "stub", pty = true)
        val host = ServeConsoleHost(
            token = "smoke-token",
            serverStdin = { process.outputStream },
            info = {},
            warn = {},
            resizeTerminal = { rows, cols -> PseudoTerminal.resize(process, rows, cols) },
        )
        try {
            host.start()
            startPump(process, host)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", host.port), 5_000)
                socket.soTimeout = 200
                write(socket, "$ATTACH_HANDSHAKE_PREFIX smoke-token 120 40\n")

                write(socket, "banner\r")
                val banner = expect(socket, "STUB_SIZE:")
                // 打进测试输出，CI 日志里能直接看到「PTY 子进程拿到什么终端、由哪个 provider 建成」
                println("PTY 冒烟诊断：$banner")
                assertTrue(banner.contains("STUB_TERMINAL:"), "桩服务端应报告终端类型：$banner")
                assertFalse(
                    banner.contains("STUB_TERMINAL:ERROR"),
                    "JLine 应能在 PTY 中识别终端：$banner",
                )
                assertFalse(
                    banner.contains("STUB_TERMINAL:dumb"),
                    "PTY 子进程必须拿到真终端，否则服务端补全 / 历史 / 行编辑全部退化：$banner",
                )

                write(socket, "help\r")
                val echoed = expect(socket, "STUB_ECHO:help")
                assertTrue(echoed.contains("STUB_ECHO:help"), "控制台命令应经端点往返到子进程并回显：$echoed")

                write(socket, "whi\t\r")
                val withTab = expect(socket, "STUB_ECHO:whi\t")
                assertTrue(
                    withTab.contains("STUB_ECHO:whi\t"),
                    "Tab 必须原样送达子进程（补全由服务端 JLine 处理，框架不改写）：$withTab",
                )
            }
        } finally {
            host.stop()
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    /** 把 PTY 输出推给附加端点（与 serve 的接线一致）。 */
    private fun startPump(process: Process, host: ServeConsoleHost) {
        val pump = Thread {
            val buffer = ByteArray(4096)
            while (true) {
                val read = runCatching { process.inputStream.read(buffer) }.getOrNull() ?: break
                if (read < 0) break
                host.pushServerOutput(buffer.copyOf(read))
            }
        }
        pump.isDaemon = true
        pump.name = "mc-testkit-test-pty-pump"
        pump.start()
    }

    /** 从附加会话读字节直到出现 [needle]（或超时），返回已读到的文本。 */
    private fun expect(socket: Socket, needle: String, timeoutMillis: Long = 30_000): String {
        val input = socket.getInputStream()
        val text = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMillis
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (read < 0) break
            text.append(String(buffer, 0, read, Charsets.UTF_8))
            if (text.contains(needle)) break
        }
        return text.toString()
    }

    private fun write(socket: Socket, text: String) {
        socket.getOutputStream().write(text.toByteArray())
        socket.getOutputStream().flush()
    }

    /**
     * 造桩控制台 jar：`Main-Class` 指向测试模块里的 [PtyStubConsole]，类路径取**客户端同款**
     * （[AttachCommand.classpathEntries] 算出的那份）+ 测试模块自身。
     *
     * 这样冒烟顺带验证「客户端在这台机器上能不能拿到真终端」：客户端正是靠 JLine 认终端，认不出就拒绝
     * attach；用同一份类路径，平台缺 provider 时这里就会以 dumb 终端失败。
     */
    private fun createStubConsoleJar(target: File): File {
        val entries = (AttachCommand.classpathEntries() + codeSourceFile(PtyStubConsole::class.java)).distinct()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = PtyStubConsole::class.java.name
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ") { it.toURI().toASCIIString() }
        }
        target.parentFile?.mkdirs()
        JarOutputStream(target.outputStream(), manifest).use { /* 只有清单，类与依赖经 Class-Path 接入 */ }
        return target
    }

    /** 定位某类所在构件（classes 目录或 jar）。 */
    private fun codeSourceFile(clazz: Class<*>): File = File(clazz.protectionDomain.codeSource.location.toURI())
}

/**
 * 桩控制台入口：用 JLine 起一个「像服务端」的控制台（服务端控制台就是 JLine 驱动的），
 * 供 [PtyAttachConsoleSmokeTest] 放进 PTY 验证终端识别与字节往返。
 *
 * 约定的输入 / 输出（测试据此断言，全部 ASCII 便于跨平台读日志）：
 * - 输入 `banner` → 输出 `STUB_TERMINAL:<终端类型>` 与 `STUB_SIZE:<列>x<行>`；
 * - 其它输入行 → 输出 `STUB_ECHO:<原样内容>`（含 Tab）；
 * - 输入 `stop` → 退出。
 */
object PtyStubConsole {

    /** JLine 的 provider 名（各自对应一个 `META-INF/services/org/jline/terminal/provider/<name>` 注册文件）。 */
    private val PROVIDER_NAMES = listOf("jna", "jni", "ffm", "exec", "jansi", "dumb")

    /** 子进程里要能查到的运行时依赖（缺失即 JLine 认不出终端，诊断用）。 */
    private val PROBED_CLASSES = listOf(
        "org.jline.terminal.impl.jni.JniTerminalProvider",
        "org.jline.nativ.JLineNativeLoader",
        "com.sun.jna.Native",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // JLine 只在 DEBUG 级别说明「为什么建不出系统终端」，这里把它的日志拧开，便于 CI 上定位
        Logger.getLogger("org.jline.utils.Log").apply {
            level = Level.ALL
            useParentHandlers = false
            addHandler(ConsoleHandler().apply { level = Level.ALL })
        }
        val terminal = try {
            TerminalBuilder.builder().system(true).build()
        } catch (ex: Exception) {
            println("STUB_TERMINAL:ERROR:${ex.message ?: ex.javaClass.simpleName}")
            return
        }
        try {
            terminal.enterRawMode()
            val reader = terminal.reader()
            val buffer = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next < 0) break
                val char = next.toChar()
                if (char != '\r' && char != '\n') {
                    buffer.append(char)
                    continue
                }
                val line = buffer.toString()
                buffer.setLength(0)
                when (line) {
                    "stop" -> {
                        println("STUB_ECHO:$line")
                        break
                    }
                    // attach 之前打的环境信息会被端点丢弃（还没有会话），故 banner 时再打一遍
                    "banner" -> printDiagnostics(terminal)
                    else -> println("STUB_ECHO:$line")
                }
            }
        } finally {
            terminal.close()
        }
    }

    /** 打一组 ASCII 诊断：终端类型 / 尺寸 + JLine provider 与依赖可见性。 */
    private fun printDiagnostics(terminal: Terminal) {
        println("STUB_ENV:console=${System.console() != null} term=${System.getenv("TERM")}")
        println("STUB_PROVIDERS:${describeProviders()}")
        println("STUB_DEPS:${describeDeps()}")
        println("STUB_TERMINAL:${terminal.type}")
        println("STUB_SIZE:${terminal.width}x${terminal.height}")
    }

    /** 逐个真建终端，报告每个 provider 的成败与原因（JLine 只在 DEBUG 里说，这里自己问）。 */
    private fun describeProviders(): String = PROVIDER_NAMES.joinToString(",") { name ->
        val outcome = runCatching {
            val provider = TerminalProvider.load(name)
            val terminal = provider.sysTerminal(
                "stub",
                null,
                false,
                Charsets.UTF_8,
                false,
                Terminal.SignalHandler.SIG_DFL,
                false,
                SystemStream.Output,
            )
            val type = terminal.type
            terminal.close()
            "ok:$type"
        }.getOrElse { ex ->
            "FAIL:${ex.javaClass.simpleName}:${(ex.message ?: "").take(160)}"
        }
        "$name=$outcome"
    }

    /** 逐个探测关键依赖是否可见（缺失说明桩 jar 的 Class-Path 没接全）。 */
    private fun describeDeps(): String = PROBED_CLASSES.joinToString(",") { name ->
        val state = runCatching {
            Class.forName(name, false, PtyStubConsole::class.java.classLoader)
            "ok"
        }.getOrElse { "missing" }
        "$name=$state"
    }
}
