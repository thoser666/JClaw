# Stage 1: Bauen der Anwendung mit JDK 25
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace/app

# Maven Wrapper und POM kopieren für das Caching der Dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Ausführungsrechte für den Wrapper setzen und Dependencies herunterladen
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

# Quellcode kopieren und das JAR bauen (ohne Tests)
COPY src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimales Laufzeit-Image erstellen mit JRE 25
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Das gebaute JAR aus der ersten Stage kopieren (versionsagnostisch, damit
# Versionsbumps in pom.xml den Docker-Build nicht brechen)
COPY --from=build /workspace/app/target/jclaw-*.jar app.jar

# Port des Spring-Boot-Webservers freigeben
EXPOSE 8080

# Anwendung starten
ENTRYPOINT ["java", "-jar", "app.jar"]
