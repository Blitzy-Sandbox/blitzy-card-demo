# ============================================================================
# Source: JCL VSAM provisioning jobs -> SQLAlchemy async engine & session mgmt
#         app/jcl/ACCTFILE.jcl  (IDCAMS DEFINE CLUSTER, KEYS(11 0),
#                                RECORDSIZE(300 300), SHAREOPTIONS(2 3),
#                                REPRO from flat file)
#         app/jcl/CARDFILE.jcl  (IDCAMS DEFINE CLUSTER, KEYS(16 0),
#                                RECORDSIZE(150 150), ALTERNATEINDEX on
#                                acct id, BLDINDEX, CEMT SET FIL OPE)
#         app/jcl/CUSTFILE.jcl  (IDCAMS DEFINE CLUSTER, KEYS(9  0),
#                                RECORDSIZE(500 500), CEMT SET FIL OPE)
#         These JCL members provisioned VSAM KSDS datasets and opened them
#         through CICS for online transaction access. The pattern is
#         replaced here by a SQLAlchemy 2.x async engine and connection
#         pool reaching AWS Aurora PostgreSQL with credentials sourced
#         from AWS Secrets Manager (AAP Section 0.7.2 security).
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
"""SQLAlchemy async database engine and session factory for Aurora PostgreSQL.

Replaces VSAM file OPEN/CLOSE patterns from JCL provisioning jobs.

Mainframe-to-Cloud Mapping
--------------------------
The original CardDemo mainframe application persisted data in ten VSAM
KSDS clusters plus three alternate indexes, provisioned by JCL members
such as ``app/jcl/ACCTFILE.jcl``, ``app/jcl/CARDFILE.jcl``, and
``app/jcl/CUSTFILE.jcl`` (IDCAMS ``DEFINE CLUSTER`` + ``REPRO`` +
``BLDINDEX``), and accessed by online CICS COBOL programs through
``EXEC CICS READ`` / ``WRITE`` / ``REWRITE`` / ``DELETE`` commands under
``CEMT SET FILE(...) OPEN``. This module replaces that machinery with a
single process-level SQLAlchemy async engine and session factory bound
to AWS Aurora PostgreSQL:

==============================  ============================================
Mainframe construct             Cloud-native replacement
==============================  ============================================
VSAM DEFINE CLUSTER (JCL)       PostgreSQL table DDL (db/migrations/V1)
VSAM REPRO (IDCAMS)             SQL INSERT (db/migrations/V3__seed_data.sql)
VSAM ALTERNATEINDEX             B-tree index (db/migrations/V2__indexes.sql)
CEMT SET FILE(...) OPEN         create_async_engine() + connection pool
CEMT SET FILE(...) CLOSED       engine.dispose() at app shutdown
EXEC CICS READ / READ NEXT      SQLAlchemy ORM SELECT via AsyncSession
EXEC CICS WRITE                 session.add() + session.commit()
EXEC CICS REWRITE               attribute assignment + session.commit()
EXEC CICS DELETE                session.delete() + session.commit()
EXEC CICS SYNCPOINT             session.commit() on clean exit
EXEC CICS SYNCPOINT ROLLBACK    session.rollback() on exception path
RACF dataset creds (JCL DD)     AWS Secrets Manager via boto3 fetch
==============================  ============================================

The three JCL source members referenced above are representative of the
broader VSAM-provisioning pattern — they define the three largest
clusters (Account: 11-digit PK / 300-byte record, Card: 16-digit PK /
150-byte record with an 11-digit alternate index, Customer: 9-digit PK
/ 500-byte record). The equivalent relational schema lives in
``db/migrations/V1__schema.sql``; the indexes that replace the VSAM AIX
paths live in ``db/migrations/V2__indexes.sql``; and credentials that
were bound into JCL DD statements (``//ACCTFILE DD DSN=...``) now live
in AWS Secrets Manager.

Design Principles
-----------------
* **Credentials from AWS Secrets Manager** (AAP Section 0.7.2) — no
  database credentials are hardcoded in the container image or
  environment variables baked into the ECS task definition. Two
  resolution paths are supported by :func:`_build_async_database_url`:

  1. ``Settings.DATABASE_URL`` is a fully-formed async URL (the common
     case for local ``docker-compose`` development and ECS task
     definitions whose ``secrets:`` block injects the complete URL
     directly from Secrets Manager at container start). The URL is
     returned verbatim.
  2. ``Settings.DATABASE_URL`` begins with the sentinel
     ``secretsmanager:`` prefix. Credentials are retrieved at runtime
     via :func:`~src.shared.config.aws_config.get_database_credentials`
     using either ``Settings.DB_SECRET_NAME`` or an optional override
     encoded after the sentinel (``secretsmanager://custom-secret``),
     and the async connection URL is assembled from the JSON payload.
     This path supports Secrets Manager credential rotation without
     redeploying the ECS task definition.

* **Rollback-on-exception** — :func:`get_async_session` wraps every
  session in a ``try/except/else`` pattern that mirrors the behavior
  of ``EXEC CICS SYNCPOINT ROLLBACK`` in ``app/cbl/COACTUPC.cbl``'s
  account-update error path (around line 953): any exception raised
  inside the calling business logic triggers ``await
  session.rollback()`` before the exception propagates out of the
  generator, guaranteeing that no partial writes are committed. The
  clean-exit branch commits (``EXEC CICS SYNCPOINT`` equivalent).

* **asyncpg driver** — the async URL scheme is
  ``postgresql+asyncpg://`` (NOT ``psycopg2``). Async SQLAlchemy
  requires an async-capable driver, and ``asyncpg`` is the
  performance-leading choice for PostgreSQL in the Python ecosystem
  (AAP Section 0.6.1 pinned version 0.30.x).

* **Connection pooling sized for ECS Fargate** — the pool parameters
  (``pool_size``, ``max_overflow``, ``pool_timeout``, ``pool_recycle``,
  ``pool_pre_ping``) are sized for the ECS Fargate task profile
  (0.5 vCPU / 1 GB RAM) specified in AAP Section 0.6.2.
  ``pool_recycle=3600`` (one hour) prevents stale-connection errors
  when Aurora applies idle-connection timeouts. ``pool_pre_ping=True``
  makes the pool transparently recover from Aurora failover events
  by issuing a lightweight ``SELECT 1`` on checkout.

* **Structured logging for CloudWatch** — all lifecycle events (engine
  init, credential retrieval, shutdown) are logged via the standard
  :mod:`logging` module. The ECS task awslogs driver forwards these
  records to CloudWatch Log Groups, matching the monitoring
  requirements in AAP Section 0.7.2.

* **Idempotent init / close** — :func:`init_db` and :func:`close_db`
  are both safe to call multiple times; duplicates log a warning and
  short-circuit rather than double-initializing or double-disposing.
  This hardens the lifecycle against misconfigured startup hooks and
  accommodates pytest fixtures that may invoke them repeatedly.

See Also
--------
* AAP Section 0.4.1 — Refactored Structure Planning
  (``src/api/database.py``).
* AAP Section 0.5.1 — File-by-File Transformation Plan.
* AAP Section 0.6.1 — API-layer dependencies (SQLAlchemy 2.0.x,
  asyncpg 0.30.x).
* AAP Section 0.6.2 — AWS Service Dependencies (Aurora PostgreSQL,
  Secrets Manager, ECS Fargate).
* AAP Section 0.7.2 — Security Requirements (Secrets Manager, no
  hardcoded credentials, IAM roles).
* ``app/cbl/COACTUPC.cbl`` — ``EXEC CICS SYNCPOINT ROLLBACK`` pattern
  preserved by :func:`get_async_session`.
* ``db/migrations/V1__schema.sql`` — Aurora PostgreSQL DDL replacing
  VSAM DEFINE CLUSTER statements from ACCTFILE.jcl, CARDFILE.jcl,
  CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl, TCATBALF.jcl, DUSRSECJ.jcl.
"""

