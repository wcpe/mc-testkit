package top.wcpe.mc.testkit
import top.wcpe.mc.testkit.dsl.McTestkitExtension

import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 集群编排（FR-10）的 DSL 形态与命名/契约扩展测试（ADR-0008）。
 */
class ClusterContractTest {

    @Test
    fun `场景 backends 记录多后端引用（集群场景）`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(McTestkitPlugin::class.java)
        val ext = project.extensions.getByType(McTestkitExtension::class.java)
        ext.scenario("crossServer") {
            backends("s1", "s2")
            via = "wf"
        }
        val sc = ext.declaredScenarios.single()
        assertEquals(listOf("s1", "s2"), sc.backendRefs)
        assertTrue(sc.backendRefs.isNotEmpty(), "声明 backends 即集群场景")
    }

    @Test
    fun `集群任务命名约定 e2e_Key_Cluster`() {
        assertEquals("e2eCrossServerCluster", McTestkitTaskNames.cluster("cross-server"))
        assertEquals("e2eCrossServerCluster", McTestkitTaskNames.cluster("crossServer"))
    }

    @Test
    fun `CLUSTER_BACKENDS env 名固定且前缀一致`() {
        assertEquals("MC_TESTKIT_E2E_CLUSTER_BACKENDS", McTestkitEnv.CLUSTER_BACKENDS)
        assertTrue(McTestkitEnv.CLUSTER_BACKENDS.startsWith(McTestkitEnv.PREFIX))
    }
}
