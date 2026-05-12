FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
RUN apk add --no-cache maven

COPY pom.xml ./
COPY eakp-common/pom.xml eakp-common/
COPY api-gateway/pom.xml api-gateway/
COPY chat-service/pom.xml chat-service/
COPY ingestion-service/pom.xml ingestion-service/
COPY admin-service/pom.xml admin-service/
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -pl admin-service -am -B 2>/dev/null || true

COPY eakp-common/src eakp-common/src
COPY admin-service/src admin-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -pl admin-service -am -DskipTests -B && \
    mv admin-service/target/*.jar app.jar && \
    java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S eakp && adduser -S eakp -G eakp
COPY --from=builder /build/dependencies/          ./
COPY --from=builder /build/spring-boot-loader/    ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/           ./
USER eakp
EXPOSE 8083
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xms96m", "-Xmx224m", "-XX:MaxMetaspaceSize=192m", "-XX:ReservedCodeCacheSize=48m", "-XX:TieredStopAtLevel=1", "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom",\
    "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}",\
    "org.springframework.boot.loader.launch.JarLauncher"]
