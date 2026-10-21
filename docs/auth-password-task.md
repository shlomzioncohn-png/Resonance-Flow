# רישום ו-login עם סיסמה ב-LENS

> LENS · Gateway + BFF + Inbox · Auth & Identity
>
> גרסה שלישית להשוואה, לצד [auth-registration-login-task.md](auth-registration-login-task.md) (Google) ו-[auth-keycloak-task.md](auth-keycloak-task.md) (Keycloak). זו הגרסה שמתאימה לשני התנאים: **אין שירות חדש ש-Relay צריכים לתחזק, ואין תלות בספק חיצוני.**

## הסיכום בקצרה

- **משתמשים רגילים** נרשמים עם email, סיסמה ושם לתצוגה, ונכנסים עם email וסיסמה. כל מי שנרשם מקבל `EMPLOYEE`.
- **ה-admin היחיד** נכנס עם שם משתמש וסיסמה, ואין לו email. הוא `SYSTEM_ADMIN` ב-Inbox ו-`MANAGER` ב-LENS, ונוצר רק ב-seed של Inbox.
- **הסיסמאות נשמרות רק ב-Inbox**, כ-bcrypt בטבלה `user_auth_identities` הקיימת. ה-gateway וה-BFF לא שומרים סיסמאות ולא hash.
- **ה-gateway מנפיק JWT** וחותם עליו ב-RS256. בכל בקשה הוא מאמת את הטוקן בלי לקרוא לאף שירות, ומעביר ל-BFF את `X-User-Id` ואת `X-User-Role`.
- **ה-gateway מדבר רק עם ה-BFF.** ה-BFF הוא היחיד שפונה ל-Inbox, כמו שמוגדר ב-`docs/lens-architecture-design.md`.


## 00. הארכיטקטורה

```text
register:
FE ──POST /auth/register──▶ Gateway ──POST /internal/auth/register──▶ BFF ──POST /api/v1/auth/register──▶ Inbox
                                                                                (bcrypt, user + identity, EMPLOYEE)
login (users: email · admin: username):
FE ──POST /auth/login──▶ Gateway ──POST /internal/auth/verify──▶ BFF ──POST /api/v1/auth/verify──▶ Inbox
                           │◀──────────── {id, displayName, role: MANAGER|EMPLOYEE} ◀──┘
                           └─ signs JWT (sub = Inbox user id, role, name) and returns it to FE

every other request:
FE ──Bearer JWT──▶ Gateway (signature check only, no downstream call) ──X-User-Id, X-User-Role──▶ BFF
```

| רכיב | אחריות במשימה הזאת |
|---|---|
| Gateway | `/auth/register`, `/auth/login` ו-`/auth/me`, ולידציה של הקלט, הגבלת ניסיונות, הנפקה ואימות של JWT, הרשאות לפי role, והזרקת headers ל-BFF. לא פונה ל-Inbox, ולא מצפין ולא בודק סיסמאות. |
| BFF | `/internal/auth/register` ו-`/internal/auth/verify`, client ל-Inbox, ומיפוי התפקיד ל-LENS. |
| Inbox | שמירת הסיסמאות ובדיקה שלהן (bcrypt), יצירת המשתמשים, ומשתמשי ה-seed. |

ה-`sub` בטוקן הוא ה-id של המשתמש ב-Inbox, כך שאין צורך בקישור נוסף בין מערכות. `X-User-Id` הוא ה-UUID שה-BFF משתמש בו מול Inbox.

## 01. המצב הנוכחי בקוד (נבדק מול ה-repo)

- **Frontend:** מסך ה-login הוא בחירת תפקיד בלבד. כתוב בו שהאימות עוד לא מומש.
- **Gateway:**
  - Spring Boot 3.4, Spring MVC ו-`RestTemplate`, עם controllers מפורשים. זה לא Spring Cloud Gateway, ולכן אין `GlobalFilter`.
  - `SecurityConfig` מוגדר עם `permitAll`.
  - יש `jjwt` ו-`app.jwt.secret` (מפתח סימטרי), ויש `app.dev-mode` עם `MOCK_USER_ID = "emp_001"`.
  - אין routes תחת `/api/manager/**`.
