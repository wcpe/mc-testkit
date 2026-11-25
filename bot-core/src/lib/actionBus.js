'use strict'

const fs = require('fs')

// 语义动作总线的 bot 侧写入器（证据链内核的跨语言一半）。
//
// 契约与 evidence-core 的 ActionBus 共享同一份 JSONL 格式（v=1）：
// 每行一个事件 {v,type,ts,suite,scenario,seq?,step?,status?,...}。
// 为什么允许跨语言两份写入器：JSONL 是字节级契约不是库契约——
// bot 跑在 node、桩跑在 JVM，强行共享一个实现反而要跨进程转发，
// 复杂度远高于各自append一行。格式的唯一真源在 evidence-core 的编译器：
// 它容忍合法 JSON 全部空白形态，两侧的写出都经同一编译器闭环验证。
//
// 降级纪律与 Java 侧一致：总线是证据增强不是测试本体，
// 未配置 MC_TESTKIT_ACTION_BUS_FILE 或写盘失败时静默降级，绝不翻转测试结论。

const FORMAT_VERSION = 1

function openBus(suite, scenario) {
  const file = process.env.MC_TESTKIT_ACTION_BUS_FILE
  if (!file) return null
  let stream
  try {
    stream = fs.createWriteStream(file, { flags: 'a' })
  } catch (e) {
    process.stderr.write(`[mc-testkit][action-bus] 总线开启失败（降级为无总线）：${e.message}\n`)
    return null
  }

  function emit(type, fields) {
    const line = JSON.stringify({
      v: FORMAT_VERSION,
      type,
      ts: Date.now(),
      suite,
      scenario,
      ...fields,
    })
    try {
      stream.write(line + '\n')
    } catch (e) {
      // 写盘失败不炸场景：证据链故障不翻转测试结论
    }
  }

  return {
    suiteStart(environment) {
      emit('suite-start', { environment })
    },
    step(seq, name, status, data) {
      emit('step', { seq, step: name, status, ...(data ? { data: JSON.stringify(data) } : {}) })
    },
    assertion(seq, stepName, passed, message, data) {
      emit('assertion', {
        seq,
        step: stepName,
        passed,
        message,
        ...(data ? { data: JSON.stringify(data) } : {}),
      })
    },
    artifact(seq, stepName, kind, path, videoStart, videoEnd) {
      emit('artifact', {
        seq,
        step: stepName,
        artifactKind: kind,
        path,
        ...(videoStart != null ? { videoStart } : {}),
        ...(videoEnd != null ? { videoEnd } : {}),
      })
    },
    suiteEnd(passed, message) {
      emit('suite-end', { passed, message })
    },
    close() {
      // 返回 Promise：等待底层流冲刷完成。调用方若不等待就 process.exit，
      // 末尾事件会丢失——套件收尾的 suite-end 恰恰是最重要的那条。
      return new Promise((resolve) => {
        try {
          stream.end(() => resolve())
        } catch (e) {
          resolve()
        }
      })
    },
  }
}

module.exports = { openBus }
