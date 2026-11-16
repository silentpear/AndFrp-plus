@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.silentpear.andfrpplus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.silentpear.andfrpplus.data.Connection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onOpen: (Connection) -> Unit,
    onEdit: (String?) -> Unit,
    onHistory: () -> Unit
) {
    val connections by viewModel.connectionStore.connections.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var collapsedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Connection?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val filtered = remember(connections, query) {
        if (query.isBlank()) connections
        else connections.filter {
            it.name.contains(query, true) || it.host.contains(query, true)
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.group } }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索连接") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { searchOpen = false; query = "" }) {
                                    Icon(Icons.Filled.Close, "关闭搜索")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("AndFrp+", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    if (!searchOpen) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, "搜索")
                        }
                        IconButton(onClick = onHistory) {
                            Icon(Icons.Filled.History, "历史")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("新建连接") }
            )
        }
    ) { padding ->
        if (filtered.isEmpty()) {
            EmptyHome(
                modifier = Modifier.padding(padding),
                onCreate = { onEdit(null) }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (group, list) ->
                    val collapsed = group in collapsedGroups
                    item(key = "header_$group") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    collapsedGroups = if (collapsed) collapsedGroups - group
                                    else collapsedGroups + group
                                }
                                .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (collapsed) Icons.Filled.KeyboardArrowRight
                                else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "$group · ${list.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!collapsed) {
                        items(list, key = { it.id }) { conn ->
                            ConnectionCard(
                                connection = conn,
                                onClick = { onOpen(conn) },
                                onEdit = { onEdit(conn.id) },
                                onDelete = { pendingDelete = conn },
                                canMoveUp = connections.indexOfFirst { it.id == conn.id } > 0,
                                canMoveDown = connections.indexOfFirst { it.id == conn.id } < connections.lastIndex,
                                onMoveUp = {
                                    scope.launch {
                                        val ids = connections.map { it.id }.toMutableList()
                                        val i = ids.indexOf(conn.id)
                                        if (i > 0) {
                                            ids[i] = ids[i - 1].also { ids[i - 1] = ids[i] }
                                            viewModel.connectionStore.reorder(ids)
                                        }
                                    }
                                },
                                onMoveDown = {
                                    scope.launch {
                                        val ids = connections.map { it.id }.toMutableList()
                                        val i = ids.indexOf(conn.id)
                                        if (i in 0 until ids.lastIndex) {
                                            ids[i] = ids[i + 1].also { ids[i + 1] = ids[i] }
                                            viewModel.connectionStore.reorder(ids)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conn ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除连接") },
            text = { Text("确定删除「${conn.name}」吗？保存的凭据也会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.deleteConnection(conn.id)
                        pendingDelete = null
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ConnectionCard(
    connection: Connection,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = connection.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = connection.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${connection.protocol.label} · ${connection.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (connection.lastConnectedAt > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "上次连接 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            .format(Date(connection.lastConnectedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("上移") },
                        enabled = canMoveUp,
                        onClick = { menuOpen = false; onMoveUp() }
                    )
                    DropdownMenuItem(
                        text = { Text("下移") },
                        enabled = canMoveDown,
                        onClick = { menuOpen = false; onMoveDown() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier = Modifier, onCreate: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudQueue,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(20.dp))
        Text("还没有连接", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "添加 FTP / SFTP / WebDAV / SMB 服务器，\n然后把它们挂进系统的文件选择器。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onCreate) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("新建连接")
        }
    }
}
