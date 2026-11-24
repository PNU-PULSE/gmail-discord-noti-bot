FROM node:22-bookworm-slim AS frontend
WORKDIR /app/admin-ui
COPY admin-ui/package.json admin-ui/package-lock.json ./
RUN npm ci
COPY admin-ui/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ ./gradle/
COPY src/ ./src/
COPY --from=frontend /app/admin-ui/dist/ ./admin-ui/dist/
RUN ./gradlew --no-daemon bootJar -PadminUi

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/gmail-noti-discord-bot.jar ./app.jar
ENV BOT_MODE=admin
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
