package io.github.silentpear.andfrpplus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import io.github.silentpear.andfrpplus.data.ConflictPolicy
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onHistory: () -> Unit
) {
    val settings = viewModel.settings
    var concurrency by remember { mutableStateOf(settings.concurrency) }
    var dynamic by remember { mutableStateOf(settings.dynamicColor) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var keepScreen by remember { mutableStateOf(settings.keepScreenOn) }
    var showHidden by remember { mutableStateOf(settings.showHidden) }
    var appLock by remember { mutableStateOf(settings.appLock) }
    var darkMode by remember { mutableStateOf(settings.darkMode) }
    var speedLimit by remember { mutableStateOf(settings.speedLimitKbps) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("设置", style = MaterialTheme.typography.headlineMedium) }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionLabel("传输")
            ListItem(
                headlineContent = { Text("并发传输数") },
                supportingContent = { Text("同时进行的任务：$concurrency") },
                leadingContent = { Icon(Icons.Filled.Speed, null) },
                trailingContent = {
                    Slider(
                        value = concurrency.toFloat(),
                        onValueChange = {
                            concurrency = it.toInt().coerceIn(1, 8)
                            settings.concurrency = concurrency
                        },
                        valueRange = 1f..8f,
                        steps = 6,
                        modifier = Modifier.width(140.dp)
                    )
                }
            )
            ListItem(
                headlineContent = { Text("仅 Wi-Fi 传输") },
                supportingContent = { Text("移动网络下暂停传输队列") },
                leadingContent = { Icon(Icons.Filled.Wifi, null) },
                trailingContent = {
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; settings.wifiOnly = it })
                }
            )
            ListItem(
                headlineContent = { Text("限速") },
                supportingContent = {
                    Text(if (speedLimit == 0) "不限制" else "$speedLimit KB/s（占位，当前版本未启用）")
                },
                leadingContent = { Icon(Icons.Filled.Speed, null) },
                trailingContent = {
                    Slider(
                        value = speedLimit.toFloat(),
                        onValueChange = { speedLimit = it.toInt(); settings.speedLimitKbps = speedLimit },
                        valueRange = 0f..10240f,
                        modifier = Modifier.width(140.dp)
                    )
                }
            )

            SectionLabel("外观")
            ListItem(
                headlineContent = { Text("动态取色") },
                supportingContent = { Text("跟随系统壁纸（Material You，Android 12+）") },
                leadingContent = { Icon(Icons.Filled.Palette, null) },
                trailingContent = {
                    Switch(checked = dynamic, onCheckedChange = { dynamic = it; settings.dynamicColor = it })
                }
            )
            ListItem(
                headlineContent = { Text("深色模式") },
                supportingContent = { Text(listOf("跟随系统", "浅色", "深色")[darkMode.coerceIn(0, 2)]) },
                leadingContent = { Icon(Icons.Filled.Palette, null) },
                modifier = Modifier.clickable {
                    darkMode = (darkMode + 1) % 3
                    settings.darkMode = darkMode
                }
            )

            SectionLabel("浏览")
            ListItem(
                headlineContent = { Text("显示隐藏文件") },
                trailingContent = {
                    Switch(checked = showHidden, onCheckedChange = { showHidden = it; settings.showHidden = it })
                }
            )
            ListItem(
                headlineContent = { Text("屏幕常亮") },
                trailingContent = {
                    Switch(checked = keepScreen, onCheckedChange = { keepScreen = it; settings.keepScreenOn = it })
                }
            )

            SectionLabel("安全")
            ListItem(
                headlineContent = { Text("应用锁") },
                supportingContent = { Text("启动时使用指纹或设备密码解锁") },
                leadingContent = { Icon(Icons.Filled.Lock, null) },
                trailingContent = {
                    Switch(checked = appLock, onCheckedChange = { appLock = it; settings.appLock = it })
                }
            )

            SectionLabel("文件")
            var dirLabel by remember { mutableStateOf(viewModel.downloadTargetLabel()) }
            val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    viewModel.setDownloadTree(uri)
                    dirLabel = viewModel.downloadTargetLabel()
                }
            }
            ListItem(
                headlineContent = { Text("下载目录") },
                supportingContent = { Text(dirLabel) },
                leadingContent = { Icon(Icons.Filled.FileOpen, null) },
                modifier = Modifier.clickable { treePicker.launch(null) }
            )
            if (!viewModel.settings.downloadTreeUri.isNullOrEmpty()) {
                ListItem(
                    headlineContent = { Text("恢复默认下载目录") },
                    supportingContent = { Text("改回应用专属目录") },
                    leadingContent = { Icon(Icons.Filled.Refresh, null) },
                    modifier = Modifier.clickable {
                        viewModel.setDownloadTree(null)
                        dirLabel = viewModel.downloadTargetLabel()
                    }
                )
            }
            ListItem(
                headlineContent = { Text("传输历史") },
                leadingContent = { Icon(Icons.Filled.FileOpen, null) },
                modifier = Modifier.clickable(onClick = onHistory)
            )
            ListItem(
                headlineContent = { Text("服务器接入系统文件管理器") },
                supportingContent = {
                    Text("在系统「文件」→「其他存储」中可见 AndFrp+，可直接读写远端文件")
                },
                leadingContent = { Icon(Icons.Filled.Info, null) }
            )

            SectionLabel("关于")
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ListItem(
                headlineContent = { Text("AndFrp+") },
                supportingContent = {
                    Text("版本 ${BuildConfigVersion()} · 开源 · 无遥测\n点击打开项目主页")
                },
                leadingContent = { Icon(Icons.Filled.Info, null) },
                modifier = Modifier.clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(PROJECT_URL)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp)
    )
}

private fun BuildConfigVersion(): String = io.github.silentpear.andfrpplus.BuildConfig.VERSION_NAME

private const val PROJECT_URL = "https://github.com/silentpear/AndFrp-plus/tree/main"
