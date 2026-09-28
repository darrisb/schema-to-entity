FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

# Copy the build descriptor first so dependency downloads remain cached when
# only application source files change.
COPY spring-example/pom.xml ./pom.xml
RUN mvn --batch-mode dependency:go-offline

COPY spring-example/src ./src
RUN mvn --batch-mode package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

COPY --from=build --chown=spring:spring /workspace/target/*.jar ./app.jar

USER spring:spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
