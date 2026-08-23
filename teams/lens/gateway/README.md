# Team LENS - API Gateway Service

API Gateway service responsible for permissions, routing, and authentication for the Resonance Flow system.

This service acts as the first interface, handling:
- Request routing to Bridge, Mind, Inbox, and Chain services
- Authentication and authorization
- Rate limiting and circuit breakers
- Request/response transformation
- CORS handling

## Why Spring Cloud Gateway?

**Spring Cloud Gateway** was chosen instead of plain Spring Boot because:

1. **Purpose-Built for Gateways** 
   - Designed specifically for API gateway patterns
   - Optimized non-blocking request handling
   - Reactive stack for high throughput

2. **Built-in Features** (No Custom Code Needed)
   - Route configuration via YAML (not Java code)
   - Pre-built filters: rate limiting, retry, circuit breaker
   - Request/response transformation filters
   - Load balancing and service discovery support

3. **Configuration-Driven Architecture**
   - Routes defined in `application.yml`
   - Easy to add new routes without coding
   - Filters applied declaratively

4. **Performance**
   - Non-blocking I/O (Project Reactor)
   - Better resource utilization
   - Lower memory footprint than traditional servlet containers

5. **Team Scalability**
   - Less boilerplate code to maintain
   - New team members can add routes via configuration
   - Clear separation of concerns

## Stack

- Java 21
- Spring Boot 3.3.13
- Spring Cloud Gateway 2024.0.0
- Spring Security (for authentication & authorization)
- JWT for token-based authentication
- Docker / Docker Compose

## Project structure

```
src/main/java/com/rnd/gateway/
├── GatewayApplication.java   # Spring Boot entry point
├── security/                 # Authentication & authorization
├── filters/                  # Custom gateway filters
└── config/                   # Configuration classes
src/main/resources/
├── application.yml          # Gateway routes & configuration
└── application-dev.yml      # Development profile
src/test/java/com/rnd/gateway/
```

## Gateway Routes

The gateway automatically routes requests to downstream services:

| Route | Service | Path |
|-------|---------|------|
| `/api/bridge/**` | Bridge | ETL & Source Adapters |
| `/api/mind/**` | Mind | Extraction & Scoring |
| `/api/inbox/**` | Inbox | Task Model & Lifecycle |
| `/api/chain/**` | Chain | Dependencie/api

To just build the jar:

```bash
mvn package -DskipTests
```

## Running with Docker

Build and run with Docker Compose:

```bash
docker-compose up --build
```

The service will be available at http://localhost:8080/api

To stop:

```bash
docker-compose down
```

## Environment variables

- `SERVER_PORT` - Server port (default: 8080)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: local)

## Configuration Example

Routes are configured in `application.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: bridge
          uri: http://bridge:8080
          predicates:
            - Path=/api/bridge/**
          filters:
            - StripPrefix=2
```

## Next Steps

- Implement JWT token validation filter
- Add authentication endpoint (login)
- Integrate with other services
- Add circuit breaker patterns
- Setup service discovery (if scaling)
- Add API documentation (Swagger/OpenAPI)
## Environment variables

- `SERVER_PORT` - Server port (default: 8080)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: local)

## Next steps

- Implement authentication endpoints
- Add authorization/permission middleware
- Integrate with other services (Bridge, Mind, Inbox, Chain)
- Add JWT token handling
- Implement rate limiting and circuit breakers
