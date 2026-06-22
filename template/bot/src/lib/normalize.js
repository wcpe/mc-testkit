'use strict'

// 文本归一工具（纯函数，可单测）：把 Minecraft 富文本 / 颜色码归一成可比较的纯文本。
// 控制消息（如 E2E_READY:smoke）经服务端聊天通道下发时可能带富文本结构与 §颜色码，
// 机器人侧统一归一后再做 includes 匹配，避免漏判。

// 递归把 mineflayer 的富文本消息对象抽成可读字符串。
function extractRenderableText(value, seen = new Set()) {
  if (value == null) {
    return ''
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  if (typeof value !== 'object') {
    return String(value)
  }
  if (seen.has(value)) {
    return ''
  }
  seen.add(value)

  // 优先用对象自带的 toString（mineflayer ChatMessage 实现了它）
  if (typeof value.toString === 'function' && value.toString !== Object.prototype.toString) {
    try {
      const rendered = value.toString()
      if (rendered && rendered !== '[object Object]') {
        return rendered
      }
    } catch (error) {
      // 忽略，转下方结构化抽取
    }
  }

  const parts = []
  if (typeof value.text === 'string') {
    parts.push(value.text)
  } else if (value.text) {
    parts.push(extractRenderableText(value.text, seen))
  }
  if (typeof value.translate === 'string') {
    parts.push(value.translate)
  }
  if (Array.isArray(value.extra)) {
    parts.push(value.extra.map((entry) => extractRenderableText(entry, seen)).join(' '))
  }
  if (Array.isArray(value.with)) {
    parts.push(value.with.map((entry) => extractRenderableText(entry, seen)).join(' '))
  }

  const combined = parts.filter(Boolean).join(' ')
  return combined || String(value)
}

// 去掉 §x 颜色 / 格式码。
function stripMinecraftFormatting(text) {
  return extractRenderableText(text).replace(/§[0-9A-FK-OR]/gi, '')
}

// 归一：抽文本 → 去颜色码 → 压缩空白 → 去首尾空白。
function normalizeText(text) {
  return stripMinecraftFormatting(text).replace(/\s+/g, ' ').trim()
}

// 把 kick / end 等回调的 reason 渲染成可读字符串（便于日志）。
function formatReason(reason) {
  if (reason == null) {
    return 'unknown'
  }
  if (typeof reason === 'string') {
    return reason
  }
  try {
    return JSON.stringify(reason)
  } catch (error) {
    return reason?.message || String(reason)
  }
}

module.exports = {
  extractRenderableText,
  stripMinecraftFormatting,
  normalizeText,
  formatReason
}
