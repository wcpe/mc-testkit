package top.wcpe.mc.testkit.task

import org.gradle.api.GradleException
import top.wcpe.mc.testkit.config.PaperConfigAdapter
import top.wcpe.mc.testkit.config.ServerProperties
import top.wcpe.mc.testkit.config.editYaml
import top.wcpe.mc.testkit.config.setNested
import top.wcpe.mc.testkit.contract.McTestkitEnv
import top.wcpe.mc.testkit.dsl.DependenciesSpec
import top.wcpe.mc.testkit.topology.ResolvedBackend
import top.wcpe.mc.testkit.topology.ResolvedProxy
import java.io.File
import java.util.Locale

/** 已预检的单个后端运行时资源。 */
internal data class BackendRuntimeResources(
    val templateDirectory: File?,
    val dependencyJars: List<ResolvedPluginJar>,
)

/** 已预检的单个代理运行时资源。 */
internal data class ProxyRuntimeResources(
    val templateDirectory: File?,
    val plugins: List<File>,
)

/** 一项任务涉及的全部节点资源；创建后即可安全进入目录清理与进程启动阶段。 */
internal data class NodeRuntimePreflight(
    val backends: Map<String, BackendRuntimeResources>,
    val proxies: Map<String, ProxyRuntimeResources>,
)

/** 预检一次任务涉及的全部后端与代理资源，任一失败时不产生目录副作用。 */
internal fun preflightNodeRuntime(
    projectDirectory: File,
    dependencies: DependenciesSpec,
    backends: List<ResolvedBackend>,
    proxies: List<ResolvedProxy>,
    legacyTemplatePath: String?,
    readEnv: (String) -> String?,
): NodeRuntimePreflight {
    val dependencyJars = if (backends.isEmpty()) emptyList() else resolveDependencyJars(dependencies, readEnv)
    val backendResources = backends.associate { backend ->
        backend.name to resolveBackendRuntimeResources(projectDirectory, backend, legacyTemplatePath, readEnv, dependencyJars)
    }
    val proxyResources = proxies.associate { proxy ->
        proxy.name to resolveProxyRuntimeResources(projectDirectory, proxy, readEnv)
    }
    return NodeRuntimePreflight(backendResources, proxyResources)
}

/** 解析后端节点模板；节点声明优先，未声明时兼容旧全局模板路径。 */
internal fun resolveBackendRuntimeResources(
    projectDirectory: File,
    backend: ResolvedBackend,
    legacyTemplatePath: String?,
    readEnv: (String) -> String?,
    dependencyJars: List<ResolvedPluginJar> = emptyList(),
): BackendRuntimeResources {
    val template = backend.templateDirectory?.let { declaration ->
        resolveRuntimeResource(projectDirectory, "后端", backend.name, declaration, RuntimeResourceType.DIRECTORY, readEnv)
    } ?: legacyTemplatePath?.takeIf { it.isNotBlank() }?.let { path ->
        resolveLegacyResource(
            "后端",
            backend.name,
            McTestkitEnv.SERVER_TEMPLATE_DIR,
            path,
            RuntimeResourceType.DIRECTORY,
        )
    }
    return BackendRuntimeResources(template, dependencyJars)
}

/** 解析代理模板与专属插件，并拒绝多个声明落到同一目标文件名。 */
internal fun resolveProxyRuntimeResources(
    projectDirectory: File,
    proxy: ResolvedProxy,
    readEnv: (String) -> String?,
): ProxyRuntimeResources {
    val template = proxy.templateDirectory?.let { declaration ->
        resolveRuntimeResource(projectDirectory, "代理", proxy.name, declaration, RuntimeResourceType.DIRECTORY, readEnv)
    }
    val plugins = proxy.plugins.map { declaration ->
        resolveRuntimeResource(projectDirectory, "代理", proxy.name, declaration, RuntimeResourceType.JAR, readEnv)
    }
    val conflict = plugins.groupBy { it.name.lowercase(Locale.ROOT) }.entries.firstOrNull { it.value.size > 1 }
    if (conflict != null) {
        throw GradleException(
            "mcTestkit 代理「${proxy.name}」的插件目标文件名冲突：「${conflict.value.first().name}」由多个 plugin(...) 声明解析得到；" +
                "请让各代理插件使用不同文件名，避免含糊覆盖。",
        )
    }
    return ProxyRuntimeResources(template, plugins)
}

