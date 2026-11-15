package io.github.ferryapp.ferry.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class HistoryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "history.json")
    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            _records.value = runCatching {
                json.decodeFromString(ListSerializer(HistoryRecord.serializer()), file.readText())
            }.getOrDefault(emptyList())
        }
    }

    suspend fun add(record: HistoryRecord) = withContext(Dispatchers.IO) {
        val updated = (listOf(record) + _records.value).take(500)
        _records.value = updated
        file.writeText(json.encodeToString(ListSerializer(HistoryRecord.serializer()), updated))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _records.value = emptyList()
        file.delete()
    }
}
