package io.github.silentpear.andfrpplus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据存储。
 *
 * 优先使用 EncryptedSharedPreferences（Keystore 加密）。但它在部分机型上会
 * 「写入成功、重启后解密失败」，表现就是密码凭空消失、连接报认证失败。
 * 因此创建时做一次写入-读回探测，探测不通过就直接用普通 SharedPreferences，
 * 保证功能可用（普通 prefs 位于应用私有目录，同样不对外暴露）。
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

    /** 加密存储是否真正可用（写入后能读回）。 */
    val encryptedUsable: Boolean = runCatching {
        val p = encryptedPrefs ?: return@runCatching false
        p.edit().putString(KEY_PROBE, PROBE_VALUE).commit()
        val ok = p.getString(KEY_PROBE, null) == PROBE_VALUE
        p.edit().remove(KEY_PROBE).commit()
        ok
    }.getOrDefault(false)

    private val prefs: SharedPreferences
        get() = if (encryptedUsable && encryptedPrefs != null) encryptedPrefs else plainPrefs

    fun putPassword(connectionId: String, password: String) {
        runCatching { prefs.edit().putString(pwKey(connectionId), password).commit() }
        // 加密存储不可用时，同时写一份到普通存储，确保下次一定读得到
        if (!encryptedUsable) Unit
    }

    fun getPassword(connectionId: String): String? = runCatching {
        val primary = prefs.getString(pwKey(connectionId), null)
        if (!primary.isNullOrEmpty()) primary
        else plainPrefs.getString(pwKey(connectionId), null)
    }.getOrNull()

    fun putPassphrase(connectionId: String, passphrase: String) {
        runCatching { prefs.edit().putString(ppKey(connectionId), passphrase).commit() }
    }

    fun getPassphrase(connectionId: String): String? = runCatching {
        val primary = prefs.getString(ppKey(connectionId), null)
        if (!primary.isNullOrEmpty()) primary
        else plainPrefs.getString(ppKey(connectionId), null)
    }.getOrNull()

    fun hasPassword(connectionId: String): Boolean = !getPassword(connectionId).isNullOrEmpty()

    fun clear(connectionId: String) {
        runCatching {
            prefs.edit().remove(pwKey(connectionId)).remove(ppKey(connectionId)).commit()
            plainPrefs.edit().remove(pwKey(connectionId)).remove(ppKey(connectionId)).commit()
        }
    }

    private fun pwKey(id: String) = "pw_$id"
    private fun ppKey(id: String) = "pp_$id"

    private companion object {
        const val KEY_PROBE = "__probe__"
        const val PROBE_VALUE = "ok"
    }
}
