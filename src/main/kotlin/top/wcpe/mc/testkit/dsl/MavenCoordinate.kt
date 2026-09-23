package top.wcpe.mc.testkit.dsl

import org.gradle.api.GradleException

/**
 * 依赖插件 Maven 坐标（`group:artifact:version`）的声明校验（依赖注入 DSL，配置期）。
 *
 * 只做**声明期**的字面校验，不触网、不解析（解析交给 Gradle 原生依赖解析，执行期落成 jar）。
 * 拒绝动态版本与版本区间：二者会让同一份声明在不同时刻拉到不同制品，破坏 e2e 的可复现性
 * （`.claude/rules/decision-alignment.md` 的「可复现」取向）。
 */
object MavenCoordinate {

    /** 坐标段数：`group:artifact:version`。 */
    private const val SEGMENT_COUNT = 3

    /** 版本区间表达式的标记字符（Maven 区间语法，如 `[1.0,2.0)`）。 */
    private val RANGE_MARKERS = listOf('[', ']', '(', ')', ',')

    /**
     * 校验坐标并原样返回；不合法即抛**中文** [GradleException]（配置期报错约定）。
     *
     * @param coordinate 消费方在 `mavenPlugin(...)` 里写的原始值。
     * @return 原样返回 [coordinate]（便于调用处链式收集声明）。
     * @throws GradleException 坐标不是合法三段式、或版本为动态 / 区间时抛出。
     */
    fun requireValid(coordinate: String): String {
        val reason = invalidReason(coordinate)
        if (reason != null) {
            throw GradleException(
                "mcTestkit 依赖插件 Maven 坐标「$coordinate」不合法：$reason。\n" +
                    "  请写成 group:artifact:version 三段，例如 mavenPlugin(\"com.example:foo-plugin:1.2.0\")。" +
                    "允许 -SNAPSHOT 后缀（便于测试期用快照版本），但它不是可复现制品。",
            )
        }
        return coordinate
    }

    /**
     * 判断坐标是否合法，返回不合法原因（中文）；合法返回 null。
     *
     * 纯函数、零 Gradle 依赖（异常类型除外），便于穷举单测。
     */
    fun invalidReason(coordinate: String): String? {
        if (coordinate.isBlank()) return "坐标为空白"
        if (coordinate != coordinate.trim()) return "坐标首尾含空白字符"
        val segments = coordinate.split(':')
        if (segments.size != SEGMENT_COUNT) {
            return "必须恰为 group:artifact:version 三段（实际 ${segments.size} 段）"
        }
        if (segments.any { it.isBlank() }) return "存在空段"
        if (segments.any { segment -> segment.any(Char::isWhitespace) }) return "分段内含有空白字符"
        // 坐标段会被直接当路径段拼进缓存镜像路径（JarCache.mavenJarFile），故须拒绝路径分隔符与
        // 上级目录——否则 `com.example:foo:../../evil` 会把镜像推导到缓存根之外（消费方自伤，但拦在门口）
        if (segments.any { segment -> segment.any { it == '/' || it == '\\' } }) {
            return "分段含路径分隔符（坐标会被用作缓存镜像路径，不允许出现 / 或 \\）"
        }
        if (segments.any { it == ".." || it == "." }) return "分段为相对路径段（. 或 ..）"
        return versionRejection(segments[SEGMENT_COUNT - 1])
    }

    /** 版本段的可复现性校验：动态版本 / 区间一律拒绝，`-SNAPSHOT` 放行。 */
    private fun versionRejection(version: String): String? = when {
        version.contains('+') -> "版本含动态占位「+」（如 1.0.+），会破坏可复现性"
        version.equals("latest", ignoreCase = true) || version.lowercase().startsWith("latest.") ->
            "版本为 latest.* 动态版本，会破坏可复现性"
        RANGE_MARKERS.any { it in version } -> "版本为区间表达式（如 [1.0,2.0)），会破坏可复现性"
        version.contains('*') -> "版本含通配符「*」，会破坏可复现性"
        else -> null
    }
}
