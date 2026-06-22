# 功能规格：FR-01 插件工程骨架 + 对外契约冻结

> 状态：开发中　·　关联 PRD：FR-01　·　分支：master（Wave 0 地基，并行其余 FR 的基线）

## 1. 背景与目标

mc-testkit 要被多个插件项目并行接入。第一期多个 FR（FR-02~07）将并行开发，若对外契约（插件形态、DSL 形态、任务命名、环境变量前缀、控制协议、结果文件）未先冻结，多线并行必然在插件入口与共享约定上冲突。

FR-01 是**地基 + 契约**：立起 Gradle 插件工程骨架，冻结对外契约写进 `docs/API.md`，并建立**内部包接缝**——让后续各 FR 各占一个包、只写库代码、不撞插件入口。属第一期 P1。

## 2. 需求（要什么）

- 范围内：
  - Gradle 插件工程：`java-gradle-plugin` + `kotlin-dsl`，纯 Kotlin + KTS 构建；Kotlin 语言/API 版本锁 1.9（ADR-0005）；版本号读根 `VERSION`；发布到 maven.wcpe.top（凭据走环境变量）。
  - 插件入口 `McTestkitPlugin`（id `top.wcpe.mc-testkit`）：创建 `mcTestkit { }` 扩展，**不注册终端任务**，只留接缝。
  - `mcTestkit { }` DSL **形态**冻结：`backend()` / `proxy()` / `scenario()` / `dependencies()` 及其 spec 类型与字段。
  - 对外契约常量冻结：环境变量前缀 `MC_TESTKIT_E2E_` 与名集、任务命名约定、机器人↔桩控制协议、结果文件约定。
- 不做（范围外）：
  - 自实现下载/运行（FR-02）、拓扑解析/端口推导/配置期校验（FR-03）、任务编排（FR-04）、环境契约固化（FR-05）、机器人驱动与判定（FR-06）、模板（FR-07）。
  - 任何 Spigot/Bukkit/Sponge 平台分支（不在项目计划内）；不预置未来平台空壳枚举项（scope-discipline）。

## 3. 设计（怎么做）

- 包布局（内部接缝，让 Wave 1 文件隔离）：
  - `top.wcpe.mc.testkit`：`McTestkitPlugin`（入口）、`McTestkitExtension`（DSL 形态）。
  - `contract/`：`McTestkitContract`（id/扩展名/默认值）、`McTestkitEnv`（env 名）、`McTestkitTaskNames`（任务命名）、`McTestkitControlProtocol` + `McTestkitResultFile`（机器人↔桩协议、结果文件）。**FR-01 冻结，Wave 1 共享引用。**
  - `dsl/`：`Platforms`（平台枚举 + DslMarker）、`Specs`（Backend/Proxy/Scenario/Bot/Dependencies Spec）。FR-01 冻结形态，FR-03 在 `dsl/`+`model/` 续写解析逻辑。
  - 后续包（FR 各自建）：`provision/`(FR-02)、`topology/`(FR-03)、`config/`(FR-05)、`bot/`+`verify/`(FR-06)、`task/`(FR-04)。
- 契约决策记于 ADR-0006（命名约定），spec 不重复决策正文。Kotlin 1.9 锁的实现要点（kotlin-dsl 默认钉 1.8，需任务级覆盖）见 `build.gradle.kts` 注释。

## 4. 任务拆分

- [x] Gradle wrapper（8.9）+ settings/gradle.properties/build.gradle.kts。
- [x] 契约常量（contract/）+ DSL 形态（dsl/ + 扩展）+ 插件入口。
- [x] 测试先行：契约常量测试、扩展 DSL 单元测试（ProjectBuilder）、TestKit 功能测试（消费者视角）。
- [x] 文档同步：PRD 状态、ADR-0006、API.md 冻结、ARCHITECTURE 包布局、CHANGELOG。

## 5. 验收标准

- `./gradlew build` 全绿：`validatePlugins` 通过、11 个测试通过（契约 4 + 扩展 6 + TestKit 1）。
- 消费者 `plugins { id("top.wcpe.mc-testkit") }` + `mcTestkit { backend/proxy/scenario/dependencies }` 配置期不报错、`help` 成功（TestKit 已覆盖）。
- Kotlin 语言/API 版本实测为 1.9（ADR-0005；已用临时诊断验证 lv/av=KOTLIN_1_9）。
- `docs/API.md` 反映冻结后的契约，不再标"草案"于 FR-01 冻结的部分。
- 本 FR 无实机维度（纯插件骨架，无真实服务端/代理/机器人）。

## 6. 风险 / 待定

- env 变量**全集**随 FR-02/04/06 实现补全；前缀 `MC_TESTKIT_E2E_` 与命名风格已冻结不变。
- DSL spec 的**字段细节**可能随 FR-03/04 微调，但四个顶层块（backend/proxy/scenario/dependencies）形态已冻结；字段演进只发生在 `dsl/` 包内（仅 FR-03 触碰），不引发跨车道冲突。
