import { defineConfig } from "vite";

// The API runs in Docker on host port 8081 (see docker-compose.yml). The dev
// server proxies /api here so the console needs no CORS handling; override
// with CONSOLE_API_URL to point at another instance.
const apiTarget = process.env.CONSOLE_API_URL ?? "http://localhost:8081";

export default defineConfig({
  base: "/console/",
  server: {
    port: 5174,
    proxy: {
      "/api": {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
});
