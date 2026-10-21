# End-to-End Authentication Testing Guide

This guide explains how to test the complete authentication flow locally:
**Register → Login → Get JWT → Access Protected Endpoint**

## Prerequisites

1. **All three services must be running:**
   - Inbox (port 8081)
   - BFF (port 8082)
   - Gateway (port 8080)

2. **PostgreSQL must be running** (Inbox uses it)

3. **JWT RSA keys must exist:**
   ```bash
   cd teams/lens/gateway
   java -cp target/classes com.rnd.gateway.util.JwtKeyGenerator .
   ```

4. **Compile all services:**
   ```bash
   mvn compile -DskipTests
   ```

## Starting Services Locally

You can start the services individually or via Docker Compose.

### Option A: Individual Spring Boot Services

**Terminal 1 - Inbox (port 8081):**
```bash
cd teams/inbox
export SERVER_PORT=8081
mvn spring-boot:run
```

**Terminal 2 - BFF (port 8082):**
```bash
cd teams/lens/bff
export SERVER_PORT=8082
export INBOX_SERVICE_URL=http://localhost:8081
mvn spring-boot:run
```

**Terminal 3 - Gateway (port 8080):**
```bash
cd teams/lens/gateway
export SERVER_PORT=8080
export BFF_SERVICE_URL=http://localhost:8082
export LENS_JWT_ISSUER=http://localhost:8080
mvn spring-boot:run
```

### Option B: Docker Compose

From the workspace root:
```bash
docker-compose up --build
```

## E2E Test Flow

### Step 1: Register a New User

```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "testuser@example.com",
    "password": "SecurePassword123!",
    "displayName": "Test User"
  }'
```

**Expected Response (201 Created):**
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**What happens:**
1. Gateway receives the request
2. Gateway forwards to BFF `/internal/auth/register`
3. BFF forwards to Inbox `/api/v1/auth/register`
4. Inbox creates user with role `EMPLOYEE`
5. Inbox creates `UserAuthIdentity` with BCrypt-hashed password
6. BFF returns user info with mapped role
7. Gateway returns userId

**Common Errors:**
- `400 BAD_REQUEST` - Invalid email format or password too short (<8 chars)
- `409 CONFLICT` - Email already registered
- `429 TOO_MANY_REQUESTS` - More than 10 registrations per hour from this IP

---

### Step 2: Login and Get JWT Token

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "login": "testuser@example.com",
    "password": "SecurePassword123!"
  }'
```

**Expected Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImN3aDJyNDkwNzVhNDU2MzAwNDU0OTQ2Yzg2ZjA3MDk2MiJ9.eyJzdWIiOiI1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDAiLCJyb2xlIjoiRU1QTE9ZRUUiLCJuYW1lIjoiVGVzdCBVc2VyIiwiaXNzIjoibGVucy1nYXRld2F5IiwiaWF0IjoxNzI4MjQwNjAwLCJleHAiOjE3MjgyNDQyMDAsImtpZCI6ImN3aDJyNDkwNzVhNDU2MzAwNDU0OTQ2Yzg2ZjA3MDk2MiJ9.WfD3j8X...",
  "expiresIn": 3600,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "displayName": "Test User",
    "role": "EMPLOYEE"
  }
}
```

**What happens:**
1. Gateway receives login request
2. Gateway normalizes email: `testuser@example.com` → `testuser@example.com` (trim + lowercase)
3. Gateway forwards to BFF `/internal/auth/verify`
4. BFF normalizes and forwards to Inbox `/api/v1/auth/verify`
5. Inbox finds user by email, verifies BCrypt password
6. Inbox returns user with role
7. BFF maps role: `EMPLOYEE` → `EMPLOYEE`
8. Gateway generates RS256-signed JWT token with claims:
   - `sub`: User UUID
   - `role`: EMPLOYEE
   - `name`: Display name
   - `iss`: lens-gateway
   - `exp`: Current time + 3600 seconds
9. Gateway returns token

**Common Errors:**
- `401 UNAUTHORIZED` - Invalid email/password (generic message, same for both)
- `429 TOO_MANY_REQUESTS` - More than 5 failed login attempts per minute from this IP
- `503 SERVICE_UNAVAILABLE` - BFF or Inbox unreachable

