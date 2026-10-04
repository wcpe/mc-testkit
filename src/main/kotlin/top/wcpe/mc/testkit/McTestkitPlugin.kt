package top.wcpe.mc.testkit
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.bundling.Jar
import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.contract.McTestkitRunDirectories
import top.wcpe.mc.testkit.dsl.McTestkitExtension
import top.wcpe.mc.testkit.dsl.VersionMatrixExpander
import top.wcpe.mc.testkit.task.McTestkitTasks
import java.io.File

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
        // 依赖声明的产物来源解析能力：spec 只承载声明、不持有 Project（ADR-0025）
        extension.declaredDependencies.fileResolver = { project.files(it) }
        // 等 mcTestkit { } 声明就绪后，按拓扑数据驱动注册任务（含配置期校验，任务自动编排）
        project.afterEvaluate {
            applyPluginUnderTestDeclaration(project, extension)
            // 版阵先展开为 backend + scenario，再走统一拓扑校验与任务注册
            VersionMatrixExpander.expandAll(extension)
            McTestkitTasks.register(project, extension)
        }
    }

    /**
     * 待测插件声明解析（契约 §3.1「待测插件 jar 默认取工作区构建产物」的落地，ADR-0025）。
     *
     * 解析顺序：
     * 1. 显式声明的**产物来源**（`pluginUnderTest(from = …)`）：解析出恰好一个产物的绝对路径写入
     *    `pluginUnderTest`（执行期路径逻辑完全复用），此时**不**进入自测回退——它不是框架默认值。
     * 2. 显式声明的**字符串**（`pluginUnderTest = "…"`）：完全尊重声明，不做任何回退。
     * 3. 两者都未声明且本工程有 `jar` 任务：回退到该任务产物
     *    （`build/libs/<name>-<version>.jar`），并标记自测模式——任务自动编排据此把
     *    prepare / e2e 任务自动接到 `jar` 上，消费方无需手写任何 `dependsOn` 样板。
     *
     * 两种情况**不启用**自测（保持 0.8.x 行为，不抛错以免破坏无插件拓扑 / 框架自测）：
     * - 显式声明过 `pluginUnderTest` 或产物来源（外部被测插件场景，完全尊重声明）；
     * - 本工程没有 `jar` 任务（未应用 java 插件，如代理拓扑编排工程）——此时告警并跳过，
     *   若确需注入请显式声明 `pluginUnderTest`。
     */
    private fun applyPluginUnderTestDeclaration(project: Project, extension: McTestkitExtension) {
        val declared = extension.declaredDependencies
        val source = declared.pluginUnderTestFiles
        if (source != null) {
            // 与字符串形式互斥：两者解析时机与语义都不同，静默取其一会让另一处声明看似生效实则
            // 被忽略（ADR-0025 同 ADR-0020 的「不静默忽略」原则），故配置期直接中文报错。
            if (!declared.pluginUnderTest.isNullOrBlank()) {
                throw GradleException(
                    "mc-testkit：dependencies { } 里同时声明了 pluginUnderTest = \"…\" 与 " +
                        "pluginUnderTest(from = …)，二者互斥。\n" +
                        "  - pluginUnderTest = \"…\"：环境变量名或路径，运行期解析（制品不由本构建产出时用）\n" +
                        "  - pluginUnderTest(from = …)：本构建的产物来源（shadowJar 等 fat jar 场景用）\n" +
                        "  请只保留一种声明。",
                )
            }
            declared.pluginUnderTest = resolvePluginUnderTestArtifact(source).absolutePath
            return
        }
        if (!declared.pluginUnderTest.isNullOrBlank()) return
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

    /**
     * 从产物来源解析出**恰好一个** jar（ADR-0025）。
     *
     * 只取路径、不校验存在性：配置期产物尚未构建，存在性由执行期（`resolveDependencyJars`）校验。
     * 产物不止一个时 `FileCollection.singleFile` 抛的是英文 Gradle 异常，此处转译为中文说明。
     */
    private fun resolvePluginUnderTestArtifact(source: FileCollection): File =
        try {
            source.singleFile
        } catch (ex: IllegalStateException) {
            throw GradleException(
                "mc-testkit：pluginUnderTest(from = …) 必须解析出恰好一个产物，实际不止一个。\n" +
                    "  请传入单个任务或单个文件来源（如 tasks.named(\"shadowJar\")）。",
                ex,
            )
        }
}
