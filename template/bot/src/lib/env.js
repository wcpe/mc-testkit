'use strict'

// 环境变量读取小工具（纯函数，可单测）。
// mc-testkit 约定所有机器人环境变量以 MC_TESTKIT_E2E_ 为前缀（docs/API.md §3.3）。

// 读取去空白后的环境变量；空串 / 未设置返回 null。
function optionalEnv(name) {
  const value = process.env[name]
  return value && value.trim() ? value.trim() : null
}

// 读取字符串环境变量，未设置时回落到默认值。
function envValue(name, defaultValue) {
  return optionalEnv(name) || defaultValue
}

// 读取整数环境变量；未设置或非法整数时回落到默认值。
function envInt(name, defaultValue) {
  const raw = optionalEnv(name)
  if (!raw) {
    return defaultValue
  }
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) ? parsed : defaultValue
}

module.exports = {
  optionalEnv,
  envValue,
  envInt
}
