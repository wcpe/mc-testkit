package top.wcpe.mc.testkit
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 集群编排（FR-10）的 DSL 形态与命名/契约扩展测试（ADR-0008）。
 */
class ClusterContractTest {

    @Test
    @DisplayName("集群场景声明多个后端时应按顺序记录引用")
    fun recordMultipleBackendReferencesForClusterScenario() {
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
    @DisplayName("短横线与驼峰场景名应生成相同的集群任务名")
    fun generateStableClusterTaskName() {
        assertEquals("e2eCrossServerCluster", McTestkitTaskNames.cluster("cross-server"))
        assertEquals("e2eCrossServerCluster", McTestkitTaskNames.cluster("crossServer"))
    }

    @Test
    @DisplayName("集群后端环境变量应使用固定名称与统一前缀")
    fun useFixedClusterBackendsEnvironmentVariable() {
        assertEquals("MC_TESTKIT_E2E_CLUSTER_BACKENDS", McTestkitEnv.CLUSTER_BACKENDS)
        assertTrue(McTestkitEnv.CLUSTER_BACKENDS.startsWith(McTestkitEnv.PREFIX))
    }
}
