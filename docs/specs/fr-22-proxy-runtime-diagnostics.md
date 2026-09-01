# 功能规格：多版本代理与诊断型 JVM 编排

> 状态：已交付@v0.7.0　·　关联 PRD：FR-22　·　决策：[ADR-0012](../adr/0012-proxy-runtime-and-javaagent.md)

## 1. 背景与目标

mc-testkit 当前代理版本由全局默认值决定，代理进程也只能使用当前 Gradle JVM，无法在同一消费者中覆盖 Velocity 3/4 的 Java 断层，也无法给后端/代理注入 ServerProbe premain。FR-22 增加最小、向后兼容的节点运行时声明，支撑 ServerProbe 的跨平台协议与诊断验收。

## 2. 需求（要什么）

- `ProxySpec` 增加独立 `version` 与 `javaVersion`；Velocity 可声明 3.1.1、最新 3.x（固定 3.5.1）、4.1.0，4.1.0 选择 Java 25。
- `BackendSpec` 与 `ProxySpec` 均可追加 JVM 参数，并可用环境变量或文件路径声明一个/多个 `-javaagent`。
- 所有 e2e、cluster、stress、serve 启动路径使用节点解析后的 Java 与 JVM 参数。
- 公开配置不写死消费方本机绝对路径；Java 运行时使用 `MC_TESTKIT_JAVA_HOME_<JAVA主版本>`，javaagent 优先从消费方声明的环境变量解析。
- 保持旧 DSL、任务名、`MC_TESTKIT_E2E_*` 环境契约与未声明运行时字段时的行为。
- 框架只提供协议客户端/诊断 fixture 的进程接线、env 与结果文件编排，不内置 ServerProbe 业务断言。
- 范围内：DSL、解析模型、下载版本、Java 选择、JVM 参数、javaagent、所有任务路径、Velocity 矩阵自举。
- 不做：发布共享 ServerProbe fixture 库、替消费项目决定协议包或 MCP 断言、用 `includeBuild` 接入快照。

## 3. 设计（怎么做）

- DSL 建议形态：`version = "4.1.0"`、`javaVersion = 25`、`jvmArg("-Dkey=value")`、`javaAgent("SERVERPROBE_AGENT_JAR")`。`javaAgent` 的字符串先按环境变量名查找，未命中再按路径解析。
- `ResolvedBackend/ResolvedProxy` 增加不可变的 `javaVersion`、`jvmArgs`、`javaAgents`；解析期保留声明，执行期才读取环境变量与规范化绝对路径，避免配置缓存捕获本机路径。
- `JavaRuntimeSelector` 增加按 Java 主版本选择：优先 `MC_TESTKIT_JAVA_HOME_25`，再回退现有版本段变量、`JAVA_HOME`、当前 JVM；显式声明但找不到匹配 Java 时立即中文失败。
- `ServerLauncher` 继续形成 `java <jvmArgs> <-javaagent> ...`，后半段按构件形态接 `-jar <jar>` 或 `-cp <启动器 jar> <Main-Class>`（选路见 fr-02 §3）；对不存在/非文件 agent 给出中文错误；所有启动入口复用同一个参数构造器，禁止各任务复制拼接。
- Velocity 下载器使用节点 `version`，未声明时保持当前默认；缓存键包含平台与版本。
- fixture 作为普通额外进程由消费项目注册，结果仍由 harness 结果文件判定；框架不从日志猜 PASS。
- 架构决策在规格获批后写入 ADR-0012。

## 4. 任务拆分

- [x] 先补 DSL 默认值、解析、Java 主版本选择、agent 路径、参数顺序与失败提示测试。
- [x] 扩展 DSL/Resolved 模型并统一所有启动路径。
- [x] 增加 Velocity 3.1.1、最新 3.x（固定 3.5.1）、4.1.0 下载与 Java 运行矩阵。
- [x] 增加消费方 fixture 进程接线示例和结果文件门禁测试。
- [x] 发布 `0.7.0-SNAPSHOT` 到 Maven Local，ServerProbe 按插件版本消费并完成真实矩阵。
- [x] ServerProbe 验收通过后再走 mc-testkit 正式版本发布。
- [x] 文档同步：PRD 状态、ARCHITECTURE、DSL、README、CHANGELOG。

## 5. 验收标准

- 旧消费者不声明新增字段时，解析结果、任务名、下载版本、Java 回退和启动参数与 v0.6.0 一致。
- 单元/功能测试证明 backend/proxy 所有 e2e/serve 路径都使用节点专属 Java、JVM 参数和 agent。
- 同一消费项目依次拉起 Velocity 3.1.1、最新 3.x（固定 3.5.1）、4.1.0；4.1.0 进程实际由 Java 25 启动。
- agent 路径可只通过消费方环境变量提供，公共 DSL/日志/结果契约不泄露固定本机路径。
- 协议流量 fixture 与 MCP 诊断 fixture 的成功与失败均由 harness 结果文件权威判定，异常结束能清理全部进程与端口。
- mc-testkit 全量测试、自举 E2E 及 ServerProbe 真实消费全部通过后才允许标记交付。

## 6. 风险 / 待定

- 建议采用 `javaVersion = 25` + `MC_TESTKIT_JAVA_HOME_25`，不新增任意 `javaHome` 路径字段；这样公共 DSL 更可移植。
- Velocity “最新 3.x”在验收流水线中解析为受控版本变量，不使用无锁定的 `latest` URL，保证可复现。
