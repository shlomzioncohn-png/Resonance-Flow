In addition to the keys above, each task has the keys below. They say what
kind of task it is, how long it takes and when it is due, and each of them
may be null.

- `tier` — string or null. Exactly one of `"OPERATIONAL"`, `"TACTICAL"`,
  `"STRATEGIC"`. Tier is about the **time horizon of the work and how far its
  consequences reach**, not about who does it: a developer can hold a
  strategic task and a chief executive an operational one.
  - `OPERATIONAL` — the horizon is **hours to a day**. One piece of known
    work, whose effect ends when it is done: fixing the crash, sending the
    report, answering the customer, clearing the alert.
  - `TACTICAL` — the horizon is **days to a week**. Work that organises or
    unblocks other work, and whose effect is felt across that week: planning
    the sprint, chasing a blocked dependency, preparing the review, choosing
    between options inside an agreed goal.
  - `STRATEGIC` — the horizon is **weeks or longer**. A direction or a
    commitment whose effect outlives the task itself: what to build, who to
    hire, what to promise a customer, which market to enter, what to stop
    doing.
  - When the content gives no sense of timescale or reach, use null. Guessing
    a tier is worse than leaving it empty, because a wrong one changes how the
    task is ranked.

- `urgency` — integer from 1 to 5, or null. 1 means nothing is lost by leaving
  it for weeks. 5 means damage accumulates today. Judge it from what the
  content says about timing and consequence, not from how emphatic the writing
  is: an exclamation mark is not evidence, and neither is the word "urgent" on
  its own. Urgency is separate from tier — a strategic task can be urgent and
  an operational one can be idle.

- `role_context` — string or null. Exactly one of `"DEVELOPER"`, `"MANAGER"`,
  `"HIGHER_MANAGER"`, `"FIELD_ENGINEER"`, `"SALESPERSON"`, `"CEO"`. Whose work
  this is, read from the content itself — not from who wrote the message, not
  from who it was addressed to, and not from who might end up doing it. A task
  can carry a role before anyone is assigned to it.
  - `DEVELOPER` — building and maintaining the product itself: code, tests,
    reviews, deployments, technical design, production defects.
  - `MANAGER` — running one team: sprint planning, assigning and unblocking
    people, one-to-ones, hiring for the team, the team's own commitments.
  - `HIGHER_MANAGER` — running several teams or a department: budgets,
    headcount across teams, cross-team programmes, reporting upwards.
  - `FIELD_ENGINEER` — the running system at a customer or a site: incidents,
    deployments, monitoring, maintenance visits, hardware and connectivity.
  - `SALESPERSON` — the pipeline: prospects, demos, pricing and contract
    terms, renewals, relationships with buyers.
  - `CEO` — the company as a whole: investors and the board, the market the
    company is in, executive hires, public commitments.
  - When the content describes work that several of these could own, or names
    no kind of work at all, use null.

- `effort_estimate_minutes` — integer greater than 0, or null. How long the
  work itself takes, in minutes. Count working time, not calendar time: an
  hour is 60, a day is 480, a week is 2400 (five working days).

- `effort_estimate_basis` — string or null. Exactly one of `"STATED"`,
  `"INFERRED"`. What the estimate rests on.
  - `STATED` — the content fixes how long the work takes, in units whose
    length is fixed: "should take about an hour", "a twenty-minute job", "two
    days of work".
  - `INFERRED` — the content fixes no duration, and the minutes are your
    reading. Either the work is small and specific enough that its length
    follows from what it is (fixing a typo, forwarding a file, approving a
    document that is already reviewed), or the content names a unit whose
    length you would have to assume ("two sprints", "a few days").
  - When neither applies, because the work is open-ended or its size is not
    described ("look into the billing issue", "plan the migration"), both keys
    are null.

