package top.wcpe.mc.testkit.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 证据编译器解析层测试：总线是跨语言契约，空白形态与键名子串陷阱必须有 regression 保护。 */
class EvidenceCompilerTest {

    @TempDir
    File dir;

    @Test
    @DisplayName("带空格的 JSON 行（python json.dumps 形态）字段照常解析")
    void parsesSpacedJson() throws Exception {
        File bus = new File(dir, "steps.jsonl");
        Files.write(bus.toPath(), java.util.Arrays.asList(
                "{\"v\": 1, \"type\": \"step\", \"ts\": 1000, \"suite\": \"s\", \"scenario\": \"c\", \"seq\": 1, \"step\": \"spawn-mobs\", \"status\": \"pass\"}",
                "{\"v\": 1, \"type\": \"suite-end\", \"ts\": 2000, \"suite\": \"s\", \"scenario\": \"c\", \"passed\": true, \"message\": \"ok\"}"
        ), StandardCharsets.UTF_8);
        List<EvidenceCompiler.Event> events = EvidenceCompiler.parseBus(bus);
        assertEquals(2, events.size(), "两行合法事件都应解析");
        assertEquals("spawn-mobs", events.get(0).step, "带空格形态的 step 字段必须解析出来");
        assertEquals(1, events.get(0).seq, "seq 数字字段必须解析");
        assertEquals(1000L, events.get(0).ts, "ts 必须解析");
    }

    @Test
    @DisplayName("type 值与 step 键同名时不会把值误当键（子串陷阱回归保护）")
    void doesNotConfuseValueWithKey() throws Exception {
        File bus = new File(dir, "steps.jsonl");
        // 紧凑形态：type 的值恰好是 "step"，而真正的 step 键在其后。
        // 子串匹配若不验证冒号，会在 type 的值处提前命中，step 字段解析为 null。
        Files.write(bus.toPath(), java.util.Arrays.asList(
                "{\"v\":1,\"type\":\"step\",\"ts\":1000,\"suite\":\"s\",\"scenario\":\"c\",\"seq\":1,\"step\":\"pursuit\",\"status\":\"pass\"}"
        ), StandardCharsets.UTF_8);
        List<EvidenceCompiler.Event> events = EvidenceCompiler.parseBus(bus);
        assertEquals(1, events.size());
        assertEquals("pursuit", events.get(0).step, "step 键必须解析为键值而不是 type 值处的空命中");
    }

    @Test
    @DisplayName("残缺行（崩溃尾巴）跳过不炸，合法前缀照常解析")
    void skipsBrokenTail() throws Exception {
        File bus = new File(dir, "steps.jsonl");
        Files.write(bus.toPath(), java.util.Arrays.asList(
                "{\"v\":1,\"type\":\"step\",\"ts\":1000,\"suite\":\"s\",\"scenario\":\"c\",\"seq\":1,\"step\":\"a\",\"status\":\"pass\"}",
                "{\"v\":1,\"type\":\"step\",\"ts\":2000,\"sui"
        ), StandardCharsets.UTF_8);
        List<EvidenceCompiler.Event> events = EvidenceCompiler.parseBus(bus);
        assertEquals(1, events.size(), "残缺尾巴必须跳过而不是拖垮整份证据");
        assertEquals("a", events.get(0).step);
    }

    @Test
    @DisplayName("动作总线写出的紧凑行能被子解析闭环（writer/parser 自洽）")
    void busWriterRoundTrips() throws Exception {
        File bus = new File(dir, "bus.jsonl");
        try (ActionBus actionBus = new ActionBus(bus, "ve-basic", "spawn-pursuit-kill")) {
            actionBus.suiteStart("env");
            actionBus.step(1, "spawn-mobs", "pass", null);
            actionBus.assertion(1, "spawn-mobs", true, "在册实例=3", null);
            actionBus.suiteEnd(true, "全绿");
        }
        List<EvidenceCompiler.Event> events = EvidenceCompiler.parseBus(bus);
        assertEquals(4, events.size(), "writer 写出的每行都必须被 parser 认领");
        assertEquals("spawn-mobs", events.get(1).step);
        assertEquals(Boolean.TRUE, events.get(2).bool("passed"), "断言 passed 必须解析");
        assertTrue(events.get(0).ts > 0, "时间戳必须存在");
    }
}
