# Docker Integration Guide - Resonance Flow

## Overview

This guide explains how to run all Resonance Flow services (Gateway, Bridge, Mind, Inbox, Chain) using Docker Compose in an orchestrated, networked environment.

## Prerequisites

- Docker Desktop (or Docker Engine)
- Docker Compose v1.29+
- Git
- Workspace: `Resonance-Flow` project root

## Quick Start

### 1. Build and Start All Services

```bash
# Navigate to project root
cd Resonance-Flow

# Build and start all services (runs in background)
docker-compose up --build -d

# Watch logs
docker-compose logs -f

# Stop all services
docker-compose down
```

### 2. Verify Services Are Running

```bash
# Check service status
docker-compose ps

# Expected output:
# NAME               STATUS              PORTS
# gateway-service    Up (healthy)        0.0.0.0:8080->8080/tcp
# bridge-service     Up (healthy)        0.0.0.0:8081->8080/tcp
# mind-service       Up (healthy)        0.0.0.0:8082->8080/tcp
# inbox-service      Up (healthy)        0.0.0.0:8083->8080/tcp
# chain-service      Up (healthy)        0.0.0.0:8084->8080/tcp
```

### 3. Test the Gateway

```bash
# Check gateway health
curl http://localhost:8080/health

# Check readiness (all services must be UP)
curl http://localhost:8080/health/ready

# Route a request through gateway to bridge service
curl http://localhost:8080/api/bridge/status
```

## Architecture

### Service Topology

```
Client/External Request
        ↓
     Gateway (Port 8080) ← Public Interface
        ↓ (routes via /api/{service}/)
   ┌────┼────┬────────┐
   ↓    ↓    ↓        ↓
Bridge Mind Inbox  Chain
(8081)(8082)(8083)(8084)  ← Internal Container Network
   ↓    ↓    ↓        ↓
[Services running on internal port 8080]
```

### Network Configuration

**Network Name:** `resonance-network` (bridge driver)

**Container Hostnames (DNS names within network):**
- `gateway-service` → http://gateway-service:8080
- `bridge-service` → http://bridge-service:8080
- `mind-service` → http://mind-service:8080
- `inbox-service` → http://inbox-service:8080
- `chain-service` → http://chain-service:8080

**Port Mapping:**
- Gateway container port 8080 → Host port 8080
- Bridge container port 8080 → Host port 8081
- Mind container port 8080 → Host port 8082
- Inbox container port 8080 → Host port 8083
- Chain container port 8080 → Host port 8084

### Environment Variables

Each service receives:
- `SERVER_PORT=8080` (internal container port)
- `SPRING_PROFILES_ACTIVE=local` (disable auth for dev)

Gateway additionally receives service URLs:
- `BRIDGE_URL=http://bridge-service:8080`
- `MIND_URL=http://mind-service:8080`
- `INBOX_URL=http://inbox-service:8080`
- `CHAIN_URL=http://chain-service:8080`

## Configuration

### Using Environment Variables

Create `.env` file in project root:

```bash
cp .env.example .env
# Edit .env as needed
```

Example `.env`:
```
GATEWAY_PORT=8080
BRIDGE_PORT=8081
MIND_PORT=8082
INBOX_PORT=8083
CHAIN_PORT=8084
SPRING_PROFILES_ACTIVE=local
```

### Customizing Service Configuration

To override environment for a specific service, edit `docker-compose.yml`:

```yaml
  gateway:
    environment:
      - CUSTOM_VAR=value
```

## Common Operations

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f gateway

# Last 50 lines
docker-compose logs --tail=50
```

### Execute Commands in Container

```bash
# Bash shell in gateway
docker-compose exec gateway bash

# Run curl test
docker-compose exec gateway curl http://localhost:8080/health
```

### Restart a Service

```bash
# Restart gateway
docker-compose restart gateway

# Rebuild and restart (if code changed)
docker-compose up --build -d gateway
```

### Clean Up

```bash
# Stop containers (data persists)
docker-compose stop

# Stop and remove containers
docker-compose down

# Remove containers, volumes, networks, images
docker-compose down -v --rmi all
```

## Health Checks

Each service includes a Docker health check:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
  interval: 10s      # Check every 10 seconds
  timeout: 5s        # Curl timeout
  retries: 3         # Fail after 3 consecutive failures
  start_period: 20s  # Wait 20s after start before first check
```

