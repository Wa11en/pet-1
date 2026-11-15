package ru.privatediary.security.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only authenticated ciphertext is persisted. Existing damaged records are never silently replaced. */
class KeystoreVault(context: Context) {
    private val preferences = context.getSharedPreferences("sealed_secrets", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.vault.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized private fun wrappingKey(): SecretKey {
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        // A missing alias with persisted ciphertext indicates key loss; do not reset the diary.
        check(preferences.all.isEmpty()) { "Encryption key is unavailable; restore a password-protected backup" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized fun storeSecret(name: String, secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val sealed = cipher.iv + cipher.doFinal(secret)
        check(preferences.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) { "Cannot persist encryption material" }
    }

    @Synchronized fun loadSecret(name: String): ByteArray? {
        val encoded = preferences.getString(name, null) ?: return null
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        require(sealed.size >= 28) { "Damaged encrypted key record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    fun contains(name: String): Boolean = preferences.contains(name)

    /** Caller must run on Dispatchers.IO and erase its returned copy after opening SQLCipher. */
    @Synchronized fun databasePassphrase(): ByteArray {
        loadSecret("database_key")?.let { require(it.size == 32); return it }
        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        storeSecret("database_key", generated)
        return generated
    }
}
