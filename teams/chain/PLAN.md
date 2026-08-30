# PLAN.md — טיפול בהערות Code Review: גרף תלויות משימות (Chain)

## הקשר (Context)

ה-Tech Lead סקר את מימוש ה-Persistence וה-Service Layer של גרף התלויות בין משימות (מודול `Resonance-Flow/teams/chain`) והעלה 8 הערות, מ-Critical ועד Cosmetic. המטרה של התוכנית הזו היא לטפל בכל ההערות בצורה מדורגת, כשכל שלב עומד בפני עצמו (ניתן לבדוק/לקמפל אחריו), ולוודא שהתיקונים לא שוברים את ההתנהגות הקיימת.

**ממצא חשוב מהחקירה:** חלק מהערה #1 (Thread-safety ב-`Task`) כבר ממותן בפועל — `InMemoryTaskRepository` כבר עושה Defensive Copying מלא דרך `Task.copy()`, ו-`update()` כבר משתמש ב-`ConcurrentHashMap.computeIfPresent` לעדכון אטומי per-key. הבעיה שנשארת היא ש-`Task` עדיין Mutable (Setters ציבוריים) — התיקון הנדרש הוא Immutability אמיתית של המחלקה עצמה, לא עוד Defensive Copy.

כמו כן, קוד הפרודקשן (Repository/Service) **לא** משתמש כרגע ב-`Task`/`TaskDependency` Setters בכלל — רק טסט אחד (`InMemoryTaskRepositoryTest`) קורא ל-`setBlockingCount`. זה מצמצם משמעותית את רדיוס ההשפעה של Immutability.

