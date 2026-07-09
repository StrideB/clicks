package com.fran.teclas.predict

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the prediction engine. Two layers:
 *  - [prefs]: EncryptedSharedPreferences holding model weights, frequency priors and places.
 *  - [encrypt]/[decrypt]: Keystore-backed AES/GCM for transition-log rows stored in Room,
 *    so the launch history is unreadable without the device keystore (SQLCipher-equivalent
 *    protection without a native dependency).
 *
 * Every entry point degrades gracefully: if the keystore or EncryptedSharedPreferences is
 * unavailable (rare OEM breakage), we fall back to plain storage rather than crash the
 * launcher — prediction is a convenience layer, never a point of failure.
 */
object PredictCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ROW_KEY_ALIAS = "teclas_predict_rows"
    private const val ENC_PREFS_NAME = "teclas_predict_secure"

    @Volatile private var cachedPrefs: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val created = runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    ENC_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull()
                ?: context.applicationContext.getSharedPreferences("${ENC_PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            cachedPrefs = created
            return created
        }
    }

    private fun rowKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ROW_KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ROW_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        }
    }.getOrNull()

    /** AES/GCM encrypt; returns base64("iv:cipher") or the plaintext prefixed marker on failure. */
    fun encrypt(plain: String): String {
        val key = rowKey() ?: return "p:$plain"
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val body = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            "e:$iv:$body"
        }.getOrDefault("p:$plain")
    }

    fun decrypt(stored: String): String? {
        if (stored.startsWith("p:")) return stored.substring(2)
        if (!stored.startsWith("e:")) return null
        val key = rowKey() ?: return null
        return runCatching {
            val parts = stored.split(":")
            if (parts.size != 3) return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val body = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }
}
