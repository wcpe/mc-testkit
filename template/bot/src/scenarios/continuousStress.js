'use strict'

const { waitForMessage } = require('../lib/messages')
const { mulberry32, jitter, sleep } = require('../lib/random')

// 持续压测示例驱动（照抄物，刻意最薄，FR-11）。
//
// 流程：spawn 进服（经代理 N-listener 钉死在本服，不切服）→ 等桩就绪信号 → 按 seed ^ botIndex
//       播种 RNG → 在 durationMs 内循环一个「假动作」（随机判成功 / 业务拒绝，演示 ok/err 分桶，
//       不连真实业务）→ 到时发累计摘要 E2E_STRESS_RESULT:ok=,err=,buckets= 给桩聚合。
// 真实压测把「假动作」替换为：经 UI/GUI 通道随机购买等业务操作，按返回码分桶；
// 不变量（不超卖）由桩查共享 DB 判，框架只收集 + 聚合（见 docs/specs/fr-31）。

// 假动作节奏（薄示例本地常量；真实场景按业务自定或经业务 env 透传）
const BUY_INTERVAL_MS = 50
const INTERVAL_JITTER_MS = 50

module.exports = async function runContinuousStress(context) {
  const { bot, config, log } = context

  // 1) 等桩就绪信号（对齐冻结控制协议 E2E_READY）
  await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )

  // 2) 按 botIndex ^ randomSeed 播种 RNG（各 bot 可复现且互异）
  const seed = (config.botIndex ^ config.randomSeed) >>> 0
  const rng = mulberry32(seed)
  log(`压测开始：botIndex=${config.botIndex} seed=${seed} durationMs=${config.durationMs}`)

  // 3) 主循环：durationMs 内反复跑一个假动作，按结果计数 ok / err（分桶）
  const stats = { ok: 0, err: 0, buckets: {} }
  const deadline = Date.now() + config.durationMs
  while (Date.now() < deadline) {
    // 假动作：约 85% 记成功，约 15% 记一种业务拒绝（演示分桶；真实场景换成业务操作 + 真实返回码）
    if (rng() < 0.85) {
      stats.ok += 1
    } else {
      stats.err += 1
      const code = rng() < 0.5 ? 'cooldown' : 'limit_exhausted'
      stats.buckets[code] = (stats.buckets[code] || 0) + 1
    }
    await sleep(jitter(rng, BUY_INTERVAL_MS, INTERVAL_JITTER_MS))
  }

  // 4) 上报累计摘要给桩聚合（冻结协议 E2E_STRESS_RESULT）
  const summary = `E2E_STRESS_RESULT:ok=${stats.ok},err=${stats.err},buckets=${JSON.stringify(stats.buckets)}`
  log(`压测结束：${summary}`)
  try {
    bot.chat(summary)
  } catch (error) {
    // 连接已断时无法发聊天，忽略
  }
  // 稍等让桩收到聊天后再结束驱动（之后保持在线，等桩到时聚合关服）
  await sleep(2000)
}
