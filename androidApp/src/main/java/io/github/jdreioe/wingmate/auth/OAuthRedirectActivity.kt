package io.github.jdreioe.wingmate.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.jdreioe.wingmate.MainActivity
import io.github.jdreioe.wingmate.infrastructure.AndroidCustomTabsOAuthBrowser

/**
 * Receives the `wingmate-auth://oauth` sign-in redirect (OneDrive), hands it to the waiting
 * sign-in, and returns to the existing [MainActivity], closing the Custom Tab above it.
 * It shows nothing and finishes at once.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let(AndroidCustomTabsOAuthBrowser::deliverRedirect)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
