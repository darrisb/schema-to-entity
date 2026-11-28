#!/bin/bash

# Build and run the schema-to-entity application
echo "Building and running schema-to-entity application..."

# Build and start all containers (the API image builds console/ itself)
docker-compose up --build

echo "Application is running!"
echo "Access the console at: http://localhost:8081/console/"
echo "Access the API at: http://localhost:8081"