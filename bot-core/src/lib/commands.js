'use strict'

// 插件消息 console 命令通道的机器人侧通信原语（FR-30，ADR-0027）。
//
// 这条通道只做**通信原语**：发一条 `E2E_CMD:<id>:<command>`，等回执 `E2E_CMD_DONE:<id>:<PASS|FAIL>:<output>`。
// 「发命令 → 拿到结构化结果」的高层封装是另一个需求（FR-31），本模块不提供，也不替调用方做重试。
//
// 为什么不走聊天：聊天有 256 字符硬上限（长命令被**静默截断**）、会污染其他玩家的聊天记录、
// 且控制流量不该出现在任何玩家屏幕上（ADR-0027）。

const { normalizeText } = require('./normalize')

// 通道名与桩侧常量逐字一致（`McTestkitProtocol.COMMAND_CHANNEL`）。
// 该名在 1.13+（`namespace:path` 形式的 Identifier）与 1.12-（裸字符串，旧版上限 20 字符）都合法，
// 故同一常量覆盖全部支持版本。
const COMMAND_CHANNEL = 'mc-testkit:cmd'

// 请求 / 回执前缀与桩侧逐字一致（`McTestkitProtocol.COMMAND_PREFIX` / `COMMAND_DONE_PREFIX`）。
const COMMAND_PREFIX = 'E2E_CMD:'
const COMMAND_DONE_PREFIX = 'E2E_CMD_DONE:'

// 默认等待回执超时：命令本身应在毫秒级返回，留足 30s 覆盖主线程排队与卡顿。
const DEFAULT_TIMEOUT_MS = 30000

// 依赖的协议客户端能力；缺失说明拿到的不是 mineflayer 的 bot 或版本不兼容。
function protocolClientOf(bot) {
  const client = bot && bot._client
  if (!client || typeof client.write !== 'function' || typeof client.on !== 'function') {
    throw new Error('bot 未暴露 mineflayer 协议客户端（bot._client），无法使用插件消息命令通道')
  }
  return client
}

// 已声明过的通道名（按 client 记账）。
//
// 不能去读 `client.channels`：minecraft-protocol 里那个数组是 pluginChannels 闭包内的局部变量，
// **没有**挂到 client 上。这里自己记账，避免重复 registerChannel 把同名通道塞进去多次。
const declaredChannels = new WeakMap()

// 通道是否已由本模块声明过。
function isChannelRegistered(client, channel) {
  const declared = declaredChannels.get(client)
  return declared != null && declared.has(channel)
}

/**
 * 确保 bot 已声明接收命令通道。
 *
 * 这一步**不能省**：服务端把回执发给某个玩家前，会检查该玩家是否声明过接收这个通道
 * （已核对 Paper 1.20.1 / 1.20.4 的 `CraftPlayer.sendPluginMessage` 实现，未声明则**静默丢弃**，
 * 机器人侧只会看到超时，看不出回执被服务端丢掉了；旧版服务端未逐一核对，但声明通道是协议标准动作，
 * 对旧版同样必需）。
 *
 * mineflayer 的 `registerChannel(name, parser, custom)` 会把通道名登记并在 `custom` 为真时
 * 发 REGISTER 包（1.13+ 发 `minecraft:register`，更早发裸 `REGISTER`，这一步由 mineflayer 按协议版本自己选）。
 * 不传解析器（parser）即按原始 Buffer 收包，本协议是 UTF-8 文本，无需额外解析。
 */
function ensureChannel(client, channel) {
  if (isChannelRegistered(client, channel)) {
    return
  }
  if (typeof client.registerChannel !== 'function') {
    throw new Error('mineflayer 协议客户端缺少 registerChannel，无法声明插件消息通道（需升级 mineflayer）')
  }
  client.registerChannel(channel, null, true)
  const declared = declaredChannels.get(client)
  if (declared == null) {
    declaredChannels.set(client, new Set([channel]))
    return
  }
  declared.add(channel)
}

/**
 * 发送一条 console 命令请求。
 *
 * 协议细节都经 mineflayer / minecraft-protocol 源码核实：
 * - 包名恒为 `custom_payload`，字段是 `{ channel, data }`（`data` 为 Buffer），
 *   从 1.8 到 1.21 的协议定义一致，不存在另一套字段名。
 * - 通道名从 1.13 起是 `namespace:path` 形式的 Identifier，1.12- 是裸字符串；
 *   两者在协议线上都是普通字符串，故同一个 `mc-testkit:cmd` 在全部支持版本上都合法。
 *
 * @param {Object} bot mineflayer bot
 * @param {string} id 请求 id（与回执配对；不得含冒号）
 * @param {string} command 待执行命令（可带前导 `/`）
 * @returns {string} 实际发出的消息文本
 */
