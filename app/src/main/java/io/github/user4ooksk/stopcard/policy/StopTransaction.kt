package io.github.user4ooksk.stopcard.policy

/** Coordinate native force-stop and its package-state side effect, without taking Android locks. */
object StopTransaction {
    fun run(valid: () -> Boolean, wasStopped: Boolean, stop: () -> Unit, restore: () -> Unit) {
        check(valid()) { "Stop decision changed before native entry" }
        var completed = false
        try {
            stop()
            completed = true
        } finally {
            // An interrupted native operation may already have set the package stopped bit.
            // Never undo a stopped state that existed before our operation.
            if (!wasStopped && (!completed || !valid())) restore()
        }
    }
}
