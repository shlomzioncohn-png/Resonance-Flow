# תשובה לצוות INBOX — 6 שאלות על Auth Password Task

**בהתאם ל-implementation בענף `auth-stage2-jwt-extraction`**

---

## 1️⃣ **Keycloak: עדיין בתוכנית?**

**תשובה: לא עכשיו. סיסמה בInbox + JWT מ-Gateway.**

- **בענף הזה**: Gateway חתום JWT בRS256 (כל בקשה מוודא חתימה בלי להיתוך לשום שירות)
- **Keycloak**: שמור ב-repo כ-"עתיד" (`teams/lens/keycloak/realm-lens.json`) אבל לא מופעל
- **MVP**: סיסמה ב-Inbox זה הפתרון הפשוט ביותר — Relay לא צריכים להנהל עוד שירות

---

## 2️⃣ **בקוד שלנו: איפה InboxUsersClient ו-/internal/auth/*?**

**תשובה: הכל כבר קיים בענף הזה!**

### **Gateway** (`teams/lens/gateway/src/main/java/com/rnd/gateway/auth/`):
- ✅ `AuthController.java` — `POST /auth/register`, `POST /auth/login`, `GET /auth/me`
- ✅ `AuthService.java` — לוגיקת רישום/התחברות
- ✅ `TokenService.java` — חתימת JWT בRS256
- ✅ `BffAuthClient.java` — HTTP client ל-BFF `/internal/auth/*`
- ✅ `RateLimitingConfig.java` — 5 ניסיונות כושלים בדקה לכל IP

### **BFF** (`teams/lens/bff/src/main/java/com/rnd/bff/`):
- ✅ `InternalAuthController.java` — `/internal/auth/register` + `/internal/auth/verify`
- ✅ `InternalAuthService.java` — קריאה ל-InboxUsersClient + מיפוי תפקידים
- ✅ `InboxUsersClient.java` — **MOCK עכשיו**, תהפוך ל-HTTP כשInbox מוכן
- ✅ `RoleMapper.java` — ממיר Inbox roles לLENS roles

**מה הInboxUsersClient עושה כרגע:**
```java
public InboxAuthUserResponse register(String email, String password, String displayName) {
    // MOCK MODE: מחזיר משתמשי קשיחים לבדיקה
    // כשInbox מוכן: נחליף עם HTTP call ל-/api/v1/auth/register
}

public InboxAuthUserResponse verify(String login, String password) {
    // MOCK MODE: בודק נגד משתמשים קשיחים
    // כשInbox מוכן: נחליף עם HTTP call ל-/api/v1/auth/verify
}
```

---

## 3️⃣ **רישום פתוח: כל מי שנרשם → EMPLOYEE בdefault tenant?**

**תשובה: כן, ל-MVP זה בסדר.**

**במאמד:**
```java
// BFF: InternalAuthService.register()
UUID userId = inboxUsersClient.register(email, password, displayName);
// ← InboxUsersClient.mockRegister() מחזיר משתמש ב-EMPLOYEE, tenant_id = 00000000-...-0001
```

**בInbox (כשתבנו את זה):**
```java
public User register(String email, String password, String displayName, UUID actorId) {
    // יוצר משתמש EMPLOYEE ב-default tenant (00000000-0000-0000-0000-000000000001)
}
```

✅ **בסדר ל-MVP**, אבל תוסיפו comment בקוד: `// TODO: move to invitation model before production`

---

## 4️⃣ **סתירה: service account לא יכול לקרוא endpoints, אבל BFF קורא דרכו?**

**תשובה: לא סתירה.**

**בקוד של BFF:**
```java
// BFF קורא ל-Inbox בתור SYSTEM_ADMIN service account
private HttpHeaders buildHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Actor-Id", "00000000-0000-0000-0000-000000000002");  // Service account
    return headers;
}
```

**בInbox (כשתבנו):**
```java
@PostMapping("/verify")
@RequireRole(SYSTEM_ADMIN)  // ← בודק ש-actor הוא SYSTEM_ADMIN
public User verify(@RequestHeader("X-Actor-Id") UUID actorId, 
                   @RequestBody AuthVerifyRequestDto request) {
    // X-Actor-Id = service account (00000000-0000-0000-0000-000000000002)
    // request.login/password = של END USER (למשל: emp@example.com / password)
}
```

