# 功能规格：机器人驱动 + 结果判定（Gradle 侧）

> 状态：已交付@v0.1.0　·　关联 PRD：FR-06

## 1. 背景与目标

一次 E2E 场景需要一个 mineflayer 机器人（Node 程序）模拟真实玩家入服驱动场景，桩跑完把结论写进 `<scenario>.properties` 结果文件，编排再读这个文件判 PASS/FAIL。本规格落地这件事的 **Gradle 侧**：把机器人作为后台子进程拉起、写 pid 文件供收尾、按环境契约构建机器人环境变量；以及读结果文件判定。属第一期（MVP）FR-06。

机器人内核本身（`connectAndWait.js` 等 Node 代码）属 `template/`（FR-07），不在本规格；本规格只负责「在 Gradle 侧启动它、收尾它、读它写出的结果」。

## 2. 需求（要什么）

- 范围内：
  - **机器人进程启动**：用平台正确的 `node` 可执行 + 机器人入口脚本路径，作为后台进程拉起 mineflayer 机器人；日志重定向到结果目录、pid 落盘。
  - **机器人环境变量构建**：按 `McTestkitEnv` 名（前缀 `MC_TESTKIT_E2E_`）组装机器人连接 / 超时 / 协议项（host / port / username / auth / version / connect / retry / ready），可被消费方同名环境变量覆盖，无业务特定项（无项目专属业务前缀）。
  - **进程收尾**：按 pid 文件温和退出 → 超时强杀；pid 文件缺失 / 进程已退出时安全 no-op；跨平台（Windows / Linux）。
  - **结果判定**：读 `<scenario>.properties`（`McTestkitResultFile` 文件名 / 键），文件缺失或 `status≠PASS` 抛**中文**错误，否则返回结论（含 `message`）。
- 不做（范围外）：
  - 不注册任何 Gradle 任务（FR-04 整合器负责把本包能力接成 `launch<Key>Bot` / `e2e<Key>` 等任务）。
  - 不实现机器人 Node 内核、控制协议的 Node 侧逻辑（FR-07 `template/`）。
  - 不下载 / 运行服务端或代理（FR-02 自实现下载/运行），不做拓扑解析（FR-03）、环境契约写盘（FR-05）。
  - 不做压测专属编排（集群多 bot 批量启动属 FR-04，按需在其上装配，本包只提供单进程启动原语）。
  - 真实拉起 node / 连真服属 FR-08 实机维度，本规格不在 CI 跑真连。

## 3. 设计（怎么做）

落在两个新包（ARCHITECTURE §2 的 `bot/` + `verify/`）：

- `top.wcpe.mc.testkit.bot`
  - `nodeCommand()`：按 `os.name` 返回 `node.exe`（Windows）/ `node`（其余），跨平台。
  - `BotConnection`（纯数据 + 纯函数）：承载一次机器人连接参数（host/port/username/auth/version/各超时）与可选覆盖来源（环境变量解析委托给调用方传入的 `(name) -> String?` 取值器，保持纯函数、不耦合 Gradle `Project`）；`toEnvironment()` 产出 `Map<String,String>`，键全部取自 `McTestkitEnv`。这样环境变量构建可被穷举单测。
  - `BotLauncher`：`ProcessBuilder(nodeCommand(), 入口脚本路径)` 在机器人工作目录后台启动、合并 stderr、日志 append 到 `bot-<key>.log`、pid 写 `bot-<key>.pid`；返回 `Process`。日志输出经注入的 `(String)->Unit`（默认 no-op），不硬耦合 `Project.logger`。
  - `stopProcessByPidFile(pidFile, logger)`：`ProcessHandle.of(pid)` 温和 `destroy()` → 限时等待 → 仍存活 `destroyForcibly()`；pid 文件缺失 / 内容非法 / 进程已退出均安全 no-op，结束后删除 pid 文件。是高风险区（进程生命周期 / 收尾 / 跨平台），重点覆盖各分支。
- `top.wcpe.mc.testkit.verify`
  - `ScenarioResult`（纯数据）：`status` / `message`。
  - `ResultReader.read(resultsDir, scenario)`：用 `McTestkitResultFile.fileName(scenario)` 定位文件 → 缺失抛中文错误（指明路径）→ `Properties` 加载 → `status≠PASS` 抛中文错误（带 `message`）→ 否则返回 `ScenarioResult`。**只认结果文件**（架构不变量真源，不靠日志猜测）。

依赖方向：本两包只依赖 `contract/`（冻结常量）与 JDK（`ProcessBuilder` / `ProcessHandle` / `Properties` / `File`）；不引新第三方依赖；不反依赖消费项目 / `template/`。控制协议 / 结果文件常量直接 import `contract/`，不重定义（决策见 ADR-0006，收尾模型见 ADR-0004，无需新 ADR）。

## 4. 任务拆分

- [x] 测试先行：`ResultReader`（PASS / FAIL / 缺失 → 正确结论 / 中文错误）。
- [x] 测试先行：机器人环境变量构建（名 / 值正确、`MC_TESTKIT_E2E_` 前缀、无项目专属业务前缀）。
- [x] 测试先行：`stopProcessByPidFile`（pid 文件缺失安全 no-op；自存活子进程可被收尾）。
- [x] 实现 `bot/`（nodeCommand / BotConnection / BotLauncher / stopProcessByPidFile）。
- [x] 实现 `verify/`（ScenarioResult / ResultReader）。
- [x] 文档同步：PRD 状态、ARCHITECTURE（bot/verify 条核对）、CHANGELOG。

## 5. 验收标准

- 新增单测红 → 绿；`./gradlew build` 全绿。
- 环境变量构建：键全部以 `MC_TESTKIT_E2E_` 开头、无任何项目专属业务前缀残留、值正确（含 port 覆盖、缺省回退）。
- `ResultReader`：PASS 返回含 message 的结论；FAIL 与文件缺失各抛**中文**错误且文案指明原因 / 路径。
- `stopProcessByPidFile`：pid 文件缺失安全 no-op（不抛错）；对一个会自行存活的子进程能温和退出 / 强杀回收并删除 pid 文件。
- **实机维度（需用户在 FR-08 备齐 Node/服务端环境确认）**：真实拉起 mineflayer 机器人经代理 / 直连进服、驱动场景、桩写出结果文件并被本包正确判定——单测不替代，标「待 FR-08 实机验」。

## 6. 风险 / 待定

- 进程收尾在异常强杀的极端情况仍可能留残留（ADR-0004 已记，排障文档给清理指引）；本包尽量覆盖正常 / 超时 / 缺失分支。
- 子进程收尾单测需跨平台可跑：用 `node` 还是用本进程派生的「自存活子进程」需权衡——优先用不依赖外部可执行的方式（如 JVM 自身或 `ProcessHandle` 可控的长睡进程），保证 CI 无 Node 也能跑收尾分支。
