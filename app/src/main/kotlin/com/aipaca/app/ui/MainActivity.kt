package com.aipaca.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aipaca.app.ui.chat.ChatScreen
import com.aipaca.app.ui.components.AlpacaBottomNav
import com.aipaca.app.ui.components.AlpacaTab
import com.aipaca.app.ui.models.ModelScreen
import com.aipaca.app.ui.server.ServerScreen
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIpacaTheme {
                AIpacaApp()
            }
        }
    }
}

@Composable
private fun AIpacaApp() {
    val navController        = rememberNavController()
    val navBackStackEntry    by navController.currentBackStackEntryAsState()
    val currentRoute         = navBackStackEntry?.destination?.route
    val selectedTab          = AlpacaTab.fromRoute(currentRoute)

    val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        modifier       = Modifier
            .fillMaxSize()
            .imePadding()
            .background(AlpacaColors.Surface.Canvas),
        containerColor = AlpacaColors.Surface.Canvas,
        bottomBar      = {
            if (!isKeyboardOpen) {
                AlpacaBottomNav(
                    selected = selectedTab,
                    onSelect = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = AlpacaTab.Chat.route,
            modifier         = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(AlpacaColors.Surface.Canvas)
        ) {
            composable(AlpacaTab.Chat.route)   { ChatScreen() }
            composable(AlpacaTab.Models.route) { ModelScreen() }
            composable(AlpacaTab.Server.route) { ServerScreen() }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AIpacaAppPreview() {
    AIpacaTheme {
        AIpacaApp()
    }
}