Status indicators:
- `Up (healthy)` - Service passed health checks
- `Up (unhealthy)` - Service failed health checks
- `Up` - Service running but health check not configured

## Gateway Routing

The gateway uses Spring Cloud Gateway with path-based routing:

```
Request: GET /api/bridge/users
↓
Gateway Rule: Path("/api/bridge/**")
↓
Transform: Strip "/api/bridge" prefix
↓
Route to: http://bridge-service:8080/users
```

### Available Routes

| Service | Path Pattern | Target |
|---------|-------------|--------|
| Bridge | `/api/bridge/**` | http://bridge-service:8080 |
| Mind | `/api/mind/**` | http://mind-service:8080 |
| Inbox | `/api/inbox/**` | http://inbox-service:8080 |
| Chain | `/api/chain/**` | http://chain-service:8080 |

## Health Endpoints

**Gateway Health Check:**
```bash
curl http://localhost:8080/health
```

Response:
```json
{
  "status": "UP|DEGRADED|DOWN",
  "message": "X/4 services healthy",
  "timestamp": "2026-08-24T11:41:15.110059075",
  "services": [
    {
      "name": "bridge",
      "status": "UP",
      "url": "http://bridge-service:8080/health",
      "responseTime": 45
    }
    // ... other services
  ],
  "servicesUp": 4,
  "servicesDown": 0
}
```

**Readiness Check:**
```bash
curl http://localhost:8080/health/ready
```

Returns:
- `200 OK` - All services UP and ready
- `503 Service Unavailable` - One or more services DOWN

## Troubleshooting

### Services Won't Start

**Issue:** `ERROR: Service 'bridge' failed to build`

**Solution:**
```bash
# Clean and rebuild
docker-compose down -v
docker-compose build --no-cache
docker-compose up -d
```

### Services Can't Communicate

**Issue:** `Cannot reach http://bridge-service:8080`

**Solution:**
1. Verify network exists: `docker network ls | grep resonance-network`
2. Check service names: `docker-compose ps`
3. Inspect network: `docker network inspect resonance-network`

### Port Already in Use

**Issue:** `bind: address already in use`

**Solution:**
```bash
# Find service using port 8080
lsof -i :8080

# Stop conflicting service or use different port
# Edit .env and change GATEWAY_PORT=8090
```

### Health Checks Failing

**Issue:** Service shows `Up (unhealthy)`

**Solution:**
1. Check service logs: `docker-compose logs gateway`
2. Test endpoint manually: `docker-compose exec gateway curl http://localhost:8080/health`
3. Increase `start_period` in docker-compose.yml if service needs longer startup time

## Security

### Local Profile (Development)

Current setup uses `SPRING_PROFILES_ACTIVE=local`:
- ✅ No authentication required
- ✅ All endpoints public
- ✅ CORS disabled
- ✅ Good for local development

### Production Profile

For production, set `SPRING_PROFILES_ACTIVE=prod`:
- Enables Spring Security
- Requires JWT authentication tokens
- Restricts /health/* to anonymous access only
- Requires additional configuration (secrets, SSL, etc.)

## Performance Tuning

### Reduce Startup Time

```yaml
gateway:
  healthcheck:
    start_period: 10s  # Reduced from 20s
```

### Increase Resource Limits (if needed)

```yaml
services:
  gateway:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
        reservations:
          cpus: '0.5'
          memory: 256M
```

## Next Steps

1. **Add monitoring**: Integrate Prometheus/Grafana for metrics
2. **Add logging**: Set up centralized logging (ELK stack)
3. **Add security**: Move to `prod` profile with JWT tokens
4. **Add persistence**: Add databases (PostgreSQL, MongoDB, etc.)
5. **Add CI/CD**: Set up GitHub Actions for automated builds

## References

- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [Docker Networking](https://docs.docker.com/network/)

## Support

For issues or questions, refer to:
- [GATEWAY_CONTRACT.md](./files/GATEWAY_CONTRACT.md)
- [INTEGRATION_GUIDE.md](./teams/lens/gateway/INTEGRATION_GUIDE.md)
- Service-specific README files in each team directory
