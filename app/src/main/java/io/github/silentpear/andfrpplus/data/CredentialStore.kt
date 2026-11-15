package io.github.silentpear.andfrpplus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val context = context.applicationContext

    /**
     * 优先用 EncryptedSharedPreferences；在部分机型上它的 Keystore 会初始化失败，
     * 这时回退到普通 SharedPreferences，保证功能可用（安全性降低但不会存不了密码）。
     */
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "ferry_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("ferry_credentials_fallback", Context.MODE_PRIVATE)
        }
    }

    var encrypted: Boolean = true
        private set

    fun putPassword(connectionId: String, password: String) {
        runCatching { prefs.edit().putString("pw_$connectionId", password).apply() }
    }

    fun getPassword(connectionId: String): String? =
        runCatching { prefs.getString("pw_$connectionId", null) }.getOrNull()

    fun putPassphrase(connectionId: String, passphrase: String) {
        runCatching { prefs.edit().putString("pp_$connectionId", passphrase).apply() }
    }

    fun getPassphrase(connectionId: String): String? =
        runCatching { prefs.getString("pp_$connectionId", null) }.getOrNull()

    fun hasPassword(connectionId: String): Boolean =
        !getPassword(connectionId).isNullOrEmpty()

    fun clear(connectionId: String) {
        runCatching {
            prefs.edit()
                .remove("pw_$connectionId")
                .remove("pp_$connectionId")
                .apply()
        }
    }
}
