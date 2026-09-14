# Running E2E Tests in Docker

## Quick Start

### 1. Start Frontend in Docker
```bash
docker compose up --build
```

The frontend will be available at `http://localhost:3000`

### 2. Run Tests (in another terminal)

**Option A: Inside Container**
```bash
docker exec lens-frontend npm run test:e2e
```

**Option B: With UI Mode (visual debugging)**
```bash
docker exec lens-frontend npm run test:e2e:ui
```

**Option C: With Debug Mode**
```bash
docker exec lens-frontend npm run test:e2e:debug
```

---

## What's Included

The Docker image includes:
- ✅ Node.js 22 Alpine
- ✅ npm dependencies
- ✅ Playwright Chrome browser (optimized for Alpine)
- ✅ Development fonts and dependencies

---

## Test Results

After running tests, view results:
- HTML Report: `test-results/index.html`
- Screenshots: `test-results/`
- Videos: `test-results/`

---

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `FRONTEND_PORT` | `3000` | Port to expose Frontend |
| `NODE_ENV` | `development` | Node environment |

**Example: Use port 8081**
```bash
FRONTEND_PORT=8081 docker compose up
```

Then access at `http://localhost:8081`

---

## Troubleshooting

### Tests timeout/can't find elements
- Make sure Frontend is fully started: `docker logs lens-frontend`
- Check if port 3000 is accessible: `http://localhost:3000`
- Restart container: `docker compose restart lens-frontend`

### Browser issues
- Playwright Chrome is pre-installed in Docker image
- No additional browser installation needed

### Rebuild image
```bash
docker compose build --no-cache
```

---

## File Structure

```
teams/lens/frontend/
├── Dockerfile (updated: development target with Playwright)
├── docker-compose.yml (updated: volumes, environment, command)
├── DOCKER_E2E_TESTS.md (this file)
├── playwright.config.ts
└── tests/
    ├── e2e/
    │   └── workspace-recommendation.spec.ts
    └── fixtures/
        └── api-mocking.ts
```

---

## Next Steps

1. ✅ Start: `docker compose up --build`
2. ✅ Wait for: `ready - started server on 0.0.0.0:3000`
3. ✅ Test: `docker exec lens-frontend npm run test:e2e`
4. ✅ View: `test-results/index.html`

**That's it!** 🚀
