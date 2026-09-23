# scripts/start.ps1 compiles the current local source tree before this runtime image is built.
# The runtime image never receives source files, CSV credentials, or local data.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 agent && useradd --uid 10001 --gid agent --no-create-home agent \
    && mkdir /app/uploads && chown agent:agent /app/uploads
COPY --chown=agent:agent target/super-biz-agent-1.0-SNAPSHOT.jar /app/app.jar
USER agent
EXPOSE 9900
ENTRYPOINT ["java", "-Dspring.devtools.restart.enabled=false", "-jar", "/app/app.jar"]
