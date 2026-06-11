# JPassbolt API

Java implementation of the Passbolt API using Spring Boot.

## Prerequisites

- Java 17+
- Docker & Docker Compose
- Maven

## Setup

1. **Start Database**
   ```bash
   docker-compose up -d
   ```

2. **Build Application**
   ```bash
   mvn clean install
   ```

3. **Run Application**
   ```bash
   mvn spring-boot:run
   ```

## API Endpoints

- Health Check: `GET /api/health-check`

## Project Structure

- `src/main/java`: Source code
- `passbolt_api_ref`: Reference PHP implementation (cloned)
