package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores Azure BYOK configuration as an Android Keystore-backed AES-GCM blob. */
class AndroidSqlConfigRepository(private val context: Context) : ConfigRepository {
    private val helper by lazy { AndroidSqlOpenHelper(context) }
    private val prefs by lazy { context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE) }
    private val legacyPrefs by lazy { context.getSharedPreferences("wingmate_prefs", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.IO) {
        readSecure()?.normalizedIfValid()?.also {
            // Also completes cleanup after a process died between secure write and legacy deletion.
            deleteLegacyPlaintext()
            return@withContext it
        }
        migrateLegacy()
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.IO) {
        val normalized = config.validatedForStorage()
        writeSecure(normalized)
        check(readSecure() == normalized) { "Secure Azure credential write could not be verified" }
        deleteLegacyPlaintext()
        OperationalLogger.info("speech_config.save", "succeeded", enabled = true)
    }

    override suspend fun clearSpeechConfig() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(ENCRYPTED_CONFIG).commit()) { "Could not clear secure Azure configuration" }
        deleteLegacyPlaintext()
        OperationalLogger.info("speech_config.clear", "succeeded", enabled = false)
    }

    private fun migrateLegacy(): SpeechServiceConfig? {
        val legacy = (readLegacySqlite() ?: readLegacyPreferences() ?: return null).normalizedIfValid()
        migrateLegacySpeechConfig(legacy, ::writeSecure, ::readSecure, ::deleteLegacyPlaintext)
        OperationalLogger.info("speech_config.migrate", "succeeded")
        return legacy
    }

    private fun readLegacySqlite(): SpeechServiceConfig? {
        val cursor = helper.readableDatabase.query(
            "configs", arrayOf("json"), "id = ?", arrayOf(LEGACY_ID), null, null, null
        )
        return cursor.use {
            if (!it.moveToFirst()) null else decodeLegacy(it.getString(0))
        }
    }

    private fun readLegacyPreferences(): SpeechServiceConfig? =
        legacyPrefs.getString(LEGACY_ID, null)?.let(::decodeLegacy)

    private fun decodeLegacy(value: String): SpeechServiceConfig? = runCatching {
        json.decodeFromString(SpeechServiceConfig.serializer(), value)
    }.onFailure {
        OperationalLogger.warn("speech_config.legacy_decode", "failed")
    }.getOrNull()

    private fun deleteLegacyPlaintext() {
        helper.writableDatabase.delete("configs", "id = ?", arrayOf(LEGACY_ID))
        check(legacyPrefs.edit().remove(LEGACY_ID).commit()) { "Could not remove legacy Azure configuration" }
    }

    private fun writeSecure(config: SpeechServiceConfig) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(json.encodeToString(SpeechServiceConfig.serializer(), config).encodeToByteArray())
        val value = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        check(prefs.edit().putString(ENCRYPTED_CONFIG, value).commit()) { "Could not persist secure Azure configuration" }
    }

    private fun readSecure(): SpeechServiceConfig? {
        val encoded = prefs.getString(ENCRYPTED_CONFIG, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE)))
            val plaintext = cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()
            json.decodeFromString(SpeechServiceConfig.serializer(), plaintext)
        }.onFailure {
            OperationalLogger.warn("speech_config.secure_read", "failed")
        }.getOrThrow()
    }

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
        const val SECURE_PREFS = "wingmate_secure_azure"
        const val ENCRYPTED_CONFIG = "encrypted_speech_config"
        const val LEGACY_ID = "speech_config"
        const val KEY_ALIAS = "wingmate.azure-speech.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
