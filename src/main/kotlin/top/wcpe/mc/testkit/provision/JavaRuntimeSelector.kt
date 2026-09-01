package top.wcpe.mc.testkit.provision

import top.wcpe.mc.testkit.config.MinecraftVersionGroup
import java.io.File

/**
 * 按 Minecraft 版本选择 Java 运行时（多版本服务端拉起）。
 *
 * 不同 Minecraft 版本要求不同 Java 版本（1.7.10–1.16.5 需 Java 8、1.17 需 Java 17、1.18+ 需 Java 17/21）。
 * 本对象经环境变量解析对应 Java home，回退到 `JAVA_HOME`，最终回退到当前 JVM：
 *
 * 1. **`MC_TESTKIT_JAVA_HOME_<版本段>`**（如 `MC_TESTKIT_JAVA_HOME_1_7` → 1.7.10、`MC_TESTKIT_JAVA_HOME_1_17` → 1.17.x）：
 *    版本段由 [MinecraftVersionGroup.javaVersionSegment] 计算（major.minor 用下划线连接）。
 * 2. **`MC_TESTKIT_JAVA_HOME_17`**：1.17–1.18 未设版本段变量时，可显式选择兼容的 Java 17。
 * 3. **`JAVA_HOME`**：未设专属变量时回退。
 * 4. **当前 JVM**：上述变量都未设时回退到 [javaExecutable]（运行编排插件的同一 JDK）。
 *
 * 纯函数边界：env 取值经注入的 [readEnv] 取值器，不直接读 `System.getenv`，便于穷举单测。
 */
object JavaRuntimeSelector {

    /** 版本段 Java home 环境变量前缀（完整名 = 前缀 + 版本段，如 `MC_TESTKIT_JAVA_HOME_1_7`）。 */
    const val ENV_PREFIX = "MC_TESTKIT_JAVA_HOME_"

    /** 通用回退 Java home 环境变量名。 */
    const val JAVA_HOME_ENV = "JAVA_HOME"

    /**
     * 解析指定版本对应的 Java home 目录。
     *
     * @param version Minecraft 版本（如 `1.7.10`）。
     * @param readEnv 环境变量取值器：给名、返回值（无则 null）。
     * @return Java home 目录路径；版本段变量 > `JAVA_HOME` > null（null 表示用当前 JVM）。
     */
    fun javaHome(version: String, readEnv: (String) -> String?): String? {
        val segment = MinecraftVersionGroup.javaVersionSegment(version)
        val versioned = readEnv(ENV_PREFIX + segment)?.takeIf { it.isNotBlank() }
        if (versioned != null) return versioned
        java17Home(version, readEnv)?.let { return it }
        return readEnv(JAVA_HOME_ENV)?.takeIf { it.isNotBlank() }
    }

    /** 1.17–1.18 的 Paper 不接受较新的 Java 时，允许统一指定 Java 17。 */
    private fun java17Home(version: String, readEnv: (String) -> String?): String? {
        if (!version.startsWith("1.17.") && !version.startsWith("1.18.")) return null
        return readEnv(ENV_PREFIX + "17")?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析指定版本对应的 `java` 可执行文件路径。
     *
     * 优先级：版本段变量 > `JAVA_HOME` > 当前 JVM（[javaExecutable]）。
     * 选定的 Java home 下按平台取 `bin/java` 或 `bin/java.exe`；文件不存在则回退为裸名 `java`（靠 PATH）。
     *
     * @param version Minecraft 版本（如 `1.7.10`）。
     * @param readEnv 环境变量取值器：给名、返回值（无则 null）。
     * @return `java` 可执行文件路径（绝对路径或裸名）。
     */
    fun executable(version: String, readEnv: (String) -> String?): String {
        val home = javaHome(version, readEnv) ?: return javaExecutable()
        val candidate = File(home, "bin").resolve(javaExecutableName())
        return if (candidate.isFile) candidate.absolutePath else javaExecutableName()
    }

    /**
     * 按显式 Java 主版本解析可执行文件。
     *
     * 此路径不允许回退 `JAVA_HOME` 或当前 JVM，避免声明 Java 25 却静默用错误版本启动代理。
     */
    fun requiredExecutableForMajor(javaVersion: Int, readEnv: (String) -> String?): String {
        val environment = ENV_PREFIX + javaVersion
        val home = readEnv(environment)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("mcTestkit 代理已声明 Java $javaVersion，但未设置环境变量「$environment」。")
        val executable = File(home, "bin").resolve(javaExecutableName())
        check(executable.isFile) {
            "mcTestkit 代理已声明 Java $javaVersion，但环境变量「$environment」指向的目录不含可执行文件：${executable.absolutePath}。"
        }
        return executable.absolutePath
    }

    private fun javaExecutableName(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
}
