# 功能规格：后端持久运行目录（`runDirectory`）

> 状态：开发中　·　关联 PRD：FR-33　·　分支：feature/output-budget-timeout-run-dir　·　依赖：FR-20（节点运行时注入）、FR-29（bot `ops.json`）

## 1. 背景与目标

后端只能在 `<buildDir>/mc-testkit/run-<后端名>` 里起服，每轮除运行库缓存（`libraries` / `cache` / `assets` / `versions`）外全部清空，该目录还随 `clean` 消失。上一轮的世界、插件数据、日志没法留给后面的测试：要在同一个世界里反复手测、或让长测接着上一轮的状态跑，只能自己在构建脚本里搬目录。

**目标**：让消费方声明某个后端在哪个目录起服，声明即持久；未声明的后端行为不变。属 P2。

## 2. 需求（要什么）

- `backend("s1") { runDirectory = "run/s1" }`：相对路径按工程目录（`Project.projectDir`）解析，绝对路径原样采用。
- 声明的目录不再每轮清空；每轮仍要保证「这一轮的输入是权威的」：
  - 铺模板（世界目录与 `logs` 照旧不从模板拷）、重写权威配置（`eula.txt`，`server.properties` 的端口 / 离线模式等）、合并 bot 的 `ops.json`、注入本轮的待测 / 依赖插件 jar；
  - 上一轮由框架注入的 jar 先移除（依赖升版后文件名变了也不能新旧并存），消费方自己放进 `plugins/` 的 jar 不动；
  - `server-libraries/` 与根目录残留的 `*.pid` 照常清掉；
  - 上一轮经代理留下的转发开关（`spigot.yml` 的 `settings.bungeecord`、`config/paper-global.yml` 的 `proxies.velocity.enabled`）先复位，直连场景不会被残留开关拒之门外，经代理的场景随后重新打开。
- 注册期校验（中文报错）：不能是工程目录本身或其上级；不能与 `<buildDir>/mc-testkit` 重叠（那里随 `clean` 清空、代理目录每轮整删）；两个后端不能指向同一目录或互相嵌套；空白声明在拓扑解析时拒绝。
- `mcTestkit.backendRunDirectory("<后端名>")` 与钩子的 `HookContext.backendRunDir(...)` 都返回这个目录。
- 范围外：代理的持久目录（代理配置全由框架生成，没有需要跨轮保留的状态）；自动重置世界（要从头来过删掉目录即可）。

## 3. 设计（怎么做）

- **DSL 与布局**：`BackendSpec.runDirectory: String?`。注册期解析成绝对目录并做上述校验，结果以 `backendRunDirOverrides` 交给 `RunLayout`；`RunLayout.backendRunDir(name)` 是全部编排路径取后端目录的唯一入口，覆盖在此一处生效，`isPersistentBackendRunDir(name)` 供 staging 分支。
- **staging**（`task/NodeRuntimeInjection`）：`stageBackendRuntime(..., persistent = true)` 把「清空并保留运行库缓存」换成 `refreshPersistentRunDir`：按注入清单 `.mc-testkit-injected-plugins` 删上一轮注入的 jar（清单被改坏时也只删 `plugins/` 下的 jar，不越界）、删 `server-libraries/` 与根目录 `*.pid`、复位转发开关；之后的铺模板、写权威配置、写 `ops.json`、注入插件与非持久目录同一条路径，注入时把本轮注入的文件名写回清单。非持久目录不写清单。
- **运行库缓存**：持久目录的运行库本来就留在原地，不参与 `server-base` 的回写。
- 契约见 [`API.md`](../API.md) §3.1「持久运行目录」。

## 4. 任务拆分

- [x] DSL 字段，注册期路径解析与重叠 / 嵌套 / 工程目录校验
- [x] `RunLayout` 覆盖，访问器与钩子上下文同源
- [x] 持久目录 staging：注入清单、框架产物整理、转发开关复位
- [x] 文档同步：PRD（FR-33 行）、API §3.1、ARCHITECTURE §5、CHANGELOG

## 5. 验收标准

- **[自动]** 路径解析：相对路径按工程目录解析、绝对路径原样采用、未声明的后端不受影响；工程目录本身或上级、与框架工作目录重叠、两后端共用或嵌套、空白声明都以中文错误拒绝（`PersistentBackendRunDirTest`）。
- **[自动]** 二次 staging：世界、插件数据、消费方自放的 jar 都保留，只替换框架注入的 jar 并整理框架产物；注入清单被改坏时也只删 `plugins/` 下的 jar；非持久目录仍按原口径清空且不写清单（`PersistentBackendRunDirTest`）。
- **[自动]** `backendRunDirectory()` 访问器在声明了 `runDirectory` 时返回持久目录（`McTestkitExtensionTest`）。
- **[真机，需用户确认]** 真实 Paper 后端声明 `runDirectory` 后连跑两轮：第二轮进入的是第一轮的世界，插件数据还在，升级待测插件后 `plugins/` 里只有新版 jar；先跑经代理、再跑直连，bot 能直连进服。

## 6. 风险 / 待定

- 持久目录里上一轮的状态会影响下一轮的结果，它适合手测与有意接续的长测；要求可重复的回归场景不应声明它。
- 消费方手工改过的权威配置（端口、离线模式等）每轮会被重写回来，与非持久目录一致。
