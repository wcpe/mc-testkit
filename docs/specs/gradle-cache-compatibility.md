# 功能规格：Gradle 双缓存兼容（构建缓存 + 配置缓存）

> 状态：已交付@v0.9.3　·　关联：0.8.1 配置缓存修复的回归加固（非新 FR）　·　分支：master（用户确认不建 worktree）

## Report

**What was built** — 消费方在 Gradle 8.x / 9.x 下可安全同时开启 `--configuration-cache` 与 `--build-cache`。配置缓存侧沿用 0.8.1 的可序列化动作捕获图；构建缓存侧在 `McTestkitTasks.registerTask` / `registerExec` 注册原语统一声明 `outputs.upToDateWhen { false }`，钉死全部副作用生命周期任务（prepare / e2e / serve / stop / sync 等）永不因输出未变或缓存命中而假跳过。任务类型仍为 `DefaultTask`/`Exec`（非 `@CacheableTask`），起服副作用不进 Build Cache。

**Verification** — `./gradlew test`（含新增 `BuildCacheFunctionalTest` 与 `ConfigurationCacheFunctionalTest` 双缓存用例、既有配置缓存用例）全绿。`./gradlew ktlintCheck` 因环境无法下载 sarif4k（TLS）失败，属 PRE-EXISTING，与本变更无关。

**Journey log** —
1. TestKit `withTestKitDir` 指到 `@TempDir` 工程内会导致守护进程锁目录、JUnit 清理抛 `IOException`（断言其实已过）；改为不设 TestKit 目录，本地缓存经 `settings.gradle.kts` 的 `buildCache.local` 隔离。
2. `upToDateWhen { false }` 只钉 UP-TO-DATE；FROM-CACHE 靠任务类型非可缓存。集成测试用 `TaskOutcome.SUCCESS` 同时拦两类假跳过。
3. 文档中「构建缓存」易与 FR-02 jar 下载缓存混淆，已在 API §3.2 显式区分。

## 1. 背景与目标

0.8.1 已让任务动作闭包不再捕获 `Project`，消费方可在 Gradle 8.x / 9.x 下启用 `--configuration-cache`（已有 `ConfigurationCacheFunctionalTest` + `TaskActionCaptureTest`）。但：

1. **构建缓存（`--build-cache`）尚无任何契约与回归测试**。消费者工程开启构建缓存时，本插件注册的 prepare / e2e / serve / stop 等任务全是带副作用的 `DefaultTask`/`Exec`（起服、写运行目录、杀进程、回写缓存）。若被错误判定为 UP-TO-DATE 或 FROM-CACHE 而跳过，会出现「假绿」或残留进程。
2. 仓库文档中的「缓存」多指**插件自有 jar 下载缓存**（`JarCache` / 持久运行库），与 Gradle Build Cache 不是同一概念，需要在 API 层把消费方双缓存契约写清。

目标：在**不改动任务编排模型**的前提下，把「消费方可安全同时开启配置缓存与构建缓存」固化为可执行回归，并显式声明副作用任务永不因输入未变而跳过。

## 2. 需求（要什么）

- 范围内：
  - 注册原语（`registerTask` / `registerExec`）为全部本插件任务设置 `outputs.upToDateWhen { false }`，使副作用任务**永不** UP-TO-DATE / 构建缓存命中跳过。
  - 新增 TestKit 集成测试：消费者开启本地 Build Cache + `--build-cache` 时，全注册点拓扑下 `stopDevServe` 成功且 outcome **不是** `FROM-CACHE` / `UP_TO_DATE`。
  - 新增/扩展 TestKit 集成测试：`--build-cache` 与 `--configuration-cache` **同时**开启时，存储 + 重用均成功，任务仍每次真实执行。
  - 文档：API 注明双缓存兼容契约；CHANGELOG 记用户可见行为。
- 不做（范围外）：
  - 不把 e2e/prepare/serve 改造成 `@CacheableTask` 或声明可缓存 inputs/outputs（起服副作用本就不可入缓存）。
  - 不修改本仓库自身 `gradle.properties` 启用 `org.gradle.caching` / `org.gradle.configuration-cache`（属「本工程自建启用」范围，用户未选）。
  - 不扩写配置缓存捕获图单测以外的新 CC 缺陷修复（现状已绿则不重写实现）。
  - 不新增 FR / ADR（无架构决策变更）。

## 3. 设计（怎么做）

- **生产加固**：在 `McTestkitTasks.registerTask` / `registerExec` 的 configure 包装里统一 `task.outputs.upToDateWhen { false }`。一处生效覆盖全部注册点，避免各 `doLast` 散落重复。
- **测试策略**（与既有 `ConfigurationCacheFunctionalTest` 同风格：全注册点拓扑 + 无副作用 `stopDevServe` 触发存储）：
  1. `BuildCacheFunctionalTest`：TestKit 临时工程启用本地 build cache 目录，断言 `stopDevServe` 在 `--build-cache` 下为 `SUCCESS`（不是 `FROM-CACHE` / `UP_TO_DATE` / `SKIPPED`）。
  2. `ConfigurationCacheFunctionalTest` 双缓存用例：同一次 runner 同时带 `--build-cache --configuration-cache`，第一次存 CC、第二次复用 CC，两次任务均为 `SUCCESS`。
- **验收证据**：`./gradlew test` 相关用例红→绿；全量 `test` 不回归。

## 4. 任务拆分

- [x] T1：`registerTask`/`registerExec` 统一 `outputs.upToDateWhen { false }`
- [x] T2：新增构建缓存集成测试（本地 cache dir + SUCCESS 断言）
- [x] T3：双缓存同时开启的存储/重用集成测试
- [x] T4：文档同步（API + CHANGELOG）
- [x] T5：`./gradlew test` 验证门全绿

## 5. 验收标准

- 消费方 `--configuration-cache` 既有用例不回归。
- 消费方开启本地 Build Cache 并传 `--build-cache` 时：全注册点拓扑 `stopDevServe` 构建成功；该任务 outcome 为 `SUCCESS`（明确不是 `FROM-CACHE` / `UP_TO_DATE`）。
- 消费方同时开启 `--build-cache --configuration-cache`：第一次 `Configuration cache entry stored`，第二次 `Reusing configuration cache`，两次 `stopDevServe` 均为 `SUCCESS`。
- `./gradlew test` 全绿（含新增用例）。
- 无新增架构漂移：未引入第三方下载库、未改 Kotlin 语言版本、未把副作用任务改成可缓存任务类型。

## 6. 风险 / 待定

- TestKit 启用本地 build cache 需隔离缓存目录：不把 `withTestKitDir` 指进 `@TempDir`（守护进程锁目录导致清理失败）；本地缓存经消费者 `settings.gradle.kts` 的 `buildCache.local.directory` 隔离在工程内。
- `Exec` 类型的 `npmInstallE2eBot` 在 `upToDateWhen { false }` 后仍每次执行——符合「装依赖副作用任务」预期，与现行为一致。
- `upToDateWhen { false }` 不单独阻止 FROM-CACHE；拦截依赖任务类型非 `@CacheableTask` + 集成测试 `SUCCESS` 断言。
