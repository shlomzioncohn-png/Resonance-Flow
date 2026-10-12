# Inbox Auth Implementation Spec

**עבור צוותInbox: בדיוק איך לבנות את שתי ה-endpoints**

---

## 🎯 Overview

Inbox צריך להוסיף שני endpoints בלבד:
- `POST /api/v1/auth/register` — רישום משתמש חדש
- `POST /api/v1/auth/verify` — אימות סיסמה

**שים לב:** הכל מנוהל בטרנזקציה אחת. אם אחד נכשל, הכל מתרולל.

---

## 1️⃣ UserAuthIdentityRepository

**קובץ:** `teams/inbox/src/main/java/com/rnd/inbox/user/repository/UserAuthIdentityRepository.java`

```java
package com.rnd.inbox.user.repository;

import com.rnd.inbox.user.entity.UserAuthIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentity, UUID> {
    
    // מחפש identity לפי auth_type ו-identifier (email או username)
    Optional<UserAuthIdentity> findByAuthTypeAndIdentifier(String authType, String identifier);
    
    // בודק אם identity כבר קיים
    boolean existsByAuthTypeAndIdentifier(String authType, String identifier);
}
```

---

## 2️⃣ AuthService

**קובץ:** `teams/inbox/src/main/java/com/rnd/inbox/auth/AuthService.java`

```java
package com.rnd.inbox.auth;

import com.rnd.inbox.user.entity.User;
import com.rnd.inbox.user.entity.UserAuthIdentity;
import com.rnd.inbox.user.repository.UserAuthIdentityRepository;
import com.rnd.inbox.user.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final String CONSTANT_HASH_FOR_TIMING = "$2a$10$N9qo8uLOickgxk2Z2.1ZOU5Ug.E8Q.2g.WnZRCq7B.f.QwZVTp2Bm";
    private static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    private final UserRepository userRepository;
    private final UserAuthIdentityRepository authIdentityRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       UserAuthIdentityRepository authIdentityRepository,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authIdentityRepository = authIdentityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * POST /api/v1/auth/register
     * רישום משתמש חדש עם סיסמה
     */
    @Transactional
    public User register(String email, String password, String displayName, UUID actorId) {
        logger.info("Attempting to register user with email: {}", email);

        // 1. בדיקת הרשאות: רק SYSTEM_ADMIN יכול ליצור משתמשים
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthorizedException("Actor not found"));
        
        if (!"SYSTEM_ADMIN".equals(actor.getRole())) {
            logger.warn("Non-admin actor {} attempted to register user", actorId);
            throw new UnauthorizedException("Only SYSTEM_ADMIN can register users");
        }

        // 2. נרמול וולידציה
        String normalizedEmail = email.trim().toLowerCase();
        validateEmail(normalizedEmail);
        validatePassword(password);

        // 3. בדיקה שה-email לא תפוס
        if (userRepository.existsByEmail(normalizedEmail) ||
            authIdentityRepository.existsByAuthTypeAndIdentifier("password", normalizedEmail)) {
            logger.warn("Email already taken: {}", normalizedEmail);
            throw new EmailTakenException("Email already in use");
        }

        // 4. יצירת משתמש (EMPLOYEE, ACTIVE, בdefault tenant)
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(normalizedEmail);
        user.setDisplayName(displayName);
        user.setRole("EMPLOYEE");
        user.setStatus("ACTIVE");
        user.setTenantId(UUID.fromString(DEFAULT_TENANT_ID));
        user.setCreatedAt(Instant.now());

        // 5. יצירת auth identity (password hash)
        UserAuthIdentity identity = new UserAuthIdentity();
        identity.setId(UUID.randomUUID());
        identity.setUserId(user.getId());
        identity.setAuthType("password");
        identity.setIdentifier(normalizedEmail);  // ← שמור email מנורמל
        identity.setPasswordHash(passwordEncoder.encode(password));  // ← bcrypt
        identity.setCreatedAt(Instant.now());

        // 6. שמור בטרנזקציה אחת
        user = userRepository.save(user);
        authIdentityRepository.save(identity);

        logger.info("User registered successfully: {}", user.getId());
        return user;
    }

    /**
     * POST /api/v1/auth/verify
     * אימות סיסמה וחזרה משתמש
     */
    @Transactional
    public User verify(String login, String password, UUID actorId) {
        logger.info("Attempting to verify credentials for login: {}", login);

        // 1. בדיקת הרשאות
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UnauthorizedException("Actor not found"));
        
        if (!"SYSTEM_ADMIN".equals(actor.getRole())) {
            logger.warn("Non-admin actor {} attempted to verify credentials", actorId);
            throw new UnauthorizedException("Only SYSTEM_ADMIN can verify credentials");
        }

        // 2. נרמול
        String normalizedLogin = login.trim().toLowerCase();

        // 3. חיפוש identity
        UserAuthIdentity identity = authIdentityRepository
                .findByAuthTypeAndIdentifier("password", normalizedLogin)
                .orElse(null);

        // 4. בדיקה בטוחה מפני timing attacks
        // אם user לא נמצא, עדיין מריצים bcrypt מול hash קבוע כדי לא לחשוף זמן תגובה
        String hashToCompare = (identity != null) 
                ? identity.getPasswordHash() 
                : CONSTANT_HASH_FOR_TIMING;
        
        boolean passwordMatches = passwordEncoder.matches(password, hashToCompare);

        if (!passwordMatches || identity == null) {
            logger.warn("Invalid credentials for login: {}", normalizedLogin);
            throw new InvalidCredentialsException("Invalid login or password");
        }

        // 5. טען את המשתמש
        User user = userRepository.findById(identity.getUserId())
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        // 6. בדיקה שה-user פעיל
        if (!"ACTIVE".equals(user.getStatus())) {
            logger.warn("Attempted login with non-active user: {}", user.getId());
            throw new InvalidCredentialsException("Invalid login or password");  // אותה הודעה בטוח
        }

        // 7. עדכן last_login_at
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        logger.info("User verified successfully: {}", user.getId());
        return user;
    }

    // ======== Helper Methods ========

    private void validateEmail(String email) {
        // שימוש ב-Jakarta validator או regex פשוט
        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new IllegalArgumentException("Invalid email format");
        }
    }

    private void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalArgumentException("Password must be 8-72 characters");
        }
    }
}

// ======== Exceptions ========

class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}

class EmailTakenException extends RuntimeException {
    public EmailTakenException(String message) {
        super(message);
    }
}

class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
```

