package top.wcpe.mc.testkit.console

import java.io.File

/**
 * 拼出**可直接粘贴**的附加控制台 attach 命令（serve 就绪提示里打印）。
 *
 * 客户端随插件 jar 发布，因此类路径要由框架自己算：进程内的 `java.class.path` 不包含插件自身的类路径
 * （插件跑在 Gradle 的插件类加载器里），故按类源（`codeSource`）取——本客户端、Kotlin、JLine 与 JNA 运行时所在的构件。
 * 消费方不需要自己拼路径，也不需要知道 jar 在哪里。
 */
internal object AttachCommand {

    /** 生成命令文本（路径按平台分隔符拼接；含空格的路径整体加引号）。 */
    fun text(
        port: Int,
        token: String,
        host: String = "127.0.0.1",
        classpath: List<File> = classpathEntries(),
        javaExecutable: String = defaultJavaExecutable(),
    ): String {
        val path = classpath.joinToString(File.pathSeparator) { it.absolutePath }
        // 用当前 JVM 的绝对路径而不是裸 `java`：用户 PATH 里未必有 java（Gradle 用的是 JAVA_HOME）。
        return "\"$javaExecutable\" -cp \"$path\" ${ServeConsoleAttach::class.java.name} --host $host --port $port --token $token"
    }

    /** 当前 JVM 的 java 可执行文件绝对路径。 */
    fun defaultJavaExecutable(): String {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        return File(File(System.getProperty("java.home"), "bin"), executable).absolutePath
    }

    /** 客户端运行所需的类路径：客户端、Kotlin、JLine 与 JNA 的代码源。 */
    fun classpathEntries(): List<File> {
        val classes = buildList {
            add(ServeConsoleAttach::class.java)
            add(Unit::class.java)
            JLINE_RUNTIME_CLASS_NAMES.mapNotNullTo(this) { loadClass(it) }
        }
        return classes
            .mapNotNull { it.protectionDomain?.codeSource?.location }
            .map { File(it.toURI()) }
            .distinctBy { it.absolutePath }
    }

    /** 从当前插件类加载器可见的代码中定位运行时依赖，不依赖本机缓存路径。 */
    private fun loadClass(name: String): Class<*>? = sequenceOf(
        ServeConsoleAttach::class.java.classLoader,
        Thread.currentThread().contextClassLoader,
    ).filterNotNull().mapNotNull { loader ->
        runCatching { Class.forName(name, false, loader) }.getOrNull()
    }.firstOrNull()

    private val JLINE_RUNTIME_CLASS_NAMES = listOf(
        "org.jline.terminal.Terminal",
        "org.jline.terminal.TerminalBuilder",
        "org.jline.terminal.spi.JnaSupport",
        "org.jline.terminal.impl.jna.JnaNativePty",
        "com.sun.jna.Native",
    )
}
