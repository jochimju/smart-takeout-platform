# Smart Takeout Platform

> A full-stack campus dining and takeout platform built with Spring Boot. It supports customer ordering and an administrator workspace, with Redis caching, RabbitMQ asynchronous order processing, flash-sale ordering, coupons, and real-time order notifications.

## Highlights

- **Core dining workflow** — user login, shopping cart, address book, menu and set-meal browsing, order creation, payment callbacks, order lifecycle management, and merchant-side operations.
- **Redis for performance** — cache-aside strategy for menu data, cache warm-up on application startup, and mutex protection for hot-key cache rebuilds.
- **High-concurrency flash sales** — a Lua script atomically validates stock and one-user-one-order constraints; RabbitMQ then processes accepted orders asynchronously.
- **Reliable order processing** — delayed queues cancel unpaid orders after the timeout window; dead-letter handling and retry records improve recoverability.
- **Marketing and observability** — coupon claiming/usage, Flyway database migrations, WebSocket order notifications, and administrative reporting.

## Architecture

```text
sky-take-out-jjs
├── sky-common   # Shared constants, utilities, exceptions, and configuration properties
├── sky-pojo     # Entities, DTOs, and view objects
└── sky-server   # Spring Boot application, REST APIs, persistence, messaging, and resources
```

## Tech Stack

| Area | Technologies |
| --- | --- |
| Backend | Java 8, Spring Boot 2.7, Spring MVC, Spring Cache |
| Persistence | MySQL, MyBatis-Plus, Druid, Flyway |
| Middleware | Redis, RabbitMQ |
| Security & API | JWT, Knife4j / Swagger |
| Integrations | WeChat Pay, Alibaba Cloud OSS, WebSocket |

## Getting Started

### Prerequisites

- JDK 8+
- Maven 3.6+
- MySQL 8+
- Redis 6+
- RabbitMQ 3+

### 1. Configure local services

Copy the example configuration and supply local credentials:

```bash
cp sky-server/src/main/resources/application-dev.example.yml sky-server/src/main/resources/application-dev.yml
```

`application-dev.yml` is deliberately ignored by Git. Do not commit credentials, certificates, or private keys.

Create a MySQL database matching the `sky.datasource.database` value. Flyway applies the schema migration at startup.

### 2. Run the application

```bash
mvn clean package -DskipTests
mvn -pl sky-server -am spring-boot:run
```

The service starts on `http://localhost:8080` by default. API documentation is available through the configured Knife4j endpoint after startup.

## Key Design Notes

| Scenario | Implementation |
| --- | --- |
| Menu reads | Cache-aside with expiration, warm-up, and mutex protection for cache rebuilds |
| Flash-sale set meals | Redis + Lua atomically checks inventory and duplicate orders, then publishes a message for asynchronous persistence |
| Unpaid orders | RabbitMQ TTL and dead-letter queues trigger timeout cancellation and rollback processing |
| Coupon claims | Database conditional updates prevent issuing more coupons than available stock |
| Order notifications | WebSocket pushes order status events to the management client |

## Documentation

- [FAISS gateway integration](docs/faiss-gateway.md)
- [Production-readiness notes](docs/production-readiness.md)

## Repository Hygiene

This repository intentionally excludes local IDE settings, build output, runtime files, private job-search materials, environment configuration, and certificates. Use `application-dev.example.yml` as the starting point for local configuration.

## License

This project is provided for learning and portfolio demonstration. Ensure that any third-party service credentials, assets, or dependencies you use comply with their respective licenses and terms.
