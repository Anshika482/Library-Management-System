# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build: compile and package with the JDK. The tests are not run here - they
# need a MySQL server, and CI runs them before this image is built.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw \
    && ./mvnw -B -ntp -DskipTests package \
    && cp target/library-management-system-*.jar app.jar

# ---------------------------------------------------------------------------
# Runtime: the JRE and the application jar, nothing else, run as a non-root
# user.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

RUN groupadd --system app \
    && useradd --system --gid app --no-create-home --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar app.jar

# The production profile and the library's business time zone. No secrets:
# the database and JWT settings are supplied when the container starts.
ENV SPRING_PROFILES_ACTIVE=prod \
    APP_TIME_ZONE=Asia/Kolkata \
    TZ=Asia/Kolkata

USER app
EXPOSE 8080

# -Duser.timezone pins the JVM's zone. The production profile refuses to start
# if it differs from APP_TIME_ZONE.
ENTRYPOINT ["java", "-Duser.timezone=Asia/Kolkata", "-jar", "/app/app.jar"]
