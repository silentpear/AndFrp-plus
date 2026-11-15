package io.github.silentpear.andfrpplus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.RemoteEntry
import io.github.silentpear.andfrpplus.protocol.RemoteFileSystem
import io.github.silentpear.andfrpplus.protocol.RemotePaths
import io.github.silentpear.andfrpplus.protocol.permissionString
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: MainViewModel,
    connectionId: String,
    initialPath: String,
    incomingFiles: List<String>,
    onConsumeIncoming: () -> Unit,
    onBack: () -> Unit,
    onEditFile: (String) -> Unit
) {
    val connection = remember(connectionId) { viewModel.connectionStore.byId(connectionId) }
    var path by remember { mutableStateOf(RemotePaths.normalize(initialPath)) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var fs by remember { mutableStateOf<RemoteFileSystem?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var serverInfo by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var propertiesTarget by remember { mutableStateOf<RemoteEntry?>(null) }

    fun reload(target: String) {
        val conn = connection ?: return
        scope.launch {
            loading = true
            error = null
            runCatching {
                val filesystem = viewModel.connect(conn)
                fs = filesystem
                serverInfo = runCatching { filesystem.serverInfo() }.getOrDefault("")
                entries = filesystem.list(target)
            }.onFailure {
                error = it.message ?: "无法读取目录"
            }
            loading = false
        }
    }

    LaunchedEffect(connectionId, path) { reload(path) }

    LaunchedEffect(incomingFiles) {
        if (incomingFiles.isEmpty()) return@LaunchedEffect
        val conn = connection ?: return@LaunchedEffect
        val files = incomingFiles.map { File(android.net.Uri.parse(it).path ?: it) }
        viewModel.queueUploads(conn, files, path)
        snackbar.showSnackbar("已加入上传队列：${files.size} 个文件")
        onConsumeIncoming()
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = path != "/" && selection.isEmpty()) { path = RemotePaths.parent(path) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索当前目录树") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(
                                text = if (selection.isEmpty()) path.substringAfterLast('/').ifBlank { connection?.name ?: "/" }
                                else "已选 ${selection.size} 项",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selection.isEmpty() && serverInfo.isNotBlank()) {
                                Text(
                                    text = serverInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!searchOpen) {
                        IconButton(onClick = {
                            searchOpen = true
                        }) { Icon(Icons.Filled.Search, "搜索") }
                        IconButton(onClick = { reload(path) }) {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, "更多")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("新建文件夹") },
                                    onClick = { menu = false; showNewFolder = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("返回上级") },
                                    onClick = { menu = false; path = RemotePaths.parent(path) }
                                )
                                DropdownMenuItem(
                                    text = { Text("上传到当前目录") },
                                    onClick = { menu = false; snackbar.launchAndShow(scope, "请从传输页选择本地文件") }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { searchOpen = false; query = "" }) {
                            Icon(Icons.Filled.Close, "关闭搜索")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selection.isNotEmpty()) {
                FilledTonalButton(
                    onClick = {
                        val conn = connection ?: return@FilledTonalButton
                        val chosen = entries.filter { it.path in selection }
                        val localDir = File(viewModel.settings.lastLocalDir)
                        viewModel.queueDownloads(conn, chosen, localDir)
                        selection = emptySet()
                        scope.launch { snackbar.showSnackbar("已加入下载队列：${chosen.size} 项") }
                    }
                ) {
                    Icon(Icons.Filled.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("下载")
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("连接失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { reload(path) }) { Text("重试") }
                }
                else -> {
                    val shown = remember(entries, query) {
                        if (query.isBlank()) entries
                        else entries.filter { it.name.contains(query, true) }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (path != "/" && query.isBlank()) {
                            item(key = "__up__") {
                                ListItem(
                                    headlineContent = { Text("..") },
                                    supportingContent = { Text("上级目录") },
                                    leadingContent = { Icon(Icons.Filled.ArrowUpward, null) },
                                    modifier = Modifier.combinedClickable(onClick = { path = RemotePaths.parent(path) })
                                )
                            }
                        }
                        items(shown, key = { it.path }) { entry ->
                            EntryRow(
                                entry = entry,
                                selected = entry.path in selection,
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        selection = if (entry.path in selection) selection - entry.path
                                        else selection + entry.path
                                    } else if (entry.isDirectory) {
                                        path = entry.path
                                    } else {
                                        scope.launch {
                                            snackbar.showSnackbar("长按文件可下载或编辑")
                                        }
                                    }
                                },
                                onLongClick = {
                                    selection = if (entry.path in selection) selection - entry.path
                                    else selection + entry.path
                                }
                            )
                        }
                        if (shown.isEmpty()) {
                            item {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selection.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selection = emptySet() },
            title = { Text("已选 ${selection.size} 项") },
            text = { Text("可以加入下载队列，或清除选择。") },
            confirmButton = {
                TextButton(onClick = {
                    val conn = connection
                    if (conn != null) {
                        val chosen = entries.filter { it.path in selection }
                        viewModel.queueDownloads(conn, chosen, File(viewModel.settings.lastLocalDir))
                        scope.launch { snackbar.showSnackbar("已加入下载队列：${chosen.size} 项") }
                    }
                    selection = emptySet()
                }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { selection = emptySet() }) { Text("取消") }
            }
        )
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = newFolderName.trim()
                    val filesystem = fs
                    if (target.isNotEmpty() && filesystem != null) {
                        scope.launch {
                            runCatching { viewModel.createFolder(filesystem, RemotePaths.join(path, target)) }
                                .onFailure { snackbar.showSnackbar("创建失败：${it.message}") }
                            reload(path)
                        }
                    }
                    showNewFolder = false
                    newFolderName = ""
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("新名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val filesystem = fs
                    if (filesystem != null) {
                        scope.launch {
                            runCatching {
                                viewModel.rename(
                                    filesystem,
                                    target.path,
                                    RemotePaths.join(RemotePaths.parent(target.path), renameValue.trim())
                                )
                            }.onFailure { snackbar.showSnackbar("重命名失败：${it.message}") }
                            reload(path)
                        }
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    propertiesTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { propertiesTarget = null },
            title = { Text(target.name) },
            text = {
                Column {
                    PropertyRow("路径", target.path)
                    PropertyRow("类型", if (target.isDirectory) "目录" else "文件")
                    if (!target.isDirectory) PropertyRow("大小", RemoteEntry.formatBytes(target.size))
                    PropertyRow("权限", permissionString(target.permissions).ifBlank { "未知" })
                    PropertyRow("所有者", target.owner ?: "未知")
                    if (target.modifiedAt > 0) {
                        PropertyRow(
                            "修改时间",
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(target.modifiedAt))
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!target.isDirectory) onEditFile(target.path)
                    propertiesTarget = null
                }) {
                    Text(if (target.isDirectory) "关闭" else "编辑文本")
                }
            }
        )
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EntryRow(
    entry: RemoteEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val parts = mutableListOf<String>()
            if (!entry.isDirectory) parts += RemoteEntry.formatBytes(entry.size)
            if (entry.modifiedAt > 0) {
                parts += java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(entry.modifiedAt))
            }
            val perm = permissionString(entry.permissions)
            if (perm.isNotBlank()) parts += perm
            if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "))
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Filled.Folder
                    else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = if (selected) {
            { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

private fun androidx.compose.material3.SnackbarHostState.launchAndShow(
    scope: kotlinx.coroutines.CoroutineScope,
    message: String
) {
    scope.launch { showSnackbar(message) }
}
