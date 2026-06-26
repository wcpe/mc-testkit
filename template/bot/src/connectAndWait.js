'use strict'

const net = require('net')
const mineflayer = require('mineflayer')
const { envValue, envInt, optionalEnv } = require('./lib/env')
const { extractMessageText } = require('./lib/messages')
const { normalizeText, formatReason } = require('./lib/normalize')
const runExampleBot = require('./scenarios/exampleBot')
const runCrossServer = require('./scenarios/crossServerBot')
const runContinuousStress = require('./scenarios/continuousStress')
const runMultiBot = require('./scenarios/multiBot')
const runCrashTakeover = require('./scenarios/crashTakeover')

// mc-testkit E2E 机器人入口（照抄物）：探测端口 → mineflayer 登录 → spawn 后按 action 分发场景
// → 驱动完成后保持在线，等待服务端侧（桩）判定并关服。连接失败按重试间隔重连直到总超时。
//
// 所有环境变量以 MC_TESTKIT_E2E_ 为前缀（mc-testkit 冻结契约，docs/API.md §3.3），集中在此读取。

// 压测 bot 比桩计时早停的秒数（留出向桩上报的窗口后再被关服）
const STRESS_BOT_EARLY_STOP_SECONDS = 10
// 压测 bot 最短施压秒数下限（避免极短时长配置把施压窗口压到 0）
const STRESS_BOT_MIN_DURATION_SECONDS = 5
// 崩溃接管（FR-15）：当前后端宕机断线后，等这么久再经代理重连——给宕机后端端口关闭 + 代理感知留时间，
// 避免重连又落回正在退出的后端
const RECONNECT_AFTER_CRASH_DELAY_MS = 3000

const config = {
  // 场景动作：决定分发到哪个场景驱动，须与桩侧场景 id、编排声明一致
  action: envValue('MC_TESTKIT_E2E_BOT_ACTION', 'unspecified'),
  // ── 连接参数 ──
  host: envValue('MC_TESTKIT_E2E_BOT_HOST', 'localhost'),
  port: envInt('MC_TESTKIT_E2E_BOT_PORT', 25565),
  username: envValue('MC_TESTKIT_E2E_BOT_USERNAME', 'McTestkitE2eBot'),
  auth: envValue('MC_TESTKIT_E2E_BOT_AUTH', 'offline'),
  // 协议版本：经代理时由编排固定为后端 MC 版本；直连可留空让 mineflayer 自动协商
  version: optionalEnv('MC_TESTKIT_E2E_BOT_VERSION'),
  // ── 超时 / 重试 ──
  connectTimeoutMs: envInt('MC_TESTKIT_E2E_BOT_CONNECT_TIMEOUT_MS', 180000),
  retryDelayMs: envInt('MC_TESTKIT_E2E_BOT_RETRY_DELAY_MS', 3000),
  readyTimeoutMs: envInt('MC_TESTKIT_E2E_BOT_READY_TIMEOUT_MS', 60000),
  // ── 压测维度（FR-11；仅 continuous-stress 场景用）──
  // 每 bot 序号 + 共享种子：seed ^ botIndex 播种确定性 RNG，使各 bot 可复现且互异
  botIndex: envInt('MC_TESTKIT_E2E_BOT_INDEX', 0),
  randomSeed: envInt('MC_TESTKIT_E2E_STRESS_RANDOM_SEED', 0),
  // 单 bot 施压时长（毫秒）：比桩计时早 STRESS_BOT_EARLY_STOP_SECONDS 停，留出上报窗口后再被关服
  durationMs:
    Math.max(
      STRESS_BOT_MIN_DURATION_SECONDS,
      envInt('MC_TESTKIT_E2E_STRESS_DURATION_SECONDS', 60) - STRESS_BOT_EARLY_STOP_SECONDS
    ) * 1000
}

let startedAt = Date.now()
let currentBot = null
let spawned = false
let exiting = false
let retryTimer = null
let scenarioStarted = false
let portProbeInFlight = false
// 崩溃接管（FR-15）：场景登记期望重连后，下面两个变量驱动「断线 → 经代理 fallback 重连到存活后端」
let reconnectExpected = false
let reconnectPending = null

function log(message) {
  console.log(`[E2E-BOT][${config.action}] ${message}`)
}

function clearRetryTimer() {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
}

// 端口未就绪 / 进服前失败时安排重试，直到累计超过连接总超时才放弃退出。
function scheduleRetry(reason) {
  if (exiting || spawned) {
    return
  }
  clearRetryTimer()
  const elapsed = Date.now() - startedAt
  if (elapsed >= config.connectTimeoutMs) {
    log(`连接超时，停止重试。最后原因: ${reason}`)
    process.exit(1)
    return
  }
  log(`连接失败，${config.retryDelayMs}ms 后重试。原因: ${reason}`)
  retryTimer = setTimeout(waitForServerPort, config.retryDelayMs)
}

// 优雅收尾：移除监听、断开 bot、延迟退出，避免残留连接。
function shutdown(code) {
  if (exiting) {
    return
  }
  exiting = true
  clearRetryTimer()
  if (currentBot) {
    try {
      currentBot.removeAllListeners()
      currentBot.quit('mc-testkit E2E bot shutdown')
    } catch (error) {
      // 忽略断开异常
    }
  }
  setTimeout(() => process.exit(code), 50)
}

