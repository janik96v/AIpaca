package com.aipaca.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aipaca.app.ui.chat.ChatScreen
import com.aipaca.app.ui.chat.ChatViewModel
import com.aipaca.app.ui.components.hairlineStart
import com.aipaca.app.ui.components.world.WorldMotion
import com.aipaca.app.ui.memory.MemoryScreen
import com.aipaca.app.ui.models.ModelScreen
import com.aipaca.app.ui.server.ServerScreen
import com.aipaca.app.ui.shell.HistorySheet
import com.aipaca.app.ui.shell.PositionLadder
import com.aipaca.app.ui.shell.Rail
import com.aipaca.app.ui.shell.Screen
import com.aipaca.app.ui.shell.StatusLine
import com.aipaca.app.ui.shell.rememberModelPresence
import com.aipaca.app.ui.shell.topHaze
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.Ink

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AIpacaTheme {
                AIpacaApp()
            }
        }
    }
}

/**
 * App shell: collapsible left rail, then the content column — status line on
 * top, the current screen below, the position ladder on its right edge and the
 * history sheet over it. The top haze sits behind everything.
 */
@Composable
private fun AIpacaApp() {
    val navController     = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val current           = Screen.fromRoute(navBackStackEntry?.destination?.route)

    // Activity-scoped so the history sheet (shell) and the chat screen share it.
    val chatViewModel: ChatViewModel = viewModel()
    val conversations         by chatViewModel.conversations.collectAsState()
    val currentConversationId by chatViewModel.currentConversationId.collectAsState()

    val presence = rememberModelPresence(chatViewModel)
    // Hoisted: the world keeps its pose while Chat is off screen, so a model
    // loaded from Models unfolds when you come back.
    val worldMotion = remember { WorldMotion() }

    var railHidden  by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }

    fun navigate(screen: Screen) {
        historyOpen = false
        if (screen == current) return
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState    = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Black)
            .topHaze()
    ) {
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !railHidden,
                enter   = expandHorizontally(tween(200)) + fadeIn(tween(200)),
                exit    = shrinkHorizontally(tween(180)) + fadeOut(tween(120))
            ) {
                Rail(
                    current    = current,
                    onSelect   = ::navigate,
                    onCollapse = { railHidden = true },
                    onHistory  = { historyOpen = true },
                    modifier   = Modifier.windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                    )
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .hairlineStart(Ink.Divider)
            ) {
                val contentInsets = if (railHidden) WindowInsets.safeDrawing
                else WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)

                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(contentInsets)
                        .then(if (historyOpen) Modifier.blur(2.dp) else Modifier)
                ) {
                    StatusLine(
                        text          = presence.statusLine,
                        railCollapsed = railHidden,
                        onExpandRail  = { railHidden = false },
                        onHistory     = { historyOpen = true }
                    )
                    NavHost(
                        navController      = navController,
                        startDestination   = Screen.Chat.route,
                        modifier           = Modifier.weight(1f),
                        enterTransition    = { fadeIn(tween(180)) },
                        exitTransition     = { fadeOut(tween(120)) },
                        popEnterTransition = { fadeIn(tween(180)) },
                        popExitTransition  = { fadeOut(tween(120)) }
                    ) {
                        composable(Screen.Chat.route) {
                            ChatScreen(
                                presence      = presence,
                                worldMotion   = worldMotion,
                                onOpenModels  = { navigate(Screen.Models) },
                                chatViewModel = chatViewModel
                            )
                        }
                        composable(Screen.Memory.route) { MemoryScreen() }
                        composable(Screen.Models.route) { ModelScreen() }
                        composable(Screen.Server.route) { ServerScreen() }
                    }
                }

                PositionLadder(
                    current  = current,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                )

                HistorySheet(
                    open                  = historyOpen,
                    conversations         = conversations,
                    currentConversationId = currentConversationId,
                    onDismiss             = { historyOpen = false },
                    onNewChat             = {
                        chatViewModel.clearChat()
                        navigate(Screen.Chat)
                    },
                    onSelect              = { id ->
                        chatViewModel.selectConversation(id)
                        navigate(Screen.Chat)
                    },
                    onDelete              = { id -> chatViewModel.deleteConversation(id) }
                )
            }
        }
    }
}
