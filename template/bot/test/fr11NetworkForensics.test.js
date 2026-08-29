'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { sendFr11PluginMessages } = require('../src/scenarios/fr11NetworkForensics')

test('FR11 机器人发送合法 Plugin Message 突发及截断载荷', () => {
  const writes = []
  sendFr11PluginMessages({
    write: (packetType, payload) => writes.push({ packetType, payload })
  })

  assert.equal(writes.length, 25)
  assert.ok(writes.every(({ packetType }) => packetType === 'custom_payload'))
  assert.ok(writes.every(({ payload }) => payload.channel === 'serverprobe:test'))
  assert.equal(writes.filter(({ payload }) => payload.data.length === 1024).length, 24)
  assert.equal(writes.at(-1).payload.data.length, 24 * 1024)
  assert.ok(writes.every(({ payload }) => payload.data.length <= 32767))
})
