// app/src/main/java/com/upuaut/xposedsearch/MainActivity.kt
package com.upuaut.xposedsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import com.upuaut.xposedsearch.ui.HomeScreen
import com.upuaut.xposedsearch.ui.MainScreen
import com.upuaut.xposedsearch.ui.HotSitesScreen
import com.upuaut.xposedsearch.ui.DarkWordScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MiuixTheme {
                MainApp()
            }
        }
    }
}

sealed class Screen {
    data object Home : Screen()
    data object SearchEngines : Screen()
    data object HotSites : Screen()
    data object DarkWord : Screen()
}

@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = currentScreen != Screen.Home) {
        currentScreen = Screen.Home
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState == Screen.Home) {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300)) togetherWith
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300))
            } else {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300)) togetherWith
                        slideOutHorizontally(
                            targetOffsetX = { -it / 3 },
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300))
            }
        },
        label = "navigation"
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(
                onNavigateToEngines = { currentScreen = Screen.SearchEngines },
                onNavigateToHotSites = { currentScreen = Screen.HotSites },
                onNavigateToDarkWord = { currentScreen = Screen.DarkWord }
            )
            Screen.SearchEngines -> MainScreen(
                onBack = { currentScreen = Screen.Home }
            )
            Screen.HotSites -> HotSitesScreen(
                onBack = { currentScreen = Screen.Home }
            )
            Screen.DarkWord -> DarkWordScreen(
                onBack = { currentScreen = Screen.Home }
            )
        }
    }
}