---

## 3️⃣ AuthController

**קובץ:** `teams/inbox/src/main/java/com/rnd/inbox/auth/AuthController.java`

```java
package com.rnd.inbox.auth;

import com.rnd.inbox.user.entity.User;
import com.rnd.inbox.auth.dto.AuthRegisterRequestDto;
import com.rnd.inbox.auth.dto.AuthVerifyRequestDto;
import com.rnd.inbox.auth.dto.UserResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Auth", description = "User registration and login")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/v1/auth/register
     */
    @Operation(summary = "Register a new user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered"),
            @ApiResponse(responseCode = "400", description = "Invalid input"),
            @ApiResponse(responseCode = "409", description = "Email already taken"),
            @ApiResponse(responseCode = "403", description = "Not authorized")
    })
    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(
            @Valid @RequestBody AuthRegisterRequestDto request,
            @RequestHeader("X-Actor-Id") UUID actorId) {

        User user = authService.register(
                request.getEmail(),
                request.getPassword(),
                request.getDisplayName(),
                actorId
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new UserResponseDto(user));
    }

    /**
     * POST /api/v1/auth/verify
     */
    @Operation(summary = "Verify user credentials")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credentials verified"),
            @ApiResponse(responseCode = "400", description = "Invalid input"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "403", description = "Not authorized")
    })
    @PostMapping("/verify")
    public ResponseEntity<UserResponseDto> verify(
            @Valid @RequestBody AuthVerifyRequestDto request,
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

---

## 4️⃣ DTOs

### `AuthRegisterRequestDto`
```java
package com.rnd.inbox.auth.dto;

import jakarta.validation.constraints.*;

public class AuthRegisterRequestDto {
    
    @Email(message = "Email should be valid")
    private String email;
    
    @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
    private String password;
    
    @NotBlank(message = "Display name is required")
    private String displayName;

    // Getters, setters...
}
```

### `AuthVerifyRequestDto`
```java
package com.rnd.inbox.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthVerifyRequestDto {
    
    @NotBlank(message = "Login is required")
    private String login;
    
    @NotBlank(message = "Password is required")
    private String password;

    // Getters, setters...
}
```

### `UserResponseDto`
```java
package com.rnd.inbox.auth.dto;

import com.rnd.inbox.user.entity.User;
import java.util.UUID;

public class UserResponseDto {
    
    private UUID id;
    private String email;
    private String displayName;
    private String role;
    private String status;
    private UUID tenantId;

    public UserResponseDto(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
        this.status = user.getStatus();
        this.tenantId = user.getTenantId();
    }

    // Getters...
}
```

---

## 5️⃣ Seed Data

**קובץ:** `teams/inbox/src/main/java/com/rnd/inbox/auth/AuthSeeder.java`

```java
package com.rnd.inbox.auth;

import com.rnd.inbox.user.entity.User;
import com.rnd.inbox.user.entity.UserAuthIdentity;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.repository.UserAuthIdentityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.UUID;

@Component
public class AuthSeeder {

