## Scenario: missing tests (not a code file — an absence)

The diff being reviewed adds `com.rnd.inbox.service.TaskReminderService`, a new `@Service` with
real branching logic (decides whether a reminder is due based on task due-date and status), and
adds no corresponding `TaskReminderServiceTest` anywhere under `src/test`.

**Expected finding:** Major — missing tests where required (CLAUDE.md §11: every `Service` class
with real logic gets a unit test covering its behavior).
