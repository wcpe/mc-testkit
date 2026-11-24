'use strict'

// 结论回传的聊天帧上限契约（node:test，纯函数，不打网络）。
//
// 回归背景：结论经 `bot.chat` 回传，而原版协议对 `serverbound chat` 的单包上限是 256
// 字符（UTF-16 码元）。超长的包在服务端**解码期就被丢弃并断开玩家**——不是发出去被截断，
// 是整包消失。于是桩一直等 `scenario-timeout-seconds`（默认 360s）才写
// `fail("场景执行超时")`，日志里只有一条 mineflayer kicked：典型卡死式假失败。
//
// 这组测试钉住三件事：
//   ① 正常长度原样发出，不做任何改动；
//   ② 超长时截断，且**截断后的整包一定不超过上限**（标注也算在内）；
//   ③ 标注必须出现——读者要知道"这条原因不完整"，否则会把半条原因当成全部。

const assert = require('node:assert/strict')
const test = require('node:test')

const { MAX_CHAT_CHARS, sendChatSafely } = require('../src/lib/messages')

// 造一个只记录 chat 调用的 bot。
function makeBot() {
  const sent = []
  return { sent, chat: (message) => sent.push(message) }
}

test('MAX_CHAT_CHARS 与原版 chat 帧上限一致', () => {
  assert.equal(MAX_CHAT_CHARS, 256)
})

test('未超长时原样发出（前缀 + 明细，不做任何改动）', () => {
  const bot = makeBot()
  const message = sendChatSafely(bot, 'E2E_BOT_COMMAND_PASS:', 'list -> 共 3 名玩家')

  assert.equal(message, 'E2E_BOT_COMMAND_PASS:list -> 共 3 名玩家')
  assert.deepEqual(bot.sent, [message], '应当只发一条，且内容不变')
})

test('恰好等于上限时不截断', () => {
  const bot = makeBot()
  const prefix = 'P:'
  const detail = 'x'.repeat(MAX_CHAT_CHARS - prefix.length)

  const message = sendChatSafely(bot, prefix, detail)

  assert.equal(message.length, MAX_CHAT_CHARS)
  assert.equal(message, prefix + detail, '恰好等于上限不应触发截断')
  assert.ok(!message.includes('截断'), '不该出现截断标注')
})

test('超长时截断，且整包不超过上限（标注计入上限）', () => {
  const bot = makeBot()
  const prefix = 'E2E_BOT_COMMAND_FAIL:'
  const detail = 'y'.repeat(1000)

  const message = sendChatSafely(bot, prefix, detail)

  assert.ok(
    message.length <= MAX_CHAT_CHARS,
    `截断后仍超出上限：${message.length} > ${MAX_CHAT_CHARS}——服务端会直接断开玩家`
  )
  assert.equal(bot.sent.length, 1)
  assert.equal(bot.sent[0], message, '发出去的内容应当就是返回值')
})

test('超长时带截断标注（读者要知道原因不完整）', () => {
  const bot = makeBot()
  const message = sendChatSafely(bot, 'P:', 'z'.repeat(1000))

  assert.match(message, /截断/, '必须显式标注，否则会把半条原因当成全部原因')
})

test('detail 为 null / undefined / 空串时只发前缀', () => {
  for (const detail of [null, undefined, '']) {
    const bot = makeBot()
    const message = sendChatSafely(bot, 'PREFIX:', detail)

    assert.equal(message, 'PREFIX:', `detail=${String(detail)} 时应只发前缀`)
    assert.deepEqual(bot.sent, ['PREFIX:'])
  }
})

test('detail 不是字符串时按字符串处理（不抛）', () => {
  const bot = makeBot()
  const message = sendChatSafely(bot, 'P:', 42)

  assert.equal(message, 'P:42')
})

test('前缀接近上限时，截断结果仍不超上限', () => {
  const bot = makeBot()
  // 前缀长到几乎没有明细预算，但标注本身比预算还长——这是最容易写出超限包的位置
  const prefix = 'P'.repeat(MAX_CHAT_CHARS - 5)

  const message = sendChatSafely(bot, prefix, 'detail')

  assert.ok(
    message.length <= MAX_CHAT_CHARS,
    `前缀很长时截断仍超限：${message.length} > ${MAX_CHAT_CHARS}（标注长度超过了剩余预算）`
  )
})

test('前缀本身超上限时也不发出超限包', () => {
  const bot = makeBot()
  const prefix = 'P'.repeat(MAX_CHAT_CHARS + 50)

  const message = sendChatSafely(bot, prefix, 'detail')

  assert.ok(
    message.length <= MAX_CHAT_CHARS,
    `前缀自身超限时仍发出了 ${message.length} 字符的包，服务端会断开玩家`
  )
})

test('各长度区间都不产生超限包（覆盖边界附近）', () => {
  const prefix = 'E2E_BOT_COMMAND_FAIL:'
  for (const length of [0, 1, 200, 238, 239, 240, 241, 250, 300, 500, 5000]) {
    const bot = makeBot()
    const message = sendChatSafely(bot, prefix, 'd'.repeat(length))

    assert.ok(
      message.length <= MAX_CHAT_CHARS,
      `detail 长度 ${length} 时发出 ${message.length} 字符，超过上限 ${MAX_CHAT_CHARS}`
    )
  }
})
