package top.wcpe.mc.testkit.provision

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把 API 响应文本解析为制品下载地址（纯函数）。
 *
 * **为什么是纯函数**：解析逻辑可喂固定样本文本单测、不打网络（对齐 [PaperDownloadsApi] 的
 * `parseDownload` 风格）。
 *
 * **实现须 [java.io.Serializable]**：消费方可能把它捕获进任务动作闭包，而配置缓存要求动作捕获图
 * 可序列化。**注意 Kotlin SAM 转换产出的 lambda 不可序列化**——请用具名类或 object 表达式实现
 * （与 [MavenServerJarSource] 同款约束）。
 *
 * 便利实现见 [ArtifactUrlResolver.lastUrlMatch]。
 */
fun interface ArtifactUrlResolver : java.io.Serializable {
    /**
     * @param responseText [ExternalArtifactSource.ApiResolved.apiUrl] 的响应文本。
     * @return 制品下载地址。
     * @throws IllegalStateException 响应中找不到地址时抛**中文**错误。
     */
    fun resolve(responseText: String): String

    companion object {
        /**
         * 按正则从响应中取**最后一个**匹配的捕获组 1 作为下载地址。
         *
         * 「取最后一个」对应常见约定：版本数组按升序排列，末项即最新；故不硬编码带内容哈希的地址
         * （哈希随再上传会变，硬编码必然失效）。
         *
         * @param regex 含捕获组 1（下载地址）的正则。
         */
        fun lastUrlMatch(regex: String): ArtifactUrlResolver = LastUrlMatchResolver(regex)
    }
}

/**
 * [ArtifactUrlResolver.lastUrlMatch] 的可序列化实现。
 *
 * 刻意用具名类而非 lambda：Kotlin SAM 转换产出的 lambda 不是 `Serializable`，进入配置缓存会失败。
 */
private class LastUrlMatchResolver(private val regex: String) : ArtifactUrlResolver {
    override fun resolve(responseText: String): String =
        Regex(regex).findAll(responseText).map { it.groupValues[1] }.lastOrNull()
            ?: throw IllegalStateException("未从响应中解析到制品下载地址（正则：$regex）。")

    override fun equals(other: Any?): Boolean = other is LastUrlMatchResolver && other.regex == regex

    override fun hashCode(): Int = regex.hashCode()
}

/**
 * 外部制品来源（ADR-0017）：**平台枚举之外**的第三方制品。
 *
 * 内置下载只覆盖六个平台（Paper/Folia/Spigot + Velocity/Waterfall/BungeeCord）；验收常需注入
 * 第三方插件（占位符后端、扩展、诊断 agent 等），其制品不在平台枚举内。本类型把这类来源**声明式**
 * 表达出来，由 [ExternalArtifactProvisioner] 下载并缓存。
 *
 * 两种形态：
 * - [FixedUrl]：地址已知且稳定（如 Hangar 的固定版本下载端点）。
 * - [ApiResolved]：地址须先查 API（如返回的 URL 内嵌内容哈希、随再上传而变）。
 *
 * **刻意不做**（守 ADR-0001「保持精简」，见 ADR-0017）：不实现插件市场 API / 搜索 / 版本列表 /
 * 鉴权管理，不做依赖传递解析——只拉声明的那个制品。
 *
 * 实现须 [java.io.Serializable]（同上）。
 */
sealed class ExternalArtifactSource : java.io.Serializable {
    /** 缓存段标识：决定缓存子目录名（`<cacheRoot>/external/<id>/`）。须为单层安全目录名。 */
    abstract val id: String

    /** 落到运行目录 / 缓存时的目标文件名（含扩展名）。 */
    abstract val fileName: String

    /** 期望的制品 SHA-256（小写十六进制）；null 表示不校验完整性。 */
    abstract val expectedSha256: String?

    /** 地址已知且稳定。 */
    data class FixedUrl(
        override val id: String,
        override val fileName: String,
        /** 制品下载地址。 */
        val url: String,
        override val expectedSha256: String? = null,
    ) : ExternalArtifactSource()

    /** 地址经 API 解析得到：先取 [apiUrl] 的响应文本，再交 [resolver] 解析出地址。 */
    data class ApiResolved(
        override val id: String,
        override val fileName: String,
        /** 返回下载地址的 API 端点。 */
        val apiUrl: String,
        /** 响应文本 → 下载地址的纯函数解析器。 */
        val resolver: ArtifactUrlResolver,
        override val expectedSha256: String? = null,
    ) : ExternalArtifactSource()
}

