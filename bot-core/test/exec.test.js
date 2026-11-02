'use strict'

// 机器人命令执行 API 测试（node:test，纯本地，不打网络、不连服务端）。
//
// 打法与 commands.test.js 一致：用最小的事件驱动假客户端替代 mineflayer 协议客户端，
// 只实现本模块真正会碰到的 `write` / `on` / `removeListener` / `registerChannel`；
// 需要隔离通信原语时用 `mock.method` 替换 commands 模块的导出（exec.js 按属性访问调用，
// 替换即生效），不为了可测性给生产代码开注入口。

const test = require('node:test')
const assert = require('node:assert/strict')
const { mock } = require('node:test')

const commands = require('../src/lib/commands')
const { execCommand, execCommands } = require('../src/lib/exec')

// 构造一个假协议客户端：记录写出的包、可手动注入收到的包。
// `ended` 默认 false——minecraft-protocol 的 Client 在 setSocket 时同样置 false。
function fakeClient(overrides = {}) {
  const listeners = new Map()
  const written = []
  const registered = []
  return {
    ended: false,
    written,
    registered,
    write(packetName, packet) {
      written.push({ packetName, packet })
    },
    registerChannel(name, parser, custom) {
      registered.push({ name, parser, custom })
    },
    on(event, handler) {
      const list = listeners.get(event) || []
      list.push(handler)
      listeners.set(event, list)
    },
    removeListener(event, handler) {
      const list = listeners.get(event) || []
      listeners.set(event, list.filter((entry) => entry !== handler))
    },
    emit(event, payload) {
      for (const handler of [...(listeners.get(event) || [])]) {
        handler(payload)
      }
    },
    listenerCount(event) {
      return (listeners.get(event) || []).length
    },
    ...overrides
  }
}

function fakeBot(client) {
  return { _client: client }
}

// 从假客户端写出的包还原出请求消息文本（失败说明里要能看出到底发了什么）。
function sentMessages(client) {
  return client.written.map((entry) => entry.packet.data.toString('utf8'))
}

// 构造一条命令通道回执载荷（与 commands.test.js 同一形状：channel + UTF-8 Buffer）。
function donePacket(id, verdict, output) {
  return {
    channel: commands.COMMAND_CHANNEL,
    data: Buffer.from(`E2E_CMD_DONE:${id}:${verdict}:${output}`, 'utf8')
  }
}

// 把通信原语整体换成桩：发包只记录，收包由用例自己决定。
// 用 `t.after` 还原，避免用例失败时把替换泄漏给后续用例（mock.method 是模块级替换）。
function stubTransport(t, { onSend, onWait } = {}) {
  const sent = []
  const waits = []
  const sendMock = mock.method(commands, 'sendCommandPayload', (bot, id, command) => {
    sent.push({ bot, id, command })
    if (onSend) {
      return onSend(bot, id, command)
    }
    return `E2E_CMD:${id}:${command}`
  })
  const waitMock = mock.method(commands, 'waitForCommandResult', (bot, id, timeoutMs) => {
    waits.push({ bot, id, timeoutMs })
    return onWait ? onWait(bot, id, timeoutMs) : Promise.resolve({ ok: true, output: 'ok' })
  })
  t.after(() => {
    sendMock.mock.restore()
    waitMock.mock.restore()
  })
  return { sent, waits, sendMock, waitMock }
}

test('execCommand 成功时原样透传回执的 ok 与 output', async (t) => {
  stubTransport(t, { onWait: () => Promise.resolve({ ok: true, output: '命令已被服务端接受并执行' }) })

  const result = await execCommand(fakeBot(fakeClient()), 'time set day', { timeoutMs: 500 })

  assert.deepEqual(result, { ok: true, output: '命令已被服务端接受并执行' })
})

test('execCommand 发出的命令保持原样（带前导斜杠也不改写）', async (t) => {
  const { sent, waits } = stubTransport(t)

  await execCommand(fakeBot(fakeClient()), '/gamemode creative Steve')

  assert.equal(sent.length, 1)
  assert.equal(sent[0].command, '/gamemode creative Steve')
  assert.equal(waits[0].timeoutMs, 10000, '未指定超时时用默认值')
})

