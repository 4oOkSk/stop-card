package io.github.user4ooksk.stopcard.module

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point. The user must scope this module to "System Framework" (android) only.
 * The agent refuses every other package and every other process.
 */
class StopCardModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        if (lpparam.processName != "system_server" && lpparam.processName != "android") return
        ModuleRuntime.attach(lpparam.classLoader)
    }
}
