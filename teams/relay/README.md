# Team Relay - Service

Foundation module for Team Relay (event bus and service communication).

This is a minimal Spring Boot skeleton. No Kafka integration or business logic is implemented yet.

## Stack

- Java 11
- Spring Boot 2.7.18 (Maven)
- Docker / Docker Compose

## Project structure

```
notification_delivery/main/java/
└── RelayApplication.java   # Spring Boot entry point
notification_delivery/main/resources/application.yml
notification_delivery/test/java/
```

## Running locally with Maven

Requires Maven and JDK 11.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8082.

To run tests:

```bash
mvn test
```

## Running with Docker

```bash
docker compose up --build
```

The app starts on http://localhost:8082.

To stop:

```bash
docker compose down
```