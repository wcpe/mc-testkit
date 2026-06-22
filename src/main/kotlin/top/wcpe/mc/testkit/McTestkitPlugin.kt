package top.wcpe.mc.testkit
import top.wcpe.mc.testkit.dsl.McTestkitExtension

import org.gradle.api.Plugin
import org.gradle.api.Project
import top.wcpe.mc.testkit.contract.McTestkitContract
import top.wcpe.mc.testkit.task.McTestkitTasks

/**
 * mc-testkit 插件入口。
 *
 * 职责：创建 `mcTestkit` 扩展（FR-01 冻结的对外 DSL 契约），并在工程配置完毕后由 FR-04 整合器
 * [McTestkitTasks] 按扩展声明**数据驱动**地注册 e2e 任务（prepare / e2e / 经代理 / 启动机器人 /
 * withBot / 固定名缓存任务），接入已落地各包（provision / serverconfig / bot / verify）。
 *
 * 任务注册放 `afterEvaluate`：消费方 `mcTestkit { }` 在 `apply` 之后才求值，须等其声明就绪再解析
 * 拓扑并注册任务（配置期校验失败抛**中文** `GradleException`）。任务体的副作用全在 `doLast`，
 * 配置期只注册不执行（真实起服 / 起代理 / 起 bot 判定属 FR-08 实机维度）。
 */
class McTestkitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension =
            project.extensions.create(McTestkitContract.EXTENSION_NAME, McTestkitExtension::class.java)
        // 等 mcTestkit { } 声明就绪后，按拓扑数据驱动注册任务（含配置期校验，FR-04）
        project.afterEvaluate {
            McTestkitTasks.register(project, extension)
        }
    }
}
