# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY main ./main
COPY test ./test
COPY --from=shared proto ./shared/proto
RUN mvn -B package -DskipTests -Dproto.root=/app/shared/proto

# E2E client (scripts/e2e.sh builds it with --target e2e).
FROM build AS e2e
RUN mvn -B -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=/app/classpath.txt
ENTRYPOINT ["sh", "-c", "exec java -cp target/classes:target/test-classes:$(cat /app/classpath.txt) pipe.e2e.NotificationDeliveryE2E"]

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