- **BFF:**
  - Spring Boot 3.4, MVC.
  - `InboxClient` הוא כרגע mock בזיכרון, כך שאין עדיין קריאת HTTP אמיתית ל-Inbox. ה-URL מוגדר כ-`inbox.service.url`.
  - קורא רק `X-User-Id`.
- **Inbox:**
  - **הסכמה כבר תומכת בסיסמאות:**
    - `user_auth_identities` עם `auth_type = password`, `identifier` ו-`password_hash`.
    - unique על `(auth_type, identifier)`.
    - `users.username` הוא unique.
  - אין שום קוד שמשתמש בהם. ל-`UserAuthIdentity` אין repository.
  - `POST /api/v1/users` מחייב `X-Actor-Id` של admin וגם `tenantId`, ויוצר את המשתמש כ-`ACTIVE`.
  - את ה-`X-Actor-Id` הם מקבלים כמו שהוא נשלח, בלי שום אימות. זה פער ידוע ומתועד אצלם.
  - התפקידים הם `SYSTEM_ADMIN, TENANT_ADMIN, TEAM_LEAD, EMPLOYEE`.
  - ה-email הוא unique, אבל case-sensitive.
- **תשתית:** אין Ingress ב-Helm chart, כך שלסביבה המשותפת אין היום כתובת שהדפדפן יכול להגיע אליה.

## 02. מיפוי תפקידים (ב-BFF)

ה-BFF ממיר את התפקיד של Inbox לתפקיד של LENS לפני שהוא מחזיר תשובה ל-gateway. ה-gateway מכיר רק את `MANAGER` ו-`EMPLOYEE`.

| Inbox | LENS |
|---|---|
| `SYSTEM_ADMIN` | `MANAGER` |
| `EMPLOYEE` | `EMPLOYEE` |
| `TENANT_ADMIN`, `TEAM_LEAD` | `EMPLOYEE` (ממתין לאישור, ראי סעיף 11) |

משתמש שנרשם בעצמו הוא תמיד `EMPLOYEE`. ה-admin נוצר רק דרך ה-seed של Inbox.

## 03. החוזים (PR לבדיקה לפני המימוש)

### Gateway: ציבורי (OpenAPI ב-`teams/lens/gateway/`)

| Endpoint | Body | תגובה |
|---|---|---|
| `POST /auth/register` | `{email, password, displayName}` | 201 `{userId}` · 409 email תפוס · 400 קלט לא תקין |
| `POST /auth/login` | `{login, password}` | 200 `{accessToken, expiresIn, user:{id, displayName, role}}` · 401 · 429 יותר מדי ניסיונות |
| `GET /auth/me` | (טוקן) | 200 `{id, displayName, role}` · 401. התשובה נבנית מה-claims של הטוקן. |

`login` הוא email למשתמשים רגילים, ושם משתמש ל-admin.

### BFF: פנימי, לשימוש ה-gateway בלבד (OpenAPI ב-`teams/lens/bff/`)

| Endpoint | Body | תגובה |
|---|---|---|
| `POST /internal/auth/register` | `{email, password, displayName}` | 201 `{userId}` · 409 |
| `POST /internal/auth/verify` | `{login, password}` | 200 `{id, displayName, role}` · 401 |

### Inbox: שירות לשירות (רק ה-BFF קורא להם)

| Endpoint | Body | תגובה |
|---|---|---|
| `POST /api/v1/auth/register` | `{email, password, displayName}` | 201 עם המשתמש · 409 |
| `POST /api/v1/auth/verify` | `{login, password}` | 200 עם המשתמש · 401 |

### כללים שנכנסים לחוזה

- אין שדה `role` ברישום, ושדה כזה שנשלח בכל זאת מתעלמים ממנו.
- 401 ב-login מחזיר הודעה כללית ("פרטי ההתחברות שגויים"), בלי לגלות אם ה-email או שם המשתמש קיימים.
- ה-hash לא יוצא מ-Inbox בשום תגובה. סיסמאות לא נכתבות ללוג באף שירות.
- הנתיבים הציבוריים לא חושפים מבנה פנימי: המילה inbox לא מופיעה בנתיבים או ב-query params, והערכים של `role` הם רק `MANAGER` ו-`EMPLOYEE`.
- שגיאות מוחזרות במבנה ה-`ErrorResponse` הקיים של כל שירות.
- יש לתאם את החפיפה בין `GET /auth/me` לבין `GET /v1/me` מ-`docs/employee-workspace-api-contract.md`.