/**
 * 外部制品下载与缓存（ADR-0017）。
 *
 * 流程与内置下载同构：
 * 1. **先查缓存**——命中即直接复用、**全程不发网络**。这条对 [ExternalArtifactSource.ApiResolved]
 *    尤其关键：若每次都要查 API，离线 / 弱网复验会被外网波动阻塞。
 * 2. 未命中才解析地址（[ExternalArtifactSource.FixedUrl] 直接用；[ExternalArtifactSource.ApiResolved]
 *    先取 API 文本再交 resolver）→ 下载到**同目录**临时文件 → 校验 → **原子**移入缓存。
 *
 * 原子移动的意义：并发读者只会看到「无文件」或「完整文件」，不会读到半成品。
 *
 * 缓存布局 `<cacheRoot>/external/<id>/<fileName>`，与 `<platform>/`、`maven/` 并列——运维可辨识、
 * 可清理（CI 可整棵缓存）。
 *
 * @property cacheRoot 持久缓存根目录（任务侧传 Gradle 共享缓存目录，不写死本机绝对路径）。
 */
class ExternalArtifactProvisioner(private val cacheRoot: File) {

    /** 推导某源的缓存文件路径（纯函数，不创建文件、不发网络）。 */
    fun cacheFile(source: ExternalArtifactSource): File =
        cacheRoot.resolve(CACHE_DIR_NAME).resolve(source.id).resolve(source.fileName)

    /**
     * 解析（必要时下载）外部制品，返回缓存中的 `File`。
     *
     * @param logger 中文分级日志输出（默认 no-op）。
     * @throws IllegalStateException 地址解析失败、下载失败或 sha256 校验失败时抛中文错误。
     */
    fun provision(source: ExternalArtifactSource, logger: (String) -> Unit = {}): File {
        val cached = cacheFile(source)
        if (cached.isFile && cached.length() > 0L && hashMatches(source, cached)) {
            logger("命中缓存：${source.fileName}")
            return cached
        }

        val url = resolveUrl(source, logger)
        logger("下载 ${source.fileName}：$url")
        val temp = createTempFile(cached)
        try {
            Downloader.download(url, temp, logger, DownloadProgress.logging(logger, source.fileName))
            verifyHash(source, temp)
            return moveIntoCache(temp, cached)
        } finally {
            temp.delete()
        }
    }

    /** 解析下载地址：固定 URL 直接返回；API 型先取响应文本再交纯函数 resolver。 */
    private fun resolveUrl(source: ExternalArtifactSource, logger: (String) -> Unit): String =
        when (source) {
            is ExternalArtifactSource.FixedUrl -> source.url
            is ExternalArtifactSource.ApiResolved -> {
                logger("查询制品地址：${source.apiUrl}")
                source.resolver.resolve(Downloader.fetchText(source.apiUrl))
            }
        }

    /** 未设期望 hash 时视为匹配（无从校验）；设了则比对当前文件哈希。 */
    private fun hashMatches(source: ExternalArtifactSource, file: File): Boolean {
        val expected = source.expectedSha256 ?: return true
        return runCatching { file.sha256() == expected }.getOrDefault(false)
    }

    /** 校验下载产物完整性；未设期望 hash 时跳过。 */
    private fun verifyHash(source: ExternalArtifactSource, file: File) {
        val expected = source.expectedSha256 ?: return
        val actual = file.sha256()
        check(actual == expected) {
            "外部制品 ${source.fileName} sha256 校验失败：期望 $expected，实际 $actual。"
        }
    }

    /** 在缓存目标**同目录**建临时文件，保证与目标同卷——[moveIntoCache] 才能原子重命名。 */
    private fun createTempFile(destination: File): File {
        val dir = destination.absoluteFile.parentFile
        dir.mkdirs()
        return Files.createTempFile(dir.toPath(), "mc-testkit-${destination.name}-", ".tmp").toFile()
    }

    /** 原子移入缓存（覆盖已存在）；极少数文件系统不支持原子移动时退回非原子替换。 */
    private fun moveIntoCache(temp: File, destination: File): File {
        destination.parentFile?.mkdirs()
        try {
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (ignored: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: IOException) {
            throw IllegalStateException("无法将外部制品移入缓存：${destination.absolutePath}。", ex)
        }
        return destination
    }

    private companion object {
        /** 外部制品的缓存子目录名（与 `<platform>/`、`maven/` 并列区分）。 */
        const val CACHE_DIR_NAME = "external"
    }
}
