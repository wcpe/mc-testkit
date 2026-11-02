'use strict'

const { waitForMessage } = require('@wcpe/mc-testkit-bot/lib/messages')
const { execCommand } = require('@wcpe/mc-testkit-bot/lib/exec')

// 命令执行 API 示例驱动（照抄物，刻意最薄，FR-31）。
//
// 示范「发一条服务端命令 → 拿到结构化结果」的一行调用：不必自己拼 `E2E_CMD:` 前缀、
// 不必生成请求 id、不必解析回执，`await` 后直接看 `result.ok`。
//
// 命令走**插件消息 console 通道**（docs/API.md §3.4.1）：服务端以 console 身份执行，
// 权限是服务端的，因此**不要求 bot 本身有 OP**。
// - 另一种路子是 `bot.chat('/time set day')`（玩家身份）：那条**必须** bot 有 OP
//   （场景里声明 `bot { op = true }`，见 ADR-0027 第一部分），否则服务端只回一句「你没有权限」。
// - 这条通道要求桩升级过 `harness-core`（有 `mc-testkit:cmd` 那条通道）。旧桩收不到请求，
//   机器人侧只会等到超时，`result.output` 会写明「可能是桩未装载该通道」。
//
// 照抄时把它换成真实业务命令，并按需用 `execCommands(bot, [...])` 顺序执行多条。
//
// **注意**：模板桩（`harness/`）没有对应的内置场景，这个驱动要能用还得按脚手架惯例在
// 另外两处登记同一个 id（`exec-command-demo`）：桩的 `ScenarioName.kt` 加枚举项 +
// `McTestkitE2eHarnessPlugin.dispatchScenario` 加分支（发 `E2E_READY` + 判定写结果文件），
// 编排的 `mcTestkit { }` 里加 `scenario("exec-command-demo") { ... }`。三处都用同一个 id，
// 少登记一处时机器人等不到就绪信号、桩也不会判 PASS。
module.exports = async function runExecCommandDemo(context) {
  const { bot, config, log } = context

  // 等桩就绪信号（对齐冻结控制协议 E2E_READY，docs/API.md §3.4）
  await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )

  const result = await execCommand(bot, 'time set day')
  if (!result.ok) {
    // 失败不抛：脚本里直接判 ok 即可。真实场景应在这里打日志并由桩侧判 FAIL。
    log(`命令执行失败: ${result.output}`)
    return
  }
  // 注意：ok 只表示「命令被服务端接受并执行」，output 是结果说明（不是命令的业务输出）。
  // 想看实际输出仍要去服务端控制台日志（原因见 docs/API.md §3.4.1 的「<output> 的限制」）。
  log(`命令执行成功: ${result.output}`)

  // 示例接续：顺序执行多条时用 execCommands，任一条失败即停并返回那一条。
  // 真实场景这里换成设置世界状态 → 触发被测逻辑 → 交由桩侧断言。
}