**Save the token for the next step:**
```bash
TOKEN="eyJhbGciOiJSUzI1NiIsImtpZCI6ImN3aDJyNDkwNzVhNDU2MzAwNDU0OTQ2Yzg2ZjA3MDk2MiJ9.eyJzdWIiOiI1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDAiLCJyb2xlIjoiRU1QTE9ZRUUiLCJuYW1lIjoiVGVzdCBVc2VyIiwiaXNzIjoibGVucy1nYXRld2F5IiwiaWF0IjoxNzI4MjQwNjAwLCJleHAiOjE3MjgyNDQyMDAsImtpZCI6ImN3aDJyNDkwNzVhNDU2MzAwNDU0OTQ2Yzg2ZjA3MDk2MiJ9.WfD3j8X..."
```

---

### Step 3: Get Current User Info (Verify Token)

```bash
curl -X GET http://localhost:8080/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "displayName": "Test User",
  "role": "EMPLOYEE"
}
```

**What happens:**
1. Gateway receives request with Bearer token
2. SecurityConfig validates JWT signature using public RSA key
3. JWT is parsed and claims are extracted
4. Token must have valid signature, not be expired, and have `iss=lens-gateway`
5. AuthController reads JWT claims and returns user info

**Common Errors:**
- `401 UNAUTHORIZED` - Token missing, invalid signature, expired, or wrong issuer

---

### Step 4: Access Protected Workspace Endpoint

Gateway has workspace endpoints that require authentication:

```bash
curl -X GET http://localhost:8080/api/workspace/tasks \
  -H "Authorization: Bearer $TOKEN"
```

**Expected Response (200 OK):**
```json
{
  "tasks": [ ... ]
}
```

**What happens:**
1. Gateway receives request with Bearer token
2. SecurityConfig validates JWT (same as `/auth/me`)
3. CurrentUserContext is populated with user ID and role from JWT claims
4. HeaderInjectionFilter injects `X-User-Id` and `X-User-Role` headers
5. WorkspaceController forwards request to BFF with headers:
   - `X-User-Id: 550e8400-e29b-41d4-a716-446655440000`
   - `X-User-Role: EMPLOYEE`
6. BFF receives request with headers and processes it
7. BFF's `/workspace/tasks` endpoint can now access the current user via `@CurrentUser` resolver

**Common Errors:**
- `401 UNAUTHORIZED` - No token or invalid token
- `403 FORBIDDEN` - User doesn't have permission (not applicable for EMPLOYEE on workspace endpoints)
- `503 SERVICE_UNAVAILABLE` - BFF unreachable

---

### Step 5: Test Admin Login (Optional)

If you've configured admin credentials via environment:

**Environment setup:**
```bash
export INBOX_ADMIN_USERNAME=admin
export INBOX_ADMIN_PASSWORD=AdminPassword123!
```

**Login as admin:**
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "login": "admin",
    "password": "AdminPassword123!"
  }'
