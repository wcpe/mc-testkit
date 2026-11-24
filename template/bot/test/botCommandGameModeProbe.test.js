'use strict'

// bot-command 场景「游戏模式取证按服务端版本选路」的单元测试（node:test，不打网络）。
//
// 回归背景：早期实现固定用 `data get entity <玩家> playerGameType` 取证，而 `/data`
// 是 MC 1.13 才加入的命令、本框架的 bot 支持却下探到 1.8。于是 1.8–1.12.2 上一个
// 本来能通过的场景会被判 FAIL——把「假通过」换成了「假失败」。这组测试锁住三条底线：
//
//   ① 1.13+ 走 `/data` 读回数值；
//   ② 1.12 及以下**不发** `/data`，改用 `/gamemode` 的成功回显作正面证据；
//   ③ 两条路都拿不到证据时**判失败**，绝不回到「没等到拒绝就算通过」。

const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildGameModeProbe,
  probeLegacyGameMode,
  resolveServerVersion,
  supportsDataCommand
} = require('../src/scenarios/botCommand')

// ── 测试替身 ──

// 造一个最小 bot：只带取证用得到的字段。
function makeBot({ version, username = 'TestBot', history = [] } = {}) {
  return { username, version, __e2eMessageHistory: history.slice() }
}

// 复刻 bot-core `waitForMessage` 的行为契约：先扫历史，再等新消息，超时 reject。
//
// `incoming` 用来模拟「命令发出后服务端才回话」：命中则 resolve，否则按超时 reject。
// 用 `setTimeout(..., 0)` 而不是同步结算，是因为真实实现要等事件循环——同步结算会在
// 被测代码还没把等待登记上时就触发，测出来的就不是真实时序了。
function makeWaitForMessage(incoming = []) {
  const waitForMessage = (bot, predicate) => {
    const fromHistory = (bot.__e2eMessageHistory || []).find((text) => predicate(text))
    if (fromHistory != null) {
      return Promise.resolve(fromHistory)
    }
    return new Promise((resolve, reject) => {
      setTimeout(() => {
        const hit = incoming.find((text) => predicate(text))
        if (hit != null) {
          resolve(hit)
        } else {
          reject(new Error('等待消息超时'))
        }
      }, 0)
    })
  }
  return { waitForMessage }
}

// 造一个 `execCommand` 替身，并记录发出的命令。
function makeExecCommand(result) {
  const calls = []
  const execCommand = (bot, command) => {
    calls.push(command)
    return Promise.resolve(result)
  }
  return { execCommand, calls }
}

// 场景侧只用到 `readyTimeoutMs`；给一个宽松值避免等待窗口影响判定。
const CONFIG = { readyTimeoutMs: 5000 }

// ── 版本判定 ──

test('supportsDataCommand：1.13 起支持 /data，1.12 及以下不支持', () => {
  assert.equal(supportsDataCommand('1.8'), false)
  assert.equal(supportsDataCommand('1.12.2'), false)
  assert.equal(supportsDataCommand('1.13'), true)
  assert.equal(supportsDataCommand('1.13.2'), true)
  assert.equal(supportsDataCommand('1.20.1'), true)
})

test('supportsDataCommand：版本缺失时按支持处理，退回 /data 由运行时如实报错', () => {
  // 无法判断版本时宁可走新路径：这条路失败会明确报错，不会误判通过；
  // 而误降到旧路径会让新版本白等一次回显超时。
  assert.equal(supportsDataCommand(null), true)
  assert.equal(supportsDataCommand(undefined), true)
  assert.equal(supportsDataCommand(''), true)
  assert.equal(supportsDataCommand('not-a-version'), true)
})

test('supportsDataCommand：未来大版本号按支持处理', () => {
  assert.equal(supportsDataCommand('2.0'), true)
})

// ── 版本来源 ──

test('resolveServerVersion：bot.version 优先于 config.version', () => {
  // bot.version 是连接协商后的真实版本（mineflayer loader 回填），比编排下发的期望值更可信
  assert.equal(resolveServerVersion({ version: '1.12.2' }, { version: '1.20.1' }), '1.12.2')
})

test('resolveServerVersion：bot 无版本时回落 config.version', () => {
  // 直连时编排不下发版本（传 null），但单测/自定义场景可能经 config 指定
  assert.equal(resolveServerVersion({}, { version: '1.20.1' }), '1.20.1')
  assert.equal(resolveServerVersion({ version: '' }, { version: '1.12.2' }), '1.12.2')
})

test('resolveServerVersion：两个来源都没有时返回 null', () => {
  assert.equal(resolveServerVersion({}, {}), null)
  assert.equal(resolveServerVersion(undefined, undefined), null)
})

// ── 1.13+：走 /data ──