## 04. Inbox: רישום ובדיקת סיסמה

> הכתיבה לפי `teams/inbox/CLAUDE.md`. ה-PR נבדק על ידי צוות Inbox. **אין שינוי בסכמה ואין migration**, כי הטבלאות והעמודות כבר קיימות.

- **תלות:** `spring-security-crypto` בלבד, בשביל `BCryptPasswordEncoder`. **לא** `spring-boot-starter-security`, שמפעיל אוטומטית הגדרות security על כל ה-endpoints של Inbox.
- **מחלקות חדשות:** `UserAuthIdentityRepository` ו-`AuthService`.
- **הרשאה:** שני ה-endpoints מוגדרים עם `@RequireRole(SYSTEM_ADMIN)`, וה-BFF קורא להם עם `X-Actor-Id` של ה-service account. כך הם נשארים עקביים עם מודל ההרשאות הקיים של Inbox, ולא נפתחים endpoints בלי actor.
- **נרמול:** `trim().toLowerCase()` ל-email ול-login, לפני כל שמירה וכל חיפוש.
- **`POST /api/v1/auth/register`:**
  - בטרנזקציה אחת יוצר:
    - משתמש `EMPLOYEE`, ‏`ACTIVE`, ב-tenant ברירת המחדל, עם `email` ו-`displayName`
    - identity עם `auth_type = password`, ‏`identifier = email`, ו-`password_hash`
  - מחזיר 409 אם ה-email כבר קיים, ב-`users` או ב-identity.
  - ה-tenant ברירת המחדל מגיע מ-`inbox.registration.default-tenant-id`.
- **`POST /api/v1/auth/verify`:**
  - מחפש identity עם `auth_type = password` ו-`identifier = login`, ובודק את הסיסמה.
  - מחזיר 200 עם המשתמש, ומעדכן `last_login_at`.
  - מחזיר 401 באותה תגובה בשלושה מקרים: לא נמצא, סיסמה שגויה, או `status` שאינו `ACTIVE`.
  - **גם כשלא נמצא משתמש, מריצים בדיקת bcrypt מול hash קבוע.** כך זמן התגובה זהה בכל המקרים, ואי אפשר לגלות לפי זמן התגובה אם המשתמש קיים.
- **seed:**
  - **admin:** `SYSTEM_ADMIN` עם `username` ו-`displayName` ובלי email, ואיתו identity עם `auth_type = password` ו-`identifier = username`.
    - שם המשתמש והסיסמה מגיעים מ-env, וה-seed שומר רק את ה-hash.
    - שם המשתמש לא יכול להכיל `@`, כדי שלא יתנגש עם email.
    - אם המשתנים לא מוגדרים, ה-admin לא נוצר והשירות כותב אזהרה ללוג.
  - **service account עבור ה-BFF:** `SYSTEM_ADMIN` בלי email ובלי identity, כך שאי אפשר להיכנס איתו.
  - **tenant ברירת מחדל** למשתמשים שנרשמים.
- **לא בהיקף המשימה:** איפוס סיסמה ושינוי סיסמה. ראי סעיף 11.

## 05. BFF: client ל-Inbox ו-endpoints פנימיים

- **`InboxUsersClient`:** זו תהיה קריאת ה-HTTP האמיתית הראשונה מה-BFF ל-Inbox. כותבים אותה עם `RestClient`.
  - config:
    - `inbox.service.url` הקיים
    - `inbox.service-actor-id`
    - connect timeout ו-read timeout
  - כל הערכים מגיעים מ-env, בלי hardcode.
