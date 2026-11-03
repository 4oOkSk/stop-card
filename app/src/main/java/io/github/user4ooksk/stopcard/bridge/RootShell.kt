package io.github.user4ooksk.stopcard.bridge

import io.github.user4ooksk.stopcard.module.ModuleProtocol
import java.util.concurrent.TimeUnit

/**
 * Minimal root shell. It is used only to write the module configuration, read its state/log and
 * install a recovery helper. It is not in the per-event enforcement path.
 *
 * KernelSU's `su` gives the app a root shell. Commands are short and bounded; no user input is
 * interpolated except package names, which are validated by the caller.
 */
class RootShell {

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile
    private var checked = false

    @Volatile
    private var granted = false

    fun available(force: Boolean = false): Boolean {
        if (!checked || force) check(force)
        return granted
    }

    fun check(force: Boolean = false): Boolean {
        if (checked && !force) return granted
        synchronized(this) {
            if (checked && !force) return granted
            granted = runCatching {
                val result = exec("id")
                result.ok && result.output.contains("uid=0")
            }.getOrDefault(false)
            checked = true
            return granted
        }
    }

    fun exec(command: String, stdin: String? = null, timeoutMs: Long = 8_000): Result {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, e.message ?: e.javaClass.simpleName)
        }
        return try {
            val writeFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
            val writer = Thread {
                try {
                    if (stdin != null) {
                        process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
                    } else {
                        process.outputStream.close()
                    }
                } catch (e: Exception) {
                    writeFailure.set(e)
                }
            }.apply {
                isDaemon = true
                start()
            }
            val output = StringBuffer()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.length + count > MAX_OUTPUT_CHARS) break
                            output.append(buffer, 0, count)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            writer.join(500)
            reader.join(500)
            val complete = finished && !writer.isAlive && !reader.isAlive && writeFailure.get() == null
            Result(if (complete) process.exitValue() else -1, output.toString())
        } catch (e: Exception) {
            runCatching { process.destroyForcibly() }
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    fun read(path: String): String? {
        val result = exec("cat ${quote(path)}")
        return if (result.ok) result.output else null
    }

    fun readTail(path: String, bytes: Int = 128 * 1024): String? {
        val result = exec("tail -c ${bytes.coerceIn(1_024, 512 * 1_024)} ${quote(path)}")
        return if (result.ok) result.output else null
    }

    fun write(path: String, content: String): Boolean {
        val dir = path.substringBeforeLast('/')
        val tmp = "$path.${System.nanoTime()}.tmp"
        val script = """
            set -eu
            sc_dir=${quote(dir)}
            sc_tmp=${quote(tmp)}
            trap 'rm -f "${'$'}sc_tmp"' EXIT
            mkdir -p "${'$'}sc_dir"
            chown 0:1000 "${'$'}sc_dir"
            chmod 0770 "${'$'}sc_dir"
            cat > "${'$'}sc_tmp"
            chown 0:1000 "${'$'}sc_tmp"
            chmod 0640 "${'$'}sc_tmp"
            restorecon "${'$'}sc_dir" "${'$'}sc_tmp" 2>/dev/null || true
            mv -f "${'$'}sc_tmp" ${quote(path)}
        """.trimIndent()
        return exec(script, content).ok
    }

    fun remove(path: String): Boolean = exec("rm -f ${quote(path)}").ok

    fun exists(path: String): Boolean = exec("test -e ${quote(path)} && echo 1").output.trim() == "1"

    fun pause(): Boolean = write(ModuleProtocol.PAUSE, "paused ${System.currentTimeMillis()}\n")

    fun resume(): Boolean = remove(ModuleProtocol.PAUSE)

    fun diagnostics(): String = exec(
        """
            echo "uid=$(id -u)"
            echo "sdk=$(getprop ro.build.version.sdk)"
            echo "release=$(getprop ro.build.version.release)"
            ls -ld ${quote(ModuleProtocol.DIR)} 2>&1
            ls -l ${quote(ModuleProtocol.DIR)} 2>&1
            test -e ${quote(ModuleProtocol.PAUSE)} && echo "paused=yes" || echo "paused=no"
        """.trimIndent(),
    ).output

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val MAX_OUTPUT_CHARS = 256 * 1024
    }
}
