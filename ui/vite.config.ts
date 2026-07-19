/**
 * Vite 8 build & dev-server configuration for the CardDemo walking-skeleton UI.
 *
 * The UI is a React 19 + TypeScript single-page application (styled with MUI v9
 * core + Emotion) that binds ONLY to the BFF (backend-for-frontend). In the
 * browser every API call is same-origin ("/api/..."); that prefix is forwarded
 * to the BFF by nginx in the Docker/production path and by the dev proxy defined
 * below during local `npm run dev`.
 *
 * The config uses the function form of `defineConfig` so environment variables
 * can be read per-mode (development / production) via `loadEnv`.
 *
 * @see https://vite.dev/config/
 */
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // Load ALL environment variables for the active mode (empty prefix ''), not
  // just VITE_-prefixed ones. VITE_BFF_PROXY_TARGET is a dev-only, server-side
  // value used to configure the proxy below; it is intentionally NOT a client
  // -inlined value. Only VITE_-prefixed vars are exposed to client code via
  // `import.meta.env`.
  const env = loadEnv(mode, process.cwd(), '');

  // Where `npm run dev` proxies "/api" during local development. Defaults to the
  // BFF host port (root .env.example sets BFF_PORT=8080; documented in
  // ui/.env.example as VITE_BFF_PROXY_TARGET). This proxy is used ONLY by the
  // Vite dev server — in the compose/nginx production path nginx does the
  // proxying instead.
  const bffProxyTarget = env.VITE_BFF_PROXY_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      // Aligns with the UI host-port convention (UI_PORT=3000). Only affects
      // `vite dev`; the production image is served by nginx on port 80.
      port: 3000,
      proxy: {
        '/api': {
          target: bffProxyTarget,
          changeOrigin: true,
          // NOTE: no rewrite — the BFF serves paths UNDER /api (e.g.
          // /api/cards/{cardNumber}, /api/auth/login, /api/menu), so the /api
          // prefix MUST be preserved end-to-end. Stripping it would break the
          // tracer slice.
        },
      },
    },
    build: {
      // The multi-stage ui/Dockerfile copies /app/dist into the nginx image.
      outDir: 'dist',
      sourcemap: true,
    },
  };
});
