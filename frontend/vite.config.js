import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const proxy = { '/api': 'http://127.0.0.1:8092' }
export default defineConfig({
  plugins: [vue()],
  server: { port: 5175, strictPort: true, proxy },
  preview: { port: 5175, strictPort: true, proxy },
})
