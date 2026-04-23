# ============================================================================
# Source: app/cbl/COMEN01C.cbl  (F-002 Main menu — 10-option dispatcher)
#         app/cbl/COADM01C.cbl  (F-003 Admin menu — 4-option admin dispatcher)
#         app/cpy/COMEN02Y.cpy  (Main menu option table — 10 entries)
#         app/cpy/COADM02Y.cpy  (Admin menu option table — 4 entries)
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
CICS region initialization that on the legacy mainframe was handled by:

* ``app/cbl/COMEN01C.cbl`` — Main menu dispatcher (10 options defined in
  ``app/cpy/COMEN02Y.cpy``: Account view/update, Card list/detail/update,
  Transaction list/detail/add, Bill payment, Report submission). In CICS
  the main menu was the primary user-facing transaction (CM00) that
  performed ``EXEC CICS XCTL`` to dispatch to the target program. Here
  the menu is dissolved into 7 REST routers (some COBOL programs share a
  router) and one GraphQL endpoint.
* ``app/cbl/COADM01C.cbl`` — Admin menu dispatcher (4 options defined in
  ``app/cpy/COADM02Y.cpy``: User List, User Add, User Update, User
  Delete mapping to COUSR00C-COUSR03C). Admin transaction CA00 is
  replaced by the ``admin_router`` and ``user_router`` modules. The
  ``CDEMO-USRTYP-ADMIN`` 88-level check is enforced by
  :class:`src.api.middleware.auth.JWTAuthMiddleware`.

Architectural role
------------------
Per the Agent Action Plan (AAP §0.5.1), this module has four
responsibilities, implemented by the :func:`create_app` factory:

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
   provides a single Strawberry schema stitching Query (eight
   read-side resolvers defined in :mod:`src.api.graphql.queries`)
   and Mutation (four write-side resolvers defined in
   :mod:`src.api.graphql.mutations`). See the authoritative
   resolver enumeration and the GraphQL SDL in
   :mod:`src.api.graphql.schema`. The schema is served at
   ``POST /graphql`` via
   :class:`strawberry.fastapi.GraphQLRouter` with a ``context_getter``
   that injects a per-resolver async session factory
   (:func:`src.api.database.get_async_session`) into every resolver's
   ``info.context["db_factory"]`` — enabling safe concurrent execution
   of sibling resolvers in multi-field GraphQL queries.

Factory pattern
---------------
The module exports **both** a :func:`create_app` factory function and
a module-level :data:`app` constant (``app = create_app()``). The
former supports test isolation and per-process configuration injection,
while the latter preserves the canonical ASGI import path
``uvicorn src.api.main:app`` required by deployed ECS containers.

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
* ``CORSMiddleware`` reads its ``allow_origins`` list from
  :attr:`Settings.CORS_ALLOWED_ORIGINS` (env var
  ``CORS_ALLOWED_ORIGINS``), which defaults to a safe localhost-only
  list for local development. The prior wildcard (``["*"]``) default
  was invalid in combination with ``allow_credentials=True`` per the
  W3C CORS specification — browsers reject credentialed requests
  when origins is wildcard, which would render the JWT
  ``Authorization: Bearer`` header unusable from any SPA. Staging
  and production deployments must set ``CORS_ALLOWED_ORIGINS``
  explicitly to the actual ALB / CloudFront domain(s).

Monitoring posture
------------------
* Every router and service emits structured JSON log records via the
  stdlib :mod:`logging` module. The :class:`JsonLogFormatter` installed
  by :func:`_configure_json_logging` (invoked at ``lifespan`` startup
  and at ``__main__`` entry) uses :func:`json.dumps` to serialize each
  record as a single-line JSON document suitable for CloudWatch Logs
  Insights queries.
* The ``/health`` endpoint is polled by CloudWatch synthetic canaries
  and by the ALB target-group health-check.

Direct execution
----------------
Running ``python -m src.api.main`` (or ``python src/api/main.py``)
invokes the ``if __name__ == "__main__"`` block, which starts a local
Uvicorn server via :func:`uvicorn.run` for development workflows. In
production (ECS Fargate), the container ``CMD`` directive uses
``uvicorn src.api.main:app`` directly rather than this block.

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

import json
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Any

import uvicorn
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from strawberry.fastapi import GraphQLRouter

