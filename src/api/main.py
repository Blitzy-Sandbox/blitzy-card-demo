# ============================================================================
# Source: app/cbl/COMEN01C.cbl  (F-002 Main menu — 10-option dispatcher)
#         app/cbl/COADM01C.cbl  (F-003 Admin menu — 4-option admin dispatcher)
#         app/cbl/CO*.cbl       (All 18 online CICS programs, mounted as REST)
#         app/cpy/COCOM01Y.cpy  (COMMAREA communication block -> JWT payload)
# ============================================================================
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ============================================================================
"""FastAPI application entry point — CardDemo API on AWS ECS Fargate.

This module is the **ASGI app factory and composition root** for the
CardDemo REST/GraphQL API service. It is the Python equivalent of the
CICS region initialization that on the legacy mainframe was handled by
:

* ``app/cbl/COMEN01C.cbl`` — Main menu dispatcher (10 options: Account
  view/update, Card list/detail/update, Transaction list/detail/add,
  Bill payment, Report submission). In CICS the main menu was the
  primary user-facing transaction; here it is split into 18 REST
  endpoints and one GraphQL endpoint, each served by a dedicated
  router module under :mod:`src.api.routers`.
* ``app/cbl/COADM01C.cbl`` — Admin menu dispatcher (4 options: User
  CRUD, Admin dashboard). Admin routes are mounted under the
  ``/admin`` prefix and gated by the ``ADMIN_ONLY_PREFIXES`` check in
  :class:`src.api.middleware.auth.JWTAuthMiddleware`.

Architectural role
------------------
Per the Agent Action Plan (AAP §0.5.1), this module has four
responsibilities:

1. **Instantiate the FastAPI app** — configure title, description,
   version, OpenAPI metadata, and a ``lifespan`` context manager that
   initializes the Aurora PostgreSQL connection pool at startup
   (:func:`src.api.database.init_db`) and disposes of it at shutdown
   (:func:`src.api.database.close_db`). Replaces the CICS ``CEMT
   SET FILE(*) OPEN`` / ``CLOSE`` bootstrap sequence.

2. **Attach middleware** — in outermost-to-innermost order:

   * :class:`fastapi.middleware.cors.CORSMiddleware` (outermost, so
     preflight ``OPTIONS`` requests are resolved before any auth check).
   * :class:`src.api.middleware.auth.JWTAuthMiddleware` — authenticates
     every request whose path is not in
     :data:`src.api.middleware.auth.PUBLIC_PATHS`, replacing the CICS
     ``EIBTRNID`` / sign-on-state check traditionally performed at the
     top of every COBOL transaction.
   * :func:`src.api.middleware.error_handler.register_exception_handlers`
     — registers global handlers that translate Python exceptions into
     the COBOL-compatible ``ABEND-DATA`` JSON envelope (preserving the
     4-character error codes defined in ``app/cpy/CSMSG01Y.cpy``).

3. **Mount all 8 REST routers** — each from :mod:`src.api.routers`,
   with a tag and URL prefix that corresponds one-to-one to the legacy
   COBOL feature area. The mount matrix is:

   ============  ================================  ========================
   Prefix         Feature(s)                        Router module
   ============  ================================  ========================
   /auth         F-001 (Sign-on)                   :mod:`.routers.auth_router`
   /accounts     F-004 (View), F-005 (Update)      :mod:`.routers.account_router`
   /cards        F-006, F-007, F-008                :mod:`.routers.card_router`
   /transactions F-009, F-010, F-011                :mod:`.routers.transaction_router`
   /bills        F-012 (Bill payment)              :mod:`.routers.bill_router`
   /reports      F-022 (Report submission)          :mod:`.routers.report_router`
   /users        F-018, F-019, F-020, F-021         :mod:`.routers.user_router`
   /admin        F-003 (Admin menu)                 :mod:`.routers.admin_router`
   ============  ================================  ========================

   This produces exactly 18 REST endpoints (matching the 18 CICS
   transactions inventoried in AAP §0.2.3).

4. **Mount the GraphQL endpoint** — :mod:`src.api.graphql.schema`
   provides a single Strawberry schema stitching Query (read-side
   resolvers from :mod:`src.api.graphql.queries`) and Mutation
   (write-side resolvers from :mod:`src.api.graphql.mutations`). The
   schema is served at ``POST /graphql`` via
   :class:`strawberry.fastapi.GraphQLRouter` with a ``context_getter``
   that injects a transactional :class:`~sqlalchemy.ext.asyncio.AsyncSession`
   into every resolver's ``info.context["db"]``.

Additional endpoints provided by this module
--------------------------------------------
* ``GET /health`` — readiness/liveness probe used by the Dockerfile
  ``HEALTHCHECK`` directive and by ECS target-group health checks.
  Returns ``200 OK`` with a small JSON body including the service
  name and version so that operators can verify which image version
  is serving traffic. Does **not** touch the database so that
  readiness checks remain fast and do not cascade DB outages into
  container restart loops.
* ``GET /docs`` — Swagger UI, auto-generated by FastAPI from router
  docstrings and Pydantic schemas. Declared in :data:`PUBLIC_PATHS`
  so no authentication is required.
* ``GET /redoc`` — ReDoc UI, alternative OpenAPI renderer.
* ``GET /openapi.json`` — OpenAPI 3.1 JSON spec for machine consumers.

Security posture
----------------
* All routes except :data:`src.api.middleware.auth.PUBLIC_PATHS`
  require a valid JWT in the ``Authorization: Bearer <token>`` header.
* The ``/graphql`` endpoint is **not** public; resolvers receive
  authenticated requests only (the JWT subject is available via
  ``request.state.user_id`` if a resolver needs it).
* Routes under ``/admin/*`` and ``/users/*`` are additionally gated by
  :data:`src.api.middleware.auth.ADMIN_ONLY_PREFIXES` — non-admin
  users receive an ``ABEND-DATA`` 403 response (``FRBD`` code).
* ``CORSMiddleware`` is configured permissively here; production
  deployments should override ``allow_origins`` via environment
  configuration to restrict cross-origin callers.

Monitoring posture
------------------
* Every router and service emits structured JSON log records via the
  stdlib :mod:`logging` module, configured externally by the ECS task
  definition (see ``infra/ecs-task-definition.json``). Log records
  include ``extra={...}`` fields suitable for CloudWatch Logs
  Insights queries.
* The ``/health`` endpoint is polled by CloudWatch synthetic canaries
  and by the ALB target-group health-check.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (row for
  ``src/api/main.py``).
* AAP §0.7.2 — Security Requirements (JWT, BCrypt) and Monitoring
  Requirements (CloudWatch, structured logging).
* :mod:`src.api.database` — :func:`init_db`, :func:`close_db`,
  :func:`get_async_session`.
* :mod:`src.api.middleware.auth` — :class:`JWTAuthMiddleware`,
  :data:`PUBLIC_PATHS`, :data:`ADMIN_ONLY_PREFIXES`.
* :mod:`src.api.middleware.error_handler` — ABEND-DATA translator.
* :mod:`src.api.graphql.schema` — Strawberry schema.
* :mod:`src.api.routers` — 8 REST router modules.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Any

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.ext.asyncio import AsyncSession
from strawberry.fastapi import GraphQLRouter

from src.api.database import close_db, init_db
from src.api.dependencies import get_db
from src.api.graphql.schema import schema as graphql_schema
from src.api.middleware.auth import JWTAuthMiddleware
from src.api.middleware.error_handler import register_exception_handlers
from src.api.routers import (
    account_router,
    admin_router,
    auth_router,
    bill_router,
    card_router,
    report_router,
    transaction_router,
    user_router,
)
from src.shared.config import get_settings

# ----------------------------------------------------------------------------
# Module-level logger. The root logger is configured externally by the ECS
# task definition (LOG_LEVEL environment variable) so that log records are
# shipped to CloudWatch Logs with structured JSON formatting.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# Application lifespan — replaces CICS "CEMT SET FILE(*) OPEN" bootstrap.
#
# The ``lifespan`` context manager is FastAPI's modern alternative to the
# deprecated ``startup``/``shutdown`` event handlers. It runs exactly once
# per worker process:
#
#   * On entry (before the server begins accepting requests): initialize
#     the Aurora PostgreSQL connection pool. Any error here fails the
#     worker startup loudly, preventing the container from passing its
#     ECS health check and blocking a bad deployment from serving
#     traffic.
#
#   * On exit (after the server stops accepting requests): dispose of
#     the pool cleanly so that Aurora's server-side connection counters
#     decrement promptly rather than waiting for TCP RSTs to drain.
#
# This is the Python equivalent of the pair of CICS operations
# performed at region startup (`CEMT SET FILE(ACCTDAT) OPEN` ...) and
# region shutdown (`CEMT SET FILE(ACCTDAT) CLOSE`) on the legacy
# mainframe.
# ----------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Initialize and dispose database resources around the app's lifetime.

    Parameters
    ----------
    app
        The FastAPI application instance (unused here but required by
        the ``lifespan`` protocol).

    Yields
    ------
    None
        Control is yielded back to Uvicorn once startup is complete.
        Shutdown code runs after the yield returns.

    Raises
    ------
    Exception
        Any exception raised during :func:`init_db` propagates,
        failing worker startup. This is intentional -- a worker that
        cannot connect to Aurora PostgreSQL must not pass the ECS
        health check.
    """
    settings = get_settings()
    logger.info(
        "carddemo-api startup beginning",
        extra={
            "event": "api_startup_begin",
            "app_name": settings.APP_NAME,
            "app_version": settings.APP_VERSION,
        },
    )
    # --- Startup: initialize DB pool --------------------------------------
    await init_db()
    logger.info(
        "carddemo-api startup complete — ready to serve traffic",
        extra={
            "event": "api_startup_complete",
            "app_name": settings.APP_NAME,
            "app_version": settings.APP_VERSION,
        },
    )
    try:
        yield
    finally:
        # --- Shutdown: dispose DB pool ------------------------------------
        logger.info(
            "carddemo-api shutdown beginning",
            extra={"event": "api_shutdown_begin"},
        )
        await close_db()
        logger.info(
            "carddemo-api shutdown complete",
            extra={"event": "api_shutdown_complete"},
        )


