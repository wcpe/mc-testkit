package top.wcpe.mc.testkit
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.contract.McTestkitRunDirectories
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.VersionMatrixExpander
import top.wcpe.mc.testkit.task.McTestkitTasks

/**
 * mc-testkit 插件入口。
 *
 * 职责：创建 `mcTestkit` 扩展（插件骨架 冻结的对外 DSL 契约），并在工程配置完毕后由 任务自动编排 整合器
 * [McTestkitTasks] 按扩展声明**数据驱动**地注册 e2e 任务（prepare / e2e / 经代理 / 启动机器人 /
 * withBot / 固定名缓存任务），接入已落地各包（provision / serverconfig / bot / verify）。
 *
 * 任务注册放 `afterEvaluate`：消费方 `mcTestkit { }` 在 `apply` 之后才求值，须等其声明就绪再解析
 * 拓扑并注册任务（配置期校验失败抛**中文** `GradleException`）。任务体的副作用全在 `doLast`，
 * 配置期只注册不执行（真实起服 / 起代理 / 起 bot 判定属 首个消费者验证 实机维度）。
 */
class McTestkitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension =
            project.extensions.create(McTestkitContract.EXTENSION_NAME, McTestkitExtension::class.java)
        // 运行根目录与任务侧同源（McTestkitRunDirectories），供消费方经
        // McTestkitExtension.backendRunDirectory 拿到注入落点
        extension.runRoot = project.layout.buildDirectory.dir(McTestkitRunDirectories.WORK_DIR_NAME)
        // 等 mcTestkit { } 声明就绪后，按拓扑数据驱动注册任务（含配置期校验，任务自动编排）
        project.afterEvaluate {
            applySelfJarDefault(project, extension)
            // 版阵先展开为 backend + scenario，再走统一拓扑校验与任务注册
            VersionMatrixExpander.expandAll(extension)
            McTestkitTasks.register(project, extension)
        }
    }

    /**
     * 自测模式默认值（契约 §3.1「待测插件 jar 默认取工作区构建产物」的落地）。
     *
     * 消费方**未显式声明** `pluginUnderTest` 且本工程有 `jar` 任务时，回退到该任务产物
     * （`build/libs/<name>-<version>.jar`），并标记自测模式——任务自动编排据此把
     * prepare / e2e 任务自动接到 `jar` 上，消费方无需手写任何 `dependsOn` 样板。
     *
     * 两种情况**不启用**自测（保持 0.8.x 行为，不抛错以免破坏无插件拓扑 / 框架自测）：
     * - 显式声明过 `pluginUnderTest`（外部被测插件场景，完全尊重声明）；
     * - 本工程没有 `jar` 任务（未应用 java 插件，如代理拓扑编排工程）——此时告警并跳过，
     *   若确需注入请显式声明 `pluginUnderTest`。
     */
    private fun applySelfJarDefault(project: Project, extension: McTestkitExtension) {
        if (!extension.declaredDependencies.pluginUnderTest.isNullOrBlank()) return
        val jarTask =
            runCatching { project.tasks.named("jar", Jar::class.java) }.getOrNull()
                ?: run {
                    project.logger.warn(
                        "mc-testkit：未声明 pluginUnderTest 且本工程没有 jar 任务（未应用 java 插件），" +
                            "跳过自测注入（不向服务端注入任何插件）。\n" +
                            "  若需注入，请显式声明 mcTestkit { dependencies { pluginUnderTest = <路径或环境变量名> } }。",
                    )
                    return
                }
        extension.declaredDependencies.pluginUnderTest = jarTask.get().archiveFile.get().asFile.absolutePath
        extension.declaredDependencies.selfJar = true
    }
}
