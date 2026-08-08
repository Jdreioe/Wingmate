package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Stores only the salted editing-code verifier in the desktop keyring. */
class LinuxSecureEditingCredentialStorage : SecureEditingCredentialStorage {
    private enum class Backend { SecretService, KWallet }

    private val backend: Backend? by lazy {
        when {
            commandExists("secret-tool") -> Backend.SecretService
            commandExists("kwallet-query") -> Backend.KWallet
            else -> null
        }
    }

    override val isSupported: Boolean get() = backend != null

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        when (backend) {
            Backend.SecretService -> run(
                listOf("secret-tool", "lookup", "application", APP_ID, "purpose", PURPOSE)
            ).takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotEmpty)
            Backend.KWallet -> readKWallet()
            null -> null
        }
    }

    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        val result = when (backend) {
            Backend.SecretService -> run(
                listOf(
                    "secret-tool", "store", "--label=Wingmate editing access code",
                    "application", APP_ID, "purpose", PURPOSE
                ),
                value
            )
            Backend.KWallet -> run(
                listOf("kwallet-query", "-w", ENTRY, "-f", FOLDER, walletName()),
                value
            )
            null -> error("Secure credential storage is unavailable")
        }
        check(result.exitCode == 0) { result.output.ifBlank { "Could not save editing access code" } }
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        when (backend) {
            Backend.SecretService -> run(
                listOf("secret-tool", "clear", "application", APP_ID, "purpose", PURPOSE)
            )
            // kwallet-query has no delete operation; overwriting with an empty
            // value removes the verifier semantically and read() treats it as absent.
            Backend.KWallet -> run(
                listOf("kwallet-query", "-w", ENTRY, "-f", FOLDER, walletName()),
                ""
            )
            null -> return@withContext
        }
        Unit
    }

    override fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun readKWallet(): String? {
        val result = run(listOf("kwallet-query", "-r", ENTRY, "-f", FOLDER, walletName()))
        return result.takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun walletName(): String =
        System.getenv("WINGMATE_KWALLET")?.takeIf(String::isNotBlank) ?: "kdewallet"

    private data class Result(val exitCode: Int, val output: String)

    private fun run(command: List<String>, input: String? = null): Result = runCatching {
        val process = ProcessBuilder(command).start()
        if (input != null) process.outputStream.bufferedWriter().use { it.write(input) }
        else process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        Result(exitCode, if (exitCode == 0) output else error.ifBlank { output })
    }.getOrElse { Result(-1, it.message.orEmpty()) }

    private fun commandExists(command: String): Boolean =
        run(listOf("which", command)).exitCode == 0

    private companion object {
        const val APP_ID = "io.github.jdreioe.wingmate"
        const val PURPOSE = "editing-access"
        const val FOLDER = "Wingmate"
        const val ENTRY = "editing-access-verifier"
    }
}
