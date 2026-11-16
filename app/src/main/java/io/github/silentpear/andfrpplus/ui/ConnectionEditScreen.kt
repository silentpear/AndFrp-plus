package io.github.silentpear.andfrpplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.silentpear.andfrpplus.data.AuthType
import io.github.silentpear.andfrpplus.data.Connection
import io.github.silentpear.andfrpplus.data.Protocol
import io.github.silentpear.andfrpplus.data.ProxyType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionEditScreen(
    viewModel: MainViewModel,
    connectionId: String?,
    onBack: () -> Unit
) {
    val existing = remember(connectionId) {
        connectionId?.let { viewModel.connectionStore.byId(it) }
    }
    val pendingImport = viewModel.clipboardImport.value
    // 新建连接时固定一个 id，保证「测试连接」与「保存」用的是同一条记录
    val draftId = remember(existing) { existing?.id ?: java.util.UUID.randomUUID().toString() }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SFTP) }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: Protocol.SFTP.defaultPort).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(connectionId?.let { viewModel.credentials.getPassword(it) } ?: "") }
    var initialPath by remember { mutableStateOf(existing?.initialPath ?: "/") }
    var group by remember { mutableStateOf(existing?.group ?: "默认") }
    var passive by remember { mutableStateOf(existing?.passiveMode ?: true) }
    var timeout by remember { mutableStateOf((existing?.timeoutSeconds ?: 30).toString()) }
    var encoding by remember { mutableStateOf(existing?.encoding ?: "UTF-8") }
    var authType by remember { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var keyPath by remember { mutableStateOf(existing?.privateKeyPath ?: "") }
    var passphrase by remember { mutableStateOf(connectionId?.let { viewModel.credentials.getPassphrase(it) } ?: "") }
    var proxyType by remember { mutableStateOf(existing?.proxyType ?: ProxyType.NONE) }
    var proxyHost by remember { mutableStateOf(existing?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(existing?.proxyPort?.toString() ?: "") }
    var domain by remember { mutableStateOf(existing?.smbDomain ?: "") }
    var insecure by remember { mutableStateOf(existing?.insecureTls ?: false) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardText = remember {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull()
    }

    LaunchedEffect(pendingImport) {
        if (pendingImport != null && existing == null) {
            protocol = pendingImport.protocol
            host = pendingImport.host
            port = pendingImport.port.toString()
            username = pendingImport.username
            initialPath = pendingImport.path
            if (name.isBlank()) name = "${pendingImport.protocol.label} ${pendingImport.host}"
            viewModel.consumeClipboardImport()
        }
    }

    fun buildConnection(): Connection = (existing ?: Connection(id = draftId, name = "", host = "")).copy(
        name = name.trim().ifBlank { host.trim() },
        protocol = protocol,
        host = host.trim(),
        port = port.toIntOrNull() ?: protocol.defaultPort,
        username = username.trim(),
        authType = authType,
        initialPath = initialPath.trim().ifBlank { "/" },
        group = group.trim().ifBlank { "默认" },
        passiveMode = passive,
        timeoutSeconds = timeout.toIntOrNull() ?: 30,
        encoding = encoding,
        proxyType = proxyType,
        proxyHost = proxyHost.trim(),
        proxyPort = proxyPort.toIntOrNull() ?: 0,
        privateKeyPath = keyPath.takeIf { it.isNotBlank() },
        smbDomain = domain,
        insecureTls = insecure
    )

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
                title = { Text(if (existing == null) "新建连接" else "编辑连接") },
                actions = {
                    TextButton(
                        enabled = host.isNotBlank(),
                        onClick = {
                            scope.launch {
                                val conn = buildConnection()
                                viewModel.saveConnection(conn, password, passphrase)
                                snackbar.showSnackbar("已保存")
                                onBack()
                            }
                        }
                    ) { Text("保存") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        val text = clipboardText
                        val parsed = text?.let { viewModel.parseConnectionUri(it) }
                        if (parsed != null) {
                            protocol = parsed.protocol
                            host = parsed.host
                            port = parsed.port.toString()
                            username = parsed.username
                            initialPath = parsed.path
                            if (name.isBlank()) name = "${parsed.protocol.label} ${parsed.host}"
                            scope.launch { snackbar.showSnackbar("已从剪贴板填入") }
                        } else {
                            scope.launch { snackbar.showSnackbar("剪贴板里没有可识别的 sftp:// 或 ftp:// 链接") }
                        }
                    },
                    label = { Text("从剪贴板粘贴链接") },
                    leadingIcon = { Icon(Icons.Filled.ContentPaste, null) }
                )
            }

            Text("协议", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 4
            ) {
                Protocol.entries.forEach { p ->
                    FilterChip(
                        selected = protocol == p,
                        onClick = {
                            protocol = p
                            val stillDefault = port == existing?.port?.toString() ||
                                port == Protocol.SFTP.defaultPort.toString()
                            if (stillDefault) port = p.defaultPort.toString()
                        },
                        label = {
                            Text(p.shortLabel, maxLines = 1, softWrap = false)
                        }
                    )
                }
            }


            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("主机 / IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (protocol == Protocol.SMB) "密码" else "密码") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            "显示密码"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = initialPath,
                onValueChange = { initialPath = it },
                label = { Text("初始目录") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = group,
                onValueChange = { group = it },
                label = { Text("分组") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (protocol == Protocol.SFTP || protocol == Protocol.SCP) {
                Text("认证方式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authType == AuthType.PASSWORD,
                        onClick = { authType = AuthType.PASSWORD },
                        label = { Text("密码") }
                    )
                    FilterChip(
                        selected = authType == AuthType.PRIVATE_KEY,
                        onClick = { authType = AuthType.PRIVATE_KEY },
                        label = { Text("私钥") }
                    )
                }
                if (authType == AuthType.PRIVATE_KEY) {
                    OutlinedTextField(
                        value = keyPath,
                        onValueChange = { keyPath = it },
                        label = { Text("私钥文件路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("私钥口令（可留空）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (protocol == Protocol.SMB) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("域 / Workgroup（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("被动模式", modifier = Modifier.weight(1f))
                Switch(checked = passive, onCheckedChange = { passive = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("忽略 TLS 证书校验", modifier = Modifier.weight(1f))
                Switch(checked = insecure, onCheckedChange = { insecure = it })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter { c -> c.isDigit() } },
                    label = { Text("超时（秒）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = encoding,
                    onValueChange = { encoding = it },
                    label = { Text("编码") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(4.dp))
            Text("代理", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyType.entries.forEach { p ->
                    FilterChip(
                        selected = proxyType == p,
                        onClick = { proxyType = p },
                        label = { Text(p.name) }
                    )
                }
            }
            if (proxyType != ProxyType.NONE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text("代理地址") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { proxyPort = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = host.isNotBlank() && !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            testResult = null
                            val result = viewModel.testConnection(buildConnection(), password, passphrase)
                            testResult = result.fold(
                                onSuccess = { "连接成功：$it" },
                                onFailure = { "失败：" + it.describe() }
                            )
                            testing = false
                        }
                    }
                ) { Text(if (testing) "测试中…" else "测试连接") }

                Button(
                    enabled = host.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val conn = buildConnection()
                            viewModel.saveConnection(conn, password, passphrase)
                            onBack()
                        }
                    }
                ) { Text("保存") }
            }

            testResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 把异常链展开，避免只看到最外层一句看不懂的话。 */
internal fun Throwable.describe(): String =
    generateSequence(this) { it.cause }
        .take(5)
        .joinToString("\n") { "${it.javaClass.simpleName}: ${it.message ?: "无描述"}" }
