package top.wcpe.mc.testkit.provision

import java.io.File

/**
 * 下载 jar 的缓存路径推导（内置下载与运行，纯函数）。
 *
 * 布局：`<cacheRoot>/<platform>/<version>/<build>.jar`；BungeeCord 无版本，版本段用固定占位
 * `bungeecord` 以让分层一致。缓存根由调用方注入（任务侧传 Gradle 共享缓存目录），**不写死本机
 * 绝对路径**（NFR 可移植）。命中且 hash 一致即复用，否则下载（复用逻辑在 [JarProvisionService]）。
 *
 * @property cacheRoot 持久缓存根目录（如 `<gradleUserHome>/caches/mc-testkit-jars`）。
 */
internal class JarCache(private val cacheRoot: File) {

    /** BungeeCord 无版本时的版本段占位。 */
    private val bungeeCordVersionSegment = "bungeecord"

    /**
     * 推导某平台 + 版本 + 构建号的缓存 jar 路径（不创建文件，仅算路径）。
     *
     * @param platform 平台。
     * @param version 版本（BungeeCord 传 null，自动用占位段）。
     * @param build 构建号。
     */
    fun jarFile(platform: ProvisionPlatform, version: String?, build: Int): File {
        val versionSegment = versionSegmentOf(platform, version)
        return cacheRoot.resolve(platform.id).resolve(versionSegment).resolve("$build.jar")
    }

    /** 某平台 + 版本的 jar 目录（同构建号同版本的多个构建落同目录，便于清理 / 复用）。 */
    fun jarDir(platform: ProvisionPlatform, version: String?): File {
        val versionSegment = versionSegmentOf(platform, version)
        return cacheRoot.resolve(platform.id).resolve(versionSegment)
    }

    /**
     * Maven 坐标服务端 / 代理 jar 的**镜像**路径（纯函数，按坐标推导，与 `platform` / DSL 的
     * `version` 字段无关——坐标自带版本，二者本是不同维度）。
     *
     * 布局沿用 Maven 仓库自身的层级，便于人工核对与按坐标清理：
     * `<cacheRoot>/maven/<groupId 转路径>/<artifactId>/<version>/<artifactId>-<version>.jar`。
     * 例：`io.papermc.paper:paper:1.12.2` →
     * `<cacheRoot>/maven/io/papermc/paper/paper/1.12.2/paper-1.12.2.jar`。
     *
     * 放在 [cacheRoot] 之下的独立 `maven/` 子树（而非与内置下载分层混放）：既让镜像与
     * `<platform>/<version>/<build>.jar` 泾渭分明，又便于 CI 单独缓存该子树。
     *
     * @param coordinate Maven 坐标 `group:artifact:version`（配置期已校验三段非空）。
     * @throws IllegalArgumentException 坐标不是三段式时抛出（配置期校验应已拦截，此处兜底）。
     */
    fun mavenJarFile(coordinate: String): File {
        val segments = coordinate.split(':')
        require(segments.size == MAVEN_SEGMENT_COUNT && segments.none { it.isBlank() }) {
            "Maven 坐标必须为 group:artifact:version 三段非空才能推导镜像路径：$coordinate"
        }
        val groupPath = segments[0].replace('.', '/')
        val artifact = segments[1]
        val version = segments[2]
        return cacheRoot.resolve(MAVEN_CACHE_DIR_NAME)
            .resolve(groupPath)
            .resolve(artifact)
            .resolve(version)
            .resolve("$artifact-$version.jar")
    }

    /** 版本段：BungeeCord 用固定占位，其余用版本号（非法路径字符已被版本号天然规避）。 */
    private fun versionSegmentOf(platform: ProvisionPlatform, version: String?): String =
        if (platform == ProvisionPlatform.BUNGEECORD) {
            bungeeCordVersionSegment
        } else {
            require(!version.isNullOrBlank()) { "平台 ${platform.id} 需要版本号才能定位缓存路径。" }
            version
        }

    private companion object {
        /** Maven 镜像子目录名（与内置下载的 `<platform>/` 分层区分，便于单独缓存该子树）。 */
        const val MAVEN_CACHE_DIR_NAME = "maven"

        /** Maven 坐标段数：`group:artifact:version`。 */
        const val MAVEN_SEGMENT_COUNT = 3
    }
}