- **DTOs משלנו בתוך ה-BFF**, שנבנים לפי ה-OpenAPI של Inbox (`/v3/api-docs`). לא מייבאים DTOs או ספרייה של Inbox.
- **`InternalAuthController`** עם שני ה-endpoints מסעיף 03. ה-controller דק, והלוגיקה נמצאת ב-service.
- **מיפוי שגיאות:**
  - 401 מ-verify ← `InvalidCredentialsException` (401)
  - 409 ← `EmailTakenException` (409)
  - timeout או 5xx ← `ServiceUnavailableException` הקיים (503)
- **מיפוי התפקידים** מסעיף 02 נעשה במקום אחד: ה-mapper של ה-client.
- **הגנה על `/internal/**`:**
  - ה-gateway לא מנתב את הנתיבים האלה כלפי חוץ. זה קורה ממילא, כי יש בו רק controllers מפורשים.
  - ה-BFF הוא ClusterIP בלי ingress.
  - NetworkPolicy שמאפשרת גישה ל-BFF רק מה-gateway (סעיף 10).
- **תיאום:** ה-mocks וה-DTOs שמניחים `emp_001` מתעדכנים ל-UUID.

## 06. Gateway: רישום, login והנפקת JWT

- **Client ל-BFF:** `BffAuthClient` עם `RestTemplate` או `RestClient`, באותו דפוס של הקריאות הקיימות ל-BFF. ה-URL מגיע מ-`bff.service.url`, וה-paths מוגדרים תחת `bff.endpoints.*`.
- **ולידציה:**
  - רישום: `@Email`, סיסמה באורך 8 עד 72 תווים (72 הוא הגבול של bcrypt), ו-`displayName` לא ריק.
  - login: שני השדות לא ריקים.
- **נרמול:** `trim().toLowerCase()` ל-email ול-login, לפני ההעברה ל-BFF.
- **רישום:** מעבירים ל-`/internal/auth/register`, בלי role. ה-gateway לא מצפין את הסיסמה. אם חוזר 409, מחזירים 409.
- **login:** קוראים ל-`/internal/auth/verify`. כל 401 הופך ל-401 עם ההודעה הכללית.
- **הגבלת ניסיונות:**
  - בערך 5 ניסיונות כושלים בדקה לכל IP ולכל login, ו-10 רישומים בשעה לכל IP. מעבר לזה מחזירים 429.
  - ההגבלה נשמרת בזיכרון, למשל עם Bucket4j. כשיהיו כמה replicas, צריך לעבור לאחסון משותף. לפתוח על זה issue.
- **הנפקת JWT** ב-RS256 עם `NimbusJwtEncoder`:

```java
var claims = JwtClaimsSet.builder()
    .issuer("lens-gateway")
    .subject(user.id().toString())                          // Inbox user UUID
    .issuedAt(now).expiresAt(now.plusSeconds(ttlSeconds))   // ttl from config, default 3600
    .claim("role", user.role())                             // MANAGER | EMPLOYEE, already mapped by BFF
    .claim("name", user.displayName())
    .build();
var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();   // kid allows key rotation later
return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

- **מפתחות:** `lens.jwt.private-key-location`, `lens.jwt.public-key-location` ו-`lens.jwt.key-id`.
  - **בפיתוח מקומי:** קבצים שמוחרגים ב-`.gitignore`. ב-README מוסיפים את הפקודות שיוצרות אותם:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
```

```bash
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
```

  - **בסביבה המשותפת:** K8s Secret (סעיף 10).
- **אין refresh token בשלב הזה.** אחרי שעה המשתמש נכנס שוב.
- **logout:** ה-frontend מוחק את הטוקן. אין ביטול טוקן בצד השרת, ולכן משתמש שהושעה נחסם רק כשהטוקן שלו פג, תוך שעה לכל היותר. זו מגבלה ידועה.
- **מה מסירים:** `jjwt`, `app.jwt.secret`, `app.dev-mode` ו-`MOCK_USER_ID`.
- **במקום `SecurityValidationConfig`:** בדיקה באתחול שהמפתחות נטענים, ואם לא, השירות נכשל מיד (fail fast).

## 07. Gateway: Security

- **Resource server:** `spring-boot-starter-oauth2-resource-server` עם `NimbusJwtDecoder` (servlet). הוא מאמת עם המפתח הציבורי שלנו, בודק `iss=lens-gateway`, ופועל במצב `STATELESS`.
  - האימות לא קורא לשום שירות. זו בדיקת חתימה ותוקף בלבד.
