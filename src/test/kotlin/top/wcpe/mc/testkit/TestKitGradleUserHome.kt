package top.wcpe.mc.testkit

import java.io.File

/**
 * 整个测试套件共用的 TestKit Gradle 用户主目录。
 *
 * TestKit 会在该目录下生成 `caches/8.9/generated-gradle-jars/gradle-api-8.9.jar`（约 171M）等
 * Gradle 运行期缓存。这些内容只取决于 Gradle 版本，与正在跑哪个测试无关——实测各目录内该 jar
 * 的 md5 完全一致，且与 `~/.gradle/caches/8.9/` 中的同名文件相同。
 *
 * 因此按测试类各建一份纯属重复：三份各 ~188M，加上 TestKit 的默认目录共 771M。改为共用一份后，
 * 该开销只发生一次。
 *
 * **为什么共用是安全的**：需要"空 Gradle 主目录"的测试，其判定依据都不在这个目录里——
 * 配置缓存与构建缓存落在测试工程的 `projectDir`（由 JUnit `@TempDir` 保证一例一目录），
 * 而服务端 jar 镜像区由 [MavenServerJarFunctionalTest] 在每个用例前清空。
 * 测试任务串行执行（未配置 `maxParallelForks`），不存在并发争用同一主目录的情况。
 */
val testKitGradleUserHome: File = File("build/test-kit-home/shared").canonicalFile
