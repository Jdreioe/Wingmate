package io.github.jdreioe.wingmate

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsalConfigTest {

    private val configFile: File
        get() {
            val candidate = File("src/main/res/raw/msal_config.json")
            if (candidate.exists()) return candidate
            val androidAppCandidate = File("androidApp/src/main/res/raw/msal_config.json")
            if (androidAppCandidate.exists()) return androidAppCandidate
            error("msal_config.json not found in expected test locations")
        }

    @Test
    fun testMsalConfigFileExists() {
        assertTrue("msal_config.json must exist in src/main/res/raw", configFile.exists())
    }

    @Test
    fun testMsalConfigJsonValid() {
        val jsonText = configFile.readText()
        val root = Json.parseToJsonElement(jsonText).jsonObject

        val clientId = root["client_id"]?.jsonPrimitive?.content
        assertNotNull("client_id must be present", clientId)
        assertTrue("client_id must not be empty", !clientId.isNullOrBlank())

        val accountMode = root["account_mode"]?.jsonPrimitive?.content
        assertEquals(
            "Account mode must be SINGLE for SingleAccountPublicClientApplication",
            "SINGLE",
            accountMode
        )
    }

    @Test
    fun testMsalConfigRedirectUriScheme() {
        val jsonText = configFile.readText()
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val redirectUri = root["redirect_uri"]?.jsonPrimitive?.content

        assertNotNull("redirect_uri must be present", redirectUri)
        assertTrue(
            "redirect_uri must start with msauth://com.hojmoseit.wingmate/",
            redirectUri?.startsWith("msauth://com.hojmoseit.wingmate/") == true
        )
    }

    @Test
    fun testMsalConfigAuthoritiesConfigured() {
        val jsonText = configFile.readText()
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val authorities = root["authorities"]?.jsonArray

        assertNotNull("authorities must be defined", authorities)
        assertTrue("authorities list must not be empty", (authorities?.size ?: 0) > 0)

        val defaultAuth = authorities!![0].jsonObject
        assertEquals("AAD", defaultAuth["type"]?.jsonPrimitive?.content)

        val audience = defaultAuth["audience"]?.jsonObject
        assertNotNull("audience object must be defined", audience)
        assertEquals(
            "F0 setup must accept both work/school and personal Microsoft accounts",
            "AzureADandPersonalMicrosoftAccount",
            audience?.get("type")?.jsonPrimitive?.content
        )

        // `common` is the shared endpoint for both account families. MSAL also permits
        // this to be omitted for this audience type, so only reject a different value.
        val tenantId = audience?.get("tenant_id")?.jsonPrimitive?.content
        assertTrue(
            "Personal-account sign-in must use the common authority when a tenant is specified",
            tenantId == null || tenantId == "common"
        )
    }
}
