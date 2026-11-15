package io.github.ferryapp.ferry.transfer

import io.github.ferryapp.ferry.data.SyncAction
import io.github.ferryapp.ferry.data.SyncMode
import io.github.ferryapp.ferry.data.SyncPlanEntry
import io.github.ferryapp.ferry.data.SyncTask
import io.github.ferryapp.ferry.protocol.RemoteFileSystem
import java.io.File

private data class FileInfo(val size: Long, val modifiedAt: Long)

object SyncPlanner {

    suspend fun plan(task: SyncTask, fs: RemoteFileSystem): List<SyncPlanEntry> {
        val local = walkLocal(File(task.localDir))
        val remote = walkRemote(fs, task.remoteDir)
        val keys = (local.keys + remote.keys).toSortedSet()
        val result = mutableListOf<SyncPlanEntry>()

        for (key in keys) {
            val l = local[key]
            val r = remote[key]
            when {
                l != null && r == null -> {
                    if (task.mode == SyncMode.MIRROR_REMOTE_TO_LOCAL) {
                        if (task.deleteExtra) result += SyncPlanEntry(key, SyncAction.DELETE_LOCAL, "远端不存在")
                    } else {
                        result += SyncPlanEntry(key, SyncAction.UPLOAD, "远端缺失", l.size)
                    }
                }
                l == null && r != null -> {
                    if (task.mode == SyncMode.MIRROR_LOCAL_TO_REMOTE) {
                        if (task.deleteExtra) result += SyncPlanEntry(key, SyncAction.DELETE_REMOTE, "本地不存在")
                    } else {
                        result += SyncPlanEntry(key, SyncAction.DOWNLOAD, "本地缺失", r.size)
                    }
                }
                l != null && r != null -> {
                    val sizeDiffers = l.size != r.size
                    if (!sizeDiffers) {
                        result += SyncPlanEntry(key, SyncAction.UNCHANGED, "相同", l.size)
                    } else {
                        when (task.mode) {
                            SyncMode.MIRROR_LOCAL_TO_REMOTE ->
                                result += SyncPlanEntry(key, SyncAction.UPLOAD, "大小不同", l.size)
                            SyncMode.MIRROR_REMOTE_TO_LOCAL ->
                                result += SyncPlanEntry(key, SyncAction.DOWNLOAD, "大小不同", r.size)
                            SyncMode.TWO_WAY -> {
                                val localNewer = l.modifiedAt >= r.modifiedAt
                                result += SyncPlanEntry(
                                    key,
                                    if (localNewer) SyncAction.UPLOAD else SyncAction.DOWNLOAD,
                                    if (localNewer) "双向：本地较新" else "双向：远端较新",
                                    if (localNewer) l.size else r.size
                                )
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun walkLocal(root: File): Map<String, FileInfo> {
        if (!root.exists() || !root.isDirectory) return emptyMap()
        val map = mutableMapOf<String, FileInfo>()
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                val rel = file.relativeTo(root).path.replace('\\', '/')
                map[rel] = FileInfo(file.length(), file.lastModified())
            }
        }
        return map
    }

    private suspend fun walkRemote(fs: RemoteFileSystem, root: String): Map<String, FileInfo> {
        val map = mutableMapOf<String, FileInfo>()
        suspend fun recurse(path: String, prefix: String, depth: Int) {
            if (depth > 12) return
            val entries = runCatching { fs.list(path) }.getOrDefault(emptyList())
            for (e in entries) {
                val rel = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                if (e.isDirectory) {
                    recurse(e.path, rel, depth + 1)
                } else {
                    map[rel] = FileInfo(e.size, e.modifiedAt)
                }
            }
        }
        recurse(root, "", 0)
        return map
    }
}
