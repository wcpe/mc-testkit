package top.wcpe.mc.testkit.provision

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * 按 平台 + 版本 解析并缓存 jar（FR-02 下载核心）。
 *
 * 流程：
 * 1. 定构建号——PaperMC 平台取版本的最新构建；BungeeCord 取最新成功 Jenkins 构建。
 * 2. 缓存命中且本地 hash 与记录一致 → 直接复用，不发下载。
 * 3. 否则下载到临时文件 → PaperMC 校验远端 sha256 / BungeeCord 校验"结构合法 jar" → 移入缓存。
 *
 * **不含** env `*_JAR` / `*_VERSION` 覆盖逻辑——那由上层 [ServerJarProvisioner] 处理（覆盖优先、
 * 设了 `*_JAR` 全程不发网络）。本服务只管"真要下载时怎么下、怎么缓存"。
 *
 * 依赖经构造注入（两 API + 下载器 + 缓存），便于单测替身；默认走真实 HTTP。
 *
 * @property cache 缓存路径推导。
 * @property paperApi PaperMC 下载 API 客户端。
 * @property bungeeApi SpigotMC Jenkins 客户端。
 * @property download 实际下载函数（url, 目标文件, 日志）→ 落地文件；注入以便单测。
 */
internal class JarProvisionService(
    private val cache: JarCache,
    private val paperApi: PaperDownloadsApi = PaperDownloadsApi(),
    private val bungeeApi: BungeeCordJenkinsApi = BungeeCordJenkinsApi(),
    private val download: (String, File, (String) -> Unit) -> Unit = { url, dest, log -> Downloader.download(url, dest, log) },
) {

    /**
     * 解析（必要时下载）某平台 + 版本的 jar，返回缓存中的 `File`。
     *
     * @param platform 平台。
     * @param version 版本（BungeeCord 忽略，传 null 即可）。
     * @param logger 中文分级日志输出（默认 no-op）。
     */
    fun resolve(platform: ProvisionPlatform, version: String?, logger: (String) -> Unit = {}): File {
        return if (platform.usesPaperApi) {
            resolveViaPaper(platform, requireVersion(platform, version), logger)
        } else {
            resolveBungeeCord(platform, logger)
        }
    }

    /** PaperMC 平台（Paper/Folia/Velocity/Waterfall）：取最新构建 → 命中复用 / 校验 sha256 下载。 */
    private fun resolveViaPaper(platform: ProvisionPlatform, version: String, logger: (String) -> Unit): File {
        val project = requireNotNull(platform.paperProject)
        val build = paperApi.latestBuild(project, version)
        val cached = cache.jarFile(platform, version, build)
        if (cached.isFile) {
            logger("命中缓存：${platform.id} $version 构建 $build")
            return cached
        }

        logger("下载 ${platform.id} $version 构建 $build …")
        val paperDownload = paperApi.download(project, version, build)
        val url = paperApi.downloadUrl(paperDownload)
        val temp = createTempJar(cached, platform, build)
        try {
            download(url, temp, logger)
            val actual = temp.sha256()
            check(actual == paperDownload.sha256) {
                "下载产物 sha256 校验失败：期望 ${paperDownload.sha256}，实际 $actual（${platform.id} $version 构建 $build）。"
            }
            return moveIntoCache(temp, cached)
        } finally {
            temp.delete()
        }
    }

    /** BungeeCord：取最新成功 Jenkins 构建 → 命中复用 / 下载后校验"结构合法 jar"（无远端 hash）。 */
    private fun resolveBungeeCord(platform: ProvisionPlatform, logger: (String) -> Unit): File {
        val build = bungeeApi.lastSuccessfulBuild()
        val cached = cache.jarFile(platform, null, build)
        if (cached.isFile) {
            logger("命中缓存：${platform.id} 构建 $build")
            return cached
        }

        logger("下载 ${platform.id} 构建 $build …")
        val url = bungeeApi.downloadUrl(build)
        val temp = createTempJar(cached, platform, build)
        try {
            download(url, temp, logger)
            requireValidJar(temp, platform, build)
            return moveIntoCache(temp, cached)
        } finally {
            temp.delete()
        }
    }

    /** 校验文件是结构合法的 jar（BungeeCord 无远端 hash 时的下载完整性兜底）。 */
    private fun requireValidJar(file: File, platform: ProvisionPlatform, build: Int) {
        try {
            JarFile(file).use { }
        } catch (ex: Exception) {
            throw IllegalStateException("下载的 ${platform.id} 构建 $build 不是合法 jar 文件。", ex)
        }
    }

    /**
     * 将临时文件**原子**移入缓存目标（覆盖已存在）。temp 与 destination 同目录（见 [createTempJar]），
     * 故 [StandardCopyOption.ATOMIC_MOVE] 走同卷原子重命名——并发读者只会看到「无文件」或「完整文件」，
     * 杜绝旧实现「跨卷退化为非原子 copyTo、覆盖期间被读到半成品 jar」的缓存损坏（review J2）。
     * 极少数文件系统不支持原子移动时退回非原子替换；移动失败抛**中文**错误（修旧 `check(...)` 死代码吞错）。
     */
    private fun moveIntoCache(temp: File, destination: File): File {
        destination.parentFile?.mkdirs()
        try {
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: java.io.IOException) {
            throw IllegalStateException("无法将下载产物移入缓存：${destination.absolutePath}。", ex)
        }
        return destination
    }

    /** 在缓存目标**同目录**建临时文件，保证与目标同卷——[moveIntoCache] 才能原子重命名（避免系统 temp 跨卷）。 */
    private fun createTempJar(destination: File, platform: ProvisionPlatform, build: Int): File {
        val dir = destination.absoluteFile.parentFile
        dir.mkdirs()
        return Files.createTempFile(dir.toPath(), "mc-testkit-${platform.id}-$build-", ".jar.tmp").toFile()
    }

    private fun requireVersion(platform: ProvisionPlatform, version: String?): String {
        require(!version.isNullOrBlank()) { "平台 ${platform.id} 需要指定版本号才能下载。" }
        return version
    }
}
