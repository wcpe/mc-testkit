# mc-testkit 脚手架模板（`template/`）

这是 [mc-testkit](../README.md) 提供的**照抄脚手架**：把它整个拷进你的插件仓库，改几处场景代码，就有了一套「服务端桩插件 + mineflayer 机器人」的 E2E 骨架，配合 mc-testkit 的 `mcTestkit { }` 编排即可在真实「代理 + 后端」上跑端到端测试。

> **它是拷贝物，不是依赖。** 模板不会被 mc-testkit 插件在运行期依赖、不进插件构建产物、也不会被加进 mc-testkit 的 `settings.gradle.kts`（架构不变量）。你把它拷走后，它就完全归你的项目所有，按需自由分叉。本期不提供可发布的共享桩基类库 / npm 包（见 mc-testkit 的 ADR-0002），改进靠「更新模板 + 手动同步」。

---

## 目录结构

```
template/
  harness/        # 服务端桩插件骨架（Kotlin，框架无关的 Bukkit/Paper 插件）
    build.gradle.kts            # 独立 Gradle 子工程（paper-api compileOnly）
    settings.gradle.kts
    src/main/kotlin/com/example/e2e/
      McTestkitE2eHarnessPlugin.kt  # 桩主体：入服派发场景、发控制消息、写结果文件、收尾关服
      HarnessConfig.kt              # 读 config.yml 的场景配置（纯数据）
      ScenarioName.kt               # 场景枚举（内置 smoke / example-bot / cross-server / continuous-stress / multi-bot）
      ScenarioResultWriter.kt       # 把 PASS/FAIL 写成 <scenario>.properties（测试结论真源）
    src/main/resources/
      plugin.yml                    # 桩插件描述
      config.yml                    # 桩自身配置（场景名 / 结果文件 / 超时）
  bot/            # mineflayer 机器人内核（Node ≥ 18）
    package.json                 # 依赖 mineflayer；devDep eslint/prettier
    .eslintrc.json / .prettierrc.json
    src/
      connectAndWait.js          # 入口：探测端口 → 登录 → 按 action 分发场景 → 等结果；集中读环境变量
      lib/{env,messages,normalize}.js  # 环境变量读取 / 消息等待 / 文本归一（纯函数）
      scenarios/exampleBot.js    # 机器人驱动示例（同目录还有 crossServerBot / continuousStress / multiBot）
  README.md       # 本文件
```

---

## 它与 mc-testkit 的契约对齐

模板与 mc-testkit 编排之间靠三件**已冻结的契约**对接（详见 mc-testkit 的 `docs/API.md`）：

1. **结果文件**：桩把结论写成 `<scenario>.properties`，键 `status`（`PASS`/`FAIL`）+ `message`。编排的 verify 任务**只认这个文件**判定。见 `ScenarioResultWriter.kt`。
2. **控制协议**：桩经聊天通道向机器人发 `E2E_READY:<scenario>` 等消息（载荷走 `:` 后缀）。见桩里的 `sendControlMessage` 与机器人 `lib/messages.js`。
3. **环境变量**：机器人连接 / 超时等参数以 `MC_TESTKIT_E2E_BOT_` 为前缀（如 `MC_TESTKIT_E2E_BOT_HOST` / `_PORT` / `_USERNAME` / `_VERSION` / `_CONNECT_TIMEOUT_MS` / `_RETRY_DELAY_MS` / `_READY_TIMEOUT_MS`）。由编排在启动机器人时注入。见 `connectAndWait.js`。

> 这些名字是 mc-testkit 的冻结契约，**不要在模板里改名**，否则编排与桩 / 机器人对不上。改场景逻辑、加新场景是自由的；改协议 / 结果文件键 / env 名不是。

---

## 怎么用（四步接线）

### 1. 拷贝

把整个 `template/` 目录拷进你的插件仓库（例如改名为 `e2e/`），然后：

- 改 `harness/` 的包名 `com.example.e2e` 为你自己的包名，同步改 `plugin.yml` 的 `main`、`build.gradle.kts` 的 `group`。
- 改 `harness/build.gradle.kts` 与 `plugin.yml` 里的 `paper-api` / `api-version` 到你后端的 Minecraft 版本（默认 `1.20.1` / `'1.20'`）。
- 桩若需读被测插件的 API：在 `plugin.yml` 的 `depend` 里加上你的插件名，并在 `harness/build.gradle.kts` 加 `compileOnly(...)` 依赖。

### 2. 在 `mcTestkit { }` 里声明场景

在你的主工程 `build.gradle.kts` 应用 mc-testkit 并声明拓扑与场景（场景 `action` 必须与下面桩 / 机器人侧的场景 id 一致）：

```kotlin
plugins {
    id("top.wcpe.mc-testkit")
}

mcTestkit {
    backend("s1") { platform = paper; version = "1.20.1" }
    scenario("smoke")                  // 无机器人：仅校验桩就绪
    scenario("example-bot") {
        backend = "s1"
        bot {
            username = "ExampleBot"
            action = "example-bot"     // 与 ScenarioName.EXAMPLE_BOT.id / 机器人分发表一致
        }
    }
}
```

### 3. 加你自己的场景

一个新场景要在**三处**用同一个 id 登记：

1. **桩**：在 `ScenarioName.kt` 加枚举项 → 在 `McTestkitE2eHarnessPlugin.dispatchScenario` 加分支，写「装备玩家 + 发 `E2E_READY` + 判定写结果文件」逻辑。
2. **机器人**：在 `bot/src/scenarios/` 加驱动文件（参照 `exampleBot.js`）→ 在 `connectAndWait.js` 的 `scenarioRunner()` 分发表加一个 `case`。
3. **编排**：在 `mcTestkit { }` 加一个 `scenario("...") { ... }`。

> 判 PASS/FAIL 始终是**桩**的职责（写结果文件）；机器人只负责「像玩家一样操作」。`exampleBot.js` 里那段无条件 PASS 仅作演示，真实场景应改为由业务事件 / 控制消息回报触发 `passScenario`，并删掉示例占位。

### 4. 跑

由 mc-testkit 生成的任务驱动（任务名见 mc-testkit `docs/API.md §3.2`），例如：

```bash
./gradlew e2eSmoke               # 烟雾场景
./gradlew e2eExampleBotWithBot   # 启动机器人 + 验证
```

机器人依赖由编排的 `npmInstallE2eBot` 任务自动 `npm install`。本地单独调试机器人：

```bash
cd bot
npm install
MC_TESTKIT_E2E_BOT_ACTION=example-bot \
MC_TESTKIT_E2E_BOT_HOST=localhost \
MC_TESTKIT_E2E_BOT_PORT=25565 \
npm run connect-and-wait
```

---

## 本地自检

- **机器人**：`cd bot && npm install && npm run lint`（eslint）+ `npm run format`（prettier）。
- **桩**：`cd harness && ./gradlew compileKotlin`（独立编译，首次需联网拉 paper-api）。

---

## 注意

- 运行期产物（`node_modules/`、`run/`、`*.log`、结果文件目录）应被你项目的 `.gitignore` 排除，不要入库。
- 桩的 `config.yml` 由编排在 prepare 阶段覆盖写入（场景名、结果文件路径）；仓库里这份只是默认占位与字段说明。
- 模板刻意做到最薄：示例机器人不点 GUI、不断言背包，避免把任何业务玩法固化进骨架。窗口点击 / 背包断言等辅助按你的真实场景自行补。