- **`permitAll`** רק לנתיבים: `POST /auth/register`, `POST /auth/login`, ו-health.
- **`/api/manager/**`** מוגדר כ-`hasRole("MANAGER")`, דרך `JwtGrantedAuthoritiesConverter` עם `authoritiesClaimName("role")` ו-`authorityPrefix("ROLE_")`.
- **כל שאר הנתיבים** מוגדרים כ-`authenticated()`.
- **401 ו-403** מוחזרים במבנה ה-`ErrorResponse` הקיים.
- **העברת זהות ל-BFF:**
  - רכיב `CurrentUser` קורא `sub` ו-`role` מה-`Jwt` שאומת.
  - `buildHeaders` מזריק מהם `X-User-Id` ו-`X-User-Role`.
  - ה-headers נבנים מאפס בכל קריאה. אף פעם לא מעתיקים `X-User-*` מהבקשה הנכנסת.
- **CORS:** אם ה-frontend וה-gateway רצים ב-origins שונים, צריך לאפשר את ה-header `Authorization` מה-origin של ה-frontend, לפי config. עם host אחד (סעיף 10) זה לא נדרש.

## 08. Frontend (תיאום, משימה נפרדת)

- **מסכים:** מסך login (email או שם משתמש, וסיסמה) ומסך רישום (email, סיסמה ושם לתצוגה), במקום בחירת התפקיד.
- **הודעות שגיאה:** הודעה כללית על 401, "האימייל כבר רשום" על 409, והודעת המתנה על 429.
- **הטוקן:** נשלח ב-`Authorization: Bearer` דרך שכבת ה-services. איפה שומרים אותו עדיין פתוח (סעיף 11).
- **ניווט:** לפי `user.role` מתשובת ה-login, ולא לפי בחירה ידנית.
- **401 מה-API** מחזיר את המשתמש למסך ה-login.
- **logout** מוחק את הטוקן.

## 08ב. הפרדות שמוזילות מעבר למוד אחר

> המטרה: אם בהמשך נוסיף Google, או נעבור ל-Keycloak או לשירות זהות מנוהל, השינוי יהיה מבודד ולא יתפזר בקוד. ההפרדות האלה כמעט לא עולות כלום היום, והן חלק מהמשימה.

1. **BFF: רכיב `CurrentUserResolver` מהיום הראשון.**
   - רכיב אחד מחזיר את המשתמש הנוכחי. בשלב הזה הוא פשוט מחזיר את `X-User-Id` כ-id של Inbox, יחד עם `X-User-Role`.
   - **ה-controllers וה-services לא קוראים את `X-User-*` ישירות.** הם מקבלים את המשתמש מה-resolver, למשל דרך `HandlerMethodArgumentResolver`.
   - במעבר לספק חיצוני `X-User-Id` יהיה ה-`sub` של הספק, ואז מחליפים רק את המימוש של ה-resolver, כך שימיר `sub` למשתמש ב-Inbox.
2. **Gateway: כל האימות ב-package אחד.**
   - הרישום, ה-login, `TokenService` (הנפקת JWT), הגבלת הניסיונות ו-`BffAuthClient` יושבים ב-`com.rnd.gateway.auth`.
   - שאר ה-gateway מכיר רק את ה-`Jwt` שאומת, דרך `CurrentUser`.
   - במעבר לספק חיצוני מוחקים את ה-package, ומחליפים את ה-decoder כך שיאמת מול ה-issuer וה-JWKS של הספק.
   - **שם ה-claim של התפקיד מגיע מ-config** (`lens.auth.role-claim`, ברירת מחדל `role`), כי כל ספק קורא לו אחרת. למשל, ב-Keycloak הוא `roles`.
3. **Frontend: מודול `authService` אחד.**
   - login, רישום, logout, שמירת הטוקן והוספת ה-header `Authorization` יושבים במקום אחד.
   - העמודים והקומפוננטות לא קוראים ל-`/auth/*` ולא ניגשים לטוקן ישירות.
   - במעבר ל-redirect של ספק חיצוני מחליפים את המודול ואת מסכי ה-login, ושאר האפליקציה לא משתנה.
