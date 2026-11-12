package io.github.user4ooksk.stopcard.module

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** Files are private to root/system. Control reads run on the worker; hooks never access storage. */
class ModuleStore(private val fault: (String) -> Unit) {
    private val sequence = AtomicLong(System.currentTimeMillis())
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { action -> Thread(action, "stop-card-storage").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private var lastPrune = -60_000L
    private val latest = ConcurrentHashMap<String, String>()
    private val scheduled = ConcurrentHashMap.newKeySet<String>()

    fun control(path: String, maxBytes: Int = 256 * 1024): String? {
        val file = File(path)
        if (!file.exists()) return null
        validateParent(file.parentFile ?: error("No parent"))
        val stat = Os.lstat(path)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == 0 && stat.st_gid == 1000 && stat.st_mode and 23 == 0) { "Unsafe control file metadata" }
        require(stat.st_size in 1..maxBytes.toLong()) { "Control file size" }
        // Root writes complete documents by atomic rename; the checked file is immutable.
        return file.readText()
    }

    fun paused(): Boolean = File(ModuleProtocol.PAUSE).exists() || File(ModuleProtocol.EMERGENCY_DISABLED).exists()

    fun emergencyReason(): String? {
        val file = File(ModuleProtocol.EMERGENCY_DISABLED)
        if (!file.exists()) return null
        return runCatching { control(file.path, 1024)?.lineSequence()?.firstOrNull()?.trim()?.take(64) }
            .getOrNull().orEmpty().ifBlank { "locked" }
    }

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

    fun state(json: String) = replace(ModuleProtocol.STATE, json)
    fun ready(json: String) = replace("${ModuleProtocol.DIR}/ready.json", json)

    private fun replace(path: String, text: String) {
        latest[path] = text
        if (!scheduled.add(path)) return
        submit(rejected = { scheduled.remove(path) }) {
            // Heartbeat/status files are scoped to the current bootId; atomic rename suffices without fsync.
            try { latest.remove(path)?.let { atomic(File(path), it, sync = false) } }
            finally {
                scheduled.remove(path)
                latest[path]?.let { replace(path, it) }
            }
        }
    }

    fun maintenance(elapsed: Long) {
        if (elapsed - lastPrune < 60_000) return
        lastPrune = elapsed
        submit {
            cleanTempFiles(File(ModuleProtocol.DIR))
            cleanTempFiles(File(ModuleProtocol.COMMAND_DIR))
            prune(File(ModuleProtocol.EVENTS))
        }
    }

    fun event(pkg: String, reason: String, detail: String = "") {
        val id = sequence.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
        val event = ModuleProtocol.Event(id, System.currentTimeMillis(), pkg, reason, detail.take(512))
        submit {
            val file = File(ModuleProtocol.EVENTS)
            file.parentFile?.mkdirs()
            file.appendText(event.toJson().toString() + "\n")
            Os.chmod(file.path, 384) // 0600; the controller reads through root.
            if (file.length() > 512 * 1024) prune(file)
        }
    }

    private fun prune(file: File) {
        if (!file.exists()) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val retained = ArrayDeque<String>()
        var bytes = 0
        file.useLines { lines -> lines.forEach { line ->
            val time = runCatching { org.json.JSONObject(line).getLong("time") }.getOrDefault(0)
            if (time >= cutoff) {
                retained.addLast(line)
                bytes += line.toByteArray(Charsets.UTF_8).size + 1
                while (retained.size > 800 || bytes > 512 * 1024) {
                    bytes -= retained.removeFirst().toByteArray(Charsets.UTF_8).size + 1
                }
            }
        } }
        atomic(file, retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))
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

    private fun atomic(file: File, text: String, sync: Boolean = true) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
            runCatching { Os.chmod(parent.path, 448) }
        }
        // Single-worker storage executor owns one deterministic staging file per target.
        val tmp = File(parent, ".${file.name}.tmp")
        tmp.delete()
        val descriptor = Os.open(tmp.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        try {
            java.io.FileOutputStream(descriptor).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                if (sync) output.fd.sync()
            }
            Os.rename(tmp.path, file.path)
        } finally {
            // Android streams do not own a descriptor supplied by the caller.
            try { Os.close(descriptor) } finally { tmp.delete() }
        }
    }

    private fun submit(rejected: () -> Unit = {}, action: () -> Unit) {
        try {
            io.execute { guardModule({ fault("storage:${it.javaClass.simpleName}") }, action) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            fault("storage:queue_full")
            rejected()
        }
    }

    companion object {
        internal fun cleanTempFiles(dir: File, cutoffMs: Long = System.currentTimeMillis() - 60_000) {
            if (!dir.isDirectory) return
            java.nio.file.Files.newDirectoryStream(dir.toPath(), "*.tmp").use { stream ->
                var scanned = 0
                for (entry in stream) {
                    if (++scanned > 1_024) break
                    runCatching {
                        val attrs = java.nio.file.Files.readAttributes(
                            entry, java.nio.file.attribute.BasicFileAttributes::class.java,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        )
                        if (attrs.isRegularFile && attrs.lastModifiedTime().toMillis() < cutoffMs) {
                            java.nio.file.Files.deleteIfExists(entry)
                        }
                    }
                }
            }
        }
    }

}
