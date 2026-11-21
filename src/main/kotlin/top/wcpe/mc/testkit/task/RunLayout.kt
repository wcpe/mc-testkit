package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.contract.McTestkitRunDirectories
import top.wcpe.mc.testkit.topology.ResolvedBackend
import java.io.File

/**
 * E2E 运行目录与缓存的路径推导（任务自动编排 整合器，纯函数）。
 *
 * 只依赖入参的 `buildDir` / `gradleUserHome` / `rootDir`，不读全局状态、不写死本机绝对路径
 * （可移植 / 幂等可重跑，NFR）。任务侧用 `project.layout` / `gradle.gradleUserHomeDir` /
 * `rootProject` 解析出这三个根目录后构造本对象，其余子目录由此派生，便于单测穷举路径关系。
 *
 * 实现 [java.io.Serializable]：注册期构造后可被任务动作闭包安全捕获（Gradle 9.x 配置缓存要求
 * 动作捕获图可序列化，不得含 `Project`）。
 *
 * @property buildDir 当前工程 build 目录（如 `<project>/build`）。
 * @property gradleUserHome Gradle 用户主目录（jar 下载缓存挂这下面，多工程共享、跨 clean 存活）。
 * @property rootDir 根工程目录（持久运行库缓存与默认 bot 目录相对它解析）。
 * @property backendRunDirOverrides 声明了持久运行目录（`backend { runDirectory }`）的后端 → 已解析的绝对目录
 *   （见 [resolvePersistentBackendRunDirs]）；未声明的后端走默认的 `run-<后端名>`。
 */
class RunLayout(
    private val buildDir: File,
    private val gradleUserHome: File,
    private val rootDir: File,
    private val backendRunDirOverrides: Map<String, File> = emptyMap(),
) : java.io.Serializable {
    /** 同一布局换上持久运行目录覆盖（其余根目录不变）。 */
    fun withBackendRunDirOverrides(overrides: Map<String, File>): RunLayout =
        RunLayout(buildDir, gradleUserHome, rootDir, overrides.toMap())

    /**
     * 该后端是否声明了持久运行目录：是则编排不清空它的运行目录，只整理框架自己产出的内容
     * （见 `stageBackendRuntime` 的 `persistent`）。
     */
    fun isPersistentBackendRunDir(backendName: String): Boolean = backendName in backendRunDirOverrides

    /** E2E 工作根：`build/mc-testkit`（clean 即清，含运行目录 / 结果 / 代理运行目录）。 */
    val workRoot: File get() = File(buildDir, McTestkitRunDirectories.WORK_DIR_NAME)

    /**
     * 单个后端节点的独立运行目录：`run-<backendName>`（声明了持久运行目录时为该目录）。
     *
     * 多版本矩阵必须隔离 `libraries`/`cache`：新 Paper 的 Java 16+ 依赖会被旧版（Java 8）
     * 误用导致 `UnsupportedClassVersionError`。与集群 [clusterBackendRunDir] 同形。
     *
     * 历史上的共享目录 `run/`（曾有一个返回它的 getter）已删除：它不归属任何后端，无恢复下标，
     * 留着只会留下一个「框架永不铺筑、也不回写」的目录可被调用。
     */
    fun backendRunDir(backendName: String): File = clusterBackendRunDir(backendName)

    /** 桩写出结果文件、机器人日志 / pid 的目录。 */
    val resultsDir: File get() = File(workRoot, "results")

    /** 后台代理的运行目录（cwd；与后端运行目录隔离，避免互相覆盖配置）。 */
    val proxyRunDir: File get() = File(workRoot, McTestkitRunDirectories.PROXY_RUN_DIR_NAME)

    /** 服务端 / 代理 jar 的下载缓存根（挂 Gradle 用户主目录，跨工程 / 跨 clean 复用，避免反复下载）。 */
    val jarCacheRoot: File get() = File(gradleUserHome, "caches/$JAR_CACHE_DIR_NAME")

    /**
     * 持久运行库缓存根（运行库 / 资源回写此处，`clean` 后可恢复而非冷启动）。
     *
     * **为什么按后端分目录（见 [persistentBackendCacheDir]）而不是共用一份**：
     * `libraries` / `cache` / `versions` 是**版本敏感**的——新 Paper 拉到 Java 16+ 依赖，
     * 被旧版（Java 8）服务端复用时抛 `UnsupportedClassVersionError`。共用一份缓存会让
     * 多版本矩阵在恢复时互相污染，正是 [backendRunDir] 那段注释要防的问题。
     */
    val persistentServerBaseDir: File get() = File(rootDir, ".gradle/${McTestkitRunDirectories.WORK_DIR_NAME}/server-base")

    /**
     * 某后端的持久运行库缓存目录：`server-base/<backendName>`。
     *
     * 与 [backendRunDir] 的 `run-<backendName>` 一一对应，使「回写」与「恢复」按下标对齐，
     * 而不是把多个后端的运行库混在一起。
     */
    fun persistentBackendCacheDir(backendName: String): File = File(persistentServerBaseDir, backendName)

    /** 某代理节点的 pid 文件（落结果目录，供收尾按 pid 杀）。 */
    fun proxyPidFile(proxyName: String): File = File(resultsDir, "proxy-$proxyName.pid")

    /**
     * 某集群后端的运行目录（cwd；与其它后端 / 单后端运行目录隔离，集群编排）。
     *
     * 目录名取自 [McTestkitRunDirectories.backendRunDirName]——对外契约同一真源。声明了持久运行目录的后端
     * 返回该目录（[backendRunDirOverrides]），所有编排路径经此一处生效。
     */
    fun clusterBackendRunDir(backendName: String): File =
        backendRunDirOverrides[backendName] ?: File(workRoot, McTestkitRunDirectories.backendRunDirName(backendName))

    /** 某集群后端的 pid 文件（落结果目录，供收尾按 pid 杀）。 */
    fun clusterBackendPidFile(backendName: String): File = File(resultsDir, "backend-$backendName.pid")

    /** 把 [botDirProperty]（缺省 [DEFAULT_BOT_DIR]）解析为相对根工程的机器人目录。 */
    fun botDir(botDirProperty: String?): File {
        val raw = botDirProperty?.takeIf { it.isNotBlank() } ?: DEFAULT_BOT_DIR
        val candidate = File(raw)
        return if (candidate.isAbsolute) candidate else File(rootDir, raw)
    }

    companion object {
        /** jar 下载缓存目录名（Gradle caches 下）。 */
        const val JAR_CACHE_DIR_NAME = "mc-testkit-jars"

        /** 默认机器人目录（相对根工程；消费方照抄 template/bot 后默认放这）。 */
        const val DEFAULT_BOT_DIR = "e2e-bot"

        /** 机器人入口脚本相对 bot 目录的路径（template 既定，见 template/bot/src/connectAndWait.js）。 */
        const val BOT_SCRIPT_RELATIVE = "src/connectAndWait.js"

        /**
         * clean 运行目录时保留的运行库 / 缓存子目录（避免连续重跑反复下载）。
         *
         * `libraries` 是 paperclip 自有的运行库下载目标，保留它只为省下重复下载；消费方注入运行库
         * 用的 `server-libraries`（`provision/` 的 `INJECTED_LIBRARY_DIR_NAME`）**刻意不在**此集合内：
         * 它每轮由消费方 prepare 重建，陈旧注入不得跨轮泄漏到下一轮 classpath。
         */
        val PRESERVED_RUNTIME_CACHE_ENTRIES = setOf("libraries", "cache", "assets", "versions")
    }
}

