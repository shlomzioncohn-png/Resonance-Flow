# plan.md — Audit & History Mechanism for Dependency Changes (Chain)

## הקשר (Context)

המטרה: לממש מנגנון מעקב והיסטוריה עבור שינויי תלויות ב-`chain` — כל קריאה ל-`TaskDependencyService.addDependency`/`removeDependency` שמצליחה בפועל (יוצרת/מסירה קשת) חייבת לייצר רשומת היסטוריה, בתוך אותה עסקה אטומית עם השינוי עצמו, עם תמיכה ב-Idempotency ו-API לקריאה עם Pagination.

**Issue #153.** Branch: `chain/ruthHoltzman/dependency-change-history` (מעודכן, ראה סעיף Git למטה).

**ממצאים מהחקירה בקוד (רלוונטיים לכל שלב בתוכנית):**

1. **אין שום DB בפרויקט** — לא ב-`chain`, לא ב-`bridge`, לא ב-`inbox`. אין JPA/JDBC driver/migration tool באף `pom.xml`. ה-[README](README.md) של `chain` אומר במפורש: "No database is configured yet - the DB choice for the project has not been decided." **הוחלט (איתך):** לא ממציאים החלטת DB בתוכנית הזו. בונים שכבת Persistence מופשטת (Port), בדיוק כמו הדפוס הקיים ב-[`TaskDependencyRepository`](src/main/java/com/rnd/chain/repository/TaskDependencyRepository.java) — Interface נקי + מימוש In-Memory אטומי (`synchronized`/`ConcurrentHashMap`), כך שכש-DB אמיתי ייבחר, רק המימוש מוחלף.
2. **אין שום Controller קיים היום ב-`main`** — תיקיית `controller/` ריקה (`.gitkeep` בלבד) בשלושת המודולים. בדקתי גם קונטרולר WIP של חברת צוות (branch `Riki-Kaplan-API`, `ChainController.java`) — **גם שם אין שום Pagination** (כל ה-GET-ים מחזירים `List<T>` פשוט, בלי `page`/`size`). כלומר אין מוסכמת Pagination קיימת לחקות בשום מקום בריפו. **הוחלט (איתך):** קובעים מוסכמה חדשה — Offset-based, `page`/`size` כ-Query Params, עטיפת תשובה `{content, page, size, totalElements, totalPages}` (בהשראת Spring Data `Page`, בלי תלות חדשה ב-Spring Data עצמו — DTO פשוט).
   - מאותו קונטרולר WIP: `ConstraintService.createConstraint(taskId, type, reason, sourceRef)` — כלומר `sourceRef` הוא כבר מונח מוכר בקוד הזה — תקדים טוב לשם השדה. **הערה:** אותו קונטרולר WIP משתמש ב-`javax.validation.Valid`, אבל זה מבוסס Spring Boot 2.7 הישן — **לא** רלוונטי לענף שלנו יותר (ראה סעיף 4 להלן, אנחנו על Boot 3.3.13/`jakarta`).
