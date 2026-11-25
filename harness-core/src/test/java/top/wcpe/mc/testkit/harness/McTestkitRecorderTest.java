package top.wcpe.mc.testkit.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 录屏 / 截图证据能力的降级契约：录制未声明（无 RECORD env）时一切调用安全降级——
 * 返回 null、不抛异常，保证无头环境照常跑测试。
 */
class McTestkitRecorderTest {

    @Test
    @DisplayName("录制未声明时 available 返回 false")
    void unavailableWithoutEnv() {
        // 测试 JVM 未注入 RECORD 契约——等价于未声明 recordEvidence
        assertFalse(McTestkitRecorder.available());
    }

    @Test
    @DisplayName("录制未声明时截图 / 开录返回 null 而不抛异常")
    void degradeToNullWithoutEnv() {
        assertNull(McTestkitRecorder.screenshot("x"));
        assertNull(McTestkitRecorder.start("x"));
    }
}
