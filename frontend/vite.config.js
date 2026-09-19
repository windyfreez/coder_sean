import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发服务器将 /chat、/admin 代理到 Spring Boot 后端（默认 8080，可按需修改）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/chat': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
