# LENS Frontend

Frontend foundation for the LENS team in Resonance Flow. The application is a React and Vite
frontend that is served as a static site behind Nginx when running with Docker.

## Stack

- React
- TypeScript
- Vite
- Docker and Docker Compose

## Project structure

```
src/                 Application entry point and styles
components/          Shared UI components
features/            Feature-specific modules
services/             API/service integrations
tests/                Test documentation and future tests
Dockerfile            Production container image
docker-compose.yml    Local container orchestration
nginx.conf            Nginx static-site configuration
```

## Run locally

Requires Node.js and npm.

```bash
npm ci
npm run dev
```

The development server is available at http://localhost:5173.

To create a production build:

```bash
npm run build
```

## Run with Docker

Build and start the frontend:

```bash
docker compose up --build
```

The application is available at http://localhost:5173. To use another host port, set
`FRONTEND_PORT`:

```bash
FRONTEND_PORT=8081 docker compose up --build
```

Stop the container with:

```bash
docker compose down
```