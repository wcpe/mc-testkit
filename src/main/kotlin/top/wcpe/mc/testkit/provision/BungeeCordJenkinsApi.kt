package top.wcpe.mc.testkit.provision

/**
 * SpigotMC Jenkins 客户端，用于解析 BungeeCord 构建（内置下载与运行）。
 *
 * BungeeCord 无版本，仅由 Jenkins 构建号标识，且 Jenkins 不暴露与下载产物匹配的校验和——
 * 故下载侧不校验远端 hash，只校验"结构合法 jar"+ 记录本地 hash 防本地损坏（见 [JarProvisionService]）。
 * 取文本经注入的 [fetchText]，[parseLastSuccessfulBuild] 为纯函数可单测。
 *
 * @property fetchText 取 URL 文本的函数（注入以便单测替身；默认真实 HTTP）。
 */
internal class BungeeCordJenkinsApi(
    private val fetchText: (String) -> String = Downloader::fetchText,
) {
    companion object {
        /**
         * 默认 Jenkins 端点：经典 `ci.md-5.net` 会 301 到 hub 域名，直接打解析后的目标。
         */
        const val ENDPOINT: String = "https://hub.spigotmc.org/jenkins/"

        /** BungeeCord 的 Jenkins job 名。 */
        const val JOB: String = "BungeeCord"

        /** BungeeCord 产物相对路径。 */
        const val ARTIFACT_PATH: String = "bootstrap/target/BungeeCord.jar"

        /**
         * 从 `job/<job>/api/json?tree=lastSuccessfulBuild[number]` 响应解析最新成功构建号。
         *
         * 纯函数：取 `lastSuccessfulBuild.number`。
         *
         * @throws IllegalStateException 无成功构建或字段缺失时抛中文错误。
         */
        fun parseLastSuccessfulBuild(jobResponseJson: String): Int {
            val obj = JsonLite.asObject(JsonLite.parse(jobResponseJson))
            val ref = obj["lastSuccessfulBuild"]
                ?: error("Jenkins job 响应缺少 lastSuccessfulBuild（可能无成功构建）。")
            val refObj = JsonLite.asObject(ref)
            val number = refObj["number"] as? Long
                ?: error("Jenkins job 响应缺少 lastSuccessfulBuild.number。")
            return number.toInt()
        }
    }

    private val base: String = ENDPOINT.trimEnd('/')

    /** 解析最新成功构建号（发网络）。 */
    fun lastSuccessfulBuild(): Int =
        parseLastSuccessfulBuild(fetchText("$base/job/$JOB/api/json?tree=lastSuccessfulBuild%5Bnumber%5D"))

    /** 拼出某构建产物的下载 URL。 */
    fun downloadUrl(build: Int): String =
        "$base/job/$JOB/$build/artifact/$ARTIFACT_PATH"
}
