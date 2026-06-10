import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import cesium from 'vite-plugin-cesium'; // 이 플러그인을 쓰면 설정이 제일 쉽습니다.
import tailwindcss from '@tailwindcss/vite';
import * as path from "node:path"; // v4 플러그인
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig({
  plugins: [react(), cesium(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"), // shadcn을 위한 경로 별칭
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: ['contextdev.duckdns.org'],
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws/monitor': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/binance-price': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/upbit-price': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/candle/5m': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/candle/1m': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/candle/15m': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  },
  // NOTE: `vite preview`는 기본적으로 dev 프록시가 적용되지 않아서,
  //       로컬/서버에서 preview 포트(예: 5174)로 띄울 때 /api 가 404가 될 수 있음.
  //       preview 환경에서도 동일하게 백엔드로 프록시되도록 설정한다.
  preview: {
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws/monitor': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/binance-price': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/upbit-price': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/candle/5m': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/candle/1m': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      },
      '/ws/candle/15m': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  },
});
