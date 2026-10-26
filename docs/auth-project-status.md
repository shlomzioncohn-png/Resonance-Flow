# Password-Based Auth Implementation - Final Status

**Date:** October 7, 2026  
**Status:** ✅ COMPLETE (Gateway + BFF)  
**Next:** Inbox team implementation (2-4 days)

---

## Summary

Password-based authentication with JWT RS256 is **fully working** in Gateway and BFF.

### What's Done ✅

| Component | Status | Files | Details |
|---|---|---|---|
| **Gateway** | ✅ Complete | 7 Java + 2 PEM | AuthController, TokenService, JWT RS256, rate limiting, 3 endpoints |
| **BFF** | ✅ Complete | 7 Java files | InternalAuthController, RoleMapper, mock InboxUsersClient |
| **Inbox** | ⏳ Pending | Spec provided | UserAuthIdentityRepository, AuthService, AuthController needed |
| **Documentation** | ✅ Complete | 2 MD files | IMPLEMENTATION_SUMMARY.md + INBOX_TEAM_HANDOFF.md |

---

## Live Demo (Local)

### Tested Flow
1. ✅ `POST /auth/register` → 201 (creates mock user)
2. ✅ `POST /auth/login` → 200 + JWT token (RS256 signed)
3. ✅ `GET /auth/me` + Bearer token → 200 + user info

### Test Users (Mock)
```
Email: emp@example.com, Password: password → EMPLOYEE
Email: mgr@example.com, Password: password → MANAGER (SYSTEM_ADMIN role)
```

### Running Locally
```bash
# Start Inbox (docker)
docker-compose up -d inbox

# Start BFF
cd teams/lens/bff
mvn spring-boot:run

# Start Gateway
cd teams/lens/gateway
mvn spring-boot:run

# Test
curl -X POST http://localhost:8090/auth/login \
  -H "Content-Type: application/json" \
  -d '{"login":"emp@example.com","password":"password"}'
```

---

## Architecture

