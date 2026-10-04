# Architecture Review Skill (Team Inbox)

A Claude Code Skill that checks Team Inbox code — a diff, a PR, a branch, or a pasted snippet —
against the rules in [`teams/inbox/CLAUDE.md`](../../../CLAUDE.md), and reports findings the way
a code reviewer would: severity, location, problem, violated rule, and a concrete fix.

It's scoped to `teams/inbox/.claude/skills/`, so it only activates for Claude Code sessions
working inside this module — it does not affect other teams.

## When to use it

Anytime you want a structural/architecture check on Team Inbox code — **it does not need to be
pushed, committed, or opened as a PR first.** It reviews whatever code exists right now: a file
you're still editing, uncommitted local changes, a branch, or a PR — a PR is just one of several
options, not a requirement. Most useful right before opening a PR (review your own work first),
or when reviewing a teammate's PR.

It checks for the things a quick read can miss under time pressure: a controller quietly calling
a repository, an entity leaking out through an API response, a hard-coded value or secret, a
service with no test. It does **not** replace human review or catch business-logic bugs — see
`SKILL.md` §6.

## How to run it

Open Claude Code inside (or with access to) `teams/inbox`, then just ask in plain language —
the skill is picked up automatically based on the request, no special syntax needed:

- **Review specific files (never committed, doesn't matter):** `"Architecture-review teams/inbox/src/main/java/.../TaskController.java"` or just `"review this file"` with it open
- **Review the current uncommitted changes:** `"Do an architecture review of my current diff"`
- **Review a branch, pushed or not:** `"Architecture-review my branch sari/task-labels against main"`
- **Review a PR (once one exists):** `"Run an architecture review on PR #23"`

Claude will read `CLAUDE.md`, pull the relevant diff (via `gh pr diff` / `git diff` as needed),
and return a report.

## Reading the output

```
### [Critical] Controller depends on Repository directly
- **Location:** src/main/java/com/rnd/inbox/controller/TaskController.java:22
- **Problem:** ...
- **Rule violated:** CLAUDE.md §5 — ...
- **Recommendation:** ...
```

- **Critical** — architecture-breaking or a security issue (e.g. a secret in code, a Controller
  calling a Repository directly). Don't merge until these are fixed.
- **Major** — a real, documented rule violation (wrong-layer DTO/entity use, hard-coded config,
  non-standard exception handling, Inbox-only code under `shared/`, an untested Service). Fix
  before/soon after merge.
- **Minor** — naming/structure inconsistencies that don't threaten correctness.

If the change is clean, the skill says so explicitly rather than inventing nitpicks:
`No architecture violations found. This change is consistent with teams/inbox/CLAUDE.md.`

## Verifying it actually works

`TEST-SCENARIOS.md` walks through 9 intentional example cases under `examples/good` and
`examples/bad` — one fully compliant vertical slice, and one isolated violation per rule
category the task description called out (controller→repository, business logic in the
controller, entity returned from the API, hard-coded config/secrets, swallowed exceptions,
Inbox-only code under `shared/`, misleading naming, and missing tests) — with the exact output
the skill produces for each, so the rules can be checked, not just trusted.

## Keeping this in sync

If `teams/inbox/CLAUDE.md` changes (new rule, changed severity, new package), update the
relevant rule category in `SKILL.md` §3 in the same PR — an out-of-date skill is worse than no
skill, since it gives false confidence.