**קבצים מרכזיים:**
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/dto/Task.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/dto/TaskDependency.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/dto/TaskStatus.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/repository/TaskRepository.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/repository/InMemoryTaskRepository.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/repository/TaskDependencyRepository.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/repository/InMemoryTaskDependencyRepository.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/service/TaskDependencyService.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/exception/{DependencyNotFoundException,DuplicateDependencyException,InvalidDependencyException,TaskNotFoundException}.java`
- `Resonance-Flow/teams/chain/src/main/java/com/rnd/chain/config/MockDataConfig.java`
- `Resonance-Flow/teams/chain/README.md`
- טסטים: `.../test/java/com/rnd/chain/repository/InMemoryTaskRepositoryTest.java`, `.../repository/InMemoryTaskDependencyRepositoryTest.java`, `.../service/TaskDependencyServiceTest.java`

**סביבת בדיקות קיימת (לשימוש חוזר, לא להוסיף ספריות חדשות):** JUnit 5 + AssertJ (דרך `spring-boot-starter-test`), ודפוס Concurrency קיים ב-`InMemoryTaskRepositoryTest.update_isAtomicUnderConcurrentIncrement` (`ExecutorService` + `CountDownLatch`) — נשתמש באותו דפוס.

**אומתה בפרויקט:** אין שום `@Profile`, `spring.profiles.active`, או קובץ `application-*.yml` בפרויקט הזה כרגע (רק `application.yml` יחיד גנרי). אין Convention קיים של Production Profile — לכן שלב 5 **לא** ממציא Profile חדש.

---

## כללי ביצוע גורפים (חלים על כל השלבים)

1. **אין להרחיב Scope.** מבצעים אך ורק את מה שנדרש כדי לסגור את 8 הערות ה-Code Review. אין Refactoring כללי, אין שינויי Naming/Architecture, אין אופטימיזציות שלא התבקשו. בעיה שמתגלה תוך כדי עבודה ואינה חלק מהמשימה — **לא** מתוקנת אוטומטית, אלא מתועדת בסיכום הסופי תחת "Follow-up / Backlog".
2. **בדיקת שימושים לפני הסרה/שינוי של כל API ציבורי** (Setter, Constructor, `TaskDependencyRepository.save`/`delete`, Implementations של ה-Interfaces): חיפוש **בכל ה-Workspace הרלוונטי, לא רק במודול `chain`** — קוד פרודקשן, Unit/Integration Tests, Mocks, Configuration, Test Utilities, וכל Consumer חיצוני של ה-Repository API. לא מניחים "אין שימוש" רק כי לא נמצא בקובץ שציפינו לו. **אומת בפועל:** ה-Workspace מכיל שלושה מודולי Maven אחים תחת `Resonance-Flow/teams` — `bridge`, `chain`, `inbox`; חיפוש `com.rnd.chain` בכל ה-Workspace מראה שהחבילה נמצאת בשימוש **רק** בתוך מודול `chain` עצמו — אין ל-`bridge` או ל-`inbox` שום תלות בה. כל שינוי API בתוכנית הזו בטוח מבחינת Cross-Module impact, אך יש לחזור על החיפוש הזה בפועל בזמן הביצוע (לא להסתמך על הממצא הזה כניצחי אם נוספו קבצים בינתיים).
3. **Definition of Done לכל שלב**, לפני מעבר לשלב הבא:
   - [ ] הקוד מתקמפל
   - [ ] הטסטים הרלוונטיים עוברים
   - [ ] אין שימושים שבורים ב-API שהשתנה (נבדק בפועל, לא בהנחה)
   - [ ] אין Warnings/Errors חדשים הקשורים לשינוי
   - [ ] ה-Behavior הקיים שלא נדרש לשינוי נשמר
   - [ ] ה-Concurrency Contract נבדק במקום שבו הוא רלוונטי
4. **אם מתגלה סתירה בין התוכנית הזו לבין הקוד בפועל** — עוצרים לפני ביצוע שינוי משמעותי, מציגים את הסתירה והאפשרויות, ולא מנחשים את ה-Intended Behavior.
5. **אין להכריז על הערה כ"נסגרה" (Fixed) בלי הוכחה** — קוד + טסט שבאמת מוכיח שהדרישה המקורית של ה-Tech Lead מתקיימת, לא רק "הוספתי טסט" או "שיניתי משהו".
6. **שאלות תוך כדי ביצוע** — כל שאלה שעולה במהלך העבודה (החלטת עיצוב לא ברורה, סתירה, ניסוח לא חד-משמעי בתוכנית, ספק לגבי כוונת ה-Tech Lead) **נשאלת למשתמשת ישירות** ולא מוכרעת עצמאית בניחוש. זה חל גם על החלטות קטנות-לכאורה, לא רק על שינויי עיצוב גדולים.
7. **קומיט לכל משימה קטנה שהושלמה** — הפרויקט הוא Git Repository (Branch `chain/ruthHoltzman/dependency-repository-66`, תחת `Resonance-Flow`). אחרי כל צעד קטן שמסתיים במצב ירוק (למשל: שינוי קובץ אחד + הטסטים הרלוונטיים לו עוברים + Definition of Done של אותו חלק מתקיים) — מבצעים Commit נפרד וממוקד, עם הודעת Commit קצרה שמתארת מה בדיוק תוקן (למשל, מתייחסת למספר ההערה מה-Code Review). **לא** מצברים כמה שלבים לקומיט אחד גדול, ו**לא** מבצעים Commit על קוד שלא מתקמפל או שהטסטים שלו לא עוברים. **הוחלט (ע"י המשתמשת):** להמשיך ברצף — Commit נוצר אוטומטית אחרי כל צעד קטן שמסתיים בהצלחה, בלי לעצור לאישור לכל Commit בנפרד. (שים לב: אין לבצע `git push` בלי אישור מפורש בנפרד — הרשאת ה-Commit הרציף לא כוללת Push לריפוזיטורי המרוחק.)

---

## שלב 1 — Domain Model: Immutability אמיתית וולידציה (`Task`, `TaskDependency`, `TaskStatus`)

**מטרה:** לסגור את הערה [Critical] #1 והערה [Significant] #2 בשורש הבעיה — הפיכת שני ה-DTOs ל-Immutable אמיתיים. גם לסגור את הערה [Medium] #5 (ולידציית `status`).

**צעדי ביצוע:**

1. **אימות Immutability אמיתית לפני שמכריזים על המחלקה כ-Immutable** (לא מספיק "אין Setters" כהוכחה):
   - כל שדות `Task` נבדקו: `id`/`parentTaskId` (`UUID`), `ownerId` (`String`), `status` (`enum`), `isReady` (`boolean`), `blockingCount`/`openConstraintCount` (`int`), `estimatedDurationMin`/`score` (`Integer`/`Double`), `createdAt`/`updatedAt`/`deletedAt` (`LocalDateTime`) — **כולם טיפוסים Immutable מטבעם** (לא `java.util.Date`, לא Collection, לא אובייקט Mutable). כלומר אין צורך ב-Defensive Copy נוסף מעבר להסרת ה-Setters עצמם — אין Getter שמחזיר Reference לאובייקט שניתן לשנות מבחוץ.
   - כנ"ל ל-`TaskDependency`: שני השדות הם `UUID` (Immutable).
   - **כל שדה state חייב להיות מוגדר `private final` בפועל** — לא מספיק שאין לו Setter. `id` ו-`createdAt` כבר `final` היום; שאר השדות (`parentTaskId`, `ownerId`, `status`, `isReady`, `blockingCount`, `openConstraintCount`, `estimatedDurationMin`, `score`, `updatedAt`, `deletedAt`) **יש להוסיף להם את מילת המפתח `final`** בהצהרת השדה, לא רק להסיר את ה-Setter שלהם. זו ההוכחה הקומפילטורית ל-Immutability, לא רק מוסכמה.
   - יש לוודא בפועל (לא רק בהנחה) שאין Getter חדש/קיים שנשכח וחושף State מוטבע, ושה-Builder של `Task` לא מאפשר לעקוף Invariants (הבנייה עדיין עוברת דרך הבנאי הפרטי שמכיל את כל הבדיקות — ראה סעיף 4 להלן).

2. **`Task.java`:**
   - הסרת כל ה-Setters הציבוריים: `setParentTaskId`, `setOwnerId`, `setStatus`, `setReady`, `setBlockingCount`, `setOpenConstraintCount`, `setEstimatedDurationMin`, `setScore`, `setUpdatedAt`, `setDeletedAt`.
   - הוספת `final` לכל שדה שמפורט בסעיף 1 לעיל.
   - **אין מוסיפים שום `withX()` חדש ל-`Task`.** נבדק: `Task.Builder` הקיים כבר מסוגל לבנות כל וריאציה של `Task` (כולל "אותו Task עם שדה אחד משתנה") דרך שרשור הערכים הקיימים מ-Getters לתוך `Builder` חדש — אין צורך אמיתי ב-`withX()` לא מ-Production (אין היום אף קריאה ל-`update()` בקוד הפרודקשן) ולא מה-Domain עצמו. הצורך היחיד שהיה קיים היה נוחות הכתיבה של טסט אחד — וזו לא סיבה מספיקה להוסיף API ל-Production (לא מוסיפים Production API רק כדי להתאים Test קיים). במקום זאת, הטסט עצמו נכתב מחדש (ראה "אסטרטגיית בדיקות" למטה) כך שה-Mutator שמועבר ל-`TaskRepository.update(id, mutator)` בונה את ה-`Task` המעודכן דרך `Task.builder()` הקיים, ולא דרך `withX()` חדש.
   - הוספת ולידציית `null` על `status` בבנאי הפרטי:
     ```java
     if (status == null) {
         throw new IllegalArgumentException("status must not be null");
     }
     ```
   - יישור עיצוב: `getOwnerId()`/`getScore()` כרגע בשורה אחת דחוסה (`{return ownerId;}`) בזמן שרוב שאר ה-Getters מרובי-שורות — ליישר לפורמט האחיד (רק Getters נשארים, אחרי הסרת Setters).
   - **`copy()` — נבדק בחיפוש בכל ה-Workspace:** הוא נמצא בשימוש **רק** בתוך `InMemoryTaskRepository.java` (4 קריאות: בנאי, `findById`, `findAllById`, `findByParentTaskId`, `save`, `update`), אין שום קורא נוסף בשום מודול. לאחר ש-`Task` הופך Immutable, `copy()` נהיה מבחינה פונקציונלית מיותר (מחזיר מופע חדש עם ערכים זהים, בלי שום תועלת הגנתית נוספת מעבר למה ש-Immutability כבר נותנת). **אין מוחקים אותו כחלק מהתוכנית הזו** — זה לא אחת מ-8 ההערות, ומחיקתו תחייב לגעת גם ב-`InMemoryTaskRepository` שלא נדרש שינוי בו. מתועד כ-Follow-up בסיכום הסופי: "`Task.copy()` הפך מיותר לאחר Immutability, ניתן לשקול הסרתו ופישוט `InMemoryTaskRepository` בנפרד".

3. **`TaskDependency.java`:**
   - הפיכת `taskId` ו-`dependsOnTaskId` ל-`final`, הסרת `setTaskId`/`setDependsOnTaskId`.
   - הסרת הבנאי חסר-הארגומנטים — **לפני ההסרה**, חיפוש גלובלי מוודא שאין שימוש בו מחוץ למחלקה (נבדק: אין `@RestController`/`@RequestBody`/`ObjectMapper` במודול, אז אין Jackson Binding שתלוי בו). נשארת רק `TaskDependency(UUID taskId, UUID dependsOnTaskId)`.
   - `equals`/`hashCode` נשארים זהים — עכשיו בטוחים במלואם, כי השדות שמשתתפים בהם לא יכולים להשתנות אחרי הבנייה (פותר ישירות את בעיית ה-Hash Drift).

4. **`TaskStatus.java`:** נבדק ונמצא נקי — אין `;` מיותר, אין רווחים חריגים. **אין צורך בשינוי.**

**אחריות לבטיחות בריבוי חוטים (ניסוח מדויק):**
- **לא** מנסחים זאת כ"`Task` הוא Thread-Safe" בצורה גורפת — `Task` לא מבצע שום פעולה מסונכרנת, ואין לו State הדורש הגנה בזמן ריצה. הניסוח הנכון: **מופעי `Task`/`TaskDependency` הם Immutable (כל השדות `final`, מאומתים ב-Constructor), ולכן ניתן לשתף אותם בין Threads לצורך קריאה בלי צורך בסנכרון על ה-State שלהם.**
- שלושת המרכיבים שמייצרים את הערובה: **Immutability** (אין דרך "חוקית" לשנות שדה אחרי הבנייה) + **Final Fields** (מבטיח מבחינת Java Memory Model שהערכים לא "יזלגו" ב-State חלקי) + **Safe Publication** (מופע ש"נבנה" במלואו לפני שנחשף לחוט אחר תמיד נראה בשלמותו, בזכות הסמנטיקה של `final`).
- זה מבטל לחלוטין את וקטור ה-Hash Drift ב-#2 (השדות שמשתתפים ב-`equals`/`hashCode` לא יכולים להשתנות אחרי שהאובייקט כבר הוכנס ל-`Set`/`Map`).

**אסטרטגיית בדיקות:**
- טסט: בנאי `Task` זורק `IllegalArgumentException` כש-`status == null` (גם דרך `Builder.build()`).
- עדכון `InMemoryTaskRepositoryTest`: כל קריאה קיימת ל-`task.setBlockingCount(...)` (כולל בתוך ה-mutator שמועבר ל-`update()` בטסט ה-Concurrency הקיים) נכתבת מחדש כך שהיא בונה `Task` חדש דרך `Task.builder()` הקיים — שרשור כל הערכים הנוכחיים מ-Getters, עם `blockingCount` בלבד מוחלף בערך המעודכן. ההתנהגות הנבדקת (אטומיות `update`) לא משתנה; אין תוספת API ל-`Task` עצמו.
- לפני מחיקת הבנאי הריק וה-Setters של `TaskDependency` — לוודא בחיפוש גלובלי (לא רק ב-`TaskDependencyServiceTest`/`InMemoryTaskDependencyRepositoryTest`) שאין שימוש בהם.
- **Gate:** קומפילציה מלאה של המודול אחרי הסרת כל Setter/Constructor.

**Checklist:**
- [ ] אימות שכל שדות `Task`/`TaskDependency` הם Immutable מטבעם (בוצע — ראה סעיף 1 לעיל)
- [ ] הסרת Setters מ-`Task`
- [ ] כל שדות ה-state של `Task` מוגדרים `private final`
- [ ] כל שדות ה-state של `TaskDependency` מוגדרים `private final`
- [ ] **אין** מתווסף `withX()`/`withBlockingCount` חדש ל-`Task` — Builder הקיים מספיק
- [ ] ולידציית `status != null` בבנאי הפרטי
- [ ] יישור פורמט Getters ב-`Task`
- [ ] הסרת Setters והבנאי הריק של `TaskDependency` (אחרי חיפוש שימושים בכל ה-Workspace)
- [ ] עדכון ה-Mutator ב-`InMemoryTaskRepositoryTest` לבנייה מחדש דרך `Task.builder()` (במקום Setter או `withX`)
- [ ] תיעוד Follow-up: `Task.copy()` הפך מיותר לאחר Immutability (לא נמחק בתוכנית הזו)
- [ ] טסטים חדשים: null-check על `status`
- [ ] קומפילציה מלאה של המודול

---

## שלב 2 — שדרוג `InMemoryTaskDependencyRepository`: מבנה נתונים יעיל + חוזה אטומי

**מטרה:** לסגור את הערה [Significant] #4 (מבנה נתונים לא יעיל) **וגם** להניח תשתית לפתרון הערה [Significant] #3 (Race Condition בשירות) — תלויים זה בזה, ולכן באותו שלב.

**צעדי ביצוע:**

1. **מבנה נתונים חדש** — מפת-שכנויות כפולה (Dual Adjacency Map) במקום `List<TaskDependency>` יחיד:
   ```java
   private final ConcurrentHashMap<UUID, Set<UUID>> dependenciesByTask;   // taskId -> {dependsOnTaskId,...}
   private final ConcurrentHashMap<UUID, Set<UUID>> dependentsByTask;     // dependsOnTaskId -> {taskId,...}
   private final Object writeLock = new Object();
   ```
   - כל Set פנימי: `ConcurrentHashMap.newKeySet()`, מוקצה Lazily דרך `computeIfAbsent`.
   - **קריאות** (`findByTaskId`, `findByDependsOnTaskId`) — `map.getOrDefault(id, emptySet())` ומיפוי לרשימה. **ללא נעילה כלל.**
   - **כתיבות** (`save`, `delete`) — מוגנות ב-`synchronized (writeLock)`, כי כתיבה חייבת לעדכן את שתי המפות יחד.
   - **Invariant מפורש שחייב להישמר בכל `save`/`delete` (לתעד ב-Javadoc של המחלקה):**
     ```text
     For every dependency A -> B:
         B ∈ dependenciesByTask[A]   AND   A ∈ dependentsByTask[B]
     ```
     כלומר: קשת קיימת אם ורק אם היא מופיעה בשתי המפות בו-זמנית. `save` מוסיף ל-**שתי** המפות בתוך אותו בלוק `synchronized`; `delete` מסיר מ-**שתיהן** באותו בלוק. אסור למצב שבו קשת קיימת רק במפה אחת — זו הפרת Invariant.

2. **דיוק בתיעוד סיבוכיות (לא לכתוב "O(1)" גורף):**
   ```text
   Map lookup (get)        → O(1) average
   Iterating + מיפוי לרשימה → O(k)
   -----------------------------------
   Total per find call     → O(k)  (k = מספר ה-dependencies הרלוונטיים לאותו taskId)
   ```
   הגישה למפה עצמה O(1) בממוצע וללא נעילה — זה השיפור המרכזי לעומת הסריקה הליניארית + נעילה גלובלית הקיימת. אבל אין לתאר את הפעולה כולה כ-O(1).

3. **תיעוד מפורש של מה ה-Atomicity באמת מבטיחה (ולא יותר מזה):**
   - כתיבה (`save`/`delete`) היא אטומית **ביחס לכתיבות אחרות** (בעזרת `writeLock`) — שני האינדקסים מתעדכנים יחד בתוך אותה נעילה.
   - כל קריאה (`find...`) מתבצעת **ללא נעילה**.
   - **אין** התחייבות ש-Reader יקבל Snapshot גלובלי עקבי של שתי המפות יחד. בפועל זה לא בעיה כרגע כי כל מתודת Read נוגעת רק במפה אחת (`findByTaskId` → `dependenciesByTask` בלבד, `findByDependsOnTaskId` → `dependentsByTask` בלבד) — אין היום שום Caller שדורש קריאה מסונכרנת משתי המפות יחד. יש לתעד את המגבלה הזו במפורש ב-Javadoc, לא להשאיר אותה משתמעת. אם בעתיד ייווסף Caller שכן צריך Snapshot חוצה-מפות (למשל מתודה שמחזירה גם dependencies וגם dependents יחד באטומיות) — יש לעצור ולבחון Design אחר (למשל קריאה גם היא בתוך `writeLock`, או מבנה נתונים יחיד), ולא להניח שהפתרון הנוכחי מספיק.

4. **שינוי חוזה הממשק `TaskDependencyRepository`**, עם Javadoc מפורש לערכי ההחזרה:
   ```java
   /**
    * @return Optional.of(dependency) אם הקשת נוצרה בפועל (לא הייתה קיימת קודם).
    *         Optional.empty() אם הקשת כבר הייתה קיימת ולא בוצע שינוי (no-op).
    */
   Optional<TaskDependency> save(TaskDependency dependency);

   /**
    * @return true אם קשת כזו הייתה קיימת ונמחקה בפועל.
    *         false אם לא הייתה קיימת קשת כזו ולא בוצע שינוי.
    */
   boolean delete(UUID taskId, UUID dependsOnTaskId);
   ```
   - `InMemoryTaskDependencyRepository.save`/`delete` מיישמים בדיקה+עדכון בתוך אותו בלוק `synchronized (writeLock)` — אין חלון זמן בין "בדיקה אם קיים" ל"הוספה/הסרה בפועל".
   - **לפני השינוי:** חיפוש גלובלי בכל המודול (כולל טסטים) אחר קריאות קיימות ל-`TaskDependencyRepository.save`/`delete`, כדי לוודא שכולן מתעדכנות לחתימה החדשה.

**אחריות לבטיחות בריבוי חוטים:** ראה סעיף 3 לעיל — זה התיעוד המדויק, לא "הכל אטומי ובטוח" בצורה גורפת.

**אסטרטגיית בדיקות:**
- עדכון הטסטים הקיימים לחוזה החדש (`save` מחזיר `Optional`, `delete` מחזיר `boolean`).
- **טסט פונקציונלי חדש לעקביות בין שתי המפות** (לא Concurrency — טסט Single-Threaded פשוט שמוכיח את ה-Invariant מסעיף 3):
  ```text
  save(A -> B)
      findByTaskId(A)          => מכיל B
      findByDependsOnTaskId(B) => מכיל A
  delete(A -> B)
      findByTaskId(A)          => ריק
      findByDependsOnTaskId(B) => ריק
  ```
- **טסטי Concurrency** (דפוס קיים: `ExecutorService` + `CountDownLatch`, עם **Start Gate משותף** — כל החוטים ממתינים ל-Latch אחד ומשוחררים יחד, כדי למקסם את חלון התחרות; **ללא** `Thread.sleep` בשום מקום; Assert גם על **מספר ההצלחות וגם על מספר הכשלונות**, לא רק "לא קרס"):
  - N חוטים `save()` עם אותה קשת → בדיוק תוצאה אחת `Optional.of(...)`, כל השאר `Optional.empty()`, ובסוף קשת יחידה ב-Repository.
  - N חוטים `delete()` עם אותה קשת קיימת → בדיוק `true` אחד.
  - חוטים מקבילים שמוסיפים קשתות שונות עם אותו `taskId` → אין Lost Updates, כל הקשתות קיימות בסוף.
  - הרצות חוזרות (3-5 פעמים) הן רק לאיתור Flakiness — **לא** הוכחת ה-Correctness עצמה; ההוכחה היא ה-Design (הנעילה סביב הבדיקה+העדכון יחד).
- **Gate:** קומפילציה מלאה + כל הטסטים הקיימים שקוראים ל-`save`/`delete` מתעדכנים ועוברים.

**Checklist:**
- [ ] חיפוש גלובלי אחר שימושים קיימים ב-`TaskDependencyRepository.save/delete` לפני שינוי החתימה
- [ ] מעבר ל-Dual Adjacency Map ב-`InMemoryTaskDependencyRepository`
- [ ] שינוי חתימת `save`→`Optional<TaskDependency>`, `delete`→`boolean`, עם Javadoc מפורש לערכי ההחזרה
- [ ] תיעוד מדויק של Big-O (O(1) average lookup + O(k) materialize, לא "O(1)" גורף)
- [ ] תיעוד מפורש של גבולות ה-Atomicity (Write-vs-Write אטומי, Read ללא נעילה, אין Cross-Map Snapshot)
- [ ] תיעוד ה-Invariant של שתי המפות ב-Javadoc של המחלקה
- [ ] טסט פונקציונלי חדש: `save`/`delete` שומרים על עקביות בין שתי המפות (שני הכיוונים)
- [ ] עדכון טסטים קיימים לחוזה החדש
- [ ] טסטי Concurrency חדשים עם Start Gate, בלי Sleep, עם Assert על הצלחות+כשלונות
- [ ] קומפילציה מלאה של המודול

---

## שלב 3 — סנכרון שכבת ה-Service: ביטול ה-Race Condition ב-`addDependency`/`removeDependency`

**מטרה:** לסגור את הערה [Significant] #3 — לוודא ש-`addDependency`/`removeDependency` אינם עוד Check-Then-Act לא-אטומי, תוך ניצול החוזה האטומי מ-שלב 2.

**צעדי ביצוע:**

1. **`addDependency`** — הסרת הבדיקה הנפרדת `findByTaskId(...).anyMatch(...)` שמתבצעת לפני `save`. שימוש ישיר בתוצאה האטומית:
   ```java
   return taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId))
           .orElseThrow(() -> new DuplicateDependencyException(
                   "Task " + taskId + " already depends on " + dependsOnTaskId));
   ```
   בדיקת self-dependency ו-`requireTaskExists` לשני הצדדים **נשארות** — אלו לא חלק מה-Race שזוהה בהערה #3.

2. **`removeDependency`** — הסרת הבדיקה הנפרדת לפני `delete`. שימוש ישיר בתוצאה:
   ```java
   boolean removed = taskDependencyRepository.delete(taskId, dependsOnTaskId);
   if (!removed) {
       throw new DependencyNotFoundException(
               "No dependency from " + taskId + " to " + dependsOnTaskId);
   }
   ```

3. **Assumption שיש לתעד במפורש, לא לתקן:** `requireTaskExists(taskId)`/`requireTaskExists(dependsOnTaskId)` מתבצעים **לפני** ה-`save` האטומי. אם Task יכול להימחק (Soft-Delete דרך `TaskRepository.update()`, שקיים כבר בממשק) **בו-זמנית** בין ה-Check הזה ל-`save`, יש כאן TOCTOU נוסף שלא נסגר ע"י שלב 2 (כי הוא בין שני Repository שונים — `TaskRepository` ו-`TaskDependencyRepository` — ואין נעילה משותפת ביניהם). **זו לא אחת מ-8 הערות ה-Tech Lead, ולכן לא מתוקנת בתוכנית הזו** (לפי כלל "אל תרחיב Scope") — אבל מתועדת במפורש כ-Assumption/Follow-up בסיכום הסופי, ולא מוסתרת בשתיקה.

**אחריות לבטיחות בריבוי חוטים:**
- לפני התיקון: שני חוטים שמנסים להוסיף בו-זמנית את אותה קשת יכולים שניהם לעבור את בדיקת ה-`alreadyExists` (כי היא נקראת לפני שמישהו שמר בפועל) — ה-Idempotency הפנימי מונע כפילות בנתונים, אבל חוט אחד "מפסיד" את ה-`DuplicateDependencyException` שהוא אמור לקבל לפי החוזה המתועד.
- אחרי התיקון: הבדיקה וההוספה קורות בפעולה אטומית אחת בתוך ה-Repository — כל קריאה מקבילה מקבלת תשובה נכונה ודטרמיניסטית.

**אסטרטגיית בדיקות:**
- **הטסט הכי חשוב בתוכנית כולה:** N חוטים קוראים `addDependency(taskId, dependsOnTaskId)` עם אותו זוג בו-זמנית (Start Gate משותף, בלי Sleep) → בדיוק חוט אחד מצליח, בדיוק N-1 תופסים `DuplicateDependencyException`, ובסוף קשת אחת בלבד קיימת.
- טסט סימטרי ל-`removeDependency`: N חוטים מנסים להסיר קשת אחת שקיימת → בדיוק `true` אחד, בדיוק N-1 תופסים `DependencyNotFoundException`, ובסוף הקשת לא קיימת.
- הטסטים הפונקציונליים הקיימים (self-dependency, task-not-found, duplicate rejection, not-found on remove) נשארים ועוברים ללא שינוי בהתנהגות הנצפית.
- **Gate:** קומפילציה מלאה + כל טסטי `TaskDependencyServiceTest` הקיימים עוברים.

**Checklist:**
- [ ] `addDependency` משתמש בערך ההחזרה האטומי של `save`
- [ ] `removeDependency` משתמש בערך ההחזרה האטומי של `delete`
- [ ] טסט Concurrency: N חוטים, אותה קשת, `addDependency` — הצלחה יחידה + N-1 `DuplicateDependencyException` + מצב סופי נכון
- [ ] טסט Concurrency: N חוטים, אותה קשת, `removeDependency` — `true` יחיד + N-1 `DependencyNotFoundException` + מצב סופי נכון
- [ ] תיעוד ה-Assumption של Task-deletion-race בסיכום הסופי (Follow-up, לא תיקון)
- [ ] כל הטסטים הפונקציונליים הקיימים עדיין עוברים

---

## שלב 4 — שיפורי חריגות (Exceptions)

**מטרה:** לסגור את החלק הרלוונטי בהערה [Cosmetic/Polish] #8 — הוספת בנאי `(String message, Throwable cause)`.

**צעדי ביצוע:**
- הוספת הבנאי הבא בלבד לכל אחת מ-`DependencyNotFoundException`, `DuplicateDependencyException`, `InvalidDependencyException`, `TaskNotFoundException`:
  ```java
  public XxxException(String message, Throwable cause) {
      super(message, cause);
  }
  ```
- **ללא לוגיקה נוספת, ללא Refactoring של היררכיית החריגות** — זה לא חלק מהמשימה.

**אסטרטגיית בדיקות:**
- לכל אחת מ-4 המחלקות: יצירה עם `(message, cause)`, אימות ש-`getMessage() == message` וש-`getCause() == cause`.
- **אופציונלי:** מכיוון שאין כרגע Convention קיים של `@ParameterizedTest` בקבצי הטסט של המודול, ניתן לכתוב את הבדיקה כטסט אחד עם `@ParameterizedTest` על פני ארבעת ה-Exception Classes (במקום 4 טסטים כמעט זהים) — **רק אם** זה משתלב בצורה נקייה עם מבנה הטסטים הקיים בפועל בזמן הביצוע; אחרת, 4 טסטים נפרדים וקצרים עדיפים על פני מבנה מאולץ. בכל מקרה — ללא Refactoring נוסף מעבר לבדיקת `message`/`cause`.

**Checklist:**
- [ ] `DependencyNotFoundException(String, Throwable)`
- [ ] `DuplicateDependencyException(String, Throwable)`
- [ ] `InvalidDependencyException(String, Throwable)`
- [ ] `TaskNotFoundException(String, Throwable)`
- [ ] טסטים לבנאים החדשים

---

## שלב 5 — ניקיון קונפיגורציה: `MockDataConfig`

**מטרה:** לסגור את הערה [Minor] #6 (הגנה על Beans). ה-TODOs מוסרים לגמרי (הוחלט ע"י המשתמש), ללא מספרי טיקט.

**צעדי ביצוע:**
1. הסרת שתי הערות ה-`// TODO: populate with sample data` לגמרי, **ללא** מספר טיקט — יתועד מחוץ לקוד (PR description) שמילוי seed data אמיתי הוא Backlog item נפרד.
2. הגנה על שני ה-`@Bean`-ים באמצעות `@ConditionalOnMissingBean` בלבד (זה מה שהערת ה-Tech Lead בפועל דרשה — "`@Profile` **או** `@ConditionalOnMissingBean`"):
   ```java
   @Bean
   @ConditionalOnMissingBean(TaskRepository.class)
   public TaskRepository taskRepository() {
       return new InMemoryTaskRepository(List.of());
   }

   @Bean
   @ConditionalOnMissingBean(TaskDependencyRepository.class)
   public TaskDependencyRepository taskDependencyRepository() {
       return new InMemoryTaskDependencyRepository(List.of());
   }
   ```
   **הוסר מהתוכנית:** `@Profile("!prod")` — אומת בפועל שאין בפרויקט הזה שום `@Profile`, `spring.profiles.active`, או קובץ `application-*.yml` (רק `application.yml` גנרי יחיד). המצאת Convention של "prod" רק בשביל המשימה הזו הייתה טעות בטיוטה הקודמת. אם רוצים הפרדת Profile בעתיד — זו החלטת Convention שהצוות צריך לקבל בנפרד, לא חלק מהמשימה הזו.

