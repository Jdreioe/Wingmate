package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
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
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
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
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
class IosSecureEditingCredentialStorage : SecureEditingCredentialStorage {
    override val isSupported: Boolean = true

    override suspend fun read(): String? = memScoped {
        val query = baseQuery()
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Could not read editing credential from Keychain ($status)" }
        val value = result.value ?: return@memScoped null
        val data = value.reinterpret<cnames.structs.__CFData>()
        val length = CFDataGetLength(data).toInt()
        val bytes = ByteArray(length)
        if (length > 0) bytes.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(data), length.convert()) }
        CFRelease(value)
        bytes.decodeToString()
    }

    override suspend fun write(value: String) {
        delete()
        val query = baseQuery()
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }
        CFDictionarySetValue(query, kSecValueData, data)
        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFRelease(data)
        CFRelease(query)
        check(status == errSecSuccess) { "Could not store editing credential in Keychain ($status)" }
    }

    override suspend fun delete() {
        val query = baseQuery()
        val status = SecItemDelete(query)
        CFRelease(query)
        check(status == errSecSuccess || status == errSecItemNotFound)
    }

    override fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
        bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) == errSecSuccess) }
    }

    private fun baseQuery() = CFDictionaryCreateMutable(null, 0, null, null)!!.also { query ->
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, cfString(SERVICE))
        CFDictionarySetValue(query, kSecAttrAccount, cfString(ACCOUNT))
    }

    private fun cfString(value: String) = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)!!

    private companion object {
        const val SERVICE = "io.github.jdreioe.wingmate.editing-access"
        const val ACCOUNT = "default"
    }
}
