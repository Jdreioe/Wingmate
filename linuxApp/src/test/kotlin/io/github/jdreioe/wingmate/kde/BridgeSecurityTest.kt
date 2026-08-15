package io.github.jdreioe.wingmate.kde

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BridgeSecurityTest {
    @Test
    fun `loopback and private-network image targets are rejected`() {
        assertFalse(isAllowedRemoteTarget(URI("http://127.0.0.1/symbol.png")))
        assertFalse(isAllowedRemoteTarget(URI("http://localhost/symbol.png")))
        assertFalse(isAllowedRemoteTarget(URI("http://10.0.0.40/symbol.png")))
        assertFalse(isAllowedRemoteTarget(URI("http://172.16.5.5/symbol.png")))
        assertFalse(isAllowedRemoteTarget(URI("http://192.168.1.40/symbol.png")))
        assertFalse(isAllowedRemoteTarget(URI("http://169.254.169.254/metadata")))
        assertFalse(isAllowedRemoteTarget(URI("http://[::1]/symbol.png")))
    }

    @Test
    fun `public image targets are accepted`() {
        assertTrue(isAllowedRemoteTarget(URI("https://1.1.1.1/symbol.png")))
        assertTrue(isAllowedRemoteTarget(URI("https://8.8.8.8/img/a.png?auth=1")))
        assertTrue(isAllowedRemoteTarget(URI("http://93.184.216.34/x.png")))
        assertFalse(isAllowedRemoteTarget(URI("http://203.0.113.5/x.png")))
    }

    @Test
    fun `non-http schemes and bad literal shapes are rejected`() {
        assertFalse(isAllowedRemoteTarget(URI("file:///etc/passwd")))
        assertFalse(isAllowedRemoteTarget(URI("ftp://example.org/x.png")))
        assertFalse(isAllowedRemoteTarget(URI("http:///x.png")))
        assertFalse(isAllowedRemoteTarget(URI("https:///x.png")))
        assertFalse(isAllowedRemoteTarget(URI("javascript:alert(1)")))
    }

    @Test
    fun `redirect targets are re-validated against the same rules`() {
        val base = URI("https://8.8.8.8/a.png")
        assertNotNull(validatedRedirectUri(base, "/b.png"))
        assertNotNull(validatedRedirectUri(base, "https://1.1.1.1/b.gif"))
        assertEquals(null, validatedRedirectUri(base, "http://127.0.0.1/steal"))
        assertEquals(null, validatedRedirectUri(base, "https://192.168.0.4/steal"))
    }

    @Test
    fun `connection uses validated DNS snapshot even when the hostname rebinds`() {
        val public = InetAddress.getByName("93.184.216.34")
        val loopback = InetAddress.getByName("127.0.0.1")
        var resolutions = 0
        val resolver = ImageHostResolver {
            resolutions += 1
            if (resolutions == 1) listOf(public) else listOf(loopback)
        }
        val transport = RemoteImageTransport { target ->
            // Simulate the answer changing at connect time. The transport is
            // still handed only the public address snapshot that was vetted.
            assertEquals(loopback, resolver.resolve(target.uri.host).single())
            assertEquals(listOf(public), target.addresses)
            RemoteImageResponse(
                status = 200,
                location = null,
                contentType = "image/png",
                body = ByteArrayInputStream(
                    byteArrayOf(
                        0x89.toByte(),
                        'P'.code.toByte(),
                        'N'.code.toByte(),
                        'G'.code.toByte(),
                    ),
                ),
            )
        }

        val result = fetchRemoteImageBytes("https://images.example/image.png", resolver, transport)

        assertEquals("image/png", result.second)
        assertEquals(2, resolutions)
    }

    @Test
    fun `pinned DNS cannot resolve a different hostname`() {
        val public = InetAddress.getByName("93.184.216.34")
        val pinned = PinnedImageDns("images.example", listOf(public))

        assertEquals(listOf(public), pinned.lookup("images.example"))
        assertFailsWith<java.net.UnknownHostException> { pinned.lookup("rebound.example") }
    }

    @Test
    fun `redirect is rejected before connecting when its DNS answer is private`() {
        val public = InetAddress.getByName("93.184.216.34")
        val loopback = InetAddress.getByName("127.0.0.1")
        var resolutions = 0
        var connections = 0
        val resolver = ImageHostResolver {
            resolutions += 1
            if (resolutions == 1) listOf(public) else listOf(loopback)
        }
        val transport = RemoteImageTransport {
            connections += 1
            RemoteImageResponse(
                status = 302,
                location = "https://rebound.example/private.png",
                contentType = null,
                body = ByteArrayInputStream(byteArrayOf()),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            fetchRemoteImageBytes("https://images.example/image.png", resolver, transport)
        }
        assertEquals(2, resolutions)
        assertEquals(1, connections)
    }

    @Test
    fun `html and empty bodies fail content validation`() {
        assertFailsWith<IllegalArgumentException> {
            validateImageContent("<html></html>".encodeToByteArray(), "text/html")
        }
    }

    @Test
    fun `png bytes pass content validation`() {
        assertTrue(sniffsSupportedImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A)))
        validateImageContent(
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A),
            "",
        )
        assertEquals("image/png", contentTypeForBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test
    fun `unexpected local files are not trusted and selected paths are validated`() {
        val temp = Files.createTempDirectory("wingmate-security")
        val inside = temp.resolve("sub").toAbsolutePath().let { Files.createDirectories(it).resolve("x.png") }
        Files.write(inside, byteArrayOf(1, 2, 3, 4))

        // Only files under the app's own data dirs may be served as local images.
        assertEquals(null, trustedLocalImageFile(inside.toString()))
        assertEquals(null, trustedLocalImageFile("file:///etc/passwd"))

        // requireSelectedPath enforces absolute, existing, regular files.
        assertFailsWith<IllegalArgumentException> { requireSelectedPath("relative/x.png") }
        assertFailsWith<IllegalArgumentException> { requireSelectedPath("/definitely/missing/file.png") }
        assertEquals(inside.toFile(), requireSelectedPath(inside.toAbsolutePath().toString()))
    }

    @Test
    fun `per-process token is random and sufficiently long`() {
        val first = secureRandomToken()
        val second = secureRandomToken()
        assertEquals(64, first.length)
        assertTrue(first != second)
    }

    @Test
    fun `bridge tokens use timing-safe comparison`() {
        val token = "a".repeat(64)
        assertTrue(bridgeTokensEqual(token, token))
        assertFalse(bridgeTokensEqual(token, "b".repeat(64)))
    }

    @Test
    fun `bridge token file is owner-only and unsafe permissions are rejected`() {
        val root = Files.createTempDirectory("wingmate-token-security")
        try {
            val file = root.resolve("wingmate/bridge-token").toFile()
            val token = "a".repeat(64)
            writeBridgeTokenFile(file, token)

            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(file.parentFile.toPath()),
            )
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file.toPath()),
            )
            assertEquals(token, readSecureBridgeTokenFile(file))

            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
            assertFailsWith<IllegalArgumentException> { readSecureBridgeTokenFile(file) }

            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            Files.setPosixFilePermissions(file.parentFile.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            assertFailsWith<IllegalArgumentException> { readSecureBridgeTokenFile(file) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