- `deadline` — object or null. When the work must be done by, written down the
  way the content says it. Do no date arithmetic: never work out a date from a
  weekday, and never turn one unit into another. Every object has `phrase`,
  the content's own words for the deadline copied exactly ("by Friday",
  "within 72 hours"), and `kind`, exactly one of the four below with the keys
  it lists. Leave out the keys a kind does not list. Any kind may also have
  `time_of_day` ("14:00", 24-hour) when the content names a time; midnight is
  "23:59".
  - `"DATE"` — a calendar date: `month` (1 to 12) and `day` (1 to 31), plus
    `year` only when the content writes one. "By November 3", "on the 20th of
    October at 10:00".
  - `"OFFSET"` — an amount of time from now: `amount` (integer above 0) and
    `unit`, exactly one of `"MINUTES"`, `"HOURS"`, `"DAYS"`, `"WEEKS"`,
    `"WORKING_DAYS"`. Write the amount and unit the content uses: "two weeks"
    is 2 `"WEEKS"`, not 14 `"DAYS"`. Use `"WORKING_DAYS"` only when the content
    says working or business days. "Tomorrow" is 1 `"DAYS"`, and "by 09:00
    tomorrow" is 1 `"DAYS"` with `time_of_day` "09:00".
  - `"WEEKDAY"` — a day of the week: `weekday`, exactly one of `"MONDAY"`,
    `"TUESDAY"`, `"WEDNESDAY"`, `"THURSDAY"`, `"FRIDAY"`, `"SATURDAY"`,
    `"SUNDAY"`; `next_week` true only for "next Friday".
  - `"PERIOD_END"` — the end of a period: `period`, exactly one of `"DAY"`
    ("today", "end of day"), `"WEEK"`, `"NEXT_WEEK"` ("next week"),
    `"MONTH"`. A time with no day ("by 12:30", "before the 10:00 call") is
    today: `"DAY"` with that `time_of_day`.

