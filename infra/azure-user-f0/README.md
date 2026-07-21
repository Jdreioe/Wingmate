# Wingmate F0 Speech Resource

Deploys an Azure Cognitive Services Speech resource with the **F0 (free) SKU** for use with Wingmate.

> The F0 tier includes **500,000 characters per month** free. This is sufficient for moderate daily use.

## Quick Deploy

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjdreioe%2Fwingmate%2Fmain%2Finfra%2Fazure-user-f0%2Fazuredeploy.json)

Click the button above to open the Azure Portal with this template pre-loaded.

## Prerequisites

- An [Azure account](https://azure.microsoft.com/free) (free tier is sufficient)
- Permissions to create resources in a resource group

## What gets deployed

| Resource | Type | SKU |
|----------|------|-----|
| Speech resource | `Microsoft.CognitiveServices/accounts` | **F0** (fixed, cannot select S0) |

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `location` | `northeurope` | Azure region. Only EU regions are allowed (see below). |
| `speechResourceName` | auto-generated | Optional custom name for the Speech resource. |
| `tags` | `{}` | Optional Azure resource tags. |

### Allowed regions

Only the following EU regions are supported:

- `northeurope` (Ireland)
- `westeurope` (Netherlands)
- `francecentral`
- `germanywestcentral`
- `switzerlandnorth`
- `swedencentral`
- `norwayeast`
- `polandcentral`
- `italynorth`
- `spaincentral`

## Manual deployment

### Azure CLI

```bash
# Login
az login

# Deploy the template
az deployment group create \
  --resource-group <your-resource-group> \
  --template-file main.bicep \
  --parameters location=northeurope
```

### PowerShell

```powershell
New-AzResourceGroupDeployment `
  -ResourceGroupName <your-resource-group> `
  -TemplateFile azuredeploy.json `
  -location northeurope
```

## Outputs

After successful deployment, the template returns:

| Output | Description |
|--------|-------------|
| `resourceId` | Full Azure resource ID |
| `name` | Name of the Speech resource |
| `region` | Azure region where deployed |
| `portalUrl` | Link to the resource in Azure Portal |

> **No secrets are exported.** Keys must be retrieved separately via the Azure Portal or `az cognitiveservices account keys list`.

## Recovering an existing F0 resource

Only one F0 Speech resource per subscription is allowed. If you already have an F0 Speech resource:

1. Go to [Azure Portal > Speech Services](https://portal.azure.com/#view/HubsExtension/BrowseResource/resourceType/Microsoft.CognitiveServices%2Faccounts)
2. Locate your existing F0 resource
3. Use its endpoint and key directly in Wingmate instead of creating a new one

Attempting to deploy a second F0 resource will fail with a conflict error. This is expected behavior.

## Security notes

- The Speech key is **not** included in the template outputs
- Local API authentication is enabled (default)
- Public network access is enabled so the Wingmate app can reach the endpoint
- Keys should be stored securely in the Wingmate app's platform secure storage (Android Keystore, iOS Keychain, OS keyring)

## Related

- [Azure Speech pricing](https://azure.microsoft.com/pricing/details/cognitive-services/speech-services/)
- [Azure resource limits](https://learn.microsoft.com/azure/azure-resource-manager/management/azure-subscription-service-limits)
- [Wingmate setup guide](../../README.md)
