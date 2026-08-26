# Team LENS - BFF Service

Backend-For-Frontend service responsible for routing requests from the Gateway to the
backing services (Bridge, Mind, Inbox, Chain) and mapping their entities to DTOs
consumed by the Frontend.

## Responsibilities

- Aggregate/compose calls to downstream services
- Map service entities to frontend-facing DTOs
- Shield the Frontend from downstream service contract changes

## Stack

- Java 21
- Spring Boot 3.4.0 (Web, Actuator)
- Maven
- Docker / Docker Compose

## Project structure

```
src/main/java/com/rnd/bff/
├── BffApplication.java   # Spring Boot entry point
└── controller/            # REST controllers
src/main/resources/
└── application.yml       # config (port, actuator)
src/test/java/com/rnd/bff/
```

## Running locally

Requires JDK 21 and Maven 3.9+.

```bash
mvn spring-boot:run
```

The service starts on [http://localhost:8082](http://localhost:8082).

- Health check: `GET /actuator/health`
- Status: `GET /api/status`

## Running with Docker

```bash
docker-compose up --build
```

To stop:

```bash
docker-compose down
```

## Building

```bash
mvn clean package
java -jar target/bff-0.1.0-SNAPSHOT.jar
```

## Environment variables

- `SERVER_PORT` - Server port (default: 8082)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: local)

## Status

Project skeleton only (stage 1). Routing/DTO-mapping logic will be added once the
downstream service contracts (Bridge, Mind, Inbox, Chain) are defined.

## Next steps

- Define DTOs shared with the Frontend
- Implement routing/aggregation to Bridge, Mind, Inbox, Chain
- Wire up authentication/authorization propagated from the Gateway