4. **החוזה בין ה-gateway ל-BFF הוא נקודת החיבור היחידה.**
   - `X-User-Id` ו-`X-User-Role` הם כל מה שה-BFF יודע על האימות. הוא לא יודע איך המשתמש נכנס.
   - ה-endpoints של `/internal/auth/*` יושבים ב-controller נפרד, כדי שיהיה אפשר להסיר אותם בלי לגעת בשאר.

**מה ש-Inbox כבר מוכן אליו:** הטבלה `user_auth_identities` תומכת בכמה זהויות לאותו משתמש. כך אפשר להוסיף בהמשך זהות של Google (או של ספק אחר) לצד הסיסמה, בלי לשנות את טבלת `users`.

## 09. בדיקות

### Gateway (ה-BFF מוחלף ב-WireMock)

- **רישום:**
  - רישום תקין מחזיר 201, רישום כפול מחזיר 409, וסיסמה קצרה מחזירה 400.
  - שליחת `role: MANAGER` ברישום לא מגיעה ל-BFF.
  - email באותיות גדולות עם רווחים מגיע ל-BFF כשהוא מנורמל.
- **login:**
  - login תקין מחזיר טוקן עם `sub`, `role`, `name` ו-`kid` נכונים.
  - login של ה-admin עם שם משתמש מחזיר טוקן עם `role=MANAGER`.
  - סיסמה שגויה ו-login לא קיים מחזירים תגובת 401 זהה.
  - אחרי חריגה ממספר הניסיונות מוחזר 429.
- **Security:**
  - בקשה בלי טוקן מחזירה 401.
  - טוקן שפג, או שנחתם במפתח אחר, מחזיר 401.
  - עובד שניגש לנתיב של מנהל מקבל 403. ה-admin שניגש לאותו נתיב מקבל 200.
  - `X-User-Id` ו-`X-User-Role` מזויפים מהלקוח לא מגיעים ל-BFF.
  - בקשה מאומתת לא גורמת לאף קריאה ל-`/internal/auth/*`.
- **סיסמאות לא מופיעות בלוגים.**
- **Gateway לא פונה לשום שירות חוץ מה-BFF:** אין בו config של Inbox, אין לו client ל-Inbox, ובבדיקות אין mock של Inbox.

### BFF (Inbox מוחלף ב-WireMock)

- register ו-verify נשלחים עם `X-Actor-Id` של ה-service account.
- `SYSTEM_ADMIN` ממופה ל-`MANAGER`, ו-`TEAM_LEAD` ממופה ל-`EMPLOYEE`.
- 401 ו-409 מ-Inbox ממופים נכון.
- timeout של Inbox מחזיר 503.
- סיסמאות לא מופיעות בלוגים.

### Inbox

- **register:**
  - נוצרים משתמש `EMPLOYEE` ו-identity, בטרנזקציה אחת.
  - email כפול מחזיר 409, גם כשההבדל הוא רק באותיות גדולות.
  - ב-DB נשמר hash ולא הסיסמה עצמה.
- **verify:**
  - סיסמה נכונה מחזירה 200 ומעדכנת `last_login_at`.
  - סיסמה שגויה, login לא קיים, ומשתמש שאינו `ACTIVE` מחזירים 401 זהה.
  - ה-service account מחזיר 401.
- **הרשאות:** actor שאינו `SYSTEM_ADMIN` מקבל 403.
- **ה-hash לא מופיע באף תגובה.**
- **seed:**
  - ה-admin נוצר עם hash.
  - בלי משתני env, ה-admin לא נוצר.
  - שם משתמש עם `@` נדחה.
- הבדיקות נכתבות לפי הקונבנציות ב-CLAUDE.md של Inbox.

## 10. תשתית מול Relay (סביבה משותפת)

הסעיף הזה עבר לקובץ נפרד שמיועד ל-Relay: **[relay-infra-request-auth.md](relay-infra-request-auth.md)**.

