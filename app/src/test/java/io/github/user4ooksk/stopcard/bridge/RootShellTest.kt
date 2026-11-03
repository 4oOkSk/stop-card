package io.github.user4ooksk.stopcard.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Executes the production shell command; only Android's ownership change is stubbed. */
class RootShellTest {
    @get:Rule val folder = TemporaryFolder()
    private val root = RootShell()
    private lateinit var bin: File
    private lateinit var config: File
    private lateinit var flock: String

    @Before
    fun prepare() {
        assumeTrue(System.getProperty("os.name").contains("Linux"))
        flock = ProcessBuilder("sh", "-c", "command -v flock").start()
            .inputStream.bufferedReader().use { it.readText().trim() }
        assumeTrue(flock.startsWith("/") && File(flock).canExecute())
        bin = folder.newFolder("bin")
        File(bin, "chown").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        // Reproduce a shell/tool boundary that drops FD 9 before invoking flock.
        File(bin, "flock").apply {
            writeText("#!/bin/sh\nexec 9>&-\nexec '$flock' \"\$@\"\n")
            setExecutable(true)
        }
        config = File(folder.root, "control with 'quote/config.json")
    }

    @Test
    fun `initial configuration works when flock cannot inherit descriptor nine`() {
        config.parentFile.mkdirs()
        val old = shell("exec 9> '${File(folder.root, "old.lock").path}'; flock -n 9")
        old.outputStream.close()
        val failure = finish(old)
        assertTrue(failure.first != 0)
        assertTrue(failure.second.contains("Bad file descriptor", ignoreCase = true))

        val text = "{\"configGeneration\":1,\"note\":\"配置\"}\n"
        assertEquals(0, write(text, null).first)
        assertEquals(text, config.readText())
        assertTrue(config.parentFile.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `stale expected content is rejected and an up to date retry succeeds`() {
        val original = "{\"configGeneration\":1}"
        val next = "{\"configGeneration\":2}"
        assertEquals(0, write(original, null).first)
        assertEquals(74, write(next, null).first)
        assertEquals(74, write(next, "stale").first)
        assertEquals(original, config.readText())
        assertEquals(0, write(next, original).first)
        assertEquals(next, config.readText())
    }

    @Test
    fun `an occupied lock prevents writes and is released with its process`() {
        val original = "original"
        assertEquals(0, write(original, null).first)
        val holder = ProcessBuilder(flock, "-n", File(config.parentFile, "config.lock").path,
            "sh", "-c", "printf 'locked\\n'; cat >/dev/null").start()
        try {
            assertEquals("locked", holder.inputStream.bufferedReader().readLine())
            assertTrue(write("next", original).first != 0)
            assertEquals(original, config.readText())
        } finally {
            holder.outputStream.close()
            if (!holder.waitFor(5, TimeUnit.SECONDS)) holder.destroyForcibly()
        }
        assertEquals(0, write("next", original).first)
        assertEquals("next", config.readText())
    }

    @Test
    fun `two writers with the same expected content cannot both replace it`() {
        assertEquals(0, write("initial", null).first)
        val first = startWrite("first", "initial")
        val second = startWrite("second", "initial")
        val results = listOf(finish(first).first, finish(second).first)
        assertEquals(1, results.count { it == 0 })
        assertTrue(config.readText() in setOf("first", "second"))
        assertFalse(config.parentFile.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    private fun write(text: String, expected: String?) = finish(startWrite(text, expected))

    private fun startWrite(text: String, expected: String?): Process {
        val digest = expected?.let { MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) } } ?: "absent"
        return shell(root.atomicWriteCommand(config.path, digest)).also { process ->
            process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun shell(command: String): Process = ProcessBuilder("sh", "-c", command)
        .redirectErrorStream(true).apply { environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}" }.start()

    private fun finish(process: Process): Pair<Int, String> {
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Shell command did not finish")
        }
        return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
    }
}
