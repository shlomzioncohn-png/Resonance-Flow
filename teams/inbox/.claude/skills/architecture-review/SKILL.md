---
name: architecture-review
description: Reviews Team Inbox Java/Spring Boot code, a diff, or a PR against the rules in teams/inbox/CLAUDE.md. Use when asked to do an "architecture review" of Inbox code, check a PR/diff/branch for layering or architecture violations, or verify a change is safe to merge into teams/inbox.
---

# Team Inbox Architecture Review

You are reviewing code that lives (or will live) under `teams/inbox/` against the rules in
`teams/inbox/CLAUDE.md`. That file is the source of truth — if anything below seems to
contradict it, `CLAUDE.md` wins (it may have been updated since this skill was written).

This is a **review**, not a rewrite: report findings, don't silently fix the code, unless the
user explicitly asks you to apply fixes after reviewing.

## 1. Load the rules

Before reviewing anything, read `teams/inbox/CLAUDE.md` (path relative to the repo root) so the
review is grounded in the actual current rules, not a memorized paraphrase of them.

## 2. Determine what you're reviewing

This review does **not** require a PR, a push, or any GitHub involvement — it reviews whatever
code exists right now, wherever it lives. A PR/branch is just one possible source among several;
uploading or pushing anything first is never a prerequisite for reviewing it. Figure out the
target from what the user gave you:
- **Specific file paths, an open editor file, or pasted code** → review exactly that, directly
  off disk (or the pasted text) — this works for code that has never been committed at all.
- **A local diff / staged / uncommitted changes** → `git diff` / `git diff --staged` scoped to
  `teams/inbox`. This is the default when nothing more specific is given: review whatever is
  currently uncommitted under `teams/inbox`.
- **A branch name** → `git diff main...<branch> -- teams/inbox` (adjust the base branch if told
  a different one) — works whether or not that branch has been pushed anywhere.
- **A PR number or URL** → only relevant once the code is actually in a PR: `gh pr diff <number>`
  (add `--repo` if not already in the repo) to get the diff, `gh pr view <number>` for
  description/context if useful.

Only evaluate files under `teams/inbox` (and, if present, code the change adds to `shared/` —
that's relevant to rule G below). Ignore unrelated files that happen to be in the same diff.

## 3. Check every changed file against these rule categories

For each changed/added file, check it against every category below that applies to its layer.
Don't limit yourself to these examples if you see a clear violation of a documented `CLAUDE.md`
rule that isn't explicitly listed — this list is the floor, not the ceiling.

**A — Business logic in Controller** *(Critical)*
A `controller/` class contains branching/looping/computation that decides a business outcome
(not just HTTP-shape logic), instead of delegating to a `Service` method. Rule: CLAUDE.md §4
(Controller responsibilities), §5 (dependency direction).

**B — Controller bypasses Service to reach Repository** *(Critical)*
A class in `controller/` autowires/imports a `*Repository` directly (with or without also
calling a Service). This is the single rule CLAUDE.md calls out as the one to watch hardest for.
Rule: CLAUDE.md §5.

**C — Wrong-direction layer access** *(Critical/Major depending on which layers)*
Any dependency pointing "up" the stack: `repository/` importing from `service/` or
`controller/`; `model/entity/` importing from `dto/`; a `Mapper` called from `Controller`
directly instead of from `Service` (Major — not as severe as A/B, but still a layering leak).
Rule: CLAUDE.md §5.

**D — DTO/Entity used in the wrong layer** *(Major)*
A `model/entity/` class returned from, or accepted as `@RequestBody` in, a `Controller`; a DTO
used inside `repository/`; persistence annotations (`@Entity`, `@Id`, `@Table`, …) on a class in
`dto/`; validation/JSON annotations aimed at the API on a class in `model/entity/`; an enum that
belongs in `model/enums/` defined inline inside a DTO or entity instead. Rule: CLAUDE.md §4 (DTO
/ Entity responsibilities).

**E — Inbox-only code placed under `shared/`** *(Major)*
Code added under `shared/` that only Team Inbox uses or that isn't an agreed contract (i.e. no
sign — PR description, linked issue/Slack decision — that another team asked for or agreed to
it). Rule: CLAUDE.md §13.

**F — Hard-coded configuration** *(Major, Critical if it's environment-specific and would break
another environment, e.g. a hard-coded prod URL/port)*
A literal value in `application.yml` or Java code for something that should be an environment
variable (`${VAR:default}` pattern) — ports, URLs, profile names, feature flags, credentials.
Rule: CLAUDE.md §12.

**G — Secrets in code** *(Critical, always)*
Anything that looks like a password, API key, token, connection string with embedded
credentials, or private key literal, anywhere in the diff — including tests and comments. Rule:
CLAUDE.md §12, and the bootcamp guide's Security/Secrets rule. Flag this even on a hunch —
false positives here are cheap, missed secrets are not.

**H — Exception handling doesn't match the standard** *(Major)*
An empty `catch` block; `catch (Exception e)` / `catch (Throwable t)` that swallows the error;
a raw exception message or stack trace returned to the client; a new exception type that
doesn't extend the shared `InboxException` base or isn't handled by
`GlobalExceptionHandler`; business-foreseeable failures falling through to a bare `500`. Rule:
CLAUDE.md §9.

**I — Naming or package-structure violations** *(Minor, Major if it actively misleads about the
layer — e.g. a class named `TaskRepository` that isn't a repository)*
Class names missing the expected role suffix; a class sitting in the wrong package for what it
does; a `Service`/`Impl` pair created without a real second implementation; non-plural or
wrong-case REST paths. Rule: CLAUDE.md §6, §7.

**J — Missing tests where they're required** *(Major for a Service with real logic and no test
at all, Minor for a thin/partial gap — e.g. only the happy path covered)*
A new/changed `Service` class with actual logic and no corresponding test class; a new
`Controller` endpoint with no slice test covering at least the happy path. Rule: CLAUDE.md §11.

## 4. Severity guide

- **Critical** — breaks the architecture or is a security issue in a way that should block the
  merge: A, B, G always; C/F when they'd actually break something (not just "not ideal").
- **Major** — a real violation of a documented rule that should be fixed before/soon after
  merge, but doesn't leak secrets or fully break layering: D, E, H, most of C/F, J for a
  fully-untested Service.
- **Minor** — style/consistency issues that don't threaten correctness: I, partial test gaps,
  small naming inconsistencies.

When in doubt between two severities, prefer the more conservative (lower) one and say why in
the recommendation — don't inflate severity to seem thorough.

## 5. Output format

Report one entry per finding, most severe first. Use this shape for each:

```
### [<Severity>] <short title>
- **Location:** <file path>:<line or method/class> (or "N/A" if it's a structural gap like a
  missing test file)
- **Problem:** <what's wrong, concretely — not "this could be better">
- **Rule violated:** CLAUDE.md §<section> — <one-line paraphrase of the rule>
- **Recommendation:** <specific, actionable fix — not "consider refactoring">
```

After listing findings, add one closing line summarizing counts, e.g.
`2 Critical, 1 Major, 0 Minor — do not merge until Critical items are resolved.`

If no meaningful issues are found, don't invent minor nitpicks to pad the output — say plainly:

```
No architecture violations found. This change is consistent with teams/inbox/CLAUDE.md.
```

## 6. What this skill is not

This is a rules-based architecture/layering check, not a full code review — it won't catch
business-logic bugs, performance issues, or things `CLAUDE.md` doesn't cover. It's meant to run
alongside normal human review, per the bootcamp guide's "AI is a tool, not a substitute for
engineering judgment" rule — a human still owns the final call on the PR.