/**
 * 把后端的持久运行目录声明（`backend { runDirectory }`）解析为绝对目录，并做注册期校验。
 *
 * 相对路径按工程目录解析。拒绝三类声明（中文 [GradleException]）：
 * - 是工程目录本身或其上级：服务端会把世界 / 配置 / 插件铺满整个工程；
 * - 与工作根 [workRoot] 重叠（在其内或是其上级）：那里随 `clean` 清空，代理目录每轮整删，存不住东西；
 * - 两个后端解析到同一目录或互相嵌套：会抢同一份世界与配置。
 *
 * 路径比较走 [java.nio.file.Path.startsWith]（按路径段，大小写规则随平台），不走字符串前缀。
 *
 * @return 后端名 → 规范化后的绝对目录；未声明的后端不出现在结果里。
 */
internal fun resolvePersistentBackendRunDirs(
    projectDir: File,
    workRoot: File,
    backends: List<ResolvedBackend>,
): Map<String, File> {
    val project = projectDir.canonicalFile
    val work = workRoot.canonicalFile
    val resolved = LinkedHashMap<String, File>()
    backends.forEach { backend ->
        val declaration = backend.runDirectory ?: return@forEach
        val declared = File(declaration)
        val dir = (if (declared.isAbsolute) declared else File(project, declaration)).canonicalFile
        val prefix = "mcTestkit 后端「${backend.name}」的 runDirectory「$declaration」解析为 ${dir.absolutePath}"
        if (project.isSameOrInside(dir)) {
            throw GradleException(
                "$prefix，是工程目录本身或其上级；服务端会把世界、配置、插件铺满整个目录。" +
                    "请改用专门的子目录（如 run/${backend.name}）。",
            )
        }
        if (dir.isSameOrInside(work) || work.isSameOrInside(dir)) {
            throw GradleException(
                "$prefix，与框架工作目录 ${work.absolutePath} 重叠；那里随 clean 清空、代理目录每轮整删，存不住东西。" +
                    "请改到工程下的其它目录（如 run/${backend.name}）。",
            )
        }
        resolved.entries.firstOrNull { (_, other) -> dir.isSameOrInside(other) || other.isSameOrInside(dir) }
            ?.let { (otherName, other) ->
                throw GradleException(
                    "$prefix，与后端「$otherName」的 runDirectory（${other.absolutePath}）是同一目录或互相嵌套；" +
                        "每个后端需要独立的运行目录。",
                )
            }
        resolved[backend.name] = dir
    }
    return resolved
}