בקצרה, אין שירות חדש ואין DB חדש. מה שנדרש מ-Relay:
- כתובת ציבורית עם HTTPS, על host אחד: `/` ל-frontend, ו-`/api` ו-`/auth` ל-gateway.
- שני Secrets: `lens-gateway-jwt` ו-`lens-admin`.
- כמה ערכי config.
- NetworkPolicy שמאפשרת גישה ל-BFF רק מה-gateway.

**מה המפתחת צריכה מהקובץ:** שמות משתני הסביבה בסעיף 2 ובסעיף 3 שם הם החוזה בין הקוד לבין ה-chart. הקוד צריך לקרוא בדיוק את השמות האלה.

## 11. החלטות פתוחות (אצלי)

- **איפה ה-frontend שומר את הטוקן:**
  - **בזיכרון:** הכי בטוח, אבל ה-login נדרש שוב אחרי רענון של הדף.
  - **ב-`sessionStorage`:** הטוקן שורד רענון, אבל חשוף ל-XSS.
  - **ב-cookie מסוג `HttpOnly`:** הכי בטוח, אבל משנה את החוזה ומחייב הגנת CSRF.
- **refresh token:** אם שעה קצרה מדי, להוסיף refresh token בשלב הבא.
- **איפוס סיסמה:** בלי SMTP אין שכחתי סיסמה. אפשר להוסיף בהמשך endpoint של admin, שמאפס סיסמה למשתמש.
- **`TENANT_ADMIN` ו-`TEAM_LEAD`:** האם הם ממופים ל-`EMPLOYEE` (המצב כרגע) או נחסמים.
- **היקף `SYSTEM_ADMIN`:** ב-Inbox הוא רואה את כל ה-tenants, ולכן מסך המנהל עלול להציג נתונים חוצי ארגונים.
- **רישום פתוח:** כל אחד יכול ליצור לעצמו `EMPLOYEE` ב-tenant ברירת המחדל. כדאי לוודא שעובד חדש לא רואה כברירת מחדל נתונים של אחרים.
- **דומיין ו-TLS לסביבה המשותפת:** מול Relay (סעיף 10).
- **OAuth בהמשך:** אפשר להוסיף "כניסה עם Google" כאפשרות נוספת, בלי לשנות את ה-JWT או את החוזה. ראי גרסת Google.

## 12. הגדרת "סיום" (DoD)

- [ ] החוזים מסעיף 03 (Gateway ציבורי, BFF פנימי ו-Inbox) אושרו ב-PR לפני המימוש.
- [ ] סיסמאות נשמרות רק ב-Inbox, כ-bcrypt. ה-gateway וה-BFF לא שומרים סיסמה או hash, ולא כותבים אותם ללוג.
- [ ] ה-gateway מדבר רק עם ה-BFF. רק ה-BFF פונה ל-Inbox.
- [ ] אין גישה ישירה ל-DB של Inbox, ואין DTOs משותפים.
- [ ] השינויים ב-Inbox נבדקו על ידי צוות Inbox.
- [ ] אין URLs, מפתחות, שמות משתמש או סיסמאות ב-hardcode, ו-`.env.example` עודכן.
- [ ] `jjwt`, `dev-mode` ו-`MOCK_USER_ID` הוסרו.
- [ ] ההפרדות מסעיף 08ב קיימות:
  - ה-controllers ב-BFF לא קוראים `X-User-*` ישירות.
  - האימות ב-gateway מרוכז ב-`com.rnd.gateway.auth`.
  - שם ה-claim של התפקיד מגיע מ-config.
  - ב-frontend הטוקן מנוהל רק ב-`authService`.
- [ ] כל הבדיקות בסעיף 09 עוברות.
- [ ] בדיקה מקצה לקצה: רישום, login, קריאה ל-BFF עם `X-User-Id` נכון (ה-UUID של Inbox).
- [ ] בדיקה מקצה לקצה ל-admin: login עם שם משתמש וסיסמה נותן `MANAGER` וגישה ל-`/api/manager/**`.
- [ ] בסביבה המשותפת: הכתובת הציבורית עובדת עם HTTPS, וה-Secrets נוצרו מחוץ ל-chart (סעיף 10).
- [ ] עודכנו: ה-README וה-`GATEWAY_CONTRACT.md` של ה-gateway (כולל יצירת המפתחות המקומיים), וה-README של ה-BFF.
