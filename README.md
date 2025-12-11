# User Management System

A Spring Boot application for user registration, login (JWT), role-based access, and basic admin functionality.

---

## Features

* Register a new user
* Login with email/password
* JWT-based authentication (stateless)
* Get current user `/api/users/me`
* Role management (Admin only)
* Admin stats endpoint
* Publishes user activity events (Register/Login)

---

## Tech Stack

* Java 17+
* Spring Boot (Web, Security, Data JPA, Validation)
* H2 (default) / MySQL (profile-based)
* RabbitMQ (event publishing)
* Maven (mvnw included)
* Swagger UI

---

## Run with Docker Compose (recommended)

This repo includes a `docker-compose.yml` so you can start the application and its dependent services together (app, MySQL, RabbitMQ).

**Prerequisites:**

* Docker (Desktop or Engine)
* Docker Compose (if using Docker CLI that requires separate `docker-compose`) — Docker Desktop includes this.

**Start everything:**

From the project root run (Linux/macOS):

```bash
# Build images and start services in detached mode
docker compose -f compose.yaml up --build -d
```

On Windows (PowerShell):

```powershell
docker compose -f compose.yaml up --build -d
```

**Stop and remove:**

```bash
docker compose -f docker-compose.yml down
```

**Notes:**

* The compose file will build the application image and start the database and message broker.
* If you change code, re-run the `docker compose ... up --build` command or rebuild the specific service.

---

## Services & Ports (default)

* Application: `http://localhost:8080` (Swagger: `/swagger-ui.html`, H2 Console: `/h2-console` if using H2)
* MySQL: `3306` (only if MySQL profile is enabled)
* RabbitMQ: `5672` (AMQP), `15672` (management UI)

(Exact ports are defined in `docker-compose.yml` and application properties.)

---

## Run locally (without Docker)

If you prefer to run just the app locally and connect to external services manually:

1. Use the included Maven wrapper so you don't need a system-wide Maven install:

```bash
# Linux/macOS
./mvnw spring-boot:run

# Windows PowerShell
./mvnw spring-boot:run
```

2. To run the `mysql` profile (connect to a MySQL instance):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

3. If you want to build the jar locally:

```bash
./mvnw clean package -DskipTests
java -jar target/*.jar
```

---

## Profiles

### Default (H2)

No setup required. H2 runs in-memory by default.

Run:

```bash
./mvnw spring-boot:run
```

H2 Console: `http://localhost:8080/h2-console`

### MySQL

Update `application-mysql.properties` with your DB credentials, or use the `compose.yaml` MySQL service.

Run with the MySQL profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

---

## Swagger Docs

`http://localhost:8080/swagger-ui.html`

---

## Endpoints

### Public

* `POST /api/users/register`
* `POST /api/users/login`

### Authenticated

* `GET /api/users/me`

### Admin Credential
*  `Default Admin User - admin@example.com`
*  `password-Admin@123`

### Admin API's
* `POST /api/roles`
* `POST /api/users/{id}/roles`
* `GET /api/admin/stats`

---

## Event Publishing

Event sent to RabbitMQ on:

* User Register
* User Login

Exchange: `user.events.exchange`
Queue: `user.events.queue`

---

## Run RabbitMQ / MySQL locally (Optional)

If you don't want to use `docker compose`, you can run RabbitMQ and MySQL locally or in separate containers. Using `docker compose` is the simplest approach to bring all services up correctly.

**Start only specific services** (example):

```bash
# Start only RabbitMQ and MySQL
docker compose -f compose.yaml up -d rabbitmq mysql

# Start only the application (if images already built)
docker compose -f compose.yaml up -d app
```

Replace service names with those defined in `compose.yaml` if different.

---



