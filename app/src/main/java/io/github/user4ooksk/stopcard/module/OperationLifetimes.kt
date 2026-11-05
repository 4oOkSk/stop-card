package io.github.user4ooksk.stopcard.module

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Native objects own lifetimes. A missed end callback cannot retain an operation forever. */
internal class OperationLifetimes {
    enum class Kind { ACTIVITY, SERVICE, BINDING, PROVIDER, BROADCAST }
    private class Lifetime(record: Any, val kind: Kind, val receiver: Int) {
        val record = WeakReference(record)
    }
    private val records = ConcurrentHashMap<String, Lifetime>()

    fun started(token: String, record: Any, kind: Kind, receiver: Int = -1) {
        records[token] = Lifetime(record, kind, receiver)
    }

    fun ended(token: String) { records.remove(token) }

    fun prune(tracked: Set<String>) {
        for ((token, lifetime) in records) {
            if (token !in tracked && lifetime.record.get() == null) records.remove(token, lifetime)
        }
    }

    /** The caller holds Android's corresponding lock, never a policy or registry lock. */
    fun review(kind: Kind, tracked: Set<String>, alive: (Any, Int) -> Boolean, finished: (String) -> Unit) {
        for (token in tracked) {
            val lifetime = records[token] ?: continue
            if (lifetime.kind != kind) continue
            val record = lifetime.record.get()
            if ((record == null || !alive(record, lifetime.receiver)) && records.remove(token, lifetime)) {
                finished(token)
            }
        }
    }
}
