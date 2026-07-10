#!/bin/sh
# Generate Android release keystore for CI
# Run this on your local machine (requires Java JDK)
# Then add the output as GitHub secrets:
#   KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD

KEYSTORE_FILE="androidApp/release.keystore"

echo "Creating keystore: $KEYSTORE_FILE"
echo ""

read -p "Keystore password: " KEYSTORE_PASSWORD
read -p "Key alias: " KEY_ALIAS
read -p "Key password [same as keystore]: " KEY_PASSWORD
: "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"
read -p "Distinguished Name [CN=Wingmate CI, OU=Development, O=Wingmate, C=US]: " DNAME
: "${DNAME:=CN=Wingmate CI, OU=Development, O=Wingmate, C=US}"

keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "$DNAME" || { echo "keytool failed"; exit 1; }

echo ""
echo "=== GitHub Secrets (copy these) ==="
echo "KEYSTORE_BASE64:"
base64 < "$KEYSTORE_FILE" | tr -d '\n'
echo ""
echo "KEYSTORE_PASSWORD: $KEYSTORE_PASSWORD"
echo "KEY_ALIAS: $KEY_ALIAS"
echo "KEY_PASSWORD: $KEY_PASSWORD"

echo ""
echo "Keystore created at $KEYSTORE_FILE"
echo "Add the above 4 secrets to: https://github.com/jdreioe/wingmate/settings/secrets/actions"
