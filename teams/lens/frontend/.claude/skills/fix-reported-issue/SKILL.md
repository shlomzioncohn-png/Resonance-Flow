---
name: fix-reported-issue
description: Analyze a reported problem in the LENS frontend - a GitHub issue, or PR review feedback requesting changes - and propose a minimal, validated code fix.
argument-hint: "Paste the issue/review text, or leave empty when triggered automatically from a GitHub event"
---

# Fix Reported Issue (LENS frontend)

Use this skill to analyze and fix a bug/problem reported for the LENS team frontend, from either of two sources:

- **A new GitHub issue** - the problem has no existing PR yet.
- **PR review feedback** (e.g. "changes requested" comments on an open PR) - the problem is feedback on code already in flight.

Identify which case applies before starting: if you were given a PR number and reviewer comments, it's the second case; if you were given only an issue number/title/body, it's the first.

## Scope guard

- This skill only handles problems in `teams/lens/frontend`. If the issue is clearly about another team (Bridge, Chain, Inbox, Mind), about Gateway/BFF, or requires a new backend API contract, stop and report that it is out of scope instead of guessing a fix.
- Read `teams/lens/frontend/CLAUDE.md` and `.github/agents/react-lens.agent.md` first — they define the binding rules for this codebase (route contracts, ownership boundaries, git workflow, validation commands). This skill does not repeat those rules; it follows them.

## Process

1. Restate the reported problem: expected vs. actual behavior, affected route/component if mentioned in the issue.
2. Locate the relevant code under `teams/lens/frontend` by searching for symptoms (component names, route paths, error text). Do not guess without reading the code first.
3. Form one falsifiable hypothesis for the root cause and check it against the code before changing anything.
4. Make the smallest change that fixes the root cause. No unrelated refactors, no drive-by cleanups.
5. If the fix touches UI-facing data shape, keep it inside the `LensDataInterface` / mock adapter boundary. Do not invent or change backend API contracts.
6. Follow the git workflow from `teams/lens/frontend/CLAUDE.md`, branching on which case this is:
   - **New issue**: create branch `claude/fix-<issue-number>-<short-slug>`, commit message `<description> #<issue-number>`, open a PR with base `lens-team`.
   - **PR review feedback**: the PR and branch already exist - do not create a new branch or a new PR. Commit directly onto the existing branch (`git commit -m "<description> #<pr-number>"`) and push to update the same PR so the reviewer sees the fix on the thread they already opened.
7. Validate from `teams/lens/frontend`:
   ```
   npm ci
   npm run build
   ```
   Report any validation command that could not run and why.
8. Report the result:
   - **New issue**: open the PR with a description containing what changed, validation results, remaining risks, and `Fixes #<issue-number>`.
   - **PR review feedback**: push the commit, then summarize what changed and validation results (posted as a PR comment or workflow log, depending on how the skill was invoked).

## Output format

- What changed (files + one-line reason each)
- Validation results (build/lint summary)
- Remaining risks or contract assumptions
- Link to the opened PR
