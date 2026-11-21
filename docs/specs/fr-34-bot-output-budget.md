# 功能规格：bot 与钩子进程的输出预算，及结果目录的输出所有权

> 状态：已交付@v0.18.0　·　关联 PRD：FR-34　·　关联决策：[ADR-0029](../adr/0029-bot-output-budget-and-run-ownership.md)　·　分支：feature/output-budget-timeout-run-dir　·　相关：FR-32（监管期限）

## 1. 背景与目标

bot 与场景钩子后台进程的输出原先用 `Redirect.appendTo(File)` 交给内核直接写文件，JVM 不经手，既没法计数也没法中断。一次本地事故里 9 个 bot 日志合计写到 130 GiB，直到磁盘写满才停。后台进程的收尾又只靠 pid 文件：构建结束时仍在跑的进程、根进程退出后仍握着输出管道的子孙进程，都可能在构建之外继续写结果目录；结果目录跨构建复用，下一次构建照常接手就会和它们并发写同一目录。

**目标**：一次构建里 bot 与钩子进程写盘的总量有硬上限；本构建起的进程都在监管下、构建结束前收干净；结果目录只在上一次构建可能仍有进程在写时拒绝接手，且 stop 任务能跨构建收掉残留进程、让后续构建自己恢复。属 P1：事故形态是写满磁盘。

## 2. 需求（要什么）

**字节预算**

- bot 与钩子后台进程的 stdout / stderr 合流走管道，由插件内的泵按字节计数后写日志。
- 单个日志到上限（默认 64 MiB）只截断这一个日志：末尾写一行 `[OUTPUT_LIMIT] limit=per-sink sink=<日志>`，之后的输出照常读出来丢掉（进程不会卡在写满的管道上），构建结束时告警有几个日志被截断；不判失败、不停任何进程。
- 整次构建到上限（默认 1 GiB）写 `[OUTPUT_LIMIT] limit=run sink=<触发者>`，判失败并停掉全部受监管进程，桩或导演已判 PASS 也一样。
- 两道上限可经系统属性或 `MC_TESTKIT_E2E_` 环境变量调整，上界 16 GiB；单日志上限不得大于整次构建上限，整次构建上限至少 32 KiB；非法值使任务失败，不退回默认。
- 日志个数与受监管进程数不设固定上限：压测一次起数百个 bot 是正当用法，磁盘由字节上限兜底。
- 钩子拿不到预算时拒绝启动外部命令，不退回无上限写盘。

**进程监管**

- 判失败的情形：整次构建超限、非零退出、超过监管期限（由 FR-32 的结果等待上限推出）、根进程退出后子孙进程仍在或仍握着输出管道、构建结束时仍未收尾、停止 / 收尾期限内无法确认退出、写日志失败、进程启动失败、结果目录拒绝接手。失败是粘性的、先到先得，原因收敛成有界 ASCII 文本，任务报 `RUN_FAIL: <原因>`。
- 本次构建已判失败后，普通进程拒绝启动；场景后钩子起的收尾进程照样启动、照样计入预算与监管，收尾不因前面的失败而做不成。

**结果目录的输出所有权**

- 每个结果目录一份 `output-run.state`：接手时写 `RUNNING` 与跑这次构建的 JVM（pid + 启动时刻），收尾时写 `CLOSED_OK` / `RUN_FAIL`、字节数、失败原因与收尾期限到时仍存活的进程（pid + 启动时刻）。
- 只在上一次构建可能仍有进程在写时拒绝接手并保留记录；已确认收尾干净的失败只告警，不连坐后续构建。没收尾就结束的构建（持有者已结束或就是当前 JVM）在进程台账里没有留下仍存活的 bot 与钩子进程时同样只告警、照常接手；持有者还在跑时拒绝并提示等它结束。拒绝时的报错按情形给出可操作的恢复步骤（跑 stop 任务、等持有者结束、确认后删记录）。
- 只有起进程、读判定的任务接手结果目录；`stopE2eProcesses`、`stop<场景>Cluster`、`stop<场景>Stress`、`stop<名字>Serve`、场景后钩子等停止与清理类任务不接手、不受这道检查拦截。

**跨构建收尾**

