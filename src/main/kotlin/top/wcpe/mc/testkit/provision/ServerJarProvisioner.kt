package top.wcpe.mc.testkit.provision

import top.wcpe.mc.testkit.contract.McTestkitDefaults
import java.io.File

/**
 * 服务端 / 代理 jar 解析入口（内置下载与运行）。
 *
 * 解析优先级（对齐契约的"环境变量逃生口"，docs/API.md §3.3）：
 * 1. **`*_JAR` 覆盖存在** → 直接返回该路径、**全程不发网络**（离线 / CI 友好；文件不存在则抛中文错误）。
 * 2. 否则定版本：`*_VERSION` 覆盖 >（后端）`MINECRAFT_VERSION` 覆盖 / [McTestkitDefaults.MINECRAFT_VERSION]
 *    缺省；BungeeCord 无版本。
 * 3. 委托 [JarProvisionService] 经对应 API 解析构建 + 缓存命中复用 / 下载。
 *
 * env 取值经注入的 [readEnv] 取值器（`(name) -> String?`），保持纯函数边界、不耦合 Gradle `Project`
 * （任务侧传 `project.providers.environmentVariable(name).orNull`）；这样"设了 `*_JAR` 就不发网络"
 * 可被单测穷举（替身取值器 + 替身下载服务，零网络）。
 *
 * @property service 下载核心（注入以便单测替身）。
 * @property readEnv 环境变量取值器：给名、返回值（无则 null）。
 */
class ServerJarProvisioner internal constructor(
    private val service: JarProvisionService,
    private val readEnv: (String) -> String?,
) {

    /**
     * 解析某平台 + 可选请求版本的 jar，返回可用的 `File`。
     *
     * @param platformId 平台 id（`paper`/`folia`/`spigot`/`velocity`/`waterfall`/`bungeecord`，大小写不敏感）。
     * @param requestedVersion DSL / 拓扑请求的版本（可空；env `*_VERSION` 覆盖优先于它，再退到缺省）。
     * @param logger 中文分级日志输出（默认 no-op）。
     * @throws IllegalArgumentException 平台不受支持时抛中文错误。
     * @throws IllegalStateException `*_JAR` 指向的文件不存在时抛中文错误。
     */
    fun resolve(platformId: String, requestedVersion: String? = null, logger: (String) -> Unit = {}): File {
        val platform = ProvisionPlatform.fromId(platformId)

        // ① `*_JAR` 覆盖：存在即直接返回、不发网络
        val jarOverride = readEnv(platform.jarEnv)?.takeIf { it.isNotBlank() }
        if (jarOverride != null) {
            val file = File(jarOverride)
            check(file.isFile) {
                "环境变量 ${platform.jarEnv} 指向的 jar 不存在：$jarOverride（请提供有效路径或移除该变量以走下载）。"
            }
            logger("使用 ${platform.jarEnv} 覆盖的 ${platform.id} jar：${file.absolutePath}（跳过下载）")
            return file
        }

        // ② 定版本：`*_VERSION` 覆盖 > 请求版本 > 缺省（BungeeCord 无版本）
        val version = resolveVersion(platform, requestedVersion)

        // ③ 委托下载核心（命中缓存复用 / 否则下载）
        return service.resolve(platform, version, logger)
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
        fun create(cacheRoot: File, readEnv: (String) -> String?): ServerJarProvisioner =
            ServerJarProvisioner(JarProvisionService(JarCache(cacheRoot)), readEnv)
    }
}