# ----------------------------------------------------------------------------
# FastAPI application factory -- constructs the ASGI app at import time.
#
# NOTE: We intentionally construct the app at module import (rather than
# inside a factory function) because:
#
#   1. ``uvicorn src.api.main:app`` requires ``app`` to be importable by
#      path. Wrapping it in a factory would force every ASGI client
#      (uvicorn, Gunicorn, the ``TestClient``) to know the factory's
#      call signature.
#
#   2. ``strawberry.fastapi.GraphQLRouter`` and
#      ``app.include_router(...)`` bind routes eagerly, so deferring
#      construction would complicate testing (the TestClient needs a
#      pre-configured ``app`` to instantiate its starlette test
#      transport).
#
# We DELIBERATELY do NOT call ``get_settings()`` at import time. The
# :class:`Settings` constructor fails with
# :class:`pydantic.ValidationError` if required env vars
# (``DATABASE_URL``, ``DATABASE_URL_SYNC``, ``JWT_SECRET_KEY``) are
# absent. Deferring the call to request time (``/health``, ``/root``)
# and to startup (``lifespan``) keeps the module importable by tooling
# (mypy, pytest collection, ruff, IDE indexers) that has not configured
# runtime secrets. The FastAPI ``version`` field is set to a literal
# default matching ``Settings.APP_VERSION`` so that auto-docs continue
# to show the correct value in production where env vars are set.
# ----------------------------------------------------------------------------

