import io.github.jdreioe.wingmate.domain.AzureF0Provisioner
import io.github.jdreioe.wingmate.domain.AzureF0Resource
import io.github.jdreioe.wingmate.domain.AzureSignInResult
import io.github.jdreioe.wingmate.domain.AzureSubscription
import io.github.jdreioe.wingmate.infrastructure.InMemoryAzureF0Provisioner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AzureF0ProvisionerTest {

    private fun createProvisioner(): InMemoryAzureF0Provisioner = InMemoryAzureF0Provisioner()

    @Test
    fun initiallyNotSignedIn() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        assertFalse(p.isSignedIn())
        assertNull(p.getSignedInUser())
    }

    @Test
    fun signInReturnsSuccess() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        val result = p.signIn()
        assertEquals(AzureSignInResult.SUCCESS, result)
        assertTrue(p.isSignedIn())
    }

    @Test
    fun signedInUserHasInfo() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        p.signIn()
        val user = p.getSignedInUser()
        assertNotNull(user)
        assertEquals("Test User", user.displayName)
        assertEquals("test@example.com", user.username)
    }

    @Test
    fun signOutClearsState() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        p.signIn()
        assertTrue(p.isSignedIn())
        p.signOut()
        assertFalse(p.isSignedIn())
        assertNull(p.getSignedInUser())
    }

    @Test
    fun emptySubscriptionsWhenNotSignedIn() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        val subs = p.getSubscriptions()
        assertTrue(subs.isEmpty())
    }

    @Test
    fun returnsConfiguredSubscriptions() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        val testSubs = listOf(
            AzureSubscription("sub-1", "Subscription 1", "tenant-1"),
            AzureSubscription("sub-2", "Subscription 2", "tenant-1")
        )
        p.configureSubscriptions(testSubs)
        p.signIn()
        val subs = p.getSubscriptions()
        assertEquals(2, subs.size)
        assertEquals("sub-1", subs[0].id)
    }

    @Test
    fun returnsConfiguredF0Resources() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        val resources = listOf(
            AzureF0Resource("res-1", "my-speech", "northeurope", "my-rg")
        )
        p.configureF0Resources("sub-1", resources)
        p.signIn()
        val result = p.getF0Resources("sub-1")
        assertEquals(1, result.size)
        assertEquals("my-speech", result[0].name)
        assertEquals("northeurope", result[0].region)
    }

    @Test
    fun zeroSubscriptionsRepresented() = kotlinx.coroutines.runBlocking {
        val p = createProvisioner()
        p.configureSubscriptions(emptyList())
        p.signIn()
        val subs = p.getSubscriptions()
        assertTrue(subs.isEmpty())
    }
}
