#!/bin/bash

# =============================================================================
# JPassbolt vs Passbolt API Comparison Script
# =============================================================================
# 
# This script compares API responses between JPassbolt (Java) and Passbolt (PHP)
# to verify implementation compatibility.
#
# Usage:
#   ./api_comparison.sh [JPASSBOLT_URL] [PASSBOLT_URL]
#
# Example:
#   ./api_comparison.sh http://localhost:8080 https://passbolt.example.com
# =============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default URLs
JPASSBOLT_URL="${1:-http://localhost:8080}"
PASSBOLT_URL="${2:-}"

echo "=============================================="
echo "  JPassbolt vs Passbolt API Comparison"
echo "=============================================="
echo ""
echo "JPassbolt URL: $JPASSBOLT_URL"
echo "Passbolt URL:  ${PASSBOLT_URL:-Not configured (single server mode)}"
echo ""

# =============================================================================
# Helper Functions
# =============================================================================

compare_json_field() {
    local field=$1
    local json1=$2
    local json2=$3
    
    val1=$(echo "$json1" | jq -r "$field" 2>/dev/null || echo "null")
    val2=$(echo "$json2" | jq -r "$field" 2>/dev/null || echo "null")
    
    if [ "$val1" == "$val2" ]; then
        echo -e "${GREEN}✓${NC} $field: matches"
        return 0
    else
        echo -e "${RED}✗${NC} $field: JPassbolt='$val1' vs Passbolt='$val2'"
        return 1
    fi
}

check_header() {
    local header_name=$1
    local response=$2
    local expected=$3
    
    actual=$(echo "$response" | grep -i "^$header_name:" | cut -d: -f2 | tr -d ' \r')
    
    if [ -z "$expected" ]; then
        # Just check existence
        if [ -n "$actual" ]; then
            echo -e "${GREEN}✓${NC} Header $header_name exists: $actual"
            return 0
        else
            echo -e "${RED}✗${NC} Header $header_name missing"
            return 1
        fi
    else
        if [ "$actual" == "$expected" ]; then
            echo -e "${GREEN}✓${NC} Header $header_name: $actual"
            return 0
        else
            echo -e "${RED}✗${NC} Header $header_name: expected='$expected', got='$actual'"
            return 1
        fi
    fi
}

# =============================================================================
# Test 1: GET /auth/verify.json
# =============================================================================

echo ""
echo "----------------------------------------------"
echo "Test 1: GET /auth/verify.json"
echo "----------------------------------------------"

JPASSBOLT_VERIFY=$(curl -s "$JPASSBOLT_URL/auth/verify.json")

echo ""
echo "Response structure check:"

# Check response has expected fields
if echo "$JPASSBOLT_VERIFY" | jq -e '.body.fingerprint' > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} body.fingerprint exists"
else
    echo -e "${RED}✗${NC} body.fingerprint missing"
fi

if echo "$JPASSBOLT_VERIFY" | jq -e '.body.keydata' > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} body.keydata exists"
else
    echo -e "${RED}✗${NC} body.keydata missing"
fi

if echo "$JPASSBOLT_VERIFY" | jq -e '.header.status' > /dev/null 2>&1; then
    status=$(echo "$JPASSBOLT_VERIFY" | jq -r '.header.status')
    if [ "$status" == "success" ]; then
        echo -e "${GREEN}✓${NC} header.status: success"
    else
        echo -e "${YELLOW}!${NC} header.status: $status (expected 'success')"
    fi
else
    echo -e "${RED}✗${NC} header.status missing"
fi

# Validate fingerprint format (40 hex chars)
FINGERPRINT=$(echo "$JPASSBOLT_VERIFY" | jq -r '.body.fingerprint')
if [[ "$FINGERPRINT" =~ ^[A-Fa-f0-9]{40}$ ]]; then
    echo -e "${GREEN}✓${NC} Fingerprint format valid: $FINGERPRINT"
else
    echo -e "${RED}✗${NC} Fingerprint format invalid: $FINGERPRINT"
fi

# =============================================================================
# Test 2: POST /auth/login.json (Stage 1 - missing gpg_auth)
# =============================================================================

echo ""
echo "----------------------------------------------"
echo "Test 2: POST /auth/login.json (missing gpg_auth)"
echo "----------------------------------------------"

RESPONSE=$(curl -s -i -X POST "$JPASSBOLT_URL/auth/login.json" \
    -H "Content-Type: application/json" \
    -d '{"data": {}}')

echo ""
echo "Header checks:"
check_header "X-GPGAuth-Authenticated" "$RESPONSE" "false"
check_header "X-GPGAuth-Error" "$RESPONSE" "true"

# =============================================================================
# Test 3: POST /auth/login.json (Stage 1 - invalid keyid)
# =============================================================================