# Default application version string. Matches the default of
# :attr:`src.shared.config.settings.Settings.APP_VERSION` (currently
# ``"1.0.0"``). In deployed environments this literal is overridden
# below at startup by copying ``get_settings().APP_VERSION`` onto
# ``app.version`` so that the ``/openapi.json`` doc reflects whatever
# env-var-driven version is in effect.
DEFAULT_APP_VERSION: str = "1.0.0"

app: FastAPI = FastAPI(
    title="CardDemo API",
    description=(
        "REST and GraphQL API surface for the CardDemo credit-card "
        "management system. Descendant of the legacy AWS CardDemo "
        "mainframe application (CICS/COBOL/VSAM), modernized to run "
        "on AWS ECS Fargate with Aurora PostgreSQL. Exposes 18 REST "
        "endpoints across 8 feature areas (sign-on, account, card, "
        "transaction, bill payment, report, user CRUD, admin) and a "
        "single GraphQL endpoint at /graphql."
    ),
    version=DEFAULT_APP_VERSION,
    lifespan=lifespan,
    # Auto-docs surfaces. These paths are in the PUBLIC_PATHS set on
    # :class:`JWTAuthMiddleware` so they do not require authentication.
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    # The default responses dict is fine; we do not need to override
    # openapi.json ahead of time because FastAPI constructs it lazily
    # from the routers' ``responses=...`` metadata.
)


