@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.silentpear.andfrpplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.SyncMode
import io.github.silentpear.andfrpplus.data.SyncPlanEntry
import io.github.silentpear.andfrpplus.data.SyncTask
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by viewModel.syncStore.tasks.collectAsStateWithLifecycle()
    val connections by viewModel.connectionStore.connections.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SyncTask?>(null) }
    var planDialog by remember { mutableStateOf<Pair<SyncTask, List<SyncPlanEntry>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SyncTask?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("同步", style = MaterialTheme.typography.headlineMedium) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (connections.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { editing = null; showEditor = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("新建同步") }
                )
            }
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Sync, null,
                    modifier = Modifier.height(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(16.dp))
                Text("还没有同步任务", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "同步 = 让手机上的一个文件夹和服务器的文件夹保持一致。\n\n" +
                        "例如把手机 /sdcard/Download/存档 同步到服务器 /backup：\n" +
                        "之后每次点「预演」会列出哪些文件要传、哪些要删，确认后再「执行」。\n\n" +
                        "三种方向：\n" +
                        "· 本地→远端：只往服务器传\n" +
                        "· 远端→本地：只往手机拉\n" +
                        "· 双向：两边都同步，较新的覆盖较旧的",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${modeLabel(task.mode)} · ${task.localDir.substringAfterLast('/')} ⇄ ${task.remoteDir}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                task.lastResult?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val plan = viewModel.planSync(task)
                                        busy = false
                                        if (plan.isEmpty()) snackbar.showSnackbar("无法生成计划，请检查连接") else planDialog = task to plan
                                    }
                                }
                            ) { Icon(Icons.Filled.PlayArrow, "预演") }
                            IconButton(onClick = { pendingDelete = task }) {
                                Icon(Icons.Filled.Delete, "删除")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AssistChip(onClick = {}, label = { Text(if (task.deleteExtra) "含删除" else "只增不删") })
                            if (task.wifiOnly) AssistChip(onClick = {}, label = { Text("仅 Wi-Fi") })
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        SyncEditorDialog(
            viewModel = viewModel,
            connections = connections,
            existing = editing,
            onDismiss = { showEditor = false },
            onSave = { task ->
                scope.launch {
                    viewModel.syncStore.upsert(task)
                    snackbar.showSnackbar("同步任务已保存")
                }
                showEditor = false
            }
        )
    }

    planDialog?.let { (task, plan) ->
        val changes = plan.filter { it.action != io.github.silentpear.andfrpplus.data.SyncAction.UNCHANGED }
        AlertDialog(
            onDismissRequest = { planDialog = null },
            title = { Text("同步预演") },
            text = {
                Column {
                    Text(
                        "将执行 ${changes.size} 项变动，${plan.size - changes.size} 项无需变动",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(280.dp)) {
                        items(changes.take(100), key = { it.relativePath }) { entry ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(actionLabel(entry), style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(end = 8.dp))
                                Text(entry.relativePath, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.executeSync(task, plan)
                        snackbar.showSnackbar("已加入传输队列")
                    }
                    planDialog = null
                }) { Text("执行") }
            },
            dismissButton = { TextButton(onClick = { planDialog = null }) { Text("取消") } }
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除同步任务") },
            text = { Text("确定删除「${task.name}」？不会删除任何文件。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.syncStore.delete(task.id) }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SyncEditorDialog(
    viewModel: MainViewModel,
    connections: List<Connection>,
    existing: SyncTask?,
    onDismiss: () -> Unit,
    onSave: (SyncTask) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var connectionId by remember { mutableStateOf(existing?.connectionId ?: connections.firstOrNull()?.id.orEmpty()) }
    var localDir by remember { mutableStateOf(existing?.localDir ?: "/sdcard") }
    var remoteDir by remember { mutableStateOf(existing?.remoteDir ?: "/") }
    var mode by remember { mutableStateOf(existing?.mode ?: SyncMode.MIRROR_LOCAL_TO_REMOTE) }
    var deleteExtra by remember { mutableStateOf(existing?.deleteExtra ?: false) }
    var wifiOnly by remember { mutableStateOf(existing?.wifiOnly ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建同步" else "编辑同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("任务名称") },
                    singleLine = true
                )
                Text("连接：${connections.firstOrNull { it.id == connectionId }?.name ?: "未知"}",
                    style = MaterialTheme.typography.labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    connections.forEach { conn ->
                        AssistChip(
                            onClick = { connectionId = conn.id },
                            label = { Text(conn.name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = localDir,
                    onValueChange = { localDir = it },
                    label = { Text("本地目录") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = remoteDir,
                    onValueChange = { remoteDir = it },
                    label = { Text("远端目录") },
                    singleLine = true
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SyncMode.entries.forEach { m ->
                        AssistChip(
                            onClick = { mode = m },
                            label = { Text(if (mode == m) "● " + modeLabel(m) else modeLabel(m)) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = deleteExtra, onCheckedChange = { deleteExtra = it })
                    Spacer(Modifier.padding(end = 8.dp))
                    Text("删除对侧多余文件", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                    Spacer(Modifier.padding(end = 8.dp))
                    Text("仅 Wi-Fi", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && localDir.isNotBlank() && remoteDir.isNotBlank(),
                onClick = {
                    onSave(
                        (existing ?: SyncTask(name = name, connectionId = connectionId, localDir = localDir, remoteDir = remoteDir))
                            .copy(
                                name = name,
                                connectionId = connectionId,
                                localDir = localDir,
                                remoteDir = remoteDir,
                                mode = mode,
                                deleteExtra = deleteExtra,
                                wifiOnly = wifiOnly
                            )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun modeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.MIRROR_LOCAL_TO_REMOTE -> "本地→远端"
    SyncMode.MIRROR_REMOTE_TO_LOCAL -> "远端→本地"
    SyncMode.TWO_WAY -> "双向"
}

private fun actionLabel(entry: SyncPlanEntry): String = when (entry.action) {
    io.github.silentpear.andfrpplus.data.SyncAction.UPLOAD -> "上传"
    io.github.silentpear.andfrpplus.data.SyncAction.DOWNLOAD -> "下载"
    io.github.silentpear.andfrpplus.data.SyncAction.DELETE_REMOTE -> "删远端"
    io.github.silentpear.andfrpplus.data.SyncAction.DELETE_LOCAL -> "删本地"
    io.github.silentpear.andfrpplus.data.SyncAction.UNCHANGED -> "不变"
}
