/**
 * main.tsx — Vite / React 19 application entry point for the CardDemo SPA.
 *
 * This is the single bootstrap module of the walking-skeleton UI (AAP §0.6.1
 * Group 3). `ui/index.html` loads it as an ES module via
 * `<script type="module" src="/app/main.tsx">` and provides the mount node
 * `<div id="root"></div>`. The module's sole job is to create the React 19 root
 * and render the composition root `<App/>`; it is deliberately kept to just the
 * imports and one render call.
 *
 * Why it is minimal — every cross-cutting concern lives ONE level down in
 * `./App` (MUI ThemeProvider + CssBaseline, the permissive AuthProvider, the
 * BrowserRouter and the route table). Bootstrap therefore contains no providers,
 * no routing, no theme and no business logic. It also performs NO global
 * side-effect imports: the Axios correlation-ID interceptor is attached at
 * module load on the shared client instance (`app/api/correlationId.ts`) that
 * the feature screens import when they issue requests, so it must not be pulled
 * in here.
 *
 * Coordination / stack facts honoured exactly:
 *   • Mount node id is `root` (from `ui/index.html`).
 *   • React 19 client API — `createRoot` from `react-dom/client`, never the
 *     legacy `react-dom` `render`.
 *   • Automatic JSX runtime (`jsx: "react-jsx"`) — no `import React`.
 *   • No path aliases configured — `App` is imported as a relative sibling.
 *   • Strict TypeScript (`strictNullChecks`) — the possibly-null
 *     `getElementById('root')` result is asserted non-null (`!`), matching the
 *     standard Vite React-TS template (the element is guaranteed by index.html).
 *
 * `StrictMode` is enabled so that, in development only, React surfaces unsafe
 * lifecycle/effect patterns by double-invoking effects; it is a no-op in
 * production builds. The tracer's data hook (owned by the card-detail feature)
 * is idempotent, so the dev double-invoke is safe.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
