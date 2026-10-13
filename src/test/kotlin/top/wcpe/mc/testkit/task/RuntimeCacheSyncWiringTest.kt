package top.wcpe.mc.testkit.task

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.testkit.contract.McTestkitTaskNames
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 终态任务 → `syncE2eRuntimeCache` 收尾接线回归锚（任务自动编排：持久运行库缓存回写）。
 *
 * 修复背景：`syncE2eRuntimeCache` 早先只被注册、**无人引用**，于是从不进入任务图——持久缓存永不填充，
 * 恢复侧（[restoreBackendRuntimeCache]）永远拿不到东西，整条缓存链静默失效。修复方式是把全部终态任务
 * `finalizedBy` 到它，故此处按**任务图**逐形态断言这层接线存在。
 *
 * 用 [ProjectBuilder] 做纯配置期断言（不跑任务体、不联网、不起服），一次覆盖五类终态形态：
 * 直连场景 / 经代理场景 / 集群 / 压测 / serve。
 *
 * 同时锚定接线**方式**必须是 `finalizedBy` 而非 `dependsOn`：回写是收尾优化，不该因它失败而让场景判定
 * 失败，且失败 / 中断路径也要沉淀缓存（`dependsOn` 在失败路径不保证执行）。
 */
class RuntimeCacheSyncWiringTest {

    /** 全部终态任务名（各自形态下会写出运行目录、因而需要收尾回写缓存）。 */
    private val terminalTaskNames = listOf(
        McTestkitTaskNames.verify("buy"),
        McTestkitTaskNames.verifyVia("buyVia", "wf"),
        McTestkitTaskNames.cluster("clusterCross"),
        McTestkitTaskNames.stress("loadStress"),
        McTestkitTaskNames.serve("dev"),
        McTestkitTaskNames.serve("clusterDev"),
    )

    /** 注册覆盖全部终态形态的拓扑（与 TaskActionCaptureTest 同源，保证注册点无遗漏）。 */
    private fun registerFullTopologyProject(): Project {
        val project = ProjectBuilder.builder().withName("runtime-cache-sync-anchor").build()
        val extension = project.extensions.create("mcTestkit", McTestkitExtension::class.java)
        extension.backend("s1") { port = 25565 }
        extension.backend("s2") { port = 25566 }
        extension.proxy("wf") {
            platform = waterfall
            port = 25577
            routesTo("s1", "s2")
        }
        extension.scenario("buy") { backend = "s1" }
        extension.scenario("buyVia") {
            backend = "s1"
            via = "wf"
        }
        extension.scenario("clusterCross") {
            backends("s1", "s2")
            via = "wf"
        }
        extension.scenario("loadStress") {
            backends("s1", "s2")
            via = "wf"
            stress {
                botsPerServer = 1
                durationSeconds = 1
            }
        }
        extension.serve("dev") { backend = "s1" }
        // 集群 serve 必须经代理（真人靠 /server 在代理上切服），故 via + 全后端路由
        extension.serve("clusterDev") {
            backends("s1", "s2")
            via = "wf"
        }
        McTestkitTasks.register(project, extension)
        return project
    }

    /** 某任务的收尾（finalizer）任务名集合。 */
    private fun Project.finalizerNamesOf(taskName: String): Set<String> {
        val task = tasks.getByName(taskName)
        return task.finalizedBy.getDependencies(task).map { it.name }.toSet()
    }

    @Test
    @DisplayName("各终态任务都应以 finalizedBy 收尾到 syncE2eRuntimeCache")
    fun finalizeTerminalTasksWithRuntimeCacheSync() {
        val project = registerFullTopologyProject()

        terminalTaskNames.forEach { name ->
            val finalizers = project.finalizerNamesOf(name)
            assertTrue(
                McTestkitTaskNames.SYNC_RUNTIME_CACHE in finalizers,
                "终态任务 $name 应 finalizedBy ${McTestkitTaskNames.SYNC_RUNTIME_CACHE}（否则持久缓存从不填充），" +
                    "实际 finalizer=$finalizers",
            )
        }
    }

    @Test
    @DisplayName("缓存回写应是收尾动作而非前置依赖")
    fun wireRuntimeCacheSyncAsFinalizerNotDependency() {
        val project = registerFullTopologyProject()

        terminalTaskNames.forEach { name ->
            val task = project.tasks.getByName(name)
            val directDependencies = task.taskDependencies.getDependencies(task).map { it.name }.toSet()
            assertTrue(
                McTestkitTaskNames.SYNC_RUNTIME_CACHE !in directDependencies,
                "缓存回写不应是 $name 的 dependsOn 依赖（回写失败不该让场景判定失败，且失败路径也要沉淀）",
            )
        }
    }

    @Test
    @DisplayName("回写任务自身不应被任何终态任务当成依赖从而形成环")
    fun keepRuntimeCacheSyncTaskFreeOfBackReferences() {
        val project = registerFullTopologyProject()

        val syncTask = project.tasks.getByName(McTestkitTaskNames.SYNC_RUNTIME_CACHE)
        // 回写只读运行目录、不依赖任何终态任务；若有反指会与 finalizedBy 形成执行序冲突
        assertEquals(
            emptySet(),
            syncTask.taskDependencies.getDependencies(syncTask).map { it.name }.toSet(),
            "回写任务不应依赖其它任务",
        )
    }

    @Test
    @DisplayName("非终态任务不应挂载缓存回写")
    fun keepNonTerminalTasksFreeOfRuntimeCacheSync() {
        val project = registerFullTopologyProject()

        // 负向对照：证明上面的断言不是恒真——回写只该挂在会写出运行目录的终态任务上，
        // prepare / 收尾停进程这类任务既不产出运行库也不该拖一条多余的收尾链。
        listOf(
            McTestkitTaskNames.prepare("buy"),
            McTestkitTaskNames.stopCluster("clusterCross"),
            McTestkitTaskNames.stopStress("loadStress"),
            McTestkitTaskNames.stopServe("dev"),
        ).forEach { name ->
            val finalizers = project.finalizerNamesOf(name)
            assertTrue(
                McTestkitTaskNames.SYNC_RUNTIME_CACHE !in finalizers,
                "非终态任务 $name 不应 finalizedBy ${McTestkitTaskNames.SYNC_RUNTIME_CACHE}，实际 finalizer=$finalizers",
            )
        }
    }
}
