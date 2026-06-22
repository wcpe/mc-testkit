import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// mc-testkit：Minecraft 插件端到端测试编排 Gradle 插件。
// 形态：java-gradle-plugin + kotlin-dsl（纯 Kotlin + KTS）；服务端/代理由插件内置模块自下载/运行（ADR-0001），Kotlin 版本锁见 ADR-0005。
plugins {
    `kotlin-dsl`
    `maven-publish`
}

// 版本号唯一真源：根 VERSION 文件（架构不变量，禁止在别处硬编码版本）。
group = "top.wcpe.mc"
version = file("VERSION").readText().trim()

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // 真实 YAML 读写：代理 config.yml 的对象化生成、后端 spigot.yml / paper-global.yml 的
    // 加载→深合并→写回（取代字符串/正则替换）。版本锁定且与 Gradle 8.x 运行时一致，避免插件类加载器冲突。
    implementation("org.yaml:snakeyaml:2.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle TestKit：以消费者视角驱动插件，校验任务注册与配置期行为（集成测试）
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 对外契约①：插件 id 与实现类（一经发布即契约，见 docs/API.md）。
gradlePlugin {
    plugins {
        create("mcTestkit") {
            id = "top.wcpe.mc-testkit"
            implementationClass = "top.wcpe.mc.testkit.McTestkitPlugin"
            displayName = "mc-testkit"
            description = "Minecraft 插件端到端测试编排 Gradle 插件（内置服务端/代理下载与运行）"
        }
    }
}

// Kotlin 语言/API 版本锁 1.9：保证构件同时被 K1（Gradle 8.x / Kotlin 1.9）与
// K2（Gradle 9.x / Kotlin 2.x）消费方加载（ADR-0005）。
// 注意：kotlin-dsl 插件会把语言/API 版本钉到 1.8（为预编译脚本前向兼容），需在**任务级**
// 显式覆盖才会生效（configureEach 晚于 kotlin-dsl 的配置，故此处会赢）。jvmTarget 跟随
// kotlin-dsl 默认以求最大兼容，不擅自抬高。
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// 发布到 maven.wcpe.top；凭据走 Gradle 属性（~/.gradle/gradle.properties）或同名环境变量，不入库
// （见 SECURITY / static-analysis 规则）。
publishing {
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
