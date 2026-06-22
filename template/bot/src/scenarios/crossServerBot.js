'use strict'

const { waitForMessage } = require('../lib/messages')

// 跨服集群示例驱动（照抄物，刻意最薄，FR-10）。
//
// 流程：spawn 落在首个后端 → 等桩就绪信号 → 按 MC_TESTKIT_E2E_CLUSTER_BACKENDS 顺序经代理
//       `/server <name>` 切到后续每个后端（切服后 mineflayer 会再次 spawn）→ 在最后一个服发
//       「切换确认标记」聊天 → 桩收到即判 PASS。
// 真实跨服场景把这里替换为：切服前后做业务操作 + 校验跨服数据一致，并由桩侧判定写结果文件。
const CLUSTER_ARRIVED_MARKER = 'E2E_CLUSTER_ARRIVED'

module.exports = async function runCrossServer(context) {
  const { bot, config, log } = context
  const backends = String(process.env.MC_TESTKIT_E2E_CLUSTER_BACKENDS || '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)

  // 等首服桩就绪信号（对齐冻结控制协议 E2E_READY）
  await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )
  log(`已在首服就绪；切换目标顺序: ${backends.join(' -> ') || '(未提供 CLUSTER_BACKENDS)'}`)

  // 首个后端是落地服，从第 2 个起依次 /server 切过去
  for (const target of backends.slice(1)) {
    const spawnedAgain = waitForNextSpawn(bot)
    bot.chat(`/server ${target}`)
    await spawnedAgain
    log(`已切到 ${target}`)
    // 切服后桩会再发就绪信号；稍等确保桩侧监听就绪
    await delay(1000)
  }

  // 在当前（最后一个）服发「切换确认标记」，触发桩判 PASS
  bot.chat(CLUSTER_ARRIVED_MARKER)
  log('已发送跨服切换确认标记，等待桩判定 / 关服')
  await delay(3000)
}

// 等下一次 spawn（经代理 /server 切服后 mineflayer 会再次触发 spawn）
function waitForNextSpawn(bot, timeoutMs = 60000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('spawn', onSpawn)
      reject(new Error('切服后等待 spawn 超时'))
    }, timeoutMs)
    function onSpawn() {
      clearTimeout(timer)
      resolve()
    }
    bot.once('spawn', onSpawn)
  })
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
