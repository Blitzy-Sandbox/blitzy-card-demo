# ============================================================================
# tests/conftest.py — Shared pytest Fixtures for the CardDemo Test Suite
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
"""Shared pytest fixtures for the CardDemo test suite.

Provides database sessions, FastAPI test client, AWS service mocks (moto),
and factory-boy factories for all 11 entity types. Source: COBOL copybooks
(app/cpy/*.cpy) and fixture data (app/data/ASCII/*.txt) — Mainframe-to-Cloud
migration.

This is the foundational test infrastructure file for the CardDemo
modernized application. It provides shared fixtures used by ALL test
modules (unit, integration, and e2e). The fixture data patterns are
derived from the original COBOL copybook layouts:

* ``app/cpy/CVACT01Y.cpy`` — Account record layout (RECLN 300)
* ``app/cpy/CVACT02Y.cpy`` — Card record layout (RECLN 150)
* ``app/cpy/CVCUS01Y.cpy`` — Customer record layout (RECLN 500)
* ``app/cpy/CVACT03Y.cpy`` — Card cross-reference (RECLN 50)
* ``app/cpy/CVTRA01Y.cpy`` — Transaction category balance (composite PK)
* ``app/cpy/CVTRA02Y.cpy`` — Disclosure group (DEFAULT/ZEROAPR)
* ``app/cpy/CVTRA03Y.cpy`` — Transaction type (7 types)
* ``app/cpy/CVTRA04Y.cpy`` — Transaction category (18 categories)
* ``app/cpy/CVTRA05Y.cpy`` — Transaction record (RECLN 350)
* ``app/cpy/CVTRA06Y.cpy`` — Daily transaction staging (RECLN 350)
* ``app/cpy/CSUSR01Y.cpy`` — User security record (RECLN 80)
* ``app/cpy/COCOM01Y.cpy`` — COMMAREA user identity/type (JWT claims)

Mainframe-to-Cloud Infrastructure Replacements
----------------------------------------------
The fixtures in this file replace the following mainframe artifacts:

* VSAM DEFINE CLUSTER (JCL) -> ``engine`` + ``tables`` fixtures
  (in-memory SQLite or test PostgreSQL)
* CICS SYNCPOINT ROLLBACK   -> ``db_session`` fixture with SAVEPOINT
* CICS region setup         -> ``test_app`` fixture (FastAPI factory)
* CICS SEND/RECEIVE MAP     -> ``client`` fixture (httpx.AsyncClient)
* GDG (DEFGDGB.jcl)         -> ``mock_s3`` fixture (versioned bucket)
* TDQ WRITEQ JOBS           -> ``mock_sqs`` fixture (SQS FIFO queue)
* RACF credentials          -> ``mock_secrets_manager`` fixture
* CICS COMMAREA (COCOM01Y)  -> ``test_jwt_token`` / ``admin_jwt_token``
* JES2 / AWS Glue runtime   -> ``spark_session`` fixture (local Spark)

Critical Contracts
------------------
* All monetary values MUST use :class:`decimal.Decimal`, NEVER ``float``.
  This preserves COBOL ``PIC S9(n)V99`` precision per AAP §0.7.2.
* User type values MUST be exactly ``'A'`` (admin) or ``'U'`` (regular) —
  matches COBOL 88-level conditions ``CDEMO-USRTYP-ADMIN VALUE 'A'`` and
  ``CDEMO-USRTYP-USER VALUE 'U'`` from ``app/cpy/COCOM01Y.cpy``.
* Factory field names MUST match the SQLAlchemy model column names
  (which are derived from the COBOL copybook field names via the
  transformation documented in ``src/shared/models/__init__.py``).
* Numeric-looking COBOL fields (CVV, card_num, acct_id, ZIPs) are stored
  as ``String(n)`` to preserve leading zeros byte-for-byte.
"""

from __future__ import annotations

import asyncio
import json
import os
from collections.abc import AsyncIterator, Iterator
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

import boto3
import factory
import pytest
import pytest_asyncio
from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import ASGITransport, AsyncClient
from jose import jwt
from moto import mock_aws
from passlib.context import CryptContext
from pyspark.sql import SparkSession
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

# Required environment variables MUST be populated BEFORE Settings is
# instantiated anywhere in the import chain. ``src.shared.config.settings``
# declares DATABASE_URL, DATABASE_URL_SYNC, and JWT_SECRET_KEY as required
# (no defaults) and raises pydantic.ValidationError at module import time
# if they are missing. The test suite uses deterministic test values so
# JWT encoding/decoding is reproducible across test runs.
#
# Mainframe equivalent: the COBOL job cards defining SYSIN DD and the
# RACF credential grant (see app/jcl/ACCTFILE.jcl, DUSRSECJ.jcl).
os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://carddemo:testpass@localhost:5432/carddemo_test",
)
os.environ.setdefault(
    "DATABASE_URL_SYNC",
    "postgresql+psycopg2://carddemo:testpass@localhost:5432/carddemo_test",
)
os.environ.setdefault(
    "JWT_SECRET_KEY",
    "test-secret-key-DO-NOT-USE-IN-PRODUCTION-0123456789abcdef",
)
os.environ.setdefault("JWT_ALGORITHM", "HS256")
os.environ.setdefault("JWT_ACCESS_TOKEN_EXPIRE_MINUTES", "30")
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")
# moto 5.x requires mock AWS credentials to be set before any boto3 client is
# created. Without these, boto3 attempts real-AWS signing and can leak actual
# credentials from developer machines into test runs.
os.environ.setdefault("AWS_ACCESS_KEY_ID", "testing")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "testing")
os.environ.setdefault("AWS_SESSION_TOKEN", "testing")

# SQLAlchemy ORM models — Base declarative class + all 11 entity models.
# These imports also register every table on Base.metadata so that
# Base.metadata.create_all()/drop_all() in the `tables` fixture covers the
# full relational schema (replaces VSAM DEFINE CLUSTER / DELETE CLUSTER).
#
# API layer imports — FastAPI app factory, database session, DI helpers.
# The test_app fixture imports create_app() to build a fresh app instance,
# then overrides get_db and get_current_user via app.dependency_overrides
# so tests don't touch the real database or authenticate against real JWTs.
#
# Settings — Pydantic BaseSettings class reading JWT_SECRET_KEY etc. from env.
# noqa markers suppress:
#   * E402 (module-level imports not at top-of-file) — intentional: env vars
#     must be set before these modules import Settings().
#   * F401 (imported but unused) for get_async_session — imported for the
#     session-lifecycle contract reference; re-exported via __all__ below.
from src.api.database import get_async_session  # noqa: E402, F401
from src.api.dependencies import CurrentUser, get_current_user, get_db  # noqa: E402
from src.api.main import create_app  # noqa: E402
from src.shared.config.settings import Settings  # noqa: E402
from src.shared.models import Base  # noqa: E402
from src.shared.models.account import Account  # noqa: E402
from src.shared.models.card import Card  # noqa: E402
from src.shared.models.card_cross_reference import CardCrossReference  # noqa: E402
from src.shared.models.customer import Customer  # noqa: E402
from src.shared.models.daily_transaction import DailyTransaction  # noqa: E402
from src.shared.models.disclosure_group import DisclosureGroup  # noqa: E402
from src.shared.models.transaction import Transaction  # noqa: E402
from src.shared.models.transaction_category import TransactionCategory  # noqa: E402
from src.shared.models.transaction_category_balance import (  # noqa: E402
    TransactionCategoryBalance,
)
from src.shared.models.transaction_type import TransactionType  # noqa: E402
from src.shared.models.user_security import UserSecurity  # noqa: E402

