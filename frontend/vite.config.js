import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VIAJAR_')

  return {
    plugins: [react()],
    server: {
      host: '0.0.0.0',
      port: 5173,
      strictPort: true,
      watch: {
        usePolling: (process.env.VIAJAR_DOCKER_WATCH || env.VIAJAR_DOCKER_WATCH) === 'true'
      },
      proxy: {
        '/api': {
          target: process.env.VIAJAR_BACKEND_URL || env.VIAJAR_BACKEND_URL || 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  }
})