# Team LENS - Gateway Service

API Gateway service responsible for permissions, routing, and authentication for the Resonance Flow system.

This service acts as the first interface, handling:
- Authentication and authorization
- Request routing to backend services
- API gateway functionality
- Permission validation

This is a minimal Spring Boot skeleton. Business logic and real APIs are built incrementally - the
goal of this stage is a clean, working Maven/Spring Boot project with security foundation.

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Spring Security (for authentication & authorization)
- Docker / Docker Compose

## Project structure

```
src/main/java/com/rnd/gateway/
├── GatewayApplication.java   # Spring Boot entry point
├── controller/
├── service/
├── repository/
├── dto/
├── security/
└── config/
src/main/resources/application.yml
src/test/java/com/rnd/gateway/
```

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 21.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080

To just build the jar:

```bash
mvn package -DskipTests
```

## Running with Docker

Build and run with Docker Compose:

```bash
docker-compose up --build
```

The service will be available at http://localhost:8080

To stop:

```bash
docker-compose down
```

## Environment variables

- `SERVER_PORT` - Server port (default: 8080)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: local)

## Next steps

- Implement authentication endpoints
- Add authorization/permission middleware
- Integrate with other services (Bridge, Mind, Inbox, Chain)
- Add JWT token handling
- Implement rate limiting and circuit breakers
