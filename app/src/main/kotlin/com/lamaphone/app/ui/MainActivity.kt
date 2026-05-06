package com.lamaphone.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lamaphone.app.EngineState
import com.lamaphone.app.ui.chat.ChatHistoryBus
import com.lamaphone.app.ui.chat.ChatScreen
import com.lamaphone.app.ui.server.ServerScreen
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import com.lamaphone.app.ui.theme.RetroCliColors
import com.lamaphone.app.ui.theme.TerminalBackground

private sealed class Screen(val route: String, val label: String) {
    object Chat   : Screen("chat",   "CHAT")
    object Server : Screen("server", "SERVER")
}

private val bottomNavItems = listOf(Screen.Chat, Screen.Server)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LamaPhoneTheme {
                LamaPhoneApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LamaPhoneApp() {
    val navController   = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val modelPath by EngineState.modelPath.collectAsState()
    val isLoaded  by EngineState.isLoaded.collectAsState()
    val isLoadingModel by EngineState.isLoadingModel.collectAsState()
    val isChatSelected = currentDestination?.hierarchy
        ?.any { it.route == Screen.Chat.route } == true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = RetroCliColors.Void,
        topBar   = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text  = "> LAMAPHONE",
                            style = MaterialTheme.typography.titleMedium,
                            color = RetroCliColors.Cyan
                        )
                        if (isLoadingModel) {
                            Text(
                                text = "MODEL: LOADING",
                                style = MaterialTheme.typography.labelSmall,
                                color = RetroCliColors.Warning
                            )
                        } else if (isLoaded && modelPath != null) {
                            Text(
                                text  = "MODEL: ${modelPath!!.substringAfterLast('/')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = RetroCliColors.Magenta
                            )
                        } else {
                            Text(
                                text = "MODEL: NOT_LOADED",
                                style = MaterialTheme.typography.labelSmall,
                                color = RetroCliColors.Muted
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = RetroCliColors.Void,
                    titleContentColor = RetroCliColors.Cyan
                ),
                actions = {
                    if (isChatSelected) {
                        IconButton(onClick = { ChatHistoryBus.requestOpen() }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open chat history",
                                tint = RetroCliColors.Cyan
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = RetroCliColors.Void,
                border = BorderStroke(1.dp, RetroCliColors.Cyan.copy(alpha = 0.36f))
            ) {
                NavigationBar(containerColor = RetroCliColors.Void) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == screen.route } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick  = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon  = {
                                when (screen) {
                                    Screen.Chat   -> Icon(Icons.Filled.Chat, contentDescription = "Chat")
                                    Screen.Server -> Icon(Icons.Filled.Dns,  contentDescription = "Server")
                                }
                            },
                            label = { Text(if (selected) "[${screen.label}]" else screen.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = RetroCliColors.Void,
                                selectedTextColor = RetroCliColors.Cyan,
                                indicatorColor = RetroCliColors.Cyan,
                                unselectedIconColor = RetroCliColors.Muted,
                                unselectedTextColor = RetroCliColors.Muted
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        TerminalBackground(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            NavHost(
                navController    = navController,
                startDestination = Screen.Chat.route
            ) {
                composable(Screen.Chat.route)   { ChatScreen() }
                composable(Screen.Server.route) { ServerScreen() }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LamaPhoneAppPreview() {
    LamaPhoneTheme(darkTheme = true) {
        LamaPhoneApp()
    }
}
