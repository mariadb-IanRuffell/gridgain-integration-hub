# CDC reader as a long-running service. Build the jar and libs on the host first (see build.sh).
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/classes classes
COPY target/lib lib
COPY jvm.args jvm.args
# jvm.args carries -Duser.timezone=UTC (Sybase datetimes are treated as UTC) and the GridGain add-opens flags
ENTRYPOINT ["java", "@jvm.args", "-cp", "classes:lib/*", "cdc.Main"]
CMD ["run"]
