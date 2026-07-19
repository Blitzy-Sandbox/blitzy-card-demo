# syntax=docker/dockerfile:1

# =============================================================================
# CardDemo Walking Skeleton — UI production image (multi-stage)
# -----------------------------------------------------------------------------
# Stage 1 (build):  compile the React 19 + TypeScript SPA with Node 22 + Vite.
# Stage 2 (serve):  serve the static Vite `dist/` output with nginx:alpine.
#
# Referenced by docker-compose.yml as service `ui`:
#   build:
#     context: ./ui
#     dockerfile: Dockerfile
#
# CRITICAL coordination facts baked into this file:
#   * Build context is `./ui` (NOT the repository root). Consequently the
#     frozen OpenAPI contracts under /contracts are NOT in this build context,
#     and the Node build image has no Java/JRE. Therefore the build MUST NOT run
#     OpenAPI codegen. `npm run build` == `tsc --noEmit && vite build` only; the
#     `generate:api` script is never invoked here.
#   * The generated TypeScript client under app/api/generated is committed and
#     is copied in via `COPY app ./app`; the type-check consumes it directly.
#   * Explicit source COPYs are used (never `COPY . .`): there is no
#     .dockerignore in the /ui scope, so an explicit list deterministically
#     avoids copying a host node_modules/dist into the image and keeps the build
#     reproducible.
#   * No lockfile is committed (greenfield), so `npm install` is used rather
#     than `npm ci` (which requires a committed package-lock.json).
#   * nginx serves on container port 80; docker-compose maps ${UI_PORT}:80.
# =============================================================================

# ---------- Stage 1: build the SPA ----------
FROM node:22-alpine AS build
WORKDIR /app

# Install dependencies first for better layer caching. Copying only the
# manifest means this layer is reused whenever dependencies are unchanged.
# No lockfile is committed, so use `npm install` (not `npm ci`).
COPY package.json ./
RUN npm install

# Copy the build inputs explicitly (avoids copying a host node_modules/dist; no
# .dockerignore is in scope). These are the only sources the build needs:
#   - tsconfig.json / vite.config.ts : TypeScript + Vite build configuration
#   - index.html                     : Vite SPA entry document
#   - app/                           : shell, theme, layout, auth, api client
#                                      (including the committed app/api/generated)
#   - components/                    : shared MUI-composed components
#   - features/                      : feature screens (incl. the Card Detail tracer)
COPY tsconfig.json vite.config.ts index.html ./
COPY app ./app
COPY components ./components
COPY features ./features

# Type-check + bundle. NOTE: no OpenAPI codegen here — the generated client
# under app/api/generated is committed and was copied in above. This runs
# `tsc --noEmit && vite build`, emitting the static bundle to /app/dist.
RUN npm run build

# ---------- Stage 2: serve static files ----------
FROM nginx:alpine AS serve

# SPA + same-origin /api reverse-proxy config (proxies /api to the `bff`
# service). Installed as the default server block; the stock nginx.conf
# `http {}` block includes /etc/nginx/conf.d/*.conf.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Static assets built in stage 1. The nginx.conf `root` is /usr/share/nginx/html.
COPY --from=build /app/dist /usr/share/nginx/html

# Container-internal port; docker-compose maps ${UI_PORT}:80.
EXPOSE 80

# Run nginx in the foreground so it is the container's PID 1 (no daemonization).
CMD ["nginx", "-g", "daemon off;"]