# ============================================================================
# BCrypt context — used by UserSecurityFactory to hash test passwords.
# ============================================================================
# The original COBOL SEC-USR-PWD field was PIC X(08) (8-char cleartext); the
# migration hashes all passwords with BCrypt (60 chars: $2b$ + cost + $ +
# 22-char salt + 31-char hash). The CryptContext is instantiated once at
# module load and reused by every UserSecurityFactory.create() call.
_PWD_CONTEXT = CryptContext(schemes=["bcrypt"], deprecated="auto")
_DEFAULT_TEST_PASSWORD = "Test1234"  # noqa: S105 — test-only fixture password
# ============================================================================


# ============================================================================
# SECTION 1 — DATABASE FIXTURES
# ----------------------------------------------------------------------------
# These fixtures replace the VSAM persistence layer used by the original
# COBOL batch and online programs. See AAP §0.4.1 and §0.5.1 for the
# VSAM -> Aurora PostgreSQL migration mapping.
# ============================================================================


@pytest.fixture(scope="session")
def event_loop() -> Iterator[asyncio.AbstractEventLoop]:
    """Provide a session-scoped asyncio event loop.

    pytest-asyncio requires a single event loop to be shared across all
    session-scoped async fixtures (``engine``, ``tables``) so that the
    SQLAlchemy AsyncEngine and async_sessionmaker — which internally
    cache references to the loop they were created on — remain valid
    for the full duration of the test session.

    Without a session-scoped loop, pytest-asyncio's default
    ``function`` scope creates a fresh loop per test, causing cryptic
    ``RuntimeError: got Future <...> attached to a different loop``
    failures when a session-scoped async fixture is consumed by a
    function-scoped test.

    Yields
    ------
    asyncio.AbstractEventLoop
        A new event loop. Closed when the session tears down.
    """
    # Create a new event loop explicitly (asyncio.new_event_loop rather
    # than asyncio.get_event_loop()) so we do not accidentally reuse a
    # loop left over from a previous pytest session or interactive shell.
    loop = asyncio.new_event_loop()
    yield loop
    # Close the loop to release file descriptors and any pending
    # transport resources. This matches the explicit cleanup pattern
    # required by pytest-asyncio when the user overrides event_loop.
    loop.close()


