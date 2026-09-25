package top.wcpe.mc.testkit.provision

import org.junit.jupiter.api.DisplayName
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HttpProxy] 单元测试（纯函数边界，零网络）。
 *
 * 覆盖：系统属性（Gradle `systemProp.*` 的落点）按 scheme 分组、环境变量回退与两种大小写拼法、
 * 优先级（系统属性压过环境变量）、缺省端口、绕过清单（`http.nonProxyHosts` 的 `|` 分隔与 `*` 通配、
 * `NO_PROXY` 的 `,` 分隔）、以及「未配置即直连」这一向后兼容前提。
 *
 * 取值器经注入传入，故可穷举且不依赖宿主真实代理环境。
 */
class HttpProxyTest {

    /** 构造一个「只有给定键有值」的系统属性读取器。 */
    private fun props(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    /** 构造一个「只有给定键有值」的环境变量读取器（两个拼法都查同一张表）。 */
    private fun envs(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    private val noProps: (String) -> String? = { null }
    private val noEnvs: (String) -> String? = { null }

    private fun hostOf(proxy: Proxy): String = (proxy.address() as java.net.InetSocketAddress).hostString

    private fun portOf(proxy: Proxy): Int = (proxy.address() as java.net.InetSocketAddress).port

    // ── 未配置：直连（向后兼容前提）──

    @Test
    @DisplayName("未配置任何代理时不得返回代理，保持直连")
    fun returnNullWhenNothingConfigured() {
        assertNull(HttpProxy.forUrl("https://fill.papermc.io/v3/", noProps, noEnvs))
        assertNull(HttpProxy.forUrl("http://example.test/a.jar", noProps, noEnvs))
    }

    // ── 系统属性（Gradle systemProp.* 落点）──

    @Test
    @DisplayName("https 地址应读 https.proxyHost 并采用其端口")
    fun readHttpsProxyPropertiesForSecureUrl() {
        val proxy = HttpProxy.forUrl(
            "https://fill.papermc.io/v3/",
            props("https.proxyHost" to "proxy.corp", "https.proxyPort" to "8443"),
            noEnvs,
        )
        assertEquals(Proxy.Type.HTTP, proxy!!.type())
        assertEquals("proxy.corp", hostOf(proxy))
        assertEquals(8443, portOf(proxy))
    }

    @Test
    @DisplayName("http 地址应读 http.proxyHost，不与 https 组混用")
    fun readHttpProxyPropertiesForPlainUrl() {
        val proxy = HttpProxy.forUrl(
            "http://example.test/a.jar",
            props("http.proxyHost" to "plain.corp", "http.proxyPort" to "3128", "https.proxyHost" to "secure.corp"),
            noEnvs,
        )
        assertEquals("plain.corp", hostOf(proxy!!))
        assertEquals(3128, portOf(proxy))
    }

    @Test
    @DisplayName("https 地址不应回退到 http 组的代理配置")
    fun notFallBackToHttpGroupForSecureUrl() {
        // 只配了 http 组，https 请求应视为未配置
        assertNull(
            HttpProxy.forUrl(
                "https://fill.papermc.io/v3/",
                props("http.proxyHost" to "plain.corp", "http.proxyPort" to "3128"),
                noEnvs,
            ),
        )
    }

    @Test
    @DisplayName("未配端口时应按 scheme 取缺省端口")
    fun useSchemeDefaultPortWhenPortMissing() {
        val secure = HttpProxy.forUrl("https://x.test/", props("https.proxyHost" to "p.test"), noEnvs)
        assertEquals(443, portOf(secure!!))
        val plain = HttpProxy.forUrl("http://x.test/", props("http.proxyHost" to "p.test"), noEnvs)
        assertEquals(80, portOf(plain!!))
    }

    // ── 环境变量（JVM 原生不读，容器 / CI 常只给这个）──

    @Test
    @DisplayName("仅环境变量提供时应采用 HTTPS_PROXY")
    fun readHttpsProxyEnvWhenNoProperties() {
        val proxy = HttpProxy.forUrl("https://x.test/", noProps, envs("HTTPS_PROXY" to "http://env.proxy:9000"))
        assertEquals("env.proxy", hostOf(proxy!!))
        assertEquals(9000, portOf(proxy))
    }

    @Test
    @DisplayName("小写拼法 https_proxy 也应被采纳")
    fun readLowercaseEnvSpelling() {
        val proxy = HttpProxy.forUrl("https://x.test/", noProps, envs("https_proxy" to "env.proxy:9001"))
        assertEquals("env.proxy", hostOf(proxy!!))
        assertEquals(9001, portOf(proxy))
    }

    @Test
    @DisplayName("环境变量可省略 scheme，缺端口时取缺省端口")
    fun acceptEnvWithoutSchemeAndPort() {
        val proxy = HttpProxy.forUrl("https://x.test/", noProps, envs("HTTPS_PROXY" to "env.proxy"))
        assertEquals("env.proxy", hostOf(proxy!!))
        assertEquals(443, portOf(proxy))
    }

    @Test
    @DisplayName("环境变量带 userinfo 时应只取主机端口，不因缺少凭据处理而失败")
    fun ignoreUserInfoInEnvProxy() {
        val proxy = HttpProxy.forUrl("https://x.test/", noProps, envs("HTTPS_PROXY" to "http://user:pw@env.proxy:9002"))
        assertEquals("env.proxy", hostOf(proxy!!))
        assertEquals(9002, portOf(proxy))
    }

    // ── 优先级 ──

    @Test
    @DisplayName("系统属性应优先于环境变量")
    fun preferPropertiesOverEnv() {
        val proxy = HttpProxy.forUrl(
            "https://x.test/",
            props("https.proxyHost" to "from.props", "https.proxyPort" to "1111"),
            envs("HTTPS_PROXY" to "http://from.env:2222"),
        )
        assertEquals("from.props", hostOf(proxy!!))
        assertEquals(1111, portOf(proxy))
    }

    // ── 绕过清单 ──

    @Test
    @DisplayName("host 命中 http.nonProxyHosts 时应直连")
    fun bypassHostInNonProxyHosts() {
        assertNull(
            HttpProxy.forUrl(
                "https://fill.papermc.io/v3/",
                props("https.proxyHost" to "p.test", "http.nonProxyHosts" to "*.papermc.io|localhost"),
                noEnvs,
            ),
        )
    }

    @Test
    @DisplayName("host 命中 NO_PROXY 时应直连（逗号分隔的形态）")
    fun bypassHostInNoProxyEnv() {
        assertNull(
            HttpProxy.forUrl(
                "https://fill.papermc.io/v3/",
                props("https.proxyHost" to "p.test"),
                envs("NO_PROXY" to "example.test,papermc.io"),
            ),
        )
    }

    @Test
    @DisplayName("NO_PROXY 的裸域名应同时命中其子域（curl 语义，与 Java 清单不同）")
    fun noProxyEnvMatchesSubdomains() {
        assertNull(
            HttpProxy.forUrl(
                "https://fill.papermc.io/v3/",
                props("https.proxyHost" to "p.test"),
                envs("NO_PROXY" to "papermc.io"),
            ),
            "NO_PROXY 的裸域名须命中子域",
        )
    }

    @Test
    @DisplayName("NO_PROXY 的前导点与可选端口应被容忍")
    fun noProxyEnvToleratesLeadingDotAndPort() {
        assertNull(
            HttpProxy.forUrl("https://fill.papermc.io/v3/", props("https.proxyHost" to "p.test"), envs("NO_PROXY" to ".papermc.io")),
        )
        assertNull(
            HttpProxy.forUrl("https://fill.papermc.io/v3/", props("https.proxyHost" to "p.test"), envs("NO_PROXY" to "papermc.io:443")),
        )
    }

    @Test
    @DisplayName("NO_PROXY 为 * 时应全部绕过")
    fun noProxyEnvStarBypassesEverything() {
        assertNull(HttpProxy.forUrl("https://x.test/", props("https.proxyHost" to "p.test"), envs("NO_PROXY" to "*")))
    }

    @Test
    @DisplayName("NO_PROXY 不应把不相关的后缀域名误判为子域")
    fun noProxyEnvDoesNotMatchUnrelatedSuffix() {
        val proxy = HttpProxy.forUrl(
            "https://notpapermc.io/v3/",
            props("https.proxyHost" to "p.test"),
            envs("NO_PROXY" to "papermc.io"),
        )
        assertEquals("p.test", hostOf(proxy!!), "notpapermc.io 不应因后缀相同而被绕过")
    }

    @Test
    @DisplayName("java 清单的裸域名不应命中子域（两份清单语义不同）")
    fun javaNonProxyHostsRequiresExplicitWildcard() {
        val proxy = HttpProxy.forUrl(
            "https://fill.papermc.io/v3/",
            props("https.proxyHost" to "p.test", "http.nonProxyHosts" to "papermc.io"),
            noEnvs,
        )
        assertEquals("p.test", hostOf(proxy!!), "Java 语义下裸域名不覆盖子域，须显式写 *.papermc.io")
    }

    @Test
    @DisplayName("未命中绕过清单时仍应走代理")
    fun stillProxyWhenNotBypassed() {
        val proxy = HttpProxy.forUrl(
            "https://fill.papermc.io/v3/",
            props("https.proxyHost" to "p.test", "http.nonProxyHosts" to "localhost|*.internal"),
            noEnvs,
        )
        assertEquals("p.test", hostOf(proxy!!))
    }

    // ── 健壮性 ──

    @Test
    @DisplayName("地址无法解析出 host 时不应误配代理")
    fun returnNullForUnparsableUrl() {
        assertNull(HttpProxy.forUrl("not a url", props("https.proxyHost" to "p.test"), noEnvs))
    }

    @Test
    @DisplayName("端口非法时应回退到 scheme 缺省端口而非崩溃")
    fun fallBackOnInvalidPort() {
        val proxy = HttpProxy.forUrl(
            "https://x.test/",
            props("https.proxyHost" to "p.test", "https.proxyPort" to "not-a-port"),
            noEnvs,
        )
        assertEquals(443, portOf(proxy!!))
    }

    @Test
    @DisplayName("空白的代理 host 应视为未配置")
    fun treatBlankHostAsUnconfigured() {
        assertNull(HttpProxy.forUrl("https://x.test/", props("https.proxyHost" to "   "), noEnvs))
    }

    @Test
    @DisplayName("绕过模式中的正则元字符应被当成字面量")
    fun treatRegexMetacharactersLiterally() {
        // `.` 若被当正则通配，则会误匹配 `fillXpapermcYio`
        assertTrue(
            HttpProxy.forUrl("https://fillXpapermcYio/v3/", props("https.proxyHost" to "p.test", "http.nonProxyHosts" to "fill.papermc.io"), noEnvs) != null,
            "点号应按字面量匹配，不应通配任意字符",
        )
        assertNull(
            HttpProxy.forUrl("https://fill.papermc.io/v3/", props("https.proxyHost" to "p.test", "http.nonProxyHosts" to "fill.papermc.io"), noEnvs),
        )
    }

    // ── 进度回调 ──

    @Test
    @DisplayName("进度工厂 NONE 应是安全的空实现")
    fun noneProgressIsNoOp() {
        DownloadProgress.NONE.onProgress(0, 0)
        DownloadProgress.NONE.onProgress(1024, -1)
    }

    @Test
    @DisplayName("日志进度应按百分比步长节流，而非逐块输出")
    fun throttleLoggingProgressByPercentStep() {
        val logs = mutableListOf<String>()
        val progress = DownloadProgress.logging({ logs += it }, "paper-1.20.1.jar", percentStep = 10)
        // 模拟 15 MB 分 240 块写入：逐块调用，但只应输出约 10 次（每 10%）
        val total = 15L * 1024 * 1024
        val chunk = total / 240
        var written = 0L
        repeat(240) {
            written += chunk
            progress.onProgress(written, total)
        }
        assertTrue(logs.size in 1..12, "应显著少于逐块调用次数（240），实际输出 ${logs.size} 条：$logs")
        assertTrue(logs.any { it.contains("paper-1.20.1.jar") }, "日志应带标签：$logs")
        assertTrue(logs.all { it.contains("%") }, "总长已知时应用百分比：$logs")
    }

    @Test
    @DisplayName("总长未知时应退化为按 MB 节流且不报百分比")
    fun throttleUnknownLengthProgressByMegabytes() {
        val logs = mutableListOf<String>()
        val progress = DownloadProgress.logging({ logs += it }, "x.jar", percentStep = 10)
        // 总长 -1：分块传输 / 无 Content-Length
        var written = 0L
        repeat(240) {
            written += 64 * 1024
            progress.onProgress(written, -1)
        }
        assertTrue(logs.isNotEmpty(), "总长未知时仍应有输出")
        assertTrue(logs.all { it.contains("MB") && !it.contains("%") }, "不应报百分比：$logs")
        assertTrue(logs.size < 240, "应节流：$logs")
    }
}
