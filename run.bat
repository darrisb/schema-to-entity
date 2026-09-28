#!/bin/bash

# Build and run the schema-to-entity application
echo "Building and running schema-to-entity application..."

# Make sure the UI is built first
cd ui
npm install
npm run build
cd ..

# Build and start all containers
docker-compose up --build

echo "Application is running!"
echo "Access the UI at: http://localhost:8080"
echo "Access the API at: http://localhost:3000"