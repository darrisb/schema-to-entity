#!/bin/bash

# Build and run the schema-to-entity application
echo "Building and running schema-to-entity application..."

# Build and start all containers
docker-compose up --build

echo "Application is running!"
echo "Access the UI at: http://localhost:8080"
echo "Access the API at: http://localhost:3000"