import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// PaySRE console — local Vite config. The runtime reads the control
// plane URL from VITE_CONTROL_PLANE_URL (default http://localhost:8082)
// so the same build runs in the docker compose stack or in dev.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_CONTROL_PLANE_URL ?? 'http://localhost:8082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  preview: {
    host: '0.0.0.0',
    port: 5173,
  },
});