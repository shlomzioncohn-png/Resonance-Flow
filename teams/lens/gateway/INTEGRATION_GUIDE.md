# Gateway Integration Guide for Service Teams

> **For:** Bridge, Mind, Inbox, Chain teams  
> **Purpose:** How to integrate your service with LENS Gateway

## Quick Start

### 1. Your Service Requirements

Your service must:
- ✅ Run on a **fixed URL** (e.g., `http://bridge:8080`)
- ✅ Be **alive during docker-compose up**
- ✅ Return **standard HTTP responses** (any 2xx/4xx/5xx status)
- ✅ Accept requests **without `/api/{service}/` prefix** (Gateway strips it)

### 2. Configure Gateway (LENS does this)

LENS Gateway team adds a route in `application.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: your-service
          uri: ${YOUR_SERVICE_URL:http://your-service:8080}
          predicates:
            - Path=/api/your-service/**
          filters:
            - StripPrefix=2  # Remove /api/your-service
```

### 3. Test Your Integration

Once added to Gateway:

```bash
# Your service endpoint
POST http://your-service:8080/data

# Via Gateway
POST http://gateway:8080/api/your-service/data

# Gateway strips /api/your-service and forwards to your service
```

---

## Example: Bridge Integration

### Bridge provides
- Service runs at: `http://bridge:8080`
- API endpoints: `/sources`, `/sync`, `/health`

### Gateway routes
```yaml
- id: bridge
  uri: ${BRIDGE_URL:http://bridge:8080}
  predicates:
    - Path=/api/bridge/**
  filters:
    - StripPrefix=2
```

### Client uses
| Client calls | Gateway sends |
|---|---|
| `/api/bridge/sources` | `GET http://bridge:8080/sources` |
| `/api/bridge/sync` | `POST http://bridge:8080/sync` |
| `/api/bridge/health` | `GET http://bridge:8080/health` |

---

## Error Handling

Gateway will handle these scenarios:

| Scenario | Gateway Returns | Your Service Action |
|---|---|---|
| Service unavailable | 503 SERVICE_UNAVAILABLE | Make sure it's running |
| Request timeout | 504 GATEWAY_TIMEOUT | Check for slow responses |
| Your service returns 400 | 400 BAD_REQUEST | Forward as-is |
| Your service returns 500 | 500 INTERNAL_ERROR | Forward as-is |

**Gateway does NOT modify** your response status or body.

---

## Docker Compose Integration

### Your docker-compose.yml

```yaml
services:
  your-service:
    image: your-service:latest
    container_name: your-service
    environment:
      - SERVER_PORT=8080
    ports:
      - "8081:8080"  # For direct testing
    networks:
      - gateway-network

networks:
  gateway-network:
    external: true  # Share with Gateway network
```

### Gateway docker-compose.yml (includes all services)

```yaml
services:
  gateway:
    build: ./teams/lens/gateway
    container_name: gateway-service
    ports:
      - "8080:8080"
    environment:
      - BRIDGE_URL=http://bridge:8080
      - MIND_URL=http://mind:8080
    networks:
      - default

  bridge:
    image: bridge:latest
    container_name: bridge-service
    ports:
      - "8081:8080"

  mind:
    image: mind:latest
    container_name: mind-service
    ports:
      - "8082:8000"
```

Then run:
```bash
docker-compose up --build
```

---

## Testing Your Service with Gateway

### Step 1: Verify service is running
```bash
curl http://your-service:8080/health
```

### Step 2: Test via Gateway
```bash
curl http://gateway:8080/api/your-service/health
```

### Step 3: Check logs
```bash
docker logs gateway-service
```

---

## Troubleshooting

| Problem | Check | Fix |
|---|---|---|
| 503 SERVICE_UNAVAILABLE | Service running? | `docker ps` to verify |
| 504 GATEWAY_TIMEOUT | Service slow? | Check service logs, add timeout |
| 404 NOT_FOUND | Route configured? | Ask LENS team to add route |
| Response looks wrong | Body modified? | Gateway doesn't modify responses |

---

## Questions?

Contact LENS team (Dvori) or review [GATEWAY_CONTRACT.md](./GATEWAY_CONTRACT.md)
