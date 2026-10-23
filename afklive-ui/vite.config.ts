/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// Built into the Spring app's static resources; Spring serves /studio/** with app/index.html.
export default defineConfig({
  plugins: [react()],
  base: '/app/',
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: '../afklive-web/target/classes/static/app',
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    // `npm run dev` with the Spring app on :8080: API calls and sign-in go through to it.
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/login': 'http://localhost:8080',
      '/logout': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
