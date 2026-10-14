# Runtime image only: the jar is built on the host with `mvn package` (see build.sh).
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/gg-api.jar app.jar
EXPOSE 8080
# UTC matches the GridGain nodes and the CDC reader (timestamps are stored as UTC wall-clock values).
# The --add-opens flags are what the GridGain client needs on JDK 17.
ENV JAVA_OPTS="-Duser.timezone=UTC \
 --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
 --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
 --add-opens=java.base/java.nio=ALL-UNNAMED \
 --add-opens=java.base/java.util=ALL-UNNAMED \
 --add-opens=java.base/java.lang=ALL-UNNAMED \
 --add-opens=java.base/java.io=ALL-UNNAMED \
 --add-opens=java.base/java.net=ALL-UNNAMED \
 -Djdk.attach.allowAttachSelf=true"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