- bot 与钩子进程起来时登记进程台账（`bot/<日志 key>`、`hook/<pid 文件相对结果目录的路径>`），与服务端 / 代理同一本台账。
- stop 任务先让进程监管停掉本次构建起的 bot，再按 pid 文件收服务端与代理，再按台账核对身份后收掉之前的构建留下的进程（连同子孙进程）；本次构建仍在监管的进程留给监管。最后收掉 `RUN_FAIL` 记录里收尾时没停下、pid 与启动时刻都对得上的进程，之后的构建据此自动接手。
- 场景与 serve 收尾只停本次构建起的 bot，不因别的 bot 判失败；`StopPidHook` 遇到不是本次构建起的进程时按台账核对后收掉，对不上的不碰也不抛错。
- 固定名任务 `stopE2eProcesses` 不论声明了哪些拓扑都注册（只有直连场景的工程也有 stop 任务可用）：只按台账与状态记录核对身份后收进程，不按 pid 文件停，不碰本次构建仍在监管的进程；同一条命令里的场景、钩子与 serve 任务都排在它之后。直连场景端口被占、结果目录拒绝接手时的报错指向它。
- 直连场景的前台服务端起来后登记台账（`backend/<名字>`），等结果时正常结束、超时、出错或任务被取消，都连同子进程收掉并删掉 pid 文件。

**范围外**：服务端日志（仍由服务端进程自己写文件，是已知缺口，另行处理）；自动接手旧版本写下的、没有持有者的 `RUNNING` 记录与 `live=unknown` 记录（无法确认没有进程在写，仍需人工确认后删除）。

## 3. 设计（怎么做）

决策与理由见 [ADR-0029](../adr/0029-bot-output-budget-and-run-ownership.md)，契约见 [`API.md`](../API.md) §3.7，此处只记落地面。

- **`bot/OutputBudget`**：两道上限的计数全用原子操作（CAS 预占额度），写盘不持有任何共享锁，某个日志写盘卡住不会拖住判失败与停进程；整次构建的额度里留出一小块给各日志的标记行。`BoundedProcessOutputPump` 一直读到管道 EOF，截断或写盘失败后只是不再写。
- **`bot/RunOutputSupervisor`**：只管本构建登记的进程。监控线程每 100 毫秒扫一遍本 JVM 的进程树，沿父进程链把子孙进程归到各自的登记进程下（不对每个登记进程各扫一遍，数百个 bot 时不会吃满一个核）。负责到期判 TIMEOUT、按需停止（先温和、200 毫秒后强杀）、构建结束时的收尾确认与存活列表；`owns(pid)` 供 stop 任务判断某个台账记录是否仍归本构建监管。进程身份交给 `ProcessHandle`（记着启动时刻，pid 被复用时不会误判、误杀）。
- **`task/RunOutputService`**（Gradle BuildService）：每个顶层构建一份，持有预算与监管器。起进程的任务经 `registerTask(..., ownsRunOutput = true)` 在 doFirst 里 `attach(resultsDir)`（写 `RUNNING` 时带上 `ProcessIdentity.current()`；上一次停在 `RUNNING` 且持有者已结束时，才按台账查仍存活、不归本构建监管的 `bot/`、`hook/` 记录），并在任务前后各调一次 `checkRunHealthy()`；停止与清理类任务用 `ownsRunOutput = false`，只拿同一份预算（`runBudget()`）、不接手。`close()` 收尾监管器、告警截断、写状态文件，有失败则让构建失败。
- **`task/RunOutputState`**：状态文件的格式、接手判定（纯函数，进程是否存活、是否当前 JVM、台账里的存活写者都可注入）、被拒后的恢复办法（`Remedy`：`STOP_PROCESSES` / `WAIT_OWNER` / `DELETE_STATE`）与 `survivors()`。
- **跨构建收尾**：`BotLauncher` 与 `ExecHook` 起进程后登记 `bot/ProcessLedger`（台账读写在本 JVM 内互斥）；`task/ProcessStopper.stopTrackedProcesses(..., owned)` 跳过本构建仍在监管的进程，核对身份后用 `bot/ProcessReaper.stopProcessTree` 连同子孙进程一起收（子孙进程在根进程退出前记下），再收状态文件记录的存活进程；`dsl/ScenarioHookRuntime.stopByPidFile` 先交监管，再按台账核对。`McTestkitTasks.registerStopProcessesTask` 注册 `stopE2eProcesses`，只调 `stopTrackedProcesses`；`orderRuntimeTasksAfterStopProcesses` 让场景、钩子与 serve 任务 `mustRunAfter` 它；`runBackendForeground` 起后端后登记 `backend/<名字>`，等待包在 `try/finally` 里收尾。

## 4. 任务拆分

- [x] ADR-0029
- [x] 管道 + 有界泵 + 两级预算（单日志截断、整次构建判失败），配置项与校验
- [x] 进程监管器：单次扫描归属子孙进程、期限、停止、构建结束收尾与存活列表
- [x] 构建级共享服务与结果目录输出所有权；拒绝时的恢复指引
- [x] 停止与清理类任务不接手结果目录；场景后钩子的收尾进程在失败后仍可启动
- [x] bot 与钩子进程登记进程台账；stop 任务跨构建收尾与收掉状态文件记录的存活进程
- [x] `RUNNING` 记下持有者：持有者存活时等待；已结束且台账里没有存活的 bot 与钩子进程时告警后接手，有则拒绝并指向 stop 任务；拒绝提示按恢复办法区分
- [x] 固定名 `stopE2eProcesses`，同一条命令里的场景与 serve 排在它之后；直连场景的前台服务端登记台账、取消或出错时收掉
- [x] 文档同步：PRD（FR-34 行）、API §3.2 任务表与 §3.7、ARCHITECTURE §5、OPERATIONS、CHANGELOG
- [x] 真机验收（见 §5）

