'use strict'

const { waitForMessage } = require('@wcpe/mc-testkit-bot/lib/messages')

// 崩溃接管 fallback 示例驱动（照抄物，刻意最薄，崩溃接管）。
//
// 只验**框架层** fallback 路由（默认后端宕机 → bot 经代理回退到存活后端），不含业务「租约 TTL 接管」：
//   ① 经代理落默认后端（集群代理 priorities 首个）→ 等桩就绪 E2E_READY
//   ② 发 E2E_TRIGGER_CRASH（template 约定）触发**当前所在**后端 halt 模拟宕机
//   ③ 默认后端宕机 → 连接断开 → 经代理重连，代理 fallback 到下一个存活后端（崩溃接管 priorities=全后端）
//   ④ 在存活后端再次 spawn → 等其就绪 → 发 E2E_CLUSTER_ARRIVED，存活桩判 PASS
// 真实「崩溃接管」业务（存活服在归属租约 TTL 过期后接管上线）由消费方在存活桩里查共享 DB 改判。
const TRIGGER_CRASH_MARKER = 'E2E_TRIGGER_CRASH'
const CLUSTER_ARRIVED_MARKER = 'E2E_CLUSTER_ARRIVED'

module.exports = async function runCrashTakeover(context) {
  const { bot, config, log, reconnectOnDisconnect } = context

  // ① 等默认后端桩就绪
  await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )
  log('已在默认后端就绪，准备触发其崩溃以验证经代理回退到存活后端')

  // ② 先登记「断线后重连」（默认后端崩溃会断开本连接），再发崩溃触发标记
  const reconnected = reconnectOnDisconnect()
  bot.chat(TRIGGER_CRASH_MARKER)
  log('已发送崩溃触发标记，等待默认后端宕机并经代理重连…')

  // ③ 等重连到存活后端（代理 fallback），拿到重连后的新 bot
  const survivorBot = await reconnected
  log('已重连并落到存活后端')

  // ④ 等存活后端桩就绪 → 发到达确认，存活桩判 PASS
  await waitForMessage(
    survivorBot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )
  survivorBot.chat(CLUSTER_ARRIVED_MARKER)
  log('已在存活后端发送到达确认，等待桩判定 / 关服')
}
