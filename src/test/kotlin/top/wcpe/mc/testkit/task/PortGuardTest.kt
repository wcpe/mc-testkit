package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 端口可用性预检与报错文案的纯函数单测（进程生命周期与收尾 高风险区）。
 *
 * 探测器经 [PortAvailabilityProbe] 注入，故「空闲 / 被占用」两分支都不必真去占端口；
 * 文案断言按「用户能否据此定位问题」来写：须含端口、用途标签、排查命令与收尾建议。
 */
class PortGuardTest {

    /** 固定判定的探测器（false = 该端口不可用），避免依赖真实端口状态。 */
    private fun probeOf(vararg occupiedPorts: Int): PortAvailabilityProbe =
        PortAvailabilityProbe { port -> port !in occupiedPorts.toSet() }

    @Test
    @DisplayName("全部端口空闲时预检应放行")
    fun allPortsAvailablePasses() {
        requirePortsAvailable(
            listOf(PortTarget(25565, "后端 s1"), PortTarget(25577, "代理 wf")),
            probe = probeOf(),
        )
    }

    @Test
    @DisplayName("无端口待检时应直接放行")
    fun emptyTargetsPass() {
        requirePortsAvailable(emptyList(), probe = probeOf(25565))
    }

    @Test
    @DisplayName("端口被占用时应抛中文错误并含端口与用途标签")
    fun occupiedPortFailsWithChineseMessage() {
        val error = assertFailsWith<GradleException> {
            requirePortsAvailable(
                listOf(PortTarget(25565, "后端 s1"), PortTarget(25577, "代理 wf")),
                probe = probeOf(25565),
            )
        }
        assertTrue("25565" in error.message.orEmpty(), "报错应含被占端口：${error.message}")
        assertTrue("后端 s1" in error.message.orEmpty(), "报错应含用途标签：${error.message}")
        assertTrue("端口预检失败" in error.message.orEmpty(), "报错应为中文且点明预检：${error.message}")
        assertTrue("25577" !in error.message.orEmpty(), "空闲端口不应出现在报错里：${error.message}")
    }

    @Test
    @DisplayName("多个端口被占用时应全部列出")
    fun multipleOccupiedPortsAreAllListed() {
        val error = assertFailsWith<GradleException> {
            requirePortsAvailable(
                listOf(PortTarget(25565, "后端 s1"), PortTarget(25577, "代理 wf")),
                probe = probeOf(25565, 25577),
            )
        }
        assertTrue("25565" in error.message.orEmpty())
        assertTrue("25577" in error.message.orEmpty())
    }

    @Test
    @DisplayName("报应含收尾任务建议与按端口排查命令")
    fun messageCarriesActionableHints() {
        val error = assertFailsWith<GradleException> {
            requirePortsAvailable(
                listOf(PortTarget(25565, "后端 s1")),
                stopTaskName = "stopDevServe",
                probe = probeOf(25565),
                osName = "Linux",
            )
        }
        val message = error.message.orEmpty()
        assertTrue("stopDevServe" in message, "应建议执行收尾任务：$message")
        assertTrue("ss -ltnp" in message || "lsof" in message, "应给出 Linux 排查命令：$message")
    }

    @Test
    @DisplayName("Windows 下应给出 netstat 排查命令而非 ss/lsof")
    fun windowsMessageUsesNetstat() {
        val error = assertFailsWith<GradleException> {
            requirePortsAvailable(
                listOf(PortTarget(25565, "后端 s1")),
                probe = probeOf(25565),
                osName = "Windows 11",
            )
        }
        assertTrue("netstat -ano" in error.message.orEmpty(), "Windows 应给 netstat：${error.message}")
        assertTrue("lsof" !in error.message.orEmpty(), "Windows 不应给 lsof：${error.message}")
    }

    @Test
    @DisplayName("未给收尾任务名时不应出现空的任务建议")
    fun messageOmitsStopHintWhenAbsent() {
        val message = portOccupiedMessage(listOf(PortTarget(25565, "后端 s1")), stopTaskName = null, osName = "Linux")
        assertTrue("gradlew" !in message, "无收尾任务时不应提 gradlew：$message")
    }

    @Test
    @DisplayName("缺省探测器应判定一个已监听端口为不可用")
    fun defaultProbeDetectsListeningPort() {
        // 自占一个临时端口（端口 0 由系统分配，避免与真实服务冲突），再断言探测器认为它不可用
        val socket = java.net.ServerSocket(0)
        try {
            val port = socket.localPort
            assertEquals(
                false,
                PortAvailabilityProbe.DEFAULT.isAvailable(port),
                "已被监听的端口应判定为不可用",
            )
        } finally {
            socket.close()
        }
    }

    @Test
    @DisplayName("缺省探测器应判定一个刚释放的端口为可用")
    fun defaultProbeAcceptsReleasedPort() {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        assertEquals(true, PortAvailabilityProbe.DEFAULT.isAvailable(port), "刚释放的端口应判定为可用")
    }
}
