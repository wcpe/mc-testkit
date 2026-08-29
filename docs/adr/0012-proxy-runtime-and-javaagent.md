# ADR-0012：代理版本、独立 Java 运行时与 Java Agent 注入

## 状态

已接受（补充 [ADR-0010](0010-velocity-modern-forwarding.md)）

## 背景

代理版本当前由全局默认值决定，代理进程复用 Gradle JVM。Velocity 4.1.0 需要 Java 25，而同一消费者还需要运行 3.x；ServerProbe 的 premain 验收也必须把 `-javaagent` 独立传给后端和代理。

## 决策

在既有 backend/proxy DSL 上增加可选的节点 `version`、`javaVersion`、`jvmArg(...)` 与 `javaAgent(...)`，以 `MC_TESTKIT_JAVA_HOME_<Java主版本>` 选择节点运行时，并由统一启动器拼装 JVM 参数。

## 理由

- 加法 DSL 保持旧消费者与冻结任务/env/协议契约不变。
- Java 主版本表达的是运行时要求，和 Minecraft/Velocity 软件版本分离，能准确覆盖 Velocity 3/4。
- agent 值在执行期优先从环境变量解析，公共构建脚本不写死机器路径。

## 后果

- `ResolvedBackend`/`ResolvedProxy` 固化节点 Java 与 JVM 参数，所有 e2e/cluster/stress/serve 路径共用同一启动参数构造器。
- 明确声明却找不到所需 Java 或 agent 文件时，在启动前中文失败；未声明时保持原回退行为。
- ServerProbe 必须先消费 Maven Local 开发版，而不是 `includeBuild`。

## 备选方案

- **只用当前 Gradle JVM**：无法覆盖 Java 25 Velocity 4 门禁，否决。
- **在 DSL 直接写 javaHome 绝对路径**：不可移植且泄漏环境结构，否决。
- **为 ServerProbe 内置业务 fixture**：让 mc-testkit 耦合某个消费者，违背工具边界，否决。

