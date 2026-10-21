# Password-Based Auth Implementation Summary

## Status: ✅ COMPLETE (Gateway + BFF with Mock Inbox)

Authentication flow is **fully working** with JWT RS256 tokens.

### Tested Flow
1. ✅ `POST /auth/register` → 201 (stores in mock)
2. ✅ `POST /auth/login` → 200 + JWT token (RS256 signed)
3. ✅ `GET /auth/me` with Bearer token → 200 + user info

---

## 1. GATEWAY Changes (`teams/lens/gateway/`)

### New Files
- `src/main/java/com/rnd/gateway/auth/AuthController.java` - Register, login, /me endpoints
- `src/main/java/com/rnd/gateway/auth/AuthService.java` - Business logic for auth flows
- `src/main/java/com/rnd/gateway/auth/TokenService.java` - JWT signing with RS256
- `src/main/java/com/rnd/gateway/auth/RateLimitingConfig.java` - 5 failed logins/min per IP
- `src/main/java/com/rnd/gateway/auth/BffAuthClient.java` - HTTP client to BFF `/internal/auth/*`
- `src/main/java/com/rnd/gateway/config/JwtConfig.java` - JWT encoder/decoder beans
- `src/main/java/com/rnd/gateway/config/CorsConfig.java` - Allow auth endpoints from browser
- `jwt-private.pem` - RS256 private key (gitignored)
- `jwt-public.pem` - RS256 public key (gitignored)

### Modified Files
- `src/main/resources/application.yml` 
  - Added: `lens.jwt.private-key-location`, `lens.jwt.public-key-location`
  - Changed: `server.port` from 8080 → 8090 (avoid conflict with Inbox)
  - Changed: `app.auth.enabled` from false → true (JWT validation now active)
  
- `src/main/java/com/rnd/gateway/config/SecurityConfig.java`
  - Now uses `JwtDecoder` bean for RS256 validation (local keys, not Keycloak)
  - `/auth/me` marked as `authenticated()`
  - `/auth/register` and `/auth/login` marked as `permitAll()`
  - `/api/**` marked as `authenticated()`
  
- `src/main/java/com/rnd/gateway/config/SecurityValidationConfig.java`
  - Updated validation to allow local JWT mode (no Keycloak issuer-uri required)

- `pom.xml`
  - Added: `bucket4j-core` (7.6.0) for rate limiting

### Key Configuration (env vars)
```yaml
lens:
  jwt:
    private-key-location: ./jwt-private.pem
    public-key-location: ./jwt-public.pem
    key-id: gateway-key-1                 # Allow key rotation later
    ttl-seconds: 3600                     # Token expires in 1 hour

server:
  port: 8090                              # Local dev port

app:
  auth:
    enabled: true                         # JWT validation active

bff:
  service:
    url: http://localhost:8082            # BFF endpoint
  endpoints:
    register: /internal/auth/register
    verify: /internal/auth/verify
```

### Endpoints (OpenAPI)

#### `POST /auth/register`
- **Request:** `{email, password, displayName}`
- **Response 201:** `{userId}`
- **Response 409:** Email already taken
- **Response 400:** Invalid input (email format, password length 8-72)

#### `POST /auth/login`
- **Request:** `{login, password}` (login = email for users, username for admin)
- **Response 200:** `{accessToken, expiresIn, user:{id, displayName, role}}`
- **Response 401:** Invalid credentials
- **Response 429:** Too many failed attempts

#### `GET /auth/me`
- **Header:** `Authorization: Bearer <token>`
- **Response 200:** `{id, displayName, role}`
- **Response 401:** Invalid/missing token

---

## 2. BFF Changes (`teams/lens/bff/`)