    private static final Logger logger = LoggerFactory.getLogger(AuthSeeder.class);
    private static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SERVICE_ACCOUNT_ID = "00000000-0000-0000-0000-000000000002";

    private final UserRepository userRepository;
    private final UserAuthIdentityRepository authIdentityRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${inbox.seed.admin-username:}")
    private String adminUsername;

    @Value("${inbox.seed.admin-password:}")
    private String adminPassword;

    public AuthSeeder(UserRepository userRepository,
                      UserAuthIdentityRepository authIdentityRepository,
                      BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authIdentityRepository = authIdentityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void seedAuthData() {
        logger.info("Starting auth seed...");

        // 1. Seed Admin User (מenv vars)
        if (adminUsername != null && !adminUsername.isBlank() &&
            adminPassword != null && !adminPassword.isBlank()) {

            if (authIdentityRepository
                    .findByAuthTypeAndIdentifier("password", adminUsername.toLowerCase())
                    .isEmpty()) {

                User admin = new User();
                admin.setId(UUID.randomUUID());
                admin.setUsername(adminUsername.toLowerCase());
                admin.setDisplayName("System Administrator");
                admin.setRole("SYSTEM_ADMIN");
                admin.setStatus("ACTIVE");
                admin.setTenantId(UUID.fromString(DEFAULT_TENANT_ID));
                admin.setCreatedAt(Instant.now());
                userRepository.save(admin);

                UserAuthIdentity adminIdentity = new UserAuthIdentity();
                adminIdentity.setId(UUID.randomUUID());
                adminIdentity.setUserId(admin.getId());
                adminIdentity.setAuthType("password");
                adminIdentity.setIdentifier(adminUsername.toLowerCase());
                adminIdentity.setPasswordHash(passwordEncoder.encode(adminPassword));
                adminIdentity.setCreatedAt(Instant.now());
                authIdentityRepository.save(adminIdentity);

                logger.info("Seeded admin user: {}", adminUsername);
            }
        } else {
            logger.warn("Admin credentials not provided in env. Skipping admin seed.");
        }

        // 2. Seed Service Account (לBFF)
        if (userRepository.findById(UUID.fromString(SERVICE_ACCOUNT_ID)).isEmpty()) {
            User serviceAccount = new User();
            serviceAccount.setId(UUID.fromString(SERVICE_ACCOUNT_ID));
            serviceAccount.setDisplayName("BFF Service Account");
            serviceAccount.setRole("SYSTEM_ADMIN");
            serviceAccount.setStatus("ACTIVE");
            serviceAccount.setTenantId(UUID.fromString(DEFAULT_TENANT_ID));
            serviceAccount.setCreatedAt(Instant.now());
            userRepository.save(serviceAccount);
            // ← אין identity — לא יכול להיכנס דרך login

            logger.info("Seeded BFF service account");
        }

        logger.info("Auth seed completed");
    }
}
```

---

## 6️⃣ pom.xml Dependencies

הוסף לInbox:

```xml
<!-- Password hashing -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
    <version>6.2.0</version>
</dependency>

<!-- Validation (אם עדיין אין) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

---

## 7️⃣ application.yml Config

```yaml
inbox:
  seed:
    admin-username: ${INBOX_ADMIN_USERNAME:}
    admin-password: ${INBOX_ADMIN_PASSWORD:}
    default-tenant-id: ${INBOX_DEFAULT_TENANT_ID:00000000-0000-0000-0000-000000000001}
    service-account-id: ${INBOX_SERVICE_ACCOUNT_ID:00000000-0000-0000-0000-000000000002}
```

---

## ✅ Testing Checklist

- [ ] Register new user → 201 + userId
- [ ] Register duplicate email → 409
- [ ] Register invalid email → 400
- [ ] Register short password → 400
- [ ] Verify correct credentials → 200 + user
- [ ] Verify wrong password → 401
- [ ] Verify non-existent login → 401 (same response)
- [ ] Verify non-ACTIVE user → 401
- [ ] Verify without X-Actor-Id header → 403
- [ ] Verify with non-admin actor → 403
- [ ] Admin login with username → 200 + SYSTEM_ADMIN role
- [ ] `last_login_at` updated on verify ✓
- [ ] Password hash never exposed in response ✓

---

## 🔗 Integration Point

כשהקובץ הזה מוכן, BFF יוכל להחליף:

```java
// FROM mock:
InboxUsersClient.register() {
    return mockRegister(email, password, displayName);
}

// TO real HTTP:
InboxUsersClient.register() {
    return restClient.post()
        .uri("http://inbox:8083/api/v1/auth/register")
        .header("X-Actor-Id", "00000000-0000-0000-0000-000000000002")
        .body(new RegisterRequest(email, password, displayName))
        .retrieve()
        .toEntity(InboxAuthUserResponse.class)
        .getBody();
}
```

---

**כל ברור? יש שאלות?** 🙌

