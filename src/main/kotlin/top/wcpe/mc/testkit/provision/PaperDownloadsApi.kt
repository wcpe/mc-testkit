package top.wcpe.mc.testkit.provision

/** PaperMC 下载 API 的某构建产物（下载名 + sha256 + 直接下载 URL）。 */
internal data class PaperDownload(
    val name: String,
    val sha256: String,
    val url: String,
)

/**
 * PaperMC 新下载服务 Fill v3 客户端（FR-02）。
 *
 * 覆盖 Paper/Folia/Velocity/Waterfall——它们同走 `fill.papermc.io/v3`，只是 project 名不同。
 * "取远端文本"经注入的 [fetchText] 完成（默认走 [Downloader.fetchText]），解析逻辑（[parseLatestBuild] /
 * [parseDownload]）是**纯函数**，可喂固定样本文本单测、不打网络。
 *
 * @property fetchText 取 URL 文本的函数（注入以便单测替身；默认真实 HTTP）。
 */
internal class PaperDownloadsApi(
    private val fetchText: (String) -> String = Downloader::fetchText,
) {
    companion object {
        /** PaperMC 新下载服务 Fill v3 端点。 */
        const val ENDPOINT: String = "https://fill.papermc.io/v3/"

        /** server jar 在 Fill 响应中的下载键。 */
        private const val SERVER_DOWNLOAD_KEY = "server:default"

        /** PaperMC 稳定构建频道名。 */
        private const val STABLE_CHANNEL = "STABLE"

        /**
         * 从 `projects/<p>/versions/<v>/builds` 响应解析"最新构建号"。
         *
         * 纯函数：Fill v3 返回构建对象数组，优先取第一个 STABLE 构建；若该版本没有 STABLE（如部分 Folia
         * 历史版本），则退回第一个构建，保持旧实现"取最新可用构建"的行为。
         *
         * @throws IllegalStateException 无构建或字段缺失时抛中文错误。
         */
        fun parseLatestBuild(buildsResponseJson: String): Int {
            val builds = JsonLite.asArray(JsonLite.parse(buildsResponseJson))
            check(builds.isNotEmpty()) { "PaperMC 构建响应为空，无可用构建。" }
            val selected = builds.firstOrNull { build ->
                val obj = JsonLite.asObject(build)
                obj["channel"] == STABLE_CHANNEL
            } ?: builds.first()
            val obj = JsonLite.asObject(selected)
            return (obj["id"] as? Long)?.toInt()
                ?: error("PaperMC 构建响应缺少 id 字段。")
        }

        /**
         * 从 `projects/<p>/versions/<v>/builds/<b>` 响应解析 server jar 下载（名 + sha256 + URL）。
         *
         * 纯函数：取 `downloads["server:default"]`。
         *
         * @throws IllegalStateException 缺少 server 下载或字段缺失时抛中文错误。
         */
        fun parseDownload(buildResponseJson: String): PaperDownload {
            val obj = JsonLite.asObject(JsonLite.parse(buildResponseJson))
            val downloads = JsonLite.asObject(obj["downloads"] ?: error("PaperMC 构建响应缺少 downloads 字段。"))
            val server = JsonLite.asObject(downloads[SERVER_DOWNLOAD_KEY] ?: error("PaperMC 构建响应缺少 downloads.$SERVER_DOWNLOAD_KEY。"))
            val checksums = JsonLite.asObject(server["checksums"] ?: error("PaperMC 构建响应缺少 downloads.$SERVER_DOWNLOAD_KEY.checksums。"))
            val name = server["name"] as? String ?: error("PaperMC 构建响应缺少 downloads.$SERVER_DOWNLOAD_KEY.name。")
            val sha256 = checksums["sha256"] as? String ?: error("PaperMC 构建响应缺少 downloads.$SERVER_DOWNLOAD_KEY.checksums.sha256。")
            val url = server["url"] as? String ?: error("PaperMC 构建响应缺少 downloads.$SERVER_DOWNLOAD_KEY.url。")
            return PaperDownload(name, sha256, url)
        }
    }

    private val base: String = ENDPOINT.trimEnd('/')

    /** 解析指定 project + 版本的最新构建号（发网络）。 */
    fun latestBuild(project: String, version: String): Int =
        parseLatestBuild(fetchText("$base/projects/$project/versions/$version/builds"))

    /** 解析指定 project + 版本 + 构建号的下载产物（发网络）。 */
    fun download(project: String, version: String, build: Int): PaperDownload =
        parseDownload(fetchText("$base/projects/$project/versions/$version/builds/$build"))

    /** 返回 Fill v3 响应中给出的对象存储下载 URL。 */
    fun downloadUrl(download: PaperDownload): String = download.url
}