from __future__ import annotations

import logging
from typing import AsyncGenerator  # noqa: UP035  (schema-specified import source)

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from src.shared.config.aws_config import get_database_credentials
from src.shared.config.settings import Settings

# ----------------------------------------------------------------------------
# Module logger.
#
# Uses ``__name__`` so CloudWatch Log Groups (ECS awslogs driver) route
# these records under "src.api.database" — matching the Python module
# path and making engine-lifecycle events easy to locate in log
# aggregation dashboards. All emitted records include:
#   - init_db / close_db lifecycle messages (INFO)
#   - Idempotent-guard warnings when init/close are double-invoked
#   - Rollback traces when exceptions surface in get_async_session
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# Public API re-export list.
#
# Only the three lifecycle functions are advertised as the module's
# public API. The module-level engine/session-factory state and the
# private URL builder are implementation details — tests that need to
# override them may reach into the module, but application code must
# use these three entry points only. Listing them in ``__all__`` also
# configures ``from src.api.database import *`` to import only these
# names (though star-imports are discouraged elsewhere in the code
# base — the ``__all__`` entry is for IDE/tooling auto-completion and
# documentation generation).
# ----------------------------------------------------------------------------
__all__ = ["init_db", "get_async_session", "close_db"]


# ============================================================================
# Module-level engine / session-factory state.
#
# These are populated by :func:`init_db` at FastAPI startup and consumed
# by :func:`get_async_session` on every request. They are initialized to
# ``None`` so that calling :func:`get_async_session` before
# :func:`init_db` raises a clear :class:`RuntimeError` rather than an
# opaque :class:`AttributeError`, greatly improving debuggability of
# "forgot the startup hook" deployment misconfigurations.
#
# Concurrency: Python's GIL ensures assignment to these module-level
# variables is atomic. No explicit lock is required because the variables
# are written exactly once at process startup (before any async event-
# loop work begins) and read afterwards. The objects themselves (the
# :class:`AsyncEngine` and the :class:`async_sessionmaker`) are
# documented as thread/async-safe by SQLAlchemy.
# ============================================================================
_engine: AsyncEngine | None = None
_async_session_factory: async_sessionmaker[AsyncSession] | None = None


