package top.wcpe.mc.testkit.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/** 压测代理的一个钉服绑定：某 listener 端口 → 钉死的后端（名 + 地址）。 */
data class StressProxyBinding(
    val backendName: String,
    val backendAddress: String,
    val listenPort: Int,
)

/** 监听器最大人数（压测峰值留余量）。 */
private const val LISTENER_MAX_PLAYERS = 600

/** 用 BLOCK 风格 snakeyaml 把配置树序列化为 `config.yml` 文本（真实 YAML 序列化，不拼字符串）。 */
private fun dumpYaml(root: Map<String, Any?>): String {
    val options = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = false
        indent = 2
        indicatorIndent = 0
    }
    return Yaml(options).dump(root)
}

/** 构建一个 BungeeCord listener 对象（priorities 首个为默认服 + force_default_server 钉服）。 */
private fun listener(port: Int, priorities: List<String>, motd: String): Map<String, Any?> = linkedMapOf(
    "host" to "0.0.0.0:$port",
    "query_port" to port,
    "motd" to motd,
    "max_players" to LISTENER_MAX_PLAYERS,
    "priorities" to priorities,
    "bind_local_address" to true,
    "force_default_server" to true,
    "ping_passthrough" to false,
    "tab_list" to "GLOBAL_PING",
    "tab_size" to 60,
)

/** 构建一个 server 条目对象（地址 + 不限制 + 展示名）。 */
private fun server(address: String, motd: String): Map<String, Any?> = linkedMapOf(
    "address" to address,
    "restricted" to false,
    "motd" to motd,
)

/**
 * 组装代理根配置：listeners + servers + 透传/限流固定项。
 * `ip_forward: true`（透传真实 IP 与离线 UUID，与后端 `spigot.yml settings.bungeecord` 配套）、
 * `online_mode: false`（放行离线机器人）、关连接限流（避免入服被节流踢出）。
 */
private fun proxyRoot(listeners: List<Map<String, Any?>>, servers: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
    "listeners" to listeners,
    "servers" to servers,
    "ip_forward" to true,
    "online_mode" to false,
    "player_limit" to -1,
    "timeout" to 30000,
    "connection_throttle" to -1,
    "connection_throttle_interval" to 0,
    "prevent_proxy_connections" to false,
)

/**
 * 单后端经代理（任务自动编排）：单 listener 绑一个端口、强制转发到单个后端。
 *
 * @param listenPort 代理监听端口（机器人连此端口进服）。
 * @param backendAddress 后端地址，形如 `127.0.0.1:25565`。
 * @param motd 监听器 / 后端展示名（默认通用占位）。
 */
fun bungeeProxyConfigYml(
    listenPort: Int,
    backendAddress: String,
    motd: String = "mc-testkit E2E",
): String = dumpYaml(
    proxyRoot(
        listeners = listOf(listener(listenPort, listOf("backend"), motd)),
        servers = linkedMapOf("backend" to server(backendAddress, motd)),
    ),
)

/**
 * 集群经代理（集群编排）：单 listener + N 个具名 server（server 名 = 后端名）。bot 连此 listener 落到首个
 * 后端（`force_default_server` + `priorities` 首个），再经 `/server <后端名>` 在同一 TCP 连接 fast-transfer 切服。
 *
 * `priorities` 为**全部后端有序列表**（首个为默认服，其余作 fallback）：默认服宕机时 bot 重连回退到下一个，
 * 支撑「崩溃接管」类测试（某后端崩溃后 bot 经代理落到存活后端，由其接管上线）。正常 `/server` 切换不受影响。
 *
 * @param listenPort 代理监听端口。
 * @param backends 有序 (后端名, 地址) 列表，首个为默认服，全部入 priorities 作 fallback。
 * @param motd 监听器展示名。
 */
fun bungeeClusterProxyConfigYml(
    listenPort: Int,
    backends: List<Pair<String, String>>,
    motd: String = "mc-testkit E2E cluster",
): String {
    require(backends.isNotEmpty()) { "集群代理至少需一个后端 server。" }
    val servers = linkedMapOf<String, Any?>()
    backends.forEach { (name, address) -> servers[name] = server(address, name) }
    return dumpYaml(
        proxyRoot(
            listeners = listOf(listener(listenPort, backends.map { it.first }, motd)),
            servers = servers,
        ),
    )
}

/**
 * 压测经代理（压测编排）：N 个 listener **一端口对一后端**——listener[i] 的 `priorities` 钉到对应后端、
 * `force_default_server` 钉服，故连某 listener 端口的 bot **钉死在该后端**（不 `/server` 切换）。
 * `servers` 段含全部后端供路由。
 *
 * @param bindings 有序钉服绑定：每项 = (后端名, 后端地址, 该后端独占的 listener 端口)。
 * @param motd 监听器展示名前缀（拼上后端名）。
 */
fun bungeeStressProxyConfigYml(
    bindings: List<StressProxyBinding>,
    motd: String = "mc-testkit E2E stress",
): String {
    require(bindings.isNotEmpty()) { "压测代理至少需一个钉服绑定。" }
    val listeners = bindings.map { listener(it.listenPort, listOf(it.backendName), "$motd ${it.backendName}") }
    val servers = linkedMapOf<String, Any?>()
    bindings.forEach { servers[it.backendName] = server(it.backendAddress, it.backendName) }
    return dumpYaml(proxyRoot(listeners, servers))
}
