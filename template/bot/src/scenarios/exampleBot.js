'use strict'

const { waitForMessage } = require('@wcpe/mc-testkit-bot/lib/messages')

// 机器人驱动示例场景（照抄物，刻意最薄）。
//
// 流程：进服 spawn 后 → 等桩下发的就绪信号 `E2E_READY:<action>` → 发一条聊天作为「驱动动作」→ 结束。
// 真实场景在这里替换为：开界面 / 点击槽位 / 发命令 / 经 UI 通道请求等，并由桩侧判定写结果文件。
// 注意：判 PASS/FAIL 是**桩**的职责（写结果文件，编排只认结果文件）；机器人只负责「像玩家一样操作」。
module.exports = async function runExampleBot(context) {
  const { bot, config, log } = context

  // 等待桩的就绪信号（对齐 mc-testkit 冻结控制协议 E2E_READY，docs/API.md §3.4）
  const readyMessage = await waitForMessage(
    bot,
    (text) => text.includes(`E2E_READY:${config.action}`),
    config.readyTimeoutMs,
    `E2E_READY:${config.action}`
  )
  log(`收到就绪信号: ${readyMessage}`)

  // 示例「驱动动作」：发一条聊天。真实场景把这里换成实际玩家操作。
  bot.chat('mc-testkit example bot says hello')
  log('已发送示例聊天，等待服务器侧判定 / 关服')
}
