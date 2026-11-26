package top.wcpe.mc.testkit.dsl

import top.wcpe.mc.testkit.bot.ProcessLedger
import top.wcpe.mc.testkit.contract.McTestkitPidFile
import top.wcpe.mc.testkit.provision.ExternalArtifactProvisioner
import top.wcpe.mc.testkit.provision.ExternalArtifactSource
import top.wcpe.mc.testkit.provision.JsonLite
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Beacon broker 测试环境的场景级供给参数。
 *
 * 一段 [ScenarioSpec.beaconBroker] 声明即完成四件事：
 * 1. `beforeScenario`——按平台坐标从 GitHub Release 供给控制面二进制（缓存于 `build/mc-testkit/results/beacon-cache`），
 *    落 `config.yml`、起后台进程、等管理端口、登录、建 namespace、把 `bootstrap-token`
 *    写进各后端 `plugins/BeaconAgent/config.yml` 与代理 `plugins/BeaconAgentProxy/config.yml`；
 *    同时把 BeaconAgent / BeaconAgentProxy 插件 jar 注入对应 plugins 目录。
 * 2. `readyScenario`——轮询 `agent-identities` 待审身份并逐条批准（approve 链），直至全部 `active`。
 *    仅集群形态场景支持 `readyScenario`，故 [approveIdentities] 要求场景是集群形态；
 *    单端场景请设 `approveIdentities = false` 并在场景内自行核验。
 * 3. `afterScenario`——按 pid 收尾控制面进程（进程台账兜底核对身份）。
 * 4. 噪音豁免——[expectedPorts] 是本环境对外开放的端口白名单声明（默认含 [port]）；
 *    消费方若有额外常驻监听（如自备代理、桩服务）挂进同一列表，供端口占用核对与台账认领使用，
 *    避免「非预期监听」误报。
 *
 * @property version Beacon 版本（对应 release tag `v<version>`）。
 * @property port 控制面 HTTP 端口。
 * @property adminUser / adminPassword 管理账号；[adminPassword] 为 null 时按
 *   `BEACON_ADMIN_PASSWORD` 环境变量 → 固定测试值 `beacon-e2e-admin` 解析。
 * @property authSecret 签名密钥；为 null 时按 `BEACON_AUTH_SECRET` → 固定测试值解析。
 * @property namespaceName / namespaceCode 供 agent 接入的 namespace 名称与编码。
 * @property releaseBaseUrl 下载基址（`<base>/v<version>/<file>`）。
 * @property controlPlaneUrl / agentJarUrl / agentProxyJarUrl 覆盖默认 release 资产地址。
 * @property injectAgents 是否把 BeaconAgent / BeaconAgentProxy jar 注入运行目录（消费方已在
 *   `dependencies` 里供给时设 false）。
 * @property approveIdentities 是否在 `readyScenario` 里走身份批准链（见上文约束）。
 * @property serverIds 后端名 → 期望 serverId（approve 时绑定用）；未指定的后端按后端名。
 * @property readyTimeoutMs / approveTimeoutMs 控制面就绪门与身份激活轮询的超时。
 * @property expectedSha256 控制面二进制完整性校验（小写十六进制）。
 * @property expectedPorts 噪音豁免端口白名单（默认 `[port]`）。
 */
class BeaconBrokerSpec : java.io.Serializable {
    var version: String = "1.5.0"
    var port: Int = 18848
    var adminUser: String = "admin"
    var adminPassword: String? = null
    var authSecret: String? = null
    var namespaceName: String = "e2e"
    var namespaceCode: String = "e2e"
    var releaseBaseUrl: String = "https://github.com/wcpe/Beacon/releases/download"
    var controlPlaneUrl: String? = null
    var agentJarUrl: String? = null
    var agentProxyJarUrl: String? = null
    var injectAgents: Boolean = true
    var approveIdentities: Boolean = true
    var serverIds: MutableMap<String, String> = mutableMapOf()
    var readyTimeoutMs: Long = 60_000
    var approveTimeoutMs: Long = 120_000
    var expectedSha256: String? = null
    var expectedPorts: MutableList<Int> = mutableListOf()

