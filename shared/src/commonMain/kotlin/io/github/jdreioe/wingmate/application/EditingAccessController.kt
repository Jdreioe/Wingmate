package io.github.jdreioe.wingmate.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock

@Serializable
data class EditingAccessCredential(
    val version: Int = 1,
    val saltHex: String,
    val verifierHex: String,
    val iterations: Int
)

interface SecureEditingCredentialStorage {
    val isSupported: Boolean
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun delete()
    fun secureRandomBytes(size: Int): ByteArray
}

interface EditingAccessStore {
    val isSupported: Boolean
    suspend fun isEnabled(): Boolean
    suspend fun setCode(code: String)
    suspend fun verify(code: String): Boolean
    suspend fun clear()
}

class DefaultEditingAccessStore(
    private val storage: SecureEditingCredentialStorage,
    private val iterations: Int = 120_000
) : EditingAccessStore {
    private val json = Json { ignoreUnknownKeys = true }
    override val isSupported: Boolean get() = storage.isSupported

    override suspend fun isEnabled(): Boolean = storage.read() != null

    override suspend fun setCode(code: String) {
        require(code.length in 4..8 && code.all(Char::isDigit)) { "Access code must contain 4 to 8 digits" }
        check(isSupported) { "Secure credential storage is unavailable" }
        val salt = storage.secureRandomBytes(16)
        val verifier = withContext(Dispatchers.Default) {
            pbkdf2HmacSha256(code.encodeToByteArray(), salt, iterations, 32)
        }
        storage.write(
            json.encodeToString(
                EditingAccessCredential(
                    saltHex = salt.toHex(),
                    verifierHex = verifier.toHex(),
                    iterations = iterations
                )
            )
        )
    }

    override suspend fun verify(code: String): Boolean {
        val encoded = storage.read() ?: return true
        val credential = runCatching { json.decodeFromString<EditingAccessCredential>(encoded) }.getOrNull()
            ?: return false
        if (credential.version != 1 || credential.iterations !in 1..1_000_000) return false
        val expected = credential.verifierHex.hexToBytes() ?: return false
        val salt = credential.saltHex.hexToBytes() ?: return false
        val actual = withContext(Dispatchers.Default) {
            pbkdf2HmacSha256(code.encodeToByteArray(), salt, credential.iterations, expected.size)
        }
        return constantTimeEquals(expected, actual)
    }

    override suspend fun clear() = storage.delete()
}

data class EditingAccessState(
    val enabled: Boolean = false,
    val unlocked: Boolean = true,
    val supported: Boolean = true,
    val failedAttempts: Int = 0
)

class EditingAccessController(
    private val store: EditingAccessStore,
    private val timeoutMillis: Long = 10 * 60 * 1000L,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val mutableState = MutableStateFlow(EditingAccessState(supported = store.isSupported))
    val state: StateFlow<EditingAccessState> = mutableState.asStateFlow()
    private var unlockedAt: Long? = null

    suspend fun refresh(): EditingAccessState {
        val enabled = store.isEnabled()
        val unlocked = !enabled || sessionIsValid()
        return EditingAccessState(enabled, unlocked, store.isSupported, mutableState.value.failedAttempts)
            .also { mutableState.value = it }
    }

    suspend fun requiresUnlock(): Boolean = refresh().let { it.enabled && !it.unlocked }

    suspend fun unlock(code: String): Boolean {
        val success = store.verify(code)
        if (success) {
            unlockedAt = nowMillis()
            mutableState.value = refresh().copy(failedAttempts = 0)
        } else {
            mutableState.value = refresh().copy(failedAttempts = mutableState.value.failedAttempts + 1)
        }
        return success
    }

    suspend fun configure(code: String) {
        check(!store.isEnabled() || sessionIsValid()) { "Editing must be unlocked before changing the code" }
        store.setCode(code)
        unlockedAt = nowMillis()
        mutableState.value = EditingAccessState(enabled = true, unlocked = true, supported = store.isSupported)
    }

    suspend fun disable(code: String): Boolean {
        if (!store.verify(code)) return false
        store.clear()
        unlockedAt = null
        mutableState.value = EditingAccessState(enabled = false, unlocked = true, supported = store.isSupported)
        return true
    }

    fun lock() {
        unlockedAt = null
        mutableState.value = mutableState.value.copy(unlocked = !mutableState.value.enabled)
    }

    suspend fun recover() {
        store.clear()
        unlockedAt = null
        mutableState.value = EditingAccessState(enabled = false, unlocked = true, supported = store.isSupported)
    }

    private fun sessionIsValid(): Boolean = unlockedAt?.let { nowMillis() - it < timeoutMillis } == true
}

private fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    length: Int
): ByteArray {
    val result = ByteArray(length)
    val key = password.toByteString()
    var outputOffset = 0
    var blockIndex = 1
    while (outputOffset < length) {
        val counter = byteArrayOf(
            (blockIndex ushr 24).toByte(),
            (blockIndex ushr 16).toByte(),
            (blockIndex ushr 8).toByte(),
            blockIndex.toByte()
        )
        var u = (salt + counter).toByteString().hmacSha256(key).toByteArray()
        val block = u.copyOf()
        repeat(iterations - 1) {
            u = u.toByteString().hmacSha256(key).toByteArray()
            for (index in block.indices) block[index] = (block[index].toInt() xor u[index].toInt()).toByte()
        }
        val count = minOf(block.size, length - outputOffset)
        block.copyInto(result, outputOffset, 0, count)
        outputOffset += count
        blockIndex++
    }
    return result
}

private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var difference = 0
    first.indices.forEach { difference = difference or (first[it].toInt() xor second[it].toInt()) }
    return difference == 0
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

class InMemorySecureEditingCredentialStorage : SecureEditingCredentialStorage {
    private var value: String? = null
    override val isSupported: Boolean = true
    override suspend fun read(): String? = value
    override suspend fun write(value: String) { this.value = value }
    override suspend fun delete() { value = null }
    override fun secureRandomBytes(size: Int): ByteArray = ByteArray(size) { (it * 31 + 17).toByte() }
}
