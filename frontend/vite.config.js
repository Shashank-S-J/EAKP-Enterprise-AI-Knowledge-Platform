import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/auth': 'http://localhost:8080',
      '/api/v1/chat': 'http://localhost:8081',
      '/api/v1/documents': 'http://localhost:8082',
      '/api/v1/admin': 'http://localhost:8083',
      '/api/v1/analytics': 'http://localhost:8083',
      '/api/v1/workspaces': 'http://localhost:8083',
    }
  }
})