function sendCommandPayload(bot, id, command) {
  const client = protocolClientOf(bot)
  if (typeof id !== 'string' || id.length === 0) {
    throw new Error('命令请求 id 不得为空——没有 id 的回执无法与请求配对')
  }
  if (id.includes(':')) {
    throw new Error(`命令请求 id 不得含冒号（收到：${id}）——它是第一个字段，含冒号会让回执配对错位`)
  }
  const message = `${COMMAND_PREFIX}${id}:${command == null ? '' : command}`
  ensureChannel(client, COMMAND_CHANNEL)
  client.write('custom_payload', {
    channel: COMMAND_CHANNEL,
    data: Buffer.from(message, 'utf8')
  })
  return message
}

/**
 * 把一条回执文本解析成结构化结果。
 *
 * **最多切 4 段**：`E2E_CMD_DONE:<id>:<PASS|FAIL>:<output>`，其中 id 与判定位置固定、
 * output 是最后一段，故 output 里的冒号（如 `minecraft:stone`）原样保留。
 * 按无上限切分会把 output 切坏且看起来「有输出」——属于静默失真。
 *
 * @param {string} text 收到的消息文本
 * @returns {{ id: string, ok: boolean, output: string } | null} 不是本协议回执时返回 null
 */
function parseCommandDone(text) {
  if (typeof text !== 'string' || !text.startsWith(COMMAND_DONE_PREFIX)) {
    return null
  }
  const parts = text.split(':')
  // 先按固定位置取 id 与判定，再把剩余部分**原样**接回 output：
  // 等价于 split(':', 4)，但不必依赖引擎对 limit 语义的实现细节
  if (parts.length < 4) {
    return null
  }
  const id = parts[1]
  const verdict = parts[2]
  if (verdict !== 'PASS' && verdict !== 'FAIL') {
    return null
  }
  const output = parts.slice(3).join(':')
  return { id, ok: verdict === 'PASS', output }
}

/**
 * 等待指定 id 的命令回执。
 *
 * 监听 `custom_payload`：minecraft-protocol 会为每个收包同时触发 `custom_payload` 事件，
 * 参数为 `{ channel, data }`（`data` 是 Buffer）。这里按 channel 过滤后再按 id 配对，
 * 因此同一条连接上并发多条命令时不会互相串台。
 *
 * @param {Object} bot mineflayer bot
 * @param {string} id 请求 id
 * @param {number} [timeoutMs] 超时毫秒数
 * @returns {Promise<{ ok: boolean, output: string }>} 超时 reject（不静默返回 undefined）
 */
function waitForCommandResult(bot, id, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const client = protocolClientOf(bot)
  return new Promise((resolve, reject) => {
    let settled = false
    const timer = setTimeout(() => {
      if (settled) {
        return
      }
      settled = true
      client.removeListener('custom_payload', onPacket)
      // 超时要说明**等的是哪一条**：只说「超时」无法区分命令没执行与回执被丢弃
      reject(new Error(`等待 console 命令回执超时（id=${id}）：桩可能未装载、通道未注册，或命令卡在主线程`))
    }, timeoutMs)

    function onPacket(packet) {
      if (settled || !packet || packet.channel !== COMMAND_CHANNEL) {
        return
      }
      const raw = Buffer.isBuffer(packet.data) ? packet.data.toString('utf8') : String(packet.data ?? '')
      const parsed = parseCommandDone(raw)
      if (parsed == null || parsed.id !== id) {
        return
      }
      settled = true
      clearTimeout(timer)
      client.removeListener('custom_payload', onPacket)
      resolve({ ok: parsed.ok, output: normalizeText(parsed.output) })
    }

    client.on('custom_payload', onPacket)
  })
}

module.exports = {
  COMMAND_CHANNEL,
  COMMAND_PREFIX,
  COMMAND_DONE_PREFIX,
  DEFAULT_TIMEOUT_MS,
  sendCommandPayload,
  waitForCommandResult,
  parseCommandDone
}

// 高层封装（`execCommand` / `execCommands`）的实现在 `./exec`——本模块只做通信原语，不反向依赖它。
// 但 ADR-0027 第三部分的示例写的是从 `lib/commands` 取 `execCommand`，而已接受的 ADR 正文不修改，
// 于是这里补一个**惰性**别名让那个写法也能跑：只在真正读取属性时才 `require`，
// 两个模块因此不构成加载期循环依赖（`exec` 依赖本模块的原语，本模块不在加载期依赖 `exec`）。
Object.defineProperty(module.exports, 'execCommand', {
  enumerable: true,
  get: () => require('./exec').execCommand
})
Object.defineProperty(module.exports, 'execCommands', {
  enumerable: true,
  get: () => require('./exec').execCommands
})
