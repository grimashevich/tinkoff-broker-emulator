# Stage 1: Build the application
FROM gradle:8.5.0-jdk21 AS build
WORKDIR /home/gradle/src
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew build --no-daemon

# Stage 2: Create the final image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/*.jar app.jar
EXPOSE 8080 50051
ENTRYPOINT ["java", "-jar", "app.jar"]
