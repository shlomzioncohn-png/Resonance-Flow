# API integration status

Snapshot as of 2026-09-01, checked against `origin/main`. Written because the frontend
now has a `services/api.ts` client ready to point at the Gateway, but nothing actually
works end to end yet — this records exactly why, so it doesn't need rediscovering.

## What exists

- **Gateway** (`teams/lens/gateway`): `/health` and `/health/ready`, plus one proxy route
  (`/api/**` → BFF). No other routes.
- **BFF** (`teams/lens/bff`): one endpoint, `GET /api/status` → `{"service":"bff","status":"up"}`.
  Its own README calls it _"Project skeleton only (stage 1)"_ — no calls to Bridge, Mind,
  Inbox, or Chain exist yet.
- **Frontend**: `services/api.ts` + `services/gatewayHealth.ts`, not imported anywhere yet.
  All employee screens still run on mock data (`app/(app)/employee/mockTask.ts`).

## Why nothing connects yet, even for a smoke test

Four concrete, independent mismatches between the Gateway's code and how BFF actually
runs (per `teams/lens/docker-compose.yml`, the compose file that runs both together):

| Gateway expects (`HealthController.java`, `application.yml`) | BFF actually is                                        | Source                                                                           |
| ------------------------------------------------------------ | ------------------------------------------------------ | -------------------------------------------------------------------------------- |
| host `bff-service`                                           | service name `bff`                                     | `teams/lens/docker-compose.yml`                                                  |
| port `8080`                                                  | port `8082`                                            | same, `SERVER_PORT=8082`                                                         |
| health at `/health`                                          | health at `/actuator/health` (Spring Actuator default) | `teams/lens/bff/README.md`                                                       |
| proxy strips `/api`, forwards `/status`                      | controller is mapped at `/api/status`, not `/status`   | `StatusController.java` `@GetMapping("/api/status")` + Gateway's `StripPrefix=1` |

The last one means even a corrected hostname/port wouldn't fix `/api/status` through the
Gateway — the stripped path doesn't match BFF's own route. It would still 404.

## What isn't broken

Gateway's own `GET /health` doesn't touch any of the above — it always reports the
Gateway's own status, and best-effort reports `dependencies.bff` (DOWN today, for the
reasons above). That's why `services/gatewayHealth.ts` targets it first: it's the one
thing that can return a real, non-mocked response before any of the wiring bugs are fixed.

## Next real step

Once Gateway's three hostname/port/path values are corrected to match
`teams/lens/docker-compose.yml`, `fetchGatewayHealth()` becomes genuinely testable.
Real task data is a separate, later step — it needs BFF to implement at least one
business endpoint (owned by BFF per their README: aggregate + DTO-map Bridge/Mind/Inbox/Chain),
which hasn't started.
