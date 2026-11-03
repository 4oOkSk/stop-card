package io.github.user4ooksk.stopcard.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.user4ooksk.stopcard.graph
import io.github.user4ooksk.stopcard.ui.onboarding.OnboardingScreen
import io.github.user4ooksk.stopcard.ui.theme.StopTheme
import io.github.user4ooksk.stopcard.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels { viewModelFactory { initializer { MainViewModel(graph) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { main.settings.value == null }
        enableEdgeToEdge()

        setContent {
            val settings by main.settings.collectAsStateWithLifecycle()
            val current = settings ?: return@setContent
            val dark = isDarkTheme(current.themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            StopTheme(darkTheme = dark, dynamicColor = current.dynamicColor) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    if (current.onboarded) {
                        MainScreen(main = main, darkTheme = dark)
                    } else {
                        OnboardingScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        main.refresh()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
