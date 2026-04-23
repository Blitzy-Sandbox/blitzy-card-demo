# ============================================================================
# CardDemo — API Service Container (FastAPI on AWS ECS Fargate)
# ----------------------------------------------------------------------------
# Replaces the CICS region that hosted the 18 online COBOL programs.
#
# Python 3.11-slim base image — aligned with AWS Glue 5.1 runtime so that
# shared code in src/shared/ is compatible with both the API container and
# the Glue batch jobs.
#
# Per AAP §0.4.4 "Python 3.11-slim, FastAPI + Uvicorn, port 80".
# ============================================================================

FROM python:3.11-slim AS runtime

# ----------------------------------------------------------------------------
# Runtime environment
# ----------------------------------------------------------------------------
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# ----------------------------------------------------------------------------
# System dependencies required by:
#   - psycopg2-binary (falls back to libpq at runtime)
#   - cryptography / bcrypt native backends (cffi)
#   - healthcheck convenience (curl)
# gcc is used only for compiling any optional native extensions then removed
# to keep the image lean.
# ----------------------------------------------------------------------------
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libpq5 \
        libpq-dev \
        gcc \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ----------------------------------------------------------------------------
# Install Python dependencies first to leverage Docker layer caching.
# Core shared + API layer dependencies are required for the ECS service.
# ----------------------------------------------------------------------------
COPY requirements.txt requirements-api.txt ./
RUN pip install --no-cache-dir --upgrade pip setuptools wheel \
    && pip install --no-cache-dir -r requirements.txt -r requirements-api.txt \
    && apt-get purge -y --auto-remove gcc libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# ----------------------------------------------------------------------------
# Copy project metadata and application source.
# Only the src/ tree is shipped — the legacy app/ (COBOL) subtree is
# explicitly excluded via .dockerignore.
# ----------------------------------------------------------------------------
COPY pyproject.toml ./
COPY src/ ./src/

# ----------------------------------------------------------------------------
# Create a non-root user for security best practices.
# UID/GID 1001 is the standard non-privileged user in container contexts and
# avoids collisions with host system users (typically in the 0-999 range).
# No home directory is created — the user's working directory is /app (owned
# by appuser via the chown below) and interactive login is disabled.
# ----------------------------------------------------------------------------
RUN groupadd --gid 1001 appgroup \
    && useradd --uid 1001 --gid appgroup --home-dir /app --no-create-home --shell /sbin/nologin appuser \
    && chown -R appuser:appgroup /app
USER appuser

# ----------------------------------------------------------------------------
# Network configuration — ECS Fargate will route ALB traffic to port 80
# ----------------------------------------------------------------------------
EXPOSE 80

# ----------------------------------------------------------------------------
# Healthcheck — FastAPI exposes a /health endpoint
# ----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:80/health || exit 1

# ----------------------------------------------------------------------------
# Launch the FastAPI application via Uvicorn (ASGI server)
#
# ``--no-server-header`` is REQUIRED for the :class:`SecurityHeadersMiddleware`
# to fully resolve QA Checkpoint 6 Issue #6 (MINOR — ``Server: uvicorn``
# disclosure, CWE-200). Without this flag Uvicorn injects its own
# ``Server: uvicorn`` header at the ASGI protocol layer AFTER the Starlette
# middleware chain finishes, resulting in two conflicting ``Server``
# response headers on every outbound response. With the flag passed,
# Uvicorn suppresses its default header and only the opaque ``Server: API``
# value set by :class:`SecurityHeadersMiddleware.dispatch` reaches the
# wire.
# ----------------------------------------------------------------------------
CMD ["uvicorn", "src.api.main:app", "--host", "0.0.0.0", "--port", "80", "--no-server-header"]
