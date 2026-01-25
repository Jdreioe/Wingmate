# Azure Token Exchange Backend Setup

> **⚠️ IMPORTANT**: This backend is ONLY needed for a **premium subscription model** where YOU (the developer) provide Azure Speech keys to users.
> 
> **For free tier**: Users should bring their own Azure keys, stored securely on-device (Android Keystore, iOS Keychain, Desktop OS keyring). No backend needed.

This document describes how to set up a secure token exchange service for Wingmate's Azure TTS integration.

## Architecture Overview

Instead of storing Azure API keys on the client, we use a serverless function that:
1. Validates the user/device
2. Fetches the real Azure key from secure storage
3. Returns a short-lived token (10 minutes) to the client

## Option 1: Azure Functions (Recommended)

### Prerequisites
- Azure account (free tier available)
- Azure CLI installed

### Step 1: Create Azure Resources

> Troubleshooting `SubscriptionNotFound`:
> - `az account list --output table` # confirm available subscriptions
> - `az account set --subscription "Azure for Students"` # or use your subscription ID
> - If the subscription is not listed, re-login with the correct tenant: `az login --use-device-code --tenant 61fd1d36-fecb-47ca-b7d7-d0df0370a198`

```bash
# Login to Azure
az login

# Create resource group
az group create --name wingmate-rg --location eastus

# Create storage account (required for Functions)
az storage account create \
  --name wingmatestorage \
  --resource-group wingmate-rg \
  --location eastus \
  --sku Standard_LRS

# Create Function App (Consumption plan = free tier)
az functionapp create \
  --name wingmate-token-exchange \
  --resource-group wingmate-rg \
  --consumption-plan-location eastus \
  --runtime node \
  --runtime-version 20 \
  --functions-version 4 \
  --storage-account wingmatestorage

# Create Key Vault for secrets
az keyvault create \
  --name wingmate-kv \
  --resource-group wingmate-rg \
  --location northeurope

# Store your Azure Speech key in Key Vault
az keyvault secret set \
  --vault-name wingmate-kv \
  --name "azure-speech-key" \
  --value "YOUR_AZURE_SPEECH_KEY"

# Grant Function App access to Key Vault
az functionapp identity assign \
  --name wingmate-token-exchange \
  --resource-group wingmate-rg

# Get the identity principal ID
PRINCIPAL_ID=$(az functionapp identity show \
  --name wingmate-token-exchange \
  --resource-group wingmate-rg \
  --query principalId -o tsv)

# Grant secret read access
az keyvault set-policy \
  --name wingmate-kv \
  --object-id $PRINCIPAL_ID \
  --secret-permissions get
```

### Step 2: Create the Token Exchange Function

Create a new directory for the function:

```bash
mkdir -p azure-functions/GetSpeechToken
cd azure-functions
func init --javascript
```

**GetSpeechToken/index.js:**
```javascript
const { DefaultAzureCredential } = require("@azure/identity");
const { SecretClient } = require("@azure/keyvault-secrets");

// Cache the token to avoid hitting Key Vault on every request
let cachedToken = null;
let tokenExpiry = 0;

module.exports = async function (context, req) {
    // Simple API key validation (replace with proper auth in production)
    const clientApiKey = req.headers['x-api-key'];
    const expectedApiKey = process.env.CLIENT_API_KEY;
    
    if (!clientApiKey || clientApiKey !== expectedApiKey) {
        context.res = { status: 401, body: { error: "Unauthorized" } };
        return;
    }

    try {
        const now = Date.now();
        
        // Return cached token if still valid (with 1 minute buffer)
        if (cachedToken && tokenExpiry > now + 60000) {
            context.res = {
                status: 200,
                headers: { "Content-Type": "application/json" },
                body: { 
                    token: cachedToken, 
                    expiresIn: Math.floor((tokenExpiry - now) / 1000),
                    region: process.env.AZURE_SPEECH_REGION 
                }
            };
            return;
        }

        // Fetch Azure Speech key from Key Vault
        const credential = new DefaultAzureCredential();
        const vaultUrl = process.env.KEY_VAULT_URL;
        const secretClient = new SecretClient(vaultUrl, credential);
        const secret = await secretClient.getSecret("azure-speech-key");
        const speechKey = secret.value;
        const region = process.env.AZURE_SPEECH_REGION;

        // Exchange key for a short-lived token
        const tokenResponse = await fetch(
            `https://${region}.api.cognitive.microsoft.com/sts/v1.0/issueToken`,
            {
                method: "POST",
                headers: {
                    "Ocp-Apim-Subscription-Key": speechKey,
                    "Content-Length": "0"
                }
            }
        );

        if (!tokenResponse.ok) {
            throw new Error(`Token fetch failed: ${tokenResponse.status}`);
        }

        const token = await tokenResponse.text();
        
        // Cache for 9 minutes (tokens last 10 minutes)
        cachedToken = token;
        tokenExpiry = now + 9 * 60 * 1000;

        context.res = {
            status: 200,
            headers: { "Content-Type": "application/json" },
            body: { 
                token: token, 
                expiresIn: 540, // 9 minutes
                region: region 
            }
        };
    } catch (error) {
        context.log.error("Token exchange error:", error);
        context.res = {
            status: 500,
            body: { error: "Failed to get speech token" }
        };
    }
};
```

**GetSpeechToken/function.json:**
```json
{
  "bindings": [
    {
      "authLevel": "anonymous",
      "type": "httpTrigger",
      "direction": "in",
      "name": "req",
      "methods": ["post"]
    },
    {
      "type": "http",
      "direction": "out",
      "name": "res"
    }
  ]
}
```

### Step 3: Configure Environment Variables

```bash
az functionapp config appsettings set \
  --name wingmate-token-exchange \
  --resource-group wingmate-rg \
  --settings \
    KEY_VAULT_URL="https://wingmate-kv.vault.azure.net/" \
    AZURE_SPEECH_REGION="eastus" \
    CLIENT_API_KEY="$(openssl rand -hex 32)"
