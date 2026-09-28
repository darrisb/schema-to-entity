# Schema to Entity Application

This project contains a complete schema-to-entity transformation system with UI, API, and database components.

## Project Structure

- `ui/` - Angular frontend application
- `api/` - Reusable schema discovery Spring Boot starter (see [`api/README.md`](api/README.md))
- `demo/` - Runnable host application used by Compose that consumes the starter
- `docker-compose.yml` - Docker orchestration file
- `Dockerfile` - Multi-stage Docker build configuration (builds `api`, then `demo`)

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
   - Schema API: http://localhost:8081/api/schema

### Services

- **Database**: PostgreSQL (host port 5433)
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
- API: Available at port 8081
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
