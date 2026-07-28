package com.melone.gallery.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Speichert das Samba-Passwort verschlüsselt (Jetpack Security).
 * Datei: secure_credentials.xml (vom Backup ausgenommen).
 */
class SecureCredentials(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

    fun clear() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val KEY_PASSWORD = "smb_password"
    }
}
