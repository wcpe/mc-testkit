package com.example.e2e

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionAttachment
import top.wcpe.mc.testkit.harness.McTestkitEnv
import top.wcpe.mc.testkit.harness.McTestkitHarnessPlugin
import top.wcpe.mc.testkit.harness.McTestkitProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mc-testkit E2E 桩插件骨架（照抄物，框架无关）。
 *
 * 继承共享协议胶水基类 [McTestkitHarnessPlugin]（共享胶水构件）：场景 / 结果文件 env 读取、
 * serve 空闲、结果原子写出 + 关服、Folia 兼容调度、E2E_READY 控制消息——这些不再在此重复。
 *
 * 本类只保留**业务与示例场景**：
 * 1. 入服玩家装备：清背包、给一个示例授权位（演示 PermissionAttachment 模式）；
 * 2. 内置示例场景（smoke / example-bot / cross-server / continuous-stress / multi-bot / crash-takeover）；
 * 3. 判定收尾经基类 [pass] / [fail] 完成。
 *
 * 扩展自己的场景：在 [ScenarioName] 加 id → 在 [dispatchScenario] 加分支 → 写驱动 / 判定逻辑。
 */
class McTestkitE2eHarnessPlugin : McTestkitHarnessPlugin(), Listener {

    /** 业务配置（超时窗口；协议部分由基类处理）。 */
    private lateinit var harnessConfig: HarnessConfig

    /** 本次场景：编排 env 下发优先，回退 config.yml 的 scenario（默认 smoke）。 */
    private val scenario: ScenarioName by lazy {
        ScenarioName.from(McTestkitEnv.scenarioIdOrNull() ?: config.getString("scenario", ScenarioName.SMOKE.id)!!)
    }

    /** 场景是否已开始驱动（首个玩家触发，幂等门）。 */
    private val started = AtomicBoolean(false)

    /** 是否已发过就绪信号（幂等门）。 */
    private val readySignalSent = AtomicBoolean(false)

    /** 为每个被装备玩家挂的授权附件，退出时移除，避免泄漏。 */
    private val permissionAttachments = ConcurrentHashMap<UUID, PermissionAttachment>()

    // ── 持续压测场景（压测编排）状态 ──
    /** 各 bot 玩家上报的压测摘要（玩家名 → E2E_STRESS_RESULT 载荷）。 */
    private val stressResults = ConcurrentHashMap<String, String>()

    /** 压测是否已聚合收尾（幂等门）。 */
    private val stressFinalized = AtomicBoolean(false)

    /** 压测计时是否已开始（首个 bot 加入 CAS，避免多 bot 重复挂计时）。 */
    private val stressClockStarted = AtomicBoolean(false)

    // ── 单场景多 bot 场景（单场景多 bot）状态 ──
    /** 已入服的各 bot 玩家名（多 bot 各唯一 username，单场景多 bot）。 */
    private val multiBotJoined: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 多 bot settle 计时是否已开始（首个 bot 加入 CAS）。 */
    private val multiBotClockStarted = AtomicBoolean(false)

    /** 多 bot 是否已聚合收尾（幂等门）。 */
    private val multiBotFinalized = AtomicBoolean(false)

    /** 业务启动钩子（基类已处理协议准备）：读业务配置，延迟少量 tick 再 bootstrap。 */
    override fun onHarnessEnabled() {
        harnessConfig = HarnessConfig.from(config)
        // 延迟少量 tick 再 bootstrap，给被测插件留出 onEnable 完成的时间
        runLater(BOOTSTRAP_DELAY_TICKS) { bootstrapScenario() }
    }