// action → 场景驱动分发表。消费方在 src/scenarios/ 加驱动文件后，在此登记一个 case。
function scenarioRunner() {
  switch (config.action) {
    case 'example-bot':
      return runExampleBot
    case 'cross-server':
      return runCrossServer
    case 'continuous-stress':
      return runContinuousStress
    case 'multi-bot':
      return runMultiBot
    case 'crash-takeover':
      return runCrashTakeover
    default:
      return async () => {
        log('当前 action 未配置专门驱动，保持在线等待服务器关闭')
      }
  }
}

// 场景登记「断线后重连」（崩溃接管 FR-15）：设置期望并返回一个在「重连后再次 spawn」时 resolve（带新 bot）
// 的 Promise。默认后端宕机会断开本连接，经代理 fallback 重连到存活后端后，新 bot 经此 Promise 交回场景。
function reconnectOnDisconnect() {
  reconnectExpected = true
  return new Promise((resolve) => {
    reconnectPending = resolve
  })
}

async function startScenario(bot) {
  if (scenarioStarted) {
    return
  }
  scenarioStarted = true
  try {
    await scenarioRunner()({ bot, config, log, reconnectOnDisconnect })
    log('场景驱动步骤执行完成，继续等待服务器侧结果')
  } catch (error) {
    log(`场景驱动失败: ${error?.stack || error?.message || String(error)}`)
    shutdown(1)
  }
}

// 先用裸 TCP 探测端口是否开放，开放后再发起 mineflayer 登录；
// 服务端尚未起 / 代理还没有可用后端时，端口探测失败走重试，避免 mineflayer 反复抛错。
function waitForServerPort() {
  if (exiting || spawned || portProbeInFlight) {
    return
  }
  portProbeInFlight = true
  const socket = net.createConnection({ host: config.host, port: config.port }, () => {
    portProbeInFlight = false
    socket.end()
    connectMineflayer()
  })
  socket.once('error', (error) => {
    portProbeInFlight = false
    socket.destroy()
    scheduleRetry(`server port not ready: ${error?.message || String(error)}`)
  })
}

function connectMineflayer() {
  if (exiting || spawned) {
    return
  }
  const options = {
    host: config.host,
    port: config.port,
    username: config.username,
    auth: config.auth
  }
  if (config.version) {
    options.version = config.version
  }

  log(
    `端口已开放，开始 mineflayer 登录 ${config.host}:${config.port}，用户名=${config.username}，auth=${config.auth}` +
      `${config.version ? `，version=${config.version}` : ''}`
  )
  const bot = mineflayer.createBot(options)
  currentBot = bot
  let settledBeforeSpawn = false

  bot.once('login', () => log('登录成功，等待 spawn'))

  bot.once('spawn', () => {
    spawned = true
    settledBeforeSpawn = true
    if (reconnectPending) {
      // 重连后的再次 spawn（崩溃接管 fallback 落到存活后端）：把新 bot 交回等待的场景，不重启场景驱动
      reconnectExpected = false
      const resolveReconnect = reconnectPending
      reconnectPending = null
      log('重连后已重新进入服务器（经代理落到存活后端）')
      resolveReconnect(bot)
      return
    }
    log('已进入服务器，启动场景驱动')
    startScenario(bot)
  })

  // 维护最近消息历史，供 waitForMessage 先扫历史再监听（避免错过早到的控制消息）
  bot.__e2eMessageHistory = []
  bot.on('message', (jsonMessage) => {
    const message = extractMessageText(jsonMessage)
    if (!message) {
      return
    }
    bot.__e2eMessageHistory.push(message)
    if (bot.__e2eMessageHistory.length > 100) {
      bot.__e2eMessageHistory.shift()
    }
    log(`服务器消息: ${message}`)
  })

  bot.on('windowOpen', (window) => {
    log(`窗口打开: title=${normalizeText(window?.title) || '(unknown)'} type=${window?.type || '(unknown)'}`)
  })

  bot.once('kicked', (reason) => {
    const formatted = formatReason(reason)
    // 进服前被踢（常见于代理「无可用后端」）：清理后重试，等后端就绪
    if (!spawned) {
      settledBeforeSpawn = true
      currentBot = null
      scheduleRetry(`kick before spawn: ${formatted}`)
      return
    }
    log(`被服务器踢出: ${formatted}`)
  })

  bot.once('error', (error) => {
    const formatted = error?.stack || error?.message || String(error)
    if (!spawned) {
      settledBeforeSpawn = true
      currentBot = null
      scheduleRetry(`error before spawn: ${formatted}`)
      return
    }
    log(`运行中错误: ${formatted}`)
  })

  bot.once('end', (reason) => {
    const formatted = formatReason(reason)
    currentBot = null
    if (!spawned && !settledBeforeSpawn) {
      scheduleRetry(`end before spawn: ${formatted}`)
      return
    }
    if (!spawned) {
      return
    }
    log(`连接结束: ${formatted}`)
    if (reconnectExpected && !exiting) {
      // 场景期望重连（崩溃接管）：当前后端已宕机，刷新重试预算后经代理重连——代理 fallback 到存活后端
      spawned = false
      startedAt = Date.now()
      log(`连接结束，${RECONNECT_AFTER_CRASH_DELAY_MS}ms 后经代理重连到存活后端…`)
      setTimeout(() => {
        if (!exiting) {
          waitForServerPort()
        }
      }, RECONNECT_AFTER_CRASH_DELAY_MS)
      return
    }
    shutdown(0)
  })
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

waitForServerPort()
