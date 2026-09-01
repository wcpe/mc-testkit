package top.wcpe.mc.testkit

import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.contract.McTestkitControlProtocol
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对外契约冻结测试（插件骨架）。
 *
 * 这些常量是 Wave 1 各 FR 共同依赖的接缝，一经发布即契约；本测试守住它们不被无意改动。
 */
class McTestkitContractTest {

    @Test
    @DisplayName("插件标识与扩展名称应保持固定")
    fun exposeStablePluginIdAndExtensionName() {
        assertEquals("top.wcpe.mc-testkit", McTestkitContract.PLUGIN_ID)
        assertEquals("mcTestkit", McTestkitContract.EXTENSION_NAME)
    }

    @Test
    @DisplayName("环境变量应使用固定的测试框架前缀")
    fun useStableEnvironmentVariablePrefix() {
        assertEquals("MC_TESTKIT_E2E_", McTestkitEnv.PREFIX)
        assertTrue(McTestkitEnv.SERVER_TEMPLATE_DIR.startsWith(McTestkitEnv.PREFIX))
        assertTrue(McTestkitEnv.BOT_CONNECT_TIMEOUT_MS.startsWith(McTestkitEnv.PREFIX))
    }

    @Test
    @DisplayName("后端声明名环境变量应保持固定契约")
    fun useStableBackendNameEnvironmentVariable() {
        // 编排经此 env 告诉每个后端「它是谁」，消费方据此 per-backend 派生身份（如 server-id 后缀）；一经发布即契约
        assertEquals("MC_TESTKIT_E2E_BACKEND_NAME", McTestkitEnv.BACKEND_NAME)
        assertTrue(McTestkitEnv.BACKEND_NAME.startsWith(McTestkitEnv.PREFIX))
    }

    @Test
    @DisplayName("不同命名风格的声明应生成稳定的任务名称")
    fun generateStableTaskNamesAcrossNamingStyles() {
        assertEquals("prepareE2eBuySuccess", McTestkitTaskNames.prepare("buySuccess"))
        assertEquals("e2eBuySuccess", McTestkitTaskNames.verify("buy-success"))
        assertEquals("e2eBuySuccessViaWaterfall", McTestkitTaskNames.verifyVia("buy-success", "waterfall"))
        assertEquals("launchBuySuccessBot", McTestkitTaskNames.launchBot("buySuccess"))
        assertEquals("e2eSmokeWithBot", McTestkitTaskNames.withBot("smoke"))
        assertEquals("npmInstallE2eBot", McTestkitTaskNames.NPM_INSTALL_BOT)
    }

    @Test
    @DisplayName("控制协议与结果文件字段应保持固定契约")
    fun keepControlProtocolAndResultFileContractStable() {
        assertEquals("E2E_READY", McTestkitControlProtocol.READY)
        assertEquals("E2E_STRESS_RESULT", McTestkitControlProtocol.STRESS_RESULT)
        assertEquals("status", McTestkitResultFile.STATUS_KEY)
        assertEquals("PASS", McTestkitResultFile.PASS)
        assertEquals("FAIL", McTestkitResultFile.FAIL)
        assertEquals("smoke.properties", McTestkitResultFile.fileName("smoke"))
    }
}
