package com.example.e2e

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * mc-testkit E2E 桩插件骨架（照抄物，框架无关）。
 *
 * 职责（通用、不含业务）：
 * 1. onEnable 读取 [HarnessConfig]，按场景在合适时机驱动；
 * 2. 入服玩家装备：清背包、给一个示例授权位（演示 PermissionAttachment 模式）；
 * 3. 通过聊天通道向机器人发送控制消息（对齐 mc-testkit 冻结控制协议）；
 * 4. 把判定结论写进结果文件（[ScenarioResultWriter]），再延迟关服回收前台 runServer。
 *
 * 扩展自己的场景：在 [ScenarioName] 加 id → 在 [dispatchScenario] 加分支 → 写驱动 / 判定逻辑。
 */
class McTestkitE2eHarnessPlugin : JavaPlugin(), Listener {

    private lateinit var harnessConfig: HarnessConfig
    private lateinit var resultWriter: ScenarioResultWriter

    /** 场景是否已开始驱动（首个玩家触发，幂等门）。 */
    private val started = AtomicBoolean(false)

    /** 场景是否已判定完成（PASS/FAIL 幂等门，防止重复写文件 / 重复关服）。 */
    private val completed = AtomicBoolean(false)

    /** 是否已发过就绪信号（幂等门）。 */
    private val readySignalSent = AtomicBoolean(false)

    /** 为每个被装备玩家挂的授权附件，退出时移除，避免泄漏。 */
    private val permissionAttachments = ConcurrentHashMap<UUID, PermissionAttachment>()

    // ── 持续压测场景（FR-11）状态 ──
    /** 各 bot 玩家上报的压测摘要（玩家名 → E2E_STRESS_RESULT 载荷）。 */
    private val stressResults = ConcurrentHashMap<String, String>()

    /** 压测是否已聚合收尾（幂等门）。 */
    private val stressFinalized = AtomicBoolean(false)

    /** 压测计时是否已开始（首个 bot 加入 CAS，避免多 bot 重复挂计时）。 */
    private val stressClockStarted = AtomicBoolean(false)

    // ── 单场景多 bot 场景（FR-16）状态 ──
    /** 已入服的各 bot 玩家名（多 bot 各唯一 username，FR-16）。 */
    private val multiBotJoined: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 多 bot settle 计时是否已开始（首个 bot 加入 CAS）。 */
    private val multiBotClockStarted = AtomicBoolean(false)

    /** 多 bot 是否已聚合收尾（幂等门）。 */
    private val multiBotFinalized = AtomicBoolean(false)

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()
        harnessConfig = HarnessConfig.from(config)
        resultWriter = ScenarioResultWriter(harnessConfig.resultFile)
        server.pluginManager.registerEvents(this, this)

