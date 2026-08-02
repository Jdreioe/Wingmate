package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.application.SecureEditingCredentialStorage
import java.security.SecureRandom

class DesktopSecureEditingCredentialStorage : SecureEditingCredentialStorage {
    private val backend: Backend? = detectBackend()
    override val isSupported: Boolean get() = backend != null

    override suspend fun read(): String? = when (backend) {
        Backend.SecretTool -> readOrNull(
            listOf("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT),
            missingWhen = { it.isBlank() }
        )
        Backend.MacKeychain -> readOrNull(
            listOf("security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w"),
            missingWhen = { it.contains("could not be found", ignoreCase = true) }
        )
        null -> null
    }

    override suspend fun write(value: String) {
        when (backend) {
            Backend.SecretTool -> run(
                listOf("secret-tool", "store", "--label=Wingmate editing access", "service", SERVICE, "account", ACCOUNT),
                value
            )
            Backend.MacKeychain -> run(
                listOf("security", "add-generic-password", "-U", "-s", SERVICE, "-a", ACCOUNT, "-w", value)
            )
            null -> error("The operating-system credential store is unavailable")
        }
    }

    override suspend fun delete() {
        when (backend) {
            Backend.SecretTool -> run(listOf("secret-tool", "clear", "service", SERVICE, "account", ACCOUNT))
            Backend.MacKeychain -> run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", ACCOUNT))
            null -> Unit
        }
    }

    override fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun detectBackend(): Backend? {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") && commandExists("security") -> Backend.MacKeychain
            commandExists("secret-tool") -> Backend.SecretTool
            else -> null
        }
    }

    private fun commandExists(command: String): Boolean = runCatching {
        ProcessBuilder(command, "--help").redirectErrorStream(true).start().apply {
            inputStream.readBytes()
            waitFor()
        }
        true
    }.getOrDefault(false)

    private fun run(command: List<String>, input: String? = null): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (input != null) process.outputStream.bufferedWriter().use { it.write(input) }
        else process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { output.ifBlank { "Credential-store command failed" } }
        return output
    }

    private fun readOrNull(command: List<String>, missingWhen: (String) -> Boolean): String? {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode == 0) return output.takeIf { it.isNotBlank() }
        if (missingWhen(output)) return null
        error(output.ifBlank { "Credential-store command failed" })
    }

    private enum class Backend { SecretTool, MacKeychain }

    private companion object {
        const val SERVICE = "io.github.jdreioe.wingmate.editing-access"
        const val ACCOUNT = "default"
    }
}
