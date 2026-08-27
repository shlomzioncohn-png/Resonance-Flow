---
name: fix-reported-issue
description: Analyze a GitHub issue reporting a bug or problem in the LENS frontend and propose a minimal, validated code fix as a pull request against lens-team.
argument-hint: "Paste the issue title + body, or leave empty when triggered automatically from a GitHub issue event"
---

# Fix Reported Issue (LENS frontend)

Use this skill to analyze and fix a bug/problem reported in a GitHub issue for the LENS team frontend.

## Scope guard

- This skill only handles problems in `teams/lens/frontend`. If the issue is clearly about another team (Bridge, Chain, Inbox, Mind), about Gateway/BFF, or requires a new backend API contract, stop and report that it is out of scope instead of guessing a fix.
- Read `teams/lens/frontend/CLAUDE.md` and `.github/agents/react-lens.agent.md` first — they define the binding rules for this codebase (route contracts, ownership boundaries, git workflow, validation commands). This skill does not repeat those rules; it follows them.

## Process

1. Restate the reported problem: expected vs. actual behavior, affected route/component if mentioned in the issue.
2. Locate the relevant code under `teams/lens/frontend` by searching for symptoms (component names, route paths, error text). Do not guess without reading the code first.
3. Form one falsifiable hypothesis for the root cause and check it against the code before changing anything.
4. Make the smallest change that fixes the root cause. No unrelated refactors, no drive-by cleanups.
5. If the fix touches UI-facing data shape, keep it inside the `LensDataInterface` / mock adapter boundary. Do not invent or change backend API contracts.
6. Follow the git workflow from `teams/lens/frontend/CLAUDE.md`:
   - Branch name: `claude/fix-<issue-number>-<short-slug>`
   - Commit message references the issue: `<description> #<issue-number>`
   - PR base branch is `lens-team`, not the repo default branch.
7. Validate from `teams/lens/frontend`:
   ```
   npm ci
   npm run build
   ```
   Report any validation command that could not run and why.
8. Open the PR with a description containing: what changed, validation results, remaining risks, and `Fixes #<issue-number>`.

## Output format

- What changed (files + one-line reason each)
- Validation results (build/lint summary)
- Remaining risks or contract assumptions
- Link to the opened PR