## 5. 验收标准

- **[自动]** 单日志超限只截断：文件不超上限、写出 `per-sink` 标记、子进程自己跑完、构建不判失败，其他日志照常写（`OutputBudgetTest`、`RunOutputGuardTest`）。
- **[自动]** 多个日志合计超过整次构建上限即判失败并点名触发者；不同构建的预算互不影响；后启动的进程不重置已用额度（`OutputBudgetTest`、`RunOutputGuardTest`）。
- **[自动]** 日志数与进程数不设上限：数百个日志、数百个登记进程都不失败，收尾确认全部结束；某个日志写盘卡住时判失败与查用量不被拖住（`OutputBudgetTest`、`RunOutputGuardTest`）。
- **[自动]** 非零退出、子孙进程握着管道、构建结束仍未收尾都判失败，导演写出的 PASS 盖不过（`RunOutputGuardTest`、`RunOutputServiceFunctionalTest`）。
- **[自动]** 已判失败后普通钩子进程拒绝启动，收尾钩子进程照样启动并落盘（`RunOutputGuardTest`）。
- **[自动]** 接手规则各分支（旧格式、`live=unknown`、所列进程仍存活、`RUNNING` 的持有者存活 / 已结束 / 就是当前 JVM、台账里有无存活写者、持有者缺失或读不懂、超长、CRLF）与各自的恢复办法（`RunOutputStateTest`）；真实 TestKit 构建：两次钩子输出合计越过构建上限时导演判 PASS 也失败、下一次构建照常接手；旧版本写下的、没有持有者的 `RUNNING` 持续拒绝且不被改写，删掉后恢复；同样的记录下 stop 任务照常执行、不改写记录；持有者仍存活时拒绝并提示等它结束，持有者退出后下一次构建自己接手；持有者已退出而台账里还有它的 bot 时拒绝、报错指向 `stopE2eProcesses` 并列出该 bot，跑完后下一次构建自己接手（`RunOutputServiceFunctionalTest`）。
- **[自动]** 跨构建收尾：台账里之前的构建留下的 bot 与状态文件记录的存活进程被 stop 任务收掉、下一次构建自己接手；只有直连场景的工程里，拒绝接手时报错指向 `stopE2eProcesses`，它收掉之前的构建留下的后端、bot 与没停下的进程后下一次构建自己接手（`RunOutputServiceFunctionalTest`）；`stopE2eProcesses` 始终注册，与场景写在同一条命令里时不论顺序都排在场景之前（`TaskOrchestrationFunctionalTest`）；直连场景跑完后后端登记在台账里、pid 文件已删（`DirectBackendProcessFunctionalTest`）；钩子进程连同子孙进程一起收、本构建仍在监管的进程不按台账另杀、启动时刻对不上的同 pid 进程不杀（`ProcessStopperTest`）；`StopPidHook` 按台账收掉之前的构建留下的进程、不碰未登记的进程（`ScenarioHooksTest`）；并发登记不丢记录（`ProcessLedgerTest`）。
- **[x] 真机验收通过（v0.18.0）**  集成验收工程连跑长矩阵：默认上限下正常负载不出现 `OUTPUT_LIMIT limit=run` 失败，一格失败不连坐后续各格，结束后没有残留的 bot 与钩子进程。
- **[x] 真机验收通过（v0.18.0）**  跑场景时强杀 Gradle 守护进程：台账里还有存活的 bot 或钩子进程时，下一次构建被拒并指向 `stopE2eProcesses`；跑完后服务端、bot 与钩子进程都被收掉，下一次构建自己接手，不需要手工删状态文件。

## 6. 风险 / 待定

- 服务端日志不在预算内，是已知缺口。
- `RUNNING` 的持有者靠 JVM 的 pid 与启动时刻识别；取不到启动时刻的平台写不出持有者，这样的记录与旧版本写下的 `RUNNING` 一样只能人工确认后删除，删除前先跑一遍 `stopE2eProcesses` 即可。
- 接手判定只查台账里的 bot 与钩子进程；残留的服务端与代理占着端口，由起服前的端口预检拦下。
- 进程台账是结果目录里的一个 properties 文件，互斥只在本 JVM 内；两个 Gradle 守护进程同时用同一个结果目录时，后来者会被输出所有权拒绝，台账不再另加跨进程锁。
- 默认值按实测负载定：单个 bot 长跑 10 分钟写约 20 MB；输出量远超此的消费方需调高上限。
