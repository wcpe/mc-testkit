package top.wcpe.mc.testkit.task

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