    /** 场景启动：无机器人的 smoke 直接判定；需要玩家的场景挂等待超时。 */
    private fun bootstrapScenario() {
        when (scenario) {
            ScenarioName.SMOKE -> runSmokeScenario()
            ScenarioName.CONTINUOUS_STRESS -> {
                // 压测计时在「首个 bot 加入」时启动（见 onPlayerJoin）；此处只挂「迟迟无 bot」失败兜底
                logger.info("[E2E] 持续压测场景，等待首个 bot 加入后开始 ${harnessConfig.stressDurationSeconds}s 计时")
                runLater(harnessConfig.waitForPlayerSeconds * TICKS_PER_SECOND) {
                    if (!stressClockStarted.get() && !isFinished()) {
                        fail("持续压测等待首个 bot 加入超时，场景=${scenario.id}")
                    }
                }
            }
            ScenarioName.MULTI_BOT -> {
                // settle 计时在「首个 bot 加入」时启动（见 onPlayerJoin）；此处只挂「迟迟无 bot」失败兜底
                logger.info("[E2E] 单场景多 bot，等待各 bot 入服后 settle 聚合")
                runLater(harnessConfig.waitForPlayerSeconds * TICKS_PER_SECOND) {
                    if (!multiBotClockStarted.get() && !isFinished()) {
                        fail("单场景多 bot 等待首个 bot 加入超时，场景=${scenario.id}")
                    }
                }
            }
            else -> {
                logger.info("[E2E] 场景 ${scenario.id} 等待首个玩家加入，超时 ${harnessConfig.waitForPlayerSeconds}s")
                runLater(harnessConfig.waitForPlayerSeconds * TICKS_PER_SECOND) {
                    if (!started.get() && !isFinished()) {
                        fail("等待玩家加入超时，场景=${scenario.id}")
                    }
                }
            }
        }
    }

    /**
     * 烟雾场景：无机器人，仅校验桩自身与被测插件已就绪。
     *
     * 通用骨架只校验「服务端已起、桩已启用」。消费方可在此追加对被测插件的就绪校验，
     * 例如 `server.pluginManager.getPlugin("YourPlugin")?.isEnabled == true`。
     */
    private fun runSmokeScenario() {
        pass(
            "桩插件已就绪，真实服务端启动烟雾场景通过",
            // backendName 来自编排下发的 MC_TESTKIT_E2E_BACKEND_NAME（每后端身份注入）：演示消费方如何取本后端声明名做 per-backend 身份
            mapOf("server" to Bukkit.getServer().name, "backendName" to harnessBackendName()),
        )
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // 持续压测：每个 bot 都装备 + 发就绪信号（不走单次 started 门）；首个 bot 加入起计时
        if (scenario == ScenarioName.CONTINUOUS_STRESS) {
            if (stressFinalized.get()) {
                return
            }
            prepareContinuousStressBot(event.player)
            if (stressClockStarted.compareAndSet(false, true)) {
                logger.info("[E2E] 首个压测 bot 已加入，开始 ${harnessConfig.stressDurationSeconds}s 计时")
                runLater(harnessConfig.stressDurationSeconds * TICKS_PER_SECOND) { finalizeContinuousStress() }
            }
            return
        }
        // 单场景多 bot：每个 bot 都装备 + 发就绪信号（不走单次 started 门）；首个 bot 加入起 settle 计时
        if (scenario == ScenarioName.MULTI_BOT) {
            if (multiBotFinalized.get()) {
                return
            }
            prepareTestPlayer(event.player)
            multiBotJoined.add(event.player.name)
            sendControlMessage(event.player, McTestkitProtocol.readyMessage(scenario.id))
            if (multiBotClockStarted.compareAndSet(false, true)) {
                logger.info("[E2E] 首个多 bot 已加入，${MULTI_BOT_SETTLE_SECONDS}s settle 窗口后聚合判定")
                runLater(MULTI_BOT_SETTLE_SECONDS * TICKS_PER_SECOND) { finalizeMultiBot() }
            }
            return
        }
        if (isFinished()) {
            return
        }
        // 单次场景门：只让首个入服玩家触发驱动
        if (!started.compareAndSet(false, true)) {
            return
        }
        logger.info("[E2E] 使用玩家 ${event.player.name} 执行场景 ${scenario.id}")
        dispatchScenario(event.player)
    }