/** 节点环境最后叠加框架环境；宿主环境由 ProcessBuilder 继承，节点映射会覆盖其同名值。 */
internal fun mergeNodeEnvironment(
    nodeEnvironment: Map<String, String>,
    frameworkEnvironment: Map<String, String>,
): Map<String, String> = LinkedHashMap<String, String>().apply {
    putAll(nodeEnvironment)
    putAll(frameworkEnvironment)
}

/** 后端顺序：清理可重建内容 → 铺模板 → 写权威基础配置 → 注入 dependencies。 */
internal fun stageBackendRuntime(
    runDirectory: File,
    backend: ResolvedBackend,
    resources: BackendRuntimeResources,
    logger: (String) -> Unit = {},
) {
    cleanRunDirPreservingRuntimeCaches(runDirectory)
    requireDirectory(runDirectory, "后端「${backend.name}」运行目录")
    resources.templateDirectory?.let { template ->
        copyTemplate(template, runDirectory, BACKEND_TEMPLATE_EXCLUDES)
        logger("已铺后端 ${backend.name} 模板：${template.absolutePath} → ${runDirectory.name}")
    }
    writeBackendAuthority(runDirectory, backend)
    injectBackendDependencies(runDirectory, resources.dependencyJars, logger)
}

/** 代理顺序：整目录清理 → 铺模板 → 写权威配置 → 平台准备 → 注入代理专属插件。 */
internal fun stageProxyRuntime(
    runDirectory: File,
    resources: ProxyRuntimeResources,
    writeFrameworkConfiguration: () -> Unit,
    preparePlatformRuntime: () -> Unit,
    logger: (String) -> Unit = {},
) {
    if (runDirectory.exists() && !runDirectory.deleteRecursively()) {
        throw GradleException("无法清理代理运行目录：${runDirectory.absolutePath}；请关闭占用文件的进程后重试。")
    }
    requireDirectory(runDirectory, "代理运行目录")
    resources.templateDirectory?.let { template ->
        copyTemplate(template, runDirectory, PROXY_TEMPLATE_EXCLUDES)
        logger("已铺代理模板：${template.absolutePath} → ${runDirectory.name}")
    }
    writeFrameworkConfiguration()
    preparePlatformRuntime()
    val pluginsDirectory = File(runDirectory, "plugins").apply { mkdirs() }
    resources.plugins.forEach { plugin ->
        plugin.copyTo(File(pluginsDirectory, plugin.name), overwrite = true)
        logger("已注入代理插件：${plugin.name} → plugins/${plugin.name}")
    }
}

private enum class RuntimeResourceType(val expected: String) {
    DIRECTORY("存在的目录"),
    JAR("存在的普通 .jar 文件"),
}

private fun resolveRuntimeResource(
    projectDirectory: File,
    nodeType: String,
    nodeName: String,
    declaration: String,
    type: RuntimeResourceType,
    readEnv: (String) -> String?,
): File {
    val environmentValue = readEnv(declaration)?.takeIf { it.isNotBlank() }
    val rawPath = environmentValue ?: declaration
    val resource = resolveProjectPath(projectDirectory, rawPath)
    if (matchesType(resource, type)) return resource
    val source = if (environmentValue != null) "采用环境变量「$declaration」的非空值后，" else ""
    throw resourceError(nodeType, nodeName, declaration, resource, type, source)
}

/** 旧全局模板环境变量保持 v0.4.2 的 File(raw) 语义，相对 JVM/Gradle 当前工作目录解析。 */
private fun resolveLegacyResource(
    nodeType: String,
    nodeName: String,
    declaration: String,
    rawPath: String,
    type: RuntimeResourceType,
): File {
    val resource = File(rawPath).canonicalFile
    if (matchesType(resource, type)) return resource
    throw resourceError(nodeType, nodeName, declaration, resource, type, "采用该环境变量值后，")
}

