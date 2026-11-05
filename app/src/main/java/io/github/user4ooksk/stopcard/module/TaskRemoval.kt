package io.github.user4ooksk.stopcard.module

/** Native task-removal reasons, after Android's permission check. Not Intent or app input. */
internal fun explicitTaskRemoval(removeFromRecents: Boolean, reason: String): Boolean {
    if (!removeFromRecents) return false
    if (reason in setOf("remove-task", "removeTask", "remove-all-visible-recent-tasks")) return true
    // Some framework builds annotate the public removeTask route with its original caller PID.
    val caller = reason.removePrefix("remove-by-pid#")
    return caller != reason && caller.isNotEmpty() && caller.all { it in '0'..'9' }
}
