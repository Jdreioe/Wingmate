@description('Azure region for the Speech resource. Must be a supported EU region.')
@allowed([
  'northeurope'
  'westeurope'
  'francecentral'
  'germanywestcentral'
  'switzerlandnorth'
  'swedencentral'
  'norwayeast'
  'polandcentral'
  'italynorth'
  'spaincentral'
])
param location string = 'northeurope'

@description('Optional: name of the Speech resource. Auto-generated if empty.')
param speechResourceName string = ''

@description('Optional: tags to apply to the resource.')
param tags object = {}

var uniqueName = !empty(speechResourceName) ? speechResourceName : 'wingmate-f0-${uniqueString(resourceGroup().id)}'

resource speech 'Microsoft.CognitiveServices/accounts@2024-10-01' = {
  name: uniqueName
  location: location
  kind: 'SpeechServices'
  sku: {
    name: 'F0'
  }
  tags: tags
  properties: {
    publicNetworkAccess: 'Enabled'
    disableLocalAuth: false
  }
}

output resourceId string = speech.id
output name string = speech.name
output region string = speech.location
output portalUrl string = 'https://portal.azure.com/#@/resource${speech.id}'
