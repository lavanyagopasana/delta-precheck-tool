# Multi-stage build: compile with Maven+JDK, run on a slim JRE only (smaller image, no build
# toolchain shipped to production).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies cached in their own layer -- only re-resolved when pom.xml actually changes, not on
# every source edit.
COPY pom.xml .
RUN mvn -q -o dependency:go-offline || mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /build/target/delta-migration-tracker.jar app.jar

# Evidence uploads MUST land on a mounted volume -- see docker-compose.yml's backend-uploads
# volume and APP_UPLOAD_DIR below. Anything written to the container's own filesystem instead is
# lost the moment this container is recreated (redeploy, restart, image update).
ENV APP_UPLOAD_DIR=/data/uploads
RUN mkdir -p /data/uploads

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
