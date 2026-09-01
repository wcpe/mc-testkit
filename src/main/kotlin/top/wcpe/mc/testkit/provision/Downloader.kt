package top.wcpe.mc.testkit.provision

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 下载时声明的 User-Agent（PaperMC Fill 要求包含软件标识与联系方式）。 */
internal const val PROVISION_USER_AGENT = "mc-testkit/0.0.0 (https://github.com/wcpe/mc-testkit)"

/** HTTP 重定向跟随上限：SpigotMC 的"经典"域名会 301 到 hub 域名，需要跟随。 */
private const val MAX_REDIRECTS = 5

/** 建立连接超时（毫秒）。 */
private const val CONNECT_TIMEOUT_MS = 30_000

/** 读取超时（毫秒；单次 read 间隔）。 */
private const val READ_TIMEOUT_MS = 60_000

/** [Downloader.fetchText] 响应体上限：PaperMC / Jenkins 的 JSON 响应极小，设 16 MiB 上限挡异常 / 被劫持的超大响应吃满内存。 */
private const val MAX_TEXT_RESPONSE_BYTES = 16 * 1024 * 1024

/**
 * 极简 HTTP 下载器（内置下载与运行）。
 *
 * 把远端 URL 流式写入目标文件；跟随重定向；遵守 User-Agent。不带进度条 / 不耦合 Gradle
 * （上游用的 Gradle `ProgressLogger` 是内部 API，本项目精简掉，符合"下载模块保持精简"）。
 * 仅 JDK [HttpURLConnection]，不引第三方 HTTP 库。
 */
internal object Downloader {

    /**
     * 下载 [url] 到 [destination]（覆盖已存在文件）。
     *
     * @param url 远端下载地址。
     * @param destination 目标文件（父目录须已存在或可创建）。
     * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
     * @throws java.io.IOException 网络 / IO 失败时抛出。
     * @throws IllegalStateException HTTP 状态码非 2xx 时抛中文错误。
     */
    fun download(url: String, destination: File, logger: (String) -> Unit = {}) {
        destination.parentFile?.mkdirs()
        val connection = openFollowingRedirects(url)
        try {
            val status = connection.responseCode
            check(status in 200..299) { "下载失败：HTTP $status，地址 $url。" }
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            logger("已下载 ${destination.name}（来自 $url）")
        } finally {
            connection.disconnect()
        }
    }

    /** 打开连接并手动跟随重定向（HttpURLConnection 默认不跨协议跟随，故自行处理）。 */
    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", PROVISION_USER_AGENT)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val status = connection.responseCode
            if (status in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                checkNotNull(location) { "下载重定向缺少 Location 头：$current。" }
                // Location 可能是相对地址，按当前 URL 解析；并拒绝 https→http 的不安全降级
                val target = URL(URL(current), location)
                check(!(current.startsWith("https:", ignoreCase = true) && target.protocol.equals("http", ignoreCase = true))) {
                    "拒绝不安全的重定向降级（https → http）：$current → $target。"
                }
                current = target.toString()
                return@repeat
            }
            return connection
        }
        error("下载重定向超过上限（$MAX_REDIRECTS 次）：$url。")
    }

    /**
     * 取远端文本（PaperMC / Jenkins 的 JSON 响应用）。
     *
     * 与下载分离，便于上层把"取文本"与"解析文本"解耦——解析逻辑可喂固定文本单测、不打网络。
     *
     * @throws IllegalStateException HTTP 状态码非 2xx 时抛中文错误。
     */
    fun fetchText(url: String): String {
        val connection = openFollowingRedirects(url)
        try {
            val status = connection.responseCode
            check(status in 200..299) { "请求失败：HTTP $status，地址 $url。" }
            // 有上限读取：最多读 MAX+1 字节，超出即判定响应过大（边读边封顶，不先全量入内存）
            val bytes = connection.inputStream.use { it.readNBytes(MAX_TEXT_RESPONSE_BYTES + 1) }
            check(bytes.size <= MAX_TEXT_RESPONSE_BYTES) {
                "响应过大（超过 $MAX_TEXT_RESPONSE_BYTES 字节）：$url。"
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
