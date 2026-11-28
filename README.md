# Schema to Entity Application

This project contains a complete schema-to-entity transformation system with an admin
console, API, and database components.

## Project Structure

- `console/` - Admin console (plain TypeScript + Vite; built inside the API image and served at `/console/`)
- `api/` - Reusable schema discovery Spring Boot starter (see [`api/README.md`](api/README.md))
- `demo/` - Runnable host application used by Compose that consumes the starter
- `docker-compose.yml` - Docker orchestration file
- `Dockerfile` - Multi-stage Docker build configuration (builds `console`, then `api`, then `demo`)

## Console

The admin console (`console/`) is a framework-free TypeScript + Vite single-page
app with five screens — Relationships, Links, Labels, Data and Drift — built on
the vendored `@visual-ui/*` Web Components. It has no authentication yet, like
the rest of the admin API, and must not be exposed beyond the local machine.
See [`console/README.md`](console/README.md) for the screens, running, testing
and how to upgrade the vendored UI library.

### Running the console locally

The Vite dev server can run outside Docker:

```bash
npm --prefix console install   # first time only; see console/README.md
npm --prefix console run dev
```

It serves the console under its base path at http://localhost:5174/console/ (not the
root) and proxies `/api` requests to http://localhost:8081, so no CORS is needed.
Set `CONSOLE_API_URL` to point the proxy at another API instance.

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
   - Console: http://localhost:8081/console/ (served by the API itself, same origin as the API)
   - Schema API: http://localhost:8081/api/schema

### Services

- **Database**: PostgreSQL (host port 5433)
- **Spring API**: Spring Boot (host port 8081, container port 8080); serves the console at `/console/`

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
- API: Available at port 8081 (with the console under `/console/`)

### Cleanup

To stop and remove all containers:
```bash
docker-compose down
```

To stop and remove all containers and volumes:
```bash
docker-compose down -v
```
