package top.wcpe.mc.testkit.provision

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Waterfall 模块预下载器。
 *
 * Waterfall 启动时会把 `/server` 等命令作为模块加载；旧版本运行期仍尝试从已 sunset 的 PaperMC v2
 * 端点自下载模块。这里在启动代理前经 Fill v3 预置 `module:*` 下载项，避免 `/server` 模块缺失。
 */
internal class WaterfallModuleProvisioner(
    private val api: PaperDownloadsApi = PaperDownloadsApi(),
    private val download: (String, File, (String) -> Unit) -> Unit = { url, dest, log -> Downloader.download(url, dest, log) },
) {

    /** 下载并校验指定 Waterfall 版本的全部模块到运行目录的 `modules/` 下。 */
    fun provision(version: String, runDirectory: File, logger: (String) -> Unit = {}) {
        val build = api.latestBuild(WATERFALL_PROJECT, version)
        val modules = api.downloads(WATERFALL_PROJECT, version, build).filterKeys { it.startsWith(MODULE_PREFIX) }
        check(modules.containsKey(SERVER_COMMAND_MODULE_KEY)) { "PaperMC Waterfall 构建 $build 缺少 $SERVER_COMMAND_MODULE_KEY 模块，无法保障 /server 切服。" }
        val modulesDir = runDirectory.resolve("modules").apply { mkdirs() }
        modules.forEach { (key, module) ->
            provisionOne(key.removePrefix(MODULE_PREFIX), module, modulesDir, logger)
        }
    }

    private fun provisionOne(moduleName: String, module: PaperDownload, modulesDir: File, logger: (String) -> Unit) {
        val destination = modulesDir.resolve("$moduleName.jar")
        if (destination.isFile && destination.sha256() == module.sha256) {
            logger("命中 Waterfall 模块缓存：${destination.name}")
            return
        }
        val temp = Files.createTempFile(modulesDir.toPath(), "mc-testkit-$moduleName-", ".jar.tmp").toFile()
        try {
            download(module.url, temp, logger)
            val actual = temp.sha256()
            check(actual == module.sha256) { "Waterfall 模块 $moduleName sha256 校验失败：期望 ${module.sha256}，实际 $actual。" }
            moveIntoPlace(temp, destination)
            logger("已预置 Waterfall 模块：${destination.name}")
        } finally {
            temp.delete()
        }
    }

    private fun moveIntoPlace(temp: File, destination: File) {
        try {
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (ex: java.io.IOException) {
            throw IllegalStateException("无法将 Waterfall 模块移入运行目录：${destination.absolutePath}。", ex)
        }
    }

    companion object {
        private const val WATERFALL_PROJECT = "waterfall"
        private const val MODULE_PREFIX = "module:"
        private const val SERVER_COMMAND_MODULE_KEY = "module:cmd_server"
    }
}
