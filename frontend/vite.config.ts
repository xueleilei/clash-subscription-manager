import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { playwright } from '@vitest/browser-playwright'
export default defineConfig({
  plugins: [vue()],
  resolve: { dedupe: ['vue'] },
  optimizeDeps: { include: ['vue', 'vue-router', '@vue/test-utils', 'element-plus', 'codemirror', '@codemirror/lang-yaml'] },
  server: { proxy: { '/api': 'http://127.0.0.1:8080', '/sub': 'http://127.0.0.1:8080' } },
  test: { include: ['src/**/*.test.ts'], setupFiles: ['./src/test/setup.ts'], browser: { enabled: true, headless: true, provider: playwright({ launchOptions: { channel: process.env.PLAYWRIGHT_CHANNEL || undefined } }), instances: [{ browser: 'chromium' }] } },
})
