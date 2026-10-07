# תוכנית עבודה: פיצול תתי-תיקיות בתוך ישויות ה-API

**סטטוס:** טיוטה לאישור
**ענף:** `yehudit/reorg-entity-subfolders` (נפתח מ-`inbox-team`)
**היקף:** 4 הישויות שיש להן API — `task`, `user`, `tenant`, `team`
**מיקום:** `teams/inbox/src/main/java/com/rnd/inbox/{entity}` וה-mirror תחת `src/test/java/com/rnd/inbox/{entity}`

## עקרון

רמת התיקייה העליונה נשארת שטוחה לפי ישות (`task`, `user`, `tenant`, `team` — כמו היום, לא משנים את זה).
בתוך כל תיקיית ישות נוסיף תתי-תיקיות לפי **שכבה אדריכלית**, אותו שם תת-תיקייה בכל הישויות, כדי שהמבנה יהיה עקבי וניתן לניווט אינטואיטיבי בין ישויות:

| תת-תיקייה   | מכיל |
|---|---|
| `model`      | ה-Entity עצמו + enums / value objects שמתארים את מצב הישות |
| `dto`        | Request/Response DTOs |
| `web`        | ה-Controller |
| `service`    | לוגיקה עסקית |
| `repository` | ממשק ה-Repository |
| `mapper`     | Entity↔DTO (וב-task גם Entity↔Proto) |
| `exception`  | חריגות ייעודיות לישות |
| `security`   | מחלקות Authorization / RoleContext |

בנוסף, בישויות עם תת-ישות פנימית (task→assignment, team→member) — תת-הישות מקבלת תיקייה משלה עם כל השכבות שלה יחד, כי הקבצים שלה תלויים זה בזה חזק יותר מאשר בשכבה המקבילה של ההורה. זה מקביל למבנה השטוח-לפי-ישות שכבר קיים ברמה העליונה.

**נקודת החלטה פתוחה:** `TaskRoleContext` — שייך ל-`security` (משמש יחד עם `TaskAuthorization`) או ל-`model`? הצעה: `security`, כי הוא קונטקסט להרשאות ולא שדה על הישות. תאשרי/י.

## מיפוי מלא — task (30 קבצים ב-main, 4 ב-test)

```
task/
├── model/
│   ├── Task.java
│   ├── TaskStatus.java
│   ├── TaskTier.java
│   ├── SourceType.java
│   ├── TaskExtractionProvenance.java
│   └── TaskFeedback.java
├── dto/
│   ├── TaskCreateRequestDto.java
│   ├── TaskUpdateRequestDto.java
│   ├── TaskStatusUpdateRequestDto.java
│   └── TaskResponseDto.java
├── web/
│   └── TaskController.java
├── service/
│   ├── TaskService.java
│   └── TaskIngestionService.java
├── repository/
│   └── TaskRepository.java
├── mapper/
│   ├── TaskMapper.java
│   └── TaskRestMapper.java
├── exception/
│   ├── TaskNotFoundException.java
│   └── InvalidTaskStatusTransitionException.java
├── security/
│   ├── TaskAuthorization.java
│   └── TaskRoleContext.java
├── event/                          # ייחודי ל-task
│   ├── TaskCreatedPayload.java
│   ├── TaskCreatedProtoMapper.java
│   ├── TaskUpdatedPayload.java
│   ├── TaskUpdatedProtoMapper.java
│   ├── TaskCompletedPayload.java
│   └── TaskCompletedProtoMapper.java
└── assignment/                     # תת-ישות
    ├── Assignment.java
    ├── AssignmentRole.java
    ├── AssigneeType.java
    ├── AssigneeDto.java
    ├── AssignmentMapper.java
    ├── AssignmentRepository.java
    └── AssigneeConsistencyValidator.java
```

test/task/ → לפצל לפי אותו היגיון: `TaskMapperTest.java`→`mapper/`, `TaskServiceTest.java`→`service/`, `TaskIngestionPersistenceHandlerTest.java`→`service/` (או `repository/` אם הוא בודק שכבת persistence — לבדוק את התוכן), `TaskAuditTransactionIntegrationTest.java`→ להשאיר ב-root של `task/` או תיקיית `integration/` ייעודית (טסטים חוצי-שכבות).

## מיפוי מלא — user (19 קבצים ב-main, 3 ב-test)

```
user/
├── model/
│   ├── User.java
│   ├── UserRole.java
│   ├── UserStatus.java
│   ├── AuthType.java
│   ├── UserAuthIdentity.java
│   ├── OtpRequest.java
│   ├── OtpChannel.java
│   └── OtpStatus.java
├── dto/
│   ├── UserCreateRequestDto.java
│   ├── UserUpdateRequestDto.java
│   ├── UserStatusUpdateRequestDto.java
│   └── UserResponseDto.java
├── web/
│   └── UserController.java
├── service/
│   └── UserService.java
├── repository/
│   └── UserRepository.java
├── mapper/
│   └── UserMapper.java
├── exception/
│   ├── UserNotFoundException.java
│   ├── DuplicateEmailException.java
│   └── DuplicateUsernameException.java
└── security/
    └── UserAuthorization.java
```

