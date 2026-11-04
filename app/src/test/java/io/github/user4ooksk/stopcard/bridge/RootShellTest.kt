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
        // Match Toybox's FD-only interface and reproduce loss of higher inherited FDs.
        File(bin, "flock").apply {
            writeText("""
                |#!/bin/sh
                |if test "${'$'}#" != 2 || test "${'$'}1" != -n; then
                |    echo "flock: Unknown option 'c'" >&2
                |    exit 1
                |fi
                |case "${'$'}2" in
                |    ''|*[!0-9]*) echo 'flock: expected fd' >&2; exit 1 ;;
                |esac
                |exec 9>&-
                |exec '$flock' "${'$'}@"
            """.trimMargin() + "\n")
            setExecutable(true)
        }
        config = File(folder.root, "control with 'quote/config.json")
    }

    @Test
    fun `initial configuration uses fd only and works when descriptor nine is lost`() {
        config.parentFile.mkdirs()
        val old = shell("exec 9> '${File(folder.root, "old.lock").path}'; flock -n 9")
        old.outputStream.close()
        val failure = finish(old)
        assertTrue(failure.first != 0)
        assertTrue(failure.second.contains("Bad file descriptor", ignoreCase = true))

        val unsupported = shell("flock -n '${File(folder.root, "old.lock").path}' sh -c 'echo wrong'")
        unsupported.outputStream.close()
        val pathFailure = finish(unsupported)
        assertEquals(1, pathFailure.first)
        assertTrue(pathFailure.second.contains("Unknown option 'c'"))
        assertFalse(pathFailure.second.contains("wrong"))

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

    @Test
    fun `writer keeps the inherited stdin lock until the replacement finishes`() {
        assertEquals(0, write("initial", null).first)
        val entered = File(folder.root, "entered")
        val release = File(folder.root, "release")
        val move = ProcessBuilder("sh", "-c", "command -v mv").start()
            .inputStream.bufferedReader().use { it.readText().trim() }
        File(bin, "mv").apply {
            writeText("""
                |#!/bin/sh
                |touch '${entered.path}'
                |while ! test -e '${release.path}'; do sleep 0.05; done
                |exec '$move' "${'$'}@"
            """.trimMargin() + "\n")
            setExecutable(true)
        }
        val first = startWrite("first", "initial")
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!entered.exists() && first.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("The first writer must reach rename", entered.exists())
            assertEquals(75, write("second", "initial").first)
            assertEquals("initial", config.readText())
        } finally {
            release.createNewFile()
            if (!first.waitFor(5, TimeUnit.SECONDS)) first.destroyForcibly()
        }
        assertEquals(0, first.exitValue())
        assertEquals("first", config.readText())
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
