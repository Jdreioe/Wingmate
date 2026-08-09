package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
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
        readKeychain()?.also {
            // Also completes cleanup after a process died between secure write and legacy deletion.
            defaults.removeObjectForKey(LEGACY_KEY)
            defaults.synchronize()
            return@withContext it
        }
        val legacyText = defaults.stringForKey(LEGACY_KEY) ?: return@withContext null
        val legacy = runCatching {
            json.decodeFromString(SpeechServiceConfig.serializer(), legacyText)
        }.getOrElse {
            println("Could not decode legacy Azure configuration; credential contents redacted")
            return@withContext null
        }
        migrateLegacySpeechConfig(legacy, ::writeKeychain, ::readKeychain) {
            defaults.removeObjectForKey(LEGACY_KEY)
            defaults.synchronize()
        }
        println("Migrated Azure speech configuration to iOS Keychain")
        legacy
    }

    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) = withContext(Dispatchers.Default) {
        require(config.subscriptionKey.isNotBlank()) { "Azure subscription key must not be blank" }
        writeKeychain(config)
        check(readKeychain() == config) { "Secure Azure credential write could not be verified" }
        defaults.removeObjectForKey(LEGACY_KEY)
        defaults.synchronize()
        println("Saved Azure speech configuration securely; credentialConfigured=true")
    }

    override suspend fun clearSpeechConfig() = withContext(Dispatchers.Default) {
        deleteKeychain()
        defaults.removeObjectForKey(LEGACY_KEY)
        defaults.synchronize()
        println("Cleared Azure speech configuration; credentialConfigured=false")
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

    private fun baseQuery() = CFDictionaryCreateMutable(null, 0, null, null)!!.also { query ->
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, cfString(SERVICE))
        CFDictionarySetValue(query, kSecAttrAccount, cfString(ACCOUNT))
    }

    private fun cfString(value: String) = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)!!

    private companion object {
        const val LEGACY_KEY = "speech_config"
        const val SERVICE = "io.github.jdreioe.wingmate.azure-speech"
        const val ACCOUNT = "default"
    }
}
