In addition to the keys above, the object has these three. They say what kind
of task this is, and each of them may be null.

- `tier` — string or null. Exactly one of `"OPERATIONAL"`, `"TACTICAL"`,
  `"STRATEGIC"`.
  - `OPERATIONAL` — carrying out known work: a fix, a reply, a report that
    already has a shape, a routine step in an existing process.
  - `TACTICAL` — organising or deciding how known work gets done: planning,
    coordinating, scheduling, unblocking other people, choosing between
    options inside an agreed goal.
  - `STRATEGIC` — setting direction or making a commitment whose effect
    outlives the task: what to build, who to hire, what to promise, what to
    stop doing.
- `urgency` — integer from 1 to 5, or null. 1 means nothing is lost by
  leaving it for weeks. 5 means damage accumulates today. Judge it from what
  the content says about timing and consequence, not from how emphatic the
  writing is: an exclamation mark is not evidence.
- `role_context` — string or null. Exactly one of `"DEVELOPER"`, `"MANAGER"`,
  `"HIGHER_MANAGER"`, `"FIELD_ENGINEER"`, `"SALESPERSON"`, `"CEO"`. Whose
  work this is, read from the content itself — not from who wrote the
  message, not from who it was addressed to, and not from who might end up
  doing it. A task can carry a role before anyone is assigned to it.

Rules for these three:

- Use `null` whenever the content does not make the value clear. An empty
  field is the correct answer, and it is always better than a guess: another
  service acts on these values, so a wrong one is worse than a missing one.
- Use the values exactly as written above, in capitals. Do not invent a
  value, do not abbreviate one, and do not substitute a word that seems
  close.
- These three are judgements about the task, not about the writing. Decide
  them from what the work actually is.
