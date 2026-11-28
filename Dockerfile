# CN-009: build the admin console so the API can serve it at /console/ from
# the same origin (see ConsoleWebConfiguration and console/vite.config.ts,
# which emits the bundle with base "/console/").
FROM node:22-alpine AS console

WORKDIR /console

# Install dependencies first so Docker reuses this layer when only console
# sources change. The @visual-ui packages come from the tarballs in
# console/vendor/ (file: dependencies), so they must be present for npm ci.
COPY console/package.json console/package-lock.json ./
COPY console/vendor/ ./vendor/
RUN npm ci

# .dockerignore keeps console/node_modules and console/dist out of the build
# context, so this adds the sources without clobbering the installed modules.
COPY console/ ./
RUN npm run build

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
# CN-009: bundle the console build inside the jar. Spring Boot serves it from
# classpath:/static/console/ at /console/**, no separate container needed.
COPY --from=console /console/dist ./demo/src/main/resources/static/console/
RUN mvn --batch-mode -f demo/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
# Writable folder for the graph overlay file (schema-overlay.json). /app is
# owned by root, so the non-root user cannot write there. Mount a volume here
# to keep overlay edits across container rebuilds.
RUN mkdir -p /data && chown spring:spring /data
WORKDIR /app

COPY --from=build --chown=spring:spring /workspace/demo/target/*.jar ./app.jar

USER spring:spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