    /** 场景派发表：通用骨架演示 example-bot 与 cross-server（集群）；消费方在此加自己的场景分支。 */
    private fun dispatchScenario(player: Player) {
        when (scenario) {
            ScenarioName.EXAMPLE_BOT -> prepareExampleBotScenario(player)
            ScenarioName.CROSS_SERVER -> prepareCrossServerScenario(player)
            ScenarioName.CRASH_TAKEOVER -> prepareCrashTakeoverScenario(player)
            // 持续压测 / 单场景多 bot 在 onPlayerJoin 前置分支处理（不走单次 started 门），不到此
            ScenarioName.CONTINUOUS_STRESS -> Unit
            ScenarioName.MULTI_BOT -> Unit
            // serve 持久手测在基类 onEnable 已短路（桩空闲），不到此
            ScenarioName.SERVE -> Unit
            // smoke 不经玩家驱动；其余场景由消费方补充分支
            ScenarioName.SMOKE -> Unit
        }
    }

    /**
     * 跨服集群示例场景（集群编排，照抄物，刻意最薄）。
     *
     * 桩对称：每个后端的桩都「装备入服玩家 + 发就绪信号」，然后等机器人在**本服**发出「切换确认标记」。
     * 机器人经代理 `/server` 切到本服后发标记，桩收到即判 PASS（演示跨服切换链路通，含代理透传的玩家身份）。
     * 真实跨服数据一致性断言（共享库 / 缓存）由消费方在此替换为业务判定，并删掉这段示例。
     */
    private fun prepareCrossServerScenario(player: Player) {
        prepareTestPlayer(player)
        sendReadySignal(player)
        armScenarioTimeout()
    }

    /**
     * 崩溃接管 fallback 示例场景（崩溃接管，照抄物，刻意最薄）。
     *
     * 桩对称：每个后端的桩都「装备 + 发就绪信号 + 挂超时」，差异全在 [onPlayerChat]——
     * **默认后端**收到机器人发的 [TRIGGER_CRASH_MARKER] 即 [simulateCrash] 模拟宕机（不写结果）；
     * 机器人经代理 fallback 落到**存活后端**后发 [CLUSTER_ARRIVED_MARKER]，存活桩收到即判 PASS。
     * 只验**框架层** fallback 路由通；真实「存活服在归属租约 TTL 过期后接管上线」由消费方在存活桩查共享 DB 改判。
     */
    private fun prepareCrashTakeoverScenario(player: Player) {
        prepareTestPlayer(player)
        sendReadySignal(player)
        armScenarioTimeout()
    }

    /**
     * 持续压测示例场景（压测编排，照抄物，刻意最薄）。
     *
     * 桩对称无角色：每个入服 bot 都装备 + 发就绪信号，bot 据此持续施压并到时上报 `E2E_STRESS_RESULT`；
     * 桩收集各 bot 摘要、到 duration 末由 [finalizeContinuousStress] 聚合写 PASS。**不做业务断言**——
     * 真实「不超卖」等不变量请在 [finalizeContinuousStress] 里查共享 DB / 缓存改判，并删掉这段无条件 PASS。
     */
    private fun prepareContinuousStressBot(player: Player) {
        prepareTestPlayer(player)
        // 每个 bot 都发 READY（N×M 个 bot 各需就绪信号，故不用单次 readySignalSent 门）
        sendControlMessage(player, McTestkitProtocol.readyMessage(scenario.id))
    }

    /** 持续压测收尾（duration 末，幂等）：聚合各 bot 上报摘要 + 写 PASS + 关服。 */
    private fun finalizeContinuousStress() {
        if (!stressFinalized.compareAndSet(false, true)) {
            return
        }
        val reportedBots = stressResults.size
        val onlineCount = Bukkit.getOnlinePlayers().size
        val details = linkedMapOf(
            "onlinePlayers" to onlineCount.toString(),
            "reportedBots" to reportedBots.toString(),
            "stressDurationSeconds" to harnessConfig.stressDurationSeconds.toString(),
        )
        stressResults.toSortedMap().forEach { (name, summary) -> details["bot.$name"] = summary }
        logger.info("[E2E][STRESS] 持续压测收尾：在线=$onlineCount 上报 bot 数=$reportedBots")
        // 薄示例：本服跑完压测即 PASS + 收集各 bot 摘要。真实「不超卖」等业务不变量请在此查共享 DB 改判。
        pass("持续压测场景收尾完成（示例：真实不变量请查共享 DB 断言）", details)
    }

