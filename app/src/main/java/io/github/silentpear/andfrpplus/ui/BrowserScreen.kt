@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package io.github.silentpear.andfrpplus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.RemoteEntry
import io.github.silentpear.andfrpplus.protocol.RemoteFileSystem
import io.github.silentpear.andfrpplus.protocol.RemotePaths
import io.github.silentpear.andfrpplus.protocol.permissionString
import kotlinx.coroutines.delay
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
    var deleteList by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var chmodTargets by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var imagePreview by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var imageIndex by remember { mutableStateOf(0) }
    var videoTarget by remember { mutableStateOf<Pair<RemoteEntry, String>?>(null) }
    var sortKey by remember { mutableStateOf(SortKey.NAME) }
    var ascending by remember { mutableStateOf(true) }

    val localContext = androidx.compose.ui.platform.LocalContext.current
    fun openExternally(entry: RemoteEntry, mime: String) {
        val conn = connection ?: return
        runCatching {
            val uri = mediaUri(localContext, conn.id, entry.path)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            localContext.startActivity(android.content.Intent.createChooser(intent, "用其他应用打开"))
        }.onFailure {
            scope.launch { snackbar.showSnackbar("没有可用的播放器") }
        }
    }

    val downloadTreePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.setDownloadTree(uri)
            scope.launch { snackbar.showSnackbar("下载目录：" + viewModel.downloadTargetLabel()) }
        }
    }

    val uploadPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val conn = connection ?: return@rememberLauncherForActivityResult
        scope.launch {
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { copyUriToCache(localContext, it) }
            }
            if (files.isEmpty()) {
                snackbar.showSnackbar("读取文件失败")
            } else {
                viewModel.queueUploads(conn, files, path)
                snackbar.showSnackbar("已加入上传队列：${files.size} 个文件")
            }
        }
    }

    var askPassword by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    fun reload(target: String, allowRetry: Boolean = true) {
        val conn = connection ?: return
        scope.launch {
            loading = true
            error = null
            var handled = false
            runCatching {
                val filesystem = viewModel.connect(conn)
                fs = filesystem
                serverInfo = runCatching { filesystem.serverInfo() }.getOrDefault("")
                entries = filesystem.list(target)
            }.onFailure { e ->
                val chain = generateSequence(e as Throwable) { it.cause }
                    .joinToString(" ") { it.message.orEmpty() }
                val looksLikeDeadSocket = listOf("abort", "Socket", "closed", "reset", "timed out")
                    .any { chain.contains(it, true) }
                if (allowRetry && looksLikeDeadSocket) {
                    // 后台被系统挂起导致连接失效：丢掉旧连接，稍等重连一次
                    viewModel.disconnect(connection.id)
                    handled = true
                    delay(600)
                    reload(target, allowRetry = false)
                } else {
                    error = e.describe()
                    if (chain.contains("authentication", true) && chain.contains("Exhausted", true)) {
                        askPassword = true
                    }
                }
            }
            if (!handled) loading = false
        }
    }

    LaunchedEffect(connectionId, path) { reload(path) }

    // 回到前台时若连接已断，自动重连
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (error != null) reload(path)
    }

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
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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
                        IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Upload, "上传文件")
                        }
                        IconButton(onClick = { reload(path) }) {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, "搜索")
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, "更多")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("下载目录…") },
                                    onClick = {
                                        menu = false
                                        downloadTreePicker.launch(null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("上传文件到当前目录") },
                                    onClick = { menu = false; uploadPicker.launch(arrayOf("*/*")) }
                                )
                                DropdownMenuItem(
                                    text = { Text("新建文件夹") },
                                    onClick = { menu = false; showNewFolder = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("返回上级") },
                                    onClick = { menu = false; path = RemotePaths.parent(path) }
                                )
                                HorizontalDivider()
                                Text(
                                    text = "排序方式",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                                )
                                listOf(
                                    SortKey.NAME to "按名称",
                                    SortKey.SIZE to "按大小",
                                    SortKey.TIME to "按修改时间"
                                ).forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text((if (sortKey == key) "● " else "   ") + label) },
                                        onClick = { sortKey = key; menu = false }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (ascending) "  升序（点此切换）" else "  降序（点此切换）") },
                                    onClick = { ascending = !ascending; menu = false }
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
        bottomBar = {
            if (selection.isNotEmpty()) {
                val chosen = entries.filter { it.path in selection }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            val conn = connection
                            if (conn != null) {
                                viewModel.queueDownloads(conn, chosen, File(viewModel.settings.lastLocalDir))
                                val dir = viewModel.safeDownloadDir(File(viewModel.settings.lastLocalDir))
                                scope.launch { snackbar.showSnackbar("已加入队列 ${chosen.size} 项 → ${dir.absolutePath}") }
                            }
                            selection = emptySet()
                        }) { Icon(Icons.Filled.Download, "下载") }

                        IconButton(onClick = { selection = entries.map { it.path }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, "全选")
                        }

                        Spacer(Modifier.weight(1f))

                        if (chosen.size == 1) {
                            val single = chosen.first()
                            if (!single.isDirectory) {
                                IconButton(onClick = {
                                    onEditFile(single.path)
                                    selection = emptySet()
                                }) { Icon(Icons.Filled.Edit, "编辑") }
                            }
                            IconButton(onClick = {
                                renameTarget = single
                                renameValue = single.name
                            }) { Icon(Icons.Filled.DriveFileRenameOutline, "重命名") }
                            IconButton(onClick = { propertiesTarget = single }) {
                                Icon(Icons.Filled.Info, "属性")
                            }
                            IconButton(onClick = { chmodTargets = chosen }) {
                                Icon(Icons.Filled.Lock, "权限")
                            }
                        }

                        IconButton(onClick = { deleteList = chosen }) {
                            Icon(Icons.Filled.Delete, "删除")
                        }
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Filled.Close, "取消选择")
                        }
                    }
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
                    Spacer(Modifier.height(12.dp))
                    if (connection != null) {
                        Text(
                            text = "主机 ${connection.host}:${connection.port}\n" +
                                "用户 「${connection.username}」\n" +
                                "认证 密码 · 密码长度 ${viewModel.credentials.getPassword(connection.id)?.length ?: 0}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { reload(path) }) { Text("重试") }
                }
                else -> {
                    val shown = remember(entries, query, sortKey, ascending) {
                        val filtered = if (query.isBlank()) entries
                        else entries.filter { it.name.contains(query, true) }
                        val comparator = when (sortKey) {
                            SortKey.NAME -> compareBy<RemoteEntry> { it.name.lowercase() }
                            SortKey.SIZE -> compareBy { it.size }
                            SortKey.TIME -> compareBy { it.modifiedAt }
                        }
                        // 目录永远排在文件前面
                        val byType = compareByDescending<RemoteEntry> { it.isDirectory }
                        val sorted = filtered.sortedWith(byType.then(if (ascending) comparator else comparator.reversed()))
                        sorted
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
                                        val mime = mimeOf(entry.name)
                                        when {
                                            mime.startsWith("image/") -> {
                                                val images = entries.filter { e -> !e.isDirectory && mimeOf(e.name).startsWith("image/") }
                                                imageIndex = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
                                                imagePreview = images
                                            }
                                            mime.startsWith("video/") -> videoTarget = entry to mime
                                            mime.startsWith("audio/") ->
                                                openExternally(entry, mime)
                                            mime.startsWith("text/") || isTextual(entry.name) ->
                                                onEditFile(entry.path)
                                            else -> propertiesTarget = entry
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

    videoTarget?.let { (entry, _) ->
        VideoPlayerDialog(
            viewModel = viewModel,
            connectionId = connectionId,
            entry = entry,
            onDismiss = { videoTarget = null }
        )
    }

    if (imagePreview.isNotEmpty()) {
        ImageGalleryDialog(
            connectionId = connectionId,
            entries = imagePreview,
            startIndex = imageIndex,
            onDismiss = { imagePreview = emptyList() }
        )
    }


    if (chmodTargets.isNotEmpty()) {
        val sample = chmodTargets.first()
        val baseType = if (sample.isDirectory) 0x4000 else 0x8000
        val initial = (sample.permissions ?: if (sample.isDirectory) 0x41ED else 0x81A4) and 0xFFF
        var bits by remember(chmodTargets) { mutableStateOf(initial) }

        AlertDialog(
            onDismissRequest = { chmodTargets = emptyList() },
            title = {
                Text(
                    if (chmodTargets.size == 1) "权限 · ${chmodTargets.first().name}"
                    else "权限 · 应用到 ${chmodTargets.size} 项"
                )
            },
            text = {
                Column {
                    Row {
                        Text("", modifier = Modifier.weight(1f))
                        listOf("所有者", "组", "其他").forEach {
                            Text(
                                it,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    listOf(
                        Triple("读", listOf(0x100, 0x020, 0x004), listOf(true, true, true)),
                        Triple("写", listOf(0x080, 0x010, 0x002), listOf(true, true, true)),
                        Triple("执行", listOf(0x040, 0x008, 0x001), listOf(true, true, true))
                    ).forEach { (label, masks, _) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, modifier = Modifier.weight(1f))
                            masks.forEach { mask ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Checkbox(
                                        checked = bits and mask != 0,
                                        onCheckedChange = {
                                            bits = if (bits and mask != 0) bits and mask.inv()
                                            else bits or mask
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "八进制 " + Integer.toOctalString(bits) +
                            "  ·  " + io.github.silentpear.andfrpplus.protocol.permissionString(baseType or bits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val filesystem = fs
                    val targets = chmodTargets
                    chmodTargets = emptyList()
                    selection = emptySet()
                    if (filesystem != null) {
                        scope.launch {
                            var failed = 0
                            targets.forEach { entry ->
                                val typeBits = if (entry.isDirectory) 0x4000 else 0x8000
                                runCatching {
                                    viewModel.chmod(filesystem, entry.path, typeBits or bits)
                                }.onFailure { failed++ }
                            }
                            if (failed > 0) {
                                snackbar.showSnackbar("$failed 项失败（FTP/WebDAV/SMB 不支持改权限）")
                            } else {
                                snackbar.showSnackbar("权限已更新")
                            }
                            reload(path)
                        }
                    }
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { chmodTargets = emptyList() }) { Text("取消") } }
        )
    }

    if (deleteList.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteList = emptyList() },
            title = { Text("删除确认") },
            text = {
                Text(
                    if (deleteList.size == 1) "确定删除「${deleteList.first().name}」？目录会连同内容一起删除。"
                    else "确定删除选中的 ${deleteList.size} 项？目录会连同内容一起删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val filesystem = fs
                    val targets = deleteList
                    deleteList = emptyList()
                    selection = emptySet()
                    if (filesystem != null) {
                        scope.launch {
                            var failed = 0
                            targets.forEach { entry ->
                                runCatching { viewModel.deleteRemote(filesystem, entry.path) }
                                    .onFailure { failed++ }
                            }
                            if (failed > 0) snackbar.showSnackbar("$failed 项删除失败")
                            reload(path)
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteList = emptyList() }) { Text("取消") } }
        )
    }

    if (askPassword) {
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("需要密码") },
            text = {
                Column {
                    Text(
                        "服务器拒绝了认证，可能是密码没有保存下来。请重新输入。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible)
                            androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    "显示密码"
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val conn = connection
                    if (conn != null && passwordInput.isNotEmpty()) {
                        viewModel.updatePassword(conn.id, passwordInput)
                        askPassword = false
                        passwordInput = ""
                        reload(path)
                    }
                }) { Text("重试") }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text("取消") } }
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
            else androidx.compose.ui.graphics.Color.Transparent
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

enum class SortKey { NAME, SIZE, TIME }

/** 把系统文件选择器返回的 Uri 复制到应用缓存，供上传使用；返回 null 表示读取失败。 */
internal fun copyUriToCache(context: android.content.Context, uri: android.net.Uri): File? = runCatching {
    val name = queryDisplayName(context, uri) ?: "upload_${System.currentTimeMillis()}"
    val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
    val target = File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    target
}.getOrNull()

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
}.getOrNull()

private val TEXT_EXTENSIONS = setOf(
    "txt", "log", "md", "markdown", "json", "yml", "yaml", "xml", "html", "htm",
    "kt", "kts", "java", "js", "ts", "py", "sh", "bash", "properties", "conf",
    "cfg", "ini", "toml", "env", "css", "gradle", "pro", "rules", "sql"
)

internal fun mimeOf(name: String): String =
    android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: if (isTextual(name)) "text/plain" else "application/octet-stream"

internal fun isTextual(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

/** 组装指向本项目 DocumentsProvider 的文档 URI，可直接交给播放器/图片加载器。 */
internal fun mediaUri(context: android.content.Context, connectionId: String, path: String): android.net.Uri =
    android.provider.DocumentsContract.buildDocumentUri(
        "${context.packageName}.documents",
        "$connectionId|$path"
    )

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageGalleryDialog(
    connectionId: String,
    entries: List<RemoteEntry>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
        pageCount = { entries.size }
    )

    val current = entries.getOrNull(pagerState.currentPage) ?: entries.first()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${pagerState.currentPage + 1}/${entries.size} · ${current.name}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) { page ->
                    RemoteImage(
                        connectionId = connectionId,
                        entry = entries[page],
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "左右滑动切换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun RemoteImage(
    connectionId: String,
    entry: RemoteEntry,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(entry.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failure by remember(entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.path) {
        bitmap = null
        failure = false
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val uri = mediaUri(context, connectionId, entry.path)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                var scale = 1
                while (bounds.outWidth / scale > 2048 || bounds.outHeight / scale > 2048) scale *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
        }
        if (bitmap == null) failure = true
    }

    var scale by remember(entry.path) { mutableStateOf(1f) }
    var offset by remember(entry.path) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier
            .pointerInput(entry.path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    scale = next
                    offset = if (next > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                }
            }
            .pointerInput(entry.path) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            failure -> Text("无法加载图片", color = MaterialTheme.colorScheme.error)
            bitmap == null -> CircularProgressIndicator()
            else -> androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = entry.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }
}
