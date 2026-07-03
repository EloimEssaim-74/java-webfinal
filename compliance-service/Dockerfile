FROM eclipse-temurin:17-jdk-alpine
LABEL maintainer="knowledge-platform"
VOLUME /tmp
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--Xms128m -Xmx256m -XX:+UseG1GC} -jar /app.jar"]