    /**
     * broker-messaging 自证探针（默认 true）：全部身份 active 后，钩子注册两个探针身份
     * （probe-a → probe-b），走 `send → poll → ack` 一次真实跨服消息投递。
     * 单端场景也成立（消息在控制面中转，与后端数量无关）。
     */
    var messagingProbe: Boolean = true

    internal fun resolvedPassword(): String =
        adminPassword ?: System.getenv("BEACON_ADMIN_PASSWORD") ?: "beacon-e2e-admin"

    internal fun resolvedSecret(): String =
        authSecret ?: System.getenv("BEACON_AUTH_SECRET") ?: "beacon-e2e-secret"
}

private fun beaconPlatformTag(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val archTag = when {
        arch == "aarch64" || arch == "arm64" -> "arm64"
        arch == "x86_64" || arch == "amd64" -> "amd64"
        else -> throw IllegalStateException("Beacon 未覆盖的 CPU 架构：$arch")
    }
    return when {
        os.contains("mac") -> "darwin-$archTag"
        os.contains("windows") -> "windows-$archTag"
        os.contains("linux") -> "linux-$archTag"
        else -> throw IllegalStateException("Beacon 未覆盖的操作系统：$os")
    }
}

private fun beaconExeSuffix(): String =
    if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""

private fun writeAgentBootstrapConfig(pluginDir: File, endpoint: String, token: String) {
    pluginDir.mkdirs()
    File(pluginDir, "config.yml").writeText(
        """
        beacon:
          endpoints:
            - "$endpoint"
          bootstrap-token: "$token"
        """.trimIndent() + "\n",
    )
}

