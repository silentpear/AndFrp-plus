package io.github.ferryapp.ferry.protocol

import io.github.ferryapp.ferry.data.Connection
import io.github.ferryapp.ferry.data.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavFileSystem(
    private val connection: Connection,
    private val password: String?
) : RemoteFileSystem {

    private var http: OkHttpClient? = null
    private var baseUrl: String = ""

    private fun client(): OkHttpClient = http ?: throw RemoteException("未连接")

    private fun url(path: String): String {
        val p = path.trimStart('/')
        return baseUrl.trimEnd('/') + if (p.isEmpty()) "/" else "/" + encodePath(p)
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "/")
        }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (http != null) return@withContext
        val builder = OkHttpClient.Builder()
            .connectTimeout(connection.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(connection.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(connection.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (connection.insecureTls) {
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
                override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
                override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        val scheme = if (connection.protocol == io.github.ferryapp.ferry.data.Protocol.WEBDAV) {
            if (connection.port == 443 || connection.insecureTls) "https" else "http"
        } else "https"
        val hostPart = if (connection.insecureTls && connection.port == 80) {
            "http://${connection.host}:${connection.port}"
        } else {
            "$scheme://${connection.host}:${connection.port}"
        }
        baseUrl = hostPart + connection.initialPath.let { if (it.isBlank() || it == "/") "" else it }
        val c = builder.build()
        val req = Request.Builder()
            .url(url("/"))
            .method("PROPFIND", "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0")
            .apply { if (connection.username.isNotBlank()) header("Authorization", Credentials.basic(connection.username, password.orEmpty())) }
            .build()
        try {
            c.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw RemoteException("WebDAV 认证失败")
                if (resp.code >= 400) throw RemoteException("WebDAV 连接失败：HTTP ${resp.code}")
            }
            http = c
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("WebDAV 连接失败：${e.message}", e)
        }
    }

    private fun Request.Builder.auth(): Request.Builder =
        apply { if (connection.username.isNotBlank()) header("Authorization", Credentials.basic(connection.username, password.orEmpty())) }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/><d:getcontentlength/><d:getlastmodified/>
                <d:resourcetype/><d:getcontenttype/>
              </d:prop>
            </d:propfind>""".trimIndent()
        val req = Request.Builder()
            .url(url(path))
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
            .auth()
            .build()
        client().newCall(req).execute().use { resp ->
            if (resp.code >= 400) throw RemoteException("列目录失败：HTTP ${resp.code}")
            val xml = resp.body?.string().orEmpty()
            parseMultiStatus(xml, path)
        }
    }

    private fun parseMultiStatus(xml: String, requestPath: String): List<RemoteEntry> {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val result = mutableListOf<RemoteEntry>()
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as Element
            val href = textOf(resp, "DAV:", "href") ?: continue
            val pathPart = runCatching {
                URL(if (href.startsWith("http")) href else "http://x$href").path
            }.getOrNull() ?: href
            val decoded = URLDecoder.decode(pathPart, "UTF-8")
            val normalizedRequest = url(requestPath).let { runCatching { URL(it).path }.getOrNull() ?: it }.trimEnd('/')
            val thisPath = decoded.trimEnd('/')
            if (thisPath == normalizedRequest) continue
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) continue
            val isDir = resp.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = textOf(resp, "DAV:", "getcontentlength")?.toLongOrNull() ?: 0L
            val modified = textOf(resp, "DAV:", "getlastmodified")?.let {
                runCatching { java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(it, java.time.Instant::from).toEpochMilli() }.getOrNull()
            } ?: 0L
            result += RemoteEntry(
                name = name,
                path = RemotePaths.join(requestPath, name),
                isDirectory = isDir,
                size = size,
                modifiedAt = modified
            )
        }
        return result
    }

    private fun textOf(parent: Element, ns: String, tag: String): String? {
        val nodes = parent.getElementsByTagNameNS(ns, tag)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim()
    }

    override suspend fun stat(path: String): RemoteEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url(path))
                .head()
                .auth()
                .build()
            client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val dir = resp.header("Content-Type")?.contains("directory") == true
                RemoteEntry(
                    name = RemotePaths.name(path),
                    path = path,
                    isDirectory = dir,
                    size = resp.header("Content-Length")?.toLongOrNull() ?: 0L,
                    modifiedAt = resp.header("Last-Modified")?.let { parseHttpDate(it) } ?: 0L
                )
            }
        }.getOrNull()
    }

    private fun parseHttpDate(value: String): Long = runCatching {
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, java.time.Instant::from).toEpochMilli()
    }.getOrDefault(0L)

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val parts = path.trim('/').split('/')
        var current = ""
        for (part in parts) {
            current = "$current/$part"
            val req = Request.Builder().url(url(current)).method("MKCOL", null).auth().build()
            client().newCall(req).execute().use { resp ->
                if (resp.code >= 400 && resp.code != 405) {
                    throw RemoteException("创建目录失败：HTTP ${resp.code}")
                }
            }
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).delete().auth().build()
        client().newCall(req).execute().use { resp ->
            if (resp.code >= 400 && resp.code != 404) throw RemoteException("删除失败：HTTP ${resp.code}")
        }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val dest = java.net.URI(url(to)).toString()
        val req = Request.Builder()
            .url(url(from))
            .method("MOVE", null)
            .header("Destination", dest)
            .header("Overwrite", "T")
            .auth()
            .build()
        client().newCall(req).execute().use { resp ->
            if (resp.code >= 400) throw RemoteException("重命名失败：HTTP ${resp.code}")
        }
    }

    override suspend fun chmod(path: String, mode: Int) {
        throw RemoteException("WebDAV 不支持修改权限")
    }

    override suspend fun download(remote: String, local: File, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(remote)).get().auth().build()
            client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RemoteException("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: throw RemoteException("响应为空")
                val total = body.contentLength()
                local.parentFile?.mkdirs()
                var written = 0L
                var lastTick = 0L
                body.byteStream().use { input ->
                    java.io.FileOutputStream(local).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (written - lastTick > 256 * 1024) {
                                onProgress(written)
                                lastTick = written
                            }
                        }
                    }
                }
                onProgress(if (total > 0) total else written)
            }
        }

    override suspend fun upload(local: File, remote: String, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val total = local.length()
            val req = Request.Builder()
                .url(url(remote))
                .put(local.asRequestBody("application/octet-stream".toMediaType()))
                .auth()
                .build()
            client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RemoteException("上传失败：HTTP ${resp.code}")
            }
            onProgress(total)
        }

    override suspend fun size(path: String): Long = stat(path)?.size ?: 0L

    override suspend fun exists(path: String): Boolean = stat(path) != null

    override suspend fun quota(): Pair<Long, Long>? = null

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).get().auth().build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RemoteException("读取失败：HTTP ${resp.code}")
            val text = resp.body?.string() ?: ""
            text.take(maxBytes)
        }
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url(path))
            .put(content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .auth()
            .build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RemoteException("写入失败：HTTP ${resp.code}")
        }
    }

    override suspend fun serverInfo(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/")).head().auth().build()
        client().newCall(req).execute().use { resp ->
            (resp.header("Server") ?: resp.header("WWW-Authenticate") ?: "WebDAV").trim()
        }
    }

    override fun close() {
        http = null
    }
}
