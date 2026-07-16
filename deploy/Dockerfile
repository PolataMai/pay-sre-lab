FROM maven:3.9.16-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY . .

ARG SERVICE_MODULE=""
ARG JAR_FILE=app.jar
RUN if [ -n "$SERVICE_MODULE" ]; then \
        mvn -q -pl "$SERVICE_MODULE" -am package -DskipTests; \
    fi && \
    test -f "$JAR_FILE" && \
    cp "$JAR_FILE" /tmp/app.jar

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 10001 -S paysre && \
    adduser -u 10001 -S paysre -G paysre
WORKDIR /app
COPY --from=build --chown=paysre:paysre /tmp/app.jar /app/app.jar

USER paysre
EXPOSE 8080 8081 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