/** Beacon 管理面最小客户端（登录 / namespace / agent 身份批准链）。 */
internal class BeaconAdmin(
    private val baseUrl: String,
) : java.io.Serializable {
    private var token: String = ""

    fun login(user: String, password: String) {
        val body = request("POST", "/admin/v1/auth/login", """{"username":"$user","password":"$password"}""", authed = false)
        token = JsonLite.asObject(JsonLite.parse(body))["token"]?.toString()
            ?: throw IllegalStateException("Beacon 登录响应缺少 token 字段：$body")
    }

    /** 建 namespace 并返回接入 token；同名编码已存在时读取既有 token（通过轮换取回明文）。 */
    fun ensureNamespace(name: String, code: String): Pair<String, String> {
        val list = JsonLite.asObject(JsonLite.parse(request("GET", "/admin/v2/namespaces", null)))

        @Suppress("UNCHECKED_CAST")
        val items = (list["items"] ?: list["namespaces"] ?: list["data"]) as? List<Any?> ?: emptyList()
        val existing = items.mapNotNull { JsonLite.asObject(it) }
            .firstOrNull { it["code"]?.toString() == code }
        if (existing != null) {
            val id = existing["id"].toString().substringBefore(".")
            val rotated = JsonLite.asObject(
                JsonLite.parse(request("POST", "/admin/v2/namespaces/$id/token/rotate", "{}")),
            )
            val accessToken = rotated["accessToken"]?.toString()
                ?: throw IllegalStateException("namespace token 轮换响应缺少 accessToken")
            return id to accessToken
        }
        val created = JsonLite.asObject(
            JsonLite.parse(request("POST", "/admin/v2/namespaces", """{"name":"$name","code":"$code"}""")),
        )
        val id = created["id"].toString().substringBefore(".")
        val accessToken = created["accessToken"]?.toString()
            ?: throw IllegalStateException("namespace 创建响应缺少 accessToken：$created")
        return id to accessToken
    }

    fun pendingIdentities(namespaceId: String): List<Map<String, Any?>> {
        val body = request("GET", "/admin/v2/agent-identities?namespaceId=$namespaceId&pageSize=200", null)
        val obj = JsonLite.asObject(JsonLite.parse(body))

        @Suppress("UNCHECKED_CAST")
        val items = (obj["items"] ?: obj["identities"] ?: obj["data"]) as? List<Any?> ?: emptyList()
        return items.mapNotNull { JsonLite.asObject(it) }
    }

    fun approveIdentity(identityId: String, serverId: String): String {
        val body = request("POST", "/admin/v2/agent-identities/$identityId/approve", """{"serverId":"$serverId","reason":"mc-testkit e2e 自动批准"}""")
        val obj = JsonLite.asObject(JsonLite.parse(body))
        return (obj["approvalRequestId"] ?: obj["requestId"] ?: obj["id"])?.toString()
            ?: throw IllegalStateException("身份批准响应缺少 approvalRequestId：$body")
    }

    /**
     * 批准二级审批票；409 视作已决（票据在身份入库绑定时可能已被先行裁决），
     * 由调用方随后用身份状态复核，不当作硬错误。
     */
    fun approveRequest(requestId: String): Boolean {
        return try {
            request("POST", "/admin/v2/approval-requests/$requestId/approve", """{"decision":"approve"}""")
            true
        } catch (e: IllegalStateException) {
            if (e.message?.contains("→ 409") == true) false else throw e
        }
    }

    /** 以 agent 身份发数据面请求（X-Beacon-Token + X-Beacon-Identity + X-Beacon-Boot）。 */
    fun agentRequest(
        method: String,
        path: String,
        bootstrapToken: String,
        identityId: String,
        bootId: String,
        body: String?,
        acceptedCodes: Set<Int> = setOf(200, 201, 202),
    ): Pair<Int, String> {
        val conn = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 40_000
        conn.setRequestProperty("X-Beacon-Token", bootstrapToken)
        conn.setRequestProperty("X-Beacon-Identity", identityId)
        conn.setRequestProperty("X-Beacon-Boot", bootId)
        conn.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use(InputStream::readBytes)?.toString(Charsets.UTF_8).orEmpty()
        conn.disconnect()
        if (code !in acceptedCodes) throw IllegalStateException("Beacon 数据面 $method $path → $code：$text")
        return code to text
    }

    private fun request(method: String, path: String, body: String?, authed: Boolean = true): String {
        val conn = URI("$baseUrl$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        if (authed && token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use(InputStream::readBytes)?.toString(Charsets.UTF_8).orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Beacon 管理面 $method $path → $code：$text")
        return text
    }
}

/**
 * `beforeScenario`：供给并拉起 Beacon 控制面，完成登录与 namespace 接入配置下发。
 */
internal class BeaconBrokerBeforeHook(private val spec: BeaconBrokerSpec) : ScenarioHook, java.io.Serializable {

