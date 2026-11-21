'use strict'

const { waitForMessage, sendChatSafely } = require('@wcpe/mc-testkit-bot/lib/messages')
const { execCommand, execCommands } = require('@wcpe/mc-testkit-bot/lib/exec')

// bot 命令通道**实机自检**驱动（FR-29 / FR-30 / FR-31，ADR-0027）。
//
// 与 `execCommandDemo.js` 的区别：那个是「怎么用」的照抄示例，这个是**验证框架自身能力是否真的成立**
// 的测试驱动。它回答三个只有真机能回答的问题：
//
//   ① console 通道到底通不通（机器人发 custom_payload → 桩 dispatchCommand → 回执回到机器人）
//   ② `output` 到底拿不拿得到真实业务输出（这是本驱动存在的首要理由：不能靠推测）
//   ③ `bot { op = true }` 写进 ops.json 的条目是否真的让玩家以 OP 身份执行了命令
//
// 两段验证都过才回 PASS 标记；任何一段失败都回 FAIL 并带具体原因，不静默。
module.exports = async function runBotCommand(context) {
  const { bot, config, log } = context

  await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )

  const failures = []
  const evidence = []

  // ── 第一段：console 通道 + 输出捕获 ──
  //
  // `list` 是最稳的选择：它一定存在、一定回一段可读文本、且不需要任何世界状态。
  // 若 output 里真拿到了玩家名或「players online」这类内容，说明 Paper 的
  // createCommandSender 捕获链路通了。
  const listResult = await execCommand(bot, 'list')
  if (!listResult.ok) {
    failures.push(`console 通道执行 list 失败：${listResult.output}`)
  } else {
    evidence.push(`list -> ${listResult.output}`)
    // 断言拿到了**真实输出**而不是「命令已被服务端接受并执行」那句兜底文案
    const gotRealOutput =
      /player|players online|There are|在线/i.test(listResult.output) &&
      !listResult.output.includes('命令已被服务端接受并执行')
    if (!gotRealOutput) {
      failures.push(`console 通道拿到了回执但没有真实业务输出：${listResult.output}`)
    }
  }

  // `time set day` 验证命令确实改变了世界状态，而不只是「被接受」
  const timeResult = await execCommand(bot, 'time set day')
  if (!timeResult.ok) {
    failures.push(`console 通道执行 time set day 失败：${timeResult.output}`)
  } else {
    evidence.push(`time set day -> ${timeResult.output}`)
  }

  // 批量执行：验证 execCommands 的顺序语义与聚合返回
  const batch = await execCommands(bot, [
    { command: 'gamerule doDaylightCycle false', label: '关昼夜循环' },
    { command: 'time set day', label: '设为白天' }
  ])
  if (!batch.ok) {
    failures.push(`execCommands 批量执行失败：${batch.output}`)
  } else {
    evidence.push(`批量 ${batch.results.length} 条全部成功`)
  }

  // ── 第二段：玩家 OP（`bot { op = true }` → 框架写 ops.json）──
  //
  // 关键点：这条命令走的是**玩家身份**，需要 OP。桩侧刻意**不给**权限附件
  // （见 McTestkitE2eHarnessPlugin.prepareBotCommandScenario），所以能成功就只可能是因为
  // 框架写的 ops.json 生效了。
  //
  // 判通过必须有正面证据：`gamemode creative` 成功时服务端静默，仅凭「没等到拒绝」分不清
  // 「生效了」与「命令根本没发出去」（断线 / 聊天被拦 / 被限流）。取证方式按版本选（见
  // buildGameModeProbe）——`/data` 是 1.13 才有的命令，而本框架的 bot 支持下限是 1.8。
  const opProbe = await runPlayerCommand(bot, 'gamemode creative', config, buildGameModeProbe(bot, config))
  if (!opProbe.ok) {
    failures.push(`玩家 OP 验证失败（bot 未能以 OP 身份执行命令）：${opProbe.output}`)
  } else {
    evidence.push(`玩家命令生效：${opProbe.output}`)
  }

  // ── 回传结论 ──
  // 结论经聊天帧回传，而原版 chat 单包上限 256 字符：超长的包会在服务端解码期被丢弃并断开玩家，
  // 表现为「桩一直等到超时、日志里只有一条 mineflayer kicked」。故走 sendChatSafely 截断。
  const detail = failures.length === 0 ? evidence.join(' ; ') : failures.join(' ; ')

  if (failures.length === 0) {
    log(`命令通道自检全部通过：${detail}`)
    sendChatSafely(bot, 'E2E_BOT_COMMAND_PASS:', detail)
  } else {
    log(`命令通道自检失败：${detail}`)
    sendChatSafely(bot, 'E2E_BOT_COMMAND_FAIL:', detail)
  }
}