private fun File.isSameOrInside(ancestor: File): Boolean = toPath().startsWith(ancestor.toPath())

/**
 * 清空运行目录但保留运行库 / 缓存子目录（[RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES]）。
 *
 * 幂等可重跑（NFR）：运行目录每轮重建，但运行库 / 资源 / 版本目录保留以免反复下载。
 * 纯 IO、无 Gradle 依赖，便于用临时目录单测。
 */
fun cleanRunDirPreservingRuntimeCaches(runDir: File) {
    if (!runDir.exists()) {
        return
    }
    // Windows 下刚被强杀的服务端可能仍持有旧运行文件句柄(杀后 Defender 等仍可能短暂持有)；
    // 对删除做有限重试,窗口与覆盖写一致取 60s。
    val deadline = System.currentTimeMillis() + 60_000L
    while (System.currentTimeMillis() < deadline) {
        val remaining = runDir.listFiles()?.filter { it.name !in RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES } ?: return
        if (remaining.isEmpty()) return
        remaining.forEach { child -> child.deleteRecursively() }
        if (remaining.none { it.exists() }) return
        Thread.sleep(250)
    }
}

/**
 * 从持久缓存恢复某后端运行目录的运行库 / 缓存子目录（[RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES]）。
 *
 * **为什么必须有这一步**：只回写不恢复等于缓存从未生效——运行目录每轮由
 * [cleanRunDirPreservingRuntimeCaches] 重建，若不同时恢复，冷启动依旧反复下载
 * （Paper 的 paperclip 会把原版服务端 jar 下到 `cache/`，约 50MB，每次重跑都要重来）。
 *
 * 覆盖语义：**只补缺失，不覆盖已有**。运行目录里已有的内容是本轮真实产物，比缓存新；
 * 用旧缓存覆盖它可能造成版本回退（缓存来自上一轮的别的构建）。
 *
 * 纯 IO、无 Gradle 依赖，便于用临时目录单测。
 *
 * @param cacheDir 该后端的持久缓存目录（见 [RunLayout.persistentBackendCacheDir]）；不存在即跳过
 * @param runDir 目标运行目录；不存在会被创建
 * @return 实际补上的文件数（0 表示缓存不存在或运行目录已齐全）。调用方据此决定汇报口径——
 *   否则「恢复」日志在一次真正的冷启动（缓存还没被回写过、恢复量为 0）里也会照打，读日志的人
 *   会以为缓存生效了，实际这一轮仍在全量下载。
 */
fun restoreBackendRuntimeCache(cacheDir: File, runDir: File): Int {
    if (!cacheDir.exists()) {
        return 0
    }
    return RunLayout.PRESERVED_RUNTIME_CACHE_ENTRIES.sumOf { name ->
        val source = File(cacheDir, name)
        if (!source.exists()) {
            return@sumOf 0
        }
        val target = File(runDir, name)
        // 该子目录本次补上的文件数，即 sumOf 的加数：已有同名目录则逐项补齐缺失文件
        // （不覆盖运行目录里已有的），否则整目录拷贝。
        if (target.exists()) {
            mergeMissing(source, target)
        } else {
            runDir.mkdirs()
            source.copyRecursively(target, overwrite = false)
            source.countFiles()
        }
    }
}

/** 递归统计目录下的文件数（恢复了多少个文件用于汇报，不关心目录）。 */
private fun File.countFiles(): Int = walkTopDown().count { it.isFile }

/**
 * 把 [source] 中 [target] 尚不存在的文件补过去，返回实际补上的文件数。
 *
 * 逐文件判断而不是整目录覆盖：缓存目录可能混有上一轮的陈旧文件，
 * 整目录覆盖会把它们带进来；只补缺失则运行目录里已有的（本轮产物）始终优先。
 */
private fun mergeMissing(source: File, target: File): Int {
    if (source.isDirectory) {
        target.mkdirs()
        val children = source.listFiles() ?: return 0
        return children.sumOf { child -> mergeMissing(child, File(target, child.name)) }
    }
    if (target.exists()) {
        return 0
    }
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = false)
    return 1
}
