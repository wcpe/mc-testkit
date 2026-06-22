'use strict'

const { normalizeText } = require('./normalize')

// 服务端聊天消息提取与按条件等待。
// 桩经聊天通道下发控制消息（mc-testkit 冻结控制协议，docs/API.md §3.4），
// 机器人侧用这里的工具等待并匹配，例如等 `E2E_READY:<scenario>`。

// 把一条原始消息对象提取并归一成纯文本。
function extractMessageText(jsonMessage) {
  const raw = jsonMessage?.toString?.() || ''
  return normalizeText(raw)
}

// 等待一条满足 predicate 的消息；先扫历史，再监听后续，超时 reject。
// bot.__e2eMessageHistory 由入口在 'message' 事件里维护（最近若干条）。
function waitForMessage(bot, predicate, timeoutMs, label) {
  const history = Array.isArray(bot.__e2eMessageHistory) ? bot.__e2eMessageHistory : []
  const matchedHistory = history.find((text) => predicate(text))
  if (matchedHistory) {
    return Promise.resolve(matchedHistory)
  }

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.off('message', onMessage)
      reject(new Error(`等待消息超时: ${label}`))
    }, timeoutMs)

    function onMessage(jsonMessage) {
      const text = extractMessageText(jsonMessage)
      if (!text || !predicate(text)) {
        return
      }
      clearTimeout(timer)
      bot.off('message', onMessage)
      resolve(text)
    }

    bot.on('message', onMessage)
  })
}

module.exports = {
  extractMessageText,
  waitForMessage
}
