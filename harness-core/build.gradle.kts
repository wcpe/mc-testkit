// harness-core：mc-testkit E2E 桩插件公共协议胶水库（共享胶水构件）。
//
// 形态说明：
// - **刻意纯 Java、零 Kotlin 依赖**：消费方（如 MultiCurrencyEconomy 的 e2e-harness）插件 jar 内
//   若带 kotlin.* 引用，需额外打包 kotlin-stdlib 并冒 Paper 插件类加载器找不到
//   kotlin/jvm/internal/Intrinsics 的风险；纯 Java 库可被 Kotlin / Java 两种桩直接依赖。
// - paper-api 仅 compileOnly：Bukkit 基类（McTestkitHarnessPlugin）运行期由真实服务端提供，
//   库本身与纯 JDK 部分（McTestkitEnv / McTestkitResultWriter）不依赖任何 Bukkit 类。
// - 发布到 maven.wcpe.top，凭据走 Gradle 属性 / 环境变量（与根工程同款约定，不入库）。
plugins {
    `java-library`
    `maven-publish`
}

group = "top.wcpe.mc"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    // 跟随 Paper 1.20.1 服务端 Java 基线（17）；换更高 MC 版本时同步调整
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 由当前 JVM 编译、产出 Java 17 兼容字节码（不依赖工具链探测——本机 Gradle 工具链探测对所有
// JDK 均返回退出码 1，环境级问题，见 MCE e2e-harness 同款注释）。
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "harness-core"
        }
    }
    repositories {
        maven {
            name = "wcpe"
            url = uri(
                if (version.toString().endsWith("SNAPSHOT")) {
                    "https://maven.wcpe.top/repository/maven-snapshots/"
                } else {
                    "https://maven.wcpe.top/repository/maven-releases/"
                },
            )
            credentials {
                username = providers.gradleProperty("WCPE_MAVEN_USERNAME").orNull
                    ?: providers.environmentVariable("WCPE_MAVEN_USERNAME").orNull
                password = providers.gradleProperty("WCPE_MAVEN_PASSWORD").orNull
                    ?: providers.environmentVariable("WCPE_MAVEN_PASSWORD").orNull
            }
        }
    }
}
