package top.wcpe.mc.testkit
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.mc.testkit.contract.McTestkitDefaults
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 压测编排（FR-11）的 DSL 形态与命名/契约扩展测试（ADR-0008）。
 */
class StressContractTest {

    @Test
    fun `场景 stress 块记录压测维度（压测场景）`() {
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
    fun `压测任务命名约定 e2e_Key_Stress 与 stop_Key_Stress`() {
        assertEquals("e2eContinuousStressStress", McTestkitTaskNames.stress("continuous-stress"))
        assertEquals("e2eContinuousStressStress", McTestkitTaskNames.stress("continuousStress"))
        assertEquals("stopContinuousStressStress", McTestkitTaskNames.stopStress("continuous-stress"))
    }

    @Test
    fun `压测 env 名固定且前缀一致`() {
        assertEquals("MC_TESTKIT_E2E_BOT_INDEX", McTestkitEnv.BOT_INDEX)
        assertEquals("MC_TESTKIT_E2E_STRESS_RANDOM_SEED", McTestkitEnv.STRESS_RANDOM_SEED)
        assertEquals("MC_TESTKIT_E2E_STRESS_DURATION_SECONDS", McTestkitEnv.STRESS_DURATION_SECONDS)
        listOf(McTestkitEnv.BOT_INDEX, McTestkitEnv.STRESS_RANDOM_SEED, McTestkitEnv.STRESS_DURATION_SECONDS)
            .forEach { assertTrue(it.startsWith(McTestkitEnv.PREFIX), "$it 须以契约前缀开头") }
    }
}
