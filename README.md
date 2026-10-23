# Streamer Application

Spring Boot application for AFK Live streaming capabilities.

## Features

- Spring Web MVC for REST APIs
- Spring Security with OAuth2 Client integration
- Spring Data JPA with H2 embedded database
- Session management with JDBC-backed sessions
- FFmpeg integration for media processing

## Prerequisites

- Java 21 JDK, Maven 3.9+
- FFmpeg 6.0+ on PATH
- PostgreSQL 16 (or Docker, to run `docker-compose.dev.yml`)

## Run locally

```sh
docker compose -f docker-compose.dev.yml up -d
SPRING_PROFILES_ACTIVE=dev mvn -pl afklive-web spring-boot:run
```

Open http://localhost:8080 and sign in with `dev@afklive.local` / `devpassword` (dev profile only).
No Google, payment, AI or cloud-storage secrets are needed for local development.

## Test

```sh
mvn -B verify
```

Integration tests use an embedded PostgreSQL, so no database or Docker is needed to run them.

See `CLAUDE.md` for project rules and `docs/product-spec.md` for the product spec.

## Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/your-feature`
3. Commit changes: `git commit -m 'Add some feature'`
4. Push to branch: `git push origin feature/your-feature`
5. Submit a pull request

## License

[Specify license - Update in pom.xml]