**אסטרטגיית בדיקות:**
- טסט Context (Spring) שמוכיח בפועל, לא רק "לא זרק שגיאה", לפי התבנית הבאה עבור **כל אחד** מ-`TaskRepository`/`TaskDependencyRepository`:
  ```text
  Given: Bean מותאם-אישית מסוג TaskRepository רשום ב-Context (למשל @TestConfiguration)
  When:  Spring טוען את MockDataConfig
  Then:  MockDataConfig לא יוצר Bean שני מאותו סוג —
         context.getBeansOfType(TaskRepository.class).size() == 1
         וה-Bean היחיד הקיים הוא זה שהוגדר ב-@TestConfiguration (זהות/Reference, לא רק טיפוס)
  ```
  כלומר לא מסתפקים ב"ה-Context עלה בהצלחה" — בודקים במפורש את מספר ה-Beans מהסוג הרלוונטי (בדיוק 1) ואת זהות ה-Bean שבו נעשה שימוש בפועל.

**Checklist:**
- [ ] הסרת שני ה-TODOs מ-`MockDataConfig.java`, ללא מספר טיקט
- [ ] `@ConditionalOnMissingBean` על שני ה-Beans
- [ ] **לא** להוסיף `@Profile` (אין Convention קיים בפרויקט)
- [ ] תיעוד חוץ-קודי (PR description) שמילוי seed data הוא Backlog item נפרד
- [ ] טסט Context ל-`TaskRepository`: Bean מותאם-אישית קיים ⇒ בדיוק Bean אחד מהסוג, וזה ה-Bean המותאם-אישית
- [ ] טסט Context מקביל ל-`TaskDependencyRepository`

