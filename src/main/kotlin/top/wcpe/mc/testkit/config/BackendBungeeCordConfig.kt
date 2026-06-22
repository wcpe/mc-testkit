package top.wcpe.mc.testkit.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 让后端进 BungeeCord 代理模式的三件套（FR-05，一处固化、消费方默认生效；见 ADR-0004）。
 *
 * 经代理（Waterfall/BungeeCord 等）跑场景时，三件事缺一不可，否则后端会拒绝代理握手或 UUID 不一致：
 * 1. `server.properties`：`online-mode=false` + `enforce-secure-profile=false`（离线 bot 经离线代理进服）。
 * 2. `spigot.yml`：`settings.bungeecord: true`（接受代理转发的握手）。
 * 3. `config/paper-global.yml`：`proxies.bungee-cord.online-mode: false`（按转发 UUID 处理）。
 *
 * YAML 用**真实读写深合并**（snakeyaml）：加载现有文件 → 在嵌套对象上只改目标键 → 写回，**保留未涉及键**，
 * 不做正则/文本替换；文件不存在则写最小片段（Paper/Spigot 首启会合并补齐其余默认项并保留本值）。
 * 纯函数式（只读写入参 runDir），便于临时目录单测。
 */
object BackendBungeeCordConfig {

    /** 对运行目录依次落地三件套（顺序不敏感，互不依赖）。 */
    fun apply(runDir: File) {
        ServerProperties.edit(
            runDir,
            mapOf(
                ServerProperties.ONLINE_MODE to "false",
                ServerProperties.ENFORCE_SECURE_PROFILE to "false",
            ),
            comment = "mc-testkit E2E BungeeCord 后端 server.properties",
        )
        // spigot.yml：settings.bungeecord = true（深合并，保留其它 settings 键）
        editYaml(File(runDir, "spigot.yml")) { root ->
            setNested(root, listOf("settings", "bungeecord"), true)
        }
        // config/paper-global.yml：proxies.bungee-cord.online-mode = false（深合并，不误改 velocity 等同名键）
        editYaml(File(runDir, "config/paper-global.yml")) { root ->
            setNested(root, listOf("proxies", "bungee-cord", "online-mode"), false)
        }
    }

    /** 加载 YAML（不存在则空映射）→ 应用 [mutate] 改键 → BLOCK 风格写回（真实读写，保留未涉及键）。 */
    private fun editYaml(file: File, mutate: (MutableMap<String, Any?>) -> Unit) {
        val root = loadYaml(file)
        mutate(root)
        file.parentFile?.mkdirs()
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
        }
        file.writer(Charsets.UTF_8).use { Yaml(options).dump(root, it) }
    }

    /** 读 YAML 顶层映射；文件不存在 / 空 / 非映射均返回空 [LinkedHashMap]。 */
    private fun loadYaml(file: File): MutableMap<String, Any?> {
        if (!file.exists()) {
            return linkedMapOf()
        }
        file.reader(Charsets.UTF_8).use { reader ->
            @Suppress("UNCHECKED_CAST")
            return (Yaml().load<Any?>(reader) as? MutableMap<String, Any?>) ?: linkedMapOf()
        }
    }

    /** 在嵌套映射上按路径深设值：缺失层级自动建为 [LinkedHashMap]，保留同层其它键。 */
    @Suppress("UNCHECKED_CAST")
    private fun setNested(root: MutableMap<String, Any?>, path: List<String>, value: Any?) {
        var node = root
        for (index in 0 until path.size - 1) {
            val key = path[index]
            val existing = node[key]
            node = if (existing is MutableMap<*, *>) {
                existing as MutableMap<String, Any?>
            } else {
                linkedMapOf<String, Any?>().also { node[key] = it }
            }
        }
        node[path.last()] = value
    }
}
