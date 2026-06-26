'use strict'

const { waitForMessage } = require('../lib/messages')

// 单场景多 bot 示例驱动（照抄物，刻意最薄，FR-16）。
//
// 多个 bot 进程（各唯一 username、经 BOT_INDEX 区分）直连同一后端入服；每个进程等桩就绪信号后
// 报一条到（便于日志 / 观测）即保持在线，由桩在 settle 窗口末聚合各 bot username 判定并关服。
// 真实多 bot 场景（如管理 GUI admin/target 双角色、N 玩家并发）把这里换成各自业务操作，
// 由桩按 username / index 判定写结果文件。
module.exports = async function runMultiBot(context) {
  const { bot, config, log } = context

  // 等待桩的就绪信号（对齐冻结控制协议 E2E_READY，docs/API.md §3.4）
  await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )
  // 桩按「入服玩家名」聚合即可，无需额外控制消息；这里只报一条到便于日志观测，然后保持在线等桩聚合关服
  log(`多 bot 报到：username=${config.username} index=${config.botIndex}`)
}