```

### Step 4: Deploy

```bash
func azure functionapp publish wingmate-token-exchange
```

---

## Option 2: Cloudflare Workers (Alternative)

If you prefer Cloudflare's global edge network:

```javascript
// Cloudflare Worker: wingmate-token-exchange
export default {
  async fetch(request, env) {
    // Validate request
    const apiKey = request.headers.get('X-API-Key');
    if (apiKey !== env.CLIENT_API_KEY) {
      return new Response(JSON.stringify({ error: 'Unauthorized' }), { 
        status: 401 
      });
    }

    // Get token from Azure
    const tokenResponse = await fetch(
      `https://${env.AZURE_REGION}.api.cognitive.microsoft.com/sts/v1.0/issueToken`,
      {
        method: 'POST',
        headers: {
          'Ocp-Apim-Subscription-Key': env.AZURE_SPEECH_KEY,
          'Content-Length': '0'
        }
      }
    );

    if (!tokenResponse.ok) {
      return new Response(JSON.stringify({ error: 'Token fetch failed' }), { 
        status: 500 
      });
    }

    const token = await tokenResponse.text();
    
    return new Response(JSON.stringify({ 
      token,
      expiresIn: 540,
      region: env.AZURE_REGION
    }), {
      headers: { 'Content-Type': 'application/json' }
    });
  }
};
```

Deploy with Wrangler:
```bash
npx wrangler deploy
npx wrangler secret put AZURE_SPEECH_KEY
npx wrangler secret put AZURE_REGION
npx wrangler secret put CLIENT_API_KEY
```

---

## Client Integration (Kotlin)

Update the `AzureTtsClient` to use token-based auth:

```kotlin
// shared/src/commonMain/kotlin/.../infrastructure/TokenExchangeClient.kt
class TokenExchangeClient(private val httpClient: HttpClient) {
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0
    
    suspend fun getToken(): TokenResult {
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Return cached token if valid
        cachedToken?.let { token ->
            if (tokenExpiry > now + 60_000) {
                return TokenResult.Success(token, (tokenExpiry - now) / 1000)
            }
        }
        
        return try {
            val response = httpClient.post("https://YOUR-FUNCTION.azurewebsites.net/api/GetSpeechToken") {
                header("X-API-Key", BuildConfig.TOKEN_EXCHANGE_API_KEY)
            }
            
            val body = response.body<TokenResponse>()
            cachedToken = body.token
            tokenExpiry = now + (body.expiresIn * 1000)
            
            TokenResult.Success(body.token, body.expiresIn)
        } catch (e: Exception) {
            TokenResult.Error(e.message ?: "Token exchange failed")
        }
    }
}

sealed class TokenResult {
    data class Success(val token: String, val expiresIn: Long) : TokenResult()
    data class Error(val message: String) : TokenResult()
}
```

---

## Security Checklist

- [ ] Azure Speech key stored in Key Vault (not in code)
- [ ] Function uses Managed Identity to access Key Vault
- [ ] Client API key rotated periodically
- [ ] HTTPS only
- [ ] Rate limiting enabled on Function
- [ ] Monitoring/alerts configured