    override fun run(context: HookContext) {
        val hooksDir = File(context.resultsDir, "hooks/${context.scenarioName}").apply { mkdirs() }
        val workDir = File(hooksDir, "beacon-broker").apply { mkdirs() }
        // 场景隔离：控制面 sqlite 库随轮次留存会让上轮残留身份混进本轮 approve 链，
        // 起控制面前清库，保证 identity/namespace 都是本轮新生。
        listOf("beacon.db", "beacon.db-wal", "beacon.db-shm").forEach { File(workDir, it).delete() }
        val cacheRoot = File(context.resultsDir, "beacon-cache").apply { mkdirs() }
        val provisioner = ExternalArtifactProvisioner(cacheRoot)

        val tag = beaconPlatformTag()
        val binary = provisioner.provision(
            ExternalArtifactSource.FixedUrl(
                id = "beacon-control-plane-${spec.version}-$tag",
                fileName = "beacon${beaconExeSuffix()}",
                url = spec.controlPlaneUrl
                    ?: "${spec.releaseBaseUrl}/v${spec.version}/beacon-${spec.version}-$tag${beaconExeSuffix()}",
                expectedSha256 = spec.expectedSha256,
            ),
        ) { context.info(it) }
        val localBinary = File(workDir, "beacon${beaconExeSuffix()}")
        Files.copy(binary.toPath(), localBinary.toPath(), StandardCopyOption.REPLACE_EXISTING)
        localBinary.setExecutable(true, false)

        val password = spec.resolvedPassword()
        File(workDir, "config.yml").writeText(
            """
            http-addr: ":${spec.port}"
            auth:
              username: "${spec.adminUser}"
              password: "$password"
              secret: "${spec.resolvedSecret()}"
            database:
              driver: "sqlite"
              sqlite:
                dsn: "beacon.db"
            """.trimIndent() + "\n",
        )

        val logFile = File(context.resultsDir, "hook-${context.scenarioName}-beacon.log")
        val pidFile = File(context.resultsDir, "hook-${context.scenarioName}-beacon.pid")
        context.info("启动 Beacon 控制面（端口 ${spec.port}，日志 ${logFile.name}）")
        val process = ScenarioHookRuntime.startBackground(
            listOf(localBinary.absolutePath, "-config", "config.yml"),
            emptyMap(),
            workDir,
            logFile,
            budget = context.outputBudget(),
            lifetimeMs = context.processLifetimeMs,
            cleanup = context.isCleanup(),
        )
        context.outputBudget().supervisor.bindPidFile(process, pidFile)
        McTestkitPidFile.write(pidFile, process.pid())
        ProcessLedger.record(
            context.resultsDir,
            ScenarioHookRuntime.ledgerKey(context.resultsDir, pidFile),
            process.pid(),
            spec.port,
        )
        ScenarioHookRuntime.awaitPort(context, spec.port, spec.readyTimeoutMs, "Beacon 控制面端口 ${spec.port}")

        val endpoint = "http://127.0.0.1:${spec.port}"
        val admin = BeaconAdmin(endpoint)
        admin.login(spec.adminUser, password)
        val (namespaceId, token) = admin.ensureNamespace(spec.namespaceName, spec.namespaceCode)

        // 落接入凭据到结果目录：approve 钩子与 driver / bot 场景脚本据此接续（运行时生成、非仓库凭据）。
        val credFile = File(context.resultsDir, "beacon-broker-${context.scenarioName}.properties")
        credFile.writeText(
            """
            endpoint=$endpoint
            adminUser=${spec.adminUser}
            adminPassword=$password
            namespaceId=$namespaceId
            namespaceCode=${spec.namespaceCode}
            bootstrapToken=$token
            """.trimIndent() + "\n",
        )
        credFile.setReadable(true, true)

        context.backendRunDirs.forEach { (name, runDir) ->
            val pluginsDir = File(runDir, "plugins")
            writeAgentBootstrapConfig(File(pluginsDir, "BeaconAgent"), endpoint, token)
            if (spec.injectAgents) {
                injectArtifact(
                    provisioner,
                    context,
                    pluginsDir,
                    id = "beacon-agent-${spec.version}",
                    fileName = "BeaconAgent.jar",
                    url = spec.agentJarUrl
                        ?: "${spec.releaseBaseUrl}/v${spec.version}/BeaconAgent-${spec.version}.jar",
                    label = name,
                )
            }
        }
        context.proxyRunDir?.let { runDir ->
            val pluginsDir = File(runDir, "plugins")
            writeAgentBootstrapConfig(File(pluginsDir, "BeaconAgentProxy"), endpoint, token)
            if (spec.injectAgents) {
                injectArtifact(
                    provisioner,
                    context,
                    pluginsDir,
                    id = "beacon-agent-proxy-${spec.version}",
                    fileName = "BeaconAgentProxy.jar",
                    url = spec.agentProxyJarUrl
                        ?: "${spec.releaseBaseUrl}/v${spec.version}/BeaconAgentProxy-${spec.version}.jar",
                    label = "proxy",
                )
            }
        }
        context.info("Beacon 接入配置已写入 ${context.backendRunDirs.size} 个后端${if (context.proxyRunDir != null) "与代理" else ""}")
    }

