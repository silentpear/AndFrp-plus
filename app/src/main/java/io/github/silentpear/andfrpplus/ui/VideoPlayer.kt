package io.github.silentpear.andfrpplus.ui

import android.media.MediaPlayer
import android.widget.MediaController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.silentpear.andfrpplus.data.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置视频播放器：先下载到缓存再播，避免远端管道不可 seek 导致黑屏。
 * 控制条包含播放/暂停、±10 秒、进度拖拽、倍速、全屏。
 */
@Composable
fun VideoPlayerDialog(
    viewModel: MainViewModel,
    connectionId: String,
    entry: RemoteEntry,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var localFile by remember(entry.path) { mutableStateOf<File?>(null) }
    var progress by remember(entry.path) { mutableStateOf(0f) }
    var failure by remember(entry.path) { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }

    var player by remember(entry.path) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(entry.path) { mutableStateOf(false) }
    var playing by remember(entry.path) { mutableStateOf(true) }
    var duration by remember(entry.path) { mutableStateOf(0) }
    var position by remember(entry.path) { mutableStateOf(0) }
    var speed by remember(entry.path) { mutableStateOf(1f) }

    // 准备文件
    LaunchedEffect(entry.path) {
        localFile = null
        failure = null
        progress = 0f
        prepared = false
        val conn = viewModel.connectionStore.byId(connectionId)
        if (conn == null) {
            failure = "连接已删除"
            return@LaunchedEffect
        }
        val target = File(context.cacheDir, "media/${entry.name}")
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() > 0) {
            localFile = target
            return@LaunchedEffect
        }
        runCatching {
            val fs = viewModel.connect(conn)
            fs.download(entry.path, target) { done ->
                if (entry.size > 0) progress = (done.toFloat() / entry.size).coerceIn(0f, 1f)
            }
        }.onFailure { failure = it.message ?: "下载失败" }
        if (failure == null) localFile = target
    }

    // 进度轮询
    LaunchedEffect(player, prepared) {
        while (prepared) {
            val p = player ?: break
            runCatching {
                if (p.isPlaying) position = p.currentPosition
            }
            delay(400)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !fullscreen)
    ) {
        Surface(
            color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.surface,
            shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(20.dp),
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier
        ) {
            Column(Modifier.padding(if (fullscreen) 0.dp else 12.dp)) {
                if (!fullscreen) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (fullscreen) 0.dp else 260.dp)
                        .then(if (fullscreen) Modifier.weight(1f) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        failure != null -> Text(
                            failure ?: "",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp)
                        )
                        localFile == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "正在准备播放 ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                android.view.SurfaceView(ctx).apply {
                                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                        override fun surfaceCreated(h: android.view.SurfaceHolder) {
                                            if (player != null) return
                                            runCatching {
                                                val mp = MediaPlayer().apply {
                                                    setDataSource(localFile!!.absolutePath)
                                                    setSurface(h.surface)
                                                    setOnPreparedListener {
                                                        duration = it.duration
                                                        prepared = true
                                                        it.start()
                                                        playing = true
                                                    }
                                                    setOnCompletionListener {
                                                        playing = false
                                                        position = duration
                                                    }
                                                    setOnErrorListener { _, _, _ ->
                                                        failure = "系统无法解码该视频"
                                                        true
                                                    }
                                                    prepareAsync()
                                                }
                                                player = mp
                                            }.onFailure { failure = it.message }
                                        }

                                        override fun surfaceChanged(
                                            h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int
                                        ) = Unit

                                        override fun surfaceDestroyed(h: android.view.SurfaceHolder) = Unit
                                    })
                                }
                            }
                        )
                    }
                }

                if (failure == null && localFile != null) {
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { position = (it * duration).toInt() },
                        onValueChangeFinished = {
                            runCatching { player?.seekTo(position) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = { seekBy(player, position - 10_000, duration) { position = it } }) {
                            Icon(Icons.Filled.FastRewind, "后退 10 秒")
                        }
                        IconButton(onClick = {
                            val p = player ?: return@IconButton
                            runCatching {
                                if (p.isPlaying) {
                                    p.pause()
                                    playing = false
                                } else {
                                    p.start()
                                    playing = true
                                }
                            }
                        }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) "暂停" else "播放",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = { seekBy(player, position + 10_000, duration) { position = it } }) {
                            Icon(Icons.Filled.FastForward, "前进 10 秒")
                        }
                        TextButton(onClick = {
                            val next = when (speed) {
                                1f -> 1.5f
                                1.5f -> 2f
                                2f -> 0.5f
                                else -> 1f
                            }
                            speed = next
                            runCatching {
                                val p = player ?: return@runCatching
                                val wasPlaying = p.isPlaying
                                p.playbackParams = p.playbackParams.setSpeed(next)
                                if (!wasPlaying) p.pause()
                            }
                        }) { Text(if (speed == 1f) "1×" else "${speed}×") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { fullscreen = !fullscreen }) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                if (fullscreen) "退出全屏" else "全屏"
                            )
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭") }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                }
            }
        }
    }
}

private fun seekBy(
    player: MediaPlayer?,
    target: Int,
    duration: Int,
    onSeek: (Int) -> Unit
) {
    val p = player ?: return
    val clamped = target.coerceIn(0, if (duration > 0) duration else 0)
    runCatching { p.seekTo(clamped) }
    onSeek(clamped)
}

private fun formatTime(ms: Int): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