test('execCommand 收到 FAIL 回执返回 ok=false 并带上失败原因', async (t) => {
  stubTransport(t, { onWait: () => Promise.resolve({ ok: false, output: '命令未被服务端接受（未知命令或无控制台权限）' }) })

  const result = await execCommand(fakeBot(fakeClient()), 'nosuchcommand')

  assert.equal(result.ok, false)
  assert.match(result.output, /未知命令/)
})

test('execCommand 超时返回 ok=false，说明里含命令、超时毫秒与「桩未装载通道」提示', async () => {
  // 用真实的通信原语 + 短超时：走的正是线上那条路径（发不出去的回执由超时兜住）
  const client = fakeClient()

  const result = await execCommand(fakeBot(client), 'time set day', { timeoutMs: 20 })

  assert.equal(result.ok, false)
  assert.match(result.output, /time set day/, '失败说明必须含命令，否则不知道是哪一条超时')
  assert.match(result.output, /20ms/, '失败说明必须含超时时长')
  assert.match(result.output, /桩未装载该通道/, '要指出最可能的原因：桩没装这条通道')
  assert.equal(client.listenerCount('custom_payload'), 0, '超时后不得残留监听器')
  assert.equal(client.registered.length, 1, '即便超时，通道也真的声明过')
  assert.match(sentMessages(client)[0], /^E2E_CMD:[^-:]+-[^:]+:time set day$/, '线上格式须与 FR-30 协议一致')
})

test('execCommand 对空命令与非法类型直接返回失败且完全不发包', async (t) => {
  const { sendMock } = stubTransport(t)
  const client = fakeClient()
  const bot = fakeBot(client)

  for (const bad of ['', '   ', null, undefined, 42]) {
    const result = await execCommand(bot, bad)
    assert.equal(result.ok, false, `命令 ${JSON.stringify(bad)} 应判失败`)
    assert.match(result.output, /命令/)
  }

  assert.equal(sendMock.mock.calls.length, 0, '注定失败的请求不得真的发出去')
  assert.equal(client.written.length, 0)
})

test('execCommand 在 bot 已断线时返回失败且不发包', async (t) => {
  const { sendMock } = stubTransport(t)

  const endedClient = fakeClient({ ended: true })
  const endedResult = await execCommand(fakeBot(endedClient), 'say hi')
  assert.equal(endedResult.ok, false)
  assert.match(endedResult.output, /连接已结束/)

  const deadSocketClient = fakeClient({ socket: { destroyed: true } })
  const deadSocketResult = await execCommand(fakeBot(deadSocketClient), 'say hi')
  assert.equal(deadSocketResult.ok, false)
  assert.match(deadSocketResult.output, /套接字已关闭/)

  const noClientBot = await execCommand({}, 'say hi')
  assert.equal(noClientBot.ok, false)
  assert.match(noClientBot.output, /协议客户端/)

  assert.equal(sendMock.mock.calls.length, 0, '断线时不得把包写进已关闭的连接')
})

test('并发两次 execCommand 使用互不相同的请求 id', async (t) => {
  const { sent } = stubTransport(t, {
    // 回执延后一个 tick，让两次调用真的重叠（否则「并发」只是顺序执行）
    onWait: () => new Promise((resolve) => setImmediate(() => resolve({ ok: true, output: 'ok' })))
  })
  const bot = fakeBot(fakeClient())

  const [first, second] = await Promise.all([
    execCommand(bot, 'time set day'),
    execCommand(bot, 'time set night')
  ])

  assert.equal(first.ok, true)
  assert.equal(second.ok, true)
  assert.equal(sent.length, 2)
  assert.notEqual(sent[0].id, sent[1].id, '同一 bot 上并发调用时 id 必须唯一，否则回执会串台')
  assert.ok(sent.every((entry) => !entry.id.includes(':')), 'id 不得含冒号（它是协议的第一个字段）')
})

