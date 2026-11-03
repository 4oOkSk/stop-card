# LSPosed loads the entry class by name from assets/xposed_init.
-keep class io.github.user4ooksk.stopcard.module.StopCardModule { *; }
-keep class io.github.user4ooksk.stopcard.module.ModuleRuntime { *; }
-keep class io.github.user4ooksk.stopcard.module.ModuleProtocol { *; }
-keep class io.github.user4ooksk.stopcard.module.ModuleProtocol$* { *; }
-keep class io.github.user4ooksk.stopcard.policy.** { *; }
-keep class io.github.user4ooksk.stopcard.model.** { *; }

# The JSON protocol is parsed reflectively by org.json only; no reflection on app classes.
-keepattributes *Annotation*
