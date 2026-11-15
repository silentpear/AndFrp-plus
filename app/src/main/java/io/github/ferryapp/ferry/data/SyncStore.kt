package io.github.ferryapp.ferry.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class SyncStore(context: android.content.Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = File(context.filesDir, "sync_tasks.json")
    private val _tasks = MutableStateFlow<List<SyncTask>>(emptyList())
    val tasks: StateFlow<List<SyncTask>> = _tasks.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            _tasks.value = runCatching {
                json.decodeFromString(ListSerializer(SyncTask.serializer()), file.readText())
            }.getOrDefault(emptyList())
        }
    }

    private suspend fun persist(list: List<SyncTask>) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(ListSerializer(SyncTask.serializer()), list))
        _tasks.value = list
    }

    suspend fun upsert(task: SyncTask) {
        val list = _tasks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == task.id }
        if (idx >= 0) list[idx] = task else list.add(task)
        persist(list)
    }

    suspend fun delete(id: String) = persist(_tasks.value.filterNot { it.id == id })
}
