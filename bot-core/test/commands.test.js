'use strict'

// 插件消息 console 命令通道的机器人侧通信原语测试（node:test，纯本地，不打网络、不连服务端）。
//
// 用最小的事件驱动假客户端替代 mineflayer 的协议客户端：只实现本模块真正会调用的
// `write` / `on` / `removeListener` / `registerChannel`。刻意不 mock 协议解析细节——
// 真实的寄存器与收发行为需真机验证，这里只锁住「本模块自己那部分」的契约。

const test = require('node:test')
const assert = require('node:assert/strict')

const {
  COMMAND_CHANNEL,
  sendCommandPayload,
  waitForCommandResult,
  parseCommandDone
} = require('../src/lib/commands')

// 构造一个假协议客户端：记录写出的包、可手动注入收到的包。
function fakeClient() {
  const listeners = new Map()
  const written = []
  const registered = []
  return {
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
    // 模拟服务端下发一个包
    emit(event, payload) {
      for (const handler of [...(listeners.get(event) || [])]) {
        handler(payload)
      }
    },
    listenerCount(event) {
      return (listeners.get(event) || []).length
    }
  }
}

function fakeBot(client) {
  return { _client: client }
}

// 构造一条回执载荷（channel 统一为命令通道，data 为 UTF-8 Buffer）。
function donePacket(text) {
  return { channel: COMMAND_CHANNEL, data: Buffer.from(text, 'utf8') }
}

test('sendCommandPayload 发出 custom_payload 且字段为 channel + data', () => {
  const client = fakeClient()
  const message = sendCommandPayload(fakeBot(client), 'r1', 'gamemode creative Steve')

  assert.equal(message, 'E2E_CMD:r1:gamemode creative Steve')
  assert.equal(client.written.length, 1)
  const { packetName, packet } = client.written[0]
  assert.equal(packetName, 'custom_payload')
  assert.equal(packet.channel, COMMAND_CHANNEL)
  assert.ok(Buffer.isBuffer(packet.data), 'data 必须是 Buffer')
  assert.equal(packet.data.toString('utf8'), 'E2E_CMD:r1:gamemode creative Steve')
})

test('sendCommandPayload 首次发送时声明通道（服务端否则会静默丢弃回执）', () => {
  const client = fakeClient()
  const bot = fakeBot(client)

  sendCommandPayload(bot, 'a', 'say hi')
  assert.deepEqual(client.registered, [{ name: COMMAND_CHANNEL, parser: null, custom: true }],
    '必须带 custom=true 才会真正发出 REGISTER 包')

  // 第二条命令不应重复声明同名通道
  sendCommandPayload(bot, 'b', 'say hi again')
  assert.equal(client.registered.length, 1, '同名通道只应声明一次')
  assert.equal(client.written.length, 2)
})

test('sendCommandPayload 保留命令里的冒号', () => {
  const client = fakeClient()
  const message = sendCommandPayload(fakeBot(client), 'x', 'give Steve minecraft:stone 1')
  assert.equal(message, 'E2E_CMD:x:give Steve minecraft:stone 1')
  assert.equal(client.written[0].packet.data.toString('utf8'), 'E2E_CMD:x:give Steve minecraft:stone 1')
})

test('sendCommandPayload 拒绝非法 id', () => {
  const client = fakeClient()
  const bot = fakeBot(client)
  assert.throws(() => sendCommandPayload(bot, '', 'say hi'), /id 不得为空/)
  assert.throws(() => sendCommandPayload(bot, 'a:b', 'say hi'), /不得含冒号/)
  assert.equal(client.written.length, 0, '被拒绝的请求不得真的发出')
})

test('sendCommandPayload 对缺少协议客户端的 bot 明确报错', () => {
  assert.throws(() => sendCommandPayload({}, 'a', 'say hi'), /协议客户端/)
})

test('parseCommandDone 按最多 4 段解析，output 里的冒号保留', () => {
  const parsed = parseCommandDone('E2E_CMD_DONE:p9:PASS:已给予 minecraft:stone x1')
  assert.deepEqual(parsed, { id: 'p9', ok: true, output: '已给予 minecraft:stone x1' })
})

test('parseCommandDone 区分 PASS 与 FAIL', () => {
  assert.deepEqual(parseCommandDone('E2E_CMD_DONE:1:PASS:命令已被服务端接受并执行'),
    { id: '1', ok: true, output: '命令已被服务端接受并执行' })
  assert.deepEqual(parseCommandDone('E2E_CMD_DONE:1:FAIL:未知命令'),
    { id: '1', ok: false, output: '未知命令' })
})

test('parseCommandDone 对非本协议载荷返回 null', () => {
  assert.equal(parseCommandDone('E2E_READY:smoke'), null)
  assert.equal(parseCommandDone('E2E_CMD_DONE:1:MAYBE:x'), null)
  assert.equal(parseCommandDone('E2E_CMD_DONE:1:PASS'), null, '缺 output 段视为不完整')
  assert.equal(parseCommandDone(null), null)
})

test('waitForCommandResult 收到匹配 id 的 PASS 回执即 resolve', async () => {
  const client = fakeClient()
  const pending = waitForCommandResult(fakeBot(client), 'r1', 1000)

  // 不同 id 的回执不得让它提前 resolve
  client.emit('custom_payload', donePacket('E2E_CMD_DONE:other:PASS:无关回执'))
  // 别的通道的包也不得被误认
  client.emit('custom_payload', { channel: 'other:chan', data: Buffer.from('E2E_CMD_DONE:r1:PASS:错通道') })
  client.emit('custom_payload', donePacket('E2E_CMD_DONE:r1:PASS:gamemode changed'))

  assert.deepEqual(await pending, { ok: true, output: 'gamemode changed' })
  assert.equal(client.listenerCount('custom_payload'), 0, '收尾后必须摘掉监听器')
})

test('waitForCommandResult 收到 FAIL 回执返回 ok=false 并带原因', async () => {
  const client = fakeClient()
  const pending = waitForCommandResult(fakeBot(client), 'f1', 1000)
  client.emit('custom_payload', donePacket('E2E_CMD_DONE:f1:FAIL:命令未被服务端接受（未知命令或无控制台权限）'))

  const result = await pending
  assert.equal(result.ok, false)
  assert.match(result.output, /未知命令/)
})

test('waitForCommandResult 保留 output 中的冒号', async () => {
  const client = fakeClient()
  const pending = waitForCommandResult(fakeBot(client), 'c1', 1000)
  client.emit('custom_payload', donePacket('E2E_CMD_DONE:c1:PASS:已给予 minecraft:stone x1'))

  assert.deepEqual(await pending, { ok: true, output: '已给予 minecraft:stone x1' })
})

test('waitForCommandResult 超时 reject 且摘掉监听器', async () => {
  const client = fakeClient()
  await assert.rejects(() => waitForCommandResult(fakeBot(client), 'slow', 20), /等待 console 命令回执超时/)
  assert.equal(client.listenerCount('custom_payload'), 0, '超时后必须摘掉监听器，避免泄留')
})

test('并发等待不同 id 时不串台', async () => {
  const client = fakeClient()
  const first = waitForCommandResult(fakeBot(client), 'a', 1000)
  const second = waitForCommandResult(fakeBot(client), 'b', 1000)

  client.emit('custom_payload', donePacket('E2E_CMD_DONE:b:FAIL:第二条失败'))
  client.emit('custom_payload', donePacket('E2E_CMD_DONE:a:PASS:第一条成功'))

  assert.deepEqual(await first, { ok: true, output: '第一条成功' })
  assert.deepEqual(await second, { ok: false, output: '第二条失败' })
})