# ============================================================================
# Sentinel prefix that marks ``Settings.DATABASE_URL`` as a Secrets
# Manager lookup directive rather than a real connection string.
#
# Because :attr:`Settings.DATABASE_URL` is declared as a required field
# on the Pydantic BaseSettings class (no default — Pydantic fails fast
# at startup if the env var is missing, providing CWE-798 protection
# against placeholder credentials being used inadvertently), production
# deployments must supply SOME value for it. For deployments that
# prefer to fetch credentials from Secrets Manager at runtime rather
# than at container launch, setting ``DATABASE_URL="secretsmanager:"``
# (or ``"secretsmanager://custom-secret-name"``) in the ECS task
# definition satisfies the Pydantic required-field check while
# simultaneously instructing this module to perform the Secrets
# Manager lookup. Both forms are supported — with or without the
# trailing ``//``.
# ============================================================================
_SECRETS_MANAGER_SENTINEL = "secretsmanager:"


# ----------------------------------------------------------------------------
# Private helper: Resolve the async Aurora PostgreSQL URL.
#
# Factored into a separate function (rather than inlined into init_db)
# so that unit tests can inject a mock Settings instance and verify the
# URL-resolution logic (direct URL vs Secrets Manager sentinel, secret-
# name override parsing) in isolation, without touching the engine or
# the pool.
# ----------------------------------------------------------------------------
def _build_async_database_url(settings: Settings) -> str:
    """Resolve the async Aurora PostgreSQL connection URL.

    Two resolution paths are supported. The chosen path is determined
    by the prefix of :attr:`Settings.DATABASE_URL`:

    1. **Direct URL (common)**: The setting is a fully-formed async
       URL such as ``postgresql+asyncpg://user:pass@host:5432/db``.
       Returned verbatim. Used by ``docker-compose.yml`` (local dev)
       and by ECS task definitions whose ``secrets:`` block injects
       the complete URL from Secrets Manager at container start.

    2. **Secrets Manager lookup**: The setting begins with the
       sentinel prefix ``secretsmanager:``. Credentials are
       retrieved via
       :func:`~src.shared.config.aws_config.get_database_credentials`
       using either :attr:`Settings.DB_SECRET_NAME` or an optional
       override name encoded after the sentinel
       (``secretsmanager://custom-secret-name``), and an async URL
       is assembled from the returned username/password/host/port/
       dbname fields. This path supports Secrets Manager rotation
       without redeploying the task definition.

    Parameters
    ----------
    settings : Settings
        The :class:`~src.shared.config.settings.Settings` instance.
        Passed explicitly (rather than re-instantiated inside the
        helper) so unit tests can inject a fixture with controlled
        ``DATABASE_URL`` / ``DB_SECRET_NAME`` values without
        touching real environment variables.

    Returns
    -------
    str
        The async PostgreSQL connection URL, suitable as the first
        argument to :func:`~sqlalchemy.ext.asyncio.create_async_engine`.
    """
    db_url = settings.DATABASE_URL

    # Detect the sentinel prefix. Both ``secretsmanager:`` and
    # ``secretsmanager://`` (with or without override-secret suffix)
    # are recognized so operators have flexibility in how they
    # structure the ECS task-definition env var.
    if db_url.startswith(_SECRETS_MANAGER_SENTINEL):
        # Parse the remainder after the sentinel. The ``lstrip("/")``
        # normalizes across both ``secretsmanager:xyz`` and
        # ``secretsmanager://xyz`` forms, giving a clean secret name.
        remainder = db_url[len(_SECRETS_MANAGER_SENTINEL) :].lstrip("/")
        # Override secret name if supplied; otherwise fall back to
        # the Settings default (``DB_SECRET_NAME`` =
        # "carddemo/aurora-credentials").
        secret_name = remainder if remainder else settings.DB_SECRET_NAME

        creds = get_database_credentials(secret_name)

        # Construct the async URL. The asyncpg driver is mandatory:
        # FastAPI/Uvicorn run inside an asyncio event loop, and
        # SQLAlchemy's async API requires an async-capable DBAPI.
        # The credential dictionary always contains str values (per
        # the get_database_credentials contract), so no further
        # coercion is needed.
        resolved_url = (
            f"postgresql+asyncpg://"
            f"{creds['username']}:{creds['password']}"
            f"@{creds['host']}:{creds['port']}/{creds['dbname']}"
        )
        # Log resolution success for CloudWatch (but never log the
        # password — only the host/dbname/secret name, which are
        # safe operational metadata).
        logger.info(
            "Resolved async database URL from AWS Secrets Manager (secret='%s', host='%s', dbname='%s')",
            secret_name,
            creds["host"],
            creds["dbname"],
        )
        return resolved_url

    # Direct-URL path: use the setting as-is. The URL scheme is
    # expected to be ``postgresql+asyncpg://`` per the
    # :attr:`Settings.DATABASE_URL` documentation, but this helper
    # does not enforce that — SQLAlchemy will raise a clear
    # :class:`sqlalchemy.exc.ArgumentError` with a descriptive
    # message if an incompatible scheme is supplied.
    return db_url


