package top.wcpe.mc.testkit
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 压测编排的 DSL 形态与命名/契约扩展测试（ADR-0008）。
 */
class StressContractTest {

    @Test
    @DisplayName("压测 DSL 应记录机器人数量持续时间与默认随机种子")
    fun recordStressScenarioDimensions() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        val ext = project.extensions.getByType(McTestkitExtension::class.java)
        ext.scenario("continuous-stress") {
            backends("s1", "s2")
            via = "wf"
            stress {
                botsPerServer = 50
                durationSeconds = 120
            }
        }
        val sc = ext.declaredScenarios.single()
        assertNotNull(sc.stressSpec, "声明 stress 即压测场景")
        assertEquals(50, sc.stressSpec!!.botsPerServer)
        assertEquals(120L, sc.stressSpec!!.durationSeconds)
        // randomSeed 未显式设 → 取默认
        assertEquals(McTestkitDefaults.STRESS_RANDOM_SEED, sc.stressSpec!!.randomSeed)
    }

    @Test
    @DisplayName("不同命名风格的压测场景应生成稳定任务名")
    fun generateStableStressTaskNames() {
        assertEquals("e2eContinuousStressStress", McTestkitTaskNames.stress("continuous-stress"))
        assertEquals("e2eContinuousStressStress", McTestkitTaskNames.stress("continuousStress"))
        assertEquals("stopContinuousStressStress", McTestkitTaskNames.stopStress("continuous-stress"))
    }

    @Test
    @DisplayName("压测环境变量应使用固定名称与统一前缀")
    fun useFixedStressEnvironmentVariables() {
        assertEquals("MC_TESTKIT_E2E_BOT_INDEX", McTestkitEnv.BOT_INDEX)
        assertEquals("MC_TESTKIT_E2E_STRESS_RANDOM_SEED", McTestkitEnv.STRESS_RANDOM_SEED)
        assertEquals("MC_TESTKIT_E2E_STRESS_DURATION_SECONDS", McTestkitEnv.STRESS_DURATION_SECONDS)
        listOf(McTestkitEnv.BOT_INDEX, McTestkitEnv.STRESS_RANDOM_SEED, McTestkitEnv.STRESS_DURATION_SECONDS)
            .forEach { assertTrue(it.startsWith(McTestkitEnv.PREFIX), "$it 须以契约前缀开头") }
    }
}
