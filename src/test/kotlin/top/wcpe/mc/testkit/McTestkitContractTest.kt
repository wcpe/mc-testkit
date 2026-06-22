package top.wcpe.mc.testkit

import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.contract.McTestkitControlProtocol
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitResultFile
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对外契约冻结测试（FR-01）。
 *
 * 这些常量是 Wave 1 各 FR 共同依赖的接缝，一经发布即契约；本测试守住它们不被无意改动。
 */
class McTestkitContractTest {

    @Test
    fun `插件 id 与扩展名固定`() {
        assertEquals("top.wcpe.mc-testkit", McTestkitContract.PLUGIN_ID)
        assertEquals("mcTestkit", McTestkitContract.EXTENSION_NAME)
    }

    @Test
    fun `环境变量前缀固定为 MC_TESTKIT_E2E_`() {
        assertEquals("MC_TESTKIT_E2E_", McTestkitEnv.PREFIX)
        assertTrue(McTestkitEnv.SERVER_TEMPLATE_DIR.startsWith(McTestkitEnv.PREFIX))
        assertTrue(McTestkitEnv.BOT_CONNECT_TIMEOUT_MS.startsWith(McTestkitEnv.PREFIX))
    }

    @Test
    fun `后端声明名环境变量契约固定（FR-12）`() {
        // 编排经此 env 告诉每个后端「它是谁」，消费方据此 per-backend 派生身份（如 server-id 后缀）；一经发布即契约
        assertEquals("MC_TESTKIT_E2E_BACKEND_NAME", McTestkitEnv.BACKEND_NAME)
        assertTrue(McTestkitEnv.BACKEND_NAME.startsWith(McTestkitEnv.PREFIX))
    }

    @Test
    fun `任务命名约定（PascalCase 中缀，兼容 kebab 与 camel 名）`() {
        assertEquals("prepareE2eBuySuccess", McTestkitTaskNames.prepare("buySuccess"))
        assertEquals("e2eBuySuccess", McTestkitTaskNames.verify("buy-success"))
        assertEquals("e2eBuySuccessViaWaterfall", McTestkitTaskNames.verifyVia("buy-success", "waterfall"))
        assertEquals("launchBuySuccessBot", McTestkitTaskNames.launchBot("buySuccess"))
        assertEquals("e2eSmokeWithBot", McTestkitTaskNames.withBot("smoke"))
        assertEquals("npmInstallE2eBot", McTestkitTaskNames.NPM_INSTALL_BOT)
    }

    @Test
    fun `控制协议与结果文件契约`() {
        assertEquals("E2E_READY", McTestkitControlProtocol.READY)
        assertEquals("E2E_STRESS_RESULT", McTestkitControlProtocol.STRESS_RESULT)
        assertEquals("status", McTestkitResultFile.STATUS_KEY)
        assertEquals("PASS", McTestkitResultFile.PASS)
        assertEquals("FAIL", McTestkitResultFile.FAIL)
        assertEquals("smoke.properties", McTestkitResultFile.fileName("smoke"))
    }
}
