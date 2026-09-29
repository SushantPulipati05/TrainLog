# ---- Build stage: compile the Kotlin server into one runnable "fat" jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

# ---- Run stage: a small image that only has the JRE plus the built jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar

# Railway sets its own PORT env var at runtime and routes traffic to it -
# App.kt already reads System.getenv("PORT"), so no code changes are needed
# when that port number is different from this default.
EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
