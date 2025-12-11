# ---------- Builder stage ----------
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app


COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw


RUN mvn -B dependency:go-offline


COPY src ./src
RUN mvn -B clean package -DskipTests


FROM eclipse-temurin:17-jre
WORKDIR /app


COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
