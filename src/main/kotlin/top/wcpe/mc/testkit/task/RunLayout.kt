package top.wcpe.mc.testkit.task

import java.io.File

/**
 * E2E 运行目录与缓存的路径推导（任务自动编排 整合器，纯函数）。
 *
 * 只依赖入参的 `buildDir` / `gradleUserHome` / `rootDir`，不读全局状态、不写死本机绝对路径
 * （可移植 / 幂等可重跑，NFR）。任务侧用 `project.layout` / `gradle.gradleUserHomeDir` /
 * `rootProject` 解析出这三个根目录后构造本对象，其余子目录由此派生，便于单测穷举路径关系。
 *
 * @property buildDir 当前工程 build 目录（如 `<project>/build`）。
 * @property gradleUserHome Gradle 用户主目录（jar 下载缓存挂这下面，多工程共享、跨 clean 存活）。
 * @property rootDir 根工程目录（持久运行库缓存与默认 bot 目录相对它解析）。
 */
class RunLayout(
    private val buildDir: File,
    private val gradleUserHome: File,
    private val rootDir: File,
) {
    /** E2E 工作根：`build/mc-testkit`（clean 即清，含运行目录 / 结果 / 代理运行目录）。 */
    val workRoot: File get() = File(buildDir, WORK_DIR_NAME)

    /** 前台被测后端的运行目录（cwd）。 */
    val runDir: File get() = File(workRoot, "run")

    /** 桩写出结果文件、机器人日志 / pid 的目录。 */
    val resultsDir: File get() = File(workRoot, "results")

    /** 后台代理的运行目录（cwd；与后端运行目录隔离，避免互相覆盖配置）。 */
    val proxyRunDir: File get() = File(workRoot, "run-proxy")

    /** 服务端 / 代理 jar 的下载缓存根（挂 Gradle 用户主目录，跨工程 / 跨 clean 复用，避免反复下载）。 */
    val jarCacheRoot: File get() = File(gradleUserHome, "caches/$JAR_CACHE_DIR_NAME")

    /** 持久运行库缓存（运行库 / 资源回写此处，clean 后可恢复而非冷启动）。 */
    val persistentServerBaseDir: File get() = File(rootDir, ".gradle/$WORK_DIR_NAME/server-base")

    /** 某代理节点的 pid 文件（落结果目录，供收尾按 pid 杀）。 */
    fun proxyPidFile(proxyName: String): File = File(resultsDir, "proxy-$proxyName.pid")

    /** 某集群后端的运行目录（cwd；与其它后端 / 单后端运行目录隔离，集群编排）。 */
    fun clusterBackendRunDir(backendName: String): File = File(workRoot, "run-$backendName")

    /** 某集群后端的 pid 文件（落结果目录，供收尾按 pid 杀）。 */
    fun clusterBackendPidFile(backendName: String): File = File(resultsDir, "backend-$backendName.pid")

    /** 把 [botDirProperty]（缺省 [DEFAULT_BOT_DIR]）解析为相对根工程的机器人目录。 */
    fun botDir(botDirProperty: String?): File {
        val raw = botDirProperty?.takeIf { it.isNotBlank() } ?: DEFAULT_BOT_DIR
        val candidate = File(raw)
        return if (candidate.isAbsolute) candidate else File(rootDir, raw)
    }

    companion object {
        /** E2E 工作目录名（build 下与持久缓存下同名）。 */
        const val WORK_DIR_NAME = "mc-testkit"

        /** jar 下载缓存目录名（Gradle caches 下）。 */
        const val JAR_CACHE_DIR_NAME = "mc-testkit-jars"

        /** 默认机器人目录（相对根工程；消费方照抄 template/bot 后默认放这）。 */
        const val DEFAULT_BOT_DIR = "e2e-bot"

        /** 机器人入口脚本相对 bot 目录的路径（template 既定，见 template/bot/src/connectAndWait.js）。 */
        const val BOT_SCRIPT_RELATIVE = "src/connectAndWait.js"

        /** clean 运行目录时保留的运行库 / 缓存子目录（避免连续重跑反复下载）。 */
        val PRESERVED_RUNTIME_CACHE_ENTRIES = setOf("libraries", "cache", "assets", "versions")
    }
}

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