# ----------------------------------------------------------------------------
# Middleware configuration.
#
# Starlette / FastAPI middleware semantics:
#
#   * ``app.add_middleware(M1); app.add_middleware(M2)`` — M2 is the
#     OUTERMOST middleware (requests hit M2 first, then M1, then the
#     route). This is the reverse of the "first added = first
#     executed" mental model used by some other frameworks.
#
#   * To put this in COBOL terms: middleware addition follows a
#     LIFO stack -- the last ``add_middleware`` is the first to
#     execute. See Starlette docs
#     (https://www.starlette.io/middleware/).
#
# Our policy:
#
#   1. ``JWTAuthMiddleware`` is added FIRST so it is innermost. This
#      means CORS preflights (OPTIONS requests with no Authorization
#      header) are resolved by CORSMiddleware before the JWT validator
#      has a chance to reject them as "missing token".
#
#   2. ``CORSMiddleware`` is added LAST so it is outermost.
#
# Order of request processing under this configuration:
#
#   Request -> CORSMiddleware -> JWTAuthMiddleware -> Routes
#
# Order of response processing (reverse):
#
#   Response <- JWTAuthMiddleware <- CORSMiddleware <- Routes
#
# Exception handlers registered via ``register_exception_handlers`` are
# attached to the FastAPI app directly and fire inside the routes
# layer -- after all middleware has run on the way in but before the
# response passes through middleware on the way out.
# ----------------------------------------------------------------------------

# 1. JWT authentication middleware (inner).
# Gates access to every non-public path; allows PUBLIC_PATHS (/health,
# /auth/login, /auth/logout, /, /docs, /redoc, /openapi.json, /static/*)
# through without an Authorization header. Rejects missing/invalid/
# expired/malformed tokens with a 401 ABEND-DATA JSON response.
app.add_middleware(JWTAuthMiddleware)

# 2. CORS middleware (outer).
#
# Permissive defaults are used here to match the previous mainframe
# model where the CICS region accepted any authenticated 3270 client.
# Production deployments should override ``allow_origins`` via an
# environment-driven config list once the target ALB domain is known.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["X-Request-ID"],
)

# ----------------------------------------------------------------------------
# Global exception handlers.
#
# ``register_exception_handlers`` attaches handlers for:
#
#   * ``fastapi.HTTPException`` / ``starlette.HTTPException`` -> 4xx
#     with ABEND-DATA envelope (uses ``NFND``, ``INVR``, ``FRBD``
#     4-char codes per ``app/cpy/CSMSG01Y.cpy``).
#   * ``fastapi.exceptions.RequestValidationError`` -> 422 with
#     ``VALD`` code (Pydantic validation failure).
#   * ``sqlalchemy.exc.SQLAlchemyError`` -> 500 with ``DBIO`` code,
#     internal SQL text and bind parameters sanitized away.
#   * Unhandled ``Exception`` -> 500 with ``ABND`` code (generic
#     COBOL ABEND), stack trace stripped from client body.
#
# This replaces the CICS ``HANDLE ABEND`` blocks that wrapped every
# COBOL transaction's PROCEDURE DIVISION.
# ----------------------------------------------------------------------------
register_exception_handlers(app)