test('走真实通信原语：并发两条命令的回执各归各的，不串台', async () => {
  // 不 mock 传输层：这条用例锁的是 exec.js 与 FR-30 原语的衔接（id 生成 → 发包 → 按 id 配对）
  const client = fakeClient()
  const bot = fakeBot(client)

  const first = execCommand(bot, 'time set day', { timeoutMs: 1000 })
  const second = execCommand(bot, 'time set night', { timeoutMs: 1000 })

  // 两条请求都真的写到了线上，各自的 id 不同
  const ids = sentMessages(client).map((message) => message.split(':')[1])
  assert.equal(ids.length, 2)
  assert.notEqual(ids[0], ids[1])

  // 乱序回执：后发的那条先回，先发的那条后回——按 id 配对才不会串台
  client.emit('custom_payload', donePacket(ids[1], 'PASS', 'night done'))
  client.emit('custom_payload', donePacket(ids[0], 'PASS', 'day done'))

  assert.deepEqual(await first, { ok: true, output: 'day done' })
  assert.deepEqual(await second, { ok: true, output: 'night done' })
  assert.equal(client.listenerCount('custom_payload'), 0, '两条都收尾后不得残留监听器')
})

test('execCommands 顺序执行：第二条失败后第三条不再执行', async () => {
  // 用真实通信原语 + 假客户端：第一条回 PASS、第二条回 FAIL，锁的是「失败即停」而不是 mock 行为
  const client = fakeClient()
  const resultPromise = execCommands(fakeBot(client), [
    { command: 'gamerule doDaylightCycle false', label: '关昼夜循环' },
    { command: 'time set day', label: '设为白天' },
    { command: 'say 不该被执行' }
  ])

  // 逐条放行：每轮取下一条写出的请求、按它的 id 回执
  for (let round = 0; round < 2; round += 1) {
    await new Promise((resolve) => setImmediate(resolve))
    const request = sentMessages(client)[round]
    const id = request.split(':')[1]
    client.emit('custom_payload', donePacket(id, round === 1 ? 'FAIL' : 'PASS', round === 1 ? '命令未被服务端接受' : 'ok'))
  }

  const result = await resultPromise
  const sent = sentMessages(client)

  assert.equal(result.ok, false)
  assert.equal(sent.length, 2, '失败即停，第三条不得发出')
  assert.match(sent[0], /:gamerule doDaylightCycle false$/, '必须按声明顺序执行')
  assert.match(sent[1], /:time set day$/)
  assert.equal(result.results.length, 2, 'results 只记到失败那条为止')
  assert.deepEqual(
    result.results.map((entry) => entry.ok),
    [true, false]
  )
  assert.match(result.output, /第 2 条/, '失败说明要点明是第几条')
  assert.match(result.output, /设为白天/, '带 label 时要说清是哪件事失败')
})

test('execCommands 全部成功时 ok=true 且 results 逐条对应', async (t) => {
  stubTransport(t)

  const result = await execCommands(fakeBot(fakeClient()), [
    'gamerule doDaylightCycle false',
    { command: 'time set day', label: '设为白天' }
  ])

  assert.equal(result.ok, true)
  assert.equal(result.results.length, 2)
  assert.deepEqual(
    result.results.map((entry) => [entry.command, entry.label, entry.ok]),
    [
      ['gamerule doDaylightCycle false', null, true],
      ['time set day', '设为白天', true]
    ]
  )
  assert.match(result.output, /全部 2 条命令/)
})

test('execCommands 对非数组与空数组返回失败且不发包', async (t) => {
  const { sendMock } = stubTransport(t)
  const bot = fakeBot(fakeClient())

  const notArray = await execCommands(bot, 'time set day')
  assert.equal(notArray.ok, false)
  assert.deepEqual(notArray.results, [])
  assert.match(notArray.output, /必须是数组/)

  const empty = await execCommands(bot, [])
  assert.equal(empty.ok, false)
  assert.match(empty.output, /为空/)

  assert.equal(sendMock.mock.calls.length, 0)
})

test('execCommands 遇到空命令时停在第一条，并指出是哪条', async (t) => {
  const { sent } = stubTransport(t)

  const result = await execCommands(fakeBot(fakeClient()), ['time set day', '  ', 'say 不该被执行'])

  assert.equal(result.ok, false)
  assert.equal(sent.length, 1, '空命令在本地就被拦下，不发包')
  assert.match(result.output, /第 2 条/)
  assert.equal(result.results.length, 2)
})

test('lib/commands 也能取到 execCommand / execCommands（ADR-0027 示例的写法）', () => {
  // ADR-0027 第三部分的示例从 lib/commands 取 execCommand；已接受的 ADR 正文不可改，
  // 故 communications 模块给了惰性别名。这里锁住它确实可用、且就是 lib/exec 的同一份实现。
  assert.equal(commands.execCommand, execCommand)
  assert.equal(commands.execCommands, execCommands)
})
