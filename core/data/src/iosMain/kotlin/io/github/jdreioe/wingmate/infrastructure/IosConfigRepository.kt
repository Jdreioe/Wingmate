package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.GoogleSpeechConfig
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/** Stores Azure BYOK configuration in a this-device-only Keychain item. */
@OptIn(ExperimentalForeignApi::class)
class IosConfigRepository : ConfigRepository {
    private val defaults by lazy { NSUserDefaults.standardUserDefaults() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getSpeechConfig(): SpeechServiceConfig? = withContext(Dispatchers.Default) {
        readKeychain()?.normalizedIfValid()?.also {
            // Also completes cleanup after a process died between secure write and legacy deletion.
            defaults.removeObjectForKey(LEGACY_KEY)
            defaults.synchronize()
            return@withContext it
        }
        val legacyText = defaults.stringForKey(LEGACY_KEY) ?: return@withContext null
        val legacy = runCatching {
            json.decodeFromString(SpeechServiceConfig.serializer(), legacyText)
        }.getOrElse {
            OperationalLogger.warn("speech_config.legacy_decode", "failed")
            return@withContext null
        }
        val normalizedLegacy = legacy.normalizedIfValid()
        migrateLegacySpeechConfig(normalizedLegacy, ::writeKeychain, ::readKeychain) {
            defaults.removeObjectForKey(LEGACY_KEY)
            defaults.synchronize()
        }
        OperationalLogger.info("speech_config.migrate", "succeeded")
        normalizedLegacy
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.Default) {
        val normalized = config.validatedForStorage()
        writeKeychain(normalized)
        check(readKeychain() == normalized) { "Secure Azure credential write could not be verified" }
        defaults.removeObjectForKey(LEGACY_KEY)
        defaults.synchronize()
        OperationalLogger.info("speech_config.save", "succeeded", enabled = true)
    }

    override suspend fun clearSpeechConfig() = withContext(Dispatchers.Default) {
        deleteKeychain()
        defaults.removeObjectForKey(LEGACY_KEY)
        defaults.synchronize()
        OperationalLogger.info("speech_config.clear", "succeeded", enabled = false)
    }

    override suspend fun getGoogleSpeechConfig(): GoogleSpeechConfig? = withContext(Dispatchers.Default) {
        readGoogleKeychain()
    }

    override suspend fun saveGoogleSpeechConfig(config: GoogleSpeechConfig) = withContext(Dispatchers.Default) {
        val normalized = config.copy(apiKey = config.apiKey.trim())
        require(normalized.apiKey.isNotEmpty()) { "Google Cloud API key is required" }
        writeGoogleKeychain(normalized)
        check(readGoogleKeychain() == normalized) { "Secure Google credential write could not be verified" }
        OperationalLogger.info("google_speech_config.save", "succeeded", enabled = true)
    }

    override suspend fun clearGoogleSpeechConfig() = withContext(Dispatchers.Default) {
        deleteGoogleKeychain()
        OperationalLogger.info("google_speech_config.clear", "succeeded", enabled = false)
    }

    private fun readKeychain(): SpeechServiceConfig? = memScoped {
        val query = baseQuery()
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Could not read Azure configuration from Keychain ($status)" }
        val value = result.value ?: return@memScoped null
        val data = value.reinterpret<cnames.structs.__CFData>()
        val length = CFDataGetLength(data).toInt()
        val bytes = ByteArray(length)
        if (length > 0) bytes.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(data), length.convert()) }
        CFRelease(value)
        json.decodeFromString(SpeechServiceConfig.serializer(), bytes.decodeToString())
    }

    private fun writeKeychain(config: SpeechServiceConfig) {
        deleteKeychain()
        val query = baseQuery()
        val bytes = json.encodeToString(SpeechServiceConfig.serializer(), config).encodeToByteArray()
        val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }
        CFDictionarySetValue(query, kSecValueData, data)
        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFRelease(data)
        CFRelease(query)
        check(status == errSecSuccess) { "Could not store Azure configuration in Keychain ($status)" }
    }

    private fun deleteKeychain() {
        val query = baseQuery()
        val status = SecItemDelete(query)
        CFRelease(query)
        check(status == errSecSuccess || status == errSecItemNotFound) { "Could not clear Azure Keychain item ($status)" }
    }

    private fun readGoogleKeychain(): GoogleSpeechConfig? = memScoped {
        val query = baseQuery(GOOGLE_SERVICE, ACCOUNT)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Could not read Google configuration from Keychain ($status)" }
        val value = result.value ?: return@memScoped null
        val data = value.reinterpret<cnames.structs.__CFData>()
        val length = CFDataGetLength(data).toInt()
        val bytes = ByteArray(length)
        if (length > 0) bytes.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(data), length.convert()) }
        CFRelease(value)
        json.decodeFromString(GoogleSpeechConfig.serializer(), bytes.decodeToString())
    }

    private fun writeGoogleKeychain(config: GoogleSpeechConfig) {
        deleteGoogleKeychain()
        val query = baseQuery(GOOGLE_SERVICE, ACCOUNT)
        val bytes = json.encodeToString(GoogleSpeechConfig.serializer(), config).encodeToByteArray()
        val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }
        CFDictionarySetValue(query, kSecValueData, data)
        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFRelease(data)
        CFRelease(query)
        check(status == errSecSuccess) { "Could not store Google configuration in Keychain ($status)" }
    }

    private fun deleteGoogleKeychain() {
        val query = baseQuery(GOOGLE_SERVICE, ACCOUNT)
        val status = SecItemDelete(query)
        CFRelease(query)
        check(status == errSecSuccess || status == errSecItemNotFound) { "Could not clear Google Keychain item ($status)" }
    }

    private fun baseQuery(service: String = SERVICE, account: String = ACCOUNT) =
        CFDictionaryCreateMutable(null, 0, null, null)!!.also { query ->
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, cfString(service))
        CFDictionarySetValue(query, kSecAttrAccount, cfString(account))
    }

    private fun cfString(value: String) = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)!!

    private companion object {
        const val LEGACY_KEY = "speech_config"
        const val SERVICE = "io.github.jdreioe.wingmate.azure-speech"
        const val GOOGLE_SERVICE = "io.github.jdreioe.wingmate.google-speech"
        const val ACCOUNT = "default"
    }
}
