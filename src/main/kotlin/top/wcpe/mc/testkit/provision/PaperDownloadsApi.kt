package top.wcpe.mc.testkit.provision

/** PaperMC 下载 API 的某构建产物（下载名 + sha256）。 */
internal data class PaperDownload(
    val name: String,
    val sha256: String,
)

/**
 * PaperMC 下载 API v2 客户端（FR-02）。
 *
 * 覆盖 Paper/Folia/Velocity/Waterfall——它们同走 `api.papermc.io/v2`，只是 project 名不同。
 * "取远端文本"经注入的 [fetchText] 完成（默认走 [Downloader.fetchText]），解析逻辑（[parseLatestBuild] /
 * [parseDownload]）是**纯函数**，可喂固定样本文本单测、不打网络。
 *
 * @property fetchText 取 URL 文本的函数（注入以便单测替身；默认真实 HTTP）。
 */
internal class PaperDownloadsApi(
    private val fetchText: (String) -> String = Downloader::fetchText,
) {
    companion object {
        /** PaperMC 下载 API v2 端点。 */
        const val ENDPOINT: String = "https://api.papermc.io/v2/"

        /**
         * 从 `projects/<p>/versions/<v>` 响应解析"最新构建号"。
         *
         * 纯函数：响应 `builds` 数组按升序排列，取最后一个为最新。
         *
         * @throws IllegalStateException 无构建或字段缺失时抛中文错误。
         */
        fun parseLatestBuild(versionResponseJson: String): Int {
            val obj = JsonLite.asObject(JsonLite.parse(versionResponseJson))
            val builds = JsonLite.asArray(obj["builds"] ?: error("PaperMC 版本响应缺少 builds 字段。"))
            check(builds.isNotEmpty()) { "PaperMC 版本响应的 builds 为空，无可用构建。" }
            val latest = builds.last()
            return (latest as? Long)?.toInt()
                ?: error("PaperMC 版本响应的 build 号不是整数：$latest。")
        }

        /**
         * 从 `projects/<p>/versions/<v>/builds/<b>` 响应解析"应用产物"下载（名 + sha256）。
         *
         * 纯函数：取 `downloads.application`。
         *
         * @throws IllegalStateException 缺少 application 下载或字段缺失时抛中文错误。
         */
        fun parseDownload(buildResponseJson: String): PaperDownload {
            val obj = JsonLite.asObject(JsonLite.parse(buildResponseJson))
            val downloads = JsonLite.asObject(obj["downloads"] ?: error("PaperMC 构建响应缺少 downloads 字段。"))
            val application = JsonLite.asObject(downloads["application"] ?: error("PaperMC 构建响应缺少 downloads.application。"))
            val name = application["name"] as? String ?: error("PaperMC 构建响应缺少 downloads.application.name。")
            val sha256 = application["sha256"] as? String ?: error("PaperMC 构建响应缺少 downloads.application.sha256。")
            return PaperDownload(name, sha256)
        }
    }

    /** 解析指定 project + 版本的最新构建号（发网络）。 */
    fun latestBuild(project: String, version: String): Int =
        parseLatestBuild(fetchText("${ENDPOINT}projects/$project/versions/$version"))

    /** 解析指定 project + 版本 + 构建号的下载产物（发网络）。 */
    fun download(project: String, version: String, build: Int): PaperDownload =
        parseDownload(fetchText("${ENDPOINT}projects/$project/versions/$version/builds/$build"))

    /** 拼出下载 URL（路径形如 .../builds/<b>/downloads/<name>）。 */
    fun downloadUrl(project: String, version: String, build: Int, download: PaperDownload): String =
        "${ENDPOINT}projects/$project/versions/$version/builds/$build/downloads/${download.name}"
}