@pytest.fixture(scope="session")
def engine() -> Iterator[AsyncEngine]:
    """Provide a session-scoped SQLAlchemy AsyncEngine for tests.

    Replaces VSAM DEFINE CLUSTER from app/jcl/ACCTFILE.jcl, CARDFILE.jcl,
    CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl, TCATBALF.jcl, DUSRSECJ.jcl
    — creates a single test database "cluster" that backs all 11 entity
    tables (matching the 10 VSAM KSDS + 3 AIX consolidation documented
    in AAP §0.4.1).

    The engine URL is chosen by the ``TEST_DATABASE_URL`` environment
    variable:

    * Unset (default) -> ``sqlite+aiosqlite:///:memory:`` — fast,
      isolated in-memory database for unit tests. No external
      dependencies; every test run starts with an empty schema.
    * Set -> the provided URL (e.g., a Testcontainers PostgreSQL URL
      of the form ``postgresql+asyncpg://user:pass@host:port/db``).
      Used by integration tests that need PostgreSQL-specific
      features (composite primary keys with NUMERIC precision,
      ILIKE, JSONB, Flyway migrations, etc.).

    Yields
    ------
    AsyncEngine
        A configured SQLAlchemy 2.x AsyncEngine. Disposed at
        session teardown, releasing any pooled connections.
    """
    # Read TEST_DATABASE_URL (integration test override) or fall back
    # to in-memory SQLite. The ``aiosqlite`` driver is required for
    # async SQLAlchemy operations against SQLite (stdlib sqlite3 is
    # sync-only).
    test_db_url = os.environ.get(
        "TEST_DATABASE_URL",
        "sqlite+aiosqlite:///:memory:",
    )

    # For in-memory SQLite, we MUST use a single shared connection —
    # the default NullPool creates a new connection per checkout, and
    # each new connection gets a fresh, empty ``:memory:`` database.
    # ``poolclass=StaticPool`` with ``connect_args={"check_same_thread":
    # False}`` gives us one shared connection per engine.
    if test_db_url.startswith("sqlite"):
        from sqlalchemy.pool import StaticPool

        engine_instance = create_async_engine(
            test_db_url,
            echo=False,
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
    else:
        # For real PostgreSQL (Testcontainers), use default pool
        # configuration — pool_pre_ping lets the engine recover from
        # container restarts between test runs.
        engine_instance = create_async_engine(
            test_db_url,
            echo=False,
            pool_pre_ping=True,
        )

    yield engine_instance

    # Dispose of the engine — closes all pooled connections and
    # releases the underlying driver resources. Required to prevent
    # "too many open connections" errors across long test runs.
    #
    # NOTE: AsyncEngine.dispose() is an async method. We schedule it
    # on the session-scoped event loop to avoid blocking the pytest
    # teardown phase.
    loop = asyncio.get_event_loop()
    if loop.is_closed():
        # Fallback: if the event_loop fixture already closed its loop,
        # create a transient one just for disposal.
        disposal_loop = asyncio.new_event_loop()
        try:
            disposal_loop.run_until_complete(engine_instance.dispose())
        finally:
            disposal_loop.close()
    else:
        loop.run_until_complete(engine_instance.dispose())


@pytest_asyncio.fixture(scope="session")
async def tables(engine: AsyncEngine) -> AsyncIterator[None]:
    """Create all 11 entity tables once per test session; drop at teardown.

    Replaces VSAM provisioning from app/jcl/*.jcl — specifically the
    DEFINE CLUSTER steps in ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl,
    TRANFILE.jcl, XREFFILE.jcl, TCATBALF.jcl, TRANIDX.jcl, and the
    reference-data loads in DUSRSECJ.jcl, TRANCATG.jcl, TRANTYPE.jcl,
    and DISCGRP.jcl.

    The SQLAlchemy equivalent is ``Base.metadata.create_all()``, which
    issues ``CREATE TABLE`` for every model class registered on
    ``Base.metadata``. All 11 model classes are imported at the top
    of this conftest, so importing this conftest (which pytest does
    automatically for every test under tests/) guarantees they are
    all registered before this fixture runs.

    Also creates the 3 B-tree indexes that replace the original VSAM
    AIX paths (from app/jcl/TRANIDX.jcl): ix_card_acct_id,
    ix_card_cross_reference_acct_id, ix_transaction_proc_ts. These
    are declared via ``__table_args__`` on the respective models.

    Yields
    ------
    None
        Yields control to the test session after creating tables.
        Drops all tables at session teardown.
    """
    # Create all 11 tables + 3 indexes in one transaction. The
    # run_sync wrapper is required because Base.metadata.create_all()
    # is a synchronous DDL operation issued over an async connection.
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    yield

    # Drop all tables at teardown to release any locked rows and
    # reset the schema for a subsequent test invocation (matters for
    # file-backed SQLite or real PostgreSQL; no-op for in-memory
    # SQLite whose lifetime is bounded by the engine).
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


@pytest_asyncio.fixture
async def db_session(
    engine: AsyncEngine,
    tables: None,  # noqa: ARG001 — forces table creation before session use
) -> AsyncIterator[AsyncSession]:
    """Provide a per-test AsyncSession with SAVEPOINT-based rollback.

    Session rollback mirrors CICS SYNCPOINT ROLLBACK from COACTUPC.cbl
    (account-update error path around line 953 in the original COBOL
    program). Each test gets a fresh, isolated view of the database:

    1. Open a connection from the engine pool.
    2. Begin an outer transaction on the connection (BEGIN).
    3. Open an AsyncSession bound to that connection, joined to the
       outer transaction via ``join_transaction_mode="create_savepoint"``.
    4. Yield the session to the test. Any COMMIT the test issues
       actually creates a RELEASE SAVEPOINT within the outer
       transaction — the data is never persisted beyond the test.
    5. After the test, roll back the outer transaction (ROLLBACK) —
       this discards ALL changes the test made, leaving the schema
       clean for the next test.

    The ``expire_on_commit=False`` flag matches the production
    configuration in ``src/api/database.py`` — SQLAlchemy attribute
    access on ORM objects after a commit should return cached values
    (not trigger lazy I/O), which prevents unexpected ``async I/O in
    sync context`` errors during FastAPI response serialization.

    Yields
    ------
    AsyncSession
        A configured async ORM session bound to a rolled-back
        SAVEPOINT. Transparently swallows test-level commits while
        guaranteeing the database is unchanged after the test.
    """
    # Open a connection from the engine pool and begin an outer
    # transaction on it. All test-level operations (commits included)
    # will run inside this transaction via SAVEPOINT.
    async with engine.connect() as connection:
        # Begin the outer transaction. Nothing committed inside the
        # session will survive the ROLLBACK we issue on exit.
        await connection.begin()
        # Create a session factory bound to THIS connection (not the
        # engine) — this is what enables the SAVEPOINT-on-commit
        # behavior.
        factory_: async_sessionmaker[AsyncSession] = async_sessionmaker(
            bind=connection,
            class_=AsyncSession,
            expire_on_commit=False,
            # "create_savepoint" means session.commit() becomes
            # "RELEASE SAVEPOINT" instead of a real COMMIT. The test
            # can commit freely and the data still disappears at the
            # end of the test.
            join_transaction_mode="create_savepoint",
        )
        async with factory_() as session:
            try:
                yield session
            finally:
                # Whether the test succeeded or failed, ROLLBACK the
                # outer transaction. This matches the CICS SYNCPOINT
                # ROLLBACK pattern: a test that raises triggers the
                # rollback, and a test that succeeds still has its
                # side effects rolled back so the next test starts
                # with a clean slate.
                await connection.rollback()


# ============================================================================
# SECTION 2 — FASTAPI TEST CLIENT FIXTURES
# ----------------------------------------------------------------------------
# These fixtures replace the CICS region + SEND/RECEIVE MAP pairs used by
# the original online COBOL programs. The FastAPI app is instantiated via
# create_app() and its dependencies (get_db, get_current_user) are
# overridden to inject the test database session and mock user identity.
# ============================================================================

# Test user identity constants — match COCOM01Y.cpy COMMAREA conventions.
# CDEMO-USER-ID is PIC X(08) and CDEMO-USER-TYPE is PIC X(01) with
# 88-level conditions CDEMO-USRTYP-ADMIN VALUE 'A' and
# CDEMO-USRTYP-USER VALUE 'U'.
_TEST_USER_ID = "TESTUSER"
_TEST_ADMIN_ID = "ADMIN001"
_TEST_USER_TYPE_REGULAR = "U"
_TEST_USER_TYPE_ADMIN = "A"


@pytest.fixture
def test_app(db_session: AsyncSession) -> Iterator[FastAPI]:
    """Build a FastAPI app configured for tests.

    Replaces CICS region setup — this fixture constructs a test instance
    of the API layer by calling :func:`src.api.main.create_app` and
    then replacing the production ``get_db`` and ``get_current_user``
    dependencies with test-scoped overrides:

    * ``get_db`` is overridden to yield the rolled-back test
      ``db_session`` — every route handler under test sees the same
      session and every commit inside the handler becomes a SAVEPOINT
      that is discarded at test teardown.
    * ``get_current_user`` is overridden to return a deterministic
      :class:`CurrentUser` with ``user_id="TESTUSER"`` and
      ``user_type="U"`` (regular user). Tests that need admin
      semantics should use the ``admin_client`` fixture instead
      (which further overrides ``get_current_user`` with an admin
      CurrentUser).

    The dependency overrides are registered on the app instance
    itself (via ``app.dependency_overrides``), which is the canonical
    FastAPI pattern for test dependency injection. The overrides are
    cleared after the test to avoid leaking state into other test
    modules.

    Yields
    ------
    FastAPI
        A fully configured FastAPI application with test
        dependencies injected.
    """
    # Build a fresh app instance per test so dependency overrides do
    # not accidentally leak between tests. The create_app() factory
    # is idempotent and cheap — it constructs the router graph but
    # does not touch I/O (no DB connections, no network, no Spark).
    app = create_app()

    # Override get_db to yield the rolled-back test session. The
    # override is itself an async generator so FastAPI's dependency
    # resolver treats it identically to the real get_db.
    async def _override_get_db() -> AsyncIterator[AsyncSession]:
        # Yield the existing session without opening/closing a new
        # one — the db_session fixture owns the lifecycle.
        yield db_session

    # Override get_current_user to return the standard test user.
    # Tests that need admin privileges use admin_client instead.
    async def _override_get_current_user() -> CurrentUser:
        return CurrentUser(
            user_id=_TEST_USER_ID,
            user_type=_TEST_USER_TYPE_REGULAR,
            is_admin=False,
        )

    # Register the overrides on the app instance. These are consulted
    # by FastAPI's dependency resolver before the real factories.
    app.dependency_overrides[get_db] = _override_get_db
    app.dependency_overrides[get_current_user] = _override_get_current_user

    yield app

    # Clear dependency overrides at teardown. Not strictly required
    # because the `app` instance is garbage-collected when the
    # fixture exits, but clearing makes the intent explicit and
    # defends against any module-level caching of the app instance.
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def client(
    test_app: FastAPI,
    test_jwt_token: str,
) -> AsyncIterator[AsyncClient]:
    """Provide an httpx AsyncClient bound to the test FastAPI app.

    Replaces CICS SEND/RECEIVE MAP interactions — this client issues
    HTTP requests directly against the FastAPI app's ASGI transport
    (no real TCP socket), making it both fast and deterministic for
    unit/integration tests.

    The client is pre-configured with an Authorization header carrying
    a valid test JWT (regular user, user_type='U'). Tests that need
    admin privileges should use :func:`admin_client` instead; tests
    that need unauthenticated requests can override the header by
    passing ``headers={"Authorization": ""}`` to the request.

    Yields
    ------
    AsyncClient
        An httpx AsyncClient with ``base_url="http://testserver"``
        and the test JWT Authorization header pre-set.
    """
    # ASGITransport(app=...) routes requests through the ASGI
    # callable directly, bypassing the network stack. Much faster
    # than spinning up uvicorn and hitting localhost.
    transport = ASGITransport(app=test_app)
    async with AsyncClient(
        transport=transport,
        base_url="http://testserver",
        headers={"Authorization": f"Bearer {test_jwt_token}"},
    ) as ac:
        yield ac


@pytest.fixture
def sync_client(
    test_app: FastAPI,
    test_jwt_token: str,
) -> Iterator[TestClient]:
    """Provide a synchronous FastAPI TestClient for sync tests.

    :class:`fastapi.testclient.TestClient` is backed by
    :mod:`httpx` internally but exposes a synchronous API, making
    it convenient for tests that don't need async context — e.g.,
    tests that only check HTTP status codes or response bodies.

    The client is pre-configured with a test JWT Authorization
    header (regular user, user_type='U').

    Yields
    ------
    TestClient
        A sync FastAPI TestClient with the test JWT header set.
    """
    # TestClient wraps the FastAPI app via httpx internally. It
    # handles lifespan events (startup/shutdown) automatically when
    # used as a context manager.
    with TestClient(
        test_app,
        headers={"Authorization": f"Bearer {test_jwt_token}"},
    ) as tc:
        yield tc


@pytest_asyncio.fixture
async def admin_client(
    db_session: AsyncSession,
    admin_jwt_token: str,
) -> AsyncIterator[AsyncClient]:
    """Provide an AsyncClient authenticated as an admin user.

    Unlike :func:`client`, this fixture builds its OWN FastAPI app
    instance so it can override ``get_current_user`` with an admin
    CurrentUser (``user_type='A'``, ``is_admin=True``). This maps
    to the ``CDEMO-USRTYP-ADMIN VALUE 'A'`` 88-level condition in
    COCOM01Y.cpy.

    The JWT token carried in the Authorization header also encodes
    ``user_type='A'`` so that any route that ALSO calls
    :func:`get_current_user` from a nested dependency (or reads
    the raw JWT) will see the admin identity consistently.

    Yields
    ------
    AsyncClient
        An httpx AsyncClient with admin JWT and admin identity
        dependency injected.
    """
    app = create_app()

    async def _override_get_db() -> AsyncIterator[AsyncSession]:
        yield db_session

    async def _override_get_current_user() -> CurrentUser:
        # 'A' admin — maps to CDEMO-USRTYP-ADMIN VALUE 'A' from
        # COCOM01Y.cpy. is_admin=True is the derived flag that
        # admin-guarded routes check via get_current_admin_user.
        return CurrentUser(
            user_id=_TEST_ADMIN_ID,
            user_type=_TEST_USER_TYPE_ADMIN,
            is_admin=True,
        )

    app.dependency_overrides[get_db] = _override_get_db
    app.dependency_overrides[get_current_user] = _override_get_current_user

    transport = ASGITransport(app=app)
    async with AsyncClient(
        transport=transport,
        base_url="http://testserver",
        headers={"Authorization": f"Bearer {admin_jwt_token}"},
    ) as ac:
        yield ac

    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def regular_client(
    db_session: AsyncSession,
    test_jwt_token: str,
) -> AsyncIterator[AsyncClient]:
    """Provide an AsyncClient authenticated as a regular (non-admin) user.

    Functionally equivalent to :func:`client` but expresses the
    "regular user" intent explicitly in tests that need to verify
    authorization boundaries (e.g., "this route should work for
    any user, even non-admins").

    Maps to the ``CDEMO-USRTYP-USER VALUE 'U'`` 88-level condition
    in COCOM01Y.cpy.

    Yields
    ------
    AsyncClient
        An httpx AsyncClient with regular-user JWT and regular-user
        identity dependency injected.
    """
    app = create_app()

    async def _override_get_db() -> AsyncIterator[AsyncSession]:
        yield db_session

    async def _override_get_current_user() -> CurrentUser:
        return CurrentUser(
            user_id=_TEST_USER_ID,
            user_type=_TEST_USER_TYPE_REGULAR,
            is_admin=False,
        )

    app.dependency_overrides[get_db] = _override_get_db
    app.dependency_overrides[get_current_user] = _override_get_current_user

    transport = ASGITransport(app=app)
    async with AsyncClient(
        transport=transport,
        base_url="http://testserver",
        headers={"Authorization": f"Bearer {test_jwt_token}"},
    ) as ac:
        yield ac

    app.dependency_overrides.clear()


# ============================================================================
# SECTION 3 — JWT TOKEN HELPERS
# ----------------------------------------------------------------------------
# JWT tokens replace the CICS COMMAREA (COCOM01Y.cpy) as the session
# identity carrier. Claims mirror the COMMAREA fields:
#   - user_id / sub  ← CDEMO-USER-ID   PIC X(08)
#   - user_type      ← CDEMO-USER-TYPE PIC X(01) ('A' admin, 'U' user)
#   - exp            ← Session expiration (1-hour default per AAP)
# ============================================================================


def create_test_token(user_id: str, user_type: str) -> str:
    """Generate a valid JWT for a test user.

    Reusable helper for tests that need a JWT but cannot use the
    ``test_jwt_token`` / ``admin_jwt_token`` fixtures directly
    (e.g., parametrized tests that iterate over multiple user
    types, or tests that need a deliberately expired token).

    The token is signed with ``Settings().JWT_SECRET_KEY`` using the
    configured ``Settings().JWT_ALGORITHM`` (default HS256). Claims
    match what :func:`src.api.dependencies.get_current_user` expects
    to decode:

    * ``sub`` (subject) — user_id (RFC 7519 standard claim)
    * ``user_id`` — user_id (preferred claim, falls back to ``sub``)
    * ``user_type`` — single character 'A' or 'U'
    * ``exp`` (expiration) — ``utcnow() +
      Settings().JWT_ACCESS_TOKEN_EXPIRE_MINUTES`` minutes

    Parameters
    ----------
    user_id : str
        User identifier (max 8 chars, matches SEC-USR-ID PIC X(08)
        from CSUSR01Y.cpy).
    user_type : str
        Single character: 'A' for admin, 'U' for regular user.
        Enforced by the get_current_user dependency on the server
        side — this helper does NOT validate the value so tests can
        construct malformed tokens on purpose.

    Returns
    -------
    str
        A signed JWT token as a compact string.
    """
    # Instantiate Settings lazily — tests set the required env vars
    # (DATABASE_URL, DATABASE_URL_SYNC, JWT_SECRET_KEY) at module
    # import time so this constructor never raises in the test env.
    settings = Settings()

    # utcnow() is deprecated in Python 3.12+ but still present in
    # 3.11; using timezone-aware datetime here is the forward-
    # compatible form. ``datetime.UTC`` is the 3.11+ alias for
    # ``datetime.timezone.utc`` (same singleton, shorter name).
    now = datetime.now(tz=UTC)
    expire = now + timedelta(minutes=settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES)

    payload: dict[str, Any] = {
        "sub": user_id,
        "user_id": user_id,
        "user_type": user_type,
        # jose.jwt serializes datetime to POSIX timestamps, but passing
        # an int explicitly avoids any locale/tz ambiguity.
        "exp": int(expire.timestamp()),
        "iat": int(now.timestamp()),
    }

    # jwt.encode returns a `str` in python-jose >= 3.2. Earlier
    # versions returned bytes; we're pinned to 3.3.0 so str is
    # guaranteed.
    token: str = jwt.encode(
        payload,
        settings.JWT_SECRET_KEY,
        algorithm=settings.JWT_ALGORITHM,
    )
    return token


@pytest.fixture
def test_jwt_token() -> str:
    """Generate a JWT for the default test user.

    User identity: ``user_id="TESTUSER"``, ``user_type="U"`` (regular
    user — matches CDEMO-USRTYP-USER VALUE 'U' from COCOM01Y.cpy).

    Returns
    -------
    str
        A signed JWT with 1-hour expiry (unless Settings overrides).
    """
    return create_test_token(
        user_id=_TEST_USER_ID,
        user_type=_TEST_USER_TYPE_REGULAR,
    )


@pytest.fixture
def admin_jwt_token() -> str:
    """Generate a JWT for the default admin test user.

    User identity: ``user_id="ADMIN001"``, ``user_type="A"``
    (admin — matches CDEMO-USRTYP-ADMIN VALUE 'A' from
    COCOM01Y.cpy).

    Returns
    -------
    str
        A signed JWT with admin claims and 1-hour expiry.
    """
    return create_test_token(
        user_id=_TEST_ADMIN_ID,
        user_type=_TEST_USER_TYPE_ADMIN,
    )


# ============================================================================
# SECTION 4 — MOCK AWS SERVICES (moto 5.x)
# ----------------------------------------------------------------------------
# These fixtures replace z/OS infrastructure components that have no
# direct Python analog:
#   * GDG (Generation Data Group) from DEFGDGB.jcl → S3 with versioning
#   * CICS TDQ (WRITEQ JOBS) from CORPT00C.cbl    → SQS FIFO queue
#   * RACF credentials                             → Secrets Manager
# All fixtures use moto 5.x's unified `mock_aws` context manager.
# ============================================================================

# Resource names used across AWS fixtures. Match the production
# naming conventions in src/shared/config/aws_config.py so tests
# exercise the same code paths.
_TEST_S3_BUCKET = "carddemo-test-bucket"
_TEST_SQS_QUEUE = "carddemo-reports.fifo"
_TEST_SECRET_NAME = "carddemo/db-credentials"
_TEST_AWS_REGION = "us-east-1"


@pytest.fixture
def mock_aws_services() -> Iterator[dict[str, Any]]:
    """Provide a unified AWS mock with S3, SQS, and Secrets Manager.

    Replaces z/OS infrastructure:
      * GDG → S3 (versioned bucket ``carddemo-test-bucket``)
      * TDQ → SQS FIFO (``carddemo-reports.fifo``,
              ContentBasedDeduplication enabled)
      * RACF → Secrets Manager (``carddemo/db-credentials``)

    The fixture yields a dict containing pre-created boto3 clients
    for each service plus the resource ARNs/URLs so tests can both
    consume and inspect mock resources without having to re-create
    them in every test:

    .. code-block:: python

        {
            "s3": <boto3 S3 client>,
            "sqs": <boto3 SQS client>,
            "secrets": <boto3 Secrets Manager client>,
            "bucket_name": "carddemo-test-bucket",
            "queue_url": "<moto-generated>",
            "secret_arn": "<moto-generated>",
        }

    Yields
    ------
    dict[str, Any]
        Mapping with boto3 clients and resource identifiers.
    """
    with mock_aws():
        # --- S3 (GDG replacement) ---
        s3 = boto3.client("s3", region_name=_TEST_AWS_REGION)
        s3.create_bucket(Bucket=_TEST_S3_BUCKET)
        # Enable versioning — the GDG (Generation Data Group)
        # abstraction keeps N prior generations of a dataset; S3
        # versioning is the closest analog.
        s3.put_bucket_versioning(
            Bucket=_TEST_S3_BUCKET,
            VersioningConfiguration={"Status": "Enabled"},
        )

        # --- SQS FIFO (TDQ WRITEQ JOBS replacement) ---
        sqs = boto3.client("sqs", region_name=_TEST_AWS_REGION)
        queue = sqs.create_queue(
            QueueName=_TEST_SQS_QUEUE,
            Attributes={
                "FifoQueue": "true",
                "ContentBasedDeduplication": "true",
            },
        )
        queue_url: str = queue["QueueUrl"]

        # --- Secrets Manager (RACF credential store replacement) ---
        secrets = boto3.client("secretsmanager", region_name=_TEST_AWS_REGION)
        secret_payload = {
            "username": "carddemo",
            "password": "testpass",
            "host": "localhost",
            "port": "5432",
            "dbname": "carddemo_test",
        }
        secret_resp = secrets.create_secret(
            Name=_TEST_SECRET_NAME,
            SecretString=json.dumps(secret_payload),
        )

        yield {
            "s3": s3,
            "sqs": sqs,
            "secrets": secrets,
            "bucket_name": _TEST_S3_BUCKET,
            "queue_url": queue_url,
            "secret_arn": secret_resp["ARN"],
            "secret_name": _TEST_SECRET_NAME,
        }


@pytest.fixture
def mock_s3() -> Iterator[Any]:
    """Provide a mock S3 client with a versioned test bucket.

    Isolated mock for tests that only exercise S3 functionality
    (statement output, report output, Glue script storage). Bucket
    has versioning enabled to emulate GDG behavior.

    Yields
    ------
    boto3.client
        A mocked S3 client with ``carddemo-test-bucket`` pre-created
        and versioned.
    """
    with mock_aws():
        s3 = boto3.client("s3", region_name=_TEST_AWS_REGION)
        s3.create_bucket(Bucket=_TEST_S3_BUCKET)
        # Enable versioning for GDG emulation
        s3.put_bucket_versioning(
            Bucket=_TEST_S3_BUCKET,
            VersioningConfiguration={"Status": "Enabled"},
        )
        yield s3


@pytest.fixture
def mock_sqs() -> Iterator[Any]:
    """Provide a mock SQS FIFO client for TDQ tests.

    Replaces the CICS TDQ (WRITEQ JOBS) destination from
    CORPT00C.cbl — the original program wrote a record to an
    internal transient data queue to request asynchronous report
    generation. Under the cloud architecture, this is an SQS FIFO
    queue with ``ContentBasedDeduplication`` enabled so duplicate
    report requests are silently dropped.

    Yields
    ------
    boto3.client
        A mocked SQS client with ``carddemo-reports.fifo``
        pre-created.
    """
    with mock_aws():
        sqs = boto3.client("sqs", region_name=_TEST_AWS_REGION)
        sqs.create_queue(
            QueueName=_TEST_SQS_QUEUE,
            Attributes={
                "FifoQueue": "true",
                "ContentBasedDeduplication": "true",
            },
        )
        yield sqs


@pytest.fixture
def mock_secrets_manager() -> Iterator[Any]:
    """Provide a mock Secrets Manager client with test DB credentials.

    Replaces RACF credential storage — the mock stores a JSON
    payload under ``carddemo/db-credentials`` with the fields
    expected by :func:`src.shared.config.aws_config.get_db_credentials`:

    * ``username`` — PostgreSQL user
    * ``password`` — PostgreSQL password
    * ``host`` — PostgreSQL host
    * ``port`` — PostgreSQL port
    * ``dbname`` — PostgreSQL database name

    Yields
    ------
    boto3.client
        A mocked Secrets Manager client with the test secret
        pre-populated.
    """
    with mock_aws():
        secrets = boto3.client("secretsmanager", region_name=_TEST_AWS_REGION)
        payload = {
            "username": "carddemo",
            "password": "testpass",
            "host": "localhost",
            "port": "5432",
            "dbname": "carddemo_test",
        }
        secrets.create_secret(
            Name=_TEST_SECRET_NAME,
            SecretString=json.dumps(payload),
        )
        yield secrets


# ============================================================================
# SECTION 5 — PYSPARK FIXTURES
# ----------------------------------------------------------------------------
# Replaces JES2 / AWS Glue runtime — a local SparkSession runs the batch
# PySpark jobs (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) inside
# the test process so the validation cascade, interest calculation, and
# statement generation logic can be unit-tested without a Glue cluster.
# ============================================================================


@pytest.fixture(scope="session")
def spark_session() -> Iterator[SparkSession]:
    """Provide a session-scoped local SparkSession for PySpark unit tests.

    Configuration chosen for fast, deterministic CI runs:

    * ``master("local[1]")`` — single-threaded local executor;
      avoids flaky ordering in tests that assert on output rows.
    * ``spark.ui.enabled=false`` — disables the Spark UI server
      (no port conflicts in parallel test runs).
    * ``spark.sql.shuffle.partitions=1`` — minimal shuffle so
      groupBy/join operations on the 50-row fixtures don't
      spawn dozens of empty partitions.
    * ``spark.sql.adaptive.enabled=false`` — AQE can reorder
      stages and break assertions that inspect the physical plan.
    * ``spark.driver.bindAddress=127.0.0.1`` /
      ``spark.driver.host=127.0.0.1`` — pin Spark to loopback to
      avoid DNS lookups on CI runners without a proper hostname.

    Log level is set to ERROR to keep pytest output clean; Spark's
    default INFO level emits dozens of progress lines per job.

    The session is created lazily on first use (via ``getOrCreate``)
    and torn down once at test session exit (``spark.stop()``) — this
    amortizes the 3-5s JVM startup cost across every PySpark test
    in the suite.

    Yields
    ------
    SparkSession
        A configured SparkSession ready for use with
        :meth:`SparkSession.createDataFrame`, :meth:`read`, etc.
    """
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("carddemo-unit-tests")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.sql.adaptive.enabled", "false")
        .config("spark.driver.bindAddress", "127.0.0.1")
        .config("spark.driver.host", "127.0.0.1")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("ERROR")
    yield spark
    spark.stop()


@pytest.fixture
def sample_daily_transactions_df(spark_session: SparkSession) -> Any:
    """Provide a PySpark DataFrame of daily transactions for POSTTRAN tests.

    Schema matches DALYTRAN-RECORD from CVTRA06Y.cpy (identical to
    CVTRA05Y.cpy but prefixed ``DALYTRAN-``). The fixture includes
    5 rows that together exercise the full POSTTRAN validation
    cascade from CBTRN02C.cbl:

    1. Valid transaction — passes all checks and posts successfully.
    2. Unknown card number — rejects with code ~100 (card not found
       in CardCrossReference).
    3. Missing account — card exists but linked account is missing.
    4. Over credit limit — amount exceeds available credit.
    5. Expired card — CARD-EXPIRAION-DATE is in the past.

    All ``amount`` values use :class:`Decimal` to preserve COBOL
    ``PIC S9(09)V99`` precision per AAP §0.7.2. The DataFrame is
    eagerly materialized (``createDataFrame``) so tests can both
    read and write it without triggering recomputation.

    Parameters
    ----------
    spark_session : SparkSession
        The session-scoped Spark session.

    Returns
    -------
    pyspark.sql.DataFrame
        A 5-row DataFrame with daily transaction test data.
    """
    # Import locally to avoid importing pyspark.sql.types at module
    # scope when collecting tests that don't need Spark. PySpark
    # modules are heavy.
    from pyspark.sql.types import (
        DecimalType,
        StringType,
        StructField,
        StructType,
    )

    # Schema mirrors CVTRA06Y.cpy DALYTRAN-RECORD layout. The COBOL
    # record is 350 bytes with 13 fields + FILLER; Spark retains the
    # same field names (minus the DALYTRAN- prefix) to match what
    # the DailyTransaction ORM model expects.
    schema = StructType(
        [
            StructField("tran_id", StringType(), nullable=False),
            StructField("type_cd", StringType(), nullable=False),
            StructField("cat_cd", StringType(), nullable=False),
            StructField("source", StringType(), nullable=False),
            StructField("description", StringType(), nullable=True),
            StructField("amount", DecimalType(15, 2), nullable=False),
            StructField("merchant_id", StringType(), nullable=True),
            StructField("merchant_name", StringType(), nullable=True),
            StructField("merchant_city", StringType(), nullable=True),
            StructField("merchant_zip", StringType(), nullable=True),
            StructField("card_num", StringType(), nullable=False),
            StructField("orig_ts", StringType(), nullable=True),
            StructField("proc_ts", StringType(), nullable=True),
        ]
    )

    # Five rows spanning the validation cascade. Card numbers are
    # 16-char strings (matching CARD-NUM PIC X(16)).
    rows: list[tuple[Any, ...]] = [
        # 1. Valid transaction — should post successfully.
        (
            "TXN0000000000001",
            "01",  # Purchase
            "0001",
            "POS",
            "Grocery purchase",
            Decimal("50.00"),
            "123456789",
            "Test Grocer",
            "New York",
            "10001",
            "4111111111111111",
            "2024-01-15-10.30.00.000000",
            "2024-01-15-10.30.00.000000",
        ),
        # 2. Unknown card number — no CardCrossReference row exists.
        #    POSTTRAN should reject this with a "card not found" code.
        (
            "TXN0000000000002",
            "01",
            "0001",
            "POS",
            "Unknown card",
            Decimal("25.00"),
            "123456789",
            "Test Merchant",
            "New York",
            "10001",
            "9999999999999999",  # unregistered card
            "2024-01-15-11.00.00.000000",
            "2024-01-15-11.00.00.000000",
        ),
        # 3. Missing account — card exists but linked account is gone.
        (
            "TXN0000000000003",
            "01",
            "0001",
            "POS",
            "Orphaned card",
            Decimal("75.00"),
            "123456789",
            "Test Merchant",
            "New York",
            "10001",
            "4222222222222222",  # card without live account
            "2024-01-15-12.00.00.000000",
            "2024-01-15-12.00.00.000000",
        ),
        # 4. Over credit limit — amount > available credit.
        (
            "TXN0000000000004",
            "01",
            "0001",
            "POS",
            "Over limit purchase",
            Decimal("99999.99"),  # exceeds any reasonable test limit
            "123456789",
            "Test Merchant",
            "New York",
            "10001",
            "4111111111111111",
            "2024-01-15-13.00.00.000000",
            "2024-01-15-13.00.00.000000",
        ),
        # 5. Expired card — CARD-EXPIRAION-DATE in the past.
        (
            "TXN0000000000005",
            "01",
            "0001",
            "POS",
            "Expired card use",
            Decimal("10.00"),
            "123456789",
            "Test Merchant",
            "New York",
            "10001",
            "4333333333333333",  # card with 2020-12-31 expiration
            "2024-01-15-14.00.00.000000",
            "2024-01-15-14.00.00.000000",
        ),
    ]

    return spark_session.createDataFrame(rows, schema=schema)


@pytest.fixture
def sample_accounts_df(spark_session: SparkSession) -> Any:
    """Provide a PySpark DataFrame of accounts for INTCALC tests.

    Schema matches ACCOUNT-RECORD from CVACT01Y.cpy. The fixture
    includes accounts that exercise interest calculation boundary
    conditions for CBACT04C.cbl:

    * Zero balance (no interest accrues).
    * Positive balance under credit limit (standard interest).
    * Balance at credit limit (edge case).
    * Inactive account (active_status = 'N', no interest).

    All monetary fields use :class:`Decimal` to preserve COBOL
    ``PIC S9(10)V99`` precision.

    Parameters
    ----------
    spark_session : SparkSession
        The session-scoped Spark session.

    Returns
    -------
    pyspark.sql.DataFrame
        A 4-row DataFrame with account test data.
    """
    from pyspark.sql.types import (
        DecimalType,
        StringType,
        StructField,
        StructType,
    )

    # Schema mirrors CVACT01Y.cpy ACCOUNT-RECORD layout (300-byte
    # VSAM record, 12 meaningful fields + FILLER).
    schema = StructType(
        [
            StructField("acct_id", StringType(), nullable=False),
            StructField("active_status", StringType(), nullable=False),
            StructField("curr_bal", DecimalType(15, 2), nullable=False),
            StructField("credit_limit", DecimalType(15, 2), nullable=False),
            StructField("cash_credit_limit", DecimalType(15, 2), nullable=False),
            StructField("open_date", StringType(), nullable=True),
            StructField("expiration_date", StringType(), nullable=True),
            StructField("reissue_date", StringType(), nullable=True),
            StructField("curr_cyc_credit", DecimalType(15, 2), nullable=False),
            StructField("curr_cyc_debit", DecimalType(15, 2), nullable=False),
            StructField("addr_zip", StringType(), nullable=True),
            StructField("group_id", StringType(), nullable=True),
        ]
    )

    rows: list[tuple[Any, ...]] = [
        # Zero balance — interest formula yields $0.00 regardless of rate.
        (
            "00000000001",
            "Y",
            Decimal("0.00"),
            Decimal("5000.00"),
            Decimal("1500.00"),
            "2020-01-15",
            "2030-12-31",
            "2025-01-15",
            Decimal("0.00"),
            Decimal("0.00"),
            "10001",
            "DEFAULT",
        ),
        # Positive balance, standard interest accrual.
        (
            "00000000002",
            "Y",
            Decimal("1000.00"),
            Decimal("5000.00"),
            Decimal("1500.00"),
            "2020-01-15",
            "2030-12-31",
            "2025-01-15",
            Decimal("100.00"),
            Decimal("200.00"),
            "10001",
            "DEFAULT",
        ),
        # At credit limit — boundary condition.
        (
            "00000000003",
            "Y",
            Decimal("5000.00"),
            Decimal("5000.00"),
            Decimal("1500.00"),
            "2020-01-15",
            "2030-12-31",
            "2025-01-15",
            Decimal("0.00"),
            Decimal("5000.00"),
            "10001",
            "DEFAULT",
        ),
        # Inactive — no interest accrues per CBACT04C.cbl.
        (
            "00000000004",
            "N",
            Decimal("2500.00"),
            Decimal("5000.00"),
            Decimal("1500.00"),
            "2020-01-15",
            "2030-12-31",
            "2025-01-15",
            Decimal("0.00"),
            Decimal("2500.00"),
            "10001",
            "ZEROAPR",
        ),
    ]

    return spark_session.createDataFrame(rows, schema=schema)


# ============================================================================
# SECTION 6 — FACTORY-BOY FACTORIES
# ----------------------------------------------------------------------------
# One factory per SQLAlchemy ORM model (11 total, matching the 11 VSAM
# datasets from the source mainframe). Each factory uses
# ``factory.Factory`` as its base — tests drive persistence explicitly via
# :meth:`AsyncSession.add` / :meth:`AsyncSession.commit` against the
# ``db_session`` fixture (or roll back via the SAVEPOINT pattern).
#
# CRITICAL RULES (per AAP §0.7.2):
#   * All monetary values use :class:`decimal.Decimal` — NEVER float.
#     This preserves COBOL ``PIC S9(n)V99`` precision.
#   * User type values are EXACTLY 'A' (admin) or 'U' (user) — matches
#     the COBOL 88-level conditions in CSUSR01Y.cpy.
#   * Numeric-looking COBOL fields (acct_id, card_num, cvv_cd, ssn,
#     phone_num) are stored as STRINGS to preserve leading zeros.
#   * Factory field names match the SQLAlchemy model column names (which
#     are derived from the COBOL copybook field names minus prefixes).
# ============================================================================


class AccountFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`Account` — maps to CVACT01Y.cpy (300-byte VSAM record).

    Generates account test data with all monetary fields as
    :class:`Decimal` to preserve COBOL ``PIC S9(10)V99`` precision.
    The ``acct_id`` sequence produces 11-digit zero-padded strings
    starting from "00000000001" to match the ``PIC 9(11)`` primary
    key layout.

    Defaults chosen for realistic account scenarios:
      * ``curr_bal="1000.00"`` — positive balance for interest tests
      * ``credit_limit="5000.00"`` — standard consumer card limit
      * ``cash_credit_limit="1500.00"`` — typical 30% of credit limit
      * ``active_status="Y"`` — active account (matches CBACT04C.cbl
        interest-accrual precondition)
    """

    class Meta:
        model = Account

    # 11-digit zero-padded account ID; matches ACCT-ID PIC 9(11).
    # Sequence starts at 1 and produces "00000000001", "00000000002", etc.
    acct_id = factory.Sequence(lambda n: f"{n + 1:011d}")
    active_status = "Y"
    # COBOL PIC S9(10)V99 → Numeric(15, 2). Must use Decimal.
    curr_bal = Decimal("1000.00")
    credit_limit = Decimal("5000.00")
    cash_credit_limit = Decimal("1500.00")
    # PIC X(10) date fields stored as ISO 8601 strings.
    open_date = "2020-01-15"
    expiration_date = "2030-12-31"
    reissue_date = "2025-01-15"
    curr_cyc_credit = Decimal("0.00")
    curr_cyc_debit = Decimal("0.00")
    addr_zip = "10001"
    group_id = "DEFAULT"


class CardFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`Card` — maps to CVACT02Y.cpy (150-byte VSAM record).

    Generates card test data with a 16-character ``card_num`` PK
    (matches ``CARD-NUM PIC X(16)``). The card number sequence uses
    the Visa BIN prefix "4111" followed by zero-padded sequence
    numbers so test card numbers pass Luhn-style format sanity
    checks (the actual Luhn checksum is not enforced in tests).

    The ``cvv_cd`` field is String(3) — values like "007" preserve
    leading zeros that would be lost if stored as Integer.
    """

    class Meta:
        model = Card

    # 16-char card number with Visa BIN prefix; zero-pads to 16.
    card_num = factory.Sequence(lambda n: f"4111{n + 1:012d}")
    # Link to first AccountFactory-generated account by default.
    acct_id = factory.Sequence(lambda n: f"{n + 1:011d}")
    # 3-digit CVV as STRING — preserves leading zeros.
    cvv_cd = "123"
    embossed_name = "TEST USER"
    expiration_date = "2030-12-31"
    active_status = "Y"


class CustomerFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`Customer` — maps to CVCUS01Y.cpy (500-byte VSAM).

    The ``cust_id`` sequence produces 9-digit zero-padded strings to
    match ``CUST-ID PIC 9(09)``. SSN and phone numbers are stored as
    strings to preserve leading zeros.

    Note that the SQLAlchemy model uses ``state_cd`` (not
    ``addr_state_cd``) and ``country_cd`` (not ``addr_country_cd``)
    — the ``ADDR-`` prefix from the COBOL copybook is collapsed
    into the field name. The ``addr_zip`` field retains its prefix
    because it disambiguates from the (non-existent) generic zip
    field.
    """

    class Meta:
        model = Customer

    # 9-digit zero-padded customer ID; matches CUST-ID PIC 9(09).
    cust_id = factory.Sequence(lambda n: f"{n + 1:09d}")
    first_name = "John"
    middle_name = "Q"
    last_name = "Doe"
    addr_line_1 = "123 Test St"
    addr_line_2 = ""
    addr_line_3 = ""
    # Model column is `state_cd` (not `addr_state_cd` as the AAP
    # prompt mistakenly suggested).
    state_cd = "NY"
    country_cd = "US"
    addr_zip = "10001"
    # Both phone numbers — model has phone_num_1 and phone_num_2.
    phone_num_1 = "2125551234"
    phone_num_2 = ""
    # 9-digit SSN as STRING — preserves leading zeros, avoids
    # accidental arithmetic on a sensitive field.
    ssn = "123456789"
    govt_issued_id = ""
    dob = "1990-01-15"
    eft_account_id = ""
    pri_card_holder_ind = "Y"
    fico_credit_score = 750


class CardCrossReferenceFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`CardCrossReference` — maps to CVACT03Y.cpy.

    The 50-byte VSAM record links a card number to a customer ID
    and account ID. This is what POSTTRAN (CBTRN02C.cbl) consults
    during Stage 1 of the validation cascade — if the inbound
    transaction's ``card_num`` has no row here, the transaction is
    rejected with a "card not found" reject code.

    Factory defaults align with ``AccountFactory`` / ``CardFactory``
    / ``CustomerFactory`` so the three factories produce mutually
    consistent rows when created with the same sequence seed.
    """

    class Meta:
        model = CardCrossReference

    card_num = factory.Sequence(lambda n: f"4111{n + 1:012d}")
    cust_id = factory.Sequence(lambda n: f"{n + 1:09d}")
    acct_id = factory.Sequence(lambda n: f"{n + 1:011d}")


class TransactionFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`Transaction` — maps to CVTRA05Y.cpy (350-byte).

    Produces posted transaction test data with:
      * 16-char ``tran_id`` sequence (TXN0000000000001, ...)
      * 2-char ``type_cd`` defaulting to "01" (Purchase) — matches
        the first row of the trantype.txt fixture
      * 4-char ``cat_cd`` as STRING (preserves leading zeros)
      * Decimal ``amount`` default "50.00" — realistic POS charge
      * ISO-8601 timestamps for ``orig_ts`` / ``proc_ts``

    The ``source``, ``description``, and merchant fields are
    populated with generic but realistic test values so tests that
    serialize these rows to JSON can round-trip them without
    tripping on empty-string edge cases.
    """

    class Meta:
        model = Transaction

    # 16-char transaction ID sequence. "TXN" prefix + 13-digit
    # zero-padded number yields a total of 16 characters matching
    # TRAN-ID PIC X(16).
    tran_id = factory.Sequence(lambda n: f"TXN{n + 1:013d}")
    type_cd = "01"
    # TRAN-CAT-CD PIC 9(04) → stored as String(4) to preserve
    # leading zeros (e.g., category "0001").
    cat_cd = "0001"
    source = "POS TERM"
    description = "Test purchase"
    # PIC S9(09)V99 → Decimal with 2 decimal places.
    amount = Decimal("50.00")
    merchant_id = "000000001"
    merchant_name = "Test Merchant"
    merchant_city = "New York"
    merchant_zip = "10001"
    card_num = factory.Sequence(lambda n: f"4111{n + 1:012d}")
    # PIC X(26) ISO timestamp with microseconds.
    orig_ts = "2024-01-15-10.30.00.000000"
    proc_ts = "2024-01-15-10.30.00.000000"


class TransactionCategoryBalanceFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`TransactionCategoryBalance` — CVTRA01Y.cpy.

    3-part composite primary key: ``(acct_id, type_cd, cat_cd)``.
    The balance defaults to zero so tests that exercise the
    INTCALC interest calculation can set a specific balance per
    category without worrying about stale non-zero test data.
    """

    class Meta:
        model = TransactionCategoryBalance

    # Composite PK — each part comes from the corresponding
    # parent-entity sequence.
    acct_id = factory.Sequence(lambda n: f"{n + 1:011d}")
    type_cd = "01"
    cat_cd = "0001"
    # PIC S9(09)V99 → Decimal.
    balance = Decimal("0.00")


class DailyTransactionFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`DailyTransaction` — maps to CVTRA06Y.cpy.

    The DailyTransaction staging entity has the SAME 13 fields as
    Transaction (CVTRA05Y.cpy) — the COBOL copybooks differ only
    in their field-name prefix (``TRAN-`` vs ``DALYTRAN-``). This
    factory produces test inputs for POSTTRAN (CBTRN02C.cbl).
    """

    class Meta:
        model = DailyTransaction

    tran_id = factory.Sequence(lambda n: f"DLY{n + 1:013d}")
    type_cd = "01"
    cat_cd = "0001"
    source = "POS TERM"
    description = "Daily test txn"
    amount = Decimal("25.00")
    merchant_id = "000000001"
    merchant_name = "Test Merchant"
    merchant_city = "New York"
    merchant_zip = "10001"
    card_num = factory.Sequence(lambda n: f"4111{n + 1:012d}")
    orig_ts = "2024-01-15-10.30.00.000000"
    proc_ts = "2024-01-15-10.30.00.000000"


class DisclosureGroupFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`DisclosureGroup` — maps to CVTRA02Y.cpy.

    3-part composite PK: ``(acct_group_id, tran_type_cd, tran_cat_cd)``.
    The ``int_rate`` field is ``Numeric(6, 2)`` (NOT 15, 2) — the
    COBOL source uses ``DIS-INT-RATE PIC S9(04)V99`` (6 digits total,
    2 decimals), a smaller precision than the monetary fields.

    Default ``acct_group_id`` is "DEFAULT" — matches the DEFAULT
    group from discgrp.txt which INTCALC (CBACT04C.cbl) falls back
    to when an account's ``group_id`` does not have a matching
    disclosure row. The "ZEROAPR" group (0.00% rate for all
    type/cat combinations) is available for tests of zero-interest
    accounts.
    """

    class Meta:
        model = DisclosureGroup

    # 3-part composite PK.
    acct_group_id = "DEFAULT"
    tran_type_cd = "01"
    tran_cat_cd = "0001"
    # Numeric(6, 2) — interest rate like 18.00 means 18.00% APR.
    # The formula (TRAN-CAT-BAL × DIS-INT-RATE) / 1200 implicit in
    # INTCALC yields the monthly interest accrual.
    int_rate = Decimal("18.00")


class TransactionTypeFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`TransactionType` — maps to CVTRA03Y.cpy.

    Reference data with 7 rows in the production seed (trantype.txt):
    01=Purchase, 02=Payment, 03=Adjustment, 04=Fee, 05=Credit,
    06=Return, 07=Chargeback. Factory defaults produce type "01"
    (Purchase) — the most common case.
    """

    class Meta:
        model = TransactionType

    # 2-char TRAN-TYPE PIC X(02). Sequence generates "01", "02", ...
    # but the default is "01" (Purchase) for single-row tests.
    tran_type = "01"
    description = "Purchase"


class TransactionCategoryFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`TransactionCategory` — maps to CVTRA04Y.cpy.

    2-part composite PK: ``(type_cd, cat_cd)``. Reference data with
    18 rows in trancatg.txt. Default produces the "01/0001"
    combination (the first row of the seed fixture).
    """

    class Meta:
        model = TransactionCategory

    # Composite PK: 2-char type + 4-char category as STRING
    # (preserves leading zeros in cat_cd).
    type_cd = "01"
    cat_cd = "0001"
    description = "Regular Purchase"


class UserSecurityFactory(factory.Factory):  # type: ignore[misc]
    """Factory for :class:`UserSecurity` — maps to CSUSR01Y.cpy (80-byte).

    Default generates a regular user (``usr_type="U"`` — matches
    CDEMO-USRTYP-USER VALUE 'U' from COCOM01Y.cpy). The
    ``password`` field stores a BCrypt hash — the original
    COBOL cleartext ``PIC X(08)`` has been replaced with a proper
    hashed credential as part of the security hardening for this
    migration (AAP §0.7.2 "BCrypt password hashing must be
    maintained").

    Use ``UserSecurityFactory.build(usr_type='A')`` to generate an
    admin user. The underlying plaintext password ("Test1234") is
    hashed via the module-level ``_PWD_CONTEXT`` once per factory
    invocation via :func:`factory.LazyFunction`.

    IMPORTANT: The model's primary key column is ``user_id`` (NOT
    ``usr_id`` as the AAP agent prompt suggested). The
    ``UserSecurity.__tablename__`` is ``user_security`` (singular,
    NOT pluralized).
    """

    class Meta:
        model = UserSecurity

    # 8-char user ID — matches SEC-USR-ID PIC X(08). Sequence yields
    # "USR00001", "USR00002", etc.; the default test_app/admin_app
    # fixtures use "TESTUSER" and "ADMIN001" respectively.
    user_id = factory.Sequence(lambda n: f"USR{n + 1:05d}")
    first_name = "Test"
    last_name = "User"
    # BCrypt-hashed default password ("Test1234"). LazyFunction
    # ensures a fresh hash is generated for each factory call
    # (BCrypt includes a random salt, so two calls produce two
    # different hashes even with the same plaintext).
    password = factory.LazyFunction(lambda: _PWD_CONTEXT.hash(_DEFAULT_TEST_PASSWORD))
    # 'U' = regular user (CDEMO-USRTYP-USER), 'A' = admin.
    usr_type = "U"
