# JPassbolt API

Java implementation of the Passbolt API using Spring Boot.

> **关于本仓库中的 GPG 私钥 / About the committed GPG private keys**
>
> `src/main/resources/gpg/` 下的 `.asc` 密钥是**有意公开**的 dev/test fixture，
> passphrase 亦公开，不保护任何真实数据 —— 播种它们的 `DataInitializer` 带
> `@Profile("local")`，只对 H2 内存库生效。密钥扫描服务对本目录的告警属预期内的
> 已知情况。完整清单、公开理由与生产环境注入方式见
> [`src/main/resources/gpg/README.md`](src/main/resources/gpg/README.md)。
>
> The `.asc` keys under `src/main/resources/gpg/` are **intentionally public**
> dev/test fixtures with public passphrases; they guard no real data. Secret
> scanner alerts on that directory are expected and known.

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
