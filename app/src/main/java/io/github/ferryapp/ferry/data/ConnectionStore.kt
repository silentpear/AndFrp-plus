package io.github.ferryapp.ferry.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class ConnectionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val file = File(context.filesDir, "connections.json")
    private val mutex = Mutex()
    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _connections.value = readFromDisk()
        }
    }

    private fun readFromDisk(): List<Connection> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Connection.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    private suspend fun persist(list: List<Connection>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Connection.serializer()), list))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            _connections.value = list
        }
    }

    fun snapshot(): List<Connection> = _connections.value

    fun byId(id: String): Connection? = _connections.value.firstOrNull { it.id == id }

    suspend fun upsert(connection: Connection) {
        val current = _connections.value.toMutableList()
        val idx = current.indexOfFirst { it.id == connection.id }
        if (idx >= 0) current[idx] = connection else current.add(connection)
        persist(current.sortedBy { it.sortOrder })
    }

    suspend fun delete(id: String) {
        persist(_connections.value.filterNot { it.id == id })
    }

    suspend fun touch(id: String) {
        val current = _connections.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        current[idx] = current[idx].copy(lastConnectedAt = System.currentTimeMillis())
        persist(current)
    }

    suspend fun reorder(ids: List<String>) {
        val map = _connections.value.associateBy { it.id }
        val ordered = ids.mapNotNull { map[it] }.mapIndexed { i, c -> c.copy(sortOrder = i) }
        persist(ordered)
    }

    suspend fun importJson(text: String): Int {
        val imported = json.decodeFromString(ListSerializer(Connection.serializer()), text)
        val existing = _connections.value.associateBy { it.id }.toMutableMap()
        imported.forEach { existing[it.id] = it }
        persist(existing.values.sortedBy { it.sortOrder })
        return imported.size
    }

    fun exportJson(): String = json.encodeToString(
        ListSerializer(Connection.serializer()),
        _connections.value
    )

    fun groups(): List<String> = _connections.value.map { it.group }.distinct().sorted()
}
