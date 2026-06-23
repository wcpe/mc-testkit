package top.wcpe.mc.testkit.task

import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.dsl.BotSpec

/**
 * 一个 bot 进程的派生身份（FR-16）：场景的 [BotSpec] 列表展开后、每个待起进程一项。
 *
 * @property action 该进程的 `BOT_ACTION`（机器人内核据此分发场景驱动）。
 * @property username 该进程的唯一用户名（多进程时强制下发，覆盖消费方单值 override）。
 * @property key 该进程的日志 / pid 唯一 key（文件名安全：`bot-<key>.log` / `bot-<key>.pid`）。
 * @property botIndex 同质复制序号（`count>1` 时 1..N，写入 `BOT_INDEX`）；单进程为 null（不下发，向后兼容）。
 * @property env 该 bot 的业务特定 env（`bot { env(...) }` 声明，原样透传）。
 */
data class BotProcessPlan(
    val action: String,
    val username: String,
    val key: String,
    val botIndex: Int?,
    val env: Map<String, String>,
)

/**
 * 把场景声明的 bot 列表展开为「每进程一项」的计划（FR-16，纯函数，可穷举单测）。
 *
 * 只依赖入参（场景名 + [BotSpec] 列表），不读环境 / 文件 / 全局状态。单 bot（匿名、`count=1`）展开后与
 * 历史行为一致（key = action、无 `BOT_INDEX`），保证向后兼容；`count>1` 复制 N 份，多个 `bot("role")`
 * 各成一支。唯一性（多 bot 须各有唯一 role）由配置期校验（[top.wcpe.mc.testkit.topology.TopologyResolver]）
 * 保证，此处假定输入已合法。
 */
object BotProcessPlanner {

    /** 展开场景 [bots] 为待起进程计划列表（空列表表示该场景无机器人）。 */
    fun expand(scenarioName: String, bots: List<BotSpec>): List<BotProcessPlan> =
        bots.flatMap { bot -> expandOne(scenarioName, bot) }

    /**
     * 把展开后的 [plans] 折成每进程的「追加 env」（FR-16 契约，纯函数，可穷举单测）：
     * - 进程数 **>1** 时强制下发**唯一** `BOT_USERNAME`（盖过消费方单值 override 以保证唯一）；单进程不强制
     *   （保留消费方 override，向后兼容）。
     * - 同质复制（`botIndex != null`）下发 `BOT_INDEX`。
     * - 末位合入 [sharedExtraEnv]（如集群的 `CLUSTER_BACKENDS`，使每个 bot 都能经代理 `/server` 切换）。
     *
     * @return 与 [plans] 一一对应、等长的 env 列表（合并顺序：业务 env → 强制唯一名 → 序号 → 共享 env）。
     */
    fun extraEnvironments(
        plans: List<BotProcessPlan>,
        sharedExtraEnv: Map<String, String> = emptyMap(),
    ): List<Map<String, String>> {
        val forceUniqueUsername = plans.size > 1
        return plans.map { plan ->
            val env = LinkedHashMap<String, String>()
            env.putAll(plan.env)
            // 多进程时强制唯一用户名（盖过消费方单值 BOT_USERNAME override）；同质复制下发序号
            if (forceUniqueUsername) env[McTestkitEnv.BOT_USERNAME] = plan.username
            plan.botIndex?.let { env[McTestkitEnv.BOT_INDEX] = it.toString() }
            env.putAll(sharedExtraEnv)
            env
        }
    }

    /**
     * 校验展开后每进程的 **key**（日志/pid 文件名）与 **username**（Minecraft 离线名）全局唯一（FR-16）。
     *
     * 配置期校验只查 `role` 唯一不够：`role` 不同的两个 bot 展开后仍可能撞 key——如 `bot("w"){count=2}`
     * 得 `w-1`/`w-2`，`bot("w-1"){}` 得 `w-1`，二者撞车会让 `bot-w-1.pid` 互相覆盖、收尾漏杀一个进程
     * 而残留占端口（testing-and-quality §2 收尾红线）；username 撞车则两 bot 同名无法同时登录。
     * 以**展开结果**（[expand] 唯一真源）查重，避免在校验侧重复 key 派生规则而漂移。
     *
     * @return 首个冲突的中文描述（key 或 username 重复）；无冲突返回 null。
     */
    fun firstConflict(scenarioName: String, bots: List<BotSpec>): String? {
        val plans = expand(scenarioName, bots)
        firstDuplicate(plans.map { it.key })?.let { return "bot 日志/pid 标识「$it」重复" }
        firstDuplicate(plans.map { it.username })?.let { return "bot 用户名「$it」重复" }
        return null
    }

    /** 返回首个重复出现的元素（保持发现顺序），无重复返回 null。 */
    private fun firstDuplicate(values: List<String>): String? {
        val seen = HashSet<String>()
        values.forEach { if (!seen.add(it)) return it }
        return null
    }

    /** 展开单个 [BotSpec]：`count=1` 一项，`count>1` 复制成 N 项（各唯一 username / key / index）。 */
    private fun expandOne(scenarioName: String, bot: BotSpec): List<BotProcessPlan> {
        // action 默认：显式 action > role > 场景名（保持单 bot 旧默认 `action ?: scenarioName`）
        val action = bot.action ?: bot.role ?: scenarioName
        // 日志/pid key 基名：role > action > 场景名（单匿名 bot 即 action，文件名沿旧）
        val keyBase = bot.role ?: bot.action ?: scenarioName
        // username 基名：显式 username > role > action > 场景名
        val userBase = bot.username ?: bot.role ?: bot.action ?: scenarioName

        if (bot.count <= 1) {
            return listOf(BotProcessPlan(action = action, username = userBase, key = keyBase, botIndex = null, env = bot.env))
        }
        return (1..bot.count).map { i ->
            BotProcessPlan(
                action = action,
                username = "$userBase$i",
                key = "$keyBase-$i",
                botIndex = i,
                env = bot.env,
            )
        }
    }
}
