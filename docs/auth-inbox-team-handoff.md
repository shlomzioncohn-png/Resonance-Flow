# Inbox Team Handoff: Password-Based Auth Implementation

> **To:** Inbox Team  
> **From:** LENS Team (Dvori)  
> **Date:** October 7, 2026  
> **Status:** Ready for implementation  

---

## Executive Summary

LENS Gateway and BFF are **complete** with password-based authentication using JWT RS256. They currently use **mock Inbox** for testing.

**Your task:** Implement auth endpoints in Inbox so we can switch from mock to real user validation.

**No breaking changes** to Inbox schema - everything you need already exists.

---

## What's Already Done (LENS Side)

### ✅ Gateway (`teams/lens/gateway/`, port 8090)
- `POST /auth/register` - Register new user
- `POST /auth/login` - Login and receive JWT
- `GET /auth/me` - Get current user (requires Bearer token)
- Rate limiting (5 failed attempts/min per IP)
- JWT signing with RS256 (local keys)

### ✅ BFF (`teams/lens/bff/`, port 8082)
- `POST /internal/auth/register` - Internal endpoint (Gateway only)
- `POST /internal/auth/verify` - Internal endpoint (Gateway only)
- Role mapping (Inbox roles → LENS roles)
- **Currently uses mock Inbox**

### 🔲 Inbox (`teams/inbox/`, port 8080)
- Schema ready (users, user_auth_identities tables)
- **Needs implementation** (see below)

---

## What You Need to Do

### New Repository
```java
// File: UserAuthIdentityRepository.java
@Repository
public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentity, UUID> {
    Optional<UserAuthIdentity> findByAuthTypeAndIdentifier(String authType, String identifier);
    boolean existsByAuthTypeAndIdentifier(String authType, String identifier);
}
```

### New Service
```java
// File: AuthService.java
@Service
public class AuthService {
    
    public User register(String email, String password, String displayName, UUID actorId) {
        // - Check actor is SYSTEM_ADMIN
        // - Normalize email (trim, lowercase)
        // - Check email not already taken
        // - Hash password with BCrypt
        // - Create User (EMPLOYEE role, default tenant)
        // - Create UserAuthIdentity (password, email)
        // - Return user
    }
    
    public User verify(String login, String password, UUID actorId) {
        // - Check actor is SYSTEM_ADMIN
        // - Find UserAuthIdentity by (password, login)
        // - Compare password hash (constant-time to prevent timing attacks)
        // - Check user is ACTIVE
        // - Update last_login_at
        // - Return user
    }
}
```

### New Controller
```java
// File: AuthController.java
@RestController
@RequestMapping("/api/v1/auth")
@RequireRole(SYSTEM_ADMIN)  // All endpoints require this
public class AuthController {
    
    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(
            @RequestBody AuthRegisterRequestDto request,
            @RequestHeader("X-Actor-Id") UUID actorId) {
        // 201 Created with user
        // 409 if email taken
        // 400 if invalid
    }
    
    @PostMapping("/verify")
    public ResponseEntity<UserResponseDto> verify(
            @RequestBody AuthVerifyRequestDto request,
            @RequestHeader("X-Actor-Id") UUID actorId) {
        // 200 OK with user
        // 401 if invalid credentials
    }
}
```

### Seed Admin User
```java
// File: AuthSeeder.java
@Component
public class AuthSeeder {
    
    @PostConstruct
    public void seedAuthData() {
        // 1. If env vars INBOX_ADMIN_USERNAME + INBOX_ADMIN_PASSWORD:
        //    - Create SYSTEM_ADMIN user with username (no email)
        //    - Create UserAuthIdentity with password hash
        
        // 2. Create service account (UUID: 00000000-0000-0000-0000-000000000002)
        //    - SYSTEM_ADMIN role
        //    - No password (cannot login)
        //    - BFF will use this UUID as X-Actor-Id header
        
        // 3. Create default tenant (UUID: 00000000-0000-0000-0000-000000000001)
        //    - New users will be added to this tenant
    }
}
```