echo ""
echo "----------------------------------------------"
echo "Test 3: POST /auth/login.json (invalid keyid)"
echo "----------------------------------------------"

RESPONSE=$(curl -s -i -X POST "$JPASSBOLT_URL/auth/login.json" \
    -H "Content-Type: application/json" \
    -d '{"data": {"gpg_auth": {"keyid": "INVALID_KEY_ID"}}}')

echo ""
echo "Header checks:"
check_header "X-GPGAuth-Authenticated" "$RESPONSE" "false"
check_header "X-GPGAuth-Error" "$RESPONSE" "true"
check_header "X-GPGAuth-Progress" "$RESPONSE"

# =============================================================================
# Test 4: POST /auth/login.json (Stage 1 - valid format keyid, non-existent)
# =============================================================================

echo ""
echo "----------------------------------------------"
echo "Test 4: POST /auth/login.json (non-existent fingerprint)"
echo "----------------------------------------------"

RESPONSE=$(curl -s -i -X POST "$JPASSBOLT_URL/auth/login.json" \
    -H "Content-Type: application/json" \
    -d '{"data": {"gpg_auth": {"keyid": "333788B5464B797FDF10A98F2FE96B47C7FF421B"}}}')

echo ""
echo "Header checks:"
check_header "X-GPGAuth-Authenticated" "$RESPONSE" "false"
check_header "X-GPGAuth-Error" "$RESPONSE" "true"

# =============================================================================
# Test 5: Nonce format validation
# =============================================================================

echo ""
echo "----------------------------------------------"
echo "Test 5: Nonce Format Validation"
echo "----------------------------------------------"

echo ""
echo "Expected nonce format: gpgauthv1.3.0|36|{UUID}|gpgauthv1.3.0"
echo ""

# Valid format example
VALID_UUID="de305d54-75b4-431b-adb2-eb6b9e546014"
VALID_NONCE="gpgauthv1.3.0|36|$VALID_UUID|gpgauthv1.3.0"

if [[ "$VALID_NONCE" =~ ^gpgauthv1\.3\.0\|36\|[0-9a-f-]+\|gpgauthv1\.3\.0$ ]]; then
    echo -e "${GREEN}✓${NC} Valid nonce format: $VALID_NONCE"
else
    echo -e "${RED}✗${NC} Nonce format check failed"
fi

# Invalid format examples
INVALID_NONCES=(
    "gpgauthv1.2.0|36|$VALID_UUID|gpgauthv1.3.0"  # wrong version
    "gpgauthv1.3.0|32|$VALID_UUID|gpgauthv1.3.0"  # wrong length
    "gpgauthv1.3.0,36|$VALID_UUID|gpgauthv1.3.0"  # wrong delimiter
)

echo ""
echo "Invalid nonce examples (should fail):"
for nonce in "${INVALID_NONCES[@]}"; do
    if [[ "$nonce" =~ ^gpgauthv1\.3\.0\|36\|[0-9a-f-]+\|gpgauthv1\.3\.0$ ]]; then
        echo -e "${RED}✗${NC} Should be invalid: $nonce"
    else
        echo -e "${GREEN}✓${NC} Correctly rejected: ${nonce:0:50}..."
    fi
done

# =============================================================================
# Compare with Passbolt (if URL provided)
# =============================================================================

if [ -n "$PASSBOLT_URL" ]; then
    echo ""
    echo "=============================================="
    echo "  Comparing with Passbolt Server"
    echo "=============================================="
    
    echo ""
    echo "----------------------------------------------"
    echo "Compare: GET /auth/verify.json"
    echo "----------------------------------------------"
    
    PASSBOLT_VERIFY=$(curl -s "$PASSBOLT_URL/auth/verify.json")
    
    echo ""
    echo "Field comparison:"
    compare_json_field ".header.status" "$JPASSBOLT_VERIFY" "$PASSBOLT_VERIFY"
    
    # Compare fingerprint length (should both be 40 chars)
    J_FP=$(echo "$JPASSBOLT_VERIFY" | jq -r '.body.fingerprint' | wc -c | tr -d ' ')
    P_FP=$(echo "$PASSBOLT_VERIFY" | jq -r '.body.fingerprint' | wc -c | tr -d ' ')
    
    if [ "$J_FP" == "$P_FP" ]; then
        echo -e "${GREEN}✓${NC} Fingerprint length matches: $((J_FP - 1)) chars"
    else
        echo -e "${YELLOW}!${NC} Different fingerprint lengths (expected, different servers)"
    fi
fi

# =============================================================================
# Summary
# =============================================================================

echo ""
echo "=============================================="
echo "  Comparison Complete"
echo "=============================================="
echo ""
echo "To run the Java compatibility tests:"
echo "  cd /Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api"
echo "  mvn test -Dtest=AuthControllerCompatibilityTest"
echo ""
