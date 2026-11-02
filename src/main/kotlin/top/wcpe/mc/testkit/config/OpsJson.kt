package top.wcpe.mc.testkit.config

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 运行目录 `ops.json` 的纯函数式写入（bot 原版 OP 提权，ADR-0027）。
 *
 * 「读现有条目 → 只追加缺失的用户名 → 写回」，**保留消费方模板自带的既有 OP 条目**；只依赖 JDK
 * [File] / [MessageDigest]，不耦合 Gradle 的其余部分，便于用临时目录穷举单测。
 *
 * **为什么不能只写 `name`**：服务端按 `uuid` 认人，而离线模式下的 UUID 是
 * `MD5("OfflinePlayer:<name>")` 派生的确定性值（见 [offlineUuid]）。写错则条目被服务端**静默忽略**
 * ——不报错、不告警，只是命令仍然没权限。故这里不采用 `add-op` 一类运行期命令，而在**起服前**按用户名
 * 落盘正确 UUID。
 *
 * 本对象只做「框架级把 bot 提为 OP」这一件事，不写任何业务配置（scope-discipline）。
 */
object OpsJson {

    /** 运行目录下的文件名（Minecraft 三端通用字面量）。 */
    const val FILE_NAME = "ops.json"

    /** OP 等级：4 = 全部权限（含 `/op`、`/stop` 等）。 */
    const val OP_LEVEL = 4

    /** 离线模式 UUID 的命名空间前缀（Bukkit `UUID.nameUUIDFromBytes` 的调用方式）。 */
    private const val OFFLINE_PLAYER_PREFIX = "OfflinePlayer:"

    /**
     * 按 Minecraft 离线模式算法派生 UUID：`MD5("OfflinePlayer:<name>")`，输出带连字符的小写 `8-4-4-4-12`。
     *
     * 与 Bukkit `UUID.nameUUIDFromBytes` 完全一致：取 MD5 的 16 字节后按 RFC 4122 设置
     * **version = 3**（`b[6]` 高 4 位置 0x30）与 **variant = IETF**（`b[8]` 高 2 位置 0x80）。
     * 派生规则必须与服务端一致，否则写进 `ops.json` 的条目会被静默忽略。
     */
    fun offlineUuid(name: String): String {
        val bytes = MessageDigest.getInstance("MD5")
            .digest((OFFLINE_PLAYER_PREFIX + name).toByteArray(StandardCharsets.UTF_8))
        // version 3（基于 MD5 的名字型 UUID）：第 7 字节高 4 位改 0011
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x30).toByte()
        // IETF variant：第 9 字节高 2 位改 10
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return formatUuid(bytes)
    }

    /**
     * 把 [names] 中的用户名写成运行目录 `ops.json` 的 OP 条目；**已存在则合并**。
     *
     * 合并语义：读出已有数组的每个条目并**原样保留**（含其 `level` / `bypassesPlayerLimit` / 未知字段），
     * 只追加 `name` 尚未出现的 [names]。覆盖写会静默剥夺消费方模板已声明的 OP——那比多一个 OP 更难排查，
     * 故这里只增不改。
     *
     * [names] 为空时**什么都不做**：不创建空文件，也不删除已有文件（无 bot 提权的场景不该产生任何痕迹）。
     *
     * @param runDir 运行目录（`ops.json` 所在 / 将写入处）。
     * @param names 待提权的 bot 用户名（调用方须保证与实际起进程用的用户名一致）。
     * @return 本次真正新增的用户名（按 [names] 顺序去重；已存在的不在其中）。
     */
    fun write(runDir: File, names: Collection<String>): List<String> {
        val targets = names.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return emptyList()
        val file = File(runDir, FILE_NAME)
        val existingEntries = readExistingEntries(file)
        val existingNames = existingEntries.mapNotNull { it.second }.toSet()
        val added = targets.filter { it !in existingNames }
        if (added.isEmpty()) return emptyList()
        val entries = existingEntries.map { it.first } + added.map { renderEntry(it) }
        runDir.mkdirs()
        file.writeText(renderDocument(entries), StandardCharsets.UTF_8)
        return added
    }

    /** 拼一个 OP 条目（字段顺序与 Minecraft 自身写出的一致，便于消费方肉眼比对）。 */
    private fun renderEntry(name: String): String =
        """{"uuid":"${offlineUuid(name)}","name":"${escapeJson(name)}",""" +
            """"level":$OP_LEVEL,"bypassesPlayerLimit":true}"""

    /** 拼整个 `ops.json` 文档：每条一行加缩进，末尾补换行（文本文件惯例）。 */
    private fun renderDocument(entries: List<String>): String =
        if (entries.isEmpty()) "[]\n" else entries.joinToString(",\n", prefix = "[\n  ", postfix = "\n]\n")

    /**
     * 读已有 `ops.json` 的条目：文件不存在或内容为空白时返回空列表。
     *
     * @return 每项为「该条目在写回时使用的文本」与「其 `name`（解析不到为 null）」。
     */
    private fun readExistingEntries(file: File): List<Pair<String, String?>> {
        if (!file.isFile) return emptyList()
        val text = file.readText(StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return emptyList()
        if (!text.startsWith("[") || !text.endsWith("]")) {
            // 不静默当空处理：直接覆盖会抹掉消费方已声明的 OP，而静默忽略则会让本轮提权失效
            throw IllegalArgumentException(
                "mcTestkit 无法合并 ${file.absolutePath}：它不是 JSON 数组（ops.json 顶层须为数组）；" +
                    "请修正该文件或删除后重试。",
            )
        }
        return objectEntries(text.substring(1, text.length - 1)).map { raw ->
            Pair(raw, NAME_FIELD.find(raw)?.groupValues?.get(1))
        }
    }

    /** 按顶层对象切分数组正文，返回每个对象 `{...}` 的原文（保留原有条目的字段与顺序）。 */
    private fun objectEntries(arrayBody: String): List<String> {
        val entries = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        arrayBody.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = index
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            entries += arrayBody.substring(start, index + 1).trim()
                            start = -1
                        }
                    }
                }
            }
        }
        return entries
    }

    /** 转义 JSON 字符串内容（含控制字符，避免用户名里的特殊字符撕坏文档）。 */
    private fun escapeJson(value: String): String {
        val builder = StringBuilder(value.length + 8)
        value.forEach { char ->
            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char < ' ' -> builder.append("\\u%04x".format(char.code))
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    /** 取 16 字节 UUID 的 `8-4-4-4-12` 小写文本。 */
    private fun formatUuid(bytes: ByteArray): String {
        val hex = StringBuilder(32)
        bytes.forEach { hex.append("%02x".format(it.toInt() and 0xff)) }
        return buildString(36) {
            append(hex, 0, 8).append('-')
            append(hex, 8, 12).append('-')
            append(hex, 12, 16).append('-')
            append(hex, 16, 20).append('-')
            append(hex, 20, 32)
        }
    }

    /** 匹配条目里的 `"name":"..."`（值部分允许转义）。 */
    private val NAME_FIELD = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
}