```

**Expected Response:**
```json
{
  "accessToken": "...",
  "expiresIn": 3600,
  "user": {
    "id": "00000000-0000-0000-0000-000000000001",
    "displayName": "Admin User",
    "role": "MANAGER"
  }
}
```

**What's different:**
- Login is admin *username*, not email
- Role is `MANAGER` (mapped from Inbox `SYSTEM_ADMIN`)
- Can access `/api/manager/**` endpoints

---

## Rate Limiting Tests

### Test Registration Rate Limit

Try registering 11 times from the same IP in under 1 hour:

```bash
for i in {1..11}; do
  echo "Attempt $i:"
  curl -X POST http://localhost:8080/auth/register \
    -H "Content-Type: application/json" \
    -d "{
      \"email\": \"user$i@example.com\",
      \"password\": \"Password123!\",
      \"displayName\": \"User $i\"
    }"
  echo ""
done
```

**Expected:** Requests 1-10 succeed (201), request 11 gets `429 TOO_MANY_REQUESTS`

### Test Login Rate Limit

Try 6 failed logins from the same IP within 1 minute:

```bash
for i in {1..6}; do
  echo "Attempt $i:"
  curl -X POST http://localhost:8080/auth/login \
    -H "Content-Type: application/json" \
    -d '{
      "login": "testuser@example.com",
      "password": "WrongPassword"
    }'
  echo ""
done
```

**Expected:** Requests 1-5 return 401, request 6 returns `429 TOO_MANY_REQUESTS`

---

## Troubleshooting

### "Connection refused" on port 8080/8082/8081

**Problem:** Services not running

**Solution:**
```bash
# Check if ports are in use
netstat -ano | grep 8080
netstat -ano | grep 8082
netstat -ano | grep 8081

# Start services in separate terminals
cd teams/inbox && mvn spring-boot:run
cd teams/lens/bff && mvn spring-boot:run
cd teams/lens/gateway && mvn spring-boot:run
```

### "Email already taken" or "Email already registered"

**Problem:** Email already exists from previous test

**Solution:** Use a different email or drop the database and restart:
```bash
# With Docker
docker-compose down -v  # Remove volumes
docker-compose up --build

# Or with local PostgreSQL
psql -U postgres -c "DROP DATABASE resonance_flow;"
psql -U postgres -c "CREATE DATABASE resonance_flow;"
```

### "Invalid JWT signature"

**Problem:** JWT keys don't exist or are corrupted

**Solution:** Regenerate keys:
```bash
cd teams/lens/gateway
java -cp target/classes com.rnd.gateway.util.JwtKeyGenerator .
```

### "BFF service unavailable" (503)

**Problem:** BFF is not running or not accessible

**Solution:**
```bash
# Check BFF is running
curl http://localhost:8082/health

# If not running
cd teams/lens/bff
export SERVER_PORT=8082
mvn spring-boot:run
```

### "Inbox service unavailable" (503)

**Problem:** Inbox is not running or not accessible from BFF

**Solution:**
```bash
# Check Inbox is running
curl http://localhost:8081/health

# If not running
cd teams/inbox
export SERVER_PORT=8081
mvn spring-boot:run
```

---

## Complete Test Script (Bash)

Save as `e2e_test.sh`:

```bash
#!/bin/bash

GATEWAY="http://localhost:8080"
EMAIL="e2etest_$(date +%s)@example.com"
PASSWORD="E2ETestPassword123!"
DISPLAYNAME="E2E Test User"

echo "=== E2E Authentication Test ==="
echo ""

# Step 1: Register
echo "1. Registering user..."
REGISTER_RESPONSE=$(curl -s -X POST "$GATEWAY/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"$EMAIL\",
    \"password\": \"$PASSWORD\",
    \"displayName\": \"$DISPLAYNAME\"
  }")

USER_ID=$(echo $REGISTER_RESPONSE | jq -r '.userId')
echo "   User ID: $USER_ID"
echo ""

# Step 2: Login
echo "2. Logging in..."
LOGIN_RESPONSE=$(curl -s -X POST "$GATEWAY/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"login\": \"$EMAIL\",
    \"password\": \"$PASSWORD\"
  }")

TOKEN=$(echo $LOGIN_RESPONSE | jq -r '.accessToken')
ROLE=$(echo $LOGIN_RESPONSE | jq -r '.user.role')
echo "   Token: ${TOKEN:0:50}..."
echo "   Role: $ROLE"
echo ""

# Step 3: Get current user
echo "3. Getting current user..."
ME_RESPONSE=$(curl -s -X GET "$GATEWAY/auth/me" \
  -H "Authorization: Bearer $TOKEN")

echo "   ID: $(echo $ME_RESPONSE | jq -r '.id')"
echo "   Name: $(echo $ME_RESPONSE | jq -r '.displayName')"
echo "   Role: $(echo $ME_RESPONSE | jq -r '.role')"
echo ""

# Step 4: Access protected endpoint
echo "4. Accessing protected endpoint..."
API_RESPONSE=$(curl -s -w "\n%{http_code}" -X GET "$GATEWAY/api/workspace/tasks" \
  -H "Authorization: Bearer $TOKEN")

HTTP_CODE=$(echo "$API_RESPONSE" | tail -n1)
echo "   HTTP Status: $HTTP_CODE"
echo ""

if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ All tests passed!"
else
  echo "❌ Test failed with HTTP $HTTP_CODE"
  echo "Response: $(echo "$API_RESPONSE" | head -n -1)"
fi
```

Run it:
```bash
chmod +x e2e_test.sh
./e2e_test.sh
```

---

## Next Steps

After passing E2E tests:

1. **Manual frontend testing** - Use actual frontend UI to register, login, and access pages
2. **Load testing** - Test rate limiting and performance
3. **Security testing** - JWT token tampering, signature validation
4. **Production deployment** - Replace keys with Kubernetes Secret, enable HTTPS