Examples. Each answer is the whole object, written exactly as you return it:

    "The checkout service is throwing null pointers since the deploy, users
    cannot pay."
    {"tasks": [{"title": "Fix the checkout service null pointers", "description": "Users cannot pay since the deploy.", "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 5, "role_context": "DEVELOPER", "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": null}]}

    "Before Thursday I need the recommendation on whether we enter the German
    market, the board is deciding."
    {"tasks": [{"title": "Write the recommendation on entering the German market", "description": "The board decides on Thursday and is waiting for it.", "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "STRATEGIC", "urgency": 4, "role_context": "CEO", "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": {"phrase": "Before Thursday", "kind": "WEEKDAY", "weekday": "THURSDAY"}}]}

    "Can you line up next sprint and work out who picks up the migration?"
    {"tasks": [{"title": "Line up next sprint", "description": "Includes working out who picks up the migration.", "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "TACTICAL", "urgency": 2, "role_context": "MANAGER", "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": null}]}

    "Please fix the typo on the login page."
    {"tasks": [{"title": "Fix the typo on the login page", "description": null, "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 1, "role_context": null, "effort_estimate_minutes": 10, "effort_estimate_basis": "INFERRED", "deadline": null}]}

    "Could you review my pull request by 15:00 today? It should take about
    half an hour."
    {"tasks": [{"title": "Review the pull request", "description": null, "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 3, "role_context": "DEVELOPER", "effort_estimate_minutes": 30, "effort_estimate_basis": "STATED", "deadline": {"phrase": "by 15:00 today", "kind": "PERIOD_END", "period": "DAY", "time_of_day": "15:00"}}]}

    "The badge readers at the warehouse gate stopped working about two hours
    ago, can someone go check them?"
    {"tasks": [{"title": "Check the badge readers at the warehouse gate", "description": "They stopped working about two hours ago.", "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 4, "role_context": "FIELD_ENGINEER", "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": null}]}

    "Project Manager: Can you send the client the revised mock-ups by tomorrow?
    I will set up the review call with their team once they have them."
    {"tasks": [{"title": "Send the client the revised mock-ups", "description": "Due tomorrow.", "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 3, "role_context": null, "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": {"phrase": "by tomorrow", "kind": "OFFSET", "amount": 1, "unit": "DAYS"}}, {"title": "Set up the review call with the client's team", "description": "Once the client has the revised mock-ups.", "source_ref": null, "has_dependencies": true, "dependency_mentions": ["Send the client the revised mock-ups"], "tier": "OPERATIONAL", "urgency": 2, "role_context": null, "effort_estimate_minutes": 15, "effort_estimate_basis": "INFERRED", "deadline": null}]}

    "Please send the Q3 numbers to finance by November 3. They present them at
    the board meeting on the 12th."
    {"tasks": [{"title": "Send the Q3 numbers to finance", "description": "Finance presents them at the board meeting on the 12th.", "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 3, "role_context": null, "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": {"phrase": "by November 3", "kind": "DATE", "month": 11, "day": 3}}]}

    "No rush on this, but could you tidy up the old feature flags at some point?"
    {"tasks": [{"title": "Tidy up the old feature flags", "description": null, "source_ref": null, "has_dependencies": false, "dependency_mentions": [], "tier": "OPERATIONAL", "urgency": 1, "role_context": "DEVELOPER", "effort_estimate_minutes": null, "effort_estimate_basis": null, "deadline": null}]}

    "Thanks everyone, good discussion on the colour options. See you next week."
    {"tasks": []}

    The typo shows the normal case for an empty field: a one-line request
    with no sign of whose work it is. Null there is the right answer, not a
    failure to answer. It is also the inferred estimate, and the pull request
    the stated one. The German market and the badge readers each carry one of
    the two traps in the rules below. The meeting turn holds two tasks, one
    asked for and one offered, so the list has two entries; the call waits
    for the mock-ups, so it names them by their title, and the mock-ups,
    which wait for nothing, name nothing. The German market, the pull request
    and the mock-ups each show a deadline as the content wrote it: a weekday,
    a time today, one day from now. The Q3 numbers are due on a date; the
    board meeting on the 12th is another event's date, not theirs. The feature
    flags say there is no rush, so their deadline is null. The last one
    discusses but asks nobody for anything, so the list is empty.

Rules for `tier`, `urgency` and `role_context`:

- Use `null` whenever the content does not make the value clear. An empty
  field is the correct answer, and it is always better than a guess: another
  service acts on these values, so a wrong one is worse than a missing one.
- Use the values exactly as written above, in capitals, as a single string.
  Do not invent a value, do not abbreviate one, do not substitute a word that
  seems close, and never answer with a list of two.
- They are judgements about the task, not about the writing. Decide
  them from what the work actually is.

Rules for the effort estimate:

- A deadline is not a duration. "By Thursday" says when the work is due, not
  how long it takes.
- Time that has already passed is not a duration either. "Down for 40
  minutes" or "waiting since Monday" describes the situation, not the work.
- Give both keys or neither: an estimate always comes with its basis.
- Unlike `tier`, `urgency` and `role_context`, an inferred estimate is welcome
  here, because the basis tells the reader how far to trust it. It is the one
  exception to basing every field on the content: how long a kind of work
  usually takes is your own knowledge, and here you may use it. The work
  itself must still come from the content, and every other field still rests
  on the content alone.

Rules for the deadline:

- A duration is not a deadline. "It takes about two hours" says how long, not
  by when.
- Time that has already passed is not a deadline. "Down for 40 minutes" or
  "waiting since Monday" describes the situation.
- The date of something else is not this task's deadline. A meeting, a launch
  or an offsite the content mentions is the deadline only when the content
  says this work must be done by it: "before the board meeting" is one, "the
  board meeting is on the 12th" on its own is not.
- A softly worded date is still a deadline: "ideally by Friday" and "would be
  great to have by Friday" are Friday. Only an explicit "no rush",
  "whenever", "no deadline" or "at some point" is null.
- When the content gives this task more than one date, use the one by which
  the whole task must be done.
- When the content says nothing about when the work is due, use null.