from src.api.database import close_db, get_async_session, init_db
from src.api.graphql.schema import schema as graphql_schema
from src.api.middleware.auth import JWTAuthMiddleware
from src.api.middleware.error_handler import (
    _sanitize_traceback,
    register_exception_handlers,
)
from src.api.middleware.security_headers import SecurityHeadersMiddleware
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
# Module-level logger. The root logger's handler set is installed (with a
# structured JSON formatter) by ``_configure_json_logging`` at ``lifespan``
# startup so that every log record produced by any module in the process
# is serialized as a single-line JSON document for CloudWatch ingestion.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# Default application version string. Matches the default of
# :attr:`src.shared.config.settings.Settings.APP_VERSION` (currently
# ``"1.0.0"``). The FastAPI ``version`` field is set to this literal at
# factory time; in deployed environments the ``/openapi.json`` doc reflects
# whatever env-var-driven ``APP_VERSION`` is in effect because operators
# override the env var at container build/run time rather than mutating the
# app object at startup.
#
# A literal is used rather than ``get_settings().APP_VERSION`` because
# :class:`~src.shared.config.settings.Settings` validates required env vars
# in its constructor (``DATABASE_URL``, ``DATABASE_URL_SYNC``,
# ``JWT_SECRET_KEY``) and would raise :class:`pydantic.ValidationError`
# during module import in environments where secrets have not yet been
# provisioned (e.g., tooling like mypy/ruff/pytest collection).
# ----------------------------------------------------------------------------
DEFAULT_APP_VERSION: str = "1.0.0"


# ----------------------------------------------------------------------------
# Structured JSON logging for AWS CloudWatch compatibility.
#
# The :class:`JsonLogFormatter` serializes each :class:`logging.LogRecord`
# as a single-line JSON document so that CloudWatch Logs Insights can parse
# the fields natively (via the JSON parser rather than regex grok patterns).
# The format matches the conventions documented in AAP §0.7.2 (Monitoring
# Requirements):
#
# * ``timestamp`` — UTC ISO-8601 timestamp of the log record.
# * ``level``     — e.g., ``"INFO"``, ``"ERROR"``.
# * ``logger``    — the :attr:`LogRecord.name` (typically the module path).
# * ``message``   — the fully rendered message (after ``%``-substitution).
# * Any ``extra={}`` keys supplied by the caller are merged in flat (e.g.,
#   ``logger.info("foo", extra={"event": "bar"})`` adds ``"event": "bar"``).
# * ``exception`` — formatted traceback when ``exc_info=True`` is used.
#
# This formatter is idempotent: calling :func:`_configure_json_logging`
# multiple times is a no-op after the first successful installation,
# which keeps test harnesses (FastAPI TestClient triggers ``lifespan``
# per test) from duplicating handlers and flooding stdout.
# ----------------------------------------------------------------------------
# Standard :class:`logging.LogRecord` attribute names that we should NOT
# promote to top-level JSON keys (they are framework-internal or already
# surfaced under a different name such as ``timestamp``/``level``/``message``).
_LOG_RECORD_STANDARD_ATTRS: frozenset[str] = frozenset(
    {
        "args",
        "asctime",
        "created",
        "exc_info",
        "exc_text",
        "filename",
        "funcName",
        "levelname",
        "levelno",
        "lineno",
        "module",
        "msecs",
        "message",
        "msg",
        "name",
        "pathname",
        "process",
        "processName",
        "relativeCreated",
        "stack_info",
        "thread",
        "threadName",
        "taskName",
    }
)


class JsonLogFormatter(logging.Formatter):
    """Format log records as single-line JSON for CloudWatch ingestion.

    The formatter produces one JSON object per record, with top-level
    keys ``timestamp``, ``level``, ``logger``, ``message``, and any
    ``extra={}`` fields supplied at the call site. Exception information
    (when ``logger.exception(...)`` or ``exc_info=True`` is used) is
    serialized under the ``exception`` key.

    CloudWatch Logs Insights parses JSON log lines natively, so the
    structured fields are queryable without regex extraction::

        fields @timestamp, level, event, message
        | filter level = "ERROR" and event = "db_write_failure"
        | sort @timestamp desc

    Notes
    -----
    * The ``default=str`` argument to :func:`json.dumps` ensures that
      non-JSON-serializable objects (e.g., :class:`datetime`,
      :class:`decimal.Decimal`) are coerced to their string
      representation rather than raising :class:`TypeError`.
    * The formatter never touches ``sys.stderr`` directly — it merely
      returns the formatted string; emission is the handler's job.
    """

    def format(self, record: logging.LogRecord) -> str:
        """Serialize a log record as a single-line JSON document.

        Parameters
        ----------
        record
            The :class:`logging.LogRecord` to serialize.

        Returns
        -------
        str
            A single-line JSON string (no trailing newline; the logging
            handler adds one via its terminator).
        """
        log_data: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        # Merge any ``extra={...}`` fields attached at the call site.
        # These arrive on the record as attributes (Python's logging
        # module's design), so we filter out the standard attributes
        # and promote the rest to top-level JSON keys.
        for key, value in record.__dict__.items():
            if key in _LOG_RECORD_STANDARD_ATTRS:
                continue
            if key.startswith("_"):
                # Private fields (e.g., ``_stack_info``) are framework
                # internals and should not leak to the log payload.
                continue
            log_data[key] = value
        if record.exc_info:
            # ``self.formatException`` returns a multi-line traceback;
            # we keep it as a single string value in the JSON payload
            # so the entire log entry remains on one line (matching the
            # "one JSON object per line" contract expected by CloudWatch).
            #
            # Per QA Checkpoint 6 Issue #5 / CWE-209 (Generation of Error
            # Message Containing Sensitive Information), we also apply
            # :func:`_sanitize_traceback` to redact absolute filesystem
            # paths (e.g., ``/tmp/blitzy/...``) from traceback text
            # before they reach log aggregators. This handles exceptions
            # emitted by:
            #   * Our own handlers in :mod:`src.api.middleware.error_handler`
            #   * Third-party libraries that log ``exc_info=True`` at import
            #     time (e.g., passlib bcrypt backend probe at startup).
            #   * Uvicorn's ``uvicorn.error`` logger, which logs the
            #     unhandled exception a SECOND time via Starlette's
            #     ``ServerErrorMiddleware`` after our
            #     ``unhandled_exception_handler`` returns — these
            #     emissions are routed through this formatter by
            #     :func:`_configure_json_logging` below.
            log_data["exception"] = _sanitize_traceback(
                self.formatException(record.exc_info),
            )
        # ``default=str`` handles datetime, Decimal, UUID and similar
        # non-JSON-primitive types that commonly appear in ``extra``.
        return json.dumps(log_data, default=str)


