package io.github.ferryapp.ferry.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
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

    fun putPassword(connectionId: String, password: String) {
        prefs.edit().putString("pw_$connectionId", password).apply()
    }

    fun getPassword(connectionId: String): String? = prefs.getString("pw_$connectionId", null)

    fun putPassphrase(connectionId: String, passphrase: String) {
        prefs.edit().putString("pp_$connectionId", passphrase).apply()
    }

    fun getPassphrase(connectionId: String): String? = prefs.getString("pp_$connectionId", null)

    fun clear(connectionId: String) {
        prefs.edit()
            .remove("pw_$connectionId")
            .remove("pp_$connectionId")
            .apply()
    }
}
