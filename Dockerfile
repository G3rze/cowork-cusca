FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /app/target/*.jar app.jar
USER app
EXPOSE 8080
# Leave headroom for metaspace, thread stacks and direct buffers inside the container limit
# On OOM, write a heap dump to /tmp and log GC activity to stdout for post-mortem analysis
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/tmp", "-Xlog:gc*:stdout:time,uptime", "-jar", "app.jar"]
