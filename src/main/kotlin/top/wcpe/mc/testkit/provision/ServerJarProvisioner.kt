package top.wcpe.mc.testkit.provision

import top.wcpe.mc.testkit.contract.McTestkitDefaults
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 服务端 / 代理 jar 的 **Maven 坐标来源**（内置下载与运行）。
 *
 * 刻意做成**接口**而非 `() -> File` lambda：Kotlin lambda 的 `Function0` 不是 `Serializable`，
 * 而实现本接口的类可以持有 Gradle `FileCollection`（配置缓存可序列化）。任务侧因此能既延迟解析、
 * 又满足配置缓存要求。
 *
 * 实现必须是**惰性**的：[file] 仅在真正需要该 jar 时才被调用。这一点是刻意的——env `*_JAR` 覆盖
 * 优先级高于本来源，若被提前求值，设了覆盖的构建仍会去触碰 Gradle 依赖解析（配置缓存存储期解析 +
 * 可能的仓库访问），白白付出代价。见 [ServerJarProvisioner.resolve] 的优先级说明。
 *
 * 实现还须 [java.io.Serializable]：注册期构造后随上下文进入任务动作闭包（配置缓存要求动作捕获图可序列化）。
 *
 * @property coordinate Maven 坐标 `group:artifact:version`（决定镜像落点）。
 */
interface MavenServerJarSource : java.io.Serializable {
    /** Maven 坐标 `group:artifact:version`。 */
    val coordinate: String

    /** 惰性取得该坐标对应的本地 jar（可能触发 Gradle 依赖解析）。 */
    fun file(): File
}

/**
 * 持单个固定文件的 [MavenServerJarSource]（命中镜像、或单测替身用）。
 *
 * 不依赖 Gradle API，可被 `provision/` 的单测直接构造。
 */
class FixedMavenServerJarSource(
    override val coordinate: String,
    private val jar: File,
) : MavenServerJarSource {
    override fun file(): File = jar
}

/**
 * 服务端 / 代理 jar 解析入口（内置下载与运行）。
 *
 * 解析优先级（对齐契约的"环境变量逃生口"，docs/API.md §3.3）：
 * 1. **`*_JAR` 覆盖存在** → 直接返回该路径、**全程不发网络、也不触碰 Maven 来源**（离线 / CI 友好；
 *    文件不存在则抛中文错误）。
 * 2. **Maven 坐标来源存在** → 命中**镜像**则直接复用（跳过解析）；否则取该坐标解析出的 jar 并
 *    **镜像**进 `<mc-testkit-jars>/maven/...`，下次即走命中分支。
 * 3. 否则定版本：`*_VERSION` 覆盖 >（后端）`MINECRAFT_VERSION` 覆盖 / [McTestkitDefaults.MINECRAFT_VERSION]
 *    缺省；BungeeCord 无版本 → 委托 [JarProvisionService] 经对应 API 解析构建 + 缓存命中复用 / 下载。
 *
 * env 取值经注入的 [readEnv] 取值器（`(name) -> String?`），保持纯函数边界、不耦合 Gradle `Project`
 * （任务侧传 `project.providers.environmentVariable(name).orNull`）；这样"设了 `*_JAR` 就不发网络"
 * 可被单测穷举（替身取值器 + 替身下载服务，零网络）。
 *
 * @property service 下载核心（注入以便单测替身）。
 * @property readEnv 环境变量取值器：给名、返回值（无则 null）。
 * @property cache 缓存路径推导（Maven 镜像落点与被删检测同源）。
 */
