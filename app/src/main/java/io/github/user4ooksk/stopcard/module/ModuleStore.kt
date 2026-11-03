package io.github.user4ooksk.stopcard.module

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Files are private to root/system. Control reads run on the worker; hooks never access storage. */
class ModuleStore(private val fault: (String) -> Unit) {
    private val sequence = AtomicLong(System.currentTimeMillis())
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { action -> Thread(action, "stop-card-storage").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private var lastPrune = 0L

    fun control(path: String, maxBytes: Int = 256 * 1024): String? {
        val file = File(path)
        if (!file.exists()) return null
        validateParent(file.parentFile ?: error("No parent"))
        val stat = Os.lstat(path)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == 0 && stat.st_gid == 1000 && stat.st_mode and 23 == 0) { "Unsafe control file metadata" }
        require(stat.st_size in 1..maxBytes.toLong()) { "Control file size" }
        return file.inputStream().use { input ->
            val bytes = input.readBytesLimited(maxBytes)
            bytes.toString(Charsets.UTF_8)
        }
    }

    fun paused(): Boolean = File(ModuleProtocol.PAUSE).exists() || File(ModuleProtocol.EMERGENCY_DISABLED).exists()

    fun commands(): List<Pair<File, String>> {
        val dir = File(ModuleProtocol.COMMAND_DIR)
        if (!dir.exists()) return emptyList()
        validateParent(dir)
        val result = ArrayList<Pair<File, String>>()
        // DirectoryStream avoids allocating/sorting a hostile or stale directory in system_server.
        java.nio.file.Files.newDirectoryStream(dir.toPath(), "*.json").use { stream ->
            val iterator = stream.iterator()
            var count = 0
            while (iterator.hasNext() && count++ < 16) {
                val file = iterator.next().toFile()
                val text = runCatching { control(file.path, 64 * 1024) }.getOrNull()
                if (text == null) file.delete() else result += file to text
            }
        }
        return result
    }

    fun state(json: String) = submit { atomic(File(ModuleProtocol.STATE), json) }
    fun ready(json: String) = submit { atomic(File("${ModuleProtocol.DIR}/ready.json"), json) }

    fun event(pkg: String, reason: String, detail: String = "") {
        val id = sequence.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
        val event = ModuleProtocol.Event(id, System.currentTimeMillis(), pkg, reason, detail.take(512))
        submit {
            val file = File(ModuleProtocol.EVENTS)
            file.parentFile?.mkdirs()
            file.appendText(event.toJson().toString() + "\n")
            Os.chmod(file.path, 384) // 0600; the controller reads through root.
            val now = System.currentTimeMillis()
            if (file.length() > 512 * 1024 || now - lastPrune > 60_000) {
                lastPrune = now
                val cutoff = now - TimeUnit.DAYS.toMillis(7)
                val retained = ArrayDeque<String>()
                file.useLines { lines -> lines.forEach { line ->
                    val time = runCatching { org.json.JSONObject(line).getLong("time") }.getOrDefault(0)
                    if (time >= cutoff) {
                        retained.addLast(line)
                        if (retained.size > 800) retained.removeFirst()
                    }
                } }
                atomic(file, retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))
            }
        }
    }

    fun clearEvents(done: (Boolean) -> Unit) = submit(rejected = { done(false) }) {
        try { atomic(File(ModuleProtocol.EVENTS), ""); done(true) }
        catch (e: Exception) { done(false); throw e }
    }

    private fun validateParent(dir: File) {
        val stat = Os.lstat(dir.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == 0 && stat.st_gid == 1000 && stat.st_mode and 7 == 0) { "Unsafe control directory" }
        if (dir.path != ModuleProtocol.DIR) validateParent(File(ModuleProtocol.DIR))
    }

    private fun atomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".module.tmp")
        tmp.outputStream().use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        Os.chmod(tmp.path, 384)
        Os.rename(tmp.path, file.path)
    }

    private fun submit(rejected: () -> Unit = {}, action: () -> Unit) {
        try {
            io.execute { try { action() } catch (e: Exception) { fault("storage:${e.javaClass.simpleName}") } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            fault("storage:queue_full")
            rejected()
        }
    }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= max) { "Control file exceeds limit" }
            output.write(buffer, 0, count)
        }
    }
}
