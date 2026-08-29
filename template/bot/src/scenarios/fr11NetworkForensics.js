'use strict'

const { sleep } = require('../lib/random')

const CHANNEL = 'serverprobe:test'
const BURST_COUNT = 24
const BURST_PAYLOAD_BYTES = 1024
const LARGE_PAYLOAD_BYTES = 24 * 1024
const PROXY_PIPELINE_READY_MS = 20_000

/**
 * 发送处于协议单包上限内的 Plugin Message；大载荷用于验证服务端前缀截断和完整 SHA-256。
 */
function sendFr11PluginMessages(client) {
  for (let index = 0; index < BURST_COUNT; index += 1) {
    client.write('custom_payload', {
      channel: CHANNEL,
      data: Buffer.alloc(BURST_PAYLOAD_BYTES, index)
    })
  }
  client.write('custom_payload', {
    channel: CHANNEL,
    data: Buffer.alloc(LARGE_PAYLOAD_BYTES, 0x5a)
  })
}

/**
 * 等代理建立后端连接并让监听器完成管线附着后，先断连重连，再在第二条真实连接上发送验收流量。
 */
module.exports = async function runFr11NetworkForensics({ bot, log, reconnectOnDisconnect }) {
  const client = bot._client
  if (!client || typeof client.write !== 'function') {
    throw new Error('Mineflayer 未暴露协议客户端，无法发送 FR11 Plugin Message')
  }

  await sleep(PROXY_PIPELINE_READY_MS)
  client.write('custom_payload', {
    channel: CHANNEL,
    data: Buffer.from([0x01])
  })
  log('首轮连接已发送合法 Plugin Message，开始断连重连')

  const reconnected = reconnectOnDisconnect()
  bot.quit('FR11 网络取证重连验证')
  const secondBot = await reconnected
  const secondClient = secondBot._client
  if (!secondClient || typeof secondClient.write !== 'function') {
    throw new Error('重连后 Mineflayer 未暴露协议客户端，无法发送 FR11 Plugin Message')
  }

  await sleep(PROXY_PIPELINE_READY_MS)
  sendFr11PluginMessages(secondClient)
  log(
    `重连后已发送 ${BURST_COUNT} 个 ${BURST_PAYLOAD_BYTES} 字节 Plugin Message 与 ${LARGE_PAYLOAD_BYTES} 字节截断验收载荷`
  )
}

module.exports.sendFr11PluginMessages = sendFr11PluginMessages
