package top.wcpe.mc.testkit.task

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import top.wcpe.mc.testkit.provision.FixedMavenServerJarSource
import top.wcpe.mc.testkit.provision.JarCache
import top.wcpe.mc.testkit.provision.MavenServerJarSource
import java.io.File

/**
 * 依赖注入与节点运行时所需的 Maven 坐标来源（任务自动编排）。
 *
 * 两类用途共用本 holder（避免串两套参数）：
 * - **依赖插件**（`dependencies { mavenPlugin(...) }`）：坐标 → 惰性文件集合，注入后端运行目录 `plugins/`。
 * - **服务端 / 代理 jar**（`backend/proxy { mavenServer(...) }`）：坐标 → [MavenServerJarSource] 来源，
 *   由 [top.wcpe.mc.testkit.provision.ServerJarProvisioner] 在 `*_JAR` 覆盖之后采用。
 *
 * 都按 `isTransitive = false` 只取声明的那个制品。
 *
 * **服务端侧的注册期分流（关键）**：注册期按坐标**纯函数**推导镜像路径
 * （`<mc-testkit-jars>/maven/<group 路径>/<artifact>/<version>/<artifact>-<version>.jar`）并做一次
 * 存在性检查——
 * - **命中**镜像 → [MavenServerJarSource] 直接持有该普通 `File`，**不创建任何 configuration**
 *   → 该任务被调度时既不触仓库、也不产生配置缓存解析负担（可离线）。
 * - **未命中** → 才创建 detached configuration（解析时机落在配置缓存存储期，无法避免）。
 *
 * 镜像文件对任务属**未声明输入**（与 env `*_JAR` 逃生口同口径：执行期取值、不进配置缓存输入集），
 * 故被删时由 provisioner 在执行期以中文错误拦下，绝不静默用错文件。
 *
 * **为什么单独一个类、而不是塞进 [TaskExecutionContext]**：动作闭包只要捕获了带 configuration 的对象，
 * Gradle 就会在该任务被调度时解析这些坐标。放进共享上下文会让不需要它们的任务（`stop<Key>Serve` /
 * `syncE2eRuntimeCache` / `npmInstallBot` 等）也去访问仓库；故本对象**只传给真正需要的任务注册点**。
 *
 * 实现 [java.io.Serializable]：任务动作闭包捕获图必须可序列化（配置缓存要求，不得含 `Project`）。
 */
internal class MavenCoordinateSources(
    private val pluginJars: Map<String, FileCollection>,
    private val serverJars: Map<String, MavenServerJarSource>,
) : java.io.Serializable {

    /** 已声明的依赖插件坐标（按声明顺序）。 */
    val pluginCoordinates: List<String> get() = pluginJars.keys.toList()

    /** 已声明的服务端 / 代理 jar 坐标（按声明顺序）。 */
    val serverCoordinates: List<String> get() = serverJars.keys.toList()

    /** 取某插件坐标解析出的 jar；解析失败即抛异常（由坐标解析管线汇总为中文报错）。 */
    fun pluginJarOf(coordinate: String): File = pluginJars.getValue(coordinate).singleFile

    /** 取某服务端 / 代理坐标的来源；null（未声明该坐标）由调用方先行判断。 */
    fun serverJarOf(coordinate: String): MavenServerJarSource = serverJars.getValue(coordinate)

    /**
     * 只含**依赖插件**坐标的视图（丢弃服务端 / 代理坐标）。
     *
     * 给「只注入插件、不起服务端」的任务用（当前是 `prepareE2e<Key>`）：这类任务捕获本对象时，
     * Gradle 会在配置缓存存储期解析其**全部** configuration，若把服务端坐标一并带上，一个与本次
     * prepare 无关、甚至拼写错误的坐标也会让构建失败。
     *
     * 仅可用于**不调用 [serverJarOf]** 的任务——本视图的 [serverCoordinates] 为空，
     * 对它调 [serverJarOf] 会抛 `NoSuchElementException`（这是刻意的响亮失败，不是静默回退）。
     */
    fun pluginsOnly(): MavenCoordinateSources = MavenCoordinateSources(pluginJars, emptyMap())

    companion object {
        /** 无任何坐标声明（配置期不建任何 configuration）。 */
        val EMPTY = MavenCoordinateSources(emptyMap(), emptyMap())

        /**
         * 注册期按声明坐标建来源；无声明时返回 [EMPTY]（零 Gradle 依赖解析配置）。
         *
         * @param cache 镜像路径推导（决定服务端坐标命中还是需要创建解析配置）。
         */
        fun of(
            project: Project,
            cache: JarCache,
            pluginCoordinates: List<String>,
            serverCoordinates: List<String>,
        ): MavenCoordinateSources {
            if (pluginCoordinates.isEmpty() && serverCoordinates.isEmpty()) return EMPTY
            return MavenCoordinateSources(
                pluginJars = pluginCoordinates.associateWith { detachedFor(project, it) },
                serverJars = serverCoordinates.associateWith { coordinate ->
                    val mirror = cache.mavenJarFile(coordinate)
                    if (mirror.isFile) {
                        // 命中镜像：直接持普通文件，不创建 configuration（不触仓库、配置缓存零解析负担）
                        FixedMavenServerJarSource(coordinate, mirror)
                    } else {
                        GradleMavenServerJarSource(coordinate, detachedFor(project, coordinate))
                    }
                },
            )
        }

        /** 单个坐标的不可传递 detached configuration（只取声明的那个制品）。 */
        private fun detachedFor(project: Project, coordinate: String): FileCollection =
            project.configurations.detachedConfiguration(project.dependencies.create(coordinate)).apply {
                isTransitive = false
            }
    }
}

/**
 * 持 detached configuration 的 [MavenServerJarSource]（镜像未命中时的真实解析来源）。
 *
 * 持 [FileCollection]（Gradle 配置缓存可序列化）而非 lambda：Kotlin lambda 的 `Function0` 不是
 * `Serializable`，做成 lambda 会在配置缓存存储期报「不可序列化」。
 */
private class GradleMavenServerJarSource(
    override val coordinate: String,
    private val files: FileCollection,
) : MavenServerJarSource {
    override fun file(): File = files.singleFile
}
