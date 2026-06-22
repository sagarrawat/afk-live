import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '^/$': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        autoRewrite: true,
        secure: false,
      },
      '^/(home\\.html|pricing|features|terms|privacy|admin|refund-policy|disclaimer).*': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        autoRewrite: true,
        secure: false,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
      '/login': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        autoRewrite: true,
        secure: false,
      },
      '/oauth2': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        autoRewrite: true,
        secure: false,
      },
      '/logout': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        autoRewrite: true,
        secure: false,
      },
      '/register': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        autoRewrite: true,
        secure: false,
      }
    }
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
  }
})