/**
 * 以**玩家身份**执行一条命令，并从聊天回显判断服务端是否拒绝了它。
 *
 * 这条路径不走 console 通道——它验的正是「bot 作为玩家有没有权限」。
 * 服务端的拒绝文案随版本与语言而变，故按几个稳定特征判断而不是精确匹配整句：
 * 英文的 `permission` / `Unknown or incomplete command`，中文的「权限」。
 *
 * ## 为什么「等到超时没被拒绝」不能算通过
 *
 * 曾经这里把「没有等到任何回显」当成「命令被接受」——理由是 `gamemode` 这类命令
 * 成功时静默。但「没有回显」有好几种完全不同的成因，而它们都指向失败：
 *
 * - bot 已被踢 / 连接已断：`bot.chat` 对已结束的连接静默无效，什么回显都不会来；
 * - 聊天被服务端插件拦截（反刷屏、聊天过滤）：命令根本没出去；
 * - 被 spam 限流：同上。
 *
 * 三者都会被判成「OP 生效」，这是**假通过**——而本场景存在的意义正是验证 OP 真的生效，
 * 判错的代价比判失败大得多（假失败有人查，假通过没人查）。
 *
 * 因此改为**必须有正面证据**：执行完立刻以 console 身份读真实状态（`consoleProbe`），
 * 只有读到「命令确实生效」才算通过。超时一律算失败。
 *
 * @param {Object} bot mineflayer bot
 * @param {string} command 不带前导 `/` 的命令
 * @param {Object} config 机器人配置（用其 readyTimeoutMs 作等待上限）
 * @param {Function|null} consoleProbe 无参异步函数：以 console 身份取证，返回
 *   `{ ok: boolean, output: string }`。返回 null 表示无可用取证途径（此时不判通过）
 * @returns {Promise<{ok: boolean, output: string}>}
 */
async function runPlayerCommand(bot, command, config, consoleProbe) {
  // 先挂监听再看历史：命令到达与回显之间可能有竞态，waitForMessage 自带历史回扫
  bot.chat(`/${command}`)
  try {
    const echoed = await waitForMessage(
      bot,
      (text) => {
        // 拒绝信号：拿到任何一句拒绝就立即失败，不必等超时
        if (/permission|权限|Unknown or incomplete command|未知或不完整/i.test(text)) {
          return true
        }
        // 成功信号：gamemode / time 这类命令成功时静默，不靠聊天回显判通过
        return false
      },
      // 等拒绝的时间窗口不必长：拒绝是即时的
      Math.min(3000, config.readyTimeoutMs)
    )
    return { ok: false, output: `服务端拒绝：${echoed}` }
  } catch (error) {
    if (!/等待消息超时/.test(error.message)) {
      return { ok: false, output: `执行 ${command} 时异常：${error.message}` }
    }
    // 没等到拒绝——这既可能是「命令被接受」，也可能是「命令根本没出去」。
    // 只有拿到正面证据才判通过（见本函数 KDoc）。
    if (typeof consoleProbe !== 'function') {
      return {
        ok: false,
        output: `${command} 未收到回显，且没有可用的正面取证途径，不能据此判为通过`
      }
    }
    const probe = await consoleProbe()
    if (!probe.ok) {
      return { ok: false, output: `${command} 未收到回显，且正面取证失败：${probe.output}` }
    }
    return { ok: true, output: `${command} 未收到拒绝，正面取证确认已生效：${probe.output}` }
  }
}

