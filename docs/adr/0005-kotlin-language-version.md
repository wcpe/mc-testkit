# ADR-0005：Kotlin 语言/API 版本锁定 1.9，兼容 K1 与 K2

## 状态
已接受

## 背景
本插件用 Kotlin 编写、以 Gradle Kotlin DSL（KTS）构建，会被不同 Gradle 版本的项目消费。Gradle 各版本内嵌的 Kotlin 编译器不同：Gradle 8.x 内嵌 Kotlin 1.9（K1 编译器），Gradle 9.x 起内嵌 Kotlin 2.x（K2 编译器）。若插件以过高的 Kotlin 语言/API 版本编译（如 2.0），低版本 Gradle（K1）的消费方将无法加载；锁太低又用不上需要的特性。

## 决策
插件以 **Kotlin 语言版本（languageVersion）与 API 版本（apiVersion）= 1.9** 编译。构建脚本一律用 Gradle Kotlin DSL（KTS）、实现一律用 Kotlin（不写 Groovy DSL、不用 Java 写实现）。由此产出的构件同时被 **K1（Gradle 8.x / Kotlin 1.9）** 与 **K2（Gradle 9.x / Kotlin 2.x）** 环境的消费方加载使用。

## 理由
- 1.9 是当前能被 K1 与 K2 同时理解的稳妥下限：K2 向后兼容 1.9 语言级别，K1 原生即 1.9。
- 与 Gradle 插件生态「锁定较低 Kotlin API 版本覆盖 Gradle 版本范围」的常见做法同源。
- 纯 Kotlin/KTS 与消费方（同为 Kotlin 构建的 JVM 插件）一致，维护门槛低。

## 后果
- 不能在插件实现里使用 Kotlin 2.0+ 才有的语言特性（接受，能力够用）。
- 需在构建里显式设置 `languageVersion` / `apiVersion = "1.9"`（及相应 `jvmTarget`），并固定版本，避免随本机 Gradle/Kotlin 漂移。
- 升级支持的 Gradle 版本下限（如某天弃用 Gradle 8）时，再走新 ADR 调整该锁定。

## 备选方案
- **跟随本机 Kotlin 版本（不锁）**：会随构建环境漂移，可能编出只有 K2 能用的构件，落选。
- **锁更低（如 1.4）**：覆盖更老 Gradle，但用不上 1.5–1.9 的实用特性、且本项目无需支持那么老，落选。
- **锁 2.0（仅 K2）**：放弃 Gradle 8 消费方，过早，落选。
