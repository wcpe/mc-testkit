# 功能规格：`template/` 脚手架（桩插件骨架 + 机器人内核 + 示例场景 + 复制说明）

> 状态：开发中　·　关联 PRD：FR-07　·　分支：feature/fr-07-template

## 1. 背景与目标

各插件做 E2E 时，「服务端桩插件 + mineflayer 机器人」每个项目都得从零搭：装备入服玩家、收发控制消息、写结果文件、连接重试……做法不一、重复劳动。FR-07 在仓库顶层提供一份 `template/` 脚手架，供消费方**照抄**到自己项目里改场景即用，且严格对齐 FR-01 已冻结的对外契约（控制协议消息、结果文件约定、`MC_TESTKIT_E2E_` 环境变量名）。

属第一期（MVP，P1）。本期只交付「编排插件 + 脚手架模板」两件制品（ADR-0002），脚手架是**照抄骨架**，不是可发布的共享桩基类库 / npm 包。

## 2. 需求（要什么）

- `template/harness/`：框架无关的 Bukkit/Paper 桩插件骨架（Kotlin），能力：
  - 加载 `config.yml` 场景配置（场景名、结果文件路径、各类超时）。
  - 入服玩家派发场景：清背包、给一个示例授权位、发控制消息 `E2E_READY:<scenario>`。
  - 把判定结论写成 `<scenario>.properties` 结果文件（`status`=PASS/FAIL、`message`、可选明细键）。
  - 自带一个 `smoke` 场景（无机器人，仅校验自身就绪即 PASS）与一个 `example-bot` 示例场景骨架（机器人驱动）。
  - 收尾：判定后延迟关服，回收前台 `runServer` 线程。
- `template/bot/`：Node ≥18 mineflayer 机器人内核，能力：
  - 端口探测 + 连接重试 + 登录（默认离线模式）。
  - spawn 后按 `action` 分发到场景驱动；等待并匹配控制消息。
  - 集中读取 `MC_TESTKIT_E2E_BOT_*` 环境变量（连接 / 超时 / 用户名 / 协议版本）。
  - 自带一个 `example-bot` 示例场景（等 `E2E_READY` → 发一条聊天 → 退出）。
  - 纯函数（env 解析、文本归一、消息匹配）可单测；`package.json` + eslint + prettier。
- `template/README.md`：把 `template/` 复制进消费方项目、接线（在 `mcTestkit { }` 里声明场景、改 action / 场景名）、跑通的中文说明。

### 范围内
- 顶层 `template/` 目录下的全部脚手架文件。
- 严格符合 FR-01 冻结契约：控制协议名（`E2E_READY` / `E2E_STRESS_RESULT` / `E2E_DISCONNECT_NOW` / `E2E_UI_TOKEN`）、结果文件（`<scenario>.properties`，键 `status`/`message`，值 PASS/FAIL）、环境变量前缀与核心名（`MC_TESTKIT_E2E_BOT_HOST` 等）。

### 不做（范围外）
- 不实现编排插件侧任何任务 / DSL / model（那是 FR-02/03/04/06）。
- 不剥出可发布的共享桩基类库 / npm 包（ADR-0002，留 P2 FR-09）。
- 不绑任何具体框架（那是消费方的私有选择）、不留业务场景 / 支付 / 具体依赖插件名等业务。
- 不实现 Spigot/Bukkit/Sponge 专属能力（不在项目计划内）。
- `template/` **不进** root `settings.gradle.kts`、不被插件代码依赖、不进插件构建产物（架构不变量）。

## 3. 设计（怎么做）