test('1.20.1 走 /data 读到 1 时判通过', async () => {
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand, calls } = makeExecCommand({
    ok: true,
    output: 'TestBot has the following entity data: 1'
  })

  const probe = buildGameModeProbe(makeBot({ version: '1.20.1' }), CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, true, result.output)
  assert.deepEqual(calls, ['data get entity TestBot playerGameType'])
})

test('1.20.1 读到 0 时判失败，不因命令执行成功就算通过', async () => {
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand } = makeExecCommand({
    ok: true,
    output: 'TestBot has the following entity data: 0'
  })

  const probe = buildGameModeProbe(makeBot({ version: '1.20.1' }), CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, false)
  assert.match(result.output, /仍为 0/)
})

test('玩家名含数字时仍以句尾的数据值判定，不误判失败', async () => {
  // 回显格式「<玩家名> has the following entity data: N」：玩家名里的数字也在输出中。
  // 取第一个数字会先命中名字（如 Buyer1 → 1 → 误判 creative；Buyer0 → 0 → 误伤成功场景），
  // 必须取句尾的数据值。
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand } = makeExecCommand({
    ok: true,
    output: 'Buyer0 has the following entity data: 1'
  })

  const probe = buildGameModeProbe(makeBot({ version: '1.20.1', username: 'Buyer0' }), CONFIG, {
    waitForMessage,
    execCommand
  })
  const result = await probe()

  assert.equal(result.ok, true, result.output)
})

test('1.13 也开始走 /data（版本边界）', async () => {
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand, calls } = makeExecCommand({
    ok: true,
    output: 'TestBot has the following entity data: 1'
  })

  const probe = buildGameModeProbe(makeBot({ version: '1.13' }), CONFIG, { waitForMessage, execCommand })
  await probe()

  assert.ok(calls.some((command) => /data get entity/.test(command)))
})

// ── 1.8–1.12：不碰 /data，改读成功回显 ──

test('1.12.2 不发 /data，改用 /gamemode 成功回显判通过', async () => {
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand, calls } = makeExecCommand({ ok: true, output: '不该被调用' })
  const bot = makeBot({ version: '1.12.2', history: ['Set own game mode to Creative Mode'] })

  const probe = buildGameModeProbe(bot, CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, true, result.output)
  assert.deepEqual(calls, [], '1.12.2 上 /data 不存在，任何情况下都不该发出去')
})

test('1.8 同样走回显路径（bot 支持下限）', async () => {
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand } = makeExecCommand({ ok: true, output: '不该被调用' })
  const bot = makeBot({ version: '1.8', history: ['Set own game mode to Creative Mode'] })

  const probe = buildGameModeProbe(bot, CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, true, result.output)
})

test('旧版本没等到成功回显时判失败，不退回「没被拒绝就算通过」', async () => {
  const { waitForMessage } = makeWaitForMessage([])
  const { execCommand } = makeExecCommand({ ok: true, output: '不该被调用' })
  const bot = makeBot({ version: '1.12.2', history: [] })

  const probe = buildGameModeProbe(bot, CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, false)
  assert.match(result.output, /回显/)
})

test('旧版本回显为中文文案时同样识别', async () => {
  const { waitForMessage } = makeWaitForMessage()
  const { execCommand } = makeExecCommand({ ok: true, output: '不该被调用' })
  const bot = makeBot({ version: '1.12.2', history: ['你的游戏模式已被设为创造模式'] })

  const result = await probeLegacyGameMode(bot, CONFIG, '1.12.2', { waitForMessage, execCommand })
  assert.equal(result.ok, true, result.output)
})

// ── /data 失败时回退回显 ──

test('/data 失败但回显命中时判通过（版本判定失误的兜底）', async () => {
  const { waitForMessage } = makeWaitForMessage(['Set own game mode to Creative Mode'])
  const { execCommand } = makeExecCommand({
    ok: false,
    output: 'Unknown or incomplete command'
  })
  // 典型场景：代理下拿到的是对外版本，实际后端更老
  const bot = makeBot({ version: '1.20.1', history: [] })

  const probe = buildGameModeProbe(bot, CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, true, result.output)
})

test('/data 与回显都失败时判失败，且结论里同时给出两条原因', async () => {
  const { waitForMessage } = makeWaitForMessage([])
  const { execCommand } = makeExecCommand({
    ok: false,
    output: 'Unknown or incomplete command'
  })
  const bot = makeBot({ version: '1.20.1', history: [] })

  const probe = buildGameModeProbe(bot, CONFIG, { waitForMessage, execCommand })
  const result = await probe()

  assert.equal(result.ok, false)
  assert.match(result.output, /data 取证失败/)
  assert.match(result.output, /回显/)
})

// ── 默认依赖：不打网络也要能构造出探针 ──

test('不注入依赖时仍能构造探针（真实依赖在运行期才被调用）', () => {
  const bot = makeBot({ version: '1.12.2' })
  const probe = buildGameModeProbe(bot, CONFIG)
  assert.equal(typeof probe, 'function')
})
