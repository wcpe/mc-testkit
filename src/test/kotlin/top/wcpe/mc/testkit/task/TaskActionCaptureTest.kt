package top.wcpe.mc.testkit.task

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

/**
 * 任务动作闭包捕获图回归锚（配置缓存兼容）。
 *
 * 动作（doLast）闭包的捕获图里不得出现 [Project]——Gradle 9.x 配置缓存**存储阶段**会序列化全部任务
 * 动作，捕获 `Project` 会报 `cannot serialize DefaultProject`、构建失败（退出码非零）。
 *
 * 本测试用反射对已注册任务的动作对象图做有限深度遍历，断言不含 `Project` 实例（快速单元锚；
 * 端到端权威验证见 [top.wcpe.mc.testkit.ConfigurationCacheFunctionalTest]）。
 */
class TaskActionCaptureTest {

    @Test
    @DisplayName("已注册任务的动作对象图不应捕获 Project")
    fun registeredTaskActionsMustNotCaptureProject() {
        val project = ProjectBuilder.builder().withName("capture-anchor").build()
        val extension = project.extensions.create("mcTestkit", McTestkitExtension::class.java)
        // 全注册点拓扑：单后端 / 经代理（含 bot）/ 集群 / 压测 / 单 serve / 集群 serve
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
            bot {
                username = "ViaBot"
                action = "buy"
            }
        }
        extension.scenario("clusterCross") {
            backends("s1", "s2")
            via = "wf"
            bot {
                username = "CrossBot"
                action = "cross"
            }
        }
        extension.scenario("loadStress") {
            backends("s1", "s2")
            via = "wf"
            stress {
                botsPerServer = 1
                durationSeconds = 1
            }
        }
        extension.serve("dev") {
            backend = "s1"
            via = "wf"
            bot {
                username = "Filler"
                action = "idle"
            }
        }
        extension.serve("clusterDev") {
            backends("s1", "s2")
            via = "wf"
        }
        McTestkitTasks.register(project, extension)

        val visited = mutableSetOf<Any>()
        val leaks = project.tasks.toList().flatMap { task ->
            task.actions.flatMap { action -> collectProjectLeaks(action, depth = 0, visited) }
        }

        assertTrue(
            leaks.isEmpty(),
            "任务动作捕获图中不得出现 Project（Gradle 配置缓存不可序列化）:\n${leaks.joinToString("\n")}",
        )
    }

    /**
     * 对动作对象做有限深度反射遍历，收集捕获图里的 `Project` 实例。
     *
     * 只递归进入本插件包的类型（Kotlin lambda 合成类 / 上下文快照等），避免误入 Gradle 内部对象图
     * （Task 等合法持有所属 Project，但那不是动作闭包捕获）。
     */
    private fun collectProjectLeaks(root: Any, depth: Int, visited: MutableSet<Any>): List<String> {
        if (depth > 4 || !visited.add(root)) return emptyList()
        val leaks = mutableListOf<String>()
        var clazz: Class<*>? = root.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                val value = runCatching {
                    field.isAccessible = true
                    field.get(root)
                }.getOrNull() ?: return@forEach
                val fieldPath = "${root.javaClass.name}.${field.name}"
                if (value is Project) {
                    leaks += fieldPath
                    return@forEach
                }
                // 仅递归本插件包内类型（动作闭包合成类 / 上下文快照），跳过 JDK 与 Gradle 内部对象
                if (value.javaClass.name.startsWith("top.wcpe.mc.testkit")) {
                    collectProjectLeaks(value, depth + 1, visited).forEach { leaks += "$fieldPath -> $it" }
                }
            }
            clazz = clazz.superclass
        }
        return leaks
    }
}