async def init_db() -> None:
    """Initialize the async SQLAlchemy engine and session factory.

    Creates a process-level
    :class:`~sqlalchemy.ext.asyncio.AsyncEngine` and
    :class:`~sqlalchemy.ext.asyncio.async_sessionmaker` bound to
    Aurora PostgreSQL, using credentials resolved by
    :func:`_build_async_database_url` (either :attr:`Settings.DATABASE_URL`
    or AWS Secrets Manager, depending on the sentinel prefix).

    Pool Configuration (AAP Section 0.6.2 — ECS Fargate 0.5 vCPU / 1 GB RAM)
    ------------------------------------------------------------------------
    * ``pool_size = Settings.DB_POOL_SIZE`` (default 10) — the number
      of persistent connections held by the pool. Sized so a single
      ECS task can handle concurrent request bursts without thrashing
      connection creation.
    * ``max_overflow = Settings.DB_MAX_OVERFLOW`` (default 20) —
      additional connections the pool may open beyond ``pool_size``
      before requests block waiting for a free connection. Set high
      enough to absorb bursts during cold-start horizontal scaling.
    * ``pool_timeout = 30`` seconds — how long a request waits for a
      free connection before raising
      :class:`sqlalchemy.exc.TimeoutError`. Shorter than most
      user-facing request timeouts so exhaustion fails fast.
    * ``pool_recycle = 3600`` seconds (one hour) — connections older
      than the recycle age are closed and reopened on next checkout,
      avoiding "server has gone away"-style errors from Aurora's
      idle-connection timeout.
    * ``pool_pre_ping = True`` — issues a lightweight ``SELECT 1`` on
      each checkout to detect and replace connections broken by
      Aurora failover events (primary/replica promotion). The small
      per-checkout overhead buys automatic recovery from infrastructure
      churn.
    * ``echo = Settings.DEBUG`` — when ``True`` (local dev only)
      SQLAlchemy logs every SQL statement to stdout for query
      analysis. Set to ``False`` in production to keep CloudWatch
      log volume manageable.

    Idempotency
    -----------
    Repeat calls to :func:`init_db` after a successful init are
    no-ops — the function logs a warning and returns. This is safe
    for pytest fixtures that may call ``init_db`` before each test
    module. To truly re-initialize (e.g., after :func:`close_db`),
    the paired :func:`close_db` call resets the module state so the
    subsequent ``init_db`` call creates a fresh engine.

    Mainframe Equivalent
    --------------------
    Analogous to CICS ``CEMT SET FILE(ACCTDAT) OPEN`` /
    ``CEMT SET FILE(CARDDAT) OPEN`` / ``CEMT SET FILE(CUSTDAT) OPEN``
    (see ``app/jcl/ACCTFILE.jcl``, ``app/jcl/CARDFILE.jcl``,
    ``app/jcl/CUSTFILE.jcl`` — all three include an OPCIFIL step
    that issues the CEMT OPEN on region startup) — but instead of
    opening individual VSAM clusters one at a time, this function
    opens a single pooled connection to Aurora PostgreSQL that
    covers every relational table. The consolidation of ten VSAM
    KSDS datasets plus three alternate-index paths into one
    relational database is one of the core simplifications of the
    mainframe-to-cloud migration (AAP Section 0.4.1).

    Raises
    ------
    pydantic.ValidationError
        If required Settings fields (``DATABASE_URL``) are missing
        from the environment. Raised from :class:`Settings` instantiation.
    botocore.exceptions.ClientError
        If Secrets Manager retrieval fails when the
        ``secretsmanager:`` sentinel is used. Re-raised from
        :func:`get_database_credentials` so FastAPI startup aborts
        cleanly rather than proceeding with broken credentials.
    """
    global _engine, _async_session_factory

    # Idempotent guard: no-op if already initialized. This is
    # important for pytest fixtures (which may invoke init_db on
    # every test module) and for any accidental double-startup
    # scenarios in uvicorn --reload mode. Skipping re-initialization
    # prevents engine/pool leaks.
    if _engine is not None:
        logger.warning("init_db() called when engine is already initialized; skipping re-initialization")
        return

    # Parse Settings once per init. Pydantic's BaseSettings reads
    # environment variables (and the .env file for local dev) and
    # validates required fields, raising ValidationError if any
    # required field (DATABASE_URL, DATABASE_URL_SYNC,
    # JWT_SECRET_KEY) is unset — failing fast per AAP Section 0.7.2.
    settings = Settings()

    # Resolve the connection URL (direct vs Secrets Manager path).
    db_url = _build_async_database_url(settings)

    # Construct the async engine with the pool parameters documented
    # above. The engine is created once per process and shared by
    # every request handler — SQLAlchemy documents its engines as
    # thread/async-safe for this pattern.
    _engine = create_async_engine(
        db_url,
        pool_size=settings.DB_POOL_SIZE,
        max_overflow=settings.DB_MAX_OVERFLOW,
        pool_timeout=30,
        pool_recycle=3600,
        pool_pre_ping=True,
        echo=settings.DEBUG,
    )

    # Construct the session factory. ``expire_on_commit=False`` is
    # standard for async SQLAlchemy — avoids implicit attribute
    # refresh lazy-loads after commit, which would otherwise trigger
    # unexpected I/O inside FastAPI response serialization (Pydantic
    # reads attributes after the session has been committed).
    _async_session_factory = async_sessionmaker(
        _engine,
        class_=AsyncSession,
        expire_on_commit=False,
    )

    # Log initialization success with the pool parameters for
    # operational visibility. Credentials are never logged — the
    # password has already been redacted by _build_async_database_url
    # (only host/dbname/secret-name were logged there).
    logger.info(
        "SQLAlchemy async engine initialized for Aurora PostgreSQL "
        "(pool_size=%d, max_overflow=%d, pool_timeout=%ds, "
        "pool_recycle=%ds, pool_pre_ping=True, echo=%s)",
        settings.DB_POOL_SIZE,
        settings.DB_MAX_OVERFLOW,
        30,
        3600,
        settings.DEBUG,
    )


