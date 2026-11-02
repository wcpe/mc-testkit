'use strict'

// 机器人命令执行 API（FR-31，ADR-0027 第三部分）。
//
// 把 FR-30 的插件消息 console 命令通道（协议细节见 docs/API.md §3.4.1）包成
// 「发一条命令 → 拿到结构化结果」的一行调用：场景代码不必再手写协议前缀、自己生成 id、
// 处理超时、解析回执。
//
// **两个函数都不 reject**：`await` 之后直接看 `ok`。超时 / 桩未装载通道 / 命令为空 /
// 连接已断，都不是「异常」，而是场景要写进日志、并据此判失败的**正常结果**——做成 reject
// 会逼每个调用方各写一遍 try/catch，还容易漏掉其中一种（漏掉的那条会挂到场景超时才暴露）。
//
// **`output` 是命令的真实业务输出**（Paper 1.16+）：桩侧用 `createCommandSender` 拿到一个能
// 回传输出的发送者，命令写的每一行都会转回来，因此 `output` 就是命令实际打印的内容
// （如 `Set the time to 1000`）。旧服务端（纯 Spigot / Paper < 1.16）上退化为执行结果说明。
//
// 但 `ok: true` 仍**不**表示「世界状态已符合预期」——它只表示命令被执行了。要断言效果，
// 用服务端断言或由桩在命令后自行检查世界状态。

const commands = require('./commands')

// 单条命令的默认等待上限。
//
// 比 FR-30 通信原语的 30s 短：命令本身是毫秒级返回，10s 已足够覆盖主线程排队；
// 卡到 10s 还没回执时继续干等的收益极低（要么桩没装这条通道，要么服务端真卡住了），
// 场景更该尽快拿到失败原因去排查。确实有慢命令时按次覆盖 `options.timeoutMs`。
const DEFAULT_TIMEOUT_MS = 10000

// 请求 id 的序号，模块级单调递增。
//
// 并发安全靠 JS 单线程：`nextRequestId()` 里自增与取值之间没有 await，同一 tick 内并发
// 发起的多条命令必然各拿一个不同的号，不存在两次 await 交错读到同一个值。
// 前缀取当前时间戳的 base36，便于把回执与服务端日志按时间对上；id 里不能有冒号
// （它是 `E2E_CMD:<id>:<command>` 的第一个字段），故两段之间用 `-` 连接。
let requestSeq = 0

// 生成一个进程内唯一、可读、不含冒号的请求 id。
function nextRequestId() {
  requestSeq += 1
  return `${Date.now().toString(36)}-${requestSeq}`
}

// 把值渲染成便于写进失败说明的类型名。
function describeType(value) {
  if (value === null) {
    return 'null'
  }
  if (Array.isArray(value)) {
    return 'array'
  }
  return typeof value
}

// 解析超时参数：缺省与非法值一律回落默认。
//
// 不做「原样透传」是因为 `setTimeout` 会把 `NaN` 与负数当成「立即超时」——那样场景只会
// 看到一条莫名的回执超时，看不出是自己把参数传错了。
function resolveTimeout(value) {
  if (value == null) {
    return DEFAULT_TIMEOUT_MS
  }
  const timeoutMs = Number(value)
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    return DEFAULT_TIMEOUT_MS
  }
  return timeoutMs
}

// 把异常渲染成单行说明（超时之外还有「发包时连接刚断」这类路径）。
function describeError(error) {
  if (error == null) {
    return '未知原因'
  }
  return error.message || String(error)
}

// 连接可用性预检。
//
// 断线判定依据 minecraft-protocol 的 `Client`（已读 `src/client.js`）：`ended` 在构造时
// 为 `true`、`setSocket` 时置 `false`、socket 的 `close` / `end` / `timeout` 时置回 `true`
// 并触发 `end` 事件；socket 关闭后 `write` 会因序列化流不可写而**静默丢弃**（`write` 里
// `if (!this.serializer.writable) return`），于是线上什么都不会发生，调用方只能等到超时。
// 提前拦住能把「等 10s 才说超时」变成「立刻说连接已断」，这才是失败原因本身。
function connectionProblem(bot) {
  const client = bot && bot._client
  if (!client) {
    return 'bot 未暴露 mineflayer 协议客户端（bot._client），无法使用插件消息命令通道'
  }
  // 与通信原语的前置检查同一套能力判断：缺了 `write` / `on` 说明拿到的不是 mineflayer bot，
  // 在这里就拦掉，免得调用方等到一个笼统的「超时」。
  if (typeof client.write !== 'function' || typeof client.on !== 'function') {
    return 'bot._client 缺少 write / on 能力，不是 mineflayer 协议客户端，无法使用插件消息命令通道'
  }
  if (client.ended === true) {
    return 'bot 与服务器尚未连接或连接已结束（bot._client.ended），命令无法送达'
  }
  const socket = client.socket
  if (socket && (socket.destroyed === true || socket.writable === false)) {
    return 'bot 的连接套接字已关闭，命令无法送达'
  }
  return null
}

