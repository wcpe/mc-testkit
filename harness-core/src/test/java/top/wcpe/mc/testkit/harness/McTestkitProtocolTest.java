package top.wcpe.mc.testkit.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McTestkitProtocol} 的 FR-30 命令通道常量与拼接规则。
 *
 * <p>重点覆盖两类容易写错的地方：<b>载荷里的冒号</b>（回执格式 {@code E2E_CMD_DONE:<id>:<PASS|FAIL>:<output>}
 * 必须按「最多切 4 段」解析，否则 output 被切坏）与<b>换行单行化</b>（留换行会让一条回执在日志里断成几行）。
 *
 * <p>桩侧真正执行 {@code dispatchCommand} 的那段**不在**单测范围内：它需要真实服务端
 * （Bukkit 的 console 发送者与命令树），见任务报告里的说明，这里不写假通过的替身。
 */
class McTestkitProtocolTest {

    @Test
    void commandRequestJoinsIdAndCommand() {
        assertEquals("E2E_CMD:1:/gamemode creative Steve",
            McTestkitProtocol.commandRequest("1", "/gamemode creative Steve"));
    }

    @Test
    void commandRequestKeepsColonsInsideCommand() {
        // 命令里的冒号（命名空间）必须原样保留：id 之后的内容整体都是 command
        String message = McTestkitProtocol.commandRequest("c-1", "give Steve minecraft:stone 1");
        assertEquals("E2E_CMD:c-1:give Steve minecraft:stone 1", message);
        assertEquals("c-1", McTestkitProtocol.commandRequestId(message));
        assertEquals("give Steve minecraft:stone 1", McTestkitProtocol.commandRequestBody(message));
    }

    @Test
    void commandRequestSplitsOnFirstColonOnly() {
        // 命令自身还可含多个冒号；取 body 必须只按**首个**冒号切
        String message = McTestkitProtocol.commandRequest("id7", "a:b:c:d");
        assertEquals("a:b:c:d", McTestkitProtocol.commandRequestBody(message));
    }

    @Test
    void commandRequestRejectsBadId() {
        assertThrows(IllegalArgumentException.class, () -> McTestkitProtocol.commandRequest("", "/say hi"));
        assertThrows(IllegalArgumentException.class, () -> McTestkitProtocol.commandRequest(null, "/say hi"));
        // id 含冒号会让接收方在第一个冒号处配错请求，必须当场拒绝而不是拼出一条歧义消息
        assertThrows(IllegalArgumentException.class, () -> McTestkitProtocol.commandRequest("a:b", "/say hi"));
    }

    @Test
    void commandRequestWithoutCommandIsStillParseable() {
        String message = McTestkitProtocol.commandRequest("x", "");
        assertEquals("E2E_CMD:x:", message);
        assertEquals("x", McTestkitProtocol.commandRequestId(message));
        assertEquals("", McTestkitProtocol.commandRequestBody(message));
    }

    @Test
    void commandRequestParsersRejectNonRequestPayloads() {
        assertNull(McTestkitProtocol.commandRequestId(null));
        assertNull(McTestkitProtocol.commandRequestBody(null));
        assertNull(McTestkitProtocol.commandRequestId("E2E_READY:smoke"));
        assertNull(McTestkitProtocol.commandRequestBody("E2E_READY:smoke"));
        // 缺 id 的残缺载荷（只有前缀 + 命令）不得被当成有效请求
        assertNull(McTestkitProtocol.commandRequestId("E2E_CMD:/say hi"));
        assertNull(McTestkitProtocol.commandRequestBody("E2E_CMD:/say hi"));
    }

    @Test
    void commandDoneJoinsPassAndFail() {
        assertEquals("E2E_CMD_DONE:1:PASS:命令已被服务端接受并执行",
            McTestkitProtocol.commandDone("1", true, "命令已被服务端接受并执行"));
        assertEquals("E2E_CMD_DONE:1:FAIL:未知命令",
            McTestkitProtocol.commandDone("1", false, "未知命令"));
    }

    @Test
    void commandDoneKeepsColonsInOutput() {
        // 这是回执格式最容易踩的坑：output 是最后一段，可含任意冒号
        String message = McTestkitProtocol.commandDone("p9", true, "已给予 minecraft:stone x1");
        String[] parts = message.split(":", 4);
        assertEquals(4, parts.length);
        assertEquals("E2E_CMD_DONE", parts[0]);
        assertEquals("p9", parts[1]);
        assertEquals("PASS", parts[2]);
        assertEquals("已给予 minecraft:stone x1", parts[3]);

        // 反例锚点：按无上限切分会切出第 5 段、末段被截成 "stone x1"
        assertTrue(message.split(":").length > 4);
        assertEquals("stone x1", message.split(":")[4]);
    }

    @Test
    void commandDoneFlattensNewlines() {
        String message = McTestkitProtocol.commandDone("n1", true, "第一行\n第二行\r\n第三行");
        assertFalse(message.contains("\n"), "回执不得含换行");
        assertFalse(message.contains("\r"), "回执不得含回车");
        assertEquals("E2E_CMD_DONE:n1:PASS:第一行 第二行  第三行", message);
    }

    @Test
    void commandDoneHandlesNullOutput() {
        assertEquals("E2E_CMD_DONE:n2:FAIL:", McTestkitProtocol.commandDone("n2", false, null));
    }

    @Test
    void channelNameSatisfiesBothFormatEras() {
        // 1.13+ 要求 namespace:path（含冒号）；1.12- 只是裸字符串但有长度上限（旧版 20 字符）
        assertTrue(McTestkitProtocol.COMMAND_CHANNEL.indexOf(':') > 0);
        assertTrue(McTestkitProtocol.COMMAND_CHANNEL.length() <= 20);
        assertFalse(McTestkitProtocol.COMMAND_CHANNEL.contains(" "));
    }
}