**בקיצור:**
- Service account = **BFF** (מי שקורא)
- login/password בbody = **END USER** (מי שמנסה להיכנס)
- ✅ זה בטוח

---

## 5️⃣ **מבנה התשובה: tenant כ-object או tenantId?**

**תשובה: עדיף tenantId בלבד.**

**מה אנחנו מחכים מInbox:**
```json
{
  "id": "20311a2c-a9e8-44ed-92b0-18665319f40a",
  "displayName": "John Doe",
  "role": "MANAGER",
  "tenantId": "00000000-0000-0000-0000-000000000001"
}
```

**למה?**
- Gateway לא צריך את שם ה-tenant (רק את ה-id)
- BFF לא צריך את זה ב-public endpoints
- אם בעתיד צריכים את הobject, קל להוסיף

**כשInbox מיישמים:**
```java
public AuthUserResponseDto(User inboxUser) {
    this.id = inboxUser.getId();
    this.displayName = inboxUser.getDisplayName();
    this.role = mapRole(inboxUser.getRole());  // INBOX roles → LENS roles
    this.tenantId = inboxUser.getTenantId();
}
```

---

## 6️⃣ **כללי סיסמה: אורך בלבד?**

**תשובה: כן, 8–72 תווים בלבד.**

**בGateway (ולידציה):**
```java
@Size(min = 8, max = 72, message = "Password must be 8-72 characters")
private String password;
```

**בInbox (בודק נוסף בכשתבנו):**
```java
private void validatePassword(String password) {
    if (password.length() < 8 || password.length() > 72) {
        throw new IllegalArgumentException("Password must be 8-72 characters");
    }
}
```

✅ **לא צריך:**
- ✋ ספרות + אותיות גדולות
- ✋ complexity scoring
- ✋ dictionary check

---

## 📋 **סיכום: מי עושה מה?**

| צוות | משימה | מצב |
|---|---|---|
| **LENS** (כבר עשינו) | Gateway auth endpoints + BFF internal endpoints + JWT RS256 + rate limiting | ✅ **מוכן בענף** |
| **INBOX** (צריך עכשיו) | `/api/v1/auth/register`, `/api/v1/auth/verify`, bcrypt, seed (admin + service account) | ⏳ **בתור** |
| **LENS** (כשInbox מוכן) | החלפת mock InboxUsersClient ב-HTTP call אמיתי | ⏳ **אחרי זה** |

---

## 🔄 **Flow — איך זה עובד עכשיו בבדיקות**

```
Frontend POST /auth/register {email, password, displayName}
    ↓
Gateway AuthController.register()
    ↓
BffAuthClient (HTTP POST → BFF)
    ↓
BFF InternalAuthController.register()
    ↓
InboxUsersClient.register() [MOCK — מחזיר hardcoded user]
    ↓
BFF מיפוי roles + החזרה ל-Gateway
    ↓
Gateway TokenService חתים JWT בRS256
    ↓
Frontend מקבל {accessToken, expiresIn, user:{id, displayName, role}}
```

**כשInbox מוכן, בשלב הרביעי:**
```java
// במקום MOCK:
InboxUsersClient.register() {
    return restClient.post()
        .uri("http://inbox:8083/api/v1/auth/register")
        .header("X-Actor-Id", "00000000-0000-0000-0000-000000000002")
        .body(request)
        .retrieve()
        .toEntity(InboxAuthUserResponse.class)
        .getBody();
}
```

---

## 📝 **Next Steps**

### **לצוותInbox:**
1. ✍️ קרא את המסמך הזה כולו
2. 📂 בנה 3 קבצים בInbox:
   - `UserAuthIdentityRepository.java`
   - `AuthService.java` (עם bcrypt)
   - `AuthController.java` (2 endpoints)
3. 🌱 הוסף seed: admin (מenv vars) + service account (00000000-0000-0000-0000-000000000002)
4. ✔️ בדוק עם WireMock מול BFF

### **לצוות LENS:**
1. כשInbox מוכנים: החלפת mock ב-HTTP call בInboxUsersClient
2. e2e test: register → login → GET /auth/me
3. merge לmain

---

**כל ברור? או שיש שאלות נוספות על הקוד?** 🙌

