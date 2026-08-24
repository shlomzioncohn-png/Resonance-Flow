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
app/                 Next.js routes and global styles
components/          Shared UI components
features/            Feature-specific modules
services/             API/service integrations
tests/                Test documentation and future tests
Dockerfile            Production container image
docker-compose.yml    Local container orchestration
```

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