        // 延迟少量 tick 再 bootstrap，给被测插件留出 onEnable 完成的时间
        runLater(BOOTSTRAP_DELAY_TICKS) { bootstrapScenario() }
    }

    /** 场景启动：无机器人的 smoke 直接判定；需要玩家的场景挂等待超时。 */
    private fun bootstrapScenario() {
        when (harnessConfig.scenario) {
            ScenarioName.SMOKE -> runSmokeScenario()
            ScenarioName.CONTINUOUS_STRESS -> {
                // 压测计时在「首个 bot 加入」时启动（见 onPlayerJoin）；此处只挂「迟迟无 bot」失败兜底
                logger.info("[E2E] 持续压测场景，等待首个 bot 加入后开始 ${harnessConfig.stressDurationSeconds}s 计时")
                runLater(harnessConfig.waitForPlayerSeconds * TICKS_PER_SECOND) {
                    if (!stressClockStarted.get() && !completed.get()) {
                        failScenario("持续压测等待首个 bot 加入超时，场景=${harnessConfig.scenario.id}")
                    }
                }
            }
            ScenarioName.MULTI_BOT -> {
                // settle 计时在「首个 bot 加入」时启动（见 onPlayerJoin）；此处只挂「迟迟无 bot」失败兜底
                logger.info("[E2E] 单场景多 bot，等待各 bot 入服后 settle 聚合")
                runLater(harnessConfig.waitForPlayerSeconds * TICKS_PER_SECOND) {
                    if (!multiBotClockStarted.get() && !completed.get()) {
                        failScenario("单场景多 bot 等待首个 bot 加入超时，场景=${harnessConfig.scenario.id}")
                    }
                }
            }
            ScenarioName.SERVE -> {
                // 持久手测（serve）空闲（FR-17，ADR-0011）：刻意什么都不做——不驱动、不挂超时、不写结果、不关服。
                // 服务端留给真人客户端连入手测，直到 mc-testkit serve 任务被手动停（编排侧收尾）。
                logger.info("[E2E] serve 持久手测模式：桩空闲，不驱动场景、不关服，等待真人客户端连入。")
            }
            else -> {
                logger.info("[E2E] 场景 ${harnessConfig.scenario.id} 等待首个玩家加入，超时 ${harnessConfig.waitForPlayerSeconds}s")
                runLater(harnessConfig.waitForPlayerSeconds * TICKS_PER_SECOND) {
                    if (!started.get() && !completed.get()) {
                        failScenario("等待玩家加入超时，场景=${harnessConfig.scenario.id}")
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
        passScenario(
            message = "桩插件已就绪，真实服务端启动烟雾场景通过",
            // backendName 来自编排下发的 MC_TESTKIT_E2E_BACKEND_NAME（FR-12）：演示消费方如何取本后端声明名做 per-backend 身份
            details = mapOf("server" to Bukkit.getServer().name, "backendName" to harnessConfig.backendName),
        )
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // serve 持久手测（FR-17）：桩空闲，真人玩家入服**不触发任何驱动**（不清背包、不发控制消息、不关服）
        if (harnessConfig.scenario == ScenarioName.SERVE) {
            return
        }
        // 持续压测：每个 bot 都装备 + 发就绪信号（不走单次 started 门）；首个 bot 加入起计时
        if (harnessConfig.scenario == ScenarioName.CONTINUOUS_STRESS) {
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
        if (harnessConfig.scenario == ScenarioName.MULTI_BOT) {
            if (multiBotFinalized.get()) {
                return
            }
            prepareTestPlayer(event.player)
            multiBotJoined.add(event.player.name)
            sendControlMessage(event.player, "$CONTROL_READY:${harnessConfig.scenario.id}")
            if (multiBotClockStarted.compareAndSet(false, true)) {
                logger.info("[E2E] 首个多 bot 已加入，${MULTI_BOT_SETTLE_SECONDS}s settle 窗口后聚合判定")
                runLater(MULTI_BOT_SETTLE_SECONDS * TICKS_PER_SECOND) { finalizeMultiBot() }
            }
            return
        }
        if (completed.get()) {
            return
        }
        // 单次场景门：只让首个入服玩家触发驱动
        if (!started.compareAndSet(false, true)) {
            return
        }
        logger.info("[E2E] 使用玩家 ${event.player.name} 执行场景 ${harnessConfig.scenario.id}")
        dispatchScenario(event.player)
    }

    /** 场景派发表：通用骨架演示 example-bot 与 cross-server（集群）；消费方在此加自己的场景分支。 */
    private fun dispatchScenario(player: Player) {
        when (harnessConfig.scenario) {
            ScenarioName.EXAMPLE_BOT -> prepareExampleBotScenario(player)
            ScenarioName.CROSS_SERVER -> prepareCrossServerScenario(player)
            ScenarioName.CRASH_TAKEOVER -> prepareCrashTakeoverScenario(player)
            // 持续压测 / 单场景多 bot 在 onPlayerJoin 前置分支处理（不走单次 started 门），不到此
            ScenarioName.CONTINUOUS_STRESS -> Unit
            ScenarioName.MULTI_BOT -> Unit
            // serve 持久手测在 onPlayerJoin 前置分支已 return（桩空闲），不到此
            ScenarioName.SERVE -> Unit
            // smoke 不经玩家驱动；其余场景由消费方补充分支
            ScenarioName.SMOKE -> Unit
        }
    }

    /**
     * 跨服集群示例场景（FR-10，照抄物，刻意最薄）。
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
     * 崩溃接管 fallback 示例场景（FR-15，照抄物，刻意最薄）。
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
     * 持续压测示例场景（FR-11，照抄物，刻意最薄）。
     *
     * 桩对称无角色：每个入服 bot 都装备 + 发就绪信号，bot 据此持续施压并到时上报 `E2E_STRESS_RESULT`；
     * 桩收集各 bot 摘要、到 duration 末由 [finalizeContinuousStress] 聚合写 PASS。**不做业务断言**——
     * 真实「不超卖」等不变量请在 [finalizeContinuousStress] 里查共享 DB / 缓存改判，并删掉这段无条件 PASS。
     */
    private fun prepareContinuousStressBot(player: Player) {
        prepareTestPlayer(player)
        // 每个 bot 都发 READY（N×M 个 bot 各需就绪信号，故不用单次 readySignalSent 门）
        sendControlMessage(player, "$CONTROL_READY:${harnessConfig.scenario.id}")
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
        passScenario("持续压测场景收尾完成（示例：真实不变量请查共享 DB 断言）", details)
    }

    /**
     * 单场景多 bot settle 收尾（FR-16，首个 bot 加入后 settle 窗口末，幂等）：聚合各 bot username + 写 PASS + 关服。
     *
     * 薄示例只校验「多个各唯一 username 的 bot 都入服」（演示 FR-16 多进程身份注入 + 全回收的真机链路通），
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
        passScenario("单场景多 bot 全部入服（示例：唯一 username 由 CI 断言）", details)
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
        // 业务事件 / 控制消息回报触发 passScenario，并删掉这段无条件 PASS。
        runLater(EXAMPLE_PASS_DELAY_TICKS) {
            if (!completed.get()) {
                passScenario(
                    message = "机器人驱动示例场景通过（示例：请替换为真实判定）",
                    details = mapOf("player" to player.name),
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
     * 跨服场景（FR-10）：收到机器人切到本服后发的「切换确认标记」（聊天）即判 PASS。
     *
     * 注：此处用 `AsyncPlayerChatEvent` 接收 bot 控制消息，在较新 Paper（1.19+）已弃用——示例以默认
     * `paper-api:1.20.1` 为准、可直接照抄；消费方若目标后端更高版本，可改用 `AsyncChatEvent`（Adventure
     * 组件，取文本经 `PlainTextComponentSerializer`）替代，控制协议字面量不变。
     */
    @EventHandler
    fun onPlayerChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
        // 持续压测：收集各 bot 上报的 E2E_STRESS_RESULT（不触发 pass/fail，N×M bot 不能各自关服）
        if (harnessConfig.scenario == ScenarioName.CONTINUOUS_STRESS) {
            if (event.message.startsWith(STRESS_RESULT_PREFIX)) {
                stressResults[event.player.name] = event.message.removePrefix(STRESS_RESULT_PREFIX)
                logger.info("[E2E][STRESS] 收到 ${event.player.name} 压测汇总: ${event.message}")
            }
            return
        }
        if (completed.get()) {
            return
        }
        val message = event.message.trim()
        // 崩溃接管：默认后端收到崩溃触发标记 → 模拟宕机（不写结果，由存活后端判定）
        if (harnessConfig.scenario == ScenarioName.CRASH_TAKEOVER && message == TRIGGER_CRASH_MARKER) {
            simulateCrash()
            return
        }
        // 跨服 / 崩溃接管：到达（存活）后端收到切换确认标记 → 判 PASS
        if (harnessConfig.scenario != ScenarioName.CROSS_SERVER && harnessConfig.scenario != ScenarioName.CRASH_TAKEOVER) {
            return
        }
        if (message != CLUSTER_ARRIVED_MARKER) {
            return
        }
        val playerName = event.player.name
        // 异步聊天事件：切回主线程 / 全局区域写结果 + 关服（Folia 兼容）
        runSync {
            passScenario(
                message = "机器人经代理到达本服并确认到达（跨服切换 / 崩溃接管 fallback 落存活后端），链路通",
                // backendName 即本到达服的声明名（编排下发 MC_TESTKIT_E2E_BACKEND_NAME，FR-12）：消费方据此判断「切到了哪台」
                details =
                    mapOf(
                        "player" to playerName,
                        "arrivedServer" to Bukkit.getServer().name,
                        "backendName" to harnessConfig.backendName,
                    ),
            )
        }
    }

    /** 发送就绪信号（幂等）：`E2E_READY:<scenario>`，通知机器人可开始驱动。 */
    private fun sendReadySignal(player: Player) {
        if (!readySignalSent.compareAndSet(false, true)) {
            return
        }
        sendControlMessage(player, "$CONTROL_READY:${harnessConfig.scenario.id}")
    }

    /**
     * 经聊天通道向机器人发送控制消息（对齐 mc-testkit 冻结控制协议，docs/API.md §3.4）。
     * 协议消息名：READY / STRESS_RESULT / DISCONNECT_NOW / UI_TOKEN，载荷走 `:` 后缀。
     */
    private fun sendControlMessage(player: Player, message: String) {
        logger.info("[E2E] 发送控制消息 -> ${player.name}: $message")
        player.sendMessage(message)
    }

    /** 挂场景整体超时：到时仍未判定则判 FAIL。 */
    private fun armScenarioTimeout() {
        runLater(harnessConfig.scenarioTimeoutSeconds * TICKS_PER_SECOND) {
            if (!completed.get()) {
                failScenario("场景执行超时: ${harnessConfig.scenario.id}")
            }
        }
    }

    /** 判 PASS：写结果文件后延迟关服（幂等）。 */
    private fun passScenario(message: String, details: Map<String, String> = emptyMap()) {
        if (!completed.compareAndSet(false, true)) {
            return
        }
        logger.info("[E2E][PASS] $message")
        resultWriter.write(ScenarioResultWriter.STATUS_PASS, message, details)
        scheduleShutdown()
    }

    /** 判 FAIL：写结果文件后延迟关服（幂等）。 */
    private fun failScenario(message: String) {
        if (!completed.compareAndSet(false, true)) {
            return
        }
        logger.severe("[E2E][FAIL] $message")
        resultWriter.write(ScenarioResultWriter.STATUS_FAIL, message)
        scheduleShutdown()
    }

    /**
     * 模拟本后端崩溃宕机（崩溃接管场景，FR-15）：用 `Runtime.halt` 立即结束 JVM——不跑关服钩子、
     * 最贴近真实崩溃，监听端口随之立即关闭，代理在 bot 重连时对本后端连接被拒 → fallback 到存活后端。
     * **刻意不写结果文件**（崩溃的后端无从判定，由存活后端 [passScenario] 判 PASS）。
     */
    private fun simulateCrash() {
        logger.warning("[E2E][CRASH-TAKEOVER] 收到崩溃触发标记，模拟本后端宕机（halt $CRASH_EXIT_CODE），端口随之关闭")
        Runtime.getRuntime().halt(CRASH_EXIT_CODE)
    }

    /** 延迟关服，回收前台 runServer 线程；延迟由配置控制以留结果落盘窗口。 */
    private fun scheduleShutdown() {
        runLater(harnessConfig.shutdownDelayTicks) { Bukkit.shutdown() }
    }

    // ── Folia 兼容调度 ──
    // Folia（区域化线程）不支持 Bukkit 全局调度器：`server.scheduler.runTask*` 会抛
    // UnsupportedOperationException，须改用 GlobalRegionScheduler。为让**同一份桩**既能在 Paper 又能在
    // Folia 跑，且编译期不依赖 Folia 专有 API（对各版本 paper-api 都能编译），这里用反射探测并调用 Folia 的
    // GlobalRegionScheduler；非 Folia（Paper）保持原 Bukkit 调度器、行为不变。

    /** Folia 全局区域调度器（仅 Folia 运行时非 null；用反射取，避免编译期依赖 Folia 专有类）。 */
    private val foliaGlobalScheduler: Any? by lazy {
        try {
            // `RegionizedServer` 是 Folia 专有类：存在即判定为 Folia 运行时（Paper 无此类）
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            Bukkit.getServer().javaClass.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer())
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 延迟在主线程 / 全局区域执行（Folia 走 GlobalRegionScheduler.runDelayed，否则 Bukkit runTaskLater）。 */
    private fun runLater(delayTicks: Long, task: () -> Unit) {
        val folia = foliaGlobalScheduler
        if (folia != null) {
            // GlobalRegionScheduler.runDelayed(Plugin, Consumer<ScheduledTask>, long)；delay 须 >=1
            folia.javaClass
                .getMethod("runDelayed", Plugin::class.java, Consumer::class.java, Long::class.javaPrimitiveType)
                .invoke(folia, this, Consumer<Any?> { task() }, maxOf(1L, delayTicks))
        } else {
            server.scheduler.runTaskLater(this, Runnable { task() }, delayTicks)
        }
    }

    /** 立即在主线程 / 全局区域执行（Folia 走 GlobalRegionScheduler.run，否则 Bukkit runTask）。 */
    private fun runSync(task: () -> Unit) {
        val folia = foliaGlobalScheduler
        if (folia != null) {
            // GlobalRegionScheduler.run(Plugin, Consumer<ScheduledTask>)
            folia.javaClass
                .getMethod("run", Plugin::class.java, Consumer::class.java)
                .invoke(folia, this, Consumer<Any?> { task() })
        } else {
            server.scheduler.runTask(this, Runnable { task() })
        }
    }

    private companion object {
        /** 每秒 tick 数（Bukkit 调度器换算）。 */
        const val TICKS_PER_SECOND = 20L

        /** onEnable 后到 bootstrap 的延迟，给被测插件留启动时间。 */
        const val BOOTSTRAP_DELAY_TICKS = 40L

        /** 单场景多 bot settle 窗口（秒，FR-16）：首个 bot 加入后等这么久收集其余 bot 再聚合判定。 */
        const val MULTI_BOT_SETTLE_SECONDS = 15L

        /** 示例场景无条件 PASS 的延时（仅演示，真实场景应删除）。 */
        const val EXAMPLE_PASS_DELAY_TICKS = 40L

        /** 示例权限节点（消费方按被测插件真实节点替换）。 */
        const val EXAMPLE_PERMISSION = "mctestkit.e2e.example"

        // ── mc-testkit 冻结控制协议消息名（docs/API.md §3.4）──
        /** 桩通知机器人「已就绪」：`E2E_READY:<scenario>`。 */
        const val CONTROL_READY = "E2E_READY"

        /** 跨服 / 崩溃接管：机器人到达目标 / 存活后端后发的「到达确认」标记（聊天）；桩收到即判 PASS（template 约定）。 */
        const val CLUSTER_ARRIVED_MARKER = "E2E_CLUSTER_ARRIVED"

        /** 崩溃接管（FR-15）：机器人发给默认后端的「立即崩溃」触发标记（聊天）；桩收到即 halt 模拟宕机（template 约定，非冻结契约）。 */
        const val TRIGGER_CRASH_MARKER = "E2E_TRIGGER_CRASH"

        /** 模拟崩溃的 JVM 退出码（`Runtime.halt`）。 */
        const val CRASH_EXIT_CODE = 70

        /** 持续压测：机器人到 duration 末上报的累计摘要前缀 `E2E_STRESS_RESULT:`（冻结协议，docs/API.md §3.4）。 */
        const val STRESS_RESULT_PREFIX = "E2E_STRESS_RESULT:"
    }
}