### Dependencies to Add
```xml
<!-- spring-security-crypto for BCrypt -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
    <version>6.2.0</version>
</dependency>

<!-- If not present: validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

---

## Key Security Requirements

### 1. Password Hashing
- **Use:** BCrypt (Spring Security's `BCryptPasswordEncoder`)
- **Never:** Store plaintext, return hash, log passwords
- **Rounds:** 10 (Spring default)

### 2. Timing Attack Prevention
```java
// Always run bcrypt even if user not found
// Use constant-time comparison to prevent timing leaks
String hashToCompare = identity != null 
    ? identity.getPasswordHash() 
    : getConstantHashForTiming();  // Dummy hash
boolean matches = passwordEncoder.matches(password, hashToCompare);
```

### 3. Authorization Check
- Both endpoints require `X-Actor-Id` header
- Only `SYSTEM_ADMIN` role can call them
- Service account (no password) cannot call these endpoints

### 4. Email/Username Normalization
```java
// Always normalize before storage and lookup
String normalized = login.trim().toLowerCase();
```

### 5. Error Messages
- **Never** distinguish between "user not found" and "wrong password"
- Always return: `"Invalid login or password"`
- This prevents username enumeration

---

## Test Cases to Cover

```java
// Register tests
✓ register valid user → 201 + UUID
✓ register duplicate email → 409
✓ register invalid email → 400
✓ register weak password → 400

// Verify tests
✓ verify correct credentials → 200 + user
✓ verify wrong password → 401
✓ verify non-existent user → 401 (same response!)
✓ verify inactive user → 401
✓ verify without X-Actor-Id → 403
✓ verify with non-ADMIN actor → 403

// Data integrity
✓ password hash never in response
✓ last_login_at updated on verify
✓ email uniqueness enforced
✓ username uniqueness enforced (admin only)
```

---

## Environment Variables

```bash
# For seeding admin user (optional)
INBOX_ADMIN_USERNAME=admin              # No @ symbol
INBOX_ADMIN_PASSWORD=securePassword123  # 8-72 chars

# For default resources
INBOX_DEFAULT_TENANT_ID=00000000-0000-0000-0000-000000000001
INBOX_SERVICE_ACCOUNT_ID=00000000-0000-0000-0000-000000000002
```

---

## Integration Points

### Request DTOs (You receive)
```json
{
  "email": "user@example.com",
  "password": "password123",
  "displayName": "John Doe"
}
```

### Response DTOs (You return)
```json
{
  "id": "20311a2c-a9e8-44ed-92b0-18665319f40a",
  "email": "user@example.com",
  "displayName": "John Doe",
  "role": "EMPLOYEE",
  "status": "ACTIVE",
  "tenantId": "00000000-0000-0000-0000-000000000001"
}
```

### Role Values You Can Return
- `SYSTEM_ADMIN` (mapped to LENS `MANAGER`)
- `EMPLOYEE` (mapped to LENS `EMPLOYEE`)
- `TENANT_ADMIN`, `TEAM_LEAD` (mapped to LENS `EMPLOYEE` for now)

---

## Database: What Already Exists

### `users` table
```sql
-- Already has these columns:
- id UUID PRIMARY KEY
- email VARCHAR UNIQUE
- username VARCHAR UNIQUE
- display_name VARCHAR
- status VARCHAR (ACTIVE, INACTIVE, SUSPENDED)
- role VARCHAR (SYSTEM_ADMIN, TENANT_ADMIN, TEAM_LEAD, EMPLOYEE)
- tenant_id UUID
- created_at TIMESTAMP
- updated_at TIMESTAMP
- last_login_at TIMESTAMP
```

### `user_auth_identities` table
```sql
-- Already exists, just need to use it:
- id UUID PRIMARY KEY
- user_id UUID FOREIGN KEY
- auth_type VARCHAR (password, oauth_google, etc)
- identifier VARCHAR (email or username)
- password_hash VARCHAR (bcrypt only for password auth type)
- created_at TIMESTAMP
- updated_at TIMESTAMP
- UNIQUE (auth_type, identifier)
```

**No migrations needed** - just add business logic.

---

## How BFF Will Call You

### Mock Phase (Current)
```
BFF (mock mode) → returns hardcoded test users
- emp@example.com / password → EMPLOYEE
- mgr@example.com / password → MANAGER
```

### Real Phase (After your implementation)
```
BFF (real mode) → calls your endpoints
POST http://inbox:8080/api/v1/auth/register
  X-Actor-Id: 00000000-0000-0000-0000-000000000002
  {email, password, displayName}

