package top.wcpe.mc.testkit.provision

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * HTTP(S) 代理解析（内置下载与运行的配套能力）。
 *
 * **为什么读系统属性就等于「用上 Gradle 配的代理」**：Gradle 会把 `gradle.properties` 里
 * `systemProp.http.proxyHost` / `systemProp.https.proxyPort` 之类施加为**守护进程的真实系统属性**，
 * 而本插件正运行在该守护进程内，故读 `http.proxyHost` 即读到用户在 Gradle 侧配的代理。
 * 另补读环境变量——JVM 原生不认 `HTTP_PROXY`，而容器 / CI 常只提供它。
 *
 * 依据优先级（高 → 低）：
 * 1. 系统属性 `http.proxyHost` / `http.proxyPort`（按 URL 的 scheme 取 http 或 https 组）
 * 2. 环境变量 `HTTP_PROXY` / `HTTPS_PROXY`（大小写两种拼法都认）
 *
 * `http.nonProxyHosts` / `NO_PROXY` 命中的主机**直连**。
 *
 * **不做**（与 ADR-0017 的「不做鉴权管理」一致）：代理认证。带 userinfo 的代理地址只取
 * host:port，凭据被忽略；需要认证的代理请改用系统属性形式并由 JVM 层的
 * `Authenticator` 处理（超出本模块范围）。
 *
 * 取值经注入的读取器（`(name) -> String?`），保持纯函数边界、可穷举单测、不耦合 Gradle `Project`。
 */
internal object HttpProxy {

    /** 系统属性名后缀：host 与 port。 */
    private const val HOST_SUFFIX = ".proxyHost"
    private const val PORT_SUFFIX = ".proxyPort"

    /** 主机绕过清单的系统属性名。 */
    private const val NON_PROXY_HOSTS = "http.nonProxyHosts"

    /**
     * 解析 [url] 应使用的代理。
     *
     * @param url 目标地址（据其 scheme 决定读 http 还是 https 那组配置）。
     * @param readProperty 系统属性读取器（默认 [System.getProperty]）。
     * @param readEnv 环境变量读取器（默认 [System.getenv]）。
     * @return 命中的代理；未配置或命中绕过清单时返回 null（表示走 JVM 默认行为：直连）。
     */
    fun forUrl(
        url: String,
        readProperty: (String) -> String? = System::getProperty,
        readEnv: (String) -> String? = System::getenv,
    ): Proxy? {
        val secure = url.startsWith("https:", ignoreCase = true)
        val scheme = if (secure) "https" else "http"
        val host = hostOf(url) ?: return null
        if (bypassed(host, readProperty(NON_PROXY_HOSTS), readEnv("NO_PROXY") ?: readEnv("no_proxy"))) return null

        // ① 系统属性（Gradle systemProp.* 落点）
        val propertyHost = readProperty(scheme + HOST_SUFFIX)?.trim()?.takeIf(String::isNotEmpty)
        if (propertyHost != null) {
            val port = readProperty(scheme + PORT_SUFFIX)?.trim()?.toIntOrNull() ?: defaultPort(secure)
            return proxyOf(propertyHost, port)
        }

        // ② 环境变量（JVM 原生不读，容器 / CI 常只给这个）
        val envRaw = if (secure) {
            readEnv("HTTPS_PROXY") ?: readEnv("https_proxy")
        } else {
            readEnv("HTTP_PROXY") ?: readEnv("http_proxy")
        }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val (envHost, envPort) = parseEnvProxy(envRaw, secure) ?: return null
        return proxyOf(envHost, envPort)
    }

    /** scheme 缺省端口（与 JVM 默认 ProxySelector 口径一致）。 */
    private fun defaultPort(secure: Boolean): Int = if (secure) 443 else 80

