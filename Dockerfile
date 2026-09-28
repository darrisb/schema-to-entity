FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

# Build the starter library first so its dependencies stay cached when only the
# demo application source changes.
COPY api/pom.xml ./api/pom.xml
RUN mvn --batch-mode -f api/pom.xml dependency:go-offline

COPY api/src ./api/src
RUN mvn --batch-mode -f api/pom.xml install -DskipTests

COPY demo/pom.xml ./demo/pom.xml
RUN mvn --batch-mode -f demo/pom.xml dependency:go-offline

COPY demo/src ./demo/src
RUN mvn --batch-mode -f demo/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

COPY --from=build --chown=spring:spring /workspace/demo/target/*.jar ./app.jar

USER spring:spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
