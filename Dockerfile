FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/

RUN ./mvnw -B -Dmaven.test.skip=true package


FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

COPY --from=build --chown=app:app \
    /workspace/target/activation-api-0.0.1-SNAPSHOT.jar \
    app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]