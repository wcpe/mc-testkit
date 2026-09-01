'use strict'

// bot-core 公共工具单元测试（node:test，纯函数，不打网络）。

const test = require('node:test')
const assert = require('node:assert/strict')

const { optionalEnv, envValue, envInt } = require('../src/lib/env')
const { normalizeText, stripMinecraftFormatting, formatReason } = require('../src/lib/normalize')
const { mulberry32, weightedPick, jitter } = require('../src/lib/random')

test('envValue 读取去空白环境变量', () => {
  process.env.MC_TESTKIT_E2E_TEST_STRING = '  hello  '
  assert.equal(envValue('MC_TESTKIT_E2E_TEST_STRING', 'fallback'), 'hello')
  assert.equal(envValue('MC_TESTKIT_E2E_TEST_UNSET', 'fallback'), 'fallback')
  delete process.env.MC_TESTKIT_E2E_TEST_STRING
})

test('optionalEnv 对空串 / 空白返回 null', () => {
  process.env.MC_TESTKIT_E2E_TEST_BLANK = '   '
  assert.equal(optionalEnv('MC_TESTKIT_E2E_TEST_BLANK'), null)
  assert.equal(optionalEnv('MC_TESTKIT_E2E_TEST_UNSET'), null)
  delete process.env.MC_TESTKIT_E2E_TEST_BLANK
})

test('envInt 读取整数并回退默认', () => {
  process.env.MC_TESTKIT_E2E_TEST_INT = '42'
  process.env.MC_TESTKIT_E2E_TEST_BAD = 'not-a-number'
  assert.equal(envInt('MC_TESTKIT_E2E_TEST_INT', 7), 42)
  assert.equal(envInt('MC_TESTKIT_E2E_TEST_BAD', 7), 7, '非法整数应回退默认')
  assert.equal(envInt('MC_TESTKIT_E2E_TEST_UNSET', 7), 7)
  delete process.env.MC_TESTKIT_E2E_TEST_INT
  delete process.env.MC_TESTKIT_E2E_TEST_BAD
})

test('normalizeText 去颜色码并压缩空白', () => {
  assert.equal(normalizeText('§aHello §bWorld'), 'Hello World')
  assert.equal(normalizeText('  多   个   空格  '), '多 个 空格')
  assert.equal(stripMinecraftFormatting('§a§b§craw'), 'raw')
  assert.equal(stripMinecraftFormatting('§7§l粗体灰§r后'), '粗体灰后')
})

test('formatReason 渲染对象 / null', () => {
  assert.equal(formatReason(null), 'unknown')
  assert.equal(formatReason('kicked'), 'kicked')
  assert.ok(formatReason({ message: 'boom' }).includes('boom'))
})

test('mulberry32 确定性且可复现', () => {
  const first = []
  const rng = mulberry32(12345)
  for (let i = 0; i < 5; i += 1) {
    first.push(rng())
  }
  const rng2 = mulberry32(12345)
  for (let i = 0; i < 5; i += 1) {
    assert.equal(rng2(), first[i], '同种子序列必须一致')
  }
})

test('weightedPick 按权重取 key，空表返回 null', () => {
  const rng = mulberry32(1)
  assert.equal(weightedPick(rng, {}), null)
  assert.equal(weightedPick(rng, { a: 0 }), null)
  assert.equal(weightedPick(rng, { a: 1 }), 'a')
})

test('jitter 抖动结果非负', () => {
  const rng = mulberry32(9)
  assert.ok(jitter(rng, 100, 50) >= 0)
  assert.ok(jitter(rng, 0, 0) >= 0)
})
