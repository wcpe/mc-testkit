# FR-35 driver 车道（通用进程驱动）

## 需求

消费方需要真客户端 / 任意外部命令参与端到端场景（calcite 真客户端打另一视角、自研压测器、
外部探针），替代各消费方自己写 Gradle 任务拉进程并自管生命周期（A 方案）。编排像管 bot
一样管 driver：拉起、监管、收尾、产物归档。

## 语义（验收判据）

- `scenario { driver { } }` / `driver("角色") { }`：多 driver 须唯一角色名；driver 与 bot
  可同时声明、独立起名独立收尾。
- 生命周期：就绪门前拉起（`launch<Key>Driver`，dependsOn prepare + beforeHooks）；场景
  结束（PASS / FAIL / 中断）必收尾——温和 destroy、`killTimeoutSeconds` 内未退升级强杀、
  杀不净判本次执行不可信；「完成即退出」（端口由开转关退 0）由 driver 自己兑现，非零提前
  退出按监管同口径毒化信任。中途某 driver 起失败时已起的先收掉再抛。
- 产物：`driver-<场景>-<角色|index>.log/.pid` 落结果目录、台账 `driver/<key>`、输出经同一
  输出预算计限额。key 必带场景名（多场景同构建不互覆盖）。
- 契约 env：`DRIVER_HOST/PORT/SCENARIO/BACKEND_NAME/ROLE/RESULTS_DIR` 注入，业务 env
  经 `env(name, value)` 透传且可覆盖契约项。
- `argsFromFile(path)`：构建期冻结模板（每行一个参数、空行/#注释忽略、读在拉起时刻）。
- `launchAfterReady = true`：分阶段拉起——不在就绪前起跑，verify 在后端端口可连后
  （与集群就绪门同源 awaitPortOpen，后端提前退出即早失败不空等）体内拉起；应用级就绪
  回执仍由 driver 脚本自重试。全 driver 均 afterReady 时不注册空 launch 任务。
- `recordEvidence = true`：注入 `…RECORD=1` + `…RECORD_DIR`（默认 `<结果目录>/recordings/`），
  后端桩 JVM 同透传；driver 用自己的捕获能力（calcite 截图 / xvfb·ffmpeg 可选链），
  JVM 套件可读 harness-core `McTestkitRecorder`。**可选降级硬规则**：依赖缺失 / 无显示 /
  未声明 / 开录失败 → 只告警返回 null，测试照常 PASS，无头环境正确跑，永不改判定。
- 聚合 `e2e<Key>WithDriver` = 本场景声明的全部驱动（bot launch + driver launch）+ verify。
- 范围：当前只支持直连单后端；via/cluster/stress 声明 driver 配置期中文报错。
- 配置期校验：command 空白、`killTimeoutSeconds <= 0`、角色名重复、生成任务名撞名
  （如 `login` + driver 与场景 `loginWithDriver`）一律中文报错，不静默忽略。

## 边界

- 编排管「进程从哪来、何时死、产物落哪」；「测什么、怎么判」（超时即使退 0 也拒过等
  语义判据）留给判定侧（桩结果文件 / 消费方报告门），框架不加判定逻辑。
- 服务端（headless）本身无可视画面：服务端侧录制指桩 JVM 所在进程可见显示源的
  ffmpeg 捕获（多用于消费方真客户端 JVM，经 driver 车道拉起时由其自身能力截图）。
