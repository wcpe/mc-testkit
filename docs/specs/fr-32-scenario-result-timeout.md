# 功能规格：场景级结果等待上限（`resultTimeoutSeconds`）

> 状态：开发中　·　关联 PRD：FR-32　·　分支：feature/output-budget-timeout-run-dir　·　相关：FR-34（场景进程的监管期限由本上限推出）

## 1. 背景与目标

编排以「桩写出结果文件」为场景完成信号。等它的上限此前写死为 600 秒（`McTestkitTasks` 里的私有常量，直连 / 经代理 / 集群共用，压测在 `durationSeconds` 之外再加它），消费方没有任何办法调整。场景本身就要跑 10 分钟以上时（长时间负载、长观测窗口），任务会在场景跑完前被强制结束，报「桩可能未正常收尾 / 启动失败」，报错与真实原因对不上。

**目标**：按场景声明这个上限，未声明的场景行为不变。属 P1：真实消费方的长时间性能场景被它直接阻断。

## 2. 需求（要什么）

- `scenario("x") { resultTimeoutSeconds = N }`，单位秒，默认 600（`McTestkitDefaults.RESULT_TIMEOUT_SECONDS`，与此前写死的值相同）。
- 配置期校验 N > 0，否则中文报错（单后端、集群、压测场景一视同仁）。
- 各场景形态通用：直连、经代理、集群场景到点仍无结果即强制结束节点并判失败；压测场景先等 `stress.durationSeconds`，再额外等 N。
- 超时报错带出实际上限，并提示场景本身要跑更久时调大 `resultTimeoutSeconds`。
- 场景 bot 与场景钩子后台进程的监管期限（FR-34）由同一上限推出，长场景不会在中途被判 TIMEOUT。
- 范围外：serve（不判定，没有结果等待）；全局覆盖开关（长跑是个别场景的属性，全局调大会让其余场景卡死时也要等那么久才失败）。

## 3. 设计（怎么做）

- **DSL 与校验**：`ScenarioSpec.resultTimeoutSeconds: Long`，默认取 `McTestkitDefaults.RESULT_TIMEOUT_SECONDS`；`TopologyResolver` 在拓扑解析时校验 >0。
- **编排**：`McTestkitTasks` 去掉私有常量，直连前台路径（`runBackendForeground`）、经代理与集群的结果轮询（`awaitClusterResult`）、压测的时长外宽限都改读场景的值，超时报错里写出实际上限。
- **监管期限**：`scenarioProcessLifetimeMs(scenario)` = `resultTimeoutSeconds`（压测再加 `stress.durationSeconds`）+ 600 秒宽限（`PROCESS_LIFETIME_SLACK_SECONDS`，覆盖起服、钩子与收尾），封顶 7 天（`RunOutputSupervisor.MAX_LIFETIME_MS`）。它只是兜底：编排自己的等待先到期并收尾，监管期限必须更长，否则会抢先把正常运行的 bot 判成 TIMEOUT。serve 人机混场的 bot 不设期限，随 serve 或构建结束收尾。
- 契约见 [`API.md`](../API.md) §3.1。

## 4. 任务拆分

- [x] DSL 字段与默认值常量，拓扑解析期校验（单后端 / 集群 / 压测）
- [x] 四种场景形态的等待改读场景值，超时报错带出上限
- [x] 场景 bot / 钩子进程的监管期限由本上限推出（与 FR-34 同批）
- [x] 文档同步：PRD（FR-32 行）、API §3.1、ARCHITECTURE §5、CHANGELOG

## 5. 验收标准

- **[自动]** 配置期：`resultTimeoutSeconds` 为 0 或负数时单后端 / 集群 / 压测场景都以中文错误拒绝；未声明时取 600（`ScenarioResultTimeoutValidationTest`）。
- **[自动]** 回归锚点：TestKit 用一个只占端口、不写结果的探针后端跑直连 `e2e<Key>`，场景声明 3 秒上限，任务很快失败并报出「在 3s 内未写出结果文件」；编排若仍按 600 秒等，用例因耗时断言失败（`DirectE2eResultTimeoutFunctionalTest`）。
- **[自动]** 监管期限：普通场景为「上限 + 600 秒」，压测另加时长，超大值封顶 7 天（`ScenarioProcessLifetimeTest`）。
- **[真机，需用户确认]** 消费方的长时间场景（场景全程超过 10 分钟）声明足够的上限后跑完并判定，不再在第 600 秒被截断。

## 6. 风险 / 待定

- 上限给得过大时，桩真的卡住也要等到上限才失败；这是按场景声明、不提供全局开关的原因。
- 监管期限封顶 7 天：更长的声明在监管侧按 7 天算，编排侧仍按声明值等。