- `template/harness/` 是一个**独立的** Gradle 子工程，自带 `build.gradle.kts`（`paper-api` compileOnly、Kotlin jvm），有自己的 `settings.gradle.kts`，与根插件工程互不引用——消费方照抄后按需改 group / 版本即可。
  - `plugin.yml`：`name: McTestkitE2eHarness`、`api-version: '1.20'`、无 `depend`（通用骨架不依赖任何业务插件；消费方按需加）。
  - `resources/config.yml`：kebab-case 字段 + 中文注释（遵循 `config-files.md`），含 `scenario` / `result-file` / 各超时。
  - `HarnessConfig`：从 `FileConfiguration` 读出强类型配置（纯数据，`from()` 工厂）。
  - `ScenarioName`：通用场景枚举，只放 `smoke`、`example-bot`（示例），`from()` 容错。
  - `ScenarioResultWriter`：把 `status`/`message`/明细写成 `<scenario>.properties`，键名取自契约（与编排 verify 读取约定一致）。
  - `McTestkitE2eHarnessPlugin`：`JavaPlugin` + `Listener`，入服派发、控制消息发送、超时兜底、PASS/FAIL 写文件后延迟 `Bukkit.shutdown()`。
- `template/bot/` 结构对齐参考实现但**剥薄**：`src/connectAndWait.js`（入口 + 集中读 env + action 分发）、`src/lib/{env,messages,normalize}.js`（可单测纯函数）、`src/scenarios/exampleBot.js`（示例）。
  - env 名严格用契约常量字面量：动作用 `MC_TESTKIT_E2E_BOT_ACTION`，连接用 `MC_TESTKIT_E2E_BOT_HOST/PORT/USERNAME/AUTH/VERSION`，超时用 `…_CONNECT_TIMEOUT_MS` / `…_RETRY_DELAY_MS` / `…_READY_TIMEOUT_MS`。
  - eslint（standard 风格的最小自带规则，零额外依赖，纯 `eslint:recommended` + node env）+ prettier 配置文件，版本固定。
- 契约不在 `template/` 里重新定义常量字符串“源”——`template/` 是独立可照抄物，不能 import 插件包；故协议 / env 名以**字面量**出现并在注释里标注「对齐 mc-testkit 冻结契约（docs/API.md §3.3/§3.4/§3.5）」，避免与插件产生编译期耦合（架构不变量：template 不被插件依赖，反之亦然）。

> 不涉及新架构决策（ADR-0002 已覆盖「只做模板、不发库」）。无需新 ADR。

## 4. 任务拆分
- [ ] 写本规格；PRD §4 FR-07 状态「计划」→「开发中」。
- [ ] `template/harness/`：build.gradle.kts + settings.gradle.kts + plugin.yml + config.yml + 4 个 Kotlin 文件。
- [ ] `template/bot/`：connectAndWait.js + lib/3 + scenarios/exampleBot.js + package.json + eslint/prettier 配置。
- [ ] `template/README.md` 复制接线说明。
- [ ] 文档同步：PRD 状态、ARCHITECTURE §2（template 条核对）、CHANGELOG 未发布段追加一行。
- [ ] 验证：bot `npm install` + eslint/`node --check` 通过；harness 独立 `gradlew compileKotlin` 通过（受网络阻则如实报告卡点）。

## 5. 验收标准
- `cd template/bot && npm install` 成功，`npx eslint .`（或对每个 `.js` 跑 `node --check`）通过。
- `template/harness` 能独立编译（`./gradlew compileKotlin`，paper-api compileOnly 解析成功）；若依赖拉取受阻无法编译，如实记录卡点而非假装通过。
- 结构齐全、消费方业务剥净（无任何具体业务 / 依赖插件名字样）、严格符合冻结契约（协议名 / 结果文件键值 / env 名）。
- 注释全中文；YAML 字段 kebab-case + 中文行注释。
- `template/` 未被加入 root `settings.gradle.kts`，根 `./gradlew build` 不依赖 `template/`。
- **实机维度（留 FR-08 复验，需用户在备齐服务端模板 / Node 的环境确认）**：照抄 `template/` 后真实拉起 Paper + 机器人跑通 smoke 与示例场景——本期不在此 worktree 内实跑，仅保证编译 / lint 与契约一致。

## 6. 风险 / 待定
- harness 依赖 `io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT`，首次编译需访问 PaperMC 仓库；离线 / 弱网下可能拉取失败（已在验收标准里约定如实报告）。
- 机器人示例场景刻意做到最薄（不点 GUI、不断言背包），避免把任何业务玩法固化进“通用”骨架；窗口 / 背包等辅助留待消费方按真实场景自行补，符合 scope-discipline 不镀金。
