// mc-testkit E2E 桩插件骨架（照抄物，框架无关的 Bukkit/Paper 插件）。
//
// 形态说明：
// - 这是一份**独立**的 Gradle 子工程，自带 settings.gradle.kts，不被 mc-testkit 编排插件依赖、
//   也不引用编排插件（架构不变量：template/ 是纯拷贝物）。
// - 消费方把整个 template/ 目录拷进自己仓库后，按需改 group / version / 依赖即可。
// - paper-api 仅 compileOnly：桩在真实服务端里由 PaperMC 提供运行期类，打包不含它。
plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.example.e2e"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    // PaperMC 官方仓库：提供 paper-api（桩编译期所需的 Bukkit/Paper API）
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 仅编译期依赖：运行期由真实 Paper 服务端提供，打入插件 jar 会冲突
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
}

kotlin {
    // 跟随 Paper 1.20.1 的 Java 基线（17）；换 MC 版本时同步调整
    jvmToolchain(17)
}

tasks.jar {
    archiveBaseName.set("mc-testkit-e2e-harness")
    // 把运行期依赖（kotlin-stdlib）打进插件 jar：桩是 Kotlin 写的，真实 Paper 服务端不提供
    // kotlin-stdlib，不打进来会在 onEnable 抛 NoClassDefFoundError: kotlin/jvm/internal/Intrinsics。
    // paper-api 是 compileOnly、不在 runtimeClasspath，故不会被打入（运行期由服务端提供）。
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
