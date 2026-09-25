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

/** 流式下载缓冲区（同时决定进度回调的调用频率：每写满一块回调一次）。 */
private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

/**
 * 下载进度回调（加法扩展，见 ADR-0017）。
 *
 * 框架原实现刻意不耦合 Gradle 的 `ProgressLogger`（内部 API），代价是 15 MB 级的服务端 jar
 * 下载期间**完全无输出**、用户只能干等。现以不依赖 Gradle 的回调补齐：调用方自行决定怎么用
 * （打日志 / 刷新进度条 / 静默），框架默认走 [logging]。
 *
 * @see logging 按百分比（总长未知时按字节）节流的日志实现
 */
fun interface DownloadProgress {
    /**
     * @param written 已写入字节数。
     * @param total 响应声明的总字节数；未知时为 -1（分块传输 / 无 `Content-Length`）。
     */
    fun onProgress(written: Long, total: Long)

    companion object {
        /** 不回报进度（默认值，行为与本功能引入前一致）。 */
        val NONE: DownloadProgress = DownloadProgress { _, _ -> }

        /**
         * 生成按**百分比**节流的日志进度（总长未知时退化为按字节节流）。
         *
         * 节流是必要的：64 KiB 一块意味着 15 MB 的 jar 会回调约 240 次，逐次打日志会淹没控制台。
         *
         * @param logger 日志输出（任务侧传 `project.logger.lifecycle`）。
         * @param label 前缀标签（如 `paper 1.20.1`），便于多制品并发下载时区分。
         * @param percentStep 百分比步长（默认 10，即 10% / 20% / … 各报一次）。
         */
        fun logging(
            logger: (String) -> Unit,
            label: String,
            percentStep: Int = 10,
        ): DownloadProgress = LoggingDownloadProgress(logger, label, percentStep)
    }
}

/**
 * [DownloadProgress.logging] 的实现：只在跨过步长阈值时输出，避免逐块刷屏。
 *
 * **刻意不实现 `Serializable`**：它持有日志 lambda，而 Kotlin lambda 不可序列化（[ArtifactUrlResolver]
 * 的注释里记过同款坑）。好在它的用法天然不跨序列化边界——下载发生在任务动作 / BuildService 的
 * **执行期**，进度对象随用随建、用完即弃。若确需把它捕获进动作闭包，请在动作**内部**构造，
 * 不要在配置期构造后捕获。
 */
private class LoggingDownloadProgress(
    private val logger: (String) -> Unit,
    private val label: String,
    private val percentStep: Int,
) : DownloadProgress {

    /** 上一回报值：`total > 0` 时是百分比，否则是 MiB 数；同一次下载只会走其中一个分支。 */
    private var lastReported = 0L

    override fun onProgress(written: Long, total: Long) {
        val step = percentStep.coerceAtLeast(1)
        if (total > 0) {
            val percent = (written * 100 / total).toInt()
            if (percent < lastReported + step && written < total) return
            lastReported = percent.toLong()
            logger("$label：$percent%（${written / 1024 / 1024} / ${total / 1024 / 1024} MB）")
        } else {
            // 总长未知：按 MiB 节流，避免无节制的逐块输出
            val writtenMb = written / (1024 * 1024)
            if (writtenMb < lastReported + 5) return
            lastReported = writtenMb
            logger("$label：已下载 ${written / 1024 / 1024} MB")
        }
    }
}

/**
 * 极简 HTTP 下载器（内置下载与运行）。
 *
 * 把远端 URL 流式写入目标文件；跟随重定向；遵守 User-Agent；**经 [HttpProxy] 走用户配的 HTTP 代理**；
 * 可按 [DownloadProgress] 回报进度。不耦合 Gradle（上游用的 Gradle `ProgressLogger` 是内部 API，
 * 本项目精简掉，符合"下载模块保持精简"）。仅 JDK [HttpURLConnection]，不引第三方 HTTP 库。
 *
 * **代理**：读系统属性 / 环境变量（即 Gradle `systemProp.*` 配的代理，详见 [HttpProxy]）。
 * 未配置即直连，行为与本功能引入前一致。
 *
 * **对外公开（ADR-0017）**：消费方可直接复用它做制品下载 / 取文本，自带 UA、重定向跟随、
 * 超时、响应体上限、代理与进度回调。签名变更按 SemVer 升 major（新增可选参数属加法）。
 */
object Downloader {

    /**
     * 下载 [url] 到 [destination]（覆盖已存在文件）。
     *
     * @param url 远端下载地址。
     * @param destination 目标文件（父目录须已存在或可创建）。
     * @param logger 中文分级日志输出（默认 no-op；任务侧可传 `project.logger.lifecycle`）。
     * @param progress 进度回调（默认 no-op）：每写入一块调用一次，总长未知时 total 参数为 -1。
     * @throws java.io.IOException 网络 / IO 失败时抛出。
     * @throws IllegalStateException HTTP 状态码非 2xx 时抛中文错误。
     */
    fun download(
        url: String,
        destination: File,
        logger: (String) -> Unit = {},
        progress: DownloadProgress = DownloadProgress.NONE,
    ) {
        destination.parentFile?.mkdirs()
        val connection = openFollowingRedirects(url)
        try {
            val status = connection.responseCode
            check(status in 200..299) { "下载失败：HTTP $status，地址 $url。" }
            // contentLengthLong：大于 2 GiB 的响应在 contentLength 上会溢出为负数。
            val total = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        progress.onProgress(written, total)
                    }
                }
            }
            logger("已下载 ${destination.name}（${written / 1024} KB，来自 $url）")
        } finally {
            connection.disconnect()
        }
    }

    /** 打开连接并手动跟随重定向（HttpURLConnection 默认不跨协议跟随，故自行处理）。 */
    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            // 代理按「当前跳转目标」解析：跨协议跳转（https → http）会切到另一组配置。
            val proxy = HttpProxy.forUrl(current)
            val connection = (if (proxy != null) URL(current).openConnection(proxy) else URL(current).openConnection())
                as HttpURLConnection
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