/**
 * 构造「玩家游戏模式」的正面取证函数，按服务端版本选路。
 *
 * ## 为什么要分版本
 *
 * `/data` 是 **1.13** 才加入的命令，而本框架的 bot 支持下限是 **1.8**。若只用
 * `data get entity <玩家> playerGameType`，1.8–1.12.2 上的取证必然失败，于是一个本来
 * 能通过的场景会被判 FAIL——那只是把「假通过」换成了「假失败」，并不算修好。
 *
 * ## 两条路都要求正面证据
 *
 * - **1.13+**：`data get entity` 直接读回数值，`1` 即 creative。
 * - **1.8–1.12**：读 `/gamemode` 的**成功回显**。这些版本成功时会回一句
 *   `Set own game mode to Creative Mode`（1.13+ 反而静默）。这句话本身就是「命令确实
 *   被执行了」的正面证据，比「没等到拒绝」强得多。
 *
 * 旧版本路径依赖 `waitForMessage` 的历史回扫（见 bot-core `messages.js`）：回显在
 * `runPlayerCommand` 等待拒绝的窗口里就已到达并存进历史，此处回扫即可命中。
 *
 * @param {Object} bot mineflayer bot
 * @param {Object} config 机器人配置（用其 readyTimeoutMs 作等待上限）
 * @param {{ waitForMessage?: Function, execCommand?: Function }} [deps] 测试注入点，生产不传
 * @returns {Function} 无参异步函数，返回 `{ ok: boolean, output: string }`
 */
function buildGameModeProbe(bot, config, deps) {
  const { execCommand } = resolveProbeDeps(deps)
  const version = resolveServerVersion(bot, config)
  if (!supportsDataCommand(version)) {
    return () => probeLegacyGameMode(bot, config, version, deps)
  }
  return async () => {
    const probe = await execCommand(bot, 'data get entity ' + bot.username + ' playerGameType')
    if (probe.ok) {
      // 期望读到 1（creative）。读不到数字说明取证本身没成立，不能当通过。
      const matched = /(-?\d+)/.exec(probe.output)
      if (!matched) {
        return { ok: false, output: `取证输出里没有游戏模式数值：${probe.output}` }
      }
      const mode = Number(matched[1])
      if (mode !== 1) {
        return { ok: false, output: `玩家游戏模式仍为 ${mode}（期望 1=creative），命令未生效` }
      }
      return { ok: true, output: `console 取证确认玩家游戏模式为 creative（${probe.output}）` }
    }
    // 版本判定为 1.13+ 但 `/data` 没成——最可能是版本串判断失误（如代理下拿到的
    // 是对外版本而实际后端更老）。此时不算通过、也不直接判死：回退到回显取证，
    // 若连回显也没有，两条路的失败原因一并写进结论，免得只报一条误导排查。
    const fallback = await probeLegacyGameMode(bot, config, version, deps)
    if (fallback.ok) {
      return fallback
    }
    return { ok: false, output: `data 取证失败（${probe.output}）；${fallback.output}` }
  }
}

/**
 * 取取证所用的依赖实现，允许测试注入替身。
 *
 * 生产路径永远走默认的模块级 `waitForMessage` / `execCommand`；注入点只为单测存在，
 * 让「1.8 上不发 `/data`」这类约束能在不起真实连接的前提下被断言。
 *
 * @param {{ waitForMessage?: Function, execCommand?: Function }|undefined} deps 可选取代项
 * @returns {{ waitForMessage: Function, execCommand: Function }}
 */