```
┌─ Frontend ──────────────────────────────────────────────────────────┐
│                                                                      │
│  1. POST /auth/register  →  2. POST /auth/login  →  3. GET /auth/me │
│     {email, pwd, name}       {login, pwd}              Bearer token  │
│            ↓                        ↓                        ↓        │
├──────────────────────────────────────────────────────────────────────┤
│                         GATEWAY (8090)                               │
│                                                                      │
│  - Validates credentials via BFF                                    │
│  - Signs JWT (RS256)                                                │
│  - Rate limiting (5 failed/min per IP)                              │
│  - Returns token + user info                                        │
│                                                                      │
│              ↓                    ↓                                  │
├──────────────────────────────────────────────────────────────────────┤
│                    BFF (8082) - Internal                             │
│                                                                      │
│  - POST /internal/auth/register  (Gateway only)                     │
│  - POST /internal/auth/verify    (Gateway only)                     │
│  - Role mapping (Inbox → LENS)                                      │
│  - Currently: Mock Inbox (returns test users)                       │
│  - Future: Real Inbox HTTP calls                                    │
│                                                                      │
│              ↓                    ↓                                  │
├──────────────────────────────────────────────────────────────────────┤
│                    INBOX (8080) - To be done                         │
│                                                                      │
│  - POST /api/v1/auth/register    (create user + password hash)      │
│  - POST /api/v1/auth/verify      (validate + return user)           │
│  - Schema ready (users, user_auth_identities)                       │
│  - Needs: AuthService, AuthController, Seed logic                   │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Files Created/Modified

### Gateway (`teams/lens/gateway/`)
**New:**
- `src/main/java/com/rnd/gateway/auth/AuthController.java`
- `src/main/java/com/rnd/gateway/auth/AuthService.java`
- `src/main/java/com/rnd/gateway/auth/TokenService.java`
- `src/main/java/com/rnd/gateway/auth/RateLimitingConfig.java`
- `src/main/java/com/rnd/gateway/auth/BffAuthClient.java`
- `src/main/java/com/rnd/gateway/config/JwtConfig.java`
- `src/main/java/com/rnd/gateway/config/CorsConfig.java`
- `jwt-private.pem` (gitignored)
- `jwt-public.pem` (gitignored)

**Modified:**
- `src/main/resources/application.yml` (port 8090, JWT config, auth enabled)
- `src/main/java/com/rnd/gateway/config/SecurityConfig.java` (JWT validation)
- `src/main/java/com/rnd/gateway/config/SecurityValidationConfig.java` (local JWT mode)
- `pom.xml` (added bucket4j-core)

### BFF (`teams/lens/bff/`)
**New:**
- `src/main/java/com/rnd/bff/controller/InternalAuthController.java`
- `src/main/java/com/rnd/bff/service/InternalAuthService.java`
- `src/main/java/com/rnd/bff/service/RoleMapper.java`
- `src/main/java/com/rnd/bff/client/InboxUsersClient.java`
- `src/main/java/com/rnd/bff/dto/AuthRegisterRequestDto.java`
- `src/main/java/com/rnd/bff/dto/AuthVerifyRequestDto.java`
- `src/main/java/com/rnd/bff/dto/AuthUserResponseDto.java`

**Modified:**
- `src/main/resources/application.yml` (inbox config)

### Documentation (Repo Root)
- **IMPLEMENTATION_SUMMARY.md** - Full technical specification (500+ lines)
- **INBOX_TEAM_HANDOFF.md** - Executive summary for Inbox team
- **PROJECT_STATUS.md** - This file

---

## Key Features

### ✅ Gateway Features
- `POST /auth/register` - Create new user
- `POST /auth/login` - Authenticate and get JWT
- `GET /auth/me` - Get current user (requires Bearer token)
- JWT RS256 signature (local keys, 1-hour expiry)
- Rate limiting (5 failed logins/min per IP)
- CORS enabled for browser requests

### ✅ BFF Features
- Internal endpoints (`/internal/auth/*`)
- Role mapping (SYSTEM_ADMIN → MANAGER, EMPLOYEE → EMPLOYEE)
- Mock Inbox for testing
- Ready for real Inbox HTTP integration

### 🔲 Inbox Needs (Spec Provided)
- `POST /api/v1/auth/register` - Create user + password identity
- `POST /api/v1/auth/verify` - Validate credentials
- UserAuthIdentityRepository
- AuthService with BCrypt hashing
- Admin seed + service account seed

---

## Endpoint Contracts

### Gateway Public API

#### `POST /auth/register`
```json
Request:  {email, password, displayName}
Response: 201 {userId}
          409 Email already taken
          400 Invalid input
```

#### `POST /auth/login`
```json
Request:  {login, password}
Response: 200 {accessToken, expiresIn, user:{id, displayName, role}}
          401 Invalid credentials
          429 Too many attempts
```

#### `GET /auth/me`
```json
Request:  Authorization: Bearer <token>
Response: 200 {id, displayName, role}
          401 Invalid/missing token
```

### BFF Internal API (Gateway only)

#### `POST /internal/auth/register`
```json
Request:  {email, password, displayName}
Response: 201 {id, displayName, role}
          409 Email already taken
          400 Invalid input
          503 Inbox unavailable (future)
```

#### `POST /internal/auth/verify`
```json
Request:  {login, password}
Response: 200 {id, displayName, role}
          401 Invalid credentials
          503 Inbox unavailable (future)
```

---

## Security Measures

✅ **Implemented:**
- JWT signature validation (RS256)
- Rate limiting (Bucket4j)
- CORS for browser requests
- Email/login normalization
- Password validation (8-72 chars)
- HTTPS ready (via deployment)

⏳ **Inbox to implement:**
- BCrypt password hashing
- Timing attack prevention
- Authorization checks (X-Actor-Id header)
- User status validation
- Last login timestamp tracking

---

## Environment Variables

### Gateway (env or application.yml)
```yaml
SERVER_PORT=8090
APP_AUTH_ENABLED=true
BFF_SERVICE_URL=http://localhost:8082
JWT_PRIVATE_KEY_LOCATION=./jwt-private.pem
JWT_PUBLIC_KEY_LOCATION=./jwt-public.pem
```

### BFF (env or application.yml)
```yaml
SERVER_PORT=8082
INBOX_MOCK_ENABLED=true
INBOX_SERVICE_URL=http://localhost:8080
```

### Inbox (when ready)
```yaml
INBOX_ADMIN_USERNAME=admin
INBOX_ADMIN_PASSWORD=password123
INBOX_DEFAULT_TENANT_ID=00000000-0000-0000-0000-000000000001
INBOX_SERVICE_ACCOUNT_ID=00000000-0000-0000-0000-000000000002
```

---

## Testing Status

### ✅ Verified (Working)
- Register with mock: 201 + userId
- Login with mock: 200 + JWT token
- GET /auth/me with Bearer token: 200 + user info
- Rate limiting: 429 after 5 failed attempts
- JWT signature validation: RS256
- CORS preflight: OPTIONS requests accepted

### 🔲 Not Yet Tested (Awaiting Inbox)
- Admin login with username
- Real Inbox integration
- Token expiration (1 hour)
- Password reset flow
- Refresh tokens

---

## Deployment Notes

### Local Development
```bash
# Generate keys (one-time)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem

# Store in teams/lens/gateway/ (gitignored)
```

### Docker/K8s (Production)
- JWT keys stored in Secret (not in repo)
- BFF/Gateway run as separate pods
- Inbox runs as separate pod
- Services communicate via internal ClusterIP

---

## Git Branch Info

**Current Branch:** `auth-stage2-jwt-extraction`

**Strategy:**
- ✅ Don't merge inbox-team (unrelated features)
- ✅ Share IMPLEMENTATION_SUMMARY.md with Inbox team
- ✅ Wait for Inbox auth implementation
- ✅ Then consider merging inbox-team features (task selection, etc.)

**Ready to:**
- Create PR for review
- Merge to `main` when Inbox team confirms integration points
- Deploy once Inbox auth is ready

---

## Next Steps (Inbox Team)

1. **Read INBOX_TEAM_HANDOFF.md** (executive summary)
2. **Review IMPLEMENTATION_SUMMARY.md** (detailed spec)
3. **Implement in 2-4 days:**
   - UserAuthIdentityRepository
   - AuthService (register + verify)
   - AuthController (2 endpoints)
   - AuthSeeder (admin + service account)
4. **Create PR** for review
5. **Test with BFF** in real mode
6. **Merge when ready**

---

## Success Criteria (Overall)

- [x] Gateway: AuthController with 3 endpoints working
- [x] Gateway: JWT RS256 signing and validation working
- [x] Gateway: Rate limiting implemented (5/min)
- [x] BFF: Internal auth endpoints with role mapping
- [x] BFF: Mock Inbox for testing
- [x] Documentation: IMPLEMENTATION_SUMMARY.md complete
- [x] Documentation: INBOX_TEAM_HANDOFF.md ready for handoff
- [ ] Inbox: Auth endpoints implemented (waiting)
- [ ] Integration: End-to-end test (register → login → /me)
- [ ] Deployment: Ready for production

---

## Questions or Issues?

Contact Dvori (@dvori on Slack)

**Status:** All LENS work complete. Awaiting Inbox team implementation.

🚀 **Ready for the next phase!**
