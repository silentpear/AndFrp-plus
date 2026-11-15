package io.github.silentpear.andfrpplus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据存储。
 *
 * 教训：EncryptedSharedPreferences 在部分机型上会出现「写进去成功、读回来是 null」
 * （Keystore 解密失败被吞掉），表现为密码凭空消失、连接一直报认证失败。
 *
 * 因此这里改成双写双读：
 *  - 能创建加密存储时，同一份值同时写入加密存储和普通存储；
 *  - 读取时先读加密存储，拿不到再读普通存储。
 * 只要其中一个可用就不会丢密码。两者都在应用私有目录内，其他应用无法读取。
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val encryptedPrefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "ferry_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    private val plainPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("ferry_credentials_plain", Context.MODE_PRIVATE)
    }

    /** 加密存储是否可用（能写入并读回）。不可用时只用普通存储。 */
    val encryptedUsable: Boolean = runCatching {
        val p = encryptedPrefs ?: return@runCatching false
        p.edit().putString("__probe__", "ok").commit()
        val ok = p.getString("__probe__", null) == "ok"
        p.edit().remove("__probe__").commit()
        ok
    }.getOrDefault(false)

    private fun write(key: String, value: String) {
        if (encryptedUsable) {
            runCatching { encryptedPrefs?.edit()?.putString(key, value)?.commit() }
        }
        // 始终写一份普通存储作为兜底
        runCatching { plainPrefs.edit().putString(key, value).commit() }
    }

    private fun read(key: String): String? {
        if (encryptedUsable) {
            val v = runCatching { encryptedPrefs?.getString(key, null) }.getOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        return runCatching { plainPrefs.getString(key, null) }.getOrNull()
    }

    fun putPassword(connectionId: String, password: String) = write(pwKey(connectionId), password)

    fun getPassword(connectionId: String): String? = read(pwKey(connectionId))

    fun putPassphrase(connectionId: String, passphrase: String) = write(ppKey(connectionId), passphrase)

    fun getPassphrase(connectionId: String): String? = read(ppKey(connectionId))

    fun hasPassword(connectionId: String): Boolean = !getPassword(connectionId).isNullOrEmpty()

    fun passwordLength(connectionId: String): Int = getPassword(connectionId)?.length ?: 0

    fun clear(connectionId: String) {
        runCatching { encryptedPrefs?.edit()?.remove(pwKey(connectionId))?.remove(ppKey(connectionId))?.commit() }
        runCatching { plainPrefs.edit().remove(pwKey(connectionId)).remove(ppKey(connectionId)).commit() }
    }

    private fun pwKey(id: String) = "pw_$id"
    private fun ppKey(id: String) = "pp_$id"
}
