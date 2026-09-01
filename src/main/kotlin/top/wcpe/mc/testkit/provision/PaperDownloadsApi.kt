package top.wcpe.mc.testkit.provision

/** PaperMC 下载 API 的某构建产物（下载名 + sha256 + 直接下载 URL）。 */
internal data class PaperDownload(
    val name: String,
    val sha256: String,
    val url: String,
)

/**
 * PaperMC 新下载服务 Fill v3 客户端（内置下载与运行）。
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

        /**
         * 从 `projects/<p>/versions/<v>/builds/latest` 响应解析"最新构建号"。
         *
         * 纯函数：Fill v3 直接返回最新构建对象，读取顶层 `id`，不依赖构建列表顺序或频道。
         *
         * @throws IllegalStateException id 字段缺失时抛中文错误。
         */
        fun parseLatestBuild(latestBuildResponseJson: String): Int {
            val obj = JsonLite.asObject(JsonLite.parse(latestBuildResponseJson))
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
        fun parseDownload(buildResponseJson: String): PaperDownload =
            parseDownloads(buildResponseJson)[SERVER_DOWNLOAD_KEY]
                ?: error("PaperMC 构建响应缺少 downloads.$SERVER_DOWNLOAD_KEY。")

        /** 解析构建响应中的全部下载项（server jar 与 module jar 共用同一结构）。 */
        fun parseDownloads(buildResponseJson: String): Map<String, PaperDownload> {
            val obj = JsonLite.asObject(JsonLite.parse(buildResponseJson))
            val downloads = JsonLite.asObject(obj["downloads"] ?: error("PaperMC 构建响应缺少 downloads 字段。"))
            return downloads.mapValues { (key, value) ->
                val item = JsonLite.asObject(value)
                val checksums = JsonLite.asObject(item["checksums"] ?: error("PaperMC 构建响应缺少 downloads.$key.checksums。"))
                val name = item["name"] as? String ?: error("PaperMC 构建响应缺少 downloads.$key.name。")
                val sha256 = checksums["sha256"] as? String ?: error("PaperMC 构建响应缺少 downloads.$key.checksums.sha256。")
                val url = item["url"] as? String ?: error("PaperMC 构建响应缺少 downloads.$key.url。")
                PaperDownload(name, sha256, url)
            }
        }
    }

    private val base: String = ENDPOINT.trimEnd('/')

    /** 解析指定 project + 版本的最新构建号（发网络）。 */
    fun latestBuild(project: String, version: String): Int =
        parseLatestBuild(fetchText("$base/projects/$project/versions/$version/builds/latest"))

    /** 解析指定 project + 版本 + 构建号的下载产物（发网络）。 */
    fun download(project: String, version: String, build: Int): PaperDownload =
        parseDownload(fetchText("$base/projects/$project/versions/$version/builds/$build"))

    /** 解析指定 project + 版本 + 构建号的全部下载项（发网络）。 */
    fun downloads(project: String, version: String, build: Int): Map<String, PaperDownload> =
        parseDownloads(fetchText("$base/projects/$project/versions/$version/builds/$build"))

    /** 返回 Fill v3 响应中给出的对象存储下载 URL。 */
    fun downloadUrl(download: PaperDownload): String = download.url
}