    /**
     * 单场景多 bot settle 收尾（单场景多 bot，首个 bot 加入后 settle 窗口末，幂等）：聚合各 bot username + 写 PASS + 关服。
     *
     * 薄示例只校验「多个各唯一 username 的 bot 都入服」（演示 单场景多 bot 多进程身份注入 + 全回收的真机链路通），
     * 不做业务断言；唯一 username 的精确断言由 CI 步骤 grep 结果文件完成（不把期望数 N 硬塞进通用骨架）。
     */
    private fun finalizeMultiBot() {
        if (!multiBotFinalized.compareAndSet(false, true)) {
            return
        }
        val joined = multiBotJoined.toSortedSet()
        val details = linkedMapOf(
            "count" to joined.size.toString(),
            "joinedBots" to joined.joinToString(","),
        )
        logger.info("[E2E][MULTI-BOT] 多 bot 聚合：count=${joined.size} joinedBots=${joined.joinToString(",")}")
        pass("单场景多 bot 全部入服（示例：唯一 username 由 CI 断言）", details)
    }

    /**
     * 机器人驱动示例场景：装备玩家 → 发就绪信号 → 等机器人自行完成最小动作。
     *
     * 这里刻意做到最薄：只装备 + 发 READY + 挂超时，不点 GUI、不断言背包，
     * 避免把任何业务玩法固化进“通用”骨架。消费方按真实用例在此补驱动与判定。
     */
    private fun prepareExampleBotScenario(player: Player) {
        prepareTestPlayer(player)
        sendReadySignal(player)
        // 示例场景没有服务端侧的成功判据，统一用「机器人完成最小动作」的预期：
        // 直接挂一个较短的成功延时演示「桩判 PASS」的位置。真实场景应改为由
        // 业务事件 / 控制消息回报触发 pass，并删掉这段无条件 PASS。
        runLater(EXAMPLE_PASS_DELAY_TICKS) {
            if (!isFinished()) {
                pass(
                    "机器人驱动示例场景通过（示例：请替换为真实判定）",
                    mapOf("player" to player.name),
                )
            }
        }
        armScenarioTimeout()
    }

    /** 装备入服玩家：清背包 + 给一个示例授权位（演示 PermissionAttachment 用法）。 */
    private fun prepareTestPlayer(player: Player) {
        player.inventory.clear()
        val attachment = permissionAttachments.computeIfAbsent(player.uniqueId) {
            player.addAttachment(this)
        }
        // 示例授权：消费方按被测插件的真实权限节点替换 / 增补
        attachment.setPermission(EXAMPLE_PERMISSION, true)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        permissionAttachments.remove(event.player.uniqueId)?.remove()
    }