    private fun injectArtifact(
        provisioner: ExternalArtifactProvisioner,
        context: HookContext,
        pluginDir: File,
        id: String,
        fileName: String,
        url: String,
        label: String,
    ) {
        val jar = provisioner.provision(ExternalArtifactSource.FixedUrl(id, fileName, url)) { context.info(it) }
        pluginDir.mkdirs()
        Files.copy(jar.toPath(), File(pluginDir, fileName).toPath(), StandardCopyOption.REPLACE_EXISTING)
        context.info("Beacon 插件注入 $label：$fileName")
    }
}

/**
 * `readyScenario`：轮询 agent 身份并逐条走批准链，直至全部 `active`（集群形态）。
 */
internal class BeaconApproveHook(private val spec: BeaconBrokerSpec) : ScenarioHook, java.io.Serializable {

    override fun run(context: HookContext) {
        // 接续 before 钩子落的凭据（namespace 复用，不再轮换 token——避免已接入 agent 401）。
        val credFile = File(context.resultsDir, "beacon-broker-${context.scenarioName}.properties")
        val cred = credFile.readLines().filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val endpoint = cred["endpoint"] ?: "http://127.0.0.1:${spec.port}"
        val namespaceId = cred["namespaceId"]
            ?: throw IllegalStateException("缺少 $credFile 的 namespaceId（before 钩子未落成）")
        val bootstrapToken = cred["bootstrapToken"].orEmpty()

        val admin = BeaconAdmin(endpoint)
        admin.login(spec.adminUser, spec.resolvedPassword())

        val expected = context.backendRunDirs.keys.map { name -> spec.serverIds[name] ?: name }.toMutableList()
        context.proxyRunDir?.let { expected += (spec.serverIds["proxy"] ?: "proxy") }
        val deadline = System.currentTimeMillis() + spec.approveTimeoutMs

        fun boundServerOf(identity: Map<String, Any?>): String =
            (identity["serverId"] ?: identity["serverName"] ?: identity["requestedServerId"])
                ?.toString().orEmpty()

        while (System.currentTimeMillis() < deadline) {
            val identities = admin.pendingIdentities(namespaceId)
            // 已占坑位：active 且绑定目标在期望集内的 serverId（本轮已指派的也算占用，避免重复指派）
            val activeServers = identities.filter { it["status"]?.toString() == "active" }
                .map { boundServerOf(it) }.filter { it in expected }.toMutableSet()
            for (identity in identities) {
                if (identity["status"]?.toString() != "pending") continue
                val id = (identity["identityId"] ?: identity["id"]).toString().substringBefore(".")
                val requested = boundServerOf(identity)
                val serverId = requested.takeIf { it in expected && it !in activeServers }
                    ?: expected.firstOrNull { it !in activeServers }
                    ?: requested.ifEmpty { id }
                context.info("批准 agent 身份 $id（serverId=$serverId）")
                val requestId = admin.approveIdentity(id, serverId)
                val decided = admin.approveRequest(requestId)
                if (!decided) {
                    context.info("审批票 $requestId 已先决（409），下轮以身份状态复核")
                }
                activeServers += serverId
            }
            val matched = identities.filter {
                it["status"]?.toString() == "active" && boundServerOf(it) in expected
            }
            if (matched.size >= expected.size && expected.isNotEmpty()) {
                context.info("Beacon 身份全部 active（${matched.size}/${expected.size}）")
                if (spec.messagingProbe && bootstrapToken.isNotEmpty()) {
                    runMessagingProbe(context, admin, namespaceId, bootstrapToken)
                }
                return
            }
            Thread.sleep(2_000)
        }
        val active = admin.pendingIdentities(namespaceId)
            .filter { it["status"]?.toString() == "active" }
            .map { boundServerOf(it) }.toSet()
        throw IllegalStateException(
            "等待 Beacon 身份激活超时（${spec.approveTimeoutMs}ms）：期望 ${expected.size} 个，" +
                "active 仅 ${active.size} 个。可查 hook 日志 hook-${context.scenarioName}-beacon.log",
        )
    }

