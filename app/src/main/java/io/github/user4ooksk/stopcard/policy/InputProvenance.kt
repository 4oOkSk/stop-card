package io.github.user4ooksk.stopcard.policy

/** Recent system-dispatched input authorizes one destination, never every call by a visible UID. */
class InputProvenance {
    private data class Input(val pkg: String, val activity: String, val at: Long, var target: String? = null)
    private val inputs = LinkedHashMap<Int, Input>()

    @Synchronized fun record(uid: Int, pkg: String, activity: String, at: Long) {
        inputs.entries.removeAll { at - it.value.at !in 0..MAX_AGE_MS }
        if (inputs.size >= 32) inputs.remove(inputs.keys.first())
        inputs[uid] = Input(pkg, activity, at)
    }

    @Synchronized fun claim(uid: Int, pkg: String, activity: String?, target: String, now: Long): Boolean {
        val input = inputs[uid] ?: return false
        if (now - input.at !in 0..MAX_AGE_MS || input.pkg != pkg ||
            (activity != null && input.activity != activity) || pkg == target) return false
        if (input.target != null && input.target != target) return false
        input.target = target
        return true
    }

    companion object { const val MAX_AGE_MS = 1_000L }
}
