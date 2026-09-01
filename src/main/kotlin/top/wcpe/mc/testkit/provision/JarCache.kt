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

    /** 版本段：BungeeCord 用固定占位，其余用版本号（非法路径字符已被版本号天然规避）。 */
    private fun versionSegmentOf(platform: ProvisionPlatform, version: String?): String =
        if (platform == ProvisionPlatform.BUNGEECORD) {
            bungeeCordVersionSegment
        } else {
            require(!version.isNullOrBlank()) { "平台 ${platform.id} 需要版本号才能定位缓存路径。" }
            version
        }
}
