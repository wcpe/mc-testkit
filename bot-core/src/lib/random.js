'use strict'

// 压测用确定性随机工具（照抄物，压测编排）。各 bot 用 seed ^ botIndex 播种，使行为可复现且互不相同。

// mulberry32：32 位种子的快速确定性 PRNG，返回 [0,1) 浮点。
function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// 按权重表 { name: weight } 用 rng 取一个 key；表空 / 权重和 ≤0 返回 null。
function weightedPick(rng, weights) {
  const entries = Object.entries(weights || {}).filter(([, w]) => w > 0)
  const total = entries.reduce((sum, [, w]) => sum + w, 0)
  if (total <= 0) {
    return null
  }
  let roll = rng() * total
  for (const [name, w] of entries) {
    roll -= w
    if (roll < 0) {
      return name
    }
  }
  return entries[entries.length - 1][0]
}

// 在 base 上下 jitter 抖出一个非负毫秒数。
function jitter(rng, baseMs, jitterMs) {
  const delta = (rng() * 2 - 1) * (jitterMs || 0)
  return Math.max(0, Math.round(baseMs + delta))
}

// Promise 化 sleep。
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

module.exports = { mulberry32, weightedPick, jitter, sleep }