private fun resourceError(
    nodeType: String,
    nodeName: String,
    declaration: String,
    resource: File,
    type: RuntimeResourceType,
    source: String,
): GradleException = GradleException(
    "mcTestkit $nodeType「$nodeName」资源声明「$declaration」${source}实际解析路径「${resource.absolutePath}」不是${type.expected}；" +
        "请修正环境变量或声明路径后重试。",
)

private fun resolveProjectPath(projectDirectory: File, rawPath: String): File {
    val declared = File(rawPath)
    val resolved = if (declared.isAbsolute) declared else File(projectDirectory, rawPath)
    return resolved.canonicalFile
}

private fun matchesType(resource: File, type: RuntimeResourceType): Boolean = when (type) {
    RuntimeResourceType.DIRECTORY -> resource.isDirectory
    RuntimeResourceType.JAR -> resource.isFile && resource.extension.equals("jar", ignoreCase = true)
}

private fun writeBackendAuthority(runDirectory: File, backend: ResolvedBackend) {
    File(runDirectory, "eula.txt").writeText("eula=true\n")
    val rawOverrides = linkedMapOf(
        ServerProperties.SERVER_PORT to backend.port.toString(),
        ServerProperties.ONLINE_MODE to "false",
        ServerProperties.ENFORCE_SECURE_PROFILE to "false",
        ServerProperties.LEVEL_TYPE to "minecraft:flat",
    )
    if (!ServerProperties.load(runDirectory).containsKey(ServerProperties.DIFFICULTY)) {
        rawOverrides[ServerProperties.DIFFICULTY] = "peaceful"
    }
    // 按版本过滤不支持的键 + 转换 level-type（FR-21）
    val overrides = ServerProperties.versionAwareOverrides(backend.version, rawOverrides)
    ServerProperties.edit(runDirectory, overrides)
    // 按版本写 Paper 配置（1.7–1.12 跳过、1.13–1.18 paper.yml、1.19+ paper-global.yml，FR-21）
    PaperConfigAdapter.forVersion(backend.version)?.let { config ->
        editYaml(File(runDirectory, config.fileName)) { root ->
            setNested(root, config.path, config.value)
        }
    }
}

private fun injectBackendDependencies(
    runDirectory: File,
    dependencies: List<ResolvedPluginJar>,
    logger: (String) -> Unit,
) {
    val pluginsDirectory = File(runDirectory, "plugins").apply { mkdirs() }
    dependencies.forEach { dependency ->
        val targetName = if (dependency.underTest) "plugin-under-test.jar" else dependency.jar.name
        dependency.jar.copyTo(File(pluginsDirectory, targetName), overwrite = true)
        logger("已注入后端插件：${dependency.jar.name} → plugins/$targetName")
    }
}

private fun requireDirectory(directory: File, label: String) {
    if (!directory.mkdirs() && !directory.isDirectory) {
        throw GradleException("无法创建$label：${directory.absolutePath}。")
    }
}

private fun copyTemplate(source: File, target: File, excludedTopLevelEntries: Set<String>) {
    source.walkTopDown()
        .onEnter { directory -> !isExcluded(source, directory, excludedTopLevelEntries) }
        .forEach { entry ->
            if (entry == source) return@forEach
            val relative = entry.relativeTo(source).invariantSeparatorsPath
            if (isExcluded(source, entry, excludedTopLevelEntries) || isRuntimeArtifact(relative, entry)) return@forEach
            val destination = File(target, relative)
            if (entry.isDirectory) destination.mkdirs() else entry.copyTo(destination.apply { parentFile?.mkdirs() }, overwrite = true)
        }
}

private fun isExcluded(source: File, entry: File, excludedTopLevelEntries: Set<String>): Boolean {
    if (entry == source) return false
    val topLevel = entry.relativeTo(source).invariantSeparatorsPath.substringBefore('/')
    return topLevel in excludedTopLevelEntries
}

private fun isRuntimeArtifact(relative: String, entry: File): Boolean =
    entry.name.endsWith(".pid", ignoreCase = true) || (!relative.contains('/') && entry.name.endsWith(".log", ignoreCase = true))

private val BACKEND_TEMPLATE_EXCLUDES = setOf("world", "world_nether", "world_the_end", "logs")
private val PROXY_TEMPLATE_EXCLUDES = setOf("logs")
