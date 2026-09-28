# Schema to Entity Application

This project contains a complete schema-to-entity transformation system with UI, API, and database components.

## Project Structure

- `ui/` - Angular frontend application
- `backend/` - Node.js schema transformation service (not started by Compose)
- `api/` - Reusable schema discovery Spring Boot starter (see [`api/README.md`](api/README.md))
- `spring-example/` - Runnable Spring Boot dynamic-entity example used by Compose
- `docker-compose.yml` - Docker orchestration file
- `Dockerfile` - Multi-stage Docker build configuration

## Running the Application with Docker

### Prerequisites

- Docker Desktop installed
- Docker Compose installed

### Running the Application

1. Build and start all containers:
```bash
docker-compose up --build
```

2. Access the application:
   - UI: http://localhost:8080
   - Schema API: http://localhost:3000/api/schema/sample
   - Spring Boot API: http://localhost:8081/api/entities

### Services

- **Database**: PostgreSQL (host port 5433)
- **Schema API**: Node.js (port 3000)
- **Spring API**: Spring Boot (host port 8081, container port 8080)
- **UI**: Angular frontend (port 8080)

### Environment Variables

The Spring Boot container uses the standard datasource environment variables:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

### Database Connection

The application connects to a PostgreSQL database with the following default credentials:
- Database: dynamic_meta_db
- User: dynamic_meta_user
- Password: dynamic_meta_password

### Health Checks

The application includes health checks:
- Database: Available at port 5432
- API: Available at port 3000
- UI: Available at port 8080

### Cleanup

To stop and remove all containers:
```bash
docker-compose down
```

To stop and remove all containers and volumes:
```bash
docker-compose down -v
```