test/user/: `UserControllerTest.java`→`web/`, `UserServiceTest.java`→`service/`, `UserMapperTest.java`→`mapper/`

**נקודת החלטה פתוחה:** OTP (`OtpRequest`, `OtpChannel`, `OtpStatus`) — זה בעצם תת-נושא בתוך user (כמו assignment/member), לא רק model. אם יש/יתווספו עוד קבצי OTP (controller, service ייעודי) בעתיד, עדיף `user/otp/` כתת-ישות במקום לפזר בין model. כרגע עם 3 קבצים בלבד — אפשר להשאיר תחת model ולשדרג ל-otp/ כשיתווספו עוד קבצים.

## מיפוי מלא — tenant (14 קבצים ב-main, 0 ב-test)

```
tenant/
├── model/
│   ├── Tenant.java
│   └── TenantStatus.java
├── dto/
│   ├── TenantCreateRequestDto.java
│   ├── TenantUpdateRequestDto.java
│   ├── TenantStatusUpdateRequestDto.java
│   ├── TenantResponseDto.java
│   └── TenantSummaryDto.java
├── web/
│   └── TenantController.java
├── service/
│   └── TenantService.java
├── repository/
│   └── TenantRepository.java
├── mapper/
│   └── TenantMapper.java
├── exception/
│   ├── TenantNotFoundException.java
│   └── DuplicateSlugException.java
└── security/
    └── TenantAuthorization.java
```

## מיפוי מלא — team (21 קבצים ב-main, 0 ב-test)

```
team/
├── model/
│   ├── Team.java
│   └── TeamStatus.java
├── dto/
│   ├── TeamCreateRequestDto.java
│   ├── TeamUpdateRequestDto.java
│   ├── TeamStatusUpdateRequestDto.java
│   └── TeamResponseDto.java
├── web/
│   └── TeamController.java
├── service/
│   └── TeamService.java
├── repository/
│   └── TeamRepository.java
├── mapper/
│   └── TeamMapper.java
├── exception/
│   └── TeamNotFoundException.java
├── security/
│   └── TeamAuthorization.java
└── member/                         # תת-ישות (מקביל ל-task/assignment)
    ├── TeamMember.java
    ├── TeamMemberId.java
    ├── TeamMemberController.java
    ├── TeamMemberService.java
    ├── TeamMemberRepository.java
    ├── TeamMemberRequestDto.java
    ├── TeamMemberResponseDto.java
    ├── TeamMembershipNotFoundException.java
    └── DuplicateTeamMembershipException.java
```

## שלבי ביצוע מומלצים

1. לבצע ישות אחת בכל פעם (סדר מוצע: `tenant` → `team` → `user` → `task`, מהקטן לגדול), כדי שכל שלב יהיה נבדק ומינימלי בסיכון.
2. **חובה להשתמש ב-Refactor→Move של IntelliJ**, לא `mv`/`git mv` ידני — Move ב-IDE מעדכן אוטומטית את שורת ה-`package` ואת כל ה-`import` בכל הקוד שמפנה לקובץ (כולל טסטים, וקוד בישויות אחרות שמייבא למשל `TaskStatus` או `AssigneeType`).
3. אחרי כל ישות: להריץ build מלא (`mvn compile test-compile`) + את הטסטים הרלוונטיים לישות, לוודא שאין import שבור.
4. לבדוק גם קבצים מחוץ ל-4 הישויות שמייבאים מהן (למשל `structuredtask`, `taskcreationrequest`, `taskselection`, `project`, `audit` יכולים לייבא `Task`, `TaskStatus`, `User` וכו') — ה-IDE Move אמור לתפוס את אלה אוטומטית, אבל כדאי `mvn compile` על כל המודול בסוף כבדיקת שפיות.
5. קומיט נפרד per-ישות (`refactor(task): split into layered sub-packages` וכו') כדי שקל יהיה לעקוב/לבטל אם משהו נשבר.

## נקודות שדורשות אישור/החלטה שלך לפני ביצוע

1. שמות תתי-התיקיות (`model`/`dto`/`web`/`service`/`repository`/`mapper`/`exception`/`security`) — מתאים? יש מוסכמה קיימת בפרויקט שכדאי להתאים אליה?
2. `TaskRoleContext` → `security` (הצעה) — מאשרת?
3. OTP ב-`user` — להשאיר תחת `model` כרגע או כבר לפצל ל-`user/otp/`?
4. `TaskAuditTransactionIntegrationTest` — להשאיר ב-root של `task/test` או לפתוח `task/integration/`?
5. סדר ביצוע: להתחיל מ-`tenant` (הכי קטן, 14 קבצים) כפיילוט לפני שממשיכים?

לאחר אישור הנקודות האלה אפשר להתחיל בביצוע בפועל על הענף `yehudit/reorg-entity-subfolders`.
