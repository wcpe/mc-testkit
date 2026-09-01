package top.wcpe.mc.testkit.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link McTestkitResultWriter} 单元测试：原子落盘、键序、PASS/FAIL。 */
class McTestkitResultWriterTest {

    @TempDir
    File tempDir;

    @Test
    void writeProducesStatusAndMessage() throws Exception {
        File target = new File(tempDir, "smoke.properties");
        new McTestkitResultWriter(target).write(McTestkitResultWriter.STATUS_PASS, "桩已就绪", Map.of());

        Properties props = load(target);
        assertEquals(McTestkitResultWriter.STATUS_PASS, props.getProperty("status"));
        assertEquals("桩已就绪", props.getProperty("message"));
    }

    @Test
    void passAndFailConvenienceMethods() throws Exception {
        File passTarget = new File(tempDir, "pass.properties");
        File failTarget = new File(tempDir, "fail.properties");
        new McTestkitResultWriter(passTarget).pass("通过");
        new McTestkitResultWriter(failTarget).fail("失败");

        assertEquals(McTestkitResultWriter.STATUS_PASS, load(passTarget).getProperty("status"));
        assertEquals(McTestkitResultWriter.STATUS_FAIL, load(failTarget).getProperty("status"));
    }

    @Test
    void detailsWrittenAndStatusWinsOverSameNamedDetail() throws Exception {
        File target = new File(tempDir, "cross-server.properties");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("backendName", "s2");
        details.put("arrivedServer", "Paper");
        details.put("status", "被打明细键污染的 status"); // 不应覆盖契约 status
        new McTestkitResultWriter(target).pass("到达", details);

        Properties props = load(target);
        assertEquals("s2", props.getProperty("backendName"));
        assertEquals("Paper", props.getProperty("arrivedServer"));
        assertEquals(McTestkitResultWriter.STATUS_PASS, props.getProperty("status"), "契约 status 键不得被明细覆盖");
    }

    @Test
    void writeCreatesMissingParentDirectories() {
        File target = new File(tempDir, "deep/nested/result.properties");
        new McTestkitResultWriter(target).pass("通过");

        assertTrue(target.isFile(), "结果文件应落盘");
    }

    @Test
    void repeatedWriteReplacesContentAtomically() throws Exception {
        File target = new File(tempDir, "replace.properties");
        McTestkitResultWriter writer = new McTestkitResultWriter(target);
        writer.pass("第一轮");
        writer.fail("第二轮覆盖");

        Properties props = load(target);
        assertEquals(McTestkitResultWriter.STATUS_FAIL, props.getProperty("status"), "第二次写出应完整替换旧结果");
        assertEquals("第二轮覆盖", props.getProperty("message"));
    }

    @Test
    void serveIdleDetectionIsFalseWhenScenarioEnvUnset() {
        // CI 环境不设 MC_TESTKIT_E2E_SCENARIO：空闲判断应安全返回 false，不会误空闲
        assertFalse(McTestkitEnv.isServeIdle(), "未下发场景时不得误判为 serve 空闲");
    }

    @Test
    void serveScenarioIdConstantMatchesContract() {
        assertEquals("__mc_testkit_serve__", McTestkitEnv.SERVE_SCENARIO_ID);
    }

    @Test
    void readyMessageUsesColonSuffix() {
        assertEquals("E2E_READY:example-bot", McTestkitProtocol.readyMessage("example-bot"));
        assertEquals("E2E_READY:__mc_testkit_serve__", McTestkitProtocol.readyMessage(McTestkitEnv.SERVE_SCENARIO_ID));
    }

    private static Properties load(File file) throws Exception {
        assertTrue(file.isFile(), "结果文件应存在：" + file);
        Properties props = new Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            props.load(in);
        }
        return props;
    }
}
