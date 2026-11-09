package io.github.user4ooksk.stopcard.policy

/** Installation-bound, bounded diagnostics. Decisions still come from the current permission. */
class WakeRelations(private val capacity: Int = 128) {
    data class Key(val source: AppKey, val target: AppKey, val kind: EntryKind, val component: String?)
    data class View(val key: Key, val attempts: Long, val denied: Long, val lastAt: Long)
    private val entries = LinkedHashMap<Key, View>()

    @Synchronized fun record(source: AppKey, target: AppKey, call: CallTarget, denied: Boolean, now: Long): View {
        val key = Key(source, target, call.kind, call.component?.take(256))
        val old = entries.remove(key)
        val value = View(key, (old?.attempts ?: 0) + 1, (old?.denied ?: 0) + if (denied) 1 else 0, now)
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return value
    }

    @Synchronized fun retain(apps: Map<String, AppKey>) {
        entries.keys.removeAll { apps[it.source.packageName] != it.source || apps[it.target.packageName] != it.target }
    }

    @Synchronized fun views(): List<View> = entries.values.toList().asReversed()
}