async def get_async_session() -> AsyncGenerator[AsyncSession, None]:
    """Yield a transactional :class:`AsyncSession` context.

    This async generator is the canonical database-session dependency
    consumed by FastAPI routers, GraphQL resolvers, and any other
    async callers that require a committable database session. It
    implements the following transactional contract — a direct
    translation of the CICS ``EXEC CICS SYNCPOINT`` / ``SYNCPOINT
    ROLLBACK`` pattern from the original online COBOL programs:

    * A fresh :class:`AsyncSession` is opened from the module-level
      ``_async_session_factory`` and bound to a new database
      connection pulled from the pool.
    * The session is yielded to the caller inside a ``try`` block.
    * **On exception**: ``await session.rollback()`` is invoked
      before the exception propagates out of the generator. This
      mirrors ``EXEC CICS SYNCPOINT ROLLBACK`` in
      ``app/cbl/COACTUPC.cbl`` (account-update error path around
      line 953), guaranteeing that no partial writes are committed
      when business logic raises.
    * **On clean exit**: ``await session.commit()`` is invoked.
      This mirrors the implicit ``EXEC CICS SYNCPOINT`` at CICS
      transaction end, persisting all pending writes atomically.
      Callers do **not** need to call ``commit()`` themselves;
      returning cleanly from the yield block triggers the commit.
    * The session and its connection are returned to the pool in
      all cases via the ``async with`` context manager, preventing
      connection leaks even on unexpected exits.

    FastAPI Dependency Injection Usage
    ----------------------------------
    Typical indirection via ``src/api/dependencies.py``::

        from fastapi import Depends
        from sqlalchemy.ext.asyncio import AsyncSession
        from src.api.database import get_async_session

        async def get_db() -> AsyncGenerator[AsyncSession, None]:
            async for session in get_async_session():
                yield session

    Or directly from a router::

        from fastapi import Depends
        from sqlalchemy.ext.asyncio import AsyncSession
        from src.api.database import get_async_session

        @router.get("/accounts/{account_id}")
        async def read_account(
            account_id: int,
            session: AsyncSession = Depends(get_async_session),
        ) -> AccountResponse:
            ...

    Yields
    ------
    AsyncSession
        An open SQLAlchemy :class:`AsyncSession` bound to Aurora
        PostgreSQL. Commits on clean exit; rolls back on exception.

    Raises
    ------
    RuntimeError
        If called before :func:`init_db` has populated
        ``_async_session_factory``. The session factory must be
        initialized at FastAPI startup before any request is
        served — typically from the ``@app.on_event("startup")``
        hook in ``src/api/main.py``.
    Exception
        Any exception raised inside the yielded block is re-raised
        after the session is rolled back, preserving the original
        exception type and traceback so FastAPI's error handlers
        (see ``src/api/middleware/error_handler.py``) can produce
        the appropriate HTTP response.
    """
    # Defensive guard. The FastAPI startup hook must invoke init_db
    # before the first request arrives; if it hasn't, fail with a
    # clear RuntimeError rather than the confusing
    # ``'NoneType' object is not callable`` that would otherwise
    # surface from the factory invocation.
    if _async_session_factory is None:
        raise RuntimeError(
            "Database session factory is not initialized. "
            "Ensure init_db() is called before get_async_session() "
            "(typically from the FastAPI startup lifecycle hook in "
            "src/api/main.py)."
        )

    # ``async with ... as session`` guarantees that the underlying
    # connection is returned to the pool on every exit path —
    # normal completion, explicit return, or uncaught exception.
    # The inner ``try/except/else`` implements the CICS SYNCPOINT
    # ROLLBACK contract (rollback on exception, commit on clean
    # exit), matching the transactional semantics of the original
    # online COBOL programs.
    async with _async_session_factory() as session:
        try:
            yield session
        except Exception:
            # Rollback the active transaction and re-raise. Any
            # uncaught exception from the calling business logic
            # reaches this handler — matches EXEC CICS SYNCPOINT
            # ROLLBACK in ``app/cbl/COACTUPC.cbl`` (around line
            # 953, in the error-handling paragraph after READ
            # UPDATE / REWRITE failure). The exception is re-raised
            # so FastAPI's error-handler middleware can map it to
            # the appropriate HTTP response.
            logger.error(
                "Exception in AsyncSession scope; rolling back transaction (CICS SYNCPOINT ROLLBACK equivalent)",
                exc_info=True,
            )
            await session.rollback()
            raise
        else:
            # Clean exit path — commit the transaction. Mirrors the
            # implicit EXEC CICS SYNCPOINT at CICS transaction end
            # in the original online programs (e.g., COACTUPC.cbl,
            # COBIL00C.cbl, COTRN02C.cbl's successful dual-write
            # finalization).
            await session.commit()


