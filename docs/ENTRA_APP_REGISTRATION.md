# Microsoft Entra app registration for F0 setup

The Android MSAL configuration targets both organizational and personal Microsoft
accounts. The matching Entra application registration must use the same audience;
the client cannot broaden an app registration that is restricted server-side.

For the client ID stored in `WINGMATE_ENTRA_CLIENT_ID`:

1. Open **Microsoft Entra ID** → **App registrations** → the Wingmate app.
2. Open **Authentication** (or **Manifest**).
3. Set **Supported account types** to **Accounts in any organizational directory
   and personal Microsoft accounts**.
4. In the manifest, confirm this is saved as:

   ```json
   "signInAudience": "AzureADandPersonalMicrosoftAccount"
   ```

5. Keep the Android `msauth://com.hojmoseit.wingmate/<signature-hash>` redirect
   URI registered for each signing certificate used to distribute the app.

MSAL uses the `common` authority and the
`AzureADandPersonalMicrosoftAccount` audience in `msal_config.json`. Changing
only the Android configuration is insufficient if the Entra registration remains
`AzureADMyOrg`; that is what causes the work-or-school-account-only error.
