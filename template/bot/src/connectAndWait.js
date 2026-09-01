'use strict'

// mc-testkit E2E 机器人入口（照抄物）。
// 机器人内核（端口探测 / 重连 / action 分发 / 优雅收尾）来自共享包 @wcpe/mc-testkit-bot（共享胶水构件），
// 本文件只做「场景 action → 驱动函数」登记并启动内核；业务场景驱动留在 ./scenarios。

const { runBot } = require('@wcpe/mc-testkit-bot')
const runExampleBot = require('./scenarios/exampleBot')
const runCrossServer = require('./scenarios/crossServerBot')
const runContinuousStress = require('./scenarios/continuousStress')
const runMultiBot = require('./scenarios/multiBot')
const runCrashTakeover = require('./scenarios/crashTakeover')
const runNetworkForensics = require('./scenarios/networkForensics')

runBot({
  scenarios: {
    'example-bot': runExampleBot,
    'cross-server': runCrossServer,
    'continuous-stress': runContinuousStress,
    'multi-bot': runMultiBot,
    'crash-takeover': runCrashTakeover,
    'network-forensics': runNetworkForensics
  }
})
