package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureEditingCredentialStorage(
    private val context: Context
) : SecureEditingCredentialStorage {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    override val isSupported: Boolean = true

    override suspend fun read(): String? {
        val encoded = preferences.getString(CREDENTIAL, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.isNotEmpty()) { "Stored editing credential is empty" }
            val ivLength = payload.first().toInt() and 0xff
            require(ivLength > 0 && payload.size > 1 + ivLength) { "Stored editing credential is malformed" }
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).decodeToString()
        }.getOrElse { error ->
            throw IllegalStateException("Could not read the protected editing credential", error)
        }
    }

    override suspend fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        check(preferences.edit().putString(CREDENTIAL, Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
    }

    override suspend fun delete() {
        preferences.edit().remove(CREDENTIAL).commit()
    }

    override fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "editing_access_secure"
        const val CREDENTIAL = "credential"
        const val KEY_ALIAS = "wingmate.editing-access.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
