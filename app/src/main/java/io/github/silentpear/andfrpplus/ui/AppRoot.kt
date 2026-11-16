package io.github.silentpear.andfrpplus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

sealed interface Screen {
    data object Home : Screen
    data object Transfers : Screen
    data object Sync : Screen
    data object Settings : Screen
    data object History : Screen
    data class Browser(val connectionId: String, val path: String) : Screen
    data class EditConnection(val connectionId: String?) : Screen
    data class Editor(val connectionId: String, val path: String) : Screen
}

@Composable
fun AppRoot(
    viewModel: MainViewModel,
    incomingUri: String?
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val backStack = remember { mutableListOf<Screen>() }
    var handledUri by remember { mutableStateOf<String?>(incomingUri) }

    fun navigate(target: Screen) {
        backStack.add(screen)
        screen = target
    }

    fun goBack() {
        backStack.removeLastOrNull()?.let { screen = it }
    }

    val locked by viewModel.locked.collectAsStateWithLifecycle()

    val pendingFiles by viewModel.pendingFiles.collectAsStateWithLifecycle()

    LaunchedEffect(incomingUri) {
        if (incomingUri != null && incomingUri != handledUri) {
            handledUri = incomingUri
            viewModel.onUriReceived(incomingUri)
            if (viewModel.clipboardImport.value != null) {
                navigate(Screen.EditConnection(null))
            }
        }
    }

    if (locked) {
        LockScreen(onUnlock = { viewModel.unlock() })
        return
    }

    val showBottomBar = screen is Screen.Home || screen is Screen.Transfers ||
        screen is Screen.Sync || screen is Screen.Settings

    BackHandler(enabled = backStack.isNotEmpty()) { goBack() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                    val items = listOf(
                        Triple(Screen.Home, "连接", Icons.Filled.Cloud),
                        Triple(Screen.Transfers, "传输", Icons.Filled.SwapVert),
                        Triple(Screen.Sync, "同步", Icons.Filled.Sync),
                        Triple(Screen.Settings, "设置", Icons.Filled.Settings)
                    )
                    items.forEach { (target, label, icon) ->
                        NavigationBarItem(
                            selected = screen == target,
                            onClick = {
                                backStack.clear()
                                screen = target
                            },
                            icon = { Icon(icon, label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "screen"
        ) { current ->
            when (current) {
                Screen.Home -> HomeScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                    onOpen = { conn -> navigate(Screen.Browser(conn.id, conn.initialPath.ifBlank { "/" })) },
                    onEdit = { connId -> navigate(Screen.EditConnection(connId)) },
                    onHistory = { navigate(Screen.History) }
                )
                Screen.Transfers -> TransfersScreen(viewModel, Modifier.padding(padding))
                Screen.Sync -> SyncScreen(viewModel, Modifier.padding(padding))
                Screen.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                    onHistory = { navigate(Screen.History) }
                )
                Screen.History -> HistoryScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                    onBack = { goBack() }
                )
                is Screen.Browser -> BrowserScreen(
                    viewModel = viewModel,
                    connectionId = current.connectionId,
                    initialPath = current.path,
                    incomingFiles = pendingFiles,
                    onConsumeIncoming = { viewModel.consumeFiles() },
                    onBack = { goBack() },
                    onEditFile = { path -> navigate(Screen.Editor(current.connectionId, path)) }
                )
                is Screen.EditConnection -> ConnectionEditScreen(
                    viewModel = viewModel,
                    connectionId = current.connectionId,
                    onBack = { goBack() }
                )
                is Screen.Editor -> EditorScreen(
                    viewModel = viewModel,
                    connectionId = current.connectionId,
                    path = current.path,
                    onBack = { goBack() }
                )
            }
        }
    }
}
