FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy the root pom and web module pom
COPY pom.xml .
COPY afklive-web/pom.xml afklive-web/

# Change Java version from 25 to 21 to match the builder image
RUN sed -i 's/<java.version>25<\/java.version>/<java.version>21<\/java.version>/g' afklive-web/pom.xml

# Download dependencies (this layer is cached)
RUN mvn dependency:go-offline -pl afklive-web -am || true

# Copy source code
COPY afklive-web/src afklive-web/src

# Build the executable JAR
RUN mvn clean package -pl afklive-web -am -DskipTests

# -------------------------
# Run Stage
# -------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install FFmpeg because VideoConversionService uses local FFmpeg process for optimizations
RUN apt-get update && \
    apt-get install -y ffmpeg && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/afklive-web/target/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