---

## שלב 6 — תיקון תיעוד: `README.md`

**מטרה:** לסגור את הערה [Minor] #7 — סתירת גרסת Java.

**צעדי ביצוע:**
- אומת מול המקור-של-אמת: `pom.xml` מגדיר `<java.version>11</java.version>`, וה-`Dockerfile` משתמש ב-`maven:3.9.6-eclipse-temurin-11` וב-`eclipse-temurin:11-jre` — **11** היא הגרסה הנכונה בפועל.
- תיקון שורה 10 ב-`README.md` (Stack section) מ-"Java 21" ל-"Java 11", כך שתתאים לשורה 35 ("...JDK 11...") ולקונפיגורציית ה-Build בפועל.

**Checklist:**
- [ ] תיקון "Java 21" ל-"Java 11" בשורה 10 של `README.md`

---

## שלב 7 — הרצת בדיקות מלאה, סקירה סופית וסיכום

**מטרה:** לוודא שכל השינויים משתלבים יחד, אין רגרסיות, וכל 8 ההערות המקוריות מכוסות בפועל (לא רק "טופלו על הנייר").

**צעדי ביצוע לפני סיום:**
1. `mvn test` (או `mvn verify`) מלא על מודול `chain`.
2. חיפוש גלובלי אחר Setters שהוסרו — לוודא שלא נשאר שימוש שבור.
3. חיפוש גלובלי אחר שימושים ישנים ב-`TaskDependencyRepository.save/delete` (חתימה ישנה) שנשכחו מעדכון.
4. אימות ש-`Task` אכן נשאר בלי שום `withX()` חדש (Builder הקיים הוא מקור השינוי היחיד).
5. בדיקה חוזרת שכל השדות ב-`Task`/`TaskDependency` עומדים בדרישות Immutability.
6. הרצת טסטי ה-Concurrency 3-5 פעמים ברצף לאיתור Flakiness (לא כהוכחת Correctness — ראה כללי ביצוע גורפים).
7. מעבר ידני על כל 8 הערות ה-Code Review המקוריות מול הקוד המתוקן, עם המיפוי הבא, ווידוא שכל אחת נסגרה בפועל:

   | # | הערה | שלב |
   |---|------|-----|
   | 1 | Thread-Safety ב-Task | שלב 1 |
   | 2 | שדות משתנים ב-TaskDependency | שלב 1 |
   | 3 | Race Condition בשירות | שלב 3 |
   | 4 | מבנה נתונים לא יעיל | שלב 2 |
   | 5 | חוסר ולידציה ל-status | שלב 1 |
   | 6 | TODOs + הגנת Beans | שלב 5 |
   | 7 | סתירת גרסת Java ב-README | שלב 6 |
   | 8 | Cosmetic (רווחים, Getters/Setters, בנאי Exception) | שלבים 1 + 4 |

**Checklist:**
- [ ] `mvn test` ירוק במלואו
- [ ] חיפוש גלובלי אחר Setters/API ישן שהוסרו — אין שימוש שבור
- [ ] הרצת טסטי Concurrency 3-5 פעמים לבדיקת יציבות
- [ ] מיפוי מלא של 8 ההערות מול הקוד המתוקן

**פורמט הסיכום הסופי שיוצג בסוף הביצוע (לא חלק מה-Plan עצמו, אלא הנחיה לדיווח):**
```text
## Implemented
- ...

## Tests
- ...

## API Changes
- ...

## Concurrency Guarantees
- ...

## Assumptions
- (למשל: Task existence לא נבדק אטומית מול מחיקה מקבילה — ראה שלב 3)

## Follow-ups / Out of Scope
- ...
```
אין להציג שינוי כ-"Fixed" בלי להוכיח (קוד + טסט) שדרישת ה-Code Review המקורית אכן מתקיימת.
