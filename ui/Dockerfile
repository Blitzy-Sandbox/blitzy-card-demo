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
#   * A package-lock.json IS committed and `npm ci` is used (finding M13) so the
#     dependency graph is installed exactly as locked; npm itself is pinned to
#     the version in package.json "packageManager".
#   * Base images are pinned by immutable digest (finding m31) so rebuilds are
#     byte-for-byte reproducible and cannot drift when a tag is re-published.
#   * nginx runs UNPRIVILEGED (USER nginx) and still serves on container port 80
#     to match the documented docker-compose ${UI_PORT}:80 mapping (m31).
# =============================================================================

# ---------- Stage 1: build the SPA ----------
# node:22-alpine pinned by digest (finding m31).
FROM node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2 AS build
WORKDIR /app

# Install dependencies first for better layer caching. Copying the manifest AND
# the committed lockfile means this layer is reused whenever they are unchanged.
# Pin npm to the "packageManager" version, then use `npm ci` (finding M13): it
# installs strictly from package-lock.json and fails if the lock and manifest
# have drifted, guaranteeing a reproducible dependency graph.
COPY package.json package-lock.json ./
RUN npm install -g npm@11.18.0 && npm ci

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
# nginx:alpine pinned by digest (finding m31).
FROM nginx:alpine@sha256:4a73073bd557c65b759505da037898b61f1be6cbcc3c2c3aeac22d2a470c1752 AS serve

# Harden the runtime to run UNPRIVILEGED (finding m31). The stock image starts
# its master as root solely to bind privileged port 80; instead we grant the
# nginx binary the single capability required to bind a low port
# (CAP_NET_BIND_SERVICE) and make the paths nginx writes to owned by the
# image's built-in non-root `nginx` user (uid 101). Container port stays 80 to
# match the documented docker-compose ${UI_PORT}:80 mapping, so nothing
# downstream needs to change.
#
# libcap is REQUIRED for the `setcap` below, so it cannot be removed; instead it
# is pinned to an EXACT version (Hadolint DL3018 / finding P7-DOCKER-HADOLINT).
# 2.78-r0 is the version published in the pinned nginx:alpine 3.24 base image's
# apk repository, so this install is reproducible and cannot silently drift.
RUN apk add --no-cache libcap=2.78-r0 \
 && setcap 'cap_net_bind_service=+ep' /usr/sbin/nginx \
 && chown -R nginx:nginx /var/cache/nginx /var/log/nginx \
 && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

# SPA + same-origin /api reverse-proxy config (proxies /api to the `bff`
# service). Installed as the default server block; the stock nginx.conf
# `http {}` block includes /etc/nginx/conf.d/*.conf.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Shared security response headers, `include`d by nginx.conf at the server scope
# and inside the cache/log-tuned locations (findings M06 + P7-PERF/P7-LOG-PAN).
# Lives OUTSIDE /etc/nginx/conf.d so the stock `include conf.d/*.conf` does not
# load it as a standalone server block — it is pulled in only via explicit
# `include /etc/nginx/snippets/security-headers.conf;`.
COPY nginx-security-headers.conf /etc/nginx/snippets/security-headers.conf

# Static assets built in stage 1. The nginx.conf `root` is /usr/share/nginx/html.
COPY --from=build /app/dist /usr/share/nginx/html

# Valid static robots.txt (finding P5-SEO-01). Served from the document root by
# the `location = /robots.txt` block so GET /robots.txt returns real robots text
# instead of falling through to the SPA index.html. Ownership is normalized by
# the chown below.
COPY robots.txt /usr/share/nginx/html/robots.txt

# Defense-in-depth for finding M05: even though production source maps are
# disabled in vite.config.ts, strip any stray *.map so none can ever be served
# from the public document root. Then hand ownership of the served tree to the
# unprivileged nginx user (the COPY above lands as root).
RUN find /usr/share/nginx/html -name '*.map' -type f -delete \
 && chown -R nginx:nginx /usr/share/nginx/html

# Drop root: the master and worker processes both run as the non-root nginx user.
USER nginx

# Container-internal port; docker-compose maps ${UI_PORT}:80.
EXPOSE 80

# Run nginx in the foreground so it is the container's PID 1 (no daemonization).
CMD ["nginx", "-g", "daemon off;"]
