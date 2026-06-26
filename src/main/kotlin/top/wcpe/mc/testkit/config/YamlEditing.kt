package top.wcpe.mc.testkit.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * YAML 深合并读改写回工具（后端代理模式配置共用：BungeeCord 三件套 / Velocity modern forwarding）。
 *
 * 加载现有文件 → 在嵌套对象上只改目标键 → BLOCK 风格写回，**保留未涉及键**（snakeyaml 真实读写，
 * 不做正则 / 文本替换）；文件不存在则写最小片段（Paper/Spigot 首启会合并补齐其余默认项并保留本值）。
 * 纯函数式（只读写入参文件），便于临时目录单测。
 */

/** 加载 YAML（不存在则空映射）→ 应用 [mutate] 改键 → BLOCK 风格写回（真实读写，保留未涉及键）。 */
internal fun editYaml(file: File, mutate: (MutableMap<String, Any?>) -> Unit) {
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
internal fun loadYaml(file: File): MutableMap<String, Any?> {
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
internal fun setNested(root: MutableMap<String, Any?>, path: List<String>, value: Any?) {
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
