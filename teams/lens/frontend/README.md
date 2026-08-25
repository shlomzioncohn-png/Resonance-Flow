# LENS Frontend

Frontend foundation for the LENS team in Resonance Flow. This is a React and Next.js application
using Material UI (MUI).

## Stack

- React
- TypeScript
- Next.js
- Material UI (MUI)
- Docker and Docker Compose

## Project structure

```
theme/                       LENS design system: MUI theme, design tokens (see below)
app/                        Next.js routes, layouts, global styles
  (app)/                      Routes wrapped in the App Shell (Header + Sidebar)
  (public)/                   Routes with no shell (/login, /unauthorized)
components/
  layout/                     AppShell, Header, Sidebar, PageContainer, Logo
  feedback/                   LoadingState, EmptyState, ErrorState
  ui/                          Shared, non-layout UI components (e.g. StatusBadge)
mocks/                       Mock data adapters used until real APIs exist
docs/                        Architecture docs (see below)
features/, services/,       Reserved for upcoming feature work — not used yet
hooks/, config/,
types/, utils/, tests/
Dockerfile                  Production container image
docker-compose.yml          Local container orchestration
```

See [docs/app-shell-and-routing.md](docs/app-shell-and-routing.md) for the
routing structure, the App Shell components, and how to add a new page.

See [docs/design-system.md](docs/design-system.md) for the MUI theme, design
tokens, and conventions for using MUI across the project.

## Run locally

Requires Node.js and npm.

```bash
npm ci
npm run dev
```

The development server is available at http://localhost:3000.

To create a production build:

```bash
npm run build
```

## Run with Docker

Build and start the frontend:

```bash
docker compose up --build
```

The application is available at http://localhost:3000. To use another host port, set
`FRONTEND_PORT`:

```bash
FRONTEND_PORT=8081 docker compose up --build
```

Stop the container with:

```bash
docker compose down
```