FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle clean bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=default
ENV SPRING_DATASOURCE_URL=jdbc:h2:mem:paymentdb
ENTRYPOINT ["java", "-jar", "app.jar"]
