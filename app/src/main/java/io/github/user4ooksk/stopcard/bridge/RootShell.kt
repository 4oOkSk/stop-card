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
        require(path != ModuleProtocol.CONFIG) { "Use configuration compare-and-swap" }
        return writeAtomic(path, content).ok
    }

    fun writeConfiguration(content: String, expected: String?): Result {
        val digest = expected?.let { java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) } } ?: "absent"
        return writeAtomic(ModuleProtocol.CONFIG, content, digest)
    }

    private fun writeAtomic(path: String, content: String, expected: String? = null): Result =
        exec(atomicWriteCommand(path, expected), content)

    internal fun atomicWriteCommand(path: String, expected: String? = null): String {
        val dir = path.substringBeforeLast('/')
        val tmp = "$path.${System.nanoTime()}.tmp"
        val compare = if (expected == null) "" else """
            if test -e ${quote(path)}; then
                test ! -L ${quote(path)} || exit 74
                sc_hash=${'$'}(sha256sum ${quote(path)})
                sc_hash=${'$'}{sc_hash%% *}
            else
                sc_hash=absent
            fi
            test "${'$'}sc_hash" = ${quote(expected)} || exit 74
        """.trimIndent()
        val writer = """
            set -eu
            sc_tmp=${quote(tmp)}
            trap 'rm -f "${'$'}sc_tmp"' EXIT
            $compare
            cat > "${'$'}sc_tmp"
            chown 0:1000 "${'$'}sc_tmp"
            chmod 0640 "${'$'}sc_tmp"
            restorecon ${quote(dir)} "${'$'}sc_tmp" 2>/dev/null || true
            mv -f "${'$'}sc_tmp" ${quote(path)}
        """.trimIndent()
        // Let flock open the file instead of relying on shell/tool descriptor inheritance.
        val write = if (expected == null) writer else
            "exec flock -n ${quote("$dir/config.lock")} sh -c ${quote(writer)}"
        return """
            set -eu
            umask 077
            mkdir -p ${quote(dir)}
            chown 0:1000 ${quote(dir)}
            chmod 0770 ${quote(dir)}
            $write
        """.trimIndent()
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
