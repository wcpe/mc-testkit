package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PTY 分配器包装（[PseudoTerminal]）单元测试：平台判定与命令构造。
 *
 * 真机维度（真的把服务端放进 pty）见 fr-25 的验收标准；此处只锁「命令长什么样」，因为那是跨平台分支
 * 最容易写错、也最容易在别人机器上静默失效的地方。
 */
class PseudoTerminalTest {

    @Test
    @DisplayName("平台判定：Linux / macOS / 其它")
    @Timeout(30)
    fun detectFamily() {
        assertEquals(PseudoTerminal.Family.LINUX, PseudoTerminal.family("Linux"))
        assertEquals(PseudoTerminal.Family.MACOS, PseudoTerminal.family("Mac OS X"))
        assertEquals(PseudoTerminal.Family.MACOS, PseudoTerminal.family("Darwin"))
        assertEquals(PseudoTerminal.Family.UNSUPPORTED, PseudoTerminal.family("Windows 11"))
    }

    @Test
    @DisplayName("Linux：script -qec 包一层，并先 stty 设尺寸再 exec")
    @Timeout(30)
    fun wrapForLinux() {
        val command = PseudoTerminal.wrapperCommand(
            listOf("/usr/bin/java", "-Xmx1G", "-jar", "/path with space/paper.jar", "--nogui"),
            PseudoTerminal.Family.LINUX,
            rows = 50,
            cols = 200,
        )
        assertEquals("script", command[0])
        assertEquals("-qec", command[1])
        assertEquals("/dev/null", command[3])
        val inner = command[2]
        assertTrue(inner.startsWith("stty rows 50 cols 200; exec "), "应先设尺寸再 exec：$inner")
        assertTrue(inner.contains("'/usr/bin/java'"), "参数应做 shell 引用：$inner")
        assertTrue(inner.contains("'/path with space/paper.jar'"), "含空格的路径应被引用：$inner")
        assertTrue(inner.contains("'--nogui'"))
    }

    @Test
    @DisplayName("shell 引用：路径里的单引号要被正确转义")
    @Timeout(30)
    fun quoteSingleQuotesInLinuxWrapper() {
        val inner = PseudoTerminal.wrapperCommand(listOf("java", "-jar", "/tmp/it's here.jar"), PseudoTerminal.Family.LINUX)[2]
        assertTrue(inner.contains("'/tmp/it'\\''s here.jar'"), "单引号应转义为 '\\''：$inner")
    }

    @Test
    @DisplayName("macOS：BSD script 直接吃参数，故显式 sh -c")
    @Timeout(30)
    fun wrapForMacOs() {
        val command = PseudoTerminal.wrapperCommand(
            listOf("/usr/bin/java", "-jar", "paper.jar"),
            PseudoTerminal.Family.MACOS,
        )
        assertEquals("script", command[0])
        assertEquals("-q", command[1])
        assertEquals("/dev/null", command[2])
        assertEquals("sh", command[3])
        assertEquals("-c", command[4])
        assertTrue(command[5].startsWith("stty rows ${PseudoTerminal.DEFAULT_ROWS} cols ${PseudoTerminal.DEFAULT_COLS}; exec "))
    }

    @Test
    @DisplayName("不支持的平台：明确报错（绝不用错误形态静默起服）")
    @Timeout(30)
    fun refuseUnsupportedPlatform() {
        val error = assertFailsWith<IllegalStateException> {
            PseudoTerminal.wrapperCommand(listOf("java"), PseudoTerminal.Family.UNSUPPORTED)
        }
        assertTrue(error.message!!.contains("POSIX"), "报错应说清原因：${error.message}")
    }

    @Test
    @DisplayName("空命令应被拒绝（包装器不该产出空 exec）")
    @Timeout(30)
    fun refuseEmptyCommand() {
        assertFailsWith<IllegalArgumentException> {
            PseudoTerminal.wrapperCommand(emptyList(), PseudoTerminal.Family.LINUX)
        }
    }

    @Test
    @DisplayName("尺寸非法时不尝试设置（best-effort 的 resize 不该误报成功）")
    @Timeout(30)
    fun skipInvalidSize() {
        assertEquals(false, PseudoTerminal.resize("/dev/pts/0", rows = 0, cols = 200))
        assertEquals(false, PseudoTerminal.resize("/dev/pts/0", rows = 50, cols = -1))
    }

    @Test
    @DisplayName("可用性探测：命令不存在 / 非零退出 / 输出异常都判不可用并中文说明")
    @Timeout(30)
    fun availabilityFailuresAreReportedAsUnavailable() {
        val notes = mutableListOf<String>()

        // 没有 script 可执行文件（ProcessBuilder 抛 IOException）
        assertEquals(
            false,
            PseudoTerminal.isAvailable(notes::add, osName = "Linux") { throw java.io.IOException("Cannot run program \"script\"") },
        )
        assertTrue(notes.last().contains("PTY 分配器不可用"), "应中文说明：${notes.last()}")

        // 命令在但退出非零
        assertEquals(
            false,
            PseudoTerminal.isAvailable(notes::add, osName = "Linux") { PseudoTerminal.CommandResult(1, "usage: script ...") },
        )
        assertTrue(notes.last().contains("探测失败"), "应中文说明：${notes.last()}")

        // 平台不支持
        assertEquals(false, PseudoTerminal.isAvailable(notes::add, osName = "Windows 10") { PseudoTerminal.CommandResult(0, "version") })
        assertTrue(notes.last().contains("没有 POSIX"), "应中文说明：${notes.last()}")
    }

    @Test
    @DisplayName("可用性探测：成功路径只在退出码 0 且输出含 version 时为真")
    @Timeout(30)
    fun availabilitySuccess() {
        assertEquals(
            true,
            PseudoTerminal.isAvailable(osName = "Linux") { PseudoTerminal.CommandResult(0, "openjdk version \"17.0.9\"") },
        )
        // 退出码 0 但输出不像 java -version（例如被包装器吞掉）→ 判不可用
        assertEquals(false, PseudoTerminal.isAvailable(osName = "Linux") { PseudoTerminal.CommandResult(0, "") })
    }

    @Test
    @DisplayName("真实平台探测：本机（Linux + script）应可用")
    @Timeout(60)
    fun availabilityOnThisMachine() {
        if (PseudoTerminal.family() != PseudoTerminal.Family.LINUX) return
        assertTrue(PseudoTerminal.isAvailable(), "本机应有可用的 PTY 分配器（util-linux 的 script）")
    }
}