    /**
     * 跨服场景（集群编排）：收到机器人切到本服后发的「切换确认标记」（聊天）即判 PASS。
     *
     * 注：此处用 `AsyncPlayerChatEvent` 接收 bot 控制消息，在较新 Paper（1.19+）已弃用——示例以默认
     * `paper-api:1.20.1` 为准、可直接照抄；消费方若目标后端更高版本，可改用 `AsyncChatEvent`（Adventure
     * 组件，取文本经 `PlainTextComponentSerializer`）替代，控制协议字面量不变。
     */
    @EventHandler
    fun onPlayerChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
        // 持续压测：收集各 bot 上报的 E2E_STRESS_RESULT（不触发 pass/fail，N×M bot 不能各自关服）
        if (scenario == ScenarioName.CONTINUOUS_STRESS) {
            if (event.message.startsWith(McTestkitProtocol.STRESS_RESULT_PREFIX)) {
                stressResults[event.player.name] = event.message.removePrefix(McTestkitProtocol.STRESS_RESULT_PREFIX)
                logger.info("[E2E][STRESS] 收到 ${event.player.name} 压测汇总: ${event.message}")
            }
            return
        }
        if (isFinished()) {
            return
        }
        val message = event.message.trim()
        // 崩溃接管：默认后端收到崩溃触发标记 → 模拟宕机（不写结果，由存活后端判定）
        if (scenario == ScenarioName.CRASH_TAKEOVER && message == TRIGGER_CRASH_MARKER) {
            simulateCrash()
            return
        }
        // 跨服 / 崩溃接管：到达（存活）后端收到切换确认标记 → 判 PASS
        if (scenario != ScenarioName.CROSS_SERVER && scenario != ScenarioName.CRASH_TAKEOVER) {
            return
        }
        if (message != CLUSTER_ARRIVED_MARKER) {
            return
        }
        val playerName = event.player.name
        // 异步聊天事件：切回主线程 / 全局区域写结果 + 关服（Folia 兼容，基类处理）
        runOnMainThread {
            pass(
                "机器人经代理到达本服并确认到达（跨服切换 / 崩溃接管 fallback 落存活后端），链路通",
                // backendName 即本到达服的声明名（编排下发 MC_TESTKIT_E2E_BACKEND_NAME，每后端身份注入）：消费方据此判断「切到了哪台」
                mapOf(
                    "player" to playerName,
                    "arrivedServer" to Bukkit.getServer().name,
                    "backendName" to harnessBackendName(),
                ),
            )
        }
    }

    /** 发送就绪信号（幂等）：`E2E_READY:<scenario>`，通知机器人可开始驱动。 */
    private fun sendReadySignal(player: Player) {
        if (!readySignalSent.compareAndSet(false, true)) {
            return
        }
        sendControlMessage(player, McTestkitProtocol.readyMessage(scenario.id))
    }

    /** 挂场景整体超时：到时仍未判定则判 FAIL。 */
    private fun armScenarioTimeout() {
        runLater(harnessConfig.scenarioTimeoutSeconds * TICKS_PER_SECOND) {
            if (!isFinished()) {
                fail("场景执行超时: ${scenario.id}")
            }
        }
    }

    /**
     * 模拟本后端崩溃宕机（崩溃接管场景，崩溃接管）：用 `Runtime.halt` 立即结束 JVM——不跑关服钩子、
     * 最贴近真实崩溃，监听端口随之立即关闭，代理在 bot 重连时对本后端连接被拒 → fallback 到存活后端。
     * **刻意不写结果文件**（崩溃的后端无从判定，由存活后端判 PASS）。
     */
    private fun simulateCrash() {
        logger.warning("[E2E][CRASH-TAKEOVER] 收到崩溃触发标记，模拟本后端宕机（halt $CRASH_EXIT_CODE），端口随之关闭")
        Runtime.getRuntime().halt(CRASH_EXIT_CODE)
    }

    /** 本后端声明名（基类场景环境读取；集群 / 压测下各服不同）。 */
    private fun harnessBackendName(): String = McTestkitEnv.envOrNull(McTestkitEnv.BACKEND_NAME) ?: ""

    private companion object {
        /** 每秒 tick 数（Bukkit 调度器换算）。 */
        const val TICKS_PER_SECOND = 20L

        /** onEnable 后到 bootstrap 的延迟，给被测插件留启动时间。 */
        const val BOOTSTRAP_DELAY_TICKS = 40L

        /** 单场景多 bot settle 窗口（秒，单场景多 bot）：首个 bot 加入后等这么久收集其余 bot 再聚合判定。 */
        const val MULTI_BOT_SETTLE_SECONDS = 15L

        /** 示例场景无条件 PASS 的延时（仅演示，真实场景应删除）。 */
        const val EXAMPLE_PASS_DELAY_TICKS = 40L

        /** 示例权限节点（消费方按被测插件真实节点替换）。 */
        const val EXAMPLE_PERMISSION = "mctestkit.e2e.example"

        /** 跨服 / 崩溃接管：机器人到达目标 / 存活后端后发的「到达确认」标记（聊天）；桩收到即判 PASS（template 约定）。 */
        const val CLUSTER_ARRIVED_MARKER = "E2E_CLUSTER_ARRIVED"

        /** 崩溃接管：机器人发给默认后端的「立即崩溃」触发标记（聊天）；桩收到即 halt 模拟宕机（template 约定，非冻结契约）。 */
        const val TRIGGER_CRASH_MARKER = "E2E_TRIGGER_CRASH"

        /** 模拟崩溃的 JVM 退出码（`Runtime.halt`）。 */
        const val CRASH_EXIT_CODE = 70
    }
}
