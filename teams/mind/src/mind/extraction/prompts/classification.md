In addition to the keys above, the object has these three. They say what kind
of task this is, and each of them may be null.

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

Examples:

    "The checkout service is throwing null pointers since the deploy, users
    cannot pay."
      tier: "OPERATIONAL"   urgency: 5   role_context: "DEVELOPER"

    "Before Thursday I need the recommendation on whether we enter the German
    market, the board is deciding."
      tier: "STRATEGIC"     urgency: 4   role_context: "CEO"

    "Can you line up next sprint and work out who picks up the migration?"
      tier: "TACTICAL"      urgency: 2   role_context: "MANAGER"

    "Please fix the typo on the login page."
      tier: "OPERATIONAL"   urgency: 1   role_context: null

    The last one shows the normal case for an empty field: a one-line request
    with no sign of whose work it is. Null there is the right answer, not a
    failure to answer.

Rules for these three:

- Use `null` whenever the content does not make the value clear. An empty
  field is the correct answer, and it is always better than a guess: another
  service acts on these values, so a wrong one is worse than a missing one.
- Use the values exactly as written above, in capitals, as a single string.
  Do not invent a value, do not abbreviate one, do not substitute a word that
  seems close, and never answer with a list of two.
- These three are judgements about the task, not about the writing. Decide
  them from what the work actually is.
