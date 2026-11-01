# Team Relay - Service

Foundation module for Team Relay (event bus and service communication).

Its service is `notification_delivery`, which pushes task notifications to users over WebSocket.
See [notification_delivery/README.md](notification_delivery/README.md).

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Docker / Docker Compose

## Project structure

```
notification_delivery/main/java/pipe/
└── NotificationDeliveryApplication.java   # Spring Boot entry point
notification_delivery/main/resources/application.yml
notification_delivery/test/java/
```

## Running locally with Maven

Requires Maven and JDK 21.

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