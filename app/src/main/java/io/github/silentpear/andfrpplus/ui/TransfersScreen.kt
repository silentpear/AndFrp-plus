package io.github.silentpear.andfrpplus.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silentpear.andfrpplus.data.TransferDirection
import io.github.silentpear.andfrpplus.data.TransferItem
import io.github.silentpear.andfrpplus.data.TransferStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val queue by viewModel.transfers.queue.collectAsStateWithLifecycle()
    val active by viewModel.transfers.activeCount.collectAsStateWithLifecycle()

    val totalBytes = queue.sumOf { it.totalBytes }
    val doneBytes = queue.sumOf { it.transferredBytes }
    val overall = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat() else 0f

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = {
                    Column {
                        Text("传输", style = MaterialTheme.typography.headlineMedium)
                        if (queue.isNotEmpty()) {
                            Text(
                                "进行中 $active · 共 ${queue.size} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        IconButton(onClick = { viewModel.transfers.clearFinished() }) {
                            Icon(Icons.Filled.Delete, "清除已完成")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (queue.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("没有传输任务", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "在文件浏览里长按选择文件即可加入队列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "__summary__") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "总进度 ${(overall * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "${formatSpeed(queue, doneBytes)} · ${io.github.silentpear.andfrpplus.data.RemoteEntry.formatBytes(doneBytes)} / ${io.github.silentpear.andfrpplus.data.RemoteEntry.formatBytes(totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            CircularSummary(overall)
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { overall },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            items(queue, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    onPause = { viewModel.transfers.pause(item.id) },
                    onResume = { viewModel.transfers.resume(item.id) },
                    onCancel = { viewModel.transfers.cancel(item.id) },
                    onRetry = { viewModel.transfers.retry(item.id) }
                )
            }
        }
    }
}

@Composable
private fun CircularSummary(progress: Float) {
    val animated by animateFloatAsState(progress, tween(400), label = "ring")
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(56.dp),
            strokeWidth = 5.dp
        )
        Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TransferRow(
    item: TransferItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val progress = item.progress
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.direction == TransferDirection.DOWNLOAD)
                        Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(io.github.silentpear.andfrpplus.data.RemoteEntry.formatBytes(item.transferredBytes))
                        if (item.totalBytes > 0) {
                            append(" / ")
                            append(io.github.silentpear.andfrpplus.data.RemoteEntry.formatBytes(item.totalBytes))
                        }
                        append("  ·  ")
                        append(item.connectionName)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            when (item.status) {
                TransferStatus.RUNNING -> {
                    IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, "暂停") }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Cancel, "取消") }
                }
                TransferStatus.PAUSED -> {
                    IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, "继续") }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Cancel, "取消") }
                }
                TransferStatus.COMPLETED -> Icon(
                    Icons.Filled.CheckCircle, "完成",
                    tint = MaterialTheme.colorScheme.primary
                )
                TransferStatus.FAILED -> {
                    Icon(Icons.Filled.Error, "失败", tint = MaterialTheme.colorScheme.error)
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "重试") }
                }
                TransferStatus.CANCELLED, TransferStatus.QUEUED -> Icon(
                    Icons.Filled.Cancel, "已取消",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (item.status == TransferStatus.RUNNING || item.status == TransferStatus.PAUSED) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item.error?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun formatSpeed(queue: List<TransferItem>, done: Long): String {
    val running = queue.filter { it.status == TransferStatus.RUNNING && it.startedAt > 0 }
    if (running.isEmpty()) return "空闲"
    val elapsed = running.maxOfOrNull { (System.currentTimeMillis() - it.startedAt).coerceAtLeast(1) } ?: 1
    val bytes = running.sumOf { it.transferredBytes }
    val bps = bytes * 1000L / elapsed
    return "${io.github.silentpear.andfrpplus.data.RemoteEntry.formatBytes(bps)}/s"
}