class ServerJarProvisioner internal constructor(
    private val service: JarProvisionService,
    private val readEnv: (String) -> String?,
    private val cache: JarCache = service.cache,
) {

    /**
     * 解析某平台 + 可选请求版本的 jar，返回可用的 `File`。
     *
     * @param platformId 平台 id（`paper`/`folia`/`spigot`/`velocity`/`waterfall`/`bungeecord`，大小写不敏感）。
     * @param requestedVersion DSL / 拓扑请求的版本（可空；env `*_VERSION` 覆盖优先于它，再退到缺省）。
     * @param mavenServer Maven 坐标来源（可空）；非空且 `*_JAR` 未覆盖时优先于内置下载。
     * @param logger 中文分级日志输出（默认 no-op）。
     * @throws IllegalArgumentException 平台不受支持时抛中文错误。
     * @throws IllegalStateException `*_JAR` 指向的文件不存在、或坐标解析结果缺失时抛中文错误。
     */
    fun resolve(
        platformId: String,
        requestedVersion: String? = null,
        mavenServer: MavenServerJarSource? = null,
        logger: (String) -> Unit = {},
    ): File {
        val platform = ProvisionPlatform.fromId(platformId)

        // ① `*_JAR` 覆盖：存在即直接返回、不发网络、不求值 Maven 来源
        val jarOverride = readEnv(platform.jarEnv)?.takeIf { it.isNotBlank() }
        if (jarOverride != null) {
            val file = File(jarOverride)
            check(file.isFile) {
                "环境变量 ${platform.jarEnv} 指向的 jar 不存在：$jarOverride（请提供有效路径或移除该变量以走下载）。"
            }
            logger("使用 ${platform.jarEnv} 覆盖的 ${platform.id} jar：${file.absolutePath}（跳过下载）")
            return file
        }

        // ② Maven 坐标来源：命中镜像即复用（跳过解析）；否则解析后镜像
        if (mavenServer != null) {
            return resolveViaMaven(platform, mavenServer, logger)
        }

        // ③ 定版本：`*_VERSION` 覆盖 > 请求版本 > 缺省（BungeeCord 无版本）
        val version = resolveVersion(platform, requestedVersion)

        // ④ 委托下载核心（命中缓存复用 / 否则下载）
        return service.resolve(platform, version, logger)
    }

    /**
     * 解析 Maven 坐标来源：镜像命中则复用，否则取坐标 jar 并镜像落盘。
     *
     * 镜像文件对任务属**未声明输入**（与 env `*_JAR` 逃生口同口径：执行期取值、不进配置缓存输入集）。
     * 故此处必须复核它仍在：镜像在「配置缓存条目被重用」期间被删掉时，宁可抛**中文错误**提示重跑，
     * 也绝不静默拿错文件或拿不到文件。
     */
    private fun resolveViaMaven(
        platform: ProvisionPlatform,
        mavenServer: MavenServerJarSource,
        logger: (String) -> Unit,
    ): File {
        val mirror = cache.mavenJarFile(mavenServer.coordinate)
        if (mirror.isFile) {
            logger("命中 maven 镜像：${platform.id} ${mavenServer.coordinate} → ${mirror.absolutePath}")
            return mirror
        }
        val resolved = mavenServer.file()
        check(resolved.isFile) {
            "Maven 坐标 ${mavenServer.coordinate} 解析结果不存在：${resolved.absolutePath}" +
                "（请检查该坐标的仓库声明与凭据，或移除镜像缓存后重试）。"
        }
        logger("镜像 maven 服务端 jar：${resolved.absolutePath} → ${mirror.absolutePath}")
        return mirrorInto(resolved, mirror, mavenServer.coordinate)
    }

    /**
     * 把坐标解析出的 jar **拷贝**进镜像位置（源文件属 Gradle 依赖缓存，不可移动）。
     *
     * 先写到镜像**同目录**的临时文件再 `Files.move` 原子替换，故并发读者只会看到「无文件」或
     * 「完整文件」——与 [JarProvisionService] 的下载落盘同口径，避免半成品镜像被复用。
     *
     * 建目录 / 建临时文件也在 `try` 内：它们的 `IOException` 与拷贝失败一样，都以**中文**错误抛出。
     */
    private fun mirrorInto(source: File, mirror: File, coordinate: String): File {
        var temp: File? = null
        try {
            val directory = mirror.absoluteFile.parentFile
            directory.mkdirs()
            temp = Files.createTempFile(directory.toPath(), "mc-testkit-maven-", ".jar.tmp").toFile()
            source.copyTo(temp, overwrite = true)
            try {
                Files.move(
                    temp.toPath(),
                    mirror.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (ignored: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), mirror.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return mirror
        } catch (ex: IOException) {
            throw IllegalStateException(
                "无法把 Maven 坐标 $coordinate 解析出的 jar 镜像到缓存：${mirror.absolutePath}。",
                ex,
            )
        } finally {
            temp?.delete()
        }
    }

    /** 定版本：BungeeCord 返回 null；其余按 env 覆盖 > 请求 > 缺省，末了按平台粒度归一。 */
    private fun resolveVersion(platform: ProvisionPlatform, requestedVersion: String?): String? {
        if (platform.versionEnv == null) {
            // BungeeCord：无版本概念
            return null
        }
        val resolved = readEnv(platform.versionEnv)?.takeIf { it.isNotBlank() }
            ?: requestedVersion?.takeIf { it.isNotBlank() }
            ?: defaultVersion(platform)
        // 按平台版本粒度归一：Waterfall 在 PaperMC 仅按 major.minor 发布（1.20.1 → 1.20），否则 404；其余原样。
        return platform.downloadVersion(resolved)
    }

    /** Velocity 与 Minecraft 后端使用不同的版本命名空间，默认值不得混用。 */
    private fun defaultVersion(platform: ProvisionPlatform): String =
        if (platform == ProvisionPlatform.VELOCITY) McTestkitDefaults.VELOCITY_VERSION else McTestkitDefaults.MINECRAFT_VERSION

    companion object {
        /**
         * 用持久缓存根目录构建解析器（生产用：真实 HTTP + 缓存）。
         *
         * @param cacheRoot 持久缓存根目录（任务侧传 Gradle 共享缓存目录，不写死本机绝对路径）。
         * @param readEnv 环境变量取值器（任务侧传 `project.providers.environmentVariable(name).orNull`）。
         */
        fun create(cacheRoot: File, readEnv: (String) -> String?): ServerJarProvisioner {
            val cache = JarCache(cacheRoot)
            return ServerJarProvisioner(JarProvisionService(cache), readEnv, cache)
        }
    }
}