# ----------------------------------------------------------------------------
# REST router mounting.
#
# Each router exposes a module-level ``router`` attribute (see
# :mod:`src.api.routers`) that is mounted at the appropriate URL prefix.
# The prefix/tag pairing mirrors the legacy COBOL feature areas.
# ----------------------------------------------------------------------------

# F-001 Sign-on / authentication
app.include_router(
    auth_router.router,
    prefix="/auth",
    tags=["Authentication"],
)

# F-004 Account view, F-005 Account update
app.include_router(
    account_router.router,
    prefix="/accounts",
    tags=["Accounts"],
)

# F-006 Card list, F-007 Card detail, F-008 Card update
app.include_router(
    card_router.router,
    prefix="/cards",
    tags=["Cards"],
)

# F-009 Transaction list, F-010 Transaction detail, F-011 Transaction add
app.include_router(
    transaction_router.router,
    prefix="/transactions",
    tags=["Transactions"],
)

# F-012 Bill payment
app.include_router(
    bill_router.router,
    prefix="/bills",
    tags=["Bills"],
)

# F-022 Report submission
app.include_router(
    report_router.router,
    prefix="/reports",
    tags=["Reports"],
)

# F-018 User list, F-019 User add, F-020 User update, F-021 User delete
app.include_router(
    user_router.router,
    prefix="/users",
    tags=["Users"],
)

# F-003 Admin menu
app.include_router(
    admin_router.router,
    prefix="/admin",
    tags=["Admin"],
)


# ----------------------------------------------------------------------------
# GraphQL endpoint mounting.
#
# The GraphQL schema is a single Strawberry schema
# (:data:`src.api.graphql.schema.schema`) that stitches together the
# 7 Query resolvers (account, card, cards, transaction, transactions,
# user, users) and the 4 Mutation resolvers (updateAccount, updateCard,
# addTransaction, payBill).
#
# The ``context_getter`` is an async FastAPI dependency callable that
# injects an :class:`AsyncSession` into every resolver's
# ``info.context["db"]`` so that resolvers can share the request-scoped
# transactional session with the REST routers.
#
# We intentionally do NOT include ``/graphql`` in the PUBLIC_PATHS of
# :class:`JWTAuthMiddleware`. This means every GraphQL request is
# authenticated before the schema sees it -- the legacy CICS model
# where every transaction checked the signed-on user ID is preserved.
# ----------------------------------------------------------------------------
async def get_graphql_context(
    db: AsyncSession = Depends(get_db),
) -> dict[str, Any]:
    """Build the per-request GraphQL context dictionary.

    Strawberry resolvers access request-scoped resources via
    ``info.context["db"]`` (see :func:`src.api.graphql.queries._get_session`
    and :func:`src.api.graphql.mutations._get_session`). By wrapping
    :func:`src.api.dependencies.get_db` we guarantee that:

    * Every resolver shares a transactional session for the duration
      of its request.
    * Commit-on-clean-exit and rollback-on-exception semantics are
      inherited from ``get_db`` -- matching the CICS ``SYNCPOINT`` /
      ``SYNCPOINT ROLLBACK`` semantics of the legacy online programs.

    Parameters
    ----------
    db
        A transactional :class:`AsyncSession` injected by FastAPI's
        :func:`~fastapi.Depends` machinery.

    Returns
    -------
    dict
        ``{"db": db}`` -- a dict that will be passed to every resolver
        as ``info.context``.
    """
    return {"db": db}


# The ``context_getter`` argument carries a narrower type in the
# Strawberry stubs (``Callable[..., Awaitable[None] | None] | None``)
# than the runtime actually accepts — Strawberry documents and supports
# context_getters that return a mapping which is then exposed to every
# resolver via ``info.context``. The mismatch is a known typing gap in
# ``strawberry-graphql``; see
# https://strawberry.rocks/docs/integrations/fastapi#context_getter
# for the officially supported usage. The targeted ignore is narrower
# than a blanket mypy disable and will surface any other unrelated
# type errors at this site.
graphql_app: GraphQLRouter = GraphQLRouter(
    graphql_schema,
    context_getter=get_graphql_context,  # type: ignore[arg-type]
)
app.include_router(
    graphql_app,
    prefix="/graphql",
    tags=["GraphQL"],
)


