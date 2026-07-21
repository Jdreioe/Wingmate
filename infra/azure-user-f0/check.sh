#!/usr/bin/env bash
set -euo pipefail

# Non-mutating validation/check task for the F0 deployment template.
# Runs basic structural checks without touching Azure.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$SCRIPT_DIR/azuredeploy.json"
BICEP="$SCRIPT_DIR/main.bicep"

errors=0

echo "=== azuredeploy.json checks ==="

# 1. Must be valid JSON
if python3 -c "import json; json.load(open('$TEMPLATE'))" 2>/dev/null; then
    echo "  [PASS] Valid JSON"
else
    echo "  [FAIL] Invalid JSON"
    errors=$((errors + 1))
fi

# 2. Must be an ARM template
SCHEMA=$(python3 -c "import json; d=json.load(open('$TEMPLATE')); print(d.get('\$schema',''))" 2>/dev/null)
if echo "$SCHEMA" | grep -q "deploymentTemplate"; then
    echo "  [PASS] Schema is ARM deploymentTemplate"
else
    echo "  [FAIL] Missing or wrong \$schema"
    errors=$((errors + 1))
fi

# 3. Content version must be present
CONTENT_VER=$(python3 -c "import json; d=json.load(open('$TEMPLATE')); print(d.get('contentVersion',''))" 2>/dev/null)
if [ -n "$CONTENT_VER" ]; then
    echo "  [PASS] contentVersion = $CONTENT_VER"
else
    echo "  [FAIL] Missing contentVersion"
    errors=$((errors + 1))
fi

# 4. SKU must be F0 (not S0)
SKU=$(python3 -c "
import json
d = json.load(open('$TEMPLATE'))
resources = d.get('resources', [])
for r in resources:
    if r.get('type','').endswith('accounts'):
        print(r.get('sku',{}).get('name',''))
" 2>/dev/null)
if [ "$SKU" = "F0" ]; then
    echo "  [PASS] SKU is F0"
else
    echo "  [FAIL] SKU is '$SKU' (expected F0)"
    errors=$((errors + 1))
fi

# 5. Kind must be SpeechServices
KIND=$(python3 -c "
import json
d = json.load(open('$TEMPLATE'))
resources = d.get('resources', [])
for r in resources:
    if r.get('type','').endswith('accounts'):
        print(r.get('kind',''))
" 2>/dev/null)
if [ "$KIND" = "SpeechServices" ]; then
    echo "  [PASS] Kind is SpeechServices"
else
    echo "  [FAIL] Kind is '$KIND' (expected SpeechServices)"
    errors=$((errors + 1))
fi

# 6. Default location must be northeurope
DEFAULT_LOC=$(python3 -c "
import json; d=json.load(open('$TEMPLATE'))
p = d.get('parameters',{}).get('location',{})
print(p.get('defaultValue',''))
" 2>/dev/null)
if [ "$DEFAULT_LOC" = "northeurope" ]; then
    echo "  [PASS] Default location is northeurope"
else
    echo "  [FAIL] Default location is '$DEFAULT_LOC' (expected northeurope)"
    errors=$((errors + 1))
fi

# 7. Allowed locations must include only EU regions
ALLOWED_LOCS=$(python3 -c "
import json; d=json.load(open('$TEMPLATE'))
p = d.get('parameters',{}).get('location',{})
print(' '.join(p.get('allowedValues',[])))
" 2>/dev/null)
EU_REGIONS="northeurope westeurope francecentral germanywestcentral switzerlandnorth swedencentral norwayeast polandcentral italynorth spaincentral"
if [ "$ALLOWED_LOCS" = "$EU_REGIONS" ]; then
    echo "  [PASS] Allowed regions are EU-only"
else
    echo "  [FAIL] Allowed regions mismatch"
    echo "    expected: $EU_REGIONS"
    echo "    got:      $ALLOWED_LOCS"
    errors=$((errors + 1))
fi

# 8. No secret outputs
OUTPUTS=$(python3 -c "
import json; d=json.load(open('$TEMPLATE'))
outs = d.get('outputs',{})
for k,v in outs.items():
    print(k)
" 2>/dev/null | sort)
if echo "$OUTPUTS" | grep -q "key\|secret\|password\|connectionString"; then
    echo "  [FAIL] Output contains secret: $OUTPUTS"
    errors=$((errors + 1))
else
    echo "  [PASS] No secret in outputs"
fi

# 9. Public network access must be enabled
PUBLIC_NET=$(python3 -c "
import json; d=json.load(open('$TEMPLATE'))
resources = d.get('resources', [])
for r in resources:
    if r.get('type','').endswith('accounts'):
        print(r.get('properties',{}).get('publicNetworkAccess',''))
" 2>/dev/null)
if [ "$PUBLIC_NET" = "Enabled" ]; then
    echo "  [PASS] Public network access is Enabled"
else
    echo "  [FAIL] Public network access is '$PUBLIC_NET' (expected Enabled)"
    errors=$((errors + 1))
fi

echo ""
echo "=== main.bicep check ==="
if [ -f "$BICEP" ]; then
    echo "  [PASS] main.bicep exists"
else
    echo "  [FAIL] main.bicep missing"
    errors=$((errors + 1))
fi

echo ""
if [ "$errors" -eq 0 ]; then
    echo "All checks passed."
else
    echo "$errors check(s) failed."
    exit 1
fi
