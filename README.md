# Streamer Application

Spring Boot application for AFK Live streaming capabilities.

## Features
- Spring Web MVC for REST APIs
- Spring Security with OAuth2 Client integration
- Spring Data JPA with H2 embedded database
- Session management with JDBC-backed sessions
- FFmpeg integration for media processing

## Prerequisites
- Java 21 JDK
- Maven 3.6.3+
- FFmpeg 6.0+
- PostgreSQL (or compatible database for production)

## Installation

### 1. Install FFmpeg

**Ubuntu/Debian:**
```sh
sudo apt-get install ffmpeg
```

**macOS (Homebrew):**
```sh
brew install ffmpeg
```

**Windows (Chocolatey):**
```sh
choco install ffmpeg
```

### 2. Build and Run
```sh
mvn clean install
mvn spring-boot:run
```

## Configuration
Create `src/main/resources/application.properties`:
```properties
# Server configuration
server.port=8080

# H2 Database (development)
spring.datasource.url=jdbc:h2:mem:afklive
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Session management
spring.session.store-type=jdbc

# Security (configure OAuth2 client)
spring.security.oauth2.client.registration.[provider].client-id=
spring.security.oauth2.client.registration.[provider].client-secret=
```

## Usage
After starting the application:
- Access H2 console at: http://localhost:8080/h2-console
- API endpoints available at: http://localhost:8080/api/

```sh
# Verify FFmpeg integration
ffmpeg -version
```

## Development Notes
- The H2 database is configured for development purposes
- Switch to PostgreSQL in production by updating datasource properties
- Session data persists in database via spring-session-jdbc
- Security configuration requires OAuth2 provider setup

## Contributing
1. Fork the repository
2. Create feature branch: `git checkout -b feature/your-feature`
3. Commit changes: `git commit -m 'Add some feature'`
4. Push to branch: `git push origin feature/your-feature`
5. Submit a pull request

## License
[Specify license - Update in pom.xml]