# ----------------------------------------------------------------------------
# Health probe.
#
# Endpoint: ``GET /health``
#
# Purpose
# -------
# Readiness/liveness probe for the ECS task and the ALB target group.
# Also consumed by the Dockerfile ``HEALTHCHECK`` directive.
#
# Design
# ------
# * Returns a small JSON body with service name, version, status, and
#   UTC timestamp. The timestamp lets operators confirm the endpoint
#   is actually serving requests vs. returning a stale cached value.
# * Does NOT touch the database. If a DB outage happens, the health
#   endpoint should still report 200 so that the container does not
#   enter a restart loop on transient DB issues. (Dedicated
#   "readiness" probes that check DB connectivity should live under a
#   separate ``/ready`` endpoint in a future iteration.)
# * No authentication required -- the path is in
#   :data:`src.api.middleware.auth.PUBLIC_PATHS`.
#
# This replaces the legacy mainframe pattern where operators ran
# ``CEMT INQ TASK`` from the console to confirm the region was
# healthy.
# ----------------------------------------------------------------------------
@app.get(
    "/health",
    tags=["Health"],
    summary="Liveness/readiness probe",
    description=(
        "Returns a 200 response as long as the ASGI process is "
        "serving requests. Consumed by the Dockerfile HEALTHCHECK, "
        "ECS target-group health check, and CloudWatch synthetic "
        "canary probes. No authentication required."
    ),
    responses={
        200: {
            "description": "Service is alive and serving traffic.",
            "content": {
                "application/json": {
                    "example": {
                        "status": "ok",
                        "service": "carddemo",
                        "version": "1.0.0",
                        "timestamp": "2025-01-01T00:00:00+00:00",
                    }
                }
            },
        },
    },
)
async def health() -> dict[str, str]:
    """Report service liveness.

    Returns
    -------
    dict
        Mapping with four keys:

        * ``status`` -- always ``"ok"`` when the endpoint is reached.
        * ``service`` -- the :attr:`Settings.APP_NAME` (defaults to
          ``"carddemo"``).
        * ``version`` -- the :attr:`Settings.APP_VERSION` (defaults
          to ``"1.0.0"``).
        * ``timestamp`` -- UTC ISO-8601 timestamp so that operators
          can confirm the reply is fresh.
    """
    settings = get_settings()
    return {
        "status": "ok",
        "service": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "timestamp": datetime.now(UTC).isoformat(),
    }


# ----------------------------------------------------------------------------
# Root endpoint ("/") -- a minimal landing page that confirms the
# service is reachable and points callers at the OpenAPI docs.
#
# Declared in :data:`PUBLIC_PATHS` so no authentication is required.
# Provided primarily for human-friendly browser visits to the bare
# hostname; programmatic callers should use ``/openapi.json`` or
# ``/health``.
# ----------------------------------------------------------------------------
@app.get(
    "/",
    tags=["Meta"],
    summary="API root",
    description=(
        "Human-friendly landing response. Returns basic service "
        "identification and pointers to the OpenAPI documentation."
    ),
    include_in_schema=False,
)
async def root() -> dict[str, str]:
    """Return a small JSON payload pointing callers at the docs."""
    settings = get_settings()
    return {
        "service": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "docs": "/docs",
        "openapi": "/openapi.json",
        "graphql": "/graphql",
        "health": "/health",
    }


# ----------------------------------------------------------------------------
# Explicit public API of this module.
#
# ``app`` is the only symbol that ASGI servers (uvicorn, Gunicorn) and
# the FastAPI TestClient need to import. ``lifespan``,
# ``get_graphql_context``, ``health``, and ``root`` are exported for
# testability but are not expected to be imported by normal callers.
# ----------------------------------------------------------------------------
__all__ = [
    "app",
    "get_graphql_context",
    "health",
    "lifespan",
    "root",
]