    /** 注册两个探针身份走一次真实 send→poll→ack，自证 broker 消息投递链可用。 */
    private fun runMessagingProbe(
        context: HookContext,
        admin: BeaconAdmin,
        namespaceId: String,
        bootstrapToken: String,
    ) {
        fun registerAndApprove(serverId: String): Pair<String, String> {
            val identityId = java.util.UUID.randomUUID().toString()
            val bootId = java.util.UUID.randomUUID().toString()
            val body =
                """{"identityId":"$identityId","kind":"backend","bootId":"$bootId","agentVersion":"e2e-probe"}"""
            admin.agentRequest("POST", "/beacon/v2/agent/register", bootstrapToken, "", "", body)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                val mine = admin.pendingIdentities(namespaceId)
                    .firstOrNull {
                        (it["identityId"] ?: it["id"]).toString() == identityId
                    }
                when (mine?.get("status")?.toString()) {
                    "pending" -> {
                        val requestId = admin.approveIdentity(identityId, serverId)
                        admin.approveRequest(requestId)
                    }
                    "active" -> {
                        context.info("探针身份 $serverId active")
                        return identityId to bootId
                    }
                }
                Thread.sleep(1_000)
            }
            throw IllegalStateException("探针身份 $serverId 未在 60s 内激活")
        }

        val (aId, aBoot) = registerAndApprove("mctk-probe-a")
        val (bId, bBoot) = registerAndApprove("mctk-probe-b")

        val messageId = java.util.UUID.randomUUID().toString()
        val send = admin.agentRequest(
            "POST",
            "/beacon/v2/agent/messages/send",
            bootstrapToken,
            aId,
            aBoot,
            """{"messageId":"$messageId","msgType":"e2e.ping","targetKind":"server",""" +
                """"targetServerId":"mctk-probe-b","payload":{"text":"ping"}}""",
        )
        val sendStatus = JsonLite.asObject(JsonLite.parse(send.second))["status"]?.toString()
        require(sendStatus == "accepted") { "broker send 未受理：${send.second}" }
        context.info("broker 探针 send→mctk-probe-b：$sendStatus")

        val deadline = System.currentTimeMillis() + 60_000
        var received: Map<String, Any?>? = null
        while (System.currentTimeMillis() < deadline && received == null) {
            val (code, body) = admin.agentRequest(
                "POST",
                "/beacon/v2/agent/messages/poll",
                bootstrapToken,
                bId,
                bBoot,
                """{"waitSec":5,"max":10}""",
                acceptedCodes = setOf(200, 204),
            )
            if (code == 200) {
                received = (JsonLite.asObject(JsonLite.parse(body))["messages"] as? List<*>)
                    ?.mapNotNull { JsonLite.asObject(it) }
                    ?.firstOrNull { it["messageId"]?.toString() == messageId }
            }
        }
        requireNotNull(received) { "mctk-probe-b 未在 60s 内收到 broker 消息 $messageId" }
        require(received["sourceServerId"]?.toString() == "mctk-probe-a") { "消息源服不符：$received" }

        val ack = admin.agentRequest(
            "POST",
            "/beacon/v2/agent/messages/ack",
            bootstrapToken,
            bId,
            bBoot,
            """{"results":[{"messageId":"$messageId","status":"delivered"}]}""",
        )
        val applied = JsonLite.asObject(JsonLite.parse(ack.second))["applied"]
        require(applied.toString() == "1" || applied.toString() == "1.0") { "ack 未应用：${ack.second}" }
        context.info("broker-messaging 探针 PASS（send→poll→ack）")
    }
}
