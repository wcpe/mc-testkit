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

// 一条原版 chat 帧的载荷上限（UTF-16 码元数）。超出后服务端在解码期就丢弃整包并断开玩家，
// 所以结论类消息必须在发出前按这个上限截断，否则表现为「机器人被踢，桩一直等到超时」。
const MAX_CHAT_CHARS = 256

// 安全地把 `前缀 + 明细` 发到聊天通道，超出单包上限时截断明细并显式标注。
//
// 为什么不静默截断：结论里带的是失败原因，读者需要知道「这里被截断了」，
// 否则会把一条不完整的原因当成全部原因。标注本身也计入上限，避免标注把它顶出去。
function sendChatSafely(bot, prefix, detail) {
  const marker = '…（超出聊天帧上限，已截断）'
  const budget = MAX_CHAT_CHARS - prefix.length
  if (budget <= 0) {
    // 前缀本身就不合法（不该发生），退回只发前缀，至少让桩能收到结论
    bot.chat(prefix)
    return prefix
  }
  const text = String(detail == null ? '' : detail)
  if (text.length <= budget) {
    const message = prefix + text
    bot.chat(message)
    return message
  }
  const keep = Math.max(0, budget - marker.length)
  const message = prefix + text.slice(0, keep) + marker
  bot.chat(message)
  return message
}

module.exports = {
  MAX_CHAT_CHARS,
  extractMessageText,
  sendChatSafely,
  waitForMessage
}