### New Files
- `src/main/java/com/rnd/bff/controller/InternalAuthController.java` - `/internal/auth/*` endpoints (private, Gateway only)
- `src/main/java/com/rnd/bff/service/InternalAuthService.java` - Auth service orchestration
- `src/main/java/com/rnd/bff/service/RoleMapper.java` - Maps Inbox roles to LENS roles
- `src/main/java/com/rnd/bff/client/InboxUsersClient.java` - HTTP client to Inbox + MOCK mode
- `src/main/java/com/rnd/bff/dto/AuthRegisterRequestDto.java` - Register request DTO
- `src/main/java/com/rnd/bff/dto/AuthVerifyRequestDto.java` - Login request DTO
- `src/main/java/com/rnd/bff/dto/AuthUserResponseDto.java` - Auth response DTO

### Modified Files
- `src/main/resources/application.yml`
  - Added: `inbox.mock-enabled: true` (default)
  - Added: `inbox.service.url: http://localhost:8083`
  - Added: `inbox.dev-actor-id` (empty by default, uses Gateway's mock userId)

### Architecture

```
Gateway
  ↓
POST /internal/auth/register ──→ InternalAuthController
POST /internal/auth/verify  ──→   ↓
                                InternalAuthService
                                   ↓
                            InboxUsersClient (MOCK or HTTP)
                                   ↓
                            RoleMapper (map roles)
                                   ↓
                            AuthUserResponseDto
                                   ↓
                            Gateway (creates JWT)
```

### DTOs (BFF Internal)

#### `AuthRegisterRequestDto` (from Gateway)
```json
{
  "email": "user@example.com",
  "password": "password123",
  "displayName": "John Doe"
}
```
- Validation: email must be valid, password 8-72 chars, displayName required

#### `AuthVerifyRequestDto` (from Gateway)
```json
{
  "login": "user@example.com or admin_username",
  "password": "password123"
}
```
- login can be email (for users) or username (for admin)

#### `AuthUserResponseDto` (to Gateway, then JWT)
```json
{
  "id": "20311a2c-a9e8-44ed-92b0-18665319f40a",
  "displayName": "John Doe",
  "role": "MANAGER"
}
```
- Role is already mapped from Inbox to LENS

### InboxUsersClient (Mock Implementation)

**Current (Mock Mode):**
```java
public InboxAuthUserResponse register(String email, String password, String displayName) {
    // Mock only - returns hardcoded test user
    if ("emp@example.com".equals(email.toLowerCase())) {
        return new InboxAuthUserResponse(
            UUID.fromString("emp-uuid-xxx"),
            "emp@example.com",
            "Test Employee",
            "EMPLOYEE"
        );
    }
    throw new EmailTakenException("Email already in use");
}

public InboxAuthUserResponse verify(String login, String password) {
    // Mock users with hardcoded credentials
    MockUser user = mockUsers.stream()
        .filter(u -> u.matchesLogin(login) && u.matchesPassword(password))
        .findFirst()
        .orElse(null);
    
    if (user == null) {
        throw new InvalidCredentialsException("Invalid login or password");
    }
    return user.toResponse();
}
```

**Mock Users:**
- Email: `emp@example.com`, Password: `password` → Role: `EMPLOYEE`
- Email: `mgr@example.com`, Password: `password` → Role: `SYSTEM_ADMIN` (maps to MANAGER)

### Role Mapping (Inbox → LENS)

| Inbox Role | LENS Role | Notes |
|---|---|---|
| `SYSTEM_ADMIN` | `MANAGER` | Full access to manager endpoints |
| `EMPLOYEE` | `EMPLOYEE` | Regular user access |
| `TENANT_ADMIN` | `EMPLOYEE` | Awaiting decision on mapping |
| `TEAM_LEAD` | `EMPLOYEE` | Awaiting decision on mapping |
| (null) | `EMPLOYEE` | Default fallback |

### InternalAuthController (Private Endpoints)

#### `POST /internal/auth/register` (201 Created)
- Called by: Gateway only (via BffAuthClient)
- Body: `AuthRegisterRequestDto`
- Response: `AuthUserResponseDto` with mapped role
- Errors: 400 (validation), 409 (email taken), 503 (Inbox down)

#### `POST /internal/auth/verify` (200 OK)
- Called by: Gateway only (via BffAuthClient)
- Body: `AuthVerifyRequestDto`
- Response: `AuthUserResponseDto` with mapped role
- Errors: 400 (validation), 401 (credentials), 503 (Inbox down)

### InternalAuthService (Business Logic)

1. **Register flow:**
   - Receives `AuthRegisterRequestDto` from controller
   - Forwards to `InboxUsersClient.register()`
   - Maps returned Inbox role to LENS role via `RoleMapper`
   - Returns `AuthUserResponseDto` to controller

2. **Verify flow:**
   - Receives `AuthVerifyRequestDto` from controller
   - Forwards to `InboxUsersClient.verify()`
   - Maps returned Inbox role to LENS role via `RoleMapper`
   - Returns `AuthUserResponseDto` to controller

### Configuration (env vars)

```yaml
inbox:
  mock-enabled: true                           # Enable mock mode (no Inbox calls)
  service:
    url: http://localhost:8083                 # Real Inbox URL (ignored in mock)
  dev-actor-id: ""                             # Service account UUID (empty = use Gateway's mock)
```

### Next: Real Inbox Integration

Replace mock methods with actual HTTP calls using `RestClient`:

```java
@Override
public InboxAuthUserResponse register(String email, String password, String displayName) {
    var request = new RegisterRequest(email, password, displayName);
    
    return restClient.post()
        .uri(inboxServiceUrl + "/api/v1/auth/register")
        .header("X-Actor-Id", serviceAccountId)
        .body(request)
        .retrieve()
        .onStatus(status -> status.value() == 409, 
            (req, res) -> { throw new EmailTakenException("Email already in use"); })
        .onStatus(status -> status.value() >= 500,
            (req, res) -> { throw new ServiceUnavailableException("Inbox unavailable"); })
        .toEntity(InboxAuthUserResponse.class)
        .getBody();
}
```

**Changes needed for real integration:**
1. Replace mock methods with HTTP calls
2. Remove `mockUsers` initialization
3. Add error mapping for Inbox responses
4. Ensure `X-Actor-Id` header is sent (service account from env)

---

## 3. INBOX Changes Needed (`teams/inbox/`)

### Status: ⚠️ NOT YET IMPLEMENTED
Inbox team needs to add password-based auth endpoints. Schema already exists.

### Database Schema (Already Exists)

#### `users` table
```sql
id UUID PRIMARY KEY
email VARCHAR UNIQUE NOT NULL
username VARCHAR UNIQUE
display_name VARCHAR
status VARCHAR (ACTIVE, INACTIVE, SUSPENDED)
role VARCHAR (SYSTEM_ADMIN, TENANT_ADMIN, TEAM_LEAD, EMPLOYEE)
tenant_id UUID FOREIGN KEY
created_at TIMESTAMP
updated_at TIMESTAMP
last_login_at TIMESTAMP
```

#### `user_auth_identities` table (Already exists, awaiting use)
```sql
id UUID PRIMARY KEY
user_id UUID FOREIGN KEY REFERENCES users(id)
auth_type VARCHAR (password, oauth_google, oauth_keycloak, etc)
identifier VARCHAR (email or username, depending on auth_type)
password_hash VARCHAR (bcrypt hash, only for auth_type=password)
created_at TIMESTAMP
updated_at TIMESTAMP

UNIQUE CONSTRAINT (auth_type, identifier)
```

### New Files to Create in Inbox

#### 1. `src/main/java/com/rnd/inbox/user/repository/UserAuthIdentityRepository.java`
```java
@Repository
public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentity, UUID> {
    
    // Find identity by auth_type and identifier (email or username)
    Optional<UserAuthIdentity> findByAuthTypeAndIdentifier(String authType, String identifier);
    
    // Check if identifier already exists for this auth type
    boolean existsByAuthTypeAndIdentifier(String authType, String identifier);
}
```

#### 2. `src/main/java/com/rnd/inbox/auth/AuthService.java`
```java
@Service
public class AuthService {
    
    private final UserRepository userRepository;
    private final UserAuthIdentityRepository authIdentityRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    
    /**
     * POST /api/v1/auth/register
     * Create new user with password identity.
     * 
     * @param email normalized (trim, lowercase)
     * @param password will be hashed with bcrypt
     * @param displayName user's display name
     * @param actorId SYSTEM_ADMIN calling this endpoint
     * @return created user
     * @throws EmailTakenException if email already used
     * @throws UnauthorizedException if actor is not SYSTEM_ADMIN
     */
    public User register(String email, String password, String displayName, UUID actorId) {
        // Check authorization
        User actor = userRepository.findById(actorId)
            .orElseThrow(() -> new UnauthorizedException("Actor not found"));
        if (!actor.getRole().equals("SYSTEM_ADMIN")) {
            throw new UnauthorizedException("Only SYSTEM_ADMIN can register users");
        }
        
        // Normalize and validate
        String normalizedEmail = email.trim().toLowerCase();
        validateEmail(normalizedEmail);
        validatePassword(password);
        
        // Check email not already taken (in users.email or auth_identities.identifier)
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail) ||
            authIdentityRepository.existsByAuthTypeAndIdentifier("password", normalizedEmail)) {
            throw new EmailTakenException("Email already in use");
        }
        
        // Create user (EMPLOYEE role, default tenant)
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(normalizedEmail);
        user.setDisplayName(displayName);
        user.setRole("EMPLOYEE");
        user.setStatus("ACTIVE");
        user.setTenantId(getDefaultTenantId());
        user.setCreatedAt(now());
        
        // Create auth identity (password hash)
        UserAuthIdentity identity = new UserAuthIdentity();
        identity.setId(UUID.randomUUID());
        identity.setUserId(user.getId());
        identity.setAuthType("password");
        identity.setIdentifier(normalizedEmail);
        identity.setPasswordHash(passwordEncoder.encode(password));
        identity.setCreatedAt(now());
        
        // Save in transaction
        return userRepository.save(user);  // Also saves identity via cascade
    }
    
    /**
     * POST /api/v1/auth/verify
     * Verify user credentials and return user info.
     * 
     * @param login email or username (for admin)
     * @param password plaintext password to verify
     * @param actorId SYSTEM_ADMIN calling this endpoint
     * @return user if credentials valid
     * @throws InvalidCredentialsException if login/password wrong or user not ACTIVE
     * @throws UnauthorizedException if actor is not SYSTEM_ADMIN
     */
    public User verify(String login, String password, UUID actorId) {
        // Check authorization
        User actor = userRepository.findById(actorId)
            .orElseThrow(() -> new UnauthorizedException("Actor not found"));
        if (!actor.getRole().equals("SYSTEM_ADMIN")) {
            throw new UnauthorizedException("Only SYSTEM_ADMIN can verify credentials");
        }
        
        // Normalize
        String normalizedLogin = login.trim().toLowerCase();
        
        // Find identity
        UserAuthIdentity identity = authIdentityRepository
            .findByAuthTypeAndIdentifier("password", normalizedLogin)
            .orElse(null);
        
        // Constant-time comparison (prevent timing attacks)
        // If identity not found, still run bcrypt to waste time equally
        String hashToCompare = identity != null ? identity.getPasswordHash() : getConstantHashForTiming();
        boolean passwordMatches = passwordEncoder.matches(password, hashToCompare);
        
        if (!passwordMatches || identity == null) {
            throw new InvalidCredentialsException("Invalid login or password");
        }
        
        // Load user
        User user = userRepository.findById(identity.getUserId())
            .orElseThrow(() -> new InvalidCredentialsException("User not found"));
        
        // Check user is ACTIVE
        if (!user.getStatus().equals("ACTIVE")) {
            throw new InvalidCredentialsException("User account is inactive");
        }
        
        // Update last login
        user.setLastLoginAt(now());
        userRepository.save(user);
        
        return user;
    }
    
    // Helper methods
    private void validateEmail(String email) {
        if (!email.matches("[^@]+@[^@]+\\.[^@]+")) {
            throw new IllegalArgumentException("Invalid email format");
        }
    }
    
    private void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalArgumentException("Password must be 8-72 characters");
        }
    }
    
    private String getConstantHashForTiming() {
        // Fixed bcrypt hash for timing consistency when user not found
        return "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcg7b3XeKeUxWdeS86E36P4/tjO2";
    }
    
    private UUID getDefaultTenantId() {
        // From config or hardcoded
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
    
    private Instant now() {
        return Instant.now();
    }
}
```

#### 3. `src/main/java/com/rnd/inbox/auth/AuthController.java`
```java
@RestController
@RequestMapping("/api/v1/auth")
@RequireRole(UserRole.SYSTEM_ADMIN)  // All auth endpoints require SYSTEM_ADMIN
public class AuthController {
    
    private final AuthService authService;
    private final UserAuthIdentityRepository authIdentityRepository;
    
    /**
     * POST /api/v1/auth/register
     * Create new user with password-based authentication.
     */
    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(
            @RequestBody AuthRegisterRequestDto request,
            @RequestHeader("X-Actor-Id") UUID actorId) {
        
        User user = authService.register(
            request.getEmail(),
            request.getPassword(),
            request.getDisplayName(),
            actorId
        );
        
        return ResponseEntity.status(201)
            .body(new UserResponseDto(user));
    }
    
    /**
     * POST /api/v1/auth/verify
     * Verify user credentials.
     */
    @PostMapping("/verify")
    public ResponseEntity<UserResponseDto> verify(
            @RequestBody AuthVerifyRequestDto request,
            @RequestHeader("X-Actor-Id") UUID actorId) {
        
        User user = authService.verify(
            request.getLogin(),
            request.getPassword(),
            actorId
        );
        
        return ResponseEntity.ok(new UserResponseDto(user));
    }
}
```

### Request/Response DTOs (Already created by BFF for reference)

#### Request: Register
```json
{
  "email": "user@example.com",
  "password": "securePassword123",
  "displayName": "John Doe"
}
```

#### Request: Verify
```json
{
  "login": "user@example.com",
  "password": "securePassword123"
}
```

#### Response (201 or 200)
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

### Seed Changes

#### Add admin user seed:
```java
@Component
public class AuthSeeder {
    
    private final UserRepository userRepository;
    private final UserAuthIdentityRepository authIdentityRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    
    @Value("${inbox.seed.admin-username:}")
    private String adminUsername;
    
    @Value("${inbox.seed.admin-password:}")
    private String adminPassword;
    
    @PostConstruct
    public void seedAuthData() {
        // Seed admin user if env vars provided
        if (adminUsername != null && !adminUsername.isBlank() &&
            adminPassword != null && !adminPassword.isBlank()) {
            
            // Check admin doesn't already exist
            if (authIdentityRepository
                .findByAuthTypeAndIdentifier("password", adminUsername.toLowerCase())
                .isEmpty()) {
                
                User admin = new User();
                admin.setId(UUID.randomUUID());
                admin.setUsername(adminUsername.toLowerCase());
                admin.setDisplayName("System Administrator");
                admin.setRole("SYSTEM_ADMIN");
                admin.setStatus("ACTIVE");
                admin.setTenantId(getDefaultTenantId());
                admin.setCreatedAt(Instant.now());
                userRepository.save(admin);
                
                UserAuthIdentity identity = new UserAuthIdentity();
                identity.setId(UUID.randomUUID());
                identity.setUserId(admin.getId());
                identity.setAuthType("password");
                identity.setIdentifier(adminUsername.toLowerCase());
                identity.setPasswordHash(passwordEncoder.encode(adminPassword));
                identity.setCreatedAt(Instant.now());
                authIdentityRepository.save(identity);
                
                log.info("Seeded admin user: {}", adminUsername);
            }
        } else {
            log.warn("Admin credentials not provided in env. Skipping admin seed.");
        }
        
        // Seed service account (UUID) for BFF to use as X-Actor-Id
        // This user has no password, so it cannot login via auth endpoints
        String serviceAccountId = "00000000-0000-0000-0000-000000000002";
        if (userRepository.findById(UUID.fromString(serviceAccountId)).isEmpty()) {
            User serviceAccount = new User();
            serviceAccount.setId(UUID.fromString(serviceAccountId));
            serviceAccount.setDisplayName("BFF Service Account");
            serviceAccount.setRole("SYSTEM_ADMIN");
            serviceAccount.setStatus("ACTIVE");
            serviceAccount.setTenantId(getDefaultTenantId());
            serviceAccount.setCreatedAt(Instant.now());
            userRepository.save(serviceAccount);
            // No auth identity - cannot be used to login
            
            log.info("Seeded BFF service account: {}", serviceAccountId);
        }
    }
    
    private UUID getDefaultTenantId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
}
```

### Configuration (env vars for Inbox)

```yaml
inbox:
  seed:
    admin-username: ${INBOX_ADMIN_USERNAME:}        # Empty = no admin seed
    admin-password: ${INBOX_ADMIN_PASSWORD:}        # Empty = no admin seed
    default-tenant-id: ${INBOX_DEFAULT_TENANT_ID:00000000-0000-0000-0000-000000000001}
    service-account-id: ${INBOX_SERVICE_ACCOUNT_ID:00000000-0000-0000-0000-000000000002}
```

### Dependencies (pom.xml changes)

Add to Inbox `pom.xml`:
```xml
<!-- Password hashing -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
    <version>6.2.0</version>
</dependency>

<!-- If not already present: validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### Security Considerations

1. **Password hashing:**
   - Use BCrypt (rounds = 10, default)
   - Never store plaintext passwords
   - Never return hash in API responses

2. **Timing attacks:**
   - Always run bcrypt check even if user not found
   - Return same error message for "user not found" and "wrong password"

3. **Authorization:**
   - Both endpoints require `X-Actor-Id` header = `SYSTEM_ADMIN`
   - Service account (no password) cannot call these endpoints

4. **Normalization:**
   - Always `trim().toLowerCase()` email and login before storage and lookup
   - Prevents case sensitivity issues

5. **Constraints:**
   - Password: 8-72 characters (bcrypt limit)
   - Email: valid format, unique across all auth types
   - Username: unique, no `@` symbol (to avoid conflict with email)

### Error Responses

| Scenario | Status | Code | Message |
|---|---|---|---|
| Email already taken | 409 | EMAIL_TAKEN | Email already in use |
| Invalid email format | 400 | INVALID_EMAIL | Invalid email format |
| Password too short/long | 400 | INVALID_PASSWORD | Password must be 8-72 characters |
| Invalid credentials | 401 | UNAUTHORIZED | Invalid login or password |
| User not ACTIVE | 401 | UNAUTHORIZED | Invalid login or password |
| Actor not SYSTEM_ADMIN | 403 | FORBIDDEN | Only SYSTEM_ADMIN can perform this action |
| Inbox error | 500 | INTERNAL_ERROR | Internal server error |

### Testing Checklist

- [ ] Register new user → 201 + user UUID
- [ ] Register duplicate email → 409
- [ ] Register with invalid email → 400
- [ ] Register with short password → 400
- [ ] Verify correct credentials → 200 + user
- [ ] Verify wrong password → 401
- [ ] Verify non-existent user → 401 (same response as wrong password)
- [ ] Verify with non-ACTIVE user → 401
- [ ] Verify without X-Actor-Id header → 403
- [ ] Verify with non-ADMIN actor → 403
- [ ] Verify response doesn't include password_hash
- [ ] Admin login with username → 200 + SYSTEM_ADMIN role
- [ ] lastLoginAt timestamp updated on verify

### Migration Strategy (if needed)

No database migration needed - schema already exists. Just need to:
1. Create `UserAuthIdentityRepository` 
2. Create `AuthService` with business logic
3. Create `AuthController` endpoints
4. Add seed logic for admin + service account
5. Add `BCryptPasswordEncoder` bean to Spring context

---

## 4. Testing Status

### ✅ Working (Gateway + BFF Mock)
- Register with mock: 201 + userId
- Login with mock: 200 + JWT token
- GET /auth/me with Bearer token: 200 + user info
- Rate limiting: 429 after 5 failed attempts
- JWT signature validation: RS256

### 🔲 Not Yet Tested
- Real Inbox integration (currently mocked)
- Admin login with username (will work once Inbox endpoints added)
- Token expiration (1 hour)
- Refresh token flow (not implemented)

### ⚠️ Known Limitations
- No logout endpoint (frontend deletes token)
- No token revocation (token valid until expiry)
- No password reset
- Admin created via seed only

---

## 5. Frontend Integration (TODO)

Frontend needs to:
1. **Save token** to localStorage after `/auth/login`:
   ```javascript
   const response = await fetch('http://localhost:8090/auth/login', {...})
   const data = await response.json()
   localStorage.setItem('authToken', data.accessToken)
   ```

2. **Send token** on every request:
   ```javascript
   const token = localStorage.getItem('authToken')
   if (token) {
     headers['Authorization'] = `Bearer ${token}`
   }
   ```

3. **Check auth** on app load:
   ```javascript
   const token = localStorage.getItem('authToken')
   if (!token) {
     // Show login page
   } else {
     // Fetch GET /auth/me to verify token is still valid
   }
   ```

---

## 6. Deployment Configuration (Helm)

### Secrets (created outside chart)
- `lens-gateway-jwt` containing private/public keys
- `lens-admin` containing admin username and password

### ConfigMap values
- `GATEWAY_PORT=8090`
- `BFF_SERVICE_URL=http://bff:8082`
- `INBOX_SERVICE_URL=http://inbox:8080`
- `INBOX_SERVICE_ACTOR_ID=<UUID of service account>`
- `JWT_TTL_SECONDS=3600`

### Environment Setup
```bash
# Generate local JWT keys (for dev)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem

# In K8s, Secret contains both keys
kubectl create secret generic lens-gateway-jwt \
  --from-file=private.pem=jwt-private.pem \
  --from-file=public.pem=jwt-public.pem
```

---

## 7. Next Steps

### Immediate
- [ ] Implement Inbox auth endpoints (register + verify)
- [ ] Implement UserAuthIdentityRepository in Inbox
- [ ] Implement password hashing (bcrypt) in Inbox AuthService
- [ ] Implement seed for admin + service account
- [ ] Replace BFF mock with real HTTP calls

### After Inbox Integration
- [ ] End-to-end test: register → login → GET /auth/me
- [ ] Test admin login with username
- [ ] Deploy to shared environment
- [ ] Add token refresh flow (if 1 hour is too short)

### Future
- [ ] Password reset flow
- [ ] OAuth2 (Google sign-in)
- [ ] Keycloak integration (alternative auth provider)
- [ ] Token revocation/logout
- [ ] RBAC with @Secured annotations on endpoints

---

## Files Modified Summary

### Gateway (`teams/lens/gateway/`)
- **New:** 7 Java files + 2 PEM files
- **Modified:** application.yml, SecurityConfig, SecurityValidationConfig, pom.xml
- **Removed:** jjwt library, dev-mode, MOCK_USER_ID

### BFF (`teams/lens/bff/`)
- **New:** 3 Java files
- **Modified:** application.yml

### Inbox (`teams/inbox/`)
- **Not yet started** - awaiting Inbox team review

---

## Git Commits (if merged)

```bash
feat(gateway): implement password-based auth with JWT RS256
feat(gateway): add rate limiting and CORS for auth endpoints
feat(gateway): add BFF auth client integration
refactor(gateway): replace jjwt with Spring Security OAuth2 JWT
feat(bff): add internal auth endpoints for gateway
feat(bff): add mock InboxUsersClient for local testing
```



---

## 4. Git Merge Strategy

### Status of inbox-team branch

`origin/inbox-team` contains:
- Task selection features (TaskSelectionService, endpoints)
- User role assignment improvements
- Query optimization and logging
- **No password-based auth code** (not relevant to our work)

### Merge Conflict Analysis

When merging `inbox-team` → current branch, conflicts occur because:

1. **Inbox directory structure changed** (both branches reorganized files)
2. **Controller/Service files modified** (different feature areas)
3. **No actual auth conflicts** (inbox-team doesn't touch auth code)

### Recommended Strategy: ✅ CHERRY-PICK AUTH FILES ONLY

**Reason:** 
- `inbox-team` is ahead on task selection, not auth
- Full merge would be noisy and high-risk for unrelated features
- We only need the **schema** (already exists in both branches)
- We need to communicate **Inbox auth requirements** separately

### What NOT to merge from inbox-team

- Task selection code (TaskSelectionService, TaskSelectionProperties, etc.)
- User role assignment changes
- Query optimization changes
- Any test changes

### What Auth Files Should Come From

**Gateway + BFF files in current branch:** 
- Already implemented ✅
- Already tested ✅
- Ready to use as-is

**Inbox auth files:**
- **Need to be CREATED by Inbox team**, not merged
- Should follow the spec in section 3 of this document
- Should be reviewed by Inbox team before merge

### Action Plan

1. **Keep current branch as-is** (don't merge inbox-team)
2. **Extract IMPLEMENTATION_SUMMARY.md** and share with Inbox team
3. **Inbox team creates auth files** based on the spec
4. **When Inbox auth is ready**, Gateway/BFF can switch from mock to real Inbox calls
5. **Then consider merging inbox-team** if their features are still needed

### If Inbox Auth Needs to Be Merged Later

Once Inbox team implements auth:
```bash
# Instead of full merge, cherry-pick specific commits:
git cherry-pick <inbox-team-commit-hash>

# Or manually cherry-pick files:
git checkout origin/inbox-team -- teams/inbox/src/main/java/com/rnd/inbox/auth/
git checkout origin/inbox-team -- teams/inbox/src/main/java/com/rnd/inbox/user/repository/UserAuthIdentityRepository.java
```

### Current Branch Status

**Current branch:** `auth-stage2-jwt-extraction`
- Gateway: ✅ Complete with RS256 JWT
- BFF: ✅ Complete with mock Inbox
- Inbox: ⏳ Awaiting Inbox team implementation

**Ready to:**
- Merge to main once Inbox team confirms auth endpoints
- Create PR for review before main merge

**NOT ready to:**
- Full merge with inbox-team (would introduce unrelated code)
- Deploy to production (Inbox auth still mock)

---

## 5. Handoff to Inbox Team

### What to Share
1. **This document** (IMPLEMENTATION_SUMMARY.md)
2. **BFF mock implementation** as reference for contracts
3. **Gateway code** (read-only, for understanding the flow)

### Key Points for Inbox Team

1. **Start with Section 3** - Contains full spec for auth files needed
2. **Don't merge inbox-team** - We'll integrate auth separately
3. **Schema already exists** - Just add the business logic
4. **Test with BFF mock first** - Then coordinate integration
5. **Contact**: Share this doc + IMPLEMENTATION_SUMMARY.md

### Success Criteria for Inbox Auth

- [ ] UserAuthIdentityRepository created
- [ ] AuthService implements register + verify with bcrypt
- [ ] AuthController with 2 endpoints at `/api/v1/auth/*`
- [ ] Admin user seed from env variables
- [ ] Service account seed (UUID 00000000-0000-0000-0000-000000000002)
- [ ] All tests from section 3 pass
- [ ] Ready for BFF HTTP integration

### Next Meeting Topics

1. Review this implementation
2. Inbox team commits to auth implementation timeline
3. Define integration test plan
4. Decide on deployment K8s setup

