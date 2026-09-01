package top.wcpe.mc.testkit.config

import java.io.File

/**
 * 让后端进 BungeeCord 代理模式的三件套（环境契约，一处固化、消费方默认生效；见 ADR-0004）。
 *
 * 经代理（Waterfall/BungeeCord 等）跑场景时，三件事缺一不可，否则后端会拒绝代理握手或 UUID 不一致：
 * 1. `server.properties`：`online-mode=false` + `enforce-secure-profile=false`（离线 bot 经离线代理进服）。
 * 2. `spigot.yml`：`settings.bungeecord: true`（接受代理转发的握手）。
 * 3. `config/paper-global.yml`：`proxies.bungee-cord.online-mode: false`（按转发 UUID 处理）。
 *
 * **版本感知（多版本服务端拉起）**：[version] 决定第三件写哪个文件——1.19+ 写 `config/paper-global.yml`（已有逻辑保留），
 * 1.13–1.18 写 `paper.yml`，1.7–1.12 跳过（BungeeCord 模式经 `spigot.yml` 即可）。`server.properties` 的
 * overrides 经 [ServerProperties.versionAwareOverrides] 按版本过滤不支持的键。
 *
 * YAML 用**真实读写深合并**（[editYaml] / [setNested]，snakeyaml）：保留未涉及键，不做正则/文本替换。
 * 纯函数式（只读写入参 runDir），便于临时目录单测。
 */
object BackendBungeeCordConfig {

    /**
     * 对运行目录依次落地三件套（顺序不敏感，互不依赖）。
     *
     * @param version 后端 Minecraft 版本（决定 paper 配置文件名 + server.properties 键过滤）。
     */
    fun apply(runDir: File, version: String) {
        val rawOverrides = mapOf(
            ServerProperties.ONLINE_MODE to "false",
            ServerProperties.ENFORCE_SECURE_PROFILE to "false",
        )
        // 按版本过滤不支持的键（多版本服务端拉起）
        val overrides = ServerProperties.versionAwareOverrides(version, rawOverrides)
        ServerProperties.edit(runDir, overrides, comment = "mc-testkit E2E BungeeCord 后端 server.properties")
        // spigot.yml：settings.bungeecord = true（深合并，保留其它 settings 键）
        editYaml(File(runDir, "spigot.yml")) { root ->
            setNested(root, listOf("settings", "bungeecord"), true)
        }
        // paper 代理在线模式配置：1.19+ → config/paper-global.yml，1.13–1.18 → paper.yml，1.7–1.12 跳过（多版本服务端拉起）
        if (MinecraftVersionGroup.needsPaperGlobal(version)) {
            editYaml(File(runDir, "config/paper-global.yml")) { root ->
                setNested(root, listOf("proxies", "bungee-cord", "online-mode"), false)
            }
        } else if (MinecraftVersionGroup.needsPaperYml(version)) {
            editYaml(File(runDir, "paper.yml")) { root ->
                setNested(root, listOf("settings", "bungeecord", "online-mode"), false)
            }
        }
    }
}
