// evidence-core：mc-testkit 证据链内核（语义动作总线 / 录屏控制 / HTML 证据编译器）。
//
// 形态纪律与 harness-core 相同：纯 Java、零第三方依赖——消费方会把构件带进
// 编排类路径甚至桩插件 jar，任何外部库都是冲突与体积来源。
// 字节码锁 Java 8：宿主机 JDK 各异，证据链没有道理要求新高版本。
plugins {
    `java-library`
    `maven-publish`
}

group = "top.wcpe.mc"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