POST http://inbox:8080/api/v1/auth/verify
  X-Actor-Id: 00000000-0000-0000-0000-000000000002
  {login, password}
```

---

## Implementation Timeline Suggestion

### Phase 1: Core (2-3 days)
- [ ] Create `UserAuthIdentityRepository`
- [ ] Create `AuthService` with register + verify
- [ ] Create `AuthController` with endpoints
- [ ] Add BCrypt dependency
- [ ] Run unit tests from test checklist

### Phase 2: Seed & Integration (1 day)
- [ ] Create `AuthSeeder` for admin + service account
- [ ] Create default tenant
- [ ] Add env variables
- [ ] Test with BFF in mock mode

### Phase 3: Real Integration (1 day)
- [ ] BFF switches from mock to HTTP calls
- [ ] End-to-end test: register → login → GET /auth/me
- [ ] Test admin login with username

### Phase 4: Review & Documentation (1 day)
- [ ] Code review
- [ ] Update Inbox README
- [ ] Document any deviations from this spec

---

## Deviations: What to Flag

If you can't implement something exactly as specified:

1. **Password hashing library different?** - Must still be constant-time (bcrypt, scrypt, argon2)
2. **Admin username/email format?** - Username cannot contain `@` (to avoid conflicts)
3. **Seed location?** - Can be in Flyway migration or Spring component
4. **Response DTO fields?** - Must include `id, displayName, role` at minimum

**Please notify us before starting if you foresee changes.**

---

## Reference Documents

1. **IMPLEMENTATION_SUMMARY.md** (in repo root)
   - Full technical spec
   - All code examples
   - Testing checklist
   - Deployment notes

2. **BFF Mock Implementation** (reference only)
   - `teams/lens/bff/src/main/java/com/rnd/bff/client/InboxUsersClient.java`
   - Shows mock behavior for testing

3. **Gateway Code** (read-only, for understanding)
   - `teams/lens/gateway/src/main/java/com/rnd/gateway/auth/`
   - Shows how we call your endpoints

---

## Questions?

### Quick Clarifications
- Email/username format rules?
- Password constraints beyond 8-72 chars?
- Tenant assignment logic for new users?
- Role hierarchy for TENANT_ADMIN / TEAM_LEAD?

### Contact
- Slack: @dvori (Dvory)
- Shared Slack channel: #lens-auth
- GitHub discussions: Start a thread in repo

---

## Success Criteria (DoD)

- [ ] `UserAuthIdentityRepository` exists and queries work
- [ ] `AuthService.register()` creates user + identity correctly
- [ ] `AuthService.verify()` validates password with timing attack protection
- [ ] `/api/v1/auth/register` endpoint returns 201/409/400 correctly
- [ ] `/api/v1/auth/verify` endpoint returns 200/401/403 correctly
- [ ] Admin user seed creates `SYSTEM_ADMIN` with username from env
- [ ] Service account seed creates actor with no password
- [ ] All test cases from checklist pass
- [ ] No passwords in logs or responses
- [ ] BFF can switch from mock to real (HTTP) calls
- [ ] End-to-end test passes: register → login → GET /auth/me

---

## Next Steps

1. **Share this document** with your team
2. **Review IMPLEMENTATION_SUMMARY.md** (detailed spec)
3. **Schedule implementation** (2-4 days total)
4. **Create PR** when ready
5. **We'll test** with BFF
6. **Then merge** to main

**Timeline for integration:** After your PR merges, Gateway/BFF will switch from mock to real Inbox calls (no further LENS work needed).

---

**Good luck! Looking forward to the integration.** 🚀
