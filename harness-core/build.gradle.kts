// harness-core：mc-testkit E2E 桩插件公共协议胶水库（共享胶水构件）。
//
// 形态说明：
// - **刻意纯 Java、零 Kotlin 依赖**：消费方（如 MultiCurrencyEconomy 的 e2e-harness）插件 jar 内
//   若带 kotlin.* 引用，需额外打包 kotlin-stdlib 并冒 Paper 插件类加载器找不到
//   kotlin/jvm/internal/Intrinsics 的风险；纯 Java 库可被 Kotlin / Java 两种桩直接依赖。
// - paper-api 仅 compileOnly：Bukkit 基类（McTestkitHarnessPlugin）运行期由真实服务端提供，
//   库本身与纯 JDK 部分（McTestkitEnv / McTestkitResultWriter）不依赖任何 Bukkit 类。
// - 发布到 maven.wcpe.top，凭据走 Gradle 属性 / 环境变量（与根工程同款约定，不入库）。
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    `java-library`
    `maven-publish`
}

group = "top.wcpe.mc"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    // 字节码锁 Java 8（见下方 JavaCompile release），以覆盖 FR-21 旧版服务端
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 产出 Java 8 兼容字节码：同一 harness 可在 Paper 1.7.10（Java 8）～ 1.21（Java 21）加载。
// 旧版本烟雾（FR-21 8 代表版本）依赖此基线；升到 17 会让 1.7–1.16 起服时 UnsupportedClassVersionError。
// compile classpath 仍按 JVM 17 解析 paper-api（其 POM 声明 TargetJvmVersion=17），否则 release=8 会解析失败。
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

configurations.named("compileClasspath") {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
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
