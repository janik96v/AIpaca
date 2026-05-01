package com.lamaphone.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lamaphone.app.EngineState
import com.lamaphone.app.ui.chat.ChatScreen
import com.lamaphone.app.ui.server.ServerScreen
import com.lamaphone.app.ui.theme.LamaPhoneTheme

private sealed class Screen(val route: String, val label: String) {
    object Chat   : Screen("chat",   "Chat")
    object Server : Screen("server", "Server")
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar   = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text  = "LamaPhone 🦙",   // llama emoji
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        if (isLoaded && modelPath != null) {
                            Text(
                                text  = modelPath!!.substringAfterLast('/'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
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
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Chat.route,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Chat.route)   { ChatScreen() }
            composable(Screen.Server.route) { ServerScreen() }
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