3. **אין שום מנגנון Request/Event ID קיים** — אין Kafka, אין correlation-id header, אין Idempotency-Key. **הוחלט (איתך):** `source_ref` הוא ה-Idempotency Key. **חובה** כש-`source` הוא `MIND`/`SYSTEM` (המקורות שחשופים ל-Retry אוטומטי/At-least-once delivery), **אופציונלי** כש-`source` הוא `USER` (קריאה סינכרונית ישירה, לא צפויה ל-Redelivery אוטומטי) — עם תיעוד מפורש שקריאת USER בלי `source_ref` לא מקבלת הגנת Idempotency (מגבלה מתועדת ל-v1, לא Bug).
4. **Java 21 / Spring Boot 3.3.13** (עודכן ב-commit `d2d54ed`, לפי הנחיית מנחה — PR #144, finding #2; לא Java 11/Boot 2.7.18 כפי שהיה בזמן המחקר הראשוני). נגזרות משמעותיות:
   - `jakarta.validation` (לא `javax.validation`) — אם משתמשים ב-`@Valid` איפשהו בשכבת ה-API.
   - **שימו לב:** למרות המעבר ל-Java 21, `Task`/`TaskDependency` **נשארו** מחלקות רגילות (Builder + `equals`/`hashCode` ידניים) — **לא** הפכו ל-Records. זה סימן מוסכמה ברור: **אין** להפוך את `DependencyHistoryEntry` החדש ל-Record — נשארים עם אותו סגנון (Builder, בנאי פרטי עם ולידציה) לעקביות.
5. **קבצים/דפוסים קיימים שישמשו בסיס לעבודה:**
   - [`Task.java`](src/main/java/com/rnd/chain/dto/Task.java) — דפוס ה-DTO: שדות `private final`, ולידציה בבנאי הפרטי (`IllegalArgumentException`), `Builder` סטטי, `equals`/`hashCode` על `id` בלבד.
   - [`TaskDependencyRepository.java`](src/main/java/com/rnd/chain/repository/TaskDependencyRepository.java) + [`InMemoryTaskDependencyRepository`](src/main/java/com/rnd/chain/repository/InMemoryTaskDependencyRepository.java) — דפוס ה-Repository: Interface נקי מ-Business Rules, מימוש עם Dual Adjacency Map + `synchronized (writeLock)`, `save`/`delete` מחזירים `Optional`/`boolean` שמבטאים אם באמת בוצע שינוי (לא Exception לזרימת "no-op"). כולל ניקוי ערכי Map ריקים אחרי `delete` (`removeIfEmpty`).
   - [`TaskDependencyService.java`](src/main/java/com/rnd/chain/service/TaskDependencyService.java) — נקודות השילוב: `addDependency`/`removeDependency`/`getDependencies`/`getDependents`, עם `requireTaskExists` פרטי. ה-TOCTOU assumption (Task נמחק בו-זמנית בין הבדיקה ל-save) מתועד עכשיו כ-issue מעוקב: [#243](https://github.com/KamaTechOrg/Resonance-Flow/issues/243).
   - חריגות קיימות: `TaskNotFoundException`, `DuplicateDependencyException`, `DependencyNotFoundException`, `InvalidDependencyException` — כולן עם בנאי `(String)` ו-`(String, Throwable)`.
   - [`MockDataConfig.java`](src/main/java/com/rnd/chain/config/MockDataConfig.java) — `@Bean`-ים מוגנים ב-`@ConditionalOnMissingBean`, בלי `@Profile` (אין Convention כזה בפרויקט).
   - **סביבת בדיקות:** JUnit 5 + AssertJ (`spring-boot-starter-test`). דפוס Concurrency קיים כ-helper משותף **`ConcurrentTestRunner.run(threadCount, task)`** (חבילה `com.rnd.chain.testsupport`, ראה [`ConcurrentTestRunner.java`](src/test/java/com/rnd/chain/testsupport/ConcurrentTestRunner.java)) — Start Gate (`CountDownLatch` כפול, בלי `Thread.sleep`), Assert על מספר הצלחות **וגם** כשלונות/no-ops. **נעשה שימוש חוזר ב-`ConcurrentTestRunner` הקיים**, לא נכתוב helper מקביל.
6. **Git:** ✅ **הושלם.** `chain/ruthHoltzman/dependency-change-history` היה Stale (נפתח לפני 6+ commits של תיקוני Code Review על `dependency-repository-66`). בבדיקה התברר שמיזוג מלא של `dependency-repository-66` מכניס גם ~340 קבצים מצוותים אחרים (יש בו `Merge branch 'main'` שמביא איתו את כל ה-main history) — **לא בוצע** מיזוג מלא. במקום זאת: זוהו 25 commits ספציפיים ל-`chain` בלבד (`git rev-list --reverse --no-merges A..B -- teams/chain`), ו-**כולם עברו Cherry-pick בהצלחה** ישירות על `dependency-change-history` (Conflict יחיד, בשורת גרסת Java ב-README, נפתר לטובת Java 21). `mvn test` ירוק (Exit 0) על המצב הנוכחי של ה-branch. **הענף מוכן לתחילת עבודה.**
   - **הערה:** `PLAN.md` הישן נמחק מהמודול כחלק מאחד ה-commits שנמשכו (`chore(chain): remove PLAN.md scratch planning doc from the module`) — לכן קובץ התוכנית הזה יכול להיקרא סוף-סוף `plan.md` כמו שביקשת במקור, בלי התנגשות case-insensitive.

**קבצים מרכזיים (חדשים + קיימים שישתנו):**
- חדש: `dto/DependencyChangeAction.java`, `dto/DependencyChangeSource.java`, `dto/DependencyHistoryEntry.java`, `dto/PageResponse.java`
- חדש: `controller/DependencyHistoryResponse.java` (DTO API-layer נפרד מה-Domain Model — ראה שלב 4, סעיף 1)
- חדש: `repository/DependencyHistoryRepository.java`, `repository/InMemoryDependencyHistoryRepository.java`
- חדש: `repository/TransactionRunner.java`, `repository/InMemoryTransactionRunner.java`
- חדש: `controller/DependencyHistoryController.java`
- חדש: `exception/GlobalExceptionHandler.java` (`@RestControllerAdvice` ראשון בריפו כולו)
- משתנה: `service/TaskDependencyService.java`, `config/MockDataConfig.java`
- טסטים: חדשים לכל קובץ חדש + עדכון `TaskDependencyServiceTest` הקיים

---

## כללי ביצוע גורפים (חלים על כל השלבים)

1. **אין להרחיב Scope.** רק מה שנדרש למימוש המנגנון. בעיה שמתגלה תוך כדי עבודה ולא חלק מהמשימה — מתועדת ב"Follow-up", לא מתוקנת אוטומטית.
2. **חיפוש גלובלי בכל ה-Workspace** (לא רק במודול `chain`) לפני כל שינוי חתימה של API ציבורי קיים (`TaskDependencyService.addDependency`/`removeDependency`/constructor, `TaskDependencyRepository`). ידוע כבר: יש קונטרולר WIP על branch נפרד (`Riki-Kaplan-API`) שקורא ל-`addDependency(taskId, dependsOnTaskId)` בשני ארגומנטים — **יש לתאם איתו/ה** לפני מיזוג, ראה "החלטות שנעשו באופן חד-צדדי" בסוף המסמך (סעיף Overload לתאימות לאחור).
3. **Definition of Done לכל שלב:** מתקמפל, טסטים רלוונטיים עוברים, אין שימושים שבורים, ה-Behavior הקיים שלא נדרש לשינוי נשמר, Concurrency Contract נבדק היכן שרלוונטי.
4. **סתירה בין התוכנית לקוד בפועל** → עוצרים, מציגים את הסתירה, לא מנחשים.
5. **אין להכריז "Fixed"/"Done" בלי הוכחה** — קוד + טסט.
6. **שאלות תוך כדי ביצוע** נשאלות ישירות, לא מוכרעות בניחוש.
7. **Commit לכל צעד קטן שמסתיים ירוק**, הודעה ממוקדת. **אין `git push` בלי אישור מפורש נפרד.**

---

## שלב 1 — שכבת המודל וה-Persistence Port

**מטרה:** מודל נתונים Immutable להיסטוריית שינויי תלויות, מנותק לחלוטין מפרוטוקולי תקשורת (לא תלוי ב-Kafka/REST), ו-Interface Persistence מופשט (בלי DB אמיתי).

**צעדי ביצוע:**

1. **`DependencyChangeAction.java`** (enum): `ADDED`, `REMOVED`.
2. **`DependencyChangeSource.java`** (enum): `MIND`, `USER`, `SYSTEM`.
3. **`DependencyHistoryEntry.java`** — DTO Immutable, באותו דפוס בדיוק כמו `Task.java` (מחלקה רגילה, **לא** Record — ראה הערה בסעיף 4 של ה-Context):
   - שדות `private final`: `id` (`UUID`), `taskId` (`UUID`), `dependsOnTaskId` (`UUID`), `action` (`DependencyChangeAction`), `source` (`DependencyChangeSource`), `sourceRef` (`String`, **nullable**), `createdAt` (`LocalDateTime`).
   - בנאי פרטי + `Builder` סטטי (כמו `Task`).
   - ולידציה בבנאי הפרטי: `id`/`taskId`/`dependsOnTaskId`/`action`/`source`/`createdAt` לא `null` → `IllegalArgumentException`. **החלטת עיצוב מפורשת:** הכלל "`sourceRef` חובה כש-`source` הוא `MIND`/`SYSTEM`" **לא** נאכף כאן ב-DTO — הוא Business Rule (תלוי ב-Source policy שנקבעה בדיון), ונאכף בשכבת ה-Service (שלב 3), כך שה-DTO עצמו נשאר "טיפש" ותקף לכל קומבינציה חוקית של שדות, בדיוק כמו ש-`TaskDependency` לא בודק self-dependency.
   - `equals`/`hashCode` על `id` בלבד (כמו `Task`).
4. **`PageResponse.java`** — DTO גנרי לעטיפת תוצאות מדופדפות: `content` (`List<T>`), `page` (`int`), `size` (`int`), `totalElements` (`long`), `totalPages` (`int`). Immutable, בנאי ציבורי פשוט (לא צריך Builder — 4-5 שדות בלבד). **שימושי גם לנקודות קצה עתידיות אחרות**, לא ספציפי להיסטוריה — ולכן חי ב-`dto` הכללי, לא בחבילה ייעודית להיסטוריה. כולל מתודת `<R> PageResponse<R> map(Function<T, R> mapper)` — שומרת `page`/`size`/`totalElements`/`totalPages`, ממפה רק את `content` — משמשת בשלב 4 למיפוי `DependencyHistoryEntry`→`DependencyHistoryResponse` בלי לחשוף את ה-Domain Model לשכבת ה-API.
5. **`DependencyHistoryRepository.java`** (Interface, Persistence Port) — Javadoc מפורש על כל חוזה:
   ```java
   public interface DependencyHistoryRepository {

       /**
        * Records the given entry atomically. If entry.getSourceRef() is
        * non-null and an entry with the same (source, sourceRef) already
        * exists, this is a no-op — returns the EXISTING entry, not a new one.
        * If sourceRef is null, always inserts a new, distinct row (no dedup
        * possible without a key).
        */
       DependencyHistoryEntry record(DependencyHistoryEntry entry);

       /** true if an entry with this exact (source, sourceRef) already exists. */
       boolean existsBySourceAndSourceRef(DependencyChangeSource source, String sourceRef);

       /**
        * Chronologically deterministic order, newest first: createdAt
        * descending, id descending as tiebreaker (stable even for
        * same-millisecond writes). History is read newest-first, like a
        * git log or audit trail.
        */
       PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size);
   }
   ```
   **הערה:** `record` (לא `save`) — נמנעים מהתנגשות שם קונספטואלית עם `TaskDependencyRepository.save` שיש לו סמנטיקה שונה (`Optional.empty()` = לא נשמר). כאן תמיד מוחזר ערך תקף (קיים או חדש) כי אין מצב "כישלון שקט" מבחינת הקורא — ה-Idempotency logic (שלב 3) היא זו שמחליטה אם `record` בכלל נקרא.
6. **`InMemoryDependencyHistoryRepository.java`** — מימוש:
   - `ConcurrentHashMap<UUID, DependencyHistoryEntry>` (id → entry) לאחסון הראשי.
   - `ConcurrentHashMap<String, UUID>` נוסף למפתח `source.name() + "|" + sourceRef` → `id`, לבדיקת ה-Uniqueness Constraint על `(source, sourceRef)` ב-O(1) (כשה-`sourceRef != null`).
   - `record(entry)`: בלוק `synchronized` (כמו ב-`InMemoryTaskDependencyRepository`) — בדיקה+הוספה אטומית לשני ה-Maps יחד. אם `sourceRef != null` וכבר קיים מפתח תואם → מחזיר את ה-Entry הקיים (לא מוסיף). אם `sourceRef == null` → תמיד מוסיף (אין מפתח דדופ).
   - `findByTaskId(taskId, page, size)`: סינון + מיון לפי `(createdAt, id)` **יורד** (חדש קודם — ראה הערת Reviewer, אומץ), חיתוך ל-page/size. **תיעוד מפורש (באותו סגנון שנעשה ב-`InMemoryTaskDependencyRepository` הקיים):** לא "O(1)" גורף — O(n) סריקה על כלל ההיסטוריה (זמנית, עד שיהיה DB עם אינדקס על `task_id`), לתעד ב-Javadoc.
   - **Java 21 `SequencedCollection`:** אחרי המיון (`Stream.sorted(...).collect(Collectors.toList())` — ה-`List` שמתקבל כבר מממש `SequencedCollection` מובנה), משתמשים ב-`.reversed()`/`.getFirst()`/`.getLast()` היכן שרלוונטי במקום לבנות `Comparator` יורד נפרד או לוגיקת אינדקסים ידנית — נקי יותר וזה בדיוק המקום הטבעי לזה (מיון פנימי בתוך ה-Repository). **מוגבל לשכבת ה-Repository בלבד** — ה-Service/API ממשיכים לעבוד עם `List`/`PageResponse` הרגילים, אין סיבה לפזר טיפוסי `SequencedCollection` בשכבות שלא נהנות מזה.
7. **`TransactionRunner.java`** (Interface) — ה"תפר" (Seam) שמייצג גבול טרנזקציה, בלי תלות ב-DB:
   ```java
   public interface TransactionRunner {
       /**
        * Runs {@code action} such that its effects across multiple
        * repositories become visible together, or (best-effort, in the
        * absence of a real transactional store) not at all if it throws.
        */
       <T> T runInTransaction(Supplier<T> action);
   }
   ```
8. **`InMemoryTransactionRunner.java`** — מימוש **מוצהר במפורש כ-Best-Effort, לא ACID אמיתי** (חובה לתעד את המגבלה הזו ב-Javadoc, לא להסתיר):
   - נעילה גלובלית יחידה (`synchronized`/`ReentrantLock`) שעוטפת את כל ה-`action`.
   - **Rollback ידני (Compensating action), לא Rollback אמיתי של Storage Engine:** מכיוון שאין WAL/Transaction Log אמיתי מאחורי `ConcurrentHashMap`, ה-`TaskDependencyService` (שלב 2) הוא זה שאחראי לבצע Compensation אם החלק השני של הפעולה נכשל (למשל: אם `save` על ה-Dependency הצליח אבל `record` על ההיסטוריה זרק Exception — הקוד קורא ל-`taskDependencyRepository.delete(...)` בתוך `catch` לפני שהוא מזרוק הלאה). זה **לא** שקוף ל-`TransactionRunner` עצמו — הוא רק דואג ל-Mutual Exclusion בזמן הריצה, לא ל-Undo.
   - **Follow-up מתועד:** כש-DB אמיתי ייבחר, `TransactionRunner` יוחלף במימוש שמבוסס `@Transactional` אמיתי (Spring + JDBC/JPA), וה-Compensation הידני ב-`TaskDependencyService` יוסר כי ה-DB יבצע Rollback אמיתי.

**Checklist שלב 1:**
- [ ] `DependencyChangeAction` (`ADDED`/`REMOVED`)
- [ ] `DependencyChangeSource` (`MIND`/`USER`/`SYSTEM`)
- [ ] `DependencyHistoryEntry` — Immutable, Builder (**לא** Record), ולידציית null-fields (לא כולל sourceRef-required-for-MIND/SYSTEM — זה ב-Service)
- [ ] `PageResponse<T>` גנרי, כולל `map(Function<T,R>)`
- [ ] `DependencyHistoryRepository` Interface עם Javadoc מלא לכל חוזה
- [ ] `InMemoryDependencyHistoryRepository` — Dual-Map (records + dedup-index), `synchronized`, מיון דטרמיניסטי יורד (חדש קודם) עם `SequencedCollection`/`.reversed()`
- [ ] `TransactionRunner` Interface
- [ ] `InMemoryTransactionRunner` — נעילה גלובלית, Javadoc מפורש שזה Best-Effort לא ACID
- [ ] טסטים יחידה: `DependencyHistoryEntry` ולידציה, `InMemoryDependencyHistoryRepository` (dedup עם/בלי sourceRef, מיון, page/size), `InMemoryTransactionRunner` (Exception propagation, נעילה תחת Concurrency עם `ConcurrentTestRunner.run(...)` הקיים)
- [ ] קומפילציה מלאה

---

## שלב 2 — לוגיקה עסקית ואטומיות: שילוב ב-`TaskDependencyService`

**מטרה:** כל `addDependency`/`removeDependency` מוצלח מייצר רשומת היסטוריה **בתוך אותה "עסקה"** (Best-Effort in-memory, כמתועד בשלב 1) עם השינוי בגרף התלויות.

**צעדי ביצוע:**

1. **שינוי Constructor** — הוספת `DependencyHistoryRepository`, `TransactionRunner`, ו-`Clock` (ל-testability של `createdAt` — ראה סעיף 4 להלן) לפרמטרים הקיימים (`TaskRepository`, `TaskDependencyRepository`).
2. **חתימת `addDependency`/`removeDependency`** — **החלטה שנדרש לתאם (ראה "החלטות חד-צדדיות" בסוף):** מוסיפים חתימה חדשה עם `source`/`sourceRef`:
   ```java
   public TaskDependency addDependency(UUID taskId, UUID dependsOnTaskId,
                                        DependencyChangeSource source, String sourceRef)
   public void removeDependency(UUID taskId, UUID dependsOnTaskId,
                                 DependencyChangeSource source, String sourceRef)
   ```
   ושומרים Overload ישן (2 ארגומנטים) לתאימות לאחור, שמאציל ל-Overload החדש עם `source = USER, sourceRef = null`:
   ```java
   public TaskDependency addDependency(UUID taskId, UUID dependsOnTaskId) {
       return addDependency(taskId, dependsOnTaskId, DependencyChangeSource.USER, null);
   }
   ```
3. **זרימת `addDependency` (4 ארגומנטים):**
   ```text
   1. Null-checks + self-dependency check (כמו היום, לא משתנה)
   2. אם source ∈ {MIND, SYSTEM} וגם (sourceRef == null || sourceRef.isBlank())
        → throw IllegalArgumentException("sourceRef is required for source " + source)
   3. Idempotency short-circuit (רק אם sourceRef != null — ראה שלב 3 לפירוט מלא):
        אם historyRepository.existsBySourceAndSourceRef(source, sourceRef)
        → זהו Retry של קריאה קודמת שכבר הצליחה. להחזיר את התוצאה הקודמת בלי
          לנסות שוב save (כדי לא לקבל DuplicateDependencyException שגוי על Retry לגיטימי).
   4. requireTaskExists(taskId), requireTaskExists(dependsOnTaskId)  (כמו היום)
   5. transactionRunner.runInTransaction(() -> {
          TaskDependency saved = taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId))
                  .orElseThrow(() -> new DuplicateDependencyException(...));
          try {
              historyRepository.record(DependencyHistoryEntry.builder()
                      .id(UUID.randomUUID())
                      .taskId(taskId).dependsOnTaskId(dependsOnTaskId)
                      .action(ADDED).source(source).sourceRef(sourceRef)
                      .createdAt(LocalDateTime.now(clock))
                      .build());
          } catch (RuntimeException historyWriteFailed) {
              // Compensating rollback — ראה שלב 1 סעיף 8
              taskDependencyRepository.delete(taskId, dependsOnTaskId);
              throw historyWriteFailed;
          }
          return saved;
      })
   ```
4. **`removeDependency`** — סימטרי: idempotency short-circuit לפני `delete`, ואז בתוך `runInTransaction`: `delete` → אם `true`, `record(REMOVED, ...)` עם אותו Compensation Pattern (אם ה-`record` נכשל, מבצעים `save` מחדש של הקשת שהוסרה כדי "לבטל" את ה-`delete`). **הערה לתיעוד:** ה-Compensation כאן לא באמת "משחזר" את מצב ה-`TaskDependency` המקורי אם היו עדכונים אחרים ל-Task בין לבין — אבל אין כרגע Race מהסוג הזה שרלוונטי (`TaskDependency` הוא Edge בלבד, בלי State פנימי לשחזר חלקית), אז זה Compensation תקין ומספיק.
5. **`Clock`** — הזרקת `java.time.Clock` (לא `LocalDateTime.now()` ישירות) ל-`TaskDependencyService`, עם `@Bean Clock clock() { return Clock.systemUTC(); }` חדש (יתווסף ל-`MockDataConfig` בשלב הבא) — מאפשר לטסטים לקבע זמן ולבדוק סדר דטרמיניסטי בלי תלות בזמן-אמת.
6. **`getHistory(UUID taskId, int page, int size)`** — מתודה חדשה ב-`TaskDependencyService` (לא Service נפרד — עקבי עם `getDependencies`/`getDependents` שכבר גרים כאן):
   ```java
   public PageResponse<DependencyHistoryEntry> getHistory(UUID taskId, int page, int size) {
       requireTaskExists(taskId);   // 404 case
       return historyRepository.findByTaskId(taskId, page, size);
   }
   ```
   היסטוריה ריקה (Task קיים, בלי שינויים) → `PageResponse` עם `content` ריק, `totalElements = 0` — **200, לא 404**. 404 שמור אך ורק ל-Task שלא קיים בכלל.

**אחריות לאטומיות (לתעד באותו ניסוח מדויק שהצוות כבר משתמש בו):**
- הכתיבה לגרף התלויות והכתיבה להיסטוריה **קורות יחד תחת אותה נעילה גלובלית** (`InMemoryTransactionRunner`) — אם החלק השני נכשל, מתבצע Compensation ידני לביטול החלק הראשון. זה **לא** Two-Phase Commit אמיתי ולא Isolation מלא (קריאות אחרות עשויות לראות מצב-ביניים קצר בין ה-`save`/`delete` הראשוני ל-Compensation אם הן לא עוברות דרך אותו `TransactionRunner`) — מגבלה מתועדת, לא מוסתרת.

**אסטרטגיית בדיקות:**
- עדכון `TaskDependencyServiceTest` הקיים: כל קריאה ל-`addDependency`/`removeDependency` (2 ארגומנטים) ממשיכה לעבוד ללא שינוי (Overload לתאימות).
- טסטים חדשים: `addDependency` מוצלח יוצר בדיוק רשומת היסטוריה אחת עם `action=ADDED`; `removeDependency` מוצלח → `action=REMOVED`; קריאה שנכשלת בולידציה (self-dep / task not found / duplicate) → **אין** רשומת היסטוריה נוצרת.
- **טסט Rollback (הכי חשוב בשלב הזה):** `DependencyHistoryRepository` מוזרק כ-Test Double שזורק Exception ב-`record()` → לוודא ש-`taskDependencyRepository.findByTaskId(taskId)` **לא** מכיל את הקשת אחרי הקריאה הכושלת (הוכחת ה-Compensation בפועל, לא רק "לא קרס").
- `MIND`/`SYSTEM` בלי `sourceRef` → `IllegalArgumentException`. `USER` בלי `sourceRef` → עובר כרגיל.
- **Gate:** קומפילציה מלאה + `TaskDependencyServiceTest` כולו ירוק.

**Checklist שלב 2:**
- [ ] Constructor מעודכן (`DependencyHistoryRepository`, `TransactionRunner`, `Clock`)
- [ ] Overload חדש (4 ארגומנטים) + Overload ישן (2 ארגומנטים) מאציל אליו
- [ ] `addDependency`: ולידציית sourceRef חובה ל-MIND/SYSTEM
- [ ] `addDependency`/`removeDependency`: כתיבה אטומית (dependency + history) עם Compensating Rollback
- [ ] `getHistory(taskId, page, size)` עם `requireTaskExists`
- [ ] טסט: היסטוריה נוצרת רק על שינוי מוצלח, לא על כישלון ולידציה
- [ ] טסט Rollback: כישלון ב-`record` → הקשת לא נשארת ב-Repository
- [ ] טסט: sourceRef חובה ל-MIND/SYSTEM, אופציונלי ל-USER
- [ ] קומפילציה מלאה

---

## שלב 3 — אסטרטגיית אידמפוטנטיות (מתועדת כנדרש בסעיף 6 של המפרט)

**מטרה:** לתעד ולממש במדויק את מנגנון מניעת הכפילות שנקבע.

**האסטרטגיה שנבחרה (מפורט):**
- **מפתח האידמפוטנטיות:** הזוג `(source, sourceRef)`. **לא** `(taskId, dependsOnTaskId, action)` — כי אותו זוג Task יכול לעבור ADDED→REMOVED→ADDED שוב באופן לגיטימי (3 רשומות שונות), וה-Dedup חייב להבחין בין "אותו ניסיון בדיוק, שוב" לבין "שינוי עסקי אמיתי חדש".
- **מי חייב לספק `sourceRef`:** `MIND`/`SYSTEM` — מקורות אוטומטיים/מונעי-אירועים שחשופים ל-Retry/At-least-once delivery. `USER` — אופציונלי (ראה מגבלה מתועדת למטה).
- **אכיפה:** Unique constraint לוגי על `(source, sourceRef)` כש-`sourceRef != null`, נבדק אטומית בתוך `synchronized` ב-`InMemoryDependencyHistoryRepository.record` (שלב 1, סעיף 6). **כש-DB אמיתי ייבחר**, זה יתורגם ל-Unique Index אמיתי בטבלה (`UNIQUE(source, source_ref) WHERE source_ref IS NOT NULL`) — מתועד כ-Follow-up, לא ממומש כאן.
- **סדר הבדיקה בזרימה (קריטי):** הבדיקה `existsBySourceAndSourceRef` מתבצעת **לפני** הניסיון ל-`save`/`delete` על גרף התלויות (שלב 2, סעיף 3-4) — לא אחרי. הסיבה: אם הבדיקה הייתה מגיעה אחרי הניסיון, Retry לגיטימי (שהעדכון המקורי שלו כבר הצליח) היה מקבל `DuplicateDependencyException`/`DependencyNotFoundException` שגוי, כי הקשת כבר קיימת/כבר לא קיימת מהניסיון הראשון — טעות-קטגוריה בין "Retry תקין" ל"בקשה כפולה אמיתית".
- **מגבלה מתועדת (לא Bug):** `USER` בלי `sourceRef` → אין הגנת Idempotency בכלל, כל קריאה מטופלת כבקשה עצמאית. Double-click/Retry-on-timeout מצד לקוח USER עלולים לגרום ל-`DuplicateDependencyException`/`DependencyNotFoundException` (לא לכפילות שקטה בנתונים — ה-Uniqueness של הגרף עצמו עדיין מוגן ע"י `TaskDependencyRepository.save`/`delete` האטומיים הקיימים; מה שלא מוגן הוא רק חוויית ה-Retry). מתועד כ-Known limitation ב-README (שלב 6), לא "נפתר חלקית" בשתיקה.
- **מקרה קצה: `record()` דו-פעמי עם אותו `(source, sourceRef)` אבל payload שונה** (למשל אותו `sourceRef` נשלח פעם ל-ADDED על A→B ופעם ל-REMOVED על C→D — קורא "עצל"/שגוי) — `record` מחזיר את ה-Entry ה**קיים** (הראשון), לא זורק Exception ולא כותב שנייה. זה תואם לסמנטיקה של "Retry = אותה בקשה שוב" — אם ה-Payload שונה זו לא באמת אותה בקשה, אבל **לא מזהים את זה כשגיאה** בשלב הזה (המפרט לא דרש Payload-equality check, רק Dedup לפי מזהה). מתועד כ-Assumption מפורשת, לא Follow-up סמוי.

**אסטרטגיית בדיקות (בנוסף לטסטים בשלבים 1-2):**
- Retry מדויק: `addDependency` עם `(MIND, "evt-1")` פעמיים ברצף → פעם ראשונה יוצרת קשת+היסטוריה, פעם שנייה מחזירה את אותה `TaskDependency` **בלי** לזרוק `DuplicateDependencyException` ובלי רשומת היסטוריה נוספת (`historyRepository` עדיין מכיל רשומה אחת בלבד).
- טסט Concurrency (עם `ConcurrentTestRunner.run(...)` הקיים): N חוטים, כולם `addDependency` עם אותו `(MIND, "evt-2")` בו-זמנית → כולם מחזירים את אותה תוצאה (הצלחה), רשומת היסטוריה **אחת** בלבד בסוף, קשת **אחת** בגרף.
- `USER` בלי `sourceRef`, שתי קריאות עוקבות לאותה קשת → השנייה מקבלת `DuplicateDependencyException` כרגיל (ללא הגנת Idempotency — מוכיח את המגבלה המתועדת, לא "בטעות עובד").

**Checklist שלב 3:**
- [ ] סדר הבדיקה (`exists` לפני `save`/`delete`) ממומש כמתואר
- [ ] טסט Retry לא-concurrent (MIND, אותו sourceRef פעמיים)
- [ ] טסט Retry concurrent (N חוטים, אותו sourceRef)
- [ ] טסט מגבלת USER (בלי sourceRef → אין הגנה, מוכח לא "בטעות מוגן")
- [ ] תיעוד ה-Assumption של payload שונה עם אותו sourceRef

---

## שלב 4 — שכבת ה-API

**מטרה:** `GET /tasks/{taskId}/dependencies/history` עם Pagination, 404 למשימה לא קיימת, טיפול בהיסטוריה ריקה.

**צעדי ביצוע:**

1. **הפרדה מפורשת בין ה-Domain Model ל-API Response** (חיזוק לדרישת המפרט: "אין לקשור בצורה הדוקה את מודל המעקב לתחבורה/פרוטוקול ספציפי כגון Kafka או REST"):
   - `DependencyHistoryEntry` (שלב 1) הוא ה-Domain Model — חי ב-`dto`, אין לו ולא יהיה לו שום תלות ב-`spring-web`/Jackson-annotations/Kafka. הוא זהה לחלוטין בין "הגיע מ-REST" ל"יגיע מ-Kafka בעתיד" — אין הבדל בייצוג.
   - **`DependencyHistoryResponse.java`** — DTO **חדש, נפרד**, חי ב-`controller` (שכבת ה-API, לא ה-Domain): אותם שדות בדיוק כמו `DependencyHistoryEntry` (`id`, `taskId`, `dependsOnTaskId`, `action`, `source`, `sourceRef`, `createdAt`), אבל מחלקה עצמאית — כדי שאם מחר יתווספו Jackson annotations ייעודיים ל-JSON shape (למשל `@JsonProperty` ל-snake_case, גרסאות API, שדות נגזרים ל-UI) הם ייגעו רק בשכבת ה-API ולא ב-Domain Model שמשמש גם את ה-Service/Repository. מיפוי סטטי פשוט: `DependencyHistoryResponse.from(DependencyHistoryEntry entry)`.
   - **מבחן ה-Decoupling בפועל:** לוודא ש-`import org.springframework.*`/`import com.fasterxml.jackson.*` **לא** מופיע בשום מקום תחת `dto/DependencyHistoryEntry.java`, `dto/DependencyChangeAction.java`, `dto/DependencyChangeSource.java`, `repository/DependencyHistoryRepository.java` — Gate מפורש בסוף שלב 4, לא רק כוונה.
2. **`DependencyHistoryController.java`** — קונטרולר עצמאי חדש (**לא** בתוך `ChainController` — זה קיים רק על branch WIP נפרד שעוד לא מוזג; יצירת קונטרולר חדש כאן נמנעת מ-Merge Conflict מיותר; מתועד כ-Follow-up לאיחוד כשה-branches ימוזגו):
   ```java
   @RestController
   @RequestMapping("/tasks")
   public class DependencyHistoryController {

       private final TaskDependencyService taskDependencyService;

       @GetMapping("/{taskId}/dependencies/history")
       public ResponseEntity<PageResponse<DependencyHistoryResponse>> getHistory(
               @PathVariable UUID taskId,
               @RequestParam(defaultValue = "0") int page,
               @RequestParam(defaultValue = "20") int size) {

           if (page < 0 || size <= 0 || size > 100) {
               throw new IllegalArgumentException("page must be >= 0 and size must be in 1..100");
           }
           PageResponse<DependencyHistoryEntry> entries =
                   taskDependencyService.getHistory(taskId, page, size);
           return ResponseEntity.ok(entries.map(DependencyHistoryResponse::from));
       }
   }
   ```
   (`PageResponse<T>.map(Function<T,R>)` — מתודת עזר קטנה שמתווספת ל-`PageResponse` בשלב 1 בשביל בדיוק המיפוי הזה; שומרת `page`/`size`/`totalElements`/`totalPages`, ממפה רק את `content`.)
   (עיצוב עקבי עם `ChainController` ה-WIP: `@RestController`/`@RequestMapping("/tasks")`/`@PathVariable UUID`/`ResponseEntity`. **לא** משתמשים ב-`@Valid`/Bean Validation כאן — הולידציה על `page`/`size` פשוטה מספיק לבדיקה ידנית; אם בכל זאת נדרש `@Valid` במקום אחר בעתיד, זה `jakarta.validation.Valid` — ראה סעיף 4 ב-Context.)
3. **`GlobalExceptionHandler.java`** (`@RestControllerAdvice`) — **הראשון בכל הריפו** (מתועד כתקדים חדש, לא רק לתכונה הזו):
   ```java
   @ExceptionHandler(TaskNotFoundException.class)      → 404 NOT_FOUND
   @ExceptionHandler(DependencyNotFoundException.class) → 404 NOT_FOUND
   @ExceptionHandler(DuplicateDependencyException.class) → 409 CONFLICT
   @ExceptionHandler(InvalidDependencyException.class)  → 400 BAD_REQUEST
   @ExceptionHandler(IllegalArgumentException.class)    → 400 BAD_REQUEST
   ```
   כל תשובת שגיאה: גוף JSON מינימלי עקבי (`{"error": "<class simple name>", "message": "..."}"`) — **מוסכמה חדשה, לא קיימת בפרויקט כיום**, מתועדת ככזו.
4. **Pagination — פרטי מוסכמה (חדשה, ראשונה בריפו, לתעד ב-README):**
   - `page` (0-based, ברירת מחדל 0), `size` (ברירת מחדל 20, מקסימום 100 — נגד DoS/Query כבד).
   - תשובה: `PageResponse<DependencyHistoryEntry>` תמיד (גם ריק) — `200`, לא `404`, כשההיסטוריה ריקה אך ה-Task קיים.
   - `404` רק כש-`taskId` לא קיים בכלל (`TaskNotFoundException` מ-`requireTaskExists`).
   - סדר: כרונולוגי **יורד** (חדש קודם — `createdAt` ואז `id`, שניהם יורדים). **אושר איתך (Review comment):** היסטוריה נצפית לרוב מהחדש לישן (כמו `git log`/Audit Log). ממומש ב-`InMemoryDependencyHistoryRepository.findByTaskId` (ראה שלב 1, סעיף 6).
5. **JSON field casing (על `DependencyHistoryResponse`, לא על ה-Domain Model):** נשאר camelCase (ברירת המחדל של Jackson, כמו שאר ה-DTOs בפרויקט) — השמות ה-snake_case במפרט (`task_id` וכו') הם ייצוג DB/מפרט, לא הכתבה ל-JSON casing. אם נדרש snake_case ב-JSON בפועל, זה החלטת `ObjectMapper` גלובלית שמשפיעה על כל שאר ה-API, לא רק פה — לתאם בנפרד אם רלוונטי. **בזכות ההפרדה מ-`DependencyHistoryEntry` (סעיף 1 לעיל), שינוי כזה עתידי יישאר מבודד לגמרי ל-`DependencyHistoryResponse` ולא ייגע ב-Domain Model/ב-Repository.**

**אסטרטגיית בדיקות (`@SpringBootTest` / `MockMvc`, לפי המוסכמות שיבחרו — אין תקדים קיים לבחור ממנו, אז MockMvc סטנדרטי):**
- `GET .../history` על Task קיים בלי שינויים → `200`, `content=[]`, `totalElements=0`.
- `GET .../history` על Task לא קיים → `404`.
- `GET .../history` אחרי כמה `addDependency`/`removeDependency` → תוכן נכון, בסדר הנכון.
- Pagination: `page=0&size=1` מול היסטוריה עם 3 רשומות → `content` באורך 1, `totalElements=3`, `totalPages=3`.
- `size=0`/`size=101`/`page=-1` → `400`.

**Checklist שלב 4:**
- [ ] `DependencyHistoryResponse` — DTO נפרד ב-`controller`, `from(DependencyHistoryEntry)` סטטי
- [ ] `DependencyHistoryController` עם `GET /tasks/{taskId}/dependencies/history`, מחזיר `PageResponse<DependencyHistoryResponse>` (לא `DependencyHistoryEntry` ישירות)
- [ ] `GlobalExceptionHandler` (`@RestControllerAdvice`) — 404/409/400 כמתואר
- [ ] Validation על `page`/`size`
- [ ] **Gate מפורש:** אין `import org.springframework.*`/`import com.fasterxml.jackson.*` תחת `dto/DependencyHistoryEntry.java`, `dto/DependencyChangeAction.java`, `dto/DependencyChangeSource.java`, `repository/DependencyHistoryRepository.java`
- [ ] טסט: היסטוריה ריקה → 200 (לא 404)
- [ ] טסט: Task לא קיים → 404
- [ ] טסט: Pagination תקין (page/size/totalElements/totalPages)
- [ ] טסט: פרמטרים לא חוקיים → 400
- [ ] קומפילציה מלאה + טסטים ירוקים

---

## שלב 5 — `MockDataConfig` וחיווט Beans

**צעדי ביצוע:**
1. `@Bean @ConditionalOnMissingBean(DependencyHistoryRepository.class)` → `new InMemoryDependencyHistoryRepository()`.
2. `@Bean @ConditionalOnMissingBean(TransactionRunner.class)` → `new InMemoryTransactionRunner()`.
3. `@Bean @ConditionalOnMissingBean(Clock.class)` → `Clock.systemUTC()`.
   (עקבי עם הדפוס הקיים ב-`MockDataConfig` — בלי `@Profile`, כי אין Convention כזה בפרויקט.)

**Checklist שלב 5:**
- [ ] 3 ה-Beans החדשים, כולם `@ConditionalOnMissingBean`
- [ ] טסט Context: כשיש Bean מותאם-אישית מכל סוג, `MockDataConfig` לא יוצר שני (אותו דפוס כמו טסטי ה-Context הקיימים ל-`TaskRepository`/`TaskDependencyRepository`)

---

## שלב 6 — תיעוד

**צעדי ביצוע:**
1. **`README.md`** — סעיף חדש "Dependency change history (Issue #153)" בסגנון הסעיף הקיים "Dependency graph persistence (Issue #66)":
   - תיאור המודל (`DependencyHistoryEntry`, שדותיו).
   - `GET /tasks/{taskId}/dependencies/history` — פרמטרים, תשובה, קודי שגיאה.
   - **מוסכמת ה-Pagination החדשה** — לתעד כמוסכמה שנקבעה כאן לראשונה בריפו (לא רק לתכונה הזו — כדי שצוותים אחרים ידעו שהיא קיימת).
   - **אסטרטגיית ה-Idempotency** — הכלל המדויק (`source_ref` חובה ל-MIND/SYSTEM, אופציונלי ל-USER) + המגבלה המתועדת ל-USER.
   - **מגבלת ה-Persistence Port** — In-Memory זמני, `TransactionRunner` הוא Best-Effort לא ACID, עד שייבחר DB.
2. **תיקון אגבי (לא קשור לפיצ'ר, ניצול ההזדמנות כשבכל מקרה עורכים את README):** שורה 35 עדיין אומרת "Requires a local Maven installation (`mvn`) and JDK 11" — נשכח בעדכון ל-Java 21. לתקן ל-"JDK 21".
3. **Javadoc** — כבר משולב בכל שלב לעיל (Interfaces + מימושים) — לוודא בסוף שאין מתודה ציבורית חדשה בלי Javadoc, עקבי עם הרמה הקיימת בקוד.

**Checklist שלב 6:**
- [ ] סעיף README חדש להיסטוריית תלויות (Issue #153)
- [ ] תיעוד מוסכמת Pagination (חדשה, גלובלית לריפו)
- [ ] תיעוד אסטרטגיית Idempotency + מגבלת USER
- [ ] תיעוד מגבלת ה-Persistence Port הזמני
- [ ] תיקון "JDK 11" ל-"JDK 21" בשורה 35 (תיקון אגבי, לא קשור לפיצ'ר)
- [ ] סקירת Javadoc על כל API ציבורי חדש

---

## שלב 7 — הרצה מלאה, סקירה סופית וסיכום

**צעדי ביצוע:**
1. `mvn test` מלא על מודול `chain`.
2. חיפוש גלובלי אחר קריאות ישנות ל-`addDependency`/`removeDependency` (2 ארגומנטים) — לוודא שה-Overload עדיין תואם, ושאין קורא חדש שהיה צריך להעביר `source`/`sourceRef` מפורש אך לא הועבר בטעות.
3. הרצת טסטי ה-Concurrency (שלבים 1+3) 3-5 פעמים ברצף לאיתור Flakiness.
4. מיפוי מלא מול 7 סעיפי המפרט המקורי:

   | # | דרישה | שלב |
   |---|------|-----|
   | 1 | מודל היסטוריה (id, task_id, depends_on_task_id, action, source, source_ref, created_at) | שלב 1 |
   | 2 | תיעוד שינויים משולב עם add/remove קיימים | שלב 2 |
   | 3 | אטומיות בתוך Transaction יחיד | שלבים 1-2 |
   | 4 | מקורות MIND/USER/SYSTEM + source_ref, מנותק מפרוטוקול | שלבים 1-3 |
   | 5 | API + Pagination + 404 + היסטוריה ריקה | שלב 4 |
   | 6 | אידמפוטנטיות מתועדת | שלב 3 |
   | 7 | בדיקות + תיעוד | שלבים 1-6 (מפוזר) + שלב 6 |

5. וידוא ש**כל** הנחה חד-צדדית (רשימה בסוף המסמך) עדיין תקפה או שקיבלה מענה ממך.

**Checklist שלב 7:**
- [ ] `mvn test` ירוק במלואו
- [ ] חיפוש גלובלי — Overload ישן לא שבור
- [ ] טסטי Concurrency יציבים על פני 3-5 הרצות
- [ ] מיפוי מלא של 7 דרישות המפרט מול הקוד
- [ ] סקירת כל ההנחות החד-צדדיות מול תשובותייך בפועל

---

## החלטות שנעשו באופן חד-צדדי (Reasonable defaults) — לאשר/לתקן

רשימה מרוכזת של החלטות שביצעתי לבד כי לא היו חוסמות אך משפיעות על העיצוב, כדי שתוכלי לתקן לפני/תוך כדי ביצוע:

1. **Overload לתאימות לאחור** — `addDependency`/`removeDependency` הישנים (2 ארגומנטים) נשארים, מאצילים ל-4 הארגומנטים עם `USER`/`null`. חלופה: לשבור את החתימה לגמרי ולחייב את כל הקוראים (כולל הקונטרולר ה-WIP) לעדכן.
2. **`DependencyHistoryController` עצמאי**, לא בתוך `ChainController` ה-WIP (על branch אחר) — כדי לא להיתקע ב-Merge Conflict. ידרוש איחוד ידני כש-branches ימוזגו.
3. ~~סדר ברירת מחדל כרונולוגי עולה~~ **נפתר (Review comment, אושר):** סדר יורד (חדש קודם) — ראה שלבים 1+4.
4. ~~מספר Issue לא ידוע~~ **נפתר:** Issue #153.
5. **`Clock` כ-Bean חדש** — תוספת קטנה שלא התבקשה במפורש במפרט, אבל נדרשת ל-testability אמיתי של סדר כרונולוגי דטרמיניסטי (לא ניתן לבדוק "סדר נכון" באמינות מול `LocalDateTime.now()` לא-מבוקר).
6. **JSON casing נשאר camelCase**, לא snake_case כמו במפרט (שהוא ככל הנראה ייצוג DB/מפרט כללי, לא הכתבת API).
7. **`GlobalExceptionHandler` גלובלי לכל הקונטרולרים** (לא רק ל-history endpoint) — כי זה יהיה ה-`@RestControllerAdvice` הראשון בריפו, וטבעי שהוא יתפוס את כל חריגות ה-Domain הקיימות, לא רק את אלו שרלוונטיות להיסטוריה. אם יש כבר תוכנית נפרדת לזה (למשל אצל חברת הצוות עם הקונטרולר), לתאם.
8. **`DependencyHistoryResponse` כ-DTO נפרד מ-`DependencyHistoryEntry`** — לא התבקש במפורש כ"מחלקה נוספת" במפרט, אבל נוסף כמימוש ישיר לדרישת ה-Decoupling (הערתך) — עלות: קצת Boilerplate (מיפוי סטטי) ומחלקה נוספת. אם מעדיפים לוותר על השכבה הזו ולחזור לסריאליזציה ישירה של ה-Domain Model (כמו ש-`TaskDependency` כבר נעשה בפרויקט), לדווח.
9. ~~מיזוג `dependency-repository-66` ל-`ruthHoltzman`~~ **בוצע אחרת בפועל:** במקום מיזוג דרך `ruthHoltzman`, זוהו 25 commits ספציפיים ל-`chain` (path-filtered, `--no-merges`) ובוצע להם Cherry-pick ישיר על `chain/ruthHoltzman/dependency-change-history` — נמנע מהכנסת ~340 קבצים לא-קשורים מצוותים אחרים שהיו נכנסים במיזוג מלא (כי ל-`dependency-repository-66` יש `Merge branch 'main'` בהיסטוריה שלו). תוך כדי כך התגלה שדרוג פלטפורמה משמעותי (Java 21/Boot 3.3.13, ראה סעיף 4 ב-Context) שלא היה ידוע כשהתוכנית המקורית נכתבה. `mvn test` ירוק על המצב הסופי.
