package top.wcpe.mc.testkit.config

/**
 * Velocity 代理配置（`velocity.toml`）生成（纯函数，FR-02/03，见 ADR-0010）。
 *
 * Velocity 是**单端口**代理（bind 一个端口），靠内置 `/server` 切服、`try` 列表做落地 / fallback 顺序；
 * 没有 BungeeCord 的「N-listener 一端口对一后端」概念——故 Velocity **不支持压测钉服模型**（钉服靠
 * 一端口对一后端），编排在 `stress + via=velocity` 时配置期中文报错（见 [McTestkitTasks][top.wcpe.mc.testkit.task.McTestkitTasks]）。
 * modern forwarding 经共享 secret 把真实玩家身份 / UUID 转发给后端（与后端 paper-global velocity.secret 同值）。
 */

/** Velocity forwarding secret 文件名（与 `velocity.toml` 的 `forwarding-secret-file` 一致）。 */
const val VELOCITY_FORWARDING_SECRET_FILE = "forwarding.secret"

/** Velocity 监听器最大人数（与 BungeeCord 侧一致，压测峰值留余量）。 */
private const val VELOCITY_SHOW_MAX_PLAYERS = 600

/**
 * 生成 `velocity.toml` 文本：modern forwarding + 离线 + N 个具名 server + `try` 顺序。
 *
 * @param listenPort 代理监听端口（bot 连此端口进服）。
 * @param servers 有序 (server 名, 地址) 列表；首个为默认落地服，全部入 `try` 作 fallback 顺序
 *   （默认服宕机时落下一个存活服，支撑 FR-15 崩溃接管）。地址形如 `127.0.0.1:25565`。
 */
fun velocityProxyConfigToml(
    listenPort: Int,
    servers: List<Pair<String, String>>,
): String {
    require(servers.isNotEmpty()) { "Velocity 代理至少需一个 server。" }
    val sb = StringBuilder()
    sb.appendLine("# mc-testkit E2E Velocity 代理配置（modern forwarding，自动生成）")
    sb.appendLine("config-version = \"2.7\"")
    sb.appendLine("bind = \"0.0.0.0:$listenPort\"")
    sb.appendLine("motd = \"mc-testkit E2E\"")
    sb.appendLine("show-max-players = $VELOCITY_SHOW_MAX_PLAYERS")
    // 离线模式：放行离线机器人（与后端 online-mode=false 配套）
    sb.appendLine("online-mode = false")
    // 关强制密钥认证：放行无 Mojang 签名 profile key 的离线机器人。Velocity 3.1.2+ 此项默认 true，会把
    // 1.19+ 无签名 key 的离线客户端在登录 / 发命令时踢掉（含经代理 /server 切服的命令）——离线测试机器人
    // 没有签名 key，必须关，否则 bot 入不了服 / 切不了服（表现为桩「等待玩家加入超时」）。
    sb.appendLine("force-key-authentication = false")
    // modern forwarding：经共享 secret 把真实玩家身份 / UUID 转发给后端
    sb.appendLine("player-info-forwarding-mode = \"modern\"")
    sb.appendLine("forwarding-secret-file = \"$VELOCITY_FORWARDING_SECRET_FILE\"")
    sb.appendLine()
    sb.appendLine("[servers]")
    // server 名用引号键（容纳带连字符等的节点名）；值为后端地址
    servers.forEach { (name, address) -> sb.appendLine("\"$name\" = \"$address\"") }
    // try：落地 / fallback 顺序（首个默认服，其余存活后端）
    sb.appendLine("try = [")
    servers.forEachIndexed { index, (name, _) ->
        val comma = if (index < servers.size - 1) "," else ""
        sb.appendLine("    \"$name\"$comma")
    }
    sb.appendLine("]")
    sb.appendLine()
    sb.appendLine("[advanced]")
    // 连接超时给足；关登录限流（并发 bot 入服不被节流踢出）
    sb.appendLine("connection-timeout = 30000")
    sb.appendLine("login-ratelimit = 0")
    sb.appendLine()
    // 必须显式写**空** [forced-hosts]：否则 Velocity 用其默认示例 forced-hosts（lobby/factions/minigames），
    // 这些引用本配置不存在的 server，会让 Velocity 校验失败、拒绝启动（端口不开 → bot 连不上、桩超时）。
    sb.appendLine("[forced-hosts]")
    return sb.toString()
}
