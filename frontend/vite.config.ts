import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  base: '/console/',
  plugins: [vue()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
  build: { outDir: '../src/main/resources/static/console', emptyOutDir: true },
});
