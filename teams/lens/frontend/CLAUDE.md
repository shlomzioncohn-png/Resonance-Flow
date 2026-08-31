# LENS Frontend Claude Code Guide

## Read project documentation first

This file contains Claude Code workflow rules only. Do not duplicate project facts here.

- Read [README.md](README.md) for the stack, project structure, and run commands.
- Read [docs/app-shell-and-routing.md](docs/app-shell-and-routing.md) for routes, App Shell,
  shared layout components, and the current role-navigation preview.

## Scope

Work only on the LENS frontend under `teams/lens/frontend` unless the task explicitly says otherwise.

Do not modify Gateway, BFF, Inbox, Bridge, Chain, or other teams' projects.

Do not implement or change authentication or authorization without an explicit task and an agreed API contract with the Gateway team.

## Development rules

- Check the working tree before editing.
- Use the existing Next.js App Router structure and MUI components described in `README.md`.
- Keep server and client components intentional; add `"use client"` only when needed.
- Reuse shared components instead of duplicating layout or feedback states.
- Keep changes focused and avoid unrelated refactors.
- Update `README.md` or the relevant document under `docs/` when documentation changes.
- Do not add secrets, tokens, `.env` files, `node_modules`, or `.next` output to Git.

## Git workflow

Before meaningful changes, run:

```bash
git status --short --branch
```

Use a feature branch with the format:

```text
participant-name/feature-name
```

Make small commits linked to the relevant Issue:

```bash
git add <files>
git commit -m "Describe the change #123"
git push -u origin <branch-name>
```

Do not commit, push, reset, or discard user changes unless explicitly requested.

Frontend Pull Requests normally target the team's integration branch, `lens-team`.

## Validation

Run commands from `teams/lens/frontend`:

```bash
npm ci
npm run build
```

If Docker files changed, also run:

```bash
docker compose config
docker compose build
```

For runtime changes, start the app and verify `http://localhost:3000`. Before finishing, run:

```bash
git diff --check
git status --short --branch
```

Report any validation command that could not be run and explain why.
