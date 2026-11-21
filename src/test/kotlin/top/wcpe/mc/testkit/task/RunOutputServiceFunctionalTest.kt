package top.wcpe.mc.testkit.task

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.bot.GuardChild
import top.wcpe.mc.testkit.bot.fixtureClasspath
import top.wcpe.mc.testkit.bot.javaCommand
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** No Minecraft, network, Node or server tasks: only the real generated scenario hook tasks. */
@Timeout(120)
class RunOutputServiceFunctionalTest {
    @TempDir
    lateinit var dir: File
    private fun quoted(s: String) = "'" + s.replace("\\", "/").replace("'", "\\'") + "'"
    private fun consumer(bytes: Int) {
        File(dir, "settings.gradle").writeText("rootProject.name='guard-consumer'\n")
        val cmd = listOf(
            javaCommand(),
            "-cp",
            fixtureClasspath(),
            GuardChild::class.java.name,
            "burst",
            bytes.toString()
        ).joinToString(",") { quoted(it) }
        File(dir, "build.gradle").writeText(
            """
            import top.wcpe.mc.testkit.dsl.ExecHook
            import top.wcpe.mc.testkit.dsl.SleepHook
            plugins { id 'top.wcpe.mc-testkit' }
            mcTestkit {
              backend('s1') { s -> s.port=25571 }; backend('s2') { s -> s.port=25572 }
              proxy('wf') { p -> p.port=25577; p.routesTo('s1','s2') }
              scenario('full') { s ->
                s.backends('s1','s2'); s.via='wf'
                s.beforeScenario(new ExecHook([$cmd], [:], null, 'before.log', 'before.pid', 0, '', 2000L))
                s.beforeScenario(new SleepHook(700L,''))
                s.afterScenario(new ExecHook([$cmd], [:], null, 'after.log', 'after.pid', 0, '', 2000L))
                s.afterScenario(new SleepHook(700L,''))
              }
            }
            def directorResult = layout.projectDirectory.file('director.properties').asFile
            tasks.register('directorPass') {
              dependsOn('beforeFullScenario')
              doLast { directorResult.text='status=PASS' }
            }
            tasks.configureEach { t -> if (t.name == 'afterFullScenario') t.dependsOn('directorPass') }
            tasks.register('guardFinal') { dependsOn('afterFullScenario') }
        """.trimIndent()
        )
    }

    private fun runner() = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withArguments(
            "guardFinal", "--offline", "--configuration-cache", "--console=plain", "-q",
            "-Dmc.testkit.bot.log.limit.bytes=65536", "-Dmc.testkit.bot.log.runLimit.bytes=98304"
        )

    @Test
    fun realGeneratedTaskFailsAfterDirectorPassAndAcrossTwoHookLaunches() {
        consumer(48 * 1024)
        val started = System.nanoTime()
        val result = runner().buildAndFail()
        save("task-output", result.output.takeLast(6000))
        assertTrue(result.output.contains("OUTPUT_LIMIT"), result.output.takeLast(2000))
        assertTrue(File(dir, "director.properties").readText().contains("PASS"))
        assertTrue(result.output.contains("OUTPUT_LIMIT"), result.output.takeLast(2000))
        val results = File(dir, "build/mc-testkit/results")
        val written = results.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(written <= 98304, "actual run disk bytes=$written")
        assertTrue(File(results, "output-run.state").readText().startsWith("RUN_FAIL"))
        val second = runner().buildAndFail()
        assertTrue(second.output.contains("prior-output-owner-not-successful"))
        save(
            "task-fail",
            "bytes=$written\nelapsedMs=${(System.nanoTime() - started) / 1000000}\n" + result.output.takeLast(3000)
        )
    }

    @Test
    fun configurationCacheReuseCreatesFreshRunOwner() {
        consumer(1024)
        runner().build()
        val state = File(dir, "build/mc-testkit/results/output-run.state")
        val first = state.readText()
        assertTrue(first.startsWith("CLOSED_OK"), first)
        val next = runner().build()
        val second = state.readText()
        assertTrue(second.startsWith("CLOSED_OK"), second)
        assertNotEquals(first.lines()[1], second.lines()[1], "CC must not revive the old run UUID")
        assertEquals(first.lines()[2], second.lines()[2], "usage must start fresh in a new build")
        save("cc-reuse", first + "\n---\n" + second + "\n" + next.output.takeLast(1500))
    }

    @Test
    fun startOnlyBuildFailsFromServiceCloseAndDoesNotLeaveChild() {
        consumer(0)
        val script = File(dir, "build.gradle")
        script.writeText(script.readText().replace("'burst'", "'sleep'").replace("700L", "100L"))
        val result = runner().buildAndFail()
        assertTrue(result.output.contains("detached-child-at-build-end"), result.output.takeLast(2000))
        val state = File(dir, "build/mc-testkit/results/output-run.state").readText()
        assertTrue(state.startsWith("RUN_FAIL"))
        val pids = File(dir, "build/mc-testkit/results").listFiles()!!.filter { it.extension == "pid" }
            .map { it.readText().trim().toLong() }
        assertTrue(pids.all { ProcessHandle.of(it).map { p -> !p.isAlive }.orElse(true) })
        save("service-close-fail", state + "\nchildrenAlive=0\n" + result.output.takeLast(2000))
    }

    private fun save(name: String, text: String) {
        System.getProperty("guard.evidence")?.let { File(it).apply { mkdirs() }.resolve("$name.txt").writeText(text) }
    }
}