def _configure_json_logging(log_level: str = "INFO") -> None:
    """Install the JSON log formatter on the root logger.

    Idempotent: subsequent calls return without installing additional
    handlers if a :class:`JsonLogFormatter` is already present on the
    root logger. This keeps test harnesses (which trigger the
    ``lifespan`` start-up multiple times via
    :class:`fastapi.testclient.TestClient`) from stacking duplicate
    handlers and producing every log line N times.

    Parameters
    ----------
    log_level
        Name of the desired log level (``"INFO"``, ``"DEBUG"``, etc.).
        Defaults to ``"INFO"`` for production. Values that
        :func:`logging.getLevelName` cannot resolve fall back to
        ``INFO``.
    """
    root_logger = logging.getLogger()
    # Idempotence guard: if a JSON formatter is already installed, do
    # nothing. This matters for TestClient fixtures where the
    # ``lifespan`` start-up block may run once per test function.
    for existing_handler in root_logger.handlers:
        if isinstance(existing_handler.formatter, JsonLogFormatter):
            return
    handler = logging.StreamHandler()
    handler.setFormatter(JsonLogFormatter())
    # Replace existing handlers rather than append — prevents duplicate
    # output when a library (e.g., uvicorn) installs its own handler at
    # import time. The JSON handler becomes the single source of truth
    # for this process.
    root_logger.handlers = [handler]
    # Translate a free-form string to a numeric level. If the level
    # name is unknown, default to INFO.
    level_value = logging.getLevelName(log_level.upper() if log_level else "INFO")
    if not isinstance(level_value, int):
        level_value = logging.INFO
    root_logger.setLevel(level_value)

    # ----------------------------------------------------------------
    # QA Checkpoint 6 Issue #5 / CWE-209 (Information Exposure Through
    # an Error Message): route Uvicorn's, FastAPI's, Starlette's, and
    # asyncio's loggers through the root logger so that ALL log records
    # (including exception tracebacks from ``ServerErrorMiddleware``
    # and from Uvicorn's ``httptools_impl``) are formatted by our
    # :class:`JsonLogFormatter` — which applies
    # :func:`_sanitize_traceback` to redact absolute filesystem paths
    # from traceback text.
    #
    # Why this is needed: Uvicorn (and by extension Starlette's
    # ``ServerErrorMiddleware``) attaches its own handlers to named
    # loggers at import/start-up. Those default handlers use a plain-
    # text formatter that emits raw ``File "/tmp/blitzy/..."`` lines
    # to stderr when ``log.exception(...)`` is called. This happens
    # AFTER our :func:`unhandled_exception_handler` returns, so the
    # sanitized JSON log entry we emit from the handler is followed
    # by an UN-sanitized stderr traceback from Starlette/Uvicorn — and
    # the QA report flagged exactly those stderr lines.
    #
    # The fix: clear each library logger's handler list and enable
    # propagation so records bubble up to the root (JSON) handler.
    # This unifies the log stream format AND ensures every traceback
    # passes through :func:`_sanitize_traceback`.
    for framework_logger_name in (
        "uvicorn",
        "uvicorn.error",
        "uvicorn.access",
        "fastapi",
        "starlette",
        "asyncio",
    ):
        framework_logger = logging.getLogger(framework_logger_name)
        framework_logger.handlers = []
        framework_logger.propagate = True