// 命令内容预检：空命令服务端也只会回一条 FAIL，先把注定失败的往返挡在本地。
// 不做 trim 后发送——调用方给什么就发什么，只把「trim 后为空」判成空命令。
function emptyCommandProblem(command) {
  if (typeof command !== 'string') {
    return `命令必须是字符串（收到 ${describeType(command)}）`
  }
  if (command.trim().length === 0) {
    return '命令为空（空命令不会被发送）'
  }
  return null
}

// 超时说明：必须带上命令与超时时长，并指出最可能的两个原因。
function timeoutText(command, timeoutMs, error) {
  return (
    `执行命令「${command}」回执超时（${timeoutMs}ms 内未收到）：` +
    '可能是桩未装载该通道（消费方桩的 harness-core 版本过旧）、通道未注册，或命令卡在主线程未返回。' +
    `原始信息：${describeError(error)}`
  )
}

/**
 * 以服务端 console 身份执行一条命令，返回结构化结果。
 *
 * 不 reject：任何失败都体现在 `ok: false` 与 `output` 里。
 *
 * @param {Object} bot mineflayer bot
 * @param {string} command 待执行命令（可带前导 `/`，保持原样发送）
 * @param {{ timeoutMs?: number }} [options] `timeoutMs` 为等待回执上限，默认 10000
 * @returns {Promise<{ ok: boolean, output: string }>}
 *   `ok` 为 true 只表示「命令被服务端接受并执行」，`output` 是执行结果说明而非业务输出
 */
async function execCommand(bot, command, options = {}) {
  const timeoutMs = resolveTimeout(options == null ? undefined : options.timeoutMs)

  const emptyProblem = emptyCommandProblem(command)
  if (emptyProblem != null) {
    return { ok: false, output: emptyProblem }
  }
  const offlineProblem = connectionProblem(bot)
  if (offlineProblem != null) {
    return { ok: false, output: offlineProblem }
  }

  const id = nextRequestId()
  try {
    // 刻意用属性访问（而非顶层解构）调用通信原语：与模块导出保持同一份实现，
    // 也让测试能替换掉发 / 收两端的行为。
    commands.sendCommandPayload(bot, id, command)
  } catch (error) {
    // 预检之后、发包之前连接断掉（或拿到的 bot 不是 mineflayer bot）时如实返回
    return { ok: false, output: `发送命令「${command}」失败：${describeError(error)}` }
  }

  try {
    // 先发后等是安全的：`sendCommandPayload` 只把包写进流，回执要经 socket 事件循环回来，
    // 不可能在本次同步执行内触发监听器，故不存在「等之前回执已过」的竞态。
    return await commands.waitForCommandResult(bot, id, timeoutMs)
  } catch (error) {
    return { ok: false, output: timeoutText(command, timeoutMs, error) }
  }
}

// 把一条命令条目解析成「命令 + 可读标签」。
// 允许 `string` 与 `{ command, label }` 两种形态：带标签时失败说明能说清「第几步是哪件事」。
function toStep(entry) {
  if (entry != null && typeof entry === 'object' && !Array.isArray(entry)) {
    const label = typeof entry.label === 'string' && entry.label.length > 0 ? entry.label : null
    return { command: entry.command, label }
  }
  return { command: entry, label: null }
}

// 失败说明里的步骤描述：有标签时把可读名称放在「第 N 条命令」之后。
function describeStep(step) {
  return step.label == null ? '' : `（${step.label}）`
}

/**
 * 顺序执行多条命令；任一条失败即停止并返回该失败。
 *
 * 顺序而非并发是刻意的：命令之间常有因果（先 `gamemode` 再 `give`、先 `setblock` 再
 * `execute if block`），并发发出后服务端按什么顺序执行不由调用方决定，场景会随机失败——
 * 这种不确定性是最难排查的一类问题。
 *
 * 不 reject：任何失败都体现在 `ok: false` 与 `output` 里。
 *
 * @param {Object} bot mineflayer bot
 * @param {Array<string|{ command: string, label?: string }>} commandList 命令列表
 * @param {{ timeoutMs?: number }} [options] 同 execCommand（每条命令各自计一次超时）
 * @returns {Promise<Object>} `{ ok, output, results }`：`ok` 为 true 表示全部命令都被接受并执行；
 *   `results` 为 `Array<{ command, label, ok, output }>`，按执行顺序逐条记录（失败时止于失败那条）
 */
async function execCommands(bot, commandList, options = {}) {
  if (!Array.isArray(commandList)) {
    return {
      ok: false,
      output: `命令列表必须是数组（string[] 或 Array<{ command, label }>），收到 ${describeType(commandList)}`,
      results: []
    }
  }
  if (commandList.length === 0) {
    return { ok: false, output: '命令列表为空，未执行任何命令', results: [] }
  }

  const results = []
  for (let index = 0; index < commandList.length; index += 1) {
    const step = toStep(commandList[index])
    const result = await execCommand(bot, step.command, options)
    results.push({ command: step.command, label: step.label, ok: result.ok, output: result.output })
    if (!result.ok) {
      return {
        ok: false,
        output: `第 ${index + 1} 条${describeStep(step)}命令执行失败：${result.output}`,
        results
      }
    }
  }

  return { ok: true, output: `全部 ${results.length} 条命令均被服务端接受并执行`, results }
}

module.exports = {
  execCommand,
  execCommands
}