function resolveProbeDeps(deps) {
  return {
    waitForMessage: deps?.waitForMessage || waitForMessage,
    execCommand: deps?.execCommand || execCommand
  }
}

/**
 * 1.8–1.12 的取证：等 `/gamemode` 的成功回显。
 *
 * 只认「设置成功」这一类文案，不把「没被拒绝」当证据——后者分不清「生效了」与
 * 「命令根本没发出去」（断线 / 聊天被拦截 / 被限流），正是本场景要防的假通过。
 *
 * 匹配中英文两种服务端文案：Mojang 官方服务端回英文，第三方中文本地化回中文。
 *
 * @param {Object} bot mineflayer bot
 * @param {Object} config 机器人配置
 * @param {string|null} version 已解析出的服务端版本，仅用于失败说明
 * @param {{ waitForMessage?: Function }} [deps] 测试注入点，生产不传
 * @returns {Promise<{ ok: boolean, output: string }>}
 */
async function probeLegacyGameMode(bot, config, version, deps) {
  const { waitForMessage } = resolveProbeDeps(deps)
  try {
    const echoed = await waitForMessage(
      bot,
      (text) => /Set own game mode to |游戏模式.*已(被)?设为|已将.*游戏模式/i.test(text),
      Math.min(3000, config.readyTimeoutMs)
    )
    return { ok: true, output: `成功回显确认 gamemode 已执行（${echoed}）` }
  } catch (error) {
    return {
      ok: false,
      output: `${version || '该版本'} 上未等到 /gamemode 的成功回显，无法确认命令生效：${error.message}`
    }
  }
}

/**
 * 取本机所连服务端的真实 MC 版本，供取证选路。
 *
 * 两个来源，按可靠性排序：
 *
 * 1. `bot.version`——mineflayer 在**实际连接协商完成后**按服务端 ping 结果回填
 *    （见 mineflayer `lib/loader.js`：`bot.version = versionData.minecraftVersion`）。
 *    这是「真正连上的那个版本」，直连与经代理都准。
 * 2. `config.version`——编排下发的期望版本，**只在经代理时**才有值（直连传 null，
 *    让 mineflayer 自动协商）。作为兜底，也让单测能不起连接就指定版本。
 *
 * 返回 null 表示两个来源都没给出可用版本。
 *
 * @param {Object} bot mineflayer bot
 * @param {Object} config 机器人配置
 * @returns {string|null} 形如 `1.20.1`
 */
function resolveServerVersion(bot, config) {
  const fromBot = typeof bot?.version === 'string' && bot.version.length > 0 ? bot.version : null
  if (fromBot != null) {
    return fromBot
  }
  const fromConfig = typeof config?.version === 'string' && config.version.length > 0 ? config.version : null
  return fromConfig
}

/**
 * 服务端版本是否支持 `/data` 命令（1.13 起）。
 *
 * 版本串取自编排下发的 `MC_TESTKIT_E2E_BOT_VERSION`（经代理时被固定为后端版本）。
 * 解析不出主次版本号时**按支持处理**：现代版本占绝对多数，且这条路失败时会如实报错、
 * 不会误判通过——宁可让新版本走正常路径，也不因版本串缺失把它误降到旧路径。
 *
 * @param {string|null|undefined} version 形如 `1.20.1` / `1.12.2`
 * @returns {boolean}
 */
function supportsDataCommand(version) {
  const matched = /^(\d+)\.(\d+)/.exec(String(version || ''))
  if (!matched) {
    return true
  }
  const major = Number(matched[1])
  const minor = Number(matched[2])
  if (major !== 1) {
    return major > 1
  }
  return minor >= 13
}

// 供单元测试使用：这些是内部纯函数 / 选路逻辑，导出不会改变运行期行为。
module.exports.buildGameModeProbe = buildGameModeProbe
module.exports.probeLegacyGameMode = probeLegacyGameMode
module.exports.resolveServerVersion = resolveServerVersion
module.exports.supportsDataCommand = supportsDataCommand
