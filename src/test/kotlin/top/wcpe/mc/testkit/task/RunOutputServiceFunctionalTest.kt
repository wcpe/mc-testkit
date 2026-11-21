package top.wcpe.mc.testkit.task

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.testkit.bot.GuardChild
import top.wcpe.mc.testkit.bot.ProcessIdentity
import top.wcpe.mc.testkit.bot.ProcessLedger
import top.wcpe.mc.testkit.bot.fixtureClasspath
import top.wcpe.mc.testkit.bot.javaCommand
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 不起 Minecraft、网络、Node 或服务端任务，只跑真实生成的场景钩子任务。 */
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
            bytes.toString(),
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
            """.trimIndent(),
        )
    }

    private fun runner() = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withArguments(
            "guardFinal",
            "--offline",
            "--configuration-cache",
            "--console=plain",
            "-q",
            "-Dmc.testkit.bot.log.limit.bytes=65536",
            "-Dmc.testkit.bot.log.runLimit.bytes=98304",
        )

    @Test
    @DisplayName("两次钩子输出合计越过构建上限：导演判 PASS 也必须失败；下一次构建照常接手、不被连坐")
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
        val first = File(results, "output-run.state").readText()
        assertTrue(first.startsWith("RUN_FAIL"), first)
        assertEquals("live=", first.lines()[4], "收尾必须确认受监管进程都已结束")
        // 上一次的失败已确认收尾干净：第二次构建自己接手，并因同样的越限再次失败，而不是被上一次连坐。
        val second = runner().buildAndFail()
        assertFalse(second.output.contains("prior-output-owner-not-successful"), second.output.takeLast(2000))
        assertTrue(second.output.contains("OUTPUT_LIMIT"), second.output.takeLast(2000))
        val next = File(results, "output-run.state").readText()
        assertTrue(next.startsWith("RUN_FAIL"), next)
        assertNotEquals(first.lines()[1], next.lines()[1], "第二次构建必须以自己的 runId 接手")
        save(
            "task-fail",
            "bytes=$written\nelapsedMs=${(System.nanoTime() - started) / 1000000}\n" + result.output.takeLast(3000),
        )
    }

    @Test
    @DisplayName("配置缓存复用时每次构建都是新的输出所有者，用量从零计")
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
    @DisplayName("构建结束时仍有没停的钩子进程：收尾杀掉并判失败，状态记下无残留")
    fun startOnlyBuildFailsFromServiceCloseAndDoesNotLeaveChild() {
        consumer(0)
        val script = File(dir, "build.gradle")
        script.writeText(script.readText().replace("'burst'", "'sleep'").replace("700L", "100L"))
        val result = runner().buildAndFail()
        assertTrue(result.output.contains("detached-child-at-build-end"), result.output.takeLast(2000))
        val state = File(dir, "build/mc-testkit/results/output-run.state").readText()
        assertTrue(state.startsWith("RUN_FAIL"))
        assertEquals("live=", state.lines()[4], "被收尾杀掉的进程必须确认已结束")
        val pids = File(dir, "build/mc-testkit/results").listFiles()!!.filter { it.extension == "pid" }
            .map { it.readLines(Charsets.UTF_8).first().trim().toLong() }
        assertTrue(pids.all { ProcessHandle.of(it).map { p -> !p.isAlive }.orElse(true) })
        save("service-close-fail", state + "\nchildrenAlive=0\n" + result.output.takeLast(2000))
    }

    @Test
    @DisplayName("旧版本写下的 RUNNING（没有持有者）：持续拒绝且不覆盖记录，删掉状态文件后恢复")
    fun interruptedPriorRunKeepsRefusingUntilItsStateIsCleared() {
        consumer(1024)
        val state = File(dir, "build/mc-testkit/results/output-run.state")
        state.parentFile.mkdirs()
        val interrupted = "RUNNING\n00000000-0000-0000-0000-000000000000\n"
        state.writeText(interrupted)
        repeat(2) {
            val refused = runner().buildAndFail()
            assertTrue(
                refused.output.contains("prior-output-owner-not-successful state=RUNNING"),
                refused.output.takeLast(2000),
            )
            assertEquals(interrupted, state.readText(), "被拒的构建不得覆盖它据以拒绝的记录")
        }
        assertTrue(state.delete())
        runner().build()
        assertTrue(state.readText().startsWith("CLOSED_OK"), state.readText())
    }

    @Test
    @DisplayName("上一次构建停在 RUNNING 时停止任务照常执行，不接手也不改写记录")
    fun stopTaskRunsWhilePriorRunIsUnconfirmed() {
        consumer(1024)
        val state = File(dir, "build/mc-testkit/results/output-run.state")
        state.parentFile.mkdirs()
        val interrupted = "RUNNING\n00000000-0000-0000-0000-000000000000\n"
        state.writeText(interrupted)
        val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
            .withArguments("stopFullCluster", "--offline", "--configuration-cache", "--console=plain")
            .build()
        assertFalse(
            result.output.contains("prior-output-owner-not-successful"),
            "停止任务不得被上一次构建的记录拦下：${result.output.takeLast(2000)}",
        )
        assertEquals(interrupted, state.readText(), "停止任务不接手结果目录，记录必须保持原样")
    }

    @Test
    @DisplayName("上一次构建留下的 bot 与收尾没停下的进程：stop 任务按台账与状态记录收掉，下一次构建自己接手")
    fun stopTaskReapsEarlierBuildProcessesAndNextBuildTakesOver() {
        consumer(1024)
        val results = File(dir, "build/mc-testkit/results").apply { mkdirs() }
        val bot = sleeper()
        val survivor = sleeper()
        try {
            ProcessLedger.record(results, "bot/full", bot.pid(), 0)
            val identity = assertNotNull(ProcessIdentity.of(survivor.toHandle()), "前提：取得到启动时刻")
            val state = File(results, RunOutputState.FILE_NAME)
            state.writeText(RunOutputState.closed("00000000-0000-0000-0000-000000000001", 1024, "TIMEOUT log=bot-full.log", listOf(identity)))

            val stop = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
                .withArguments("stopFullCluster", "--offline", "--configuration-cache", "--console=plain")
                .build()

            assertTrue(bot.waitFor(20, TimeUnit.SECONDS), "台账里上一次构建的 bot 应被 stop 任务收掉：${stop.output.takeLast(2000)}")
            assertTrue(survivor.waitFor(20, TimeUnit.SECONDS), "状态记录里没停下的进程应被 stop 任务收掉：${stop.output.takeLast(2000)}")
            assertTrue(ProcessLedger.records(results).isEmpty(), "收掉后台账记录应注销")
            runner().build()
            assertTrue(state.readText().startsWith("CLOSED_OK"), "下一次构建应接手并成功收尾：${state.readText()}")
        } finally {
            bot.destroyForcibly()
            survivor.destroyForcibly()
        }
    }

    @Test
    @DisplayName("只有直连场景的工程：拒绝接手时指向 stopE2eProcesses，它收掉之前的构建留下的后端、bot 与没停下的进程，下一次构建自己接手")
    fun stopProcessesRecoversDirectOnlyProject() {
        File(dir, "settings.gradle").writeText("rootProject.name='direct-consumer'\n")
        File(dir, "build.gradle").writeText(
            """
            import top.wcpe.mc.testkit.dsl.SleepHook
            plugins { id 'top.wcpe.mc-testkit' }
            mcTestkit {
              backend('s1') { s -> s.port=25581 }
              scenario('solo') { s -> s.backend='s1'; s.beforeScenario(new SleepHook(100L,'')) }
            }
            """.trimIndent(),
        )
        val results = File(dir, "build/mc-testkit/results").apply { mkdirs() }
        val backend = sleeper()
        val bot = sleeper()
        val survivor = sleeper()
        try {
            ProcessLedger.record(results, "backend/s1", backend.pid(), 25581)
            ProcessLedger.record(results, "bot/solo", bot.pid(), 0)
            val identity = assertNotNull(ProcessIdentity.of(survivor.toHandle()), "前提：取得到启动时刻")
            val state = File(results, RunOutputState.FILE_NAME)
            val record = RunOutputState.closed("00000000-0000-0000-0000-000000000002", 1024, "TIMEOUT log=bot-solo.log", listOf(identity))
            state.writeText(record)

            val refused = direct("beforeSoloScenario").buildAndFail()
            assertTrue(refused.output.contains("./gradlew stopE2eProcesses"), "拒绝接手时应指向 stopE2eProcesses：${refused.output.takeLast(2000)}")

            val stop = direct("stopE2eProcesses").build()
            listOf(backend, bot, survivor).forEach { process ->
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "之前的构建留下的进程应被收掉：${stop.output.takeLast(2000)}")
            }
            assertTrue(ProcessLedger.records(results).isEmpty(), "收掉后台账记录应注销")
            assertEquals(record, state.readText(), "stopE2eProcesses 不接手结果目录，记录必须保持原样")

            direct("beforeSoloScenario").build()
            assertTrue(state.readText().startsWith("CLOSED_OK"), "下一次构建应接手并成功收尾：${state.readText()}")
        } finally {
            listOf(backend, bot, survivor).forEach(Process::destroyForcibly)
        }
    }

    @Test
    @DisplayName("上一次构建被强杀（停在 RUNNING、持有者已退出）且台账里还有它的 bot：拒绝并指向 stopE2eProcesses，收掉后下一次构建自己接手")
    fun killedPriorRunWithLiveBotRecoversAfterStopProcesses() {
        consumer(1024)
        val results = File(dir, "build/mc-testkit/results").apply { mkdirs() }
        val bot = sleeper()
        try {
            ProcessLedger.record(results, "bot/full", bot.pid(), 0)
            val state = File(results, RunOutputState.FILE_NAME)
            val killed = RunOutputState.running("00000000-0000-0000-0000-000000000003", exitedProcess())
            state.writeText(killed)

            val refused = runner().buildAndFail()
            assertTrue(refused.output.contains("state=RUNNING run=00000000-0000-0000-0000-000000000003 owner=ended ledger-live=1"), refused.output.takeLast(2000))
            assertTrue(refused.output.contains("./gradlew stopE2eProcesses"), refused.output.takeLast(2000))
            assertTrue(refused.output.contains("bot/full"), "拒绝时应列出台账里仍存活的进程：${refused.output.takeLast(2000)}")
            assertEquals(killed, state.readText(), "被拒的构建不得覆盖它据以拒绝的记录")

            val stop = direct("stopE2eProcesses").build()
            assertTrue(bot.waitFor(20, TimeUnit.SECONDS), "台账里上一次构建的 bot 应被收掉：${stop.output.takeLast(2000)}")

            runner().build()
            assertTrue(state.readText().startsWith("CLOSED_OK"), "下一次构建应自己接手并成功收尾：${state.readText()}")
        } finally {
            bot.destroyForcibly()
        }
    }

    @Test
    @DisplayName("持有记录的构建还在跑（停在 RUNNING、持有者仍存活）：拒绝并提示等它结束；它退出后台账里没有存活进程，下一次构建自己接手")
    fun liveOwnerIsWaitedForThenAbandonedRunIsTakenOver() {
        consumer(1024)
        val state = File(dir, "build/mc-testkit/results/${RunOutputState.FILE_NAME}").apply { parentFile.mkdirs() }
        val owner = sleeper()
        try {
            val identity = assertNotNull(ProcessIdentity.of(owner.toHandle()), "前提：取得到启动时刻")
            val running = RunOutputState.running("00000000-0000-0000-0000-000000000004", identity)
            state.writeText(running)

            val refused = runner().buildAndFail()
            assertTrue(refused.output.contains("state=RUNNING run=00000000-0000-0000-0000-000000000004 owner=$identity"), refused.output.takeLast(2000))
            assertTrue(refused.output.contains("等它结束"), refused.output.takeLast(2000))
            assertEquals(running, state.readText(), "被拒的构建不得覆盖它据以拒绝的记录")

            assertTrue(owner.destroyForcibly().waitFor(20, TimeUnit.SECONDS), "前提：持有者已退出")
            runner().build()
            assertTrue(state.readText().startsWith("CLOSED_OK"), "持有者退出后应自己接手并成功收尾：${state.readText()}")
        } finally {
            owner.destroyForcibly()
        }
    }

    /** 一个已经退出的进程的身份，充当被强杀的构建 JVM。 */
    private fun exitedProcess(): ProcessIdentity {
        val process = sleeper()
        val identity = assertNotNull(ProcessIdentity.of(process.toHandle()), "前提：取得到启动时刻")
        assertTrue(process.destroyForcibly().waitFor(20, TimeUnit.SECONDS), "前提：进程已退出")
        return identity
    }

    private fun direct(task: String) = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withArguments(task, "--offline", "--configuration-cache", "--console=plain")

    private fun sleeper(): Process =
        ProcessBuilder(javaCommand(), "-cp", fixtureClasspath(), GuardChild::class.java.name, "long-sleep")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

    private fun save(name: String, text: String) {
        System.getProperty("guard.evidence")?.let { File(it).apply { mkdirs() }.resolve("$name.txt").writeText(text) }
    }
}
