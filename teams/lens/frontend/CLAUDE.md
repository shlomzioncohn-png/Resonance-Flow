# LENS Frontend Development Guide

## Project scope

This directory is the root of the LENS frontend project.

The frontend uses:

- React
- Next.js with the App Router
- TypeScript
- Material UI (MUI)
- Docker and Docker Compose

All frontend application code, configuration, documentation, and infrastructure files must stay under `teams/lens/frontend`.

## Architecture

- `app/`: Next.js routes, layouts, route groups, and global styles
- `components/layout/`: shared App Shell, Header, Sidebar, PageContainer, and Logo
- `components/feedback/`: LoadingState, EmptyState, and ErrorState
- `components/ui/`: shared UI components that are not layout-specific
- `features/`: feature-specific code
- `services/`: API and external-service integrations
- `hooks/`: shared React hooks
- `config/`: frontend configuration
- `types/`: shared TypeScript types
- `utils/`: shared utility functions
- `mocks/`: temporary mock data and adapters
- `docs/`: architecture and development documentation
- `tests/`: frontend tests

Read `README.md` and the relevant file under `docs/` before changing architecture or routing.

## Development commands

Run these commands from `teams/lens/frontend`:

```bash
npm ci
npm run dev
npm run build
npm run start
```

The development and production servers use port `3000` by default.

Run with Docker:

```bash
docker compose up --build
docker compose down
```

Use `FRONTEND_PORT` to change the host port when needed.

## Coding rules

- Use TypeScript and keep strict typing enabled.
- Prefer the existing Next.js App Router structure.
- Use MUI components and the existing theme/design conventions for shared UI.
- Keep server and client components intentional. Add `"use client"` only when client behavior is required.
- Reuse shared components instead of duplicating layout or feedback states.
- Keep text, labels, and user-facing behavior accessible.
- Avoid unrelated refactors and do not change public APIs without a clear reason.
- Update the relevant documentation when changing architecture, routes, commands, or folder structure.

## Boundaries

Work only on the LENS frontend unless the task explicitly says otherwise.

Do not modify the Gateway, BFF, Inbox, Bridge, Chain, or other teams' projects.

Do not implement or change authentication and authorization behavior unless there is an explicit task and an agreed API contract with the Gateway team.

Do not add secrets, tokens, `.env` files, `node_modules`, or `.next` build output to Git.

## Git workflow

Before editing:

```bash
git status --short --branch
```

Use a feature branch with the format:

```text
participant-name/feature-name
```

Make small commits that reference the relevant Issue number:

```bash
git add <files>
git commit -m "Describe the change #123"
git push -u origin <branch-name>
```

Do not commit or push unless the user explicitly asks for it.

Pull Requests for frontend work should target the team's integration branch, normally `lens-team`, unless the task says otherwise.

## Validation

After code or configuration changes:

1. Run `npm run build`.
2. If Docker files changed, run `docker compose config` and `docker compose build`.
3. For runtime changes, start the app and verify `http://localhost:3000`.
4. Report any command that could not be run and why.

Before finishing, check:

```bash
git status --short --branch
git diff --check
```