    private fun proxyOf(host: String, port: Int): Proxy =
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    /** 取 URL 的 host；地址不合法时返回 null（交由后续连接阶段报错，不在此处抛）。 */
    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * 解析环境变量形式的代理地址。
     *
     * 接受 `host:port`、`http://host:port`、`http://user:pass@host:port` 三种形态（userinfo 忽略，
     * 见类注释的「不做鉴权管理」）；缺端口时按 scheme 取缺省端口。
     */
    private fun parseEnvProxy(raw: String, secure: Boolean): Pair<String, Int>? {
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        return runCatching {
            val uri = URI(normalized)
            val host = uri.host?.takeIf(String::isNotEmpty) ?: return null
            val port = if (uri.port > 0) uri.port else defaultPort(secure)
            host to port
        }.getOrNull()
    }

    /** 主机是否命中绕过清单。两份清单**语义不同**，须分别判定（见各自辅助函数）。 */
    private fun bypassed(host: String, nonProxyHosts: String?, noProxy: String?): Boolean =
        matchesJavaNonProxyHosts(host, nonProxyHosts) || matchesNoProxyEnv(host, noProxy)

    /**
     * `http.nonProxyHosts` 的判定（**Java 语义**）：`|` 分隔，逐项 glob 匹配，子域须显式写 `*.`。
     *
     * 例：`*.foo.com|localhost` 命中 `a.foo.com` 与 `localhost`，但**不**命中 `foo.com` 本身。
     */
    private fun matchesJavaNonProxyHosts(host: String, patterns: String?): Boolean =
        patterns?.split('|')?.any { pattern ->
            pattern.trim().takeIf(String::isNotEmpty)?.let { globMatches(host, it) } == true
        } ?: false

    /**
     * `NO_PROXY` / `no_proxy` 的判定（**curl 语义**，与上者不同，不可混用）：`,` 分隔；
     * 裸域名同时命中其**子域**（这是它最易踩的差异——按 Java 那套写成精确匹配会漏绕过）。
     *
     * 例：`local.com` 命中 `local.com` 与 `www.local.com`，但不命中 `notlocal.com`；`*` 表示全部绕过。
     * 另容忍可选的 `:port` 后缀（curl 支持，端口在此无意义，取主机部分比较）。
     */
    private fun matchesNoProxyEnv(host: String, patterns: String?): Boolean =
        patterns?.split(',')?.any { raw ->
            // 前导点（`.example.com`）按 curl 惯例等价于裸域名
            val pattern = raw.trim().removePrefix(".").takeIf(String::isNotEmpty) ?: return@any false
            if (pattern == "*") return@any true
            val bare = stripOptionalPort(pattern).takeIf(String::isNotEmpty) ?: return@any false
            host.equals(bare, ignoreCase = true) || host.endsWith(".$bare", ignoreCase = true)
        } ?: false

    /**
     * 剥掉可选的 `:port` 后缀。
     *
     * 只在「冒号后是纯数字」时剥离——否则会把 IPv6 字面量（`::1`）的地址段误当端口截掉。
     * 方括号形式（`[::1]` / `[::1]:8080`）按 `]` 定位，取到含右括号为止。
     */
    private fun stripOptionalPort(pattern: String): String =
        if (pattern.startsWith("[")) {
            val end = pattern.indexOf(']')
            if (end >= 0) pattern.substring(0, end + 1) else pattern
        } else {
            val colon = pattern.lastIndexOf(':')
            if (colon > 0 && pattern.substring(colon + 1).all(Char::isDigit)) pattern.substring(0, colon) else pattern
        }

    /** 把含 `*` 通配的主机模式转为正则匹配（大小写不敏感）。 */
    private fun globMatches(host: String, pattern: String): Boolean {
        val regex = buildString {
            append('^')
            pattern.forEach { char ->
                when {
                    char == '*' -> append(".*")
                    char in REGEX_METACHARACTERS -> {
                        append('\\')
                        append(char)
                    }
                    else -> append(char)
                }
            }
            append('$')
        }
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(host) }.getOrDefault(false)
    }

    /** 正则元字符：逐字转义，避免把 hosts 模式里的 `.` / `[::1]` 等误当正则语法。 */
    private val REGEX_METACHARACTERS = charArrayOf('.', '^', '$', '+', '?', '(', ')', '[', ']', '{', '}', '|', '\\')
}