# ----------------------------------------------------------------------------
# Application lifespan — replaces CICS "CEMT SET FILE(*) OPEN" bootstrap.
#
# The ``lifespan`` context manager is FastAPI's modern alternative to the
# deprecated ``@app.on_event("startup")`` / ``@app.on_event("shutdown")``
# event handlers. It runs exactly once per worker process:
#
#   * On entry (before the server begins accepting requests): configure
#     structured JSON logging (CloudWatch-compatible), then initialize the
#     Aurora PostgreSQL connection pool. Any error here fails the worker
#     startup loudly, preventing the container from passing its ECS health
#     check and blocking a bad deployment from serving traffic.
#
#   * On exit (after the server stops accepting requests): dispose of
#     the pool cleanly so that Aurora's server-side connection counters
#     decrement promptly rather than waiting for TCP RSTs to drain.
#
# This is the Python equivalent of the pair of CICS operations
# performed at region startup (``CEMT SET FILE(ACCTDAT) OPEN`` ...) and
# region shutdown (``CEMT SET FILE(ACCTDAT) CLOSE``) on the legacy
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
    # Install structured JSON logging *first* so that every subsequent
    # startup log record (including DB pool initialization) is already
    # serialized as JSON for CloudWatch.
    _configure_json_logging(log_level=settings.LOG_LEVEL)
    logger.info(
        "carddemo-api startup beginning",
        extra={
            "event": "api_startup_begin",
            "app_name": settings.APP_NAME,
            "app_version": settings.APP_VERSION,
            "log_level": settings.LOG_LEVEL,
            "debug": settings.DEBUG,
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
# GraphQL context dependency.
#
# Strawberry resolvers access request-scoped resources via
# ``info.context[...]``. This ``context_getter`` is an async FastAPI
# dependency callable that injects:
#
#   * ``db_factory`` — a callable that each resolver invokes to obtain
#                      its OWN :class:`AsyncSession` with CICS SYNCPOINT
#                      / SYNCPOINT ROLLBACK semantics. Providing a
#                      factory (rather than a single shared session)
#                      is essential because Strawberry executes
#                      sibling resolvers concurrently via
#                      ``asyncio.gather``; SQLAlchemy's
#                      :class:`AsyncSession` is NOT safe for
#                      concurrent use (raises
#                      ``InvalidRequestError: This session is
#                      provisioning a new connection; concurrent
#                      operations are not permitted``).
#   * ``user_id``   — the authenticated user id (from JWT middleware).
#   * ``user_type`` — ``'A'`` (admin) or ``'U'`` (user).
#   * ``is_admin``  — boolean convenience flag.
#
# This matches the legacy CICS COSGN00 / COMEN01 / COADM01 flow, where
# admin-only transactions checked ``CDEMO-USER-TYPE = 'A'`` (88-level
# ``CDEMO-USRTYP-ADMIN``) before dispatching.
# ----------------------------------------------------------------------------
async def get_graphql_context(
    request: Request,
) -> dict[str, Any]:
    """Build the per-request GraphQL context dictionary.

    Strawberry resolvers access request-scoped resources via
    ``info.context[...]`` (see :func:`src.api.graphql.queries._get_session`
    and :func:`src.api.graphql.mutations._get_session`). This context
    getter publishes a SESSION FACTORY rather than a single session so
    that EACH resolver opens its OWN :class:`AsyncSession`:

    * Strawberry executes sibling resolvers concurrently (via
      ``asyncio.gather``) when a GraphQL query selects multiple
      top-level fields (e.g. ``{ accounts users }``). SQLAlchemy's
      :class:`AsyncSession` is **NOT** safe for concurrent use; any
      overlapping ``execute`` / ``commit`` call would raise
      ``InvalidRequestError: This session is provisioning a new
      connection; concurrent operations are not permitted``. Giving
      every resolver its own session eliminates that entire class of
      failure mode.
    * Each resolver retains commit-on-clean-exit and
      rollback-on-exception semantics because the factory
      (:func:`src.api.database.get_async_session`) implements the
      CICS ``SYNCPOINT`` / ``SYNCPOINT ROLLBACK`` contract internally
      — matching the transactional behaviour of the legacy online
      COBOL programs (see ``app/cbl/COACTUPC.cbl`` line 953 for the
      canonical ROLLBACK path).

    Authenticated user identity is also propagated from the
    :class:`~src.api.middleware.auth.JWTAuthMiddleware` through
    :class:`starlette.requests.Request.state` (the middleware runs
    before the GraphQL router and, on a valid JWT, populates
    ``request.state.user_id``, ``request.state.user_type``, and
    ``request.state.is_admin``). Exposing these claims on the
    GraphQL context enables resolvers to enforce field-level
    authorization equivalent to the REST middleware's path-prefix
    admin gating (see
    :data:`src.api.middleware.auth.ADMIN_ONLY_PREFIXES`).

    This preserves parity with the legacy CICS COSGN00 /
    COMEN01 / COADM01 flow, where admin-only transactions
    (COUSR00 / COUSR01 / COUSR02 / COUSR03) checked
    ``CDEMO-USER-TYPE = 'A'`` (88 ``CDEMO-USRTYP-ADMIN``) before
    rendering or dispatching.

    Parameters
    ----------
    request
        The underlying Starlette :class:`~starlette.requests.Request`
        produced by the FastAPI / GraphQLRouter integration.
        Its ``state`` attribute is populated by
        :class:`src.api.middleware.auth.JWTAuthMiddleware` on
        successful JWT validation; by the time a GraphQL resolver
        runs, ``request.state.user_id``, ``request.state.user_type``
        and ``request.state.is_admin`` are guaranteed to be present
        because ``/graphql`` is NOT listed in the middleware's public
        paths (any request that reaches this context-getter has
        already passed JWT authentication).

    Returns
    -------
    dict
        A mapping containing:

        * ``"db_factory"`` -- the async-generator callable
          :func:`src.api.database.get_async_session` that each
          resolver invokes to obtain a FRESH :class:`AsyncSession`
          with commit/rollback semantics. Resolvers consume this
          via ``async with _get_session(info) as session:`` (see
          :func:`src.api.graphql.queries._get_session` and
          :func:`src.api.graphql.mutations._get_session` which wrap
          the factory with :func:`contextlib.asynccontextmanager`).
        * ``"user_id"`` -- str, the authenticated user id
          (``CDEMO-USER-ID`` PIC X(08)).
        * ``"user_type"`` -- str, the authenticated user type
          (``CDEMO-USER-TYPE`` PIC X(01); ``'A'`` = admin,
          ``'U'`` = regular user).
        * ``"is_admin"`` -- bool, True iff ``user_type == 'A'``
          (88 ``CDEMO-USRTYP-ADMIN``). Resolvers use this flag
          to enforce admin-only access to USRSEC data equivalent
          to the REST :data:`~src.api.middleware.auth.ADMIN_ONLY_PREFIXES`
          gating.

        ``getattr`` with safe defaults is used when reading
        ``request.state`` attributes so the context getter stays
        robust in theoretical edge cases (for example, if the
        middleware set ``/graphql`` public in a future
        configuration change, unauthenticated GraphQL calls
        would receive empty claims and the resolver-level guards
        would deny admin-only queries rather than raising a
        confusing ``AttributeError``).

    Notes
    -----
    Historical note (QA Checkpoint 10, Issue 1): Prior to this
    revision, the context getter injected a single shared
    :class:`AsyncSession` acquired via
    :func:`~src.api.dependencies.get_db`. That design broke
    multi-field GraphQL queries with 100% failure rate because
    Strawberry's concurrent resolver execution invoked overlapping
    ``execute`` calls on the same session. Replacing the single
    session with a session FACTORY is the architectural fix;
    see ``src/api/graphql/queries.py::_get_session`` and
    ``src/api/graphql/mutations.py::_get_session`` for the consumer
    side (per-resolver ``async with`` acquisition).
    """
    # Extract JWT claims from request.state (populated by
    # JWTAuthMiddleware on successful authentication). ``getattr``
    # with safe defaults preserves robustness if middleware ordering
    # ever changes.
    user_id: str = getattr(request.state, "user_id", "")
    user_type: str = getattr(request.state, "user_type", "")
    is_admin: bool = getattr(request.state, "is_admin", False)
    return {
        # Per-resolver session factory (see class docstring). Each
        # resolver calls into this to obtain its OWN AsyncSession,
        # avoiding the concurrency bug that single-session sharing
        # introduced when Strawberry executes sibling resolvers via
        # asyncio.gather.
        "db_factory": get_async_session,
        "user_id": user_id,
        "user_type": user_type,
        "is_admin": is_admin,
    }


# ----------------------------------------------------------------------------
# Health probe handler.
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
# Root endpoint handler ("/") -- a minimal landing page that confirms the
# service is reachable and points callers at the OpenAPI docs.
#
# Declared in :data:`PUBLIC_PATHS` so no authentication is required.
# Provided primarily for human-friendly browser visits to the bare
# hostname; programmatic callers should use ``/openapi.json`` or
# ``/health``.
# ----------------------------------------------------------------------------
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
# Application factory.
#
# ``create_app()`` constructs a fully configured FastAPI application with
# all middleware, exception handlers, REST routers, GraphQL endpoint, and
# system routes (``/health`` and ``/``) in place. This factory pattern
# serves three purposes:
#
#   1. **Test isolation.** Test suites can instantiate a fresh app per
#      test function (or test class) so that route registrations,
#      exception handlers, and middleware state do not leak between
#      tests. Without a factory, the singleton module-level app would
#      carry monkey-patched fixtures forward across tests.
#
#   2. **Configuration injection.** Future refactors may want to pass
#      a pre-built :class:`Settings` instance, a custom logger, or a
#      substitute database engine into the factory. The factory
#      signature is the correct extension point for such hooks.
#
#   3. **Explicit composition root.** All wiring (middleware order,
#      router mounting, GraphQL context binding) is centralized in one
#      readable function body rather than scattered across module-level
#      statements. This matches the architecture documented in
#      AAP §0.4.3 ("Design Pattern Applications").
#
# The module-level ``app = create_app()`` binding is retained so that
# ``uvicorn src.api.main:app`` (the canonical ASGI import path used by
# the ECS ``CMD`` directive) continues to work unchanged.
# ----------------------------------------------------------------------------
def create_app() -> FastAPI:
    """Construct and fully configure a CardDemo FastAPI application.

    Composition root for the API service. Produces a
    :class:`fastapi.FastAPI` instance with:

    * All middleware (CORS, JWT authentication) attached in the correct
      outermost-to-innermost order.
    * Global exception handlers registered
      (:func:`src.api.middleware.error_handler.register_exception_handlers`).
    * All 8 REST routers included with the canonical URL prefix and
      OpenAPI tag.
    * The Strawberry GraphQL schema mounted at ``/graphql`` with
      a transactional database session and authenticated user context.
    * System routes for ``/health`` (readiness probe) and ``/``
      (landing redirect).
    * A ``lifespan`` context manager that initializes the Aurora
      PostgreSQL connection pool on startup and disposes of it on
      shutdown.

    Returns
    -------
    FastAPI
        A fully wired application instance, ready to be served by
        :mod:`uvicorn` (either via ``uvicorn src.api.main:app`` or via
        the ``if __name__ == "__main__"`` block below).

    Notes
    -----
    The factory makes exactly ONE :func:`get_settings` call during app
    construction — to read :attr:`Settings.CORS_ALLOWED_ORIGINS` and
    configure the CORS middleware allow-list (see code-review MEDIUM
    security finding: wildcard ``["*"]`` combined with
    ``allow_credentials=True`` is invalid per the W3C CORS spec). All
    other settings access is deferred to request handlers and the
    ``lifespan`` callback. This construction-time settings read is
    compatible with:

    * **pytest collection** — the root ``tests/conftest.py`` populates
      ``DATABASE_URL``, ``DATABASE_URL_SYNC``, and ``JWT_SECRET_KEY``
      via ``os.environ.setdefault(...)`` BEFORE importing
      :func:`create_app`, so :class:`Settings` validation succeeds.
    * **mypy** and **ruff** — both are pure static analyzers that
      operate on the AST and never execute module-level code; the
      ``get_settings()`` call is never triggered during type-check /
      lint runs.
    * **Production (ECS Fargate)** — the ECS task definition injects
      the required env vars from AWS Secrets Manager at container
      start, well before the ASGI worker imports this module.

    If a downstream caller imports :mod:`src.api.main` outside any of
    the above contexts without the required env vars set, a
    :class:`pydantic.ValidationError` will surface at import time —
    the same fail-fast behavior as the Settings class itself (AAP
    §0.7.2 "Environment variable validation at startup").
    """
    # ------------------------------------------------------------------
    # 1. Instantiate the FastAPI app.
    #
    # ``version`` is set to the compile-time literal ``DEFAULT_APP_VERSION``
    # rather than ``get_settings().APP_VERSION`` for the reasons
    # described in the module docstring (import-safety during tooling).
    # ------------------------------------------------------------------
    new_app: FastAPI = FastAPI(
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

    # ------------------------------------------------------------------
    # 2. Middleware configuration.
    #
    # Starlette / FastAPI middleware semantics:
    #
    #   * ``app.add_middleware(M1); app.add_middleware(M2)`` — M2 is the
    #     OUTERMOST middleware (requests hit M2 first, then M1, then the
    #     route). This is the reverse of the "first added = first
    #     executed" mental model used by some other frameworks.
    #
    # Our policy (innermost → outermost order of ``add_middleware``
    # calls; request flow is the reverse — outermost first):
    #
    #   1. ``JWTAuthMiddleware`` is added FIRST so it is innermost. This
    #      means CORS preflights (OPTIONS requests with no Authorization
    #      header) and security-header injection run before the JWT
    #      validator has a chance to reject them.
    #
    #   2. ``SecurityHeadersMiddleware`` is added SECOND so it wraps the
    #      JWT middleware. This guarantees security headers
    #      (X-Content-Type-Options, X-Frame-Options, HSTS, CSP,
    #      Referrer-Policy, Permissions-Policy, stripped Server header,
    #      and Cache-Control: no-store on auth endpoints) appear on
    #      every response — including the 401/403 responses produced
    #      by the JWT middleware. Addresses QA Checkpoint 6 Issues #1
    #      (CRITICAL) and #6 (MINOR).
    #
    #   3. ``CORSMiddleware`` is added LAST so it is outermost. Placing
    #      it outside SecurityHeadersMiddleware ensures CORS preflight
    #      (OPTIONS) responses, which CORSMiddleware generates directly
    #      without traversing the inner chain, are not mutated by the
    #      security-header middleware — preflights already carry exactly
    #      the minimal set of Access-Control-Allow-* headers the browser
    #      expects. (Actual non-preflight responses still traverse the
    #      full chain, so security headers are applied to them normally.)
    #
    # Order of request processing under this configuration:
    #
    #   Request -> CORSMiddleware -> SecurityHeadersMiddleware ->
    #              JWTAuthMiddleware -> Routes
    # ------------------------------------------------------------------

    # 2a. JWT authentication middleware (innermost).
    # Gates access to every non-public path; allows PUBLIC_PATHS (/health,
    # /auth/login, /auth/logout, /, /docs, /redoc, /openapi.json, /static/*)
    # through without an Authorization header. Rejects missing/invalid/
    # expired/malformed tokens with a 401 ABEND-DATA JSON response.
    new_app.add_middleware(JWTAuthMiddleware)

    # 2b. Security response headers middleware (middle).
    # Injects OWASP-recommended security headers (X-Content-Type-Options,
    # X-Frame-Options, HSTS, CSP, Referrer-Policy, Permissions-Policy)
    # on every response regardless of status code. Overwrites the Uvicorn
    # Server header with an opaque "API" value. Applies
    # Cache-Control: no-store to /auth/* responses so that JWTs are
    # never cached by browsers or proxies. Addresses QA Checkpoint 6
    # Issues #1 (CRITICAL — defense-in-depth) and #6 (MINOR —
    # information disclosure).
    new_app.add_middleware(SecurityHeadersMiddleware)

    # 2c. CORS middleware (outermost).
    #
    # ``allow_origins`` is sourced from :attr:`Settings.CORS_ALLOWED_ORIGINS`
    # (env var ``CORS_ALLOWED_ORIGINS``) rather than the prior
    # wildcard ``["*"]`` default. Per the W3C CORS specification,
    # ``allow_origins=["*"]`` combined with ``allow_credentials=True``
    # is an invalid configuration — browsers reject credentialed
    # requests when origins is wildcard, which would render the JWT
    # ``Authorization: Bearer`` header unusable from any modern SPA
    # (see code-review MEDIUM security finding). By reading the allow-
    # list from settings, local development remains permissive via the
    # safe localhost-only default (``http://localhost:3000``,
    # ``http://localhost:8080``), while staging and production
    # deployments set ``CORS_ALLOWED_ORIGINS`` explicitly on the ECS
    # task definition to the actual ALB / CloudFront domain(s). This
    # closes the CORS-wildcard-with-credentials vulnerability without
    # sacrificing the cross-origin access required by SPA clients.
    new_app.add_middleware(
        CORSMiddleware,
        allow_origins=list(get_settings().CORS_ALLOWED_ORIGINS),
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["X-Request-ID"],
    )

    # ------------------------------------------------------------------
    # 3. Global exception handlers.
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
    # ------------------------------------------------------------------
    register_exception_handlers(new_app)

    # ------------------------------------------------------------------
    # 4. REST router mounting. Replaces CICS XCTL-based menu navigation.
    #
    # Each symbol imported from :mod:`src.api.routers` above is an
    # ``APIRouter`` instance re-exported by ``src/api/routers/__init__.py``.
    # The prefix/tag pairing mirrors the legacy COBOL feature areas.
    # ------------------------------------------------------------------

    # F-001 Sign-on / authentication (COBOL: COSGN00C, CICS txn CC00).
    new_app.include_router(
        auth_router,
        prefix="/auth",
        tags=["Authentication"],
    )

    # F-004 Account view (COACTVWC), F-005 Account update (COACTUPC).
    new_app.include_router(
        account_router,
        prefix="/accounts",
        tags=["Accounts"],
    )

    # F-006 Card list (COCRDLIC), F-007 Card detail (COCRDSLC),
    # F-008 Card update (COCRDUPC).
    new_app.include_router(
        card_router,
        prefix="/cards",
        tags=["Cards"],
    )

    # F-009 Transaction list (COTRN00C), F-010 Transaction detail
    # (COTRN01C), F-011 Transaction add (COTRN02C).
    new_app.include_router(
        transaction_router,
        prefix="/transactions",
        tags=["Transactions"],
    )

    # F-012 Bill payment (COBIL00C).
    new_app.include_router(
        bill_router,
        prefix="/bills",
        tags=["Bills"],
    )

    # F-022 Report submission (CORPT00C, TDQ -> SQS bridge).
    new_app.include_router(
        report_router,
        prefix="/reports",
        tags=["Reports"],
    )

    # F-018 User list (COUSR00C), F-019 User add (COUSR01C),
    # F-020 User update (COUSR02C), F-021 User delete (COUSR03C).
    new_app.include_router(
        user_router,
        prefix="/users",
        tags=["Users"],
    )

    # F-003 Admin menu (COADM01C). Admin-only endpoints that bridge the
    # remaining CICS admin sub-transactions.
    new_app.include_router(
        admin_router,
        prefix="/admin",
        tags=["Admin"],
    )

    # ------------------------------------------------------------------
    # 5. GraphQL endpoint mounting.
    #
    # The GraphQL schema is a single Strawberry schema
    # (:data:`src.api.graphql.schema.schema`) that stitches together the
    # Query resolvers (account, card, cards, transaction, transactions,
    # user, users) and the Mutation resolvers (updateAccount, updateCard,
    # addTransaction, payBill).
    #
    # The ``context_getter`` is an async FastAPI dependency callable that
    # injects a SESSION FACTORY (``get_async_session``) into every
    # resolver's ``info.context["db_factory"]`` so that each resolver
    # opens its OWN :class:`AsyncSession`. This is essential because
    # Strawberry executes sibling resolvers concurrently via
    # ``asyncio.gather`` — the prior single-session design raised
    # ``sqlalchemy.exc.InvalidRequestError`` on any multi-field query
    # (QA Checkpoint 10, Issue 1). Each resolver retains CICS
    # SYNCPOINT / SYNCPOINT ROLLBACK semantics through the factory
    # (rollback-on-exception, commit-on-clean-exit).
    #
    # The ``context_getter`` argument carries a narrower type in the
    # Strawberry stubs (``Callable[..., Awaitable[None] | None] | None``)
    # than the runtime actually accepts — Strawberry documents and
    # supports context_getters that return a mapping which is then
    # exposed to every resolver via ``info.context``. The mismatch is a
    # known typing gap in ``strawberry-graphql``; see
    # https://strawberry.rocks/docs/integrations/fastapi#context_getter
    # for the officially supported usage. The targeted ignore is
    # narrower than a blanket mypy disable.
    #
    # We intentionally do NOT include ``/graphql`` in the PUBLIC_PATHS
    # of :class:`JWTAuthMiddleware`. This means every GraphQL request
    # is authenticated before the schema sees it — the legacy CICS
    # model where every transaction checked the signed-on user ID is
    # preserved.
    # ------------------------------------------------------------------
    graphql_app: GraphQLRouter = GraphQLRouter(
        graphql_schema,
        context_getter=get_graphql_context,  # type: ignore[arg-type]
    )
    new_app.include_router(
        graphql_app,
        prefix="/graphql",
        tags=["GraphQL"],
    )

    # ------------------------------------------------------------------
    # 6. System routes: /health and /.
    #
    # Registered via ``add_api_route`` rather than ``@new_app.get(...)``
    # decorators because the handler coroutines (``health``, ``root``)
    # are defined at module scope (for export and testability) rather
    # than inside the factory body.
    # ------------------------------------------------------------------
    new_app.add_api_route(
        "/health",
        health,
        methods=["GET"],
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

    new_app.add_api_route(
        "/",
        root,
        methods=["GET"],
        tags=["Meta"],
        summary="API root",
        description=(
            "Human-friendly landing response. Returns basic service "
            "identification and pointers to the OpenAPI documentation."
        ),
        include_in_schema=False,
    )

    return new_app


# ----------------------------------------------------------------------------
# Module-level ASGI application instance.
#
# The canonical import path for ASGI servers (Uvicorn, Gunicorn) is
# ``src.api.main:app``. This line ensures that path continues to resolve
# to a fully configured FastAPI app — the same instance that
# :func:`create_app` would return.
#
# In-process tests that need isolation can call ``create_app()`` directly
# to build a fresh instance per test; the module-level ``app`` is reserved
# for deployment (ECS ``CMD`` directive and Uvicorn reload workflows).
# ----------------------------------------------------------------------------
app: FastAPI = create_app()


# ----------------------------------------------------------------------------
# Explicit public API of this module.
#
# ``app`` is the canonical ASGI import that Uvicorn, Gunicorn, and the
# FastAPI TestClient need. ``create_app`` is the factory that test
# suites can call for isolation. ``lifespan``, ``get_graphql_context``,
# ``health``, and ``root`` are exported for testability but are not
# expected to be imported by normal callers.
# ----------------------------------------------------------------------------
__all__ = [
    "DEFAULT_APP_VERSION",
    "JsonLogFormatter",
    "app",
    "create_app",
    "get_graphql_context",
    "health",
    "lifespan",
    "root",
]


# ----------------------------------------------------------------------------
# Direct execution entry point (``python -m src.api.main``).
#
# In production (AWS ECS Fargate), the container ``CMD`` directive runs
# Uvicorn with the canonical ASGI import path::
#
#     uvicorn src.api.main:app --host 0.0.0.0 --port 8000
#
# The block below is exercised only during local development, where a
# single ``python src/api/main.py`` command is convenient. It reads the
# bind address and port from :class:`Settings` defaults
# (host = ``0.0.0.0``, port = ``8000``) and disables auto-reload
# (``reload=False``) because reload requires the ``watchfiles`` dev
# dependency and is not something we want in production.
#
# Structured JSON logging is configured eagerly here so that developer
# log output during ``python -m src.api.main`` mirrors the format
# emitted in the ECS container (easier debugging parity).
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    # Configure JSON logging before Uvicorn installs its own handlers.
    _configure_json_logging()
    logger.info(
        "carddemo-api launching via direct execution (__main__)",
        extra={"event": "api_main_direct_launch"},
    )
    # ``server_header=False`` is REQUIRED for QA Checkpoint 6 Issue #6
    # (MINOR — ``Server: uvicorn`` disclosure, CWE-200). Without this,
    # Uvicorn injects its own ``Server: uvicorn`` header at the ASGI
    # protocol layer AFTER :class:`SecurityHeadersMiddleware.dispatch`
    # finishes, resulting in two conflicting ``Server`` headers. With
    # it set to ``False`` the opaque ``Server: API`` value from
    # :class:`SecurityHeadersMiddleware` is the only one emitted.
    uvicorn.run(
        "src.api.main:app",
        host="0.0.0.0",  # noqa: S104 — intentional; listen on all interfaces for dev.
        port=8000,
        reload=False,
        log_level="info",
        server_header=False,
    )
