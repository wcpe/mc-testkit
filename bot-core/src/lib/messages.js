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
//
// 截断的硬约束：**返回的整包长度永不超过 MAX_CHAT_CHARS**。这一条不能用「预算减标注」
// 应付——前缀长到让标注都放不下时，那种算法会得出「保留 0 字符 + 完整标注」，
// 结果比预算还长，服务端照样断开玩家，等于截断失效。所以要按剩余空间逐级降级：
// 放得下标注 → 放得下缩短的标注 → 实在放不下就宁可只发前缀。
function sendChatSafely(bot, prefix, detail) {
  const marker = '…（超出聊天帧上限，已截断）'
  // 前缀自身超限：发出去必然被服务端断开，绝不能原样送出。截到上限内保住结论通道。
  const head = String(prefix == null ? '' : prefix)
  if (head.length >= MAX_CHAT_CHARS) {
    const clipped = head.slice(0, MAX_CHAT_CHARS)
    bot.chat(clipped)
    return clipped
  }
  const budget = MAX_CHAT_CHARS - head.length
  const text = String(detail == null ? '' : detail)
  if (text.length <= budget) {
    const message = head + text
    bot.chat(message)
    return message
  }
  // 明细放不下：优先保住标注（读者必须知道原因不完整）。
  if (marker.length < budget) {
    const keep = budget - marker.length
    const message = head + text.slice(0, keep) + marker
    bot.chat(message)
    return message
  }
  // 连完整标注都放不下：缩短标注，至少让「这里有截断」这件事可见。
  const shortMarker = '…'
  if (shortMarker.length < budget) {
    const keep = budget - shortMarker.length
    const message = head + text.slice(0, keep) + shortMarker
    bot.chat(message)
    return message
  }
  // 预算连一个字符的明细都放不下（前缀几乎顶满上限）：只发前缀。
  bot.chat(head)
  return head
}

module.exports = {
  MAX_CHAT_CHARS,
  extractMessageText,
  sendChatSafely,
  waitForMessage
}
