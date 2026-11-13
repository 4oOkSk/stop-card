package io.github.user4ooksk.stopcard.module

import android.os.FileObserver
import java.io.File

/** FileObserver dispatches on a shared system_server thread outside the control Handler. */
internal class ModuleFileObserver(
    path: String,
    mask: Int,
    private val failed: (Throwable) -> Unit,
    private val changed: (Int, String?) -> Unit,
) : FileObserver(File(path), mask) {
    override fun onEvent(event: Int, path: String?) {
        guardModule(failed) { changed(event, path) }
    }
}
