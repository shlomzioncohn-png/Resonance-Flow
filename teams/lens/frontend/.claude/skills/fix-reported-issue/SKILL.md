---
name: fix-reported-issue
description: Analyze a reported problem in the LENS frontend - a GitHub issue, or PR review feedback requesting changes - and propose a minimal, validated code fix.
argument-hint: 'Paste the issue/review text, or leave empty when triggered automatically from a GitHub event'
---

# Fix Reported Issue (LENS frontend)

Use this skill to analyze and fix a bug/problem reported for the LENS team frontend, from either of two sources:

- **A new GitHub issue** - the problem has no existing PR yet.
- **PR review feedback** (e.g. "changes requested" comments on an open PR) - the problem is feedback on code already in flight.

Identify which case applies before starting: if you were given a PR number and reviewer comments, it's the second case; if you were given only an issue number/title/body, it's the first.

## Scope guard

- This skill only handles problems in `teams/lens/frontend`. If the issue is clearly about another team (Bridge, Chain, Inbox, Mind), about Gateway/BFF, or requires a new backend API contract, stop and report that it is out of scope instead of guessing a fix.
- Read `teams/lens/frontend/CLAUDE.md` and `.github/agents/react-lens.agent.md` first — they define the binding rules for this codebase (route contracts, ownership boundaries, git workflow, validation commands). This skill does not repeat those rules; it follows them.

## Human approval is required before anything is written to git

This skill never commits, pushes, creates a branch, or opens/updates a pull request on its own judgment. It proposes; a human decides. This applies identically whether the skill was invoked interactively or automatically (e.g. from the GitHub Action) — there is no mode where it skips the approval step.

- Prepare the fix in the working tree so it can be reviewed as a real diff, but stop there.
- Present the proposal (see Output format) and explicitly ask whether to proceed. A vague go-ahead earlier in the conversation ("build the skill", "handle issues automatically") is not approval of this specific fix — wait for an explicit yes on the actual diff shown.
- **Invoked non-interactively** (issue labeled `lens-auto-fix`, or a PR review event, with no human present to answer): do not commit or push under any circumstances. Post the proposal as a comment on the issue/PR instead, and stop. Applying it is a separate, explicit follow-up step a human triggers afterward — never the same run that proposed it.
- **Invoked interactively** (a human is in the conversation): show the diff and wait for their explicit approval before running any `git add` / `git commit` / `git push` / PR-creation command.
- Only after that explicit approval, follow the git workflow from `teams/lens/frontend/CLAUDE.md`.

## Process

1. Restate the reported problem: expected vs. actual behavior, affected route/component if mentioned in the issue.
2. Locate the relevant code under `teams/lens/frontend` by searching for symptoms (component names, route paths, error text). Do not guess without reading the code first.
3. Form one falsifiable hypothesis for the root cause and check it against the code before changing anything.
4. Make the smallest change that fixes the root cause. No unrelated refactors, no drive-by cleanups.
5. If the fix touches UI-facing data shape, keep it inside the `LensDataInterface` / mock adapter boundary. Do not invent or change backend API contracts.
6. Validate from `teams/lens/frontend`:
   ```
   npm ci
   npm run build
   ```
   Report any validation command that could not run and why.
7. Present the proposal and stop (see "Human approval is required" above). Do not proceed past this point without explicit approval.
8. Only after explicit approval, follow the git workflow from `teams/lens/frontend/CLAUDE.md`, branching on which case this is:
   - **New issue**: create branch `claude/fix-<issue-number>-<short-slug>`, commit message `<description> #<issue-number>`, open a PR with base `lens-team`.
   - **PR review feedback**: the PR and branch already exist - do not create a new branch or a new PR. Commit directly onto the existing branch (`git commit -m "<description> #<pr-number>"`) and push to update the same PR so the reviewer sees the fix on the thread they already opened.
9. Report what was actually done: confirm the commit/PR now exists, or, if approval was withheld or this was a non-interactive proposal awaiting a follow-up, say so plainly instead of implying the fix was applied.

## Output format

The proposal (steps 1-7) is reported the same way whether or not it's later approved:

- What changed (files + one-line reason each) and the actual diff
- Validation results (build/lint summary)
- Remaining risks or contract assumptions
- Explicit ask: "Apply this?" / "Push this to the PR?" — never assumed

After approval and push, add: a link to the opened/updated PR.
