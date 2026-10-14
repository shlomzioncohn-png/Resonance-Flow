# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# "shared" is a named build context (repo-root shared/) - outside this dir
COPY --from=shared proto ./shared/proto
RUN mvn -B package -DskipTests -Dproto.root=/app/shared/proto

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