async def close_db() -> None:
    """Dispose of the async engine and release all pooled connections.

    Called from the FastAPI shutdown hook
    (``@app.on_event("shutdown")`` in ``src/api/main.py``) so that
    the ECS task exits cleanly without leaking connections to Aurora
    PostgreSQL. Analogous to ``CEMT SET FILE(ACCTDAT) CLOSED`` /
    ``CEMT SET FILE(CARDDAT) CLOSED`` / ``CEMT SET FILE(CUSTDAT)
    CLOSED`` at CICS region shutdown — but because all tables share
    one engine/pool, a single ``dispose()`` call is sufficient
    (versus the per-file CEMT CLOSE commands that the original JCL
    CLCIFIL steps issued in ``app/jcl/CARDFILE.jcl`` and
    ``app/jcl/CUSTFILE.jcl``).

    After :func:`close_db` returns, the module-level state is reset
    to ``None`` so that a subsequent :func:`init_db` call succeeds
    and creates a fresh engine. This supports pytest fixtures that
    tear down and rebuild the database between test modules.

    Idempotency
    -----------
    Safe to call even when :func:`init_db` has not been invoked
    (e.g., because startup was aborted by a failed Secrets Manager
    lookup and the shutdown hook still fires). The function logs
    a warning and returns without raising.
    """
    global _engine, _async_session_factory

    # Idempotent guard: shutdown hook invoked when startup was
    # aborted or never ran. Nothing to dispose; log and return.
    # This prevents masking the original startup failure with a
    # confusing AttributeError on ``None.dispose()``.
    if _engine is None:
        logger.warning("close_db() called when engine was never initialized; no-op")
        return

    # ``engine.dispose()`` closes all pooled connections
    # asynchronously. After dispose the engine MUST NOT be reused —
    # any further query attempt raises :class:`sqlalchemy.exc.InvalidRequestError`.
    # Callers that need a fresh engine must call :func:`init_db` again
    # (which first requires this function to reset the module state
    # to ``None`` below).
    await _engine.dispose()
    logger.info("SQLAlchemy async engine disposed; Aurora PostgreSQL connection pool released")

    # Reset module-level state so subsequent init_db() succeeds.
    # This is particularly important for test suites that exercise
    # the full lifecycle (init -> run -> close -> init again).
    _engine = None
    _async_session_factory = None
