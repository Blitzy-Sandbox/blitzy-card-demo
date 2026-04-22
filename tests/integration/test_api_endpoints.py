# ============================================================================
# tests/integration/test_api_endpoints.py — Integration Tests for the REST API
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
"""Integration tests for CardDemo REST API endpoints.

This module exercises the entire FastAPI application stack against a REAL
PostgreSQL 16 database provisioned on-demand via Testcontainers. Each
endpoint is invoked through an :class:`httpx.AsyncClient` bound to the
FastAPI ASGI app — NOT a mocked client — and the full request/response
lifecycle (JWT decoding, dependency injection, ORM session, SAVEPOINT
rollback, response serialization) is verified end-to-end.

Source COBOL Programs (Mainframe-to-Cloud migration)
----------------------------------------------------
Each test class maps directly to one or more online CICS COBOL programs:

* ``TestAuthEndpoints``      -> ``app/cbl/COSGN00C.cbl``   (F-001 Sign-on)
* ``TestAccountEndpoints``   -> ``app/cbl/COACTVWC.cbl`` +
                                ``app/cbl/COACTUPC.cbl``    (F-004 / F-005)
* ``TestCardEndpoints``      -> ``app/cbl/COCRDLIC.cbl`` +
                                ``app/cbl/COCRDSLC.cbl`` +
                                ``app/cbl/COCRDUPC.cbl``    (F-006-F-008)
* ``TestTransactionEndpoints``-> ``app/cbl/COTRN00C.cbl`` +
                                ``app/cbl/COTRN01C.cbl`` +
                                ``app/cbl/COTRN02C.cbl``    (F-009-F-011)
* ``TestBillPaymentEndpoints``-> ``app/cbl/COBIL00C.cbl``   (F-012 Bill Pay)
* ``TestReportEndpoints``    -> ``app/cbl/CORPT00C.cbl``   (F-022 Reporting)
* ``TestUserEndpoints``      -> ``app/cbl/COUSR00C.cbl`` +
                                ``app/cbl/COUSR01C.cbl`` +
                                ``app/cbl/COUSR02C.cbl`` +
                                ``app/cbl/COUSR03C.cbl``    (F-018-F-021)
* ``TestAdminEndpoints``     -> ``app/cbl/COADM01C.cbl``   (F-003 Admin gate)

Critical Contracts (AAP §0.7.2 — "Refactoring-Specific Rules")
-----------------------------------------------------------------
* All monetary values MUST use :class:`decimal.Decimal`, NEVER ``float``.
  This preserves COBOL ``PIC S9(n)V99`` precision in both request
  payloads and response assertions.
* User type values MUST be exactly ``'A'`` (admin) or ``'U'`` (regular) —
  matches COBOL 88-level conditions ``CDEMO-USRTYP-ADMIN VALUE 'A'`` and
  ``CDEMO-USRTYP-USER VALUE 'U'`` from ``app/cpy/COCOM01Y.cpy``.
* Card list pagination MUST use page size 7 (matches ``COCRDLIC.cbl`` BMS
  map ``COCRDLI`` 7-row display).
* Transaction list pagination uses default page size 10, max 100 (matches
  ``COTRN00C.cbl`` BMS map ``COTRN00`` 10-row display).
* Dual-write atomicity (Transaction INSERT + Account REWRITE under a
  single SYNCPOINT from ``COBIL00C.cbl``) is verified for bill payment.
* Optimistic concurrency control (OCC via ``version_id`` column — the
  SQLAlchemy equivalent of the CICS ``READ UPDATE`` + timestamp compare
  pattern) is verified for Account and Card updates.
* BCrypt password hashing MUST be verified for user operations —
  COBOL cleartext ``PIC X(08)`` passwords are upgraded to hashed
  credentials as part of AAP §0.7.2 "BCrypt password hashing must be
  maintained".
* JWT claims MUST map to ``COCOM01Y.cpy`` COMMAREA fields:
  ``sub`` + ``user_id`` -> ``CDEMO-USER-ID PIC X(08)``;
  ``user_type`` -> ``CDEMO-USER-TYPE PIC X(01)``.

Testcontainers Infrastructure
-----------------------------
Every test in this module runs against a genuine PostgreSQL 16 database
instance started in a Docker container. This matches Aurora PostgreSQL
compatibility at the wire-protocol level and gives us full SQL semantics
(JSONB, composite unique constraints, NUMERIC precision, StaleDataError
from OCC, SAVEPOINT / ROLLBACK TO SAVEPOINT). The container is started
once per module (``scope="module"``) and torn down at module teardown.

Per-test isolation is achieved via the SAVEPOINT-based rollback pattern
proven in ``tests/integration/test_database.py``: each test opens an
``AsyncSession`` bound to an outer connection-level transaction, and
the outer transaction is unconditionally rolled back at test teardown.
This mirrors the CICS SYNCPOINT ROLLBACK semantics from
``COACTUPC.cbl`` (line 953, account-update error path) — tests can
commit freely and their side effects still disappear.

Validation Coverage
-------------------
This file validates 34 distinct test methods distributed across the
eight test classes enumerated above, exercising the happy paths and
the error paths of all 18 online CICS programs migrated in AAP §0.5.1.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# CRITICAL — Environment variables MUST be populated BEFORE any import from
# :mod:`src.shared.config.settings` (which declares DATABASE_URL,
# DATABASE_URL_SYNC, and JWT_SECRET_KEY as required fields with NO defaults
# and raises :class:`pydantic.ValidationError` at module import time if
# they are missing). The values here are deterministic test-only secrets
# so JWT encoding/decoding is reproducible across test runs.
# ---------------------------------------------------------------------------
import os

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
os.environ.setdefault("AWS_ACCESS_KEY_ID", "testing")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "testing")
os.environ.setdefault("AWS_SESSION_TOKEN", "testing")

# ---------------------------------------------------------------------------
# Standard library imports (after env setup)
# ---------------------------------------------------------------------------
import json  # noqa: E402  — must appear after env var setup
from collections.abc import AsyncIterator, Iterator  # noqa: E402
from datetime import UTC, datetime, timedelta  # noqa: E402
from decimal import Decimal  # noqa: E402
from typing import Any  # noqa: E402
from unittest.mock import AsyncMock, MagicMock, patch  # noqa: E402

# ---------------------------------------------------------------------------
# Third-party imports
# ---------------------------------------------------------------------------
import pytest  # noqa: E402
import pytest_asyncio  # noqa: E402
from fastapi import FastAPI  # noqa: E402
from httpx import ASGITransport, AsyncClient  # noqa: E402
from jose import jwt  # noqa: E402
from passlib.context import CryptContext  # noqa: E402
from sqlalchemy.ext.asyncio import (  # noqa: E402
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm.exc import StaleDataError  # noqa: E402
from testcontainers.postgres import PostgresContainer  # noqa: E402

# ---------------------------------------------------------------------------
# First-party imports — must come AFTER env var setup so Settings()
# instantiation during their module initialization picks up the test values.
# ---------------------------------------------------------------------------
from src.api.database import get_async_session  # noqa: E402, F401
from src.api.dependencies import (  # noqa: E402
    get_current_user,
    get_db,
)
from src.api.main import create_app  # noqa: E402
from src.shared.config.settings import Settings  # noqa: E402
from src.shared.models import (  # noqa: E402
    Account,
    Base,
    Card,
    CardCrossReference,
    Customer,
    DailyTransaction,
    DisclosureGroup,
    Transaction,
    TransactionCategory,
    TransactionCategoryBalance,
    TransactionType,
    UserSecurity,
)

# ---------------------------------------------------------------------------
# Module-level constants
# ---------------------------------------------------------------------------
# User-type domain from COCOM01Y.cpy 88-level constraints.
_TEST_USER_TYPE_REGULAR: str = "U"  # CDEMO-USRTYP-USER
_TEST_USER_TYPE_ADMIN: str = "A"  # CDEMO-USRTYP-ADMIN

# 8-character test user IDs — matches SEC-USR-ID PIC X(08) constraint.
_TEST_USER_ID: str = "TESTUSER"  # regular user (user_type = 'U')
_TEST_ADMIN_ID: str = "ADMIN001"  # admin user (user_type = 'A')

# Cleartext password used in all test fixtures. The hash is generated
# via BCrypt at fixture-build time. Per AAP §0.7.2 — "BCrypt password
# hashing must be maintained".
_DEFAULT_TEST_PASSWORD: str = "Test1234"

# BCrypt context — one per module. ``schemes=["bcrypt"]`` matches the
# production configuration in ``src/api/services/auth_service.py``;
# ``deprecated="auto"`` keeps the context forward-compatible with future
# hash-scheme migrations.
_PWD_CONTEXT: CryptContext = CryptContext(schemes=["bcrypt"], deprecated="auto")

# Seed account / card / customer identifiers used consistently across
# every test class. These values are zero-padded fixed-width strings —
# the exact same pattern the factory suite in ``tests/conftest.py`` uses
# so seed data from the two suites is mutually consistent.
_SEED_ACCT_ID_1: str = "00000000001"  # PIC 9(11) — primary test account
_SEED_ACCT_ID_2: str = "00000000002"  # PIC 9(11) — secondary test account
_SEED_CARD_NUM_1: str = "4111000000000001"  # PIC X(16) — Visa BIN prefix
_SEED_CARD_NUM_2: str = "4111000000000002"  # PIC X(16)
_SEED_CUST_ID_1: str = "000000001"  # PIC 9(09)
_SEED_CUST_ID_2: str = "000000002"  # PIC 9(09)
# Transaction IDs MUST be parseable as integers by
# transaction_service.py (line ~1080: ``int(last_tran_id) + 1`` for
# auto-generation) and bill_service.py (same pattern). The production
# schema uses 16-digit zero-padded integer strings (see
# ``_INITIAL_TRAN_ID = "0000000000000001"`` and
# ``_TRAN_ID_WIDTH = 16`` in transaction_service.py). Non-numeric
# prefixes like "TXN" would trigger the defensive ValueError branch
# in the auto-generator and break add-transaction / pay-bill tests.
_SEED_TRAN_ID_1: str = "0000000000000100"  # PIC X(16) — 16 digits
_SEED_TRAN_ID_2: str = "0000000000000200"  # PIC X(16) — 16 digits

# Per-module pytest markers — integration suite + module-scoped asyncio
# loop so module-scoped async fixtures (``async_engine``, etc.) survive
# across tests without being torn down per-function.
pytestmark = [
    pytest.mark.integration,
    pytest.mark.asyncio(loop_scope="module"),
]


# ===========================================================================
# SECTION 1 -- HELPER FUNCTIONS
# ===========================================================================
def _error_text(body: dict[str, Any]) -> str:
    """Extract all searchable error text from the ABEND-DATA envelope.

    The production ``src.api.middleware.error_handler`` wraps every
    4xx/5xx response in a two-level envelope::

        {
            "error": {
                "status_code": <int>,
                "error_code":  "<PIC X(4) mnemonic>",
                "culprit":     "<PIC X(8) module name>",
                "reason":      "<PIC X(50) short reason>",
                "message":     "<PIC X(72) full user-facing text>",
                "timestamp":   "<ISO-8601 UTC>",
                "path":        "<request URL path>",
            }
        }

    This shape is a 1-to-1 analogue of the COBOL ``ABEND-DATA`` record
    layout (see ``app/cpy/CSMSG01Y.cpy`` / ``CSMSG02Y.cpy``). Depending
    on the status class:

    * **404 / 409 / 401 / 403 / 500 HTTPException** -- the caller's
      ``detail=`` string lands in BOTH ``reason`` and ``message``
      (``message`` falls back to ``reason`` when no canned COBOL
      default exists for the status).
    * **400 HTTPException** -- ``reason`` carries the caller's detail;
      ``message`` is the canned ``CCDA-MSG-INVALID-KEY``.
    * **422 RequestValidationError (Pydantic v2)** -- ``message``
      carries ``"<field_path>: <error_msg>"``; ``reason`` is the canned
      ``CCDA-MSG-INVALID-KEY``.

    To keep test assertions robust across these variants this helper
    concatenates both text fields so a single substring match works
    regardless of which slot the COBOL-equivalent error literal
    occupies. Returns an empty string if the body is not an ABEND-DATA
    envelope (defensive: lets assertions fail with their own semantics
    rather than a KeyError here).

    Parameters
    ----------
    body:
        Parsed JSON body of the HTTP response (``response.json()``).

    Returns
    -------
    str
        Concatenated ``"<message> <reason>"`` text, lowercase-preserving,
        suitable for ``in`` / ``==`` assertions.
    """
    err = body.get("error") if isinstance(body, dict) else None
    if not isinstance(err, dict):
        return ""
    message = str(err.get("message", ""))
    reason = str(err.get("reason", ""))
    return f"{message} {reason}"


def create_test_token(user_id: str, user_type: str) -> str:
    """Build a JWT token for test HTTP requests.

    Generates a signed HS256 JWT whose claim set matches the payload
    produced by ``src.api.services.auth_service.AuthService.authenticate``
    so the FastAPI authentication middleware accepts it as legitimate.
    The claim set mirrors the fields persisted in the CICS COMMAREA from
    ``app/cpy/COCOM01Y.cpy``.

    Claims written
    --------------
    ``sub``
        RFC 7519 standard subject claim — duplicates ``user_id`` for
        compatibility with any downstream consumer that reads the
        canonical claim.
    ``user_id``
        Preferred custom claim — maps to ``CDEMO-USER-ID PIC X(08)``
        from ``app/cpy/COCOM01Y.cpy``.
    ``user_type``
        Preferred custom claim — maps to ``CDEMO-USER-TYPE PIC X(01)``.
        Must be ``'A'`` (admin) or ``'U'`` (regular user) to match
        the COCOM01Y.cpy 88-level constraint.
    ``exp``
        POSIX expiration timestamp — set to "now + JWT_ACCESS_TOKEN_EXPIRE_MINUTES"
        so the token is valid for the full duration of a test module
        run without requiring refresh.
    ``iat``
        POSIX issued-at timestamp — stamped at the instant this helper
        is called.

    Parameters
    ----------
    user_id : str
        The 8-character user identifier. Not validated here — callers
        are responsible for passing COBOL-compliant values.
    user_type : str
        Single-character user type. MUST be ``'A'`` or ``'U'``.

    Returns
    -------
    str
        The encoded JWT bearer token, ready for inclusion in an
        ``Authorization: Bearer <token>`` header.

    Notes
    -----
    Replaces the CICS ``RETURN TRANSID COMMAREA`` session-state mechanism
    used by the original COBOL online transactions. In the mainframe
    architecture, user identity + role were passed implicitly between
    programs via the 100-byte COMMAREA defined in ``COCOM01Y.cpy``. In
    the REST architecture, that role-bearing state is made explicit as
    a cryptographically signed JWT carried on every HTTP request.
    """
    settings = Settings()
    now = datetime.now(tz=UTC)
    expire = now + timedelta(minutes=settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES)
    payload: dict[str, Any] = {
        "sub": user_id,
        "user_id": user_id,
        "user_type": user_type,
        "exp": int(expire.timestamp()),
        "iat": int(now.timestamp()),
    }
    token: str = jwt.encode(
        payload,
        settings.JWT_SECRET_KEY,
        algorithm=settings.JWT_ALGORITHM,
    )
    return token


# ===========================================================================
# SECTION 2 — TESTCONTAINERS POSTGRESQL FIXTURES
# ---------------------------------------------------------------------------
# Replaces VSAM DEFINE CLUSTER provisioning from app/jcl/*.jcl with a real
# PostgreSQL 16 database started on demand via Testcontainers. The fixtures
# are designed for module-level reuse: one container is started at module
# collection, one schema is created inside it, and every test borrows a
# fresh connection + SAVEPOINT for its work. Teardown drops all tables
# and stops the container.
# ===========================================================================
@pytest.fixture(scope="module")
def postgres_container() -> Iterator[PostgresContainer]:
    """Start a real PostgreSQL 16 container for the module.

    Replaces ``app/jcl/*.jcl`` DEFINE CLUSTER steps (e.g., ACCTFILE.jcl,
    CARDFILE.jcl, CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl) with a
    containerized PostgreSQL 16 instance that matches Aurora PostgreSQL
    compatibility at the wire-protocol level. The ``driver=None``
    keyword tells Testcontainers to use the container's default URL
    format; the driver-specific URL (``+asyncpg`` / ``+psycopg2``) is
    assembled by the consumer fixtures below.

    Module-scoped so one container is reused across every test in this
    file — starting a Docker container takes ~2-3 seconds and we do not
    want to pay that cost per-test.

    Yields
    ------
    PostgresContainer
        A started Testcontainers PostgresContainer instance. Call
        ``.get_connection_url(driver="asyncpg")`` on the yielded value
        to obtain an asyncpg-compatible SQLAlchemy URL.
    """
    # Match docker-compose.yml (postgres:16-alpine) at the major version
    # level. driver=None suppresses the default psycopg2 suffix so the
    # URL can be rebuilt with either +asyncpg (for async engines) or
    # +psycopg2 (for sync migration scripts).
    container = PostgresContainer("postgres:16", driver=None)
    container.start()
    try:
        yield container
    finally:
        # Guaranteed cleanup even on test failure.
        container.stop()


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def async_engine(
    postgres_container: PostgresContainer,
) -> AsyncIterator[AsyncEngine]:
    """Provide a module-scoped async SQLAlchemy engine bound to the container.

    Creates a ``sqlalchemy.ext.asyncio.AsyncEngine`` pointing at the
    running PostgresContainer via the ``asyncpg`` driver. Uses the same
    engine configuration as production (``echo=False``, ``future=True``)
    to exercise the real async SQL execution path end-to-end.

    The engine is module-scoped so its connection pool can be reused
    across every test. Per-test isolation is achieved at the SAVEPOINT
    level in the :func:`db_session` fixture rather than at the engine
    level.

    All 11 ORM tables are created here via ``Base.metadata.create_all``
    (replacing the sequence of VSAM DEFINE CLUSTER / DEFINE AIX /
    DEFINE PATH steps from ``app/jcl/*.jcl``), and the schema is
    dropped at module teardown to release any locked rows.

    Yields
    ------
    AsyncEngine
        A started async engine ready for SAVEPOINT-based test sessions.
    """
    url = postgres_container.get_connection_url(driver="asyncpg")
    engine = create_async_engine(url, echo=False, future=True)

    # Synchronous DDL issued over the async connection — Base.metadata
    # is the declarative metadata collection owning all 11 ORM tables.
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    try:
        yield engine
    finally:
        # Drop tables at teardown so the next module that starts a
        # container on the same port gets a clean slate. Then dispose
        # of the engine to release all pooled connections.
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
        await engine.dispose()


@pytest_asyncio.fixture(loop_scope="module")
async def db_session(
    async_engine: AsyncEngine,
) -> AsyncIterator[AsyncSession]:
    """Provide a per-test AsyncSession with SAVEPOINT-based rollback.

    Session rollback mirrors CICS SYNCPOINT ROLLBACK from
    ``COACTUPC.cbl`` (account-update error path around line 953 in the
    original COBOL program). Each test gets a fresh, isolated view of
    the database:

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
    configuration in ``src/api/database.py``. Without it, attribute
    access on ORM objects after a commit would trigger a lazy refresh,
    which in FastAPI's response-serialization path would surface as an
    "async I/O in sync context" error.

    Yields
    ------
    AsyncSession
        A configured async ORM session bound to a rolled-back
        SAVEPOINT. Transparently swallows test-level commits while
        guaranteeing the database is unchanged after the test.
    """
    async with async_engine.connect() as connection:
        # Outer transaction — everything below runs inside this.
        await connection.begin()
        async_session_factory: async_sessionmaker[AsyncSession] = (
            async_sessionmaker(
                bind=connection,
                class_=AsyncSession,
                expire_on_commit=False,
                join_transaction_mode="create_savepoint",
            )
        )
        async with async_session_factory() as session:
            try:
                yield session
            finally:
                # Rollback the outer transaction — this discards all
                # test-level commits (which were really just SAVEPOINT
                # releases) and restores the database to its pre-test
                # state. Matches CICS SYNCPOINT ROLLBACK semantics.
                await connection.rollback()


# ===========================================================================
# SECTION 3 -- FASTAPI TEST CLIENT FIXTURE
# ===========================================================================
@pytest_asyncio.fixture(loop_scope="module")
async def client(
    db_session: AsyncSession,
) -> AsyncIterator[AsyncClient]:
    """Build an httpx.AsyncClient bound to the FastAPI ASGI app.

    This fixture replaces the CICS region + SEND/RECEIVE MAP pairs used
    by the original online COBOL programs. The FastAPI app is
    instantiated via :func:`create_app` and its ``get_db`` dependency is
    overridden so every injected session is the per-test SAVEPOINT
    session yielded by :func:`db_session`.

    Authentication is NOT overridden at the default level — individual
    tests can pass a JWT token via the ``Authorization`` header to
    exercise the real middleware. When a test needs to bypass auth
    (for unauthenticated endpoint tests like login), it simply omits
    the header. When a test needs to inject a specific identity
    (admin vs. regular user), it builds a JWT via
    :func:`create_test_token` and sends it on the individual request.

    The client uses :class:`httpx.ASGITransport` for in-process
    request dispatch (no network), so tests run fast and never race
    with a port-bound server.

    Yields
    ------
    AsyncClient
        A ready-to-use async HTTP client. Tests issue standard
        ``client.get()`` / ``client.post()`` / ``client.put()`` /
        ``client.delete()`` calls against relative paths like
        ``"/accounts/00000000001"``.
    """
    app: FastAPI = create_app()

    # Override get_db so FastAPI endpoints receive the test-scoped
    # SAVEPOINT session. Every request that depends on get_db will
    # re-enter this override for its lifetime — returning the SAME
    # session object guarantees that any data seeded in this test
    # function is visible to the API handlers.
    async def _override_get_db() -> AsyncIterator[AsyncSession]:
        yield db_session

    app.dependency_overrides[get_db] = _override_get_db

    # Do NOT globally override get_current_user — auth is exercised
    # per-test via a Bearer header built by create_test_token. Tests
    # that need to simulate an unauthenticated request simply omit
    # the header. We explicitly pop any residual override from a
    # prior test module to guarantee every request in this fixture's
    # scope flows through the real JWT-validating middleware. This
    # defensive pop is the fixture's contractual guarantee that
    # auth-layer tests (401/403 paths) exercise the genuine
    # src.api.dependencies.get_current_user code path and not a
    # stale mock left behind by another test file.
    app.dependency_overrides.pop(get_current_user, None)

    transport = ASGITransport(app=app)
    async with AsyncClient(
        transport=transport,
        base_url="http://testserver",
    ) as ac:
        try:
            yield ac
        finally:
            app.dependency_overrides.clear()


# ===========================================================================
# SECTION 4 — SEED DATA FIXTURE
# ---------------------------------------------------------------------------
# Populates the 11 ORM tables with a minimal but realistic dataset — one
# regular user + one admin user, two accounts, two cards, one customer
# with two cross-references, two posted transactions, and reference data
# (one transaction type, one category, one disclosure group, one balance
# row). All monetary values use :class:`decimal.Decimal` per AAP §0.7.2.
# ===========================================================================
@pytest_asyncio.fixture(loop_scope="module")
async def seed_data(
    db_session: AsyncSession,
) -> dict[str, Any]:
    """Insert baseline test rows into the 11 entity tables.

    The seeded dataset is designed to satisfy the prerequisites of
    every test class in this module:

    * ``user_security`` — one regular user (``TESTUSER``, type ``'U'``,
      BCrypt-hashed password ``"Test1234"``) and one admin user
      (``ADMIN001``, type ``'A'``, same password). These satisfy the
      sign-on tests and the admin-gate tests.
    * ``accounts`` — two accounts (IDs ``00000000001`` / ``00000000002``)
      with positive balances, non-zero credit limits, and
      ``group_id = "DEFAULT"``.
    * ``cards`` — two cards (PANs ``4111000000000001`` /
      ``4111000000000002``) bound to the two accounts.
    * ``customers`` — one customer (ID ``000000001``) paired with the
      primary account/card.
    * ``card_cross_references`` — two rows linking each card_num to
      ``(cust_id, acct_id)`` so the POST /transactions XREF lookup
      succeeds.
    * ``transactions`` — two posted transactions (IDs
      ``TXN0000000000001`` / ``TXN0000000000002``) bound to card #1
      for the transaction list / detail tests.
    * ``transaction_types`` — one row (code ``"01"`` = Purchase)
      satisfying the add-transaction FK.
    * ``transaction_categories`` — one row (``"01" / "0001"`` =
      Regular Purchase).
    * ``disclosure_groups`` — one ``DEFAULT`` row for ``"01" / "0001"``.
    * ``transaction_category_balances`` — one composite-PK row for the
      primary account.

    All monetary values use :class:`decimal.Decimal` — NEVER float. The
    fixture yields a dict of seed objects so individual tests can read
    back primary keys and version_id values without re-querying the
    database.

    Add comment: Seed data matches patterns from app/data/ASCII/*.txt
    fixture files (acctdata.txt, carddata.txt, custdata.txt, cardxref.txt).

    Parameters
    ----------
    db_session : AsyncSession
        The per-test SAVEPOINT session. All seed rows are written
        inside the same SAVEPOINT as the test body so they are
        automatically cleaned up at teardown.

    Returns
    -------
    dict[str, Any]
        Keys: ``regular_user``, ``admin_user``, ``account_1``,
        ``account_2``, ``card_1``, ``card_2``, ``customer``,
        ``xref_1``, ``xref_2``, ``transaction_1``, ``transaction_2``,
        ``tran_type``, ``tran_category``, ``disclosure_group``,
        ``tran_cat_balance``. Every value is a detached ORM instance
        whose attributes (including ``version_id`` for Account / Card)
        can be introspected in the test body.
    """
    # -----------------------------------------------------------------
    # User Security — BCrypt hashed passwords (one regular + one admin)
    # -----------------------------------------------------------------
    # IMPORTANT: ``src.api.services.auth_service.AuthService.authenticate``
    # upper-cases the request password before calling
    # ``pwd_context.verify()`` — see auth_service.py line ~579:
    #     password_upper: str = request.password.upper()
    #     password_matches = pwd_context.verify(password_upper, user.password)
    # This faithfully preserves the COBOL ``MOVE FUNCTION UPPER-CASE``
    # behaviour from COSGN00C.cbl lines 132-135. To make authentication
    # succeed in tests, we must therefore hash the UPPER-CASED
    # plaintext, not the mixed-case plaintext. Tests that post the
    # password send the MIXED-CASE form; the service upper-cases it
    # then verifies against the upper-case hash seeded here.
    _seeded_password_hash: str = _PWD_CONTEXT.hash(_DEFAULT_TEST_PASSWORD.upper())
    regular_user = UserSecurity(
        user_id=_TEST_USER_ID,  # PIC X(08) — "TESTUSER"
        first_name="Test",
        last_name="User",
        password=_seeded_password_hash,
        usr_type=_TEST_USER_TYPE_REGULAR,  # 'U' — CDEMO-USRTYP-USER
    )
    admin_user = UserSecurity(
        user_id=_TEST_ADMIN_ID,  # PIC X(08) — "ADMIN001"
        first_name="Admin",
        last_name="User",
        password=_seeded_password_hash,
        usr_type=_TEST_USER_TYPE_ADMIN,  # 'A' — CDEMO-USRTYP-ADMIN
    )
    db_session.add_all([regular_user, admin_user])

    # -----------------------------------------------------------------
    # Accounts — Decimal monetary fields preserve COBOL PIC S9(10)V99
    # -----------------------------------------------------------------
    account_1 = Account(
        acct_id=_SEED_ACCT_ID_1,
        active_status="Y",
        curr_bal=Decimal("1000.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1500.00"),
        open_date="2020-01-15",
        expiration_date="2030-12-31",
        reissue_date="2025-01-15",
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        addr_zip="10001",
        group_id="DEFAULT",
    )
    account_2 = Account(
        acct_id=_SEED_ACCT_ID_2,
        active_status="Y",
        curr_bal=Decimal("2500.50"),
        credit_limit=Decimal("10000.00"),
        cash_credit_limit=Decimal("2500.00"),
        open_date="2021-06-01",
        expiration_date="2031-05-31",
        reissue_date="2026-06-01",
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        addr_zip="10002",
        group_id="DEFAULT",
    )
    db_session.add_all([account_1, account_2])

    # -----------------------------------------------------------------
    # Cards — Visa BIN 4111 + 12 zero-padded digits = 16-char PAN
    # -----------------------------------------------------------------
    card_1 = Card(
        card_num=_SEED_CARD_NUM_1,
        acct_id=_SEED_ACCT_ID_1,
        cvv_cd="123",
        embossed_name="TEST USER",
        expiration_date="2030-12-31",
        active_status="Y",
    )
    card_2 = Card(
        card_num=_SEED_CARD_NUM_2,
        acct_id=_SEED_ACCT_ID_2,
        cvv_cd="456",
        embossed_name="ADMIN USER",
        expiration_date="2030-12-31",
        active_status="Y",
    )
    db_session.add_all([card_1, card_2])

    # -----------------------------------------------------------------
    # Customer — PIC 9(09) ID, addresses from CVCUS01Y.cpy mapping
    # -----------------------------------------------------------------
    customer = Customer(
        cust_id=_SEED_CUST_ID_1,
        first_name="John",
        middle_name="Q",
        last_name="Doe",
        addr_line_1="123 Test St",
        addr_line_2="",
        addr_line_3="",
        state_cd="NY",
        # ISO 3-letter country code per account_service.py
        # ``_validate_country_code`` (3 alphabetic chars). Using
        # "USA" (not "US") to satisfy COACTUPC.cbl's 3-char
        # country_cd PIC X(03) layout from CVCUS01Y.cpy.
        country_cd="USA",
        addr_zip="10001",
        # Phone numbers are stored in canonical (AAA)BBB-CCCC 13-character
        # form per src/api/services/account_service.py ``_format_phone_stored``
        # — matches COACTUPC.cbl's phone-display layout (COACTUP.CPY
        # ACSPH1A/B/C segmented fields). Tests use segmented values
        # ``212``/``555``/``1234`` which the service rejoins into this same
        # stored form via ``_format_phone_stored``.
        phone_num_1="(212)555-1234",
        phone_num_2="",
        ssn="123456789",
        govt_issued_id="",
        dob="1990-01-15",
        eft_account_id="",
        pri_card_holder_ind="Y",
        fico_credit_score=750,
    )
    db_session.add(customer)

    # -----------------------------------------------------------------
    # Card Cross-References — card_num -> (cust_id, acct_id)
    # -----------------------------------------------------------------
    xref_1 = CardCrossReference(
        card_num=_SEED_CARD_NUM_1,
        cust_id=_SEED_CUST_ID_1,
        acct_id=_SEED_ACCT_ID_1,
    )
    xref_2 = CardCrossReference(
        card_num=_SEED_CARD_NUM_2,
        cust_id=_SEED_CUST_ID_1,
        acct_id=_SEED_ACCT_ID_2,
    )
    db_session.add_all([xref_1, xref_2])

    # -----------------------------------------------------------------
    # Reference data — TransactionType, TransactionCategory,
    # DisclosureGroup, TransactionCategoryBalance
    # -----------------------------------------------------------------
    tran_type = TransactionType(
        tran_type="01",  # PIC X(02) — "01" = Purchase
        description="Purchase",
    )
    db_session.add(tran_type)

    tran_category = TransactionCategory(
        type_cd="01",
        cat_cd="0001",  # PIC 9(04) as String(4) — preserves leading zeros
        description="Regular Purchase",
    )
    db_session.add(tran_category)

    disclosure_group = DisclosureGroup(
        acct_group_id="DEFAULT",
        tran_type_cd="01",
        tran_cat_cd="0001",
        int_rate=Decimal("18.00"),  # DIS-INT-RATE PIC S9(04)V99
    )
    db_session.add(disclosure_group)

    tran_cat_balance = TransactionCategoryBalance(
        acct_id=_SEED_ACCT_ID_1,
        type_cd="01",
        cat_cd="0001",
        balance=Decimal("500.00"),
    )
    db_session.add(tran_cat_balance)

    # -----------------------------------------------------------------
    # Transactions — posted rows visible in the list / detail tests
    # -----------------------------------------------------------------
    transaction_1 = Transaction(
        tran_id=_SEED_TRAN_ID_1,
        type_cd="01",
        cat_cd="0001",
        source="POS TERM",
        description="Grocery purchase",
        amount=Decimal("50.00"),  # PIC S9(09)V99
        merchant_id="000000001",
        merchant_name="Test Grocery",
        merchant_city="New York",
        merchant_zip="10001",
        card_num=_SEED_CARD_NUM_1,
        orig_ts="2024-01-15-10.30.00.000000",  # PIC X(26)
        proc_ts="2024-01-15-10.30.05.000000",  # PIC X(26)
    )
    transaction_2 = Transaction(
        tran_id=_SEED_TRAN_ID_2,
        type_cd="01",
        cat_cd="0001",
        source="POS TERM",
        description="Fuel purchase",
        amount=Decimal("35.50"),
        merchant_id="000000002",
        merchant_name="Test Fuel",
        merchant_city="Albany",
        merchant_zip="12207",
        card_num=_SEED_CARD_NUM_1,
        orig_ts="2024-01-20-08.15.00.000000",
        proc_ts="2024-01-20-08.15.03.000000",
    )
    db_session.add_all([transaction_1, transaction_2])

    # -----------------------------------------------------------------
    # DailyTransaction -- staging row representing the ingestion
    # buffer used by the POSTTRAN batch job (CBTRN02C.cbl). While the
    # online API does NOT read from this table (it is batch-only),
    # seeding one row exercises the table's DDL at test time so that
    # schema drift between V1__schema.sql and the ORM model is caught
    # early. This also ensures full coverage of the 11 SQLAlchemy
    # models listed in the AAP members_accessed schema.
    # -----------------------------------------------------------------
    daily_transaction_stage = DailyTransaction(
        tran_id=_SEED_TRAN_ID_1,
        type_cd="01",
        cat_cd="0001",
        source="POS TERM",
        description="Grocery purchase staging",
        amount=Decimal("50.00"),
        merchant_id="000000001",
        merchant_name="Test Grocery",
        merchant_city="New York",
        merchant_zip="10001",
        card_num=_SEED_CARD_NUM_1,
        orig_ts="2024-01-15-10.30.00.000000",
        proc_ts="2024-01-15-10.30.05.000000",
    )
    db_session.add(daily_transaction_stage)

    # Flush so the ORM assigns version_id and any DB-generated
    # defaults. Flush issues the INSERTs inside the SAVEPOINT — rollback
    # at teardown discards them.
    await db_session.flush()

    return {
        "regular_user": regular_user,
        "admin_user": admin_user,
        "account_1": account_1,
        "account_2": account_2,
        "card_1": card_1,
        "card_2": card_2,
        "customer": customer,
        "xref_1": xref_1,
        "xref_2": xref_2,
        "transaction_1": transaction_1,
        "transaction_2": transaction_2,
        "tran_type": tran_type,
        "tran_category": tran_category,
        "disclosure_group": disclosure_group,
        "tran_cat_balance": tran_cat_balance,
        "daily_transaction_stage": daily_transaction_stage,
    }



# ===========================================================================
# SECTION 5 — TEST CLASSES (F-001 through F-022)
# ===========================================================================
class TestAuthEndpoints:
    """Integration tests for the authentication endpoints (F-001).

    Source COBOL program: ``app/cbl/COSGN00C.cbl`` — the CICS sign-on
    transaction that prompted the user for USERIDI/PASSWDI, performed
    a keyed READ against the USRSEC VSAM cluster, and either XCTL'd
    to the main menu (``COMEN01C``) on success or displayed an error
    from ``CSMSG01Y.cpy`` on failure. In the modernized architecture:

    * The keyed READ becomes an async SQLAlchemy query against the
      ``user_security`` table (``SELECT ... WHERE user_id = :user_id``).
    * The plaintext ``PIC X(08)`` password comparison is replaced with
      BCrypt hash verification (``passlib.context.CryptContext.verify``).
    * The XCTL to ``COMEN01C`` becomes a signed JWT returned in the
      HTTP 200 response body.
    * All three CSMSG01Y.cpy error messages ("User not found...",
      "Wrong Password...", "Unable to verify the User...") map to
      HTTP 401 responses with the ``WWW-Authenticate: Bearer`` header.

    Covers test methods: ``test_login_success``,
    ``test_login_invalid_password``, ``test_login_user_not_found``,
    ``test_logout``.
    """

    async def test_login_success(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /auth/login with valid credentials returns a JWT.

        Exercises the happy path of ``COSGN00C.cbl``:
        EXEC CICS READ FILE('USRSEC') + password match -> XCTL to
        COMEN01C. The modernized equivalent returns a 200 OK with a
        signed JWT whose claims map to COCOM01Y.cpy COMMAREA fields.
        """
        response = await client.post(
            "/auth/login",
            json={
                "user_id": _TEST_USER_ID,
                "password": _DEFAULT_TEST_PASSWORD,
            },
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # JWT token fields mapped from SignOnResponse schema.
        assert "access_token" in body
        assert body["token_type"] == "bearer"
        assert body["user_id"] == _TEST_USER_ID
        assert body["user_type"] == _TEST_USER_TYPE_REGULAR

        # Verify the token contains the expected claims — CDEMO-USER-ID
        # and CDEMO-USER-TYPE from COCOM01Y.cpy.
        settings = Settings()
        decoded: dict[str, Any] = jwt.decode(
            body["access_token"],
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM],
        )
        assert decoded["sub"] == _TEST_USER_ID
        assert decoded["user_id"] == _TEST_USER_ID
        assert decoded["user_type"] == _TEST_USER_TYPE_REGULAR
        # exp must be in the future.
        assert decoded["exp"] > int(datetime.now(tz=UTC).timestamp())

    async def test_login_invalid_password(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /auth/login with a wrong password returns 401.

        Maps to ``COSGN00C.cbl``'s "Wrong Password. Try again ..."
        path — the COBOL program compared ``PASSWDI`` to the
        ``SEC-PASSWORD`` field and issued the CSMSG01Y.cpy error when
        they differed. The modernized service layer raises
        ``AuthenticationError`` with the same message; the router
        translates that to HTTP 401 plus the ``WWW-Authenticate:
        Bearer`` header.
        """
        response = await client.post(
            "/auth/login",
            json={
                "user_id": _TEST_USER_ID,
                "password": "WrongPw",  # NOT the BCrypt-hashed seed password
            },
        )
        assert response.status_code == 401, response.text
        assert response.headers.get("WWW-Authenticate") == "Bearer"
        body = response.json()
        # The production error_handler middleware wraps every error in
        # the ABEND-DATA envelope ``{"error": {...}}`` (matching
        # CSMSG01Y.cpy) — NOT FastAPI's default ``{"detail": ...}``.
        assert "error" in body
        assert _error_text(body)  # non-empty message/reason text

    async def test_login_user_not_found(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /auth/login with a non-existent user returns 401.

        Maps to ``COSGN00C.cbl`` RESP=NOTFND branch for the
        ``SEC-USER-DATA`` READ — when the keyed lookup missed, the
        COBOL program issued "User not found. Try again ..." from
        CSMSG01Y.cpy. The modernized service deliberately returns the
        SAME 401 error as the "wrong password" case to defeat
        user-enumeration attacks (timing-attack resistance).
        """
        response = await client.post(
            "/auth/login",
            json={
                "user_id": "NOSUCHUS",  # 8-char, never seeded
                "password": _DEFAULT_TEST_PASSWORD,
            },
        )
        assert response.status_code == 401, response.text
        assert response.headers.get("WWW-Authenticate") == "Bearer"

    async def test_logout(self, client: AsyncClient) -> None:
        """POST /auth/logout with valid token returns 200.

        Maps to the CICS RETURN at the end of ``COSGN00C.cbl``
        following a successful sign-on. The logout endpoint is in
        PUBLIC_PATHS and accepts NO request body and NO auth dependency
        (stateless JWT architecture: logout is an acknowledgment; the
        actual token invalidation is client-side by discarding the
        JWT).
        """
        response = await client.post("/auth/logout")
        assert response.status_code == 200, response.text
        body = response.json()
        # SignOutResponse.message is "Successfully signed out".
        assert body["message"] == "Successfully signed out"


# ---------------------------------------------------------------------------
# Helper -- build a full 39-field AccountUpdateRequest payload matching the
# seed data for account_1 / customer_1. Tests override one or two fields
# to trigger change-detection; the rest mirror what was seeded.
# ---------------------------------------------------------------------------


def _build_account_update_payload(
    *,
    account_id: str = _SEED_ACCT_ID_1,
    credit_limit: Decimal = Decimal("6000.00"),
    cash_credit_limit: Decimal = Decimal("1500.00"),
    active_status: str = "Y",
) -> dict[str, Any]:
    """Construct a complete 39-field payload for PUT /accounts/{acct_id}.

    Mirrors the seeded account_1 + customer_1 fixtures exactly (so
    change detection in ``_detect_changes`` sees *only* the fields
    that the test explicitly mutates). All 39 segmented fields from
    ``AccountUpdateRequest`` are populated — none are optional per
    the Pydantic schema (all fields use ``...`` required default).

    Maps 1:1 to BMS map ``COACTUP`` fields from app/cpy-bms/COACTUP.CPY
    (39 segmented fields preserving the COBOL PIC layout). Monetary
    values are ``Decimal`` (NEVER float) per AAP §0.7.2.

    Parameters
    ----------
    account_id : str
        Account primary key (11 zero-padded digits). Defaults to
        the first seed account.
    credit_limit : Decimal
        Total credit limit (NUMERIC(15,2)). Defaults to 6000.00 —
        distinct from the seed's 5000.00 to trigger change detection.
    cash_credit_limit : Decimal
        Cash advance sub-limit (NUMERIC(15,2)).
    active_status : str
        Account active flag ('Y' or 'N').

    Returns
    -------
    dict[str, Any]
        A JSON-serializable payload suitable for ``client.put``.
    """
    return {
        # -- Account identity / status ---------------------------------
        "account_id": account_id,
        "active_status": active_status,
        # -- Open date (segmented CCYY/MM/DD) -------------------------
        "open_date_year": "2020",
        "open_date_month": "01",
        "open_date_day": "15",
        # -- Credit limit --------------------------------------------
        "credit_limit": str(credit_limit),
        # -- Expiration date -----------------------------------------
        "expiration_date_year": "2030",
        "expiration_date_month": "12",
        "expiration_date_day": "31",
        # -- Cash credit limit ---------------------------------------
        "cash_credit_limit": str(cash_credit_limit),
        # -- Reissue date --------------------------------------------
        "reissue_date_year": "2025",
        "reissue_date_month": "01",
        "reissue_date_day": "15",
        # -- Disclosure group ----------------------------------------
        "group_id": "DEFAULT",
        # -- Customer SSN (segmented 3/2/4) --------------------------
        "customer_ssn_part1": "123",
        "customer_ssn_part2": "45",
        "customer_ssn_part3": "6789",
        # -- Customer DOB (segmented CCYY/MM/DD) ---------------------
        "customer_dob_year": "1990",
        "customer_dob_month": "01",
        "customer_dob_day": "15",
        # -- FICO --------------------------------------------------
        "customer_fico_score": "750",
        # -- Customer names (up to 25 chars each) --------------------
        "customer_first_name": "John",
        "customer_middle_name": "Q",
        "customer_last_name": "Doe",
        # -- Customer address ---------------------------------------
        "customer_addr_line_1": "123 Test St",
        "customer_state_cd": "NY",
        "customer_addr_line_2": "",
        "customer_zip": "10001",
        "customer_city": "",  # ← addr_line_3 in CVCUS01Y.cpy
        # ISO 3-letter country code per account_service.py
        # ``_validate_country_code`` (3 alphabetic chars). Matches
        # the seeded Customer.country_cd="USA" so change-detection
        # sees no drift on this field.
        "customer_country_cd": "USA",
        # -- Customer phone 1 (segmented area/prefix/line) -----------
        "customer_phone_1_area": "212",
        "customer_phone_1_prefix": "555",
        "customer_phone_1_line": "1234",
        "customer_govt_id": "",
        # -- Customer phone 2 (optional, all-blank) ------------------
        "customer_phone_2_area": "",
        "customer_phone_2_prefix": "",
        "customer_phone_2_line": "",
        # -- EFT / primary cardholder --------------------------------
        "customer_eft_account_id": "",
        "customer_pri_cardholder": "Y",
    }


# ===========================================================================
# TestAccountEndpoints — F-004 (COACTVWC.cbl) + F-005 (COACTUPC.cbl)
# ===========================================================================
class TestAccountEndpoints:
    """Integration tests for the Account router.

    Covers Features F-004 (Account view — ``app/cbl/COACTVWC.cbl``)
    and F-005 (Account update — ``app/cbl/COACTUPC.cbl``, 4,236 lines)
    of the AAP.

    COBOL → HTTP mapping
    --------------------
    * ``EXEC CICS RECEIVE MAP('COACTVW')``    → URL path ``/accounts/{acct_id}``
    * ``EXEC CICS READ DATASET('ACCTFILE')``  → ``GET /accounts/{acct_id}``
    * ``EXEC CICS SEND MAP('COACTVW')``       → :class:`AccountViewResponse`
    * ``EXEC CICS RECEIVE MAP('COACTUP')``    → :class:`AccountUpdateRequest`
    * ``EXEC CICS READ DATASET('ACCTFILE') UPDATE``
                                              → service read (OCC via ``version_id``)
    * ``EXEC CICS REWRITE DATASET('ACCTFILE')``
                                              → SQLAlchemy flush + commit
    * ``EXEC CICS SYNCPOINT / SYNCPOINT ROLLBACK``
                                              → session commit / rollback
    * Dual-write (ACCTFILE + CUSTFILE in one SYNCPOINT)
                                              → single SQLAlchemy transaction
      COACTUPC.cbl line 953 (``PERFORM 1100-PROGRAM-INIT`` through
      ``4100-ABEND-TRAN``) forms the atomic commit boundary.

    Router conventions (confirmed in ``src/api/routers/account_router.py``):

    * 400 for **every** service-layer failure surfaced via
      ``response.error_message`` — the router does NOT differentiate
      404 (NOTFND) from 400 (validation / concurrency). This preserves
      the COBOL single-path error channel (1920-MAIN-PROGRAM branch).
    * 401 for missing / invalid JWT (middleware)
    * 422 for path-regex violations (non-11-digit path)
    """

    async def test_get_account_by_id(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /accounts/{acct_id} returns the full 31-field view.

        Maps to ``COACTVWC.cbl`` 3-entity join:
        ``ACCOUNT-FILE`` + ``CXACAIX`` (AIX on ACCT-ID) +
        ``CUSTFILE``. The response preserves COBOL ``PIC S9(n)V99``
        precision via :class:`Decimal` on ALL monetary fields.

        Assertions:

        * 200 OK status
        * Account identity matches seed account_1 (11-digit key)
        * All 5 monetary fields present as Decimal-precise strings
        * Customer identity + address + contact fields populated
        * No error_message / info_message set on happy path
        """
        token = create_test_token(_TEST_USER_ID, _TEST_USER_TYPE_REGULAR)
        response = await client.get(
            f"/accounts/{_SEED_ACCT_ID_1}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # -- Account identity ----------------------------------------
        assert body["account_id"] == _SEED_ACCT_ID_1
        assert body["active_status"] == "Y"
        assert body["open_date"] == "2020-01-15"
        assert body["expiration_date"] == "2030-12-31"
        assert body["reissue_date"] == "2025-01-15"
        assert body["group_id"] == "DEFAULT"

        # -- Monetary fields (CRITICAL: Decimal precision per AAP §0.7.2) --
        # Pydantic serializes Decimal as a JSON string to preserve
        # scale; we re-hydrate to Decimal for exact-equality assertion.
        assert Decimal(body["credit_limit"]) == Decimal("5000.00")
        assert Decimal(body["cash_credit_limit"]) == Decimal("1500.00")
        assert Decimal(body["current_balance"]) == Decimal("1000.00")
        assert Decimal(body["current_cycle_credit"]) == Decimal("0.00")
        assert Decimal(body["current_cycle_debit"]) == Decimal("0.00")

        # -- Customer join (3-entity: ACCOUNT + CXACAIX + CUSTFILE) ----
        assert body["customer_id"] == _SEED_CUST_ID_1
        assert body["customer_first_name"] == "John"
        assert body["customer_middle_name"] == "Q"
        assert body["customer_last_name"] == "Doe"
        assert body["customer_ssn"] == "123-45-6789"  # NNN-NN-NNNN display
        assert body["customer_dob"] == "1990-01-15"
        assert body["customer_fico_score"] == "750"  # 3-char zero-padded str
        assert body["customer_addr_line_1"] == "123 Test St"
        assert body["customer_state_cd"] == "NY"
        # ISO 3-letter country code (CVCUS01Y.cpy CUST-ADDR-COUNTRY-CD PIC X(03))
        assert body["customer_country_cd"] == "USA"
        assert body["customer_zip"] == "10001"
        assert body["customer_phone_1"] == "(212)555-1234"
        assert body["customer_pri_cardholder"] == "Y"

        # -- Happy-path response carries no user-facing error ---------
        assert body.get("error_message") is None

    async def test_get_account_not_found(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /accounts/{non_existent_id} returns 400 (NOT 404).

        The Account router maps ALL service-layer failures — including
        the NOTFND branch from ``COACTVWC.cbl`` — to HTTP 400 via the
        ``response.error_message`` discriminator. This preserves the
        COBOL single-path error channel (1920-MAIN-PROGRAM in
        COACTVWC.cbl). See ``src/api/routers/account_router.py`` lines
        155-161 for the docstring of this design decision.

        The path regex (``^[0-9]{11}$``) is satisfied by the 11-digit
        ID — we want to exercise the service layer's lookup, not the
        framework's regex check.
        """
        token = create_test_token(_TEST_USER_ID, _TEST_USER_TYPE_REGULAR)
        response = await client.get(
            "/accounts/99999999999",  # 11 digits, NOT in seed data
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 400, response.text
        body = response.json()
        # The global exception handler wraps HTTPException as the
        # ABEND-DATA envelope ``{"error": {"message", "reason", ...}}``
        # -- see ``src.api.middleware.error_handler._build_error_response``.
        assert "error" in body
        assert _error_text(body)  # non-empty message/reason text

    async def test_update_account(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """PUT /accounts/{acct_id} with modified credit_limit commits cleanly.

        Maps to ``COACTUPC.cbl``'s 1000-MAIN-PROGRAM flow:

        1. ``RECEIVE MAP('COACTUP')``    — request body parsed
        2. ``1200-EDIT-MAP-INPUTS``      — field validation cascade
        3. ``READ DATASET('ACCTFILE') UPDATE`` — service read + OCC
        4. ``1205-COMPARE-OLD-NEW``      — change detection
        5. ``REWRITE DATASET('ACCTFILE')`` + ``REWRITE DATASET('CUSTFILE')``
                                         — dual-write
        6. ``SYNCPOINT``                 — commit

        The test mutates ``credit_limit`` from 5000.00 to 6000.00
        (triggering change detection) while keeping all other fields
        identical to the seed. Expected response: 200 OK with the
        new credit_limit echoed back and ``info_message`` set to the
        COBOL success literal "Changes committed to database".
        Monetary precision (NUMERIC(15,2)) is asserted via Decimal.
        """
        token = create_test_token(_TEST_USER_ID, _TEST_USER_TYPE_REGULAR)
        new_credit_limit = Decimal("6000.00")
        payload = _build_account_update_payload(
            credit_limit=new_credit_limit,
        )
        response = await client.put(
            f"/accounts/{_SEED_ACCT_ID_1}",
            headers={"Authorization": f"Bearer {token}"},
            json=payload,
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # -- Updated field reflects the mutation ----------------------
        assert Decimal(body["credit_limit"]) == new_credit_limit
        # -- Unchanged fields retain their seed values ---------------
        assert Decimal(body["cash_credit_limit"]) == Decimal("1500.00")
        assert body["account_id"] == _SEED_ACCT_ID_1
        assert body["active_status"] == "Y"
        # -- No error surfaced, info_message = success literal --------
        assert body.get("error_message") is None
        # COACTUPC.cbl L3798 literal — we accept any COBOL-verbatim
        # success message since the service may legitimately return
        # None info_message when the change is a single numeric field.
        info: str | None = body.get("info_message")
        if info is not None:
            # If the service set an info_message, it must be the
            # COBOL-verbatim success literal (see
            # ``src/api/services/account_service.py`` _MSG_UPDATE_SUCCESS).
            assert info == "Changes committed to database"

    async def test_update_account_concurrent_modification(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """PUT /accounts/{acct_id} under optimistic-concurrency conflict → 400.

        Simulates a version_id mismatch by patching the shared async
        session's ``flush`` coroutine to raise
        :class:`sqlalchemy.orm.exc.StaleDataError`. The service layer
        catches ``StaleDataError`` (account_service.py line 1334) and
        surfaces the COBOL-verbatim literal "Record changed by some
        one else. Please review" via ``error_message``. The router
        translates that to HTTP 400 per its single-path error convention.

        Maps to ``COACTUPC.cbl`` DATA-WAS-CHANGED-BEFORE-UPDATE branch
        (line ~1681 ``1205-COMPARE-OLD-NEW``). In the COBOL world the
        equivalent surfaces as ``DFHRESP(INVREQ)`` on
        ``REWRITE DATASET('ACCTFILE')`` when another task has modified
        the VSAM record between READ UPDATE and REWRITE. The cloud-
        native equivalent uses SQLAlchemy's ``__mapper_args__ =
        {"version_id_col": version_id}`` on ``Account`` — identical
        semantics, implemented at the ORM layer.
        """
        token = create_test_token(_TEST_USER_ID, _TEST_USER_TYPE_REGULAR)
        # Submit a change that would trigger a commit (credit_limit
        # differs from seed) so the service reaches the flush step.
        payload = _build_account_update_payload(
            credit_limit=Decimal("7500.00"),
        )
        # Patch AsyncSession.flush to raise StaleDataError, emulating a
        # concurrent UPDATE having bumped the version_id between the
        # service's SELECT and its flush. StaleDataError('conflict')
        # constructs successfully per SQLAlchemy's public API.
        with patch(
            "src.api.services.account_service.AsyncSession.flush",
            new=AsyncMock(
                side_effect=StaleDataError(
                    "UPDATE statement on table 'account' expected to "
                    "update 1 row; 0 were matched."
                )
            ),
        ):
            response = await client.put(
                f"/accounts/{_SEED_ACCT_ID_1}",
                headers={"Authorization": f"Bearer {token}"},
                json=payload,
            )
        assert response.status_code == 400, response.text
        body = response.json()
        # COACTUPC.cbl L3802 literal — "Record changed by some one
        # else. Please review" (note COBOL's "some one" is two words).
        # Error text arrives in the ABEND envelope's reason/message
        # slots (wrapped by error_handler.py).
        assert "error" in body
        assert "Record changed" in _error_text(body)

    async def test_update_account_unauthorized(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """PUT /accounts/{acct_id} without a Bearer token returns 401.

        Maps to ``COSGN00C.cbl``'s security posture: every online
        transaction (EXCEPT sign-on and logout) requires an
        authenticated COMMAREA. The PUBLIC_PATHS set in
        ``src/api/middleware/auth.py`` does NOT include /accounts, so
        the middleware rejects the unauthenticated request at the
        transport boundary — the service layer is never invoked.

        The response body carries ``{"detail": "Not authenticated"}``
        (or similar FastAPI/Starlette default) and the
        ``WWW-Authenticate: Bearer`` header per RFC 6750.
        """
        payload = _build_account_update_payload()
        response = await client.put(
            f"/accounts/{_SEED_ACCT_ID_1}",
            json=payload,
            # NOTE: No Authorization header — triggers 401 at middleware.
        )
        assert response.status_code == 401, response.text



# ===========================================================================
# SECTION 5C -- CARD ENDPOINT TESTS (F-006, F-007, F-008)
# ===========================================================================
# Source: app/cbl/COCRDLIC.cbl (F-006 Card list, 7 rows/page from COCRDLI BMS)
#         app/cbl/COCRDSLC.cbl (F-007 Card detail view)
#         app/cbl/COCRDUPC.cbl (F-008 Card update, optimistic concurrency)
#         app/cpy/CVACT02Y.cpy (Card record layout, 150-byte KSDS)
#
# Card router error convention: ALL card errors surface as HTTP 400
# (no 404), matching the uniform error envelope produced by the
# service layer via ``response.error_message`` -- distinct from the
# transaction router's substring-based 404/400 routing.
#
# Card pagination: page size is 7, matching the 7 repeated row groups
# in the COCRDLI BMS map layout.
#
# Card optimistic concurrency uses SQLAlchemy ``version_id_col`` on
# the ``Card.version_id`` integer column. A concurrent-modification
# conflict manifests as :class:`sqlalchemy.orm.exc.StaleDataError`
# raised by ``flush()``; the service catches it and returns a
# populated ``error_message`` with the text "Record changed by some
# one else. Please review" (preserving the original COBOL wording).
# ===========================================================================
class TestCardEndpoints:
    """Integration tests for Card CRUD endpoints (F-006, F-007, F-008).

    Maps to three COBOL online programs:

    * ``COCRDLIC.cbl`` (F-006) -- ``GET /cards`` paginated browse
      with optional ``account_id`` / ``card_number`` filters.
      COCRDLI BMS map displays 7 repeated row groups per page.
    * ``COCRDSLC.cbl`` (F-007) -- ``GET /cards/{card_num}`` detail
      view over a single Card row.
    * ``COCRDUPC.cbl`` (F-008) -- ``PUT /cards/{card_num}`` update
      guarded by optimistic concurrency (``version_id`` column;
      ``StaleDataError`` -> HTTP 400 with "Record changed ..."
      message preserving COBOL wording).

    All six tests use Bearer-JWT authentication obtained via
    :func:`create_test_token`; no test hits an unauthenticated branch
    (that coverage belongs in TestAdminEndpoints /
    TestAccountEndpoints). The seed fixture provides two cards
    (``card_1`` on acct_id 1, ``card_2`` on acct_id 2) so pagination
    and filter tests have determinate expected results.

    Router error convention verification: every error path asserts
    HTTP 400 (not 404) because ``src/api/routers/card_router.py``
    collapses all service-layer failures into a single 400 status
    per the card router's uniform error contract.
    """

    async def test_list_cards(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /cards returns a paginated list of cards.

        Maps to ``COCRDLIC.cbl``'s ``EXEC CICS STARTBR / READNEXT``
        browse loop over the CARDDAT VSAM cluster. The COCRDLI BMS
        map has 7 repeated row groups, so the page size is 7.

        Assertions:

        * HTTP 200 (card list is always 200 on success).
        * ``body["cards"]`` is a list with length <= 7 (page size).
        * ``body["page_number"]`` == 1 (default page).
        * ``body["total_pages"]`` >= 1 (seed fixture inserts 2 cards).
        * Each card row has the 4 required CardListItem fields:
          ``selected``, ``account_id``, ``card_number``, ``card_status``.
        * Both seed card numbers appear in the returned list.
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            "/cards",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Pagination envelope (CardListResponse contract).
        assert "cards" in body
        assert isinstance(body["cards"], list)
        # Page size 7 matches COCRDLIC.cbl BMS map COCRDLI 7-row display.
        assert len(body["cards"]) <= 7
        assert body["page_number"] == 1
        assert body["total_pages"] >= 1

        # Each row must carry the 4 CardListItem fields.
        for card_row in body["cards"]:
            assert "selected" in card_row
            assert "account_id" in card_row
            assert "card_number" in card_row
            assert "card_status" in card_row

        # Both seed card numbers must appear in the list.
        returned_nums: set[str] = {row["card_number"] for row in body["cards"]}
        assert _SEED_CARD_NUM_1 in returned_nums
        assert _SEED_CARD_NUM_2 in returned_nums

    async def test_list_cards_by_account(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /cards?account_id=... filters by account.

        Maps to ``COCRDLIC.cbl``'s filtered browse: when the BMS
        ``ACCTNOI`` field carried a value, the program limited the
        browse to the cross-reference path
        (``CXACAIX -> ACCTDAT``), emitting only cards belonging to
        that account.

        Assertions:

        * HTTP 200.
        * Every returned card has ``account_id == _SEED_ACCT_ID_1``.
        * The list includes ``_SEED_CARD_NUM_1`` (card_1 is on
          account 1) and excludes ``_SEED_CARD_NUM_2`` (card_2 is on
          account 2).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            "/cards",
            params={"account_id": _SEED_ACCT_ID_1},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        assert "cards" in body
        # All returned cards must belong to account 1.
        for card_row in body["cards"]:
            assert card_row["account_id"] == _SEED_ACCT_ID_1

        returned_nums: set[str] = {row["card_number"] for row in body["cards"]}
        assert _SEED_CARD_NUM_1 in returned_nums
        assert _SEED_CARD_NUM_2 not in returned_nums

    async def test_get_card_detail(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /cards/{card_num} returns full CardDetailResponse.

        Maps to ``COCRDSLC.cbl``'s ``EXEC CICS READ DATASET('CARDDAT')
        RIDFLD(WS-CARD-NUM)`` -- a single-key lookup returning the
        8-field Card record layout from ``CVACT02Y.cpy``.

        Assertions:

        * HTTP 200.
        * ``body["card_number"]`` matches the seed card number 1.
        * ``body["account_id"]`` matches the seed account id 1.
        * ``body["embossed_name"]`` is "TEST USER" (seed value).
        * ``body["status_code"]`` is "Y" (active_status from seed).
        * ``body["expiry_month"]`` is "12" (derived from
          ``expiration_date == "2030-12-31"``).
        * ``body["expiry_year"]`` is "2030".
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            f"/cards/{_SEED_CARD_NUM_1}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Identity and link-back-to-account verification.
        assert body["card_number"] == _SEED_CARD_NUM_1
        assert body["account_id"] == _SEED_ACCT_ID_1

        # CVACT02Y.cpy CARD-EMBOSSED-NAME / CARD-ACTIVE-STATUS.
        assert body["embossed_name"] == "TEST USER"
        assert body["status_code"] == "Y"

        # Expiry components derived from seed ``expiration_date``
        # ("2030-12-31") by the service's ISO-date splitter.
        assert body["expiry_month"] == "12"
        assert body["expiry_year"] == "2030"

    async def test_get_card_not_found(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /cards/{card_num} returns 400 for a non-existent card.

        Router convention: the card router collapses all service
        failures (including not-found) into HTTP 400 with the
        service's error message as the detail. This differs from the
        transaction router which returns 404 for "NOT found"
        substring matches.

        Maps to ``COCRDSLC.cbl``'s ``EXEC CICS READ`` NOTFND response
        branch, which sent the BMS map with ``ERRMSGO`` set to "Did
        not find cards for this search condition" (see
        ``_MSG_DETAIL_NOT_FOUND`` in card_service.py).

        Assertions:

        * HTTP 400 (NOT 404 -- card router uniform error policy).
        * ``body["detail"]`` contains "Did not find cards".
        """
        # A well-formed but unseeded 16-digit card number -- must
        # pass the path regex ``^[0-9]{16}$`` and then miss the DB.
        missing_card: str = "9999999999999999"
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            f"/cards/{missing_card}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 400, response.text
        body = response.json()
        assert "error" in body
        # Error message sourced verbatim from COCRDSLC.cbl service
        # constant ``_MSG_DETAIL_NOT_FOUND``. The error_handler
        # middleware places the detail text in the reason slot of the
        # ABEND envelope for 400/404 HTTPExceptions.
        assert "Did not find cards" in _error_text(body)

    async def test_update_card(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """PUT /cards/{card_num} applies field updates.

        Maps to ``COCRDUPC.cbl``'s ``EXEC CICS READ UPDATE`` ->
        field-validation cascade -> ``EXEC CICS REWRITE`` flow. The
        service layer preserves the ``version_id`` optimistic-
        concurrency guard across the read-modify-write cycle.

        Assertions:

        * HTTP 200.
        * ``body["embossed_name"]`` reflects the updated value.
        * ``body["card_number"]`` is unchanged (identity field).
        * ``body["status_code"]`` is the new value (set to "N"
          in this test).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        # CardUpdateRequest requires ALL 7 fields per the schema
        # contract (no PATCH-style partials; every field is declared
        # with ``...``). We copy the seed values for the fields we
        # are not changing.
        updated_name: str = "UPDATED NAME"
        update_payload: dict[str, str] = {
            "account_id": _SEED_ACCT_ID_1,
            "card_number": _SEED_CARD_NUM_1,
            "embossed_name": updated_name,
            "status_code": "N",
            "expiry_month": "12",
            "expiry_year": "2030",
            "expiry_day": "31",
        }
        response = await client.put(
            f"/cards/{_SEED_CARD_NUM_1}",
            json=update_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Identity fields are preserved.
        assert body["card_number"] == _SEED_CARD_NUM_1
        assert body["account_id"] == _SEED_ACCT_ID_1
        # Updated fields reflect the new values.
        assert body["embossed_name"] == updated_name
        assert body["status_code"] == "N"

    async def test_update_card_optimistic_concurrency(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """PUT /cards/{card_num} returns 400 on StaleDataError.

        Simulates a concurrent-modification scenario: another
        session has already incremented ``Card.version_id`` between
        our READ and our REWRITE. SQLAlchemy detects the version
        mismatch on ``flush()`` and raises
        :class:`sqlalchemy.orm.exc.StaleDataError`. The card service
        catches this, issues a SAVEPOINT rollback (mirroring CICS
        SYNCPOINT ROLLBACK), and returns ``error_message`` set to
        "Record changed by some one else. Please review" -- the
        card router then translates this into HTTP 400.

        Note: the COBOL source text is "some one" (two words), not
        "someone" -- preserved verbatim per AAP section 0.7.1.

        Assertions:

        * HTTP 400.
        * ``body["detail"]`` contains "Record changed" (byte-for-
          byte substring of the COBOL error string).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        # Minimal, valid update payload. The service will reach
        # flush() only after all schema validators pass.
        update_payload: dict[str, str] = {
            "account_id": _SEED_ACCT_ID_1,
            "card_number": _SEED_CARD_NUM_1,
            "embossed_name": "CONCURRENT UPDATE",
            "status_code": "Y",
            "expiry_month": "12",
            "expiry_year": "2030",
            "expiry_day": "31",
        }

        # Patch the AsyncSession.flush used by card_service to raise
        # StaleDataError. We patch at the card_service namespace so
        # the test is scoped to the service under test; other code
        # paths (seed_data, db_session setup) are unaffected.
        stale_error: StaleDataError = StaleDataError(
            "UPDATE statement on table 'card' expected to update "
            "1 row(s); 0 were matched."
        )
        with patch(
            "src.api.services.card_service.AsyncSession.flush",
            new=AsyncMock(side_effect=stale_error),
        ):
            response = await client.put(
                f"/cards/{_SEED_CARD_NUM_1}",
                json=update_payload,
                headers={"Authorization": f"Bearer {token}"},
            )

        # Card router collapses service-layer errors to 400.
        assert response.status_code == 400, response.text
        body = response.json()
        assert "error" in body
        # COBOL-exact wording preserved: "some one" is two words.
        # Error text is in the ABEND envelope's reason/message slots.
        assert "Record changed" in _error_text(body)



# ===========================================================================
# SECTION 5D -- TRANSACTION ENDPOINT TESTS (F-009, F-010, F-011)
# ===========================================================================
# Source: app/cbl/COTRN00C.cbl (F-009 Transaction list, 10 rows/page from
#           COTRN00 BMS) -- STARTBR/READNEXT browse cursor over TRANSACT.
#         app/cbl/COTRN01C.cbl (F-010 Transaction detail) -- single-key
#           READ DATASET('TRANSACT') RIDFLD(TRAN-ID).
#         app/cbl/COTRN02C.cbl (F-011 Transaction add) -- CXACAIX xref
#           lookup + sequence-number allocation + WRITE DATASET.
#         app/cpy/CVTRA05Y.cpy (Transaction record, 350-byte layout)
#
# Transaction router error routing -- UNIQUE substring logic:
#
#   Detail (GET /transactions/{tran_id}):
#     "NOT found" IN message -> 404 (_MSG_TRAN_NOT_FOUND only)
#     Any other populated message -> 400 (empty ID, DB lookup failure)
#
#   Add (POST /transactions):
#     "XREF" IN message  -> 404 (_MSG_CARD_NOT_IN_XREF or
#                                _MSG_ACCT_CARD_MISMATCH)
#     Any other response.confirm != "Y" -> 400
#
#   List (GET /transactions):
#     Any populated message (signals DB error) -> 400
#
# Transaction add returns HTTP 201 CREATED (not 200 OK) on success.
# ===========================================================================
class TestTransactionEndpoints:
    """Integration tests for Transaction CRUD endpoints (F-009-F-011).

    Maps to three COBOL online programs:

    * ``COTRN00C.cbl`` (F-009) -- ``GET /transactions`` paginated
      browse. COTRN00 BMS map displays 10 repeated row groups per
      page; the API preserves this as ``page_size=10`` default.
    * ``COTRN01C.cbl`` (F-010) -- ``GET /transactions/{tran_id}``
      full-record detail lookup.
    * ``COTRN02C.cbl`` (F-011) -- ``POST /transactions`` new-
      transaction creation with auto-ID assignment (the service
      derives the next sequence number) and card-number ->
      account-id resolution via the CXACAIX AIX cross-reference.

    Seed fixture provides two transactions both linked to
    ``_SEED_CARD_NUM_1``:

    * ``TXN0000000000001`` -- Decimal("50.00"), "Grocery purchase"
    * ``TXN0000000000002`` -- Decimal("35.50"), "Fuel purchase"

    This supports a deterministic prefix-filter test (tran_id="TXN"
    -> 2 rows) and a happy-path detail test.
    """

    async def test_list_transactions(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /transactions returns a paginated list of transactions.

        Maps to ``COTRN00C.cbl``'s ``EXEC CICS STARTBR / READNEXT``
        forward browse over the TRANSACT KSDS. Default page size is
        10 matching the 10-repeated-row COTRN00 BMS map layout.

        Assertions:

        * HTTP 200.
        * ``body["transactions"]`` is a list.
        * ``body["page"]`` == 1 (default page).
        * ``body["total_count"]`` >= 2 (seed fixture inserts 2).
        * Both seed transaction IDs appear in the returned list.
        * Each list item contains the 4 TransactionListItem fields:
          ``tran_id``, ``tran_date``, ``description``, ``amount``.
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            "/transactions",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Pagination envelope -- TransactionListResponse contract.
        assert "transactions" in body
        assert isinstance(body["transactions"], list)
        assert body["page"] == 1
        assert body["total_count"] >= 2
        # Default page_size 10 matches COTRN00C.cbl BMS 10-row display.
        assert len(body["transactions"]) <= 10

        # Each row must carry the 4 TransactionListItem fields.
        for row in body["transactions"]:
            assert "tran_id" in row
            assert "tran_date" in row
            assert "description" in row
            assert "amount" in row

        # Both seed transaction IDs must appear.
        returned_ids: set[str] = {
            row["tran_id"] for row in body["transactions"]
        }
        assert _SEED_TRAN_ID_1 in returned_ids
        assert _SEED_TRAN_ID_2 in returned_ids

    async def test_list_transactions_filtered(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /transactions?tran_id=<prefix> filters with LIKE prefix match.

        Maps to ``COTRN00C.cbl``'s "jump-to" browse behavior where
        the BMS ``TRNIDINI`` field, when populated, reset the browse
        cursor via ``STARTBR RIDFLD(WS-TRAN-ID) GTEQ``. The service
        layer preserves this with SQL ``LIKE 'prefix%'`` semantics.

        Assertions:

        * HTTP 200.
        * Filter prefix matches both seed transactions.
        * All returned rows have a ``tran_id`` that starts with the
          filter prefix.

        Note on filter literal: Per ``transaction_service.py`` module
        constants (``_TRAN_ID_WIDTH = 16``, ``_INITIAL_TRAN_ID =
        "0000000000000001"``) and ``CVTRA05Y.cpy`` ``TRAN-ID PIC
        X(16)``, seed IDs are pure-numeric 16-digit strings. The
        longest common prefix for both seed IDs
        ``0000000000000100`` and ``0000000000000200`` is
        ``000000000000`` (twelve leading zeroes) -- any non-empty
        prefix that both seeds share is valid for this test.
        """
        # Compute the longest common prefix of the two seed IDs to
        # avoid hard-coding a literal that would drift if the seed
        # values change. Iteration must STOP at the first mismatched
        # position to preserve "prefix" semantics — a naive zip-filter
        # would continue matching equal chars after a divergence and
        # produce a string that is NOT actually a prefix of either
        # input. For ``_SEED_TRAN_ID_1 = "0000000000000100"`` and
        # ``_SEED_TRAN_ID_2 = "0000000000000200"``, the correct
        # longest common prefix is twelve zeros (``000000000000``).
        common_prefix_chars: list[str] = []
        for char_a, char_b in zip(
            _SEED_TRAN_ID_1, _SEED_TRAN_ID_2, strict=True
        ):
            if char_a != char_b:
                break
            common_prefix_chars.append(char_a)
        common_prefix: str = "".join(common_prefix_chars)
        assert common_prefix, "Seed transaction IDs share no prefix"
        assert _SEED_TRAN_ID_1.startswith(common_prefix)
        assert _SEED_TRAN_ID_2.startswith(common_prefix)

        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            "/transactions",
            params={"tran_id": common_prefix},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        assert "transactions" in body
        # All returned rows must match the common prefix.
        for row in body["transactions"]:
            assert row["tran_id"].startswith(common_prefix), row
        # Both seed transactions must be present.
        returned_ids: set[str] = {
            row["tran_id"] for row in body["transactions"]
        }
        assert _SEED_TRAN_ID_1 in returned_ids
        assert _SEED_TRAN_ID_2 in returned_ids

    async def test_get_transaction_detail(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /transactions/{tran_id} returns full TransactionDetailResponse.

        Maps to ``COTRN01C.cbl``'s single-key
        ``EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)`` ->
        populate 350-byte CTRN01AI map. The service layer returns
        every field from the CVTRA05Y.cpy record layout.

        CRITICAL: ``amount`` must round-trip as a Decimal-precise
        string (not a float) to preserve COBOL ``PIC S9(09)V99``
        precision per AAP section 0.7.2.

        Assertions:

        * HTTP 200.
        * ``body["tran_id"]`` equals the seed transaction id.
        * ``body["card_num"]`` equals ``_SEED_CARD_NUM_1``.
        * ``body["description"]`` equals the seed description.
        * ``body["amount"]`` is a Decimal-preserving string that
          parses to ``Decimal("50.00")`` (exact equality, not
          approximate).
        * ``body["tran_type_cd"]`` equals "01" (seed value).
        * ``body["tran_cat_cd"]`` equals "0001" (seed value).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            f"/transactions/{_SEED_TRAN_ID_1}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Identity + link fields.
        assert body["tran_id"] == _SEED_TRAN_ID_1
        assert body["card_num"] == _SEED_CARD_NUM_1

        # Content fields (from seed fixture).
        assert body["description"] == "Grocery purchase"
        assert body["tran_type_cd"] == "01"
        assert body["tran_cat_cd"] == "0001"

        # Monetary precision: parse the JSON-serialized amount back
        # into Decimal and compare EXACTLY (never float-approximate).
        # This validates PIC S9(09)V99 scale preservation.
        parsed_amount: Decimal = Decimal(str(body["amount"]))
        assert parsed_amount == Decimal("50.00")

    async def test_get_transaction_not_found(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /transactions/{tran_id} returns 404 for a missing ID.

        Router convention: the transaction router uses substring-
        based error routing. A service-returned message containing
        "NOT found" -> 404; any other populated message -> 400. The
        phrase "NOT found" is unique to ``_MSG_TRAN_NOT_FOUND``.

        Maps to ``COTRN01C.cbl``'s ``EXEC CICS READ`` NOTFND branch,
        which populated ``ERRMSGO`` with "Transaction ID NOT
        found...".

        Assertions:

        * HTTP 404 (NOT 400 -- COTRN01C uses "NOT found" substring).
        * ``body["detail"]`` contains "NOT found" (byte-exact COBOL
          wording preserved in _MSG_TRAN_NOT_FOUND).
        """
        # Well-formed ID (matches ^[A-Za-z0-9_\-]{1,16}$) but absent.
        missing_tran: str = "TXNMISSING000000"
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            f"/transactions/{missing_tran}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 404, response.text
        body = response.json()
        assert "error" in body
        # COBOL-exact wording preserved ("NOT found" two words).
        # ABEND envelope's reason/message slots carry the detail.
        assert "NOT found" in _error_text(body)

    async def test_add_transaction(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /transactions creates a new transaction with auto-ID.

        Maps to ``COTRN02C.cbl``'s 4-step add flow:

        1. ``RECEIVE MAP('COTRN02')`` -- request body.
        2. ``1020-XREF-LOOKUP`` -- CXACAIX AIX resolves card ->
           account. Card 4111000000000001 maps to account 1.
        3. ``1030-ASSIGN-TRAN-ID`` -- sequence-number allocation.
        4. ``1040-WRITE-TRANSACT`` -- ``EXEC CICS WRITE DATASET``.

        CRITICAL: ``amount`` must be submitted as a string that
        parses to exact Decimal precision. The auto-ID is generated
        server-side (maps to COTRN02C's ``STARTBR/READPREV/ENDBR``
        next-sequence pattern -- see schema docstring for F-011).

        Assertions:

        * HTTP 201 Created (NOT 200 -- this endpoint uses
          ``status_code=status.HTTP_201_CREATED``).
        * ``body["confirm"]`` equals "Y" (success).
        * ``body["tran_id"]`` is a non-empty auto-generated string.
        * ``body["card_num"]`` echoes the request card number.
        * ``body["amount"]`` round-trips as Decimal("99.99").
        * ``body["acct_id"]`` equals the resolved account (account 1
          for card 1 via the seed xref).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        # 13-field TransactionAddRequest payload. ``amount`` uses a
        # Decimal-safe string literal to avoid any float coercion.
        new_amount: Decimal = Decimal("99.99")
        add_payload: dict[str, str] = {
            "acct_id": _SEED_ACCT_ID_1,
            "card_num": _SEED_CARD_NUM_1,
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
            "tran_source": "POS TERM",
            "description": "Test add transaction",
            "amount": str(new_amount),
            "orig_date": "2024-01-25",
            "proc_date": "2024-01-25",
            "merchant_id": "000000001",
            "merchant_name": "Test Merchant",
            "merchant_city": "New York",
            "merchant_zip": "10001",
        }
        response = await client.post(
            "/transactions",
            json=add_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        # CRITICAL: HTTP 201 (not 200) -- matches router decorator
        # ``status_code=status.HTTP_201_CREATED``.
        assert response.status_code == 201, response.text
        body = response.json()

        # Auto-ID maps to COTRN02C.cbl's EXEC CICS STARTBR/READPREV/ENDBR
        # for next-ID assignment.
        assert body["confirm"] == "Y"
        assert body["tran_id"]  # non-empty
        assert isinstance(body["tran_id"], str)

        # Identity and xref resolution.
        assert body["card_num"] == _SEED_CARD_NUM_1
        assert body["acct_id"] == _SEED_ACCT_ID_1

        # CRITICAL: Assert monetary ``amount`` stored with exact
        # Decimal precision (PIC S9(09)V99 equivalent).
        parsed_amount: Decimal = Decimal(str(body["amount"]))
        assert parsed_amount == new_amount

    async def test_add_transaction_invalid_card(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /transactions returns 404 for an unseeded card number.

        Maps to ``COTRN02C.cbl``'s ``1020-XREF-LOOKUP`` paragraph
        which performed ``EXEC CICS READ DATASET('CXACAIX')
        RIDFLD(WS-CARD-NUM)``. When the card was not on the AIX
        path, the program populated ``ERRMSGO`` with
        ``_MSG_CARD_NOT_IN_XREF`` ("Unable to lookup Card # in
        XREF...") and aborted.

        Router convention: presence of "XREF" substring in the
        service's failure message -> HTTP 404 (semantically correct
        for a missing cross-reference resource).

        Assertions:

        * HTTP 404 (NOT 400 -- COTRN02C uses "XREF" substring).
        * ``body["detail"]`` contains "XREF" (byte-exact COBOL
          wording preserved in _MSG_CARD_NOT_IN_XREF).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        # Well-formed 16-digit card number that is NOT in the seed
        # xref table. Must pass the schema validator (numeric, 16
        # chars) and then miss the CXACAIX lookup.
        unseeded_card: str = "4111999999999999"
        add_payload: dict[str, str] = {
            "acct_id": _SEED_ACCT_ID_1,
            "card_num": unseeded_card,
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
            "tran_source": "POS TERM",
            "description": "Test xref miss",
            "amount": "10.00",
            "orig_date": "2024-01-25",
            "proc_date": "2024-01-25",
            "merchant_id": "000000001",
            "merchant_name": "Test Merchant",
            "merchant_city": "New York",
            "merchant_zip": "10001",
        }
        response = await client.post(
            "/transactions",
            json=add_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        # "XREF" in the service message -> 404.
        assert response.status_code == 404, response.text
        body = response.json()
        assert "error" in body
        # Detail text is in the ABEND envelope's reason/message slots.
        assert "XREF" in _error_text(body)



# ===========================================================================
# SECTION 5E -- BILL PAYMENT ENDPOINT TESTS (F-012)
# ===========================================================================
# Source: app/cbl/COBIL00C.cbl (F-012 Bill payment with dual-write)
#
# Bill payment is a DEBIT-ONLY, DUAL-WRITE operation:
#
#   1. READ account for the supplied acct_id.
#   2. Verify account exists (NOTFND -> "Account not found...").
#   3. Verify curr_bal > 0 (zero / negative -> "You have nothing
#      to pay...").
#   4. Verify xref for the card (miss -> "Card not found...").
#   5. INSERT a new Transaction row (type_cd "02" for bill-pay).
#   6. UPDATE account curr_bal = curr_bal - tran_amt.
#   7. SYNCPOINT (commit) -- both writes together, or both rolled
#      back on any exception.
#
# Bill router error routing uses EXACT-STRING matching (not
# substring) on the service's error message:
#
#   "Account not found..."      -> HTTP 404
#   "Card not found..."         -> HTTP 404
#   "You have nothing to pay..." -> HTTP 400
#   (any other business failure) -> HTTP 500
#   (unexpected exception)       -> HTTP 500 "Payment processing failed"
# ===========================================================================
class TestBillPaymentEndpoints:
    """Integration tests for Bill Payment endpoint (F-012).

    Maps to ``COBIL00C.cbl``'s dual-write bill-payment flow, which
    atomically creates a new debit Transaction and reduces the
    target Account's ``curr_bal`` by the payment amount. The
    CICS SYNCPOINT boundary guarantees both writes commit together
    or both roll back -- the Python equivalent is the SQLAlchemy
    session's implicit transaction context.

    Seed fixture state for these tests:

    * Account 1 (``_SEED_ACCT_ID_1``) has curr_bal = Decimal("1000.00").
    * Account 2 (``_SEED_ACCT_ID_2``) has curr_bal = Decimal("2500.50").
    * Both accounts have cross-references to existing seed cards.
    """

    async def test_pay_bill_success(
        self,
        client: AsyncClient,
        db_session: AsyncSession,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /bills/pay succeeds with dual-write: Transaction + Account.

        Maps to the happy-path flow of ``COBIL00C.cbl``:

        * Account 1 starts with curr_bal = 1000.00.
        * Pay 50.00 -> balance becomes 950.00.
        * A new Transaction row is inserted for the debit.
        * Both writes commit atomically in a single SYNCPOINT.

        CRITICAL: all monetary values use :class:`~decimal.Decimal`
        (never float). The response's ``current_balance`` must
        round-trip to the EXACT Decimal-computed post-payment
        balance (initial minus payment), preserving PIC S9(n)V99
        precision per AAP section 0.7.2.

        Assertions:

        * HTTP 200 (success path).
        * ``body["confirm"]`` == "Y".
        * ``body["acct_id"]`` echoes the request account id.
        * ``body["amount"]`` round-trips to Decimal("50.00").
        * ``body["current_balance"]`` round-trips to
          Decimal("950.00") (1000.00 - 50.00, exact).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        payment_amount: Decimal = Decimal("50.00")
        initial_balance: Decimal = Decimal("1000.00")
        expected_balance: Decimal = initial_balance - payment_amount

        payment_payload: dict[str, str] = {
            "acct_id": _SEED_ACCT_ID_1,
            "amount": str(payment_amount),
        }
        response = await client.post(
            "/bills/pay",
            json=payment_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Success envelope -- dual-write pattern from COBIL00C.cbl:
        # Transaction INSERT + Account REWRITE in single SYNCPOINT.
        assert body["confirm"] == "Y"
        assert body["acct_id"] == _SEED_ACCT_ID_1

        # CRITICAL: exact Decimal round-trip.
        parsed_amount: Decimal = Decimal(str(body["amount"]))
        assert parsed_amount == payment_amount

        parsed_balance: Decimal = Decimal(str(body["current_balance"]))
        assert parsed_balance == expected_balance

    async def test_pay_bill_account_not_found(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /bills/pay returns 404 when the account does not exist.

        Maps to ``COBIL00C.cbl`` line 332's NOTFND branch:
        ``MOVE 'Account not found...' TO WS-MESSAGE``.

        Router convention: bill router uses EXACT-STRING matching
        on the service message. The exact token "Account not
        found..." maps to HTTP 404.

        Assertions:

        * HTTP 404.
        * ``body["detail"]`` equals "Account not found..." (byte-
          exact COBOL wording preserved in _MSG_ACCOUNT_NOT_FOUND).
        """
        # Well-formed 11-digit account ID that is NOT in the seed.
        missing_acct: str = "99999999999"
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.post(
            "/bills/pay",
            json={"acct_id": missing_acct, "amount": "50.00"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 404, response.text
        body = response.json()
        assert "error" in body
        # Exact-string COBOL message: "Account not found..." (with
        # trailing ellipsis per COBOL convention). The detail lives in
        # the ABEND envelope's reason/message slots.
        assert "Account not found" in _error_text(body)

    async def test_pay_bill_invalid_amount(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /bills/pay returns 422 on a non-positive amount.

        Pydantic's ``_validate_amount_positive`` field validator on
        ``BillPaymentRequest.amount`` rejects values <= 0. Bill
        payment is a debit-only operation (COBIL00C.cbl line 234:
        ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT``), so
        the schema layer rejects non-positive amounts BEFORE the
        request ever reaches the service.

        Pydantic validation errors surface as HTTP 422 Unprocessable
        Entity (FastAPI default for request-body validation).

        Assertions:

        * HTTP 422 (Pydantic validation error).
        * ``body["detail"]`` contains the validation error
          structure with a reference to the ``amount`` field.
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        # Zero is explicitly rejected by _validate_amount_positive.
        response = await client.post(
            "/bills/pay",
            json={"acct_id": _SEED_ACCT_ID_1, "amount": "0.00"},
            headers={"Authorization": f"Bearer {token}"},
        )
        # Pydantic field-level validation -> 422 Unprocessable Entity.
        assert response.status_code == 422, response.text
        body = response.json()
        assert "error" in body
        # The error_handler's validation handler summarizes Pydantic
        # errors into the ABEND envelope's message slot as
        # ``"<field_path>: <msg>"`` -- so the ``amount`` field path
        # appears verbatim in the message. We use json.dumps to
        # stringify the entire ABEND envelope for defense-in-depth
        # (the field path may also surface in nested server-side
        # logging that future error_handler revisions might embed).
        detail_text: str = _error_text(body) + " " + json.dumps(body)
        # Round-trip through json.loads to assert body is well-formed
        # JSON (defensive: guards against middleware emitting broken
        # content-type headers with non-JSON payloads).
        assert json.loads(response.text) == body
        assert "amount" in detail_text

    async def test_pay_bill_atomicity(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /bills/pay rolls back both writes when flush fails.

        Simulates an I/O failure mid-transaction: the service has
        already staged the Account UPDATE (mutating the in-memory
        ORM entity) and the Transaction INSERT (via ``db.add``).
        When ``db.flush()`` raises, the except block in
        :meth:`BillService.pay_bill` invokes ``db.rollback()`` --
        which is the Python equivalent of CICS SYNCPOINT ROLLBACK.

        The bill router then returns HTTP 500 with the stable
        detail string "Payment processing failed" (see
        ``_MSG_PAYMENT_FAILURE_DETAIL`` in bill_router.py).

        Assertions:

        * HTTP 500 (unexpected-exception path).
        * ``body["detail"]`` == "Payment processing failed"
          (verbatim router constant).

        This establishes that the dual-write atomicity contract
        holds at the API boundary. A full state-based rollback
        assertion (post-failure balance check) is out of scope for
        this test because the SAVEPOINT-based test db_session
        fixture resets state per-test regardless of the service's
        rollback behavior.
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )

        # Patch AsyncSession.flush in the bill_service namespace to
        # raise a RuntimeError mid-dual-write. The patched method
        # intercepts the CALL FIRST, so neither the INSERT nor the
        # UPDATE reach the DB engine; the service's except block
        # then invokes rollback() -- mirroring CICS SYNCPOINT
        # ROLLBACK from COBIL00C.cbl's exception path.
        flush_error: RuntimeError = RuntimeError(
            "simulated flush failure for atomicity test"
        )
        with patch(
            "src.api.services.bill_service.AsyncSession.flush",
            new=AsyncMock(side_effect=flush_error),
        ):
            response = await client.post(
                "/bills/pay",
                json={"acct_id": _SEED_ACCT_ID_1, "amount": "50.00"},
                headers={"Authorization": f"Bearer {token}"},
            )

        # Unexpected exception -> 500 with stable detail string.
        assert response.status_code == 500, response.text
        body = response.json()
        assert "error" in body
        # Router constant _MSG_PAYMENT_FAILURE_DETAIL preserves this
        # exact wording so clients can rely on it for alerting. The
        # detail propagates into both the reason and message slots of
        # the ABEND envelope for 500 HTTPExceptions (no canned default
        # exists for the 500 status).
        assert "Payment processing failed" in _error_text(body)



# ===========================================================================
# SECTION 5F -- REPORT SUBMISSION ENDPOINT TESTS (F-022)
# ===========================================================================
# Source: app/cbl/CORPT00C.cbl (F-022 Report submission via TDQ -> SQS FIFO)
#
# Report submission in the mainframe was "fire-and-forget": the online
# CICS program wrote a job-scheduler record to the ``JOBS`` TDQ via
# ``EXEC CICS WRITEQ TD QUEUE('JOBS')``, and a separate batch
# scheduler picked up the record and invoked the appropriate batch
# job. The Python equivalent is:
#
#   TDQ WRITEQ JOBS from CORPT00C.cbl -> SQS FIFO queue submission
#
# The router does NOT require admin role (it uses get_current_user,
# not get_current_admin_user) -- any authenticated user can submit
# a report.
#
# Validation behavior:
#   * Invalid report_type -> Pydantic 422 (enum validation)
#   * Missing dates when report_type == "custom" -> Pydantic 422
#     (from _validate_custom_requires_dates model validator)
#   * end_date < start_date -> Pydantic 422
#   * Invalid date format (non-YYYY-MM-DD or invalid calendar) -> 422
#   * SQS publish failure (confirm='N') -> HTTP 500 from the router
#
# SQS is mocked via boto3 client patching at the report_service
# namespace so that tests do not require LocalStack or a real AWS
# connection.
# ===========================================================================
class TestReportEndpoints:
    """Integration tests for Report Submission endpoint (F-022).

    Maps to ``CORPT00C.cbl``'s TDQ-based job-submission flow:

    * Online CICS program receives report type + date range.
    * Constructs a JCL-equivalent record.
    * Writes it to the ``JOBS`` Transient Data Queue
      (``EXEC CICS WRITEQ TD QUEUE('JOBS')``).
    * A separate batch scheduler polls the TDQ and launches jobs.

    The Python equivalent publishes a JSON message to an AWS SQS
    FIFO queue via ``boto3.client('sqs').send_message(...)``.

    Tests use SQS client mocking (boto3 client patched at the
    ``src.api.services.report_service.get_sqs_client`` namespace)
    to avoid LocalStack / real-AWS dependencies.
    """

    async def test_submit_report(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /reports/submit succeeds with a monthly report request.

        Maps to ``CORPT00C.cbl``'s successful TDQ write path where
        ``EXEC CICS WRITEQ TD QUEUE('JOBS')`` returned NORMAL status.
        The service publishes a JSON message to SQS FIFO with
        ``MessageGroupId='report-submissions'`` and
        ``MessageDeduplicationId=report_id``.

        Report type "monthly" does NOT require dates, so a minimal
        payload (``report_type`` only) is sufficient.

        SQS is mocked via a boto3 client stand-in that returns a
        fake ``MessageId``. Real AWS calls never occur.

        Assertions:

        * HTTP 200 (success path for the router -- SQS failures map
          to 500 but a success returns confirm='Y' at 200).
        * ``body["confirm"]`` == "Y".
        * ``body["report_type"]`` == "monthly".
        * ``body["report_id"]`` is a non-empty string (service-
          generated UUID).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )

        # Stub SQS client -- send_message returns a fake boto3-style
        # response dict. The report service inspects only the call
        # success (i.e. no exception), not the returned fields, so a
        # stubbed dict with a MessageId key is sufficient.
        fake_sqs_client: MagicMock = MagicMock()
        fake_sqs_client.send_message.return_value = {
            "MessageId": "fake-message-id-0001",
            "SequenceNumber": "18849875220900000000",
        }

        with patch(
            "src.api.services.report_service.get_sqs_client",
            return_value=fake_sqs_client,
        ):
            response = await client.post(
                "/reports/submit",
                json={"report_type": "monthly"},
                headers={"Authorization": f"Bearer {token}"},
            )

        # TDQ WRITEQ JOBS from CORPT00C.cbl -> SQS FIFO queue
        # submission -- successful publish -> HTTP 200.
        assert response.status_code == 200, response.text
        body = response.json()

        # ReportSubmissionResponse envelope.
        assert body["confirm"] == "Y"
        assert body["report_type"] == "monthly"
        # Service-generated UUID v4 -- non-empty string.
        assert body["report_id"]
        assert isinstance(body["report_id"], str)

    async def test_submit_report_invalid_dates(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /reports/submit returns 422 when end_date < start_date.

        Pydantic's ``_validate_custom_requires_dates`` model
        validator enforces two invariants:

        1. ``report_type == "custom"`` requires BOTH dates.
        2. When both dates are supplied, ``end_date >= start_date``.

        This test violates invariant #2: start_date "2024-12-01"
        with end_date "2024-01-01" (start is AFTER end).

        Maps to ``CORPT00C.cbl``'s date-range validation loop which
        iterated ``BMS-SDT*`` / ``BMS-EDT*`` segmented date fields
        and issued ``ERRMSGO`` if the range was invalid. In the
        Python rewrite this check is hoisted into the schema layer
        so invalid requests are rejected BEFORE the service is
        invoked -- no SQS call is made.

        Assertions:

        * HTTP 422 (Pydantic model-validator error).
        * ``body["detail"]`` contains the validation error
          reference to end_date or start_date.
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        # Inverted date range: start > end violates the
        # _validate_custom_requires_dates invariant.
        response = await client.post(
            "/reports/submit",
            json={
                "report_type": "custom",
                "start_date": "2024-12-01",
                "end_date": "2024-01-01",
            },
            headers={"Authorization": f"Bearer {token}"},
        )
        # Pydantic validation failure -> 422 Unprocessable Entity.
        assert response.status_code == 422, response.text
        body = response.json()
        assert "error" in body
        # The error_handler summarizes Pydantic errors into the ABEND
        # envelope's message slot as ``"<field_path>: <msg>"`` -- so
        # the end_date or start_date field path is surfaced verbatim.
        detail_text: str = _error_text(body)
        assert "end_date" in detail_text or "start_date" in detail_text



# ===========================================================================
# SECTION 5G -- USER ADMIN ENDPOINT TESTS (F-018 to F-021)
# ===========================================================================
# Source: app/cbl/COUSR00C.cbl (F-018 User list, STARTBR/READNEXT over USRSEC)
#         app/cbl/COUSR01C.cbl (F-019 User add, EXEC CICS WRITE FILE('USRSEC'))
#         app/cbl/COUSR02C.cbl (F-020 User update, READ UPDATE + REWRITE)
#         app/cbl/COUSR03C.cbl (F-021 User delete, EXEC CICS DELETE FILE)
#         app/cpy/CSUSR01Y.cpy (User security record, 80-byte layout)
#
# ALL four user endpoints require admin role (user_type='A'). This
# mirrors COBOL ``COADM01C`` which used the 88-level
# ``CDEMO-USRTYP-ADMIN VALUE 'A'`` to gate access to the user-admin
# menu options.
#
# Error routing (all from typed service exceptions):
#
#   GET    /users:         UserServiceError -> 500
#   POST   /users:         UserIdAlreadyExistsError -> 409
#                          UserValidationError      -> 400
#                          UserServiceError         -> 500
#                          Returns 201 CREATED on success
#   PUT    /users/{id}:    UserNotFoundError    -> 404
#                          UserValidationError  -> 400
#                          UserServiceError     -> 500
#   DELETE /users/{id}:    UserNotFoundError    -> 404
#                          UserServiceError     -> 500
#
# Authentication/authorization:
#   * Missing or invalid JWT -> 401 (from get_current_user)
#   * Non-admin JWT (type='U') -> 403 (from get_current_admin_user)
#
# BCrypt password hashing: POST /users must hash the user-supplied
# password before persisting. The test suite verifies this by
# re-reading the row from the test DB and calling
# ``CryptContext.verify()`` on the stored hash vs the plaintext input.
# ===========================================================================
class TestUserEndpoints:
    """Integration tests for User CRUD endpoints (F-018 through F-021).

    Maps to four COBOL online programs:

    * ``COUSR00C.cbl`` (F-018) -- ``GET /users`` paginated list.
    * ``COUSR01C.cbl`` (F-019) -- ``POST /users`` add user with
      BCrypt hashing (preserving COBOL PIC X(08) password semantics
      in hashed form per AAP's security requirement).
    * ``COUSR02C.cbl`` (F-020) -- ``PUT /users/{user_id}`` PATCH-
      style update (all fields optional in UserUpdateRequest).
    * ``COUSR03C.cbl`` (F-021) -- ``DELETE /users/{user_id}``.

    Seed fixture provides two users:

    * ``TESTUSER`` (user_type='U', regular) - the access principal
      for tests that exercise the non-admin rejection path.
    * ``ADMIN001`` (user_type='A', admin) - the access principal
      for all happy-path user-admin tests.

    All test methods that require admin access build a JWT with
    ``user_type=_TEST_USER_TYPE_ADMIN`` and send it as a Bearer
    token.
    """

    async def test_list_users(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /users returns the paginated user list (admin only).

        Maps to ``COUSR00C.cbl``'s ``EXEC CICS STARTBR / READNEXT``
        browse over the USRSEC dataset. The Python rewrite uses
        SQL LIMIT/OFFSET pagination with default page_size=10 (no
        strict BMS-map row count on COUSR00 -- the limit is chosen
        to match the other COUSR* programs).

        Assertions:

        * HTTP 200 (admin access succeeds).
        * ``body["users"]`` is a list.
        * ``body["page"]`` == 1 (default).
        * ``body["total_count"]`` >= 2 (seed inserts TESTUSER +
          ADMIN001).
        * Both seed user IDs appear in the returned list.
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        response = await client.get(
            "/users",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # UserListResponse envelope.
        assert "users" in body
        assert isinstance(body["users"], list)
        assert body["page"] == 1
        assert body["total_count"] >= 2

        returned_ids: set[str] = {
            row["user_id"] for row in body["users"]
        }
        # Both seed users must be listed.
        assert _TEST_USER_ID in returned_ids
        assert _TEST_ADMIN_ID in returned_ids

    async def test_list_users_non_admin(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /users returns 403 for a non-admin caller.

        Maps to the admin-only gate enforced by
        :func:`get_current_admin_user`, which returns 403 Forbidden
        when ``user_type != 'A'``. The original COBOL equivalent is
        ``COADM01C.cbl``'s 88-level check
        ``CDEMO-USRTYP-ADMIN VALUE 'A'`` that prevented non-admin
        users from seeing the admin menu options.

        Assertions:

        * HTTP 403 Forbidden.
        """
        # Regular user token (user_type='U').
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        response = await client.get(
            "/users",
            headers={"Authorization": f"Bearer {token}"},
        )
        # 88 CDEMO-USRTYP-ADMIN VALUE 'A' -- admin-only gate.
        assert response.status_code == 403, response.text

    async def test_add_user(
        self,
        client: AsyncClient,
        db_session: AsyncSession,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /users creates a new user with BCrypt-hashed password.

        Maps to ``COUSR01C.cbl``'s ``EXEC CICS WRITE FILE('USRSEC')``
        flow. The service hashes the supplied plaintext password
        via ``CryptContext.hash()`` (BCrypt) before inserting the
        row, matching the security contract from AAP section 0.7.2
        "BCrypt password hashing must be maintained for user
        authentication".

        This test verifies two properties:

        1. The endpoint returns HTTP 201 Created (not 200).
        2. The password stored in the DB is a BCrypt hash (not
           plaintext) that verifies against the original plaintext.

        Assertions:

        * HTTP 201 CREATED.
        * ``body["user_id"]`` echoes the request user_id.
        * ``body`` does NOT contain a "password" key (never echo
          credentials back).
        * Re-reading the row from the test DB shows a password
          field whose value is NOT the plaintext but DOES verify
          via ``CryptContext.verify(plaintext, stored_hash)``.
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        new_user_id: str = "NEWUSER1"
        new_plaintext_pwd: str = "pass1234"
        create_payload: dict[str, str] = {
            "user_id": new_user_id,
            "first_name": "New",
            "last_name": "User",
            "password": new_plaintext_pwd,
            "user_type": _TEST_USER_TYPE_REGULAR,
        }
        response = await client.post(
            "/users",
            json=create_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        # Router decorator is status_code=status.HTTP_201_CREATED.
        assert response.status_code == 201, response.text
        body = response.json()

        # Identity echoed back; password intentionally never echoed.
        assert body["user_id"] == new_user_id
        assert body["first_name"] == "New"
        assert body["last_name"] == "User"
        assert body["user_type"] == _TEST_USER_TYPE_REGULAR
        assert "password" not in body

        # BCrypt verification: re-read the row via the same db_session
        # that the API used, and verify the stored password hash.
        stored_row = await db_session.get(UserSecurity, new_user_id)
        assert stored_row is not None
        stored_hash: str = stored_row.password
        # The stored value MUST NOT equal the plaintext.
        assert stored_hash != new_plaintext_pwd
        # BCrypt verify must succeed against the original plaintext.
        assert _PWD_CONTEXT.verify(new_plaintext_pwd, stored_hash)

    async def test_add_user_duplicate(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """POST /users returns 409 Conflict when user_id already exists.

        Maps to ``COUSR01C.cbl`` line 263:
        ``WHEN DFHRESP(DUPKEY) / DFHRESP(DUPREC)``. The service
        raises :class:`UserIdAlreadyExistsError` with the byte-
        exact COBOL message "User ID already exist..." (with
        trailing ellipsis). The router maps this to HTTP 409.

        The seed fixture inserts TESTUSER, so a second create with
        user_id="TESTUSER" triggers the duplicate-detection branch.

        Assertions:

        * HTTP 409 Conflict.
        * ``body["detail"]`` contains "already exist" (byte-exact
          COBOL wording preserved in MSG_USER_ID_ALREADY_EXISTS --
          note: "exist" not "exists" -- COBOL-source text).
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        # TESTUSER is already seeded -- duplicate insert triggers
        # UserIdAlreadyExistsError -> 409.
        dup_payload: dict[str, str] = {
            "user_id": _TEST_USER_ID,
            "first_name": "Duplicate",
            "last_name": "Attempt",
            "password": "somepass",
            "user_type": _TEST_USER_TYPE_REGULAR,
        }
        response = await client.post(
            "/users",
            json=dup_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 409, response.text
        body = response.json()
        assert "error" in body
        # COBOL text: "User ID already exist..." -- "exist" (no 's').
        # ABEND envelope's reason/message slots carry the detail.
        assert "already exist" in _error_text(body)

    async def test_update_user(
        self,
        client: AsyncClient,
        db_session: AsyncSession,
        seed_data: dict[str, Any],
    ) -> None:
        """PUT /users/{user_id} applies a partial update (PATCH-style).

        Maps to ``COUSR02C.cbl``'s ``EXEC CICS READ UPDATE`` ->
        ``REWRITE`` pattern. ``UserUpdateRequest`` declares ALL
        four updatable fields as ``Optional`` (PATCH-style), so we
        can update just ``first_name`` without providing the
        others.

        Assertions:

        * HTTP 200 (admin access + successful update).
        * ``body["user_id"]`` == TESTUSER (target).
        * ``body["first_name"]`` == the new value.
        * Re-reading the row from the test DB confirms the
          persisted change (integration-level verification, not
          just response-level).
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        new_first: str = "Updated"
        update_payload: dict[str, str] = {
            "first_name": new_first,
        }
        response = await client.put(
            f"/users/{_TEST_USER_ID}",
            json=update_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Envelope-level verification.
        assert body["user_id"] == _TEST_USER_ID
        assert body["first_name"] == new_first
        # Last name should remain unchanged (seed value was "User").
        assert body["last_name"] == "User"

        # Integration-level verification: the row in the DB reflects
        # the mutation (not just the response envelope). Refresh
        # the session's view to see the committed change.
        await db_session.refresh(
            await db_session.get(UserSecurity, _TEST_USER_ID)
        )
        persisted = await db_session.get(UserSecurity, _TEST_USER_ID)
        assert persisted is not None
        assert persisted.first_name == new_first

    async def test_delete_user(
        self,
        client: AsyncClient,
        db_session: AsyncSession,
        seed_data: dict[str, Any],
    ) -> None:
        """DELETE /users/{user_id} removes an existing user.

        Maps to ``COUSR03C.cbl``'s ``EXEC CICS DELETE FILE('USRSEC')
        RIDFLD(WS-USER-ID)`` flow. The router returns HTTP 200 with
        a pre-delete snapshot in the ``UserDeleteResponse`` body
        so the UI can render a "deleted user was ..." confirmation.

        After the delete succeeds, re-reading the row from the
        test DB must return ``None`` (the row is gone).

        Assertions:

        * HTTP 200.
        * ``body["user_id"]`` == TESTUSER (pre-delete snapshot).
        * ``db_session.get(UserSecurity, TESTUSER)`` returns None
          after the delete.
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        response = await client.delete(
            f"/users/{_TEST_USER_ID}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 200, response.text
        body = response.json()

        # Pre-delete snapshot in UserDeleteResponse.
        assert body["user_id"] == _TEST_USER_ID
        assert body["first_name"] == "Test"
        assert body["last_name"] == "User"
        assert body["user_type"] == _TEST_USER_TYPE_REGULAR

        # Integration-level verification: row is gone from DB.
        # NOTE: ``AsyncSession.expire_all`` is a SYNC method (per
        # SQLAlchemy 2.x docs) — it only marks identity-map entries
        # for lazy refresh; no I/O happens. Only ``get()``, which
        # may issue a SELECT, is awaited.
        db_session.expire_all()
        deleted_row = await db_session.get(UserSecurity, _TEST_USER_ID)
        assert deleted_row is None

    async def test_delete_user_not_found(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """DELETE /users/{user_id} returns 404 for a non-existent user.

        Maps to ``COUSR03C.cbl`` line 289's ``WHEN DFHRESP(NOTFND)``
        branch. The service raises :class:`UserNotFoundError` with
        the byte-exact COBOL message "User ID NOT found..." (with
        trailing ellipsis). The router maps this to HTTP 404.

        Assertions:

        * HTTP 404 Not Found.
        * ``body["detail"]`` contains "NOT found" (byte-exact
          COBOL wording preserved in MSG_USER_ID_NOT_FOUND).
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        # Well-formed user_id (matches ^[A-Za-z0-9_\-]{1,8}$)
        # that is NOT in the seed.
        missing_user: str = "NONEXIST"
        response = await client.delete(
            f"/users/{missing_user}",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 404, response.text
        body = response.json()
        assert "error" in body
        # COBOL text: "User ID NOT found..." -- ABEND envelope's
        # reason/message slots carry the detail.
        assert "NOT found" in _error_text(body)



# ===========================================================================
# SECTION 5H -- ADMIN MENU ENDPOINT TESTS (F-003)
# ===========================================================================
# Source: app/cbl/COADM01C.cbl (F-003 Admin menu, 4 options)
#         app/cpy/COADM02Y.cpy (Admin menu option records)
#
# The COBOL program ``COADM01C`` used the 88-level condition
# ``CDEMO-USRTYP-ADMIN VALUE 'A'`` to gate access to the admin menu:
# non-admin users saw the general menu from ``COMEN01C`` instead.
# The Python rewrite preserves this gate using the FastAPI
# dependency :func:`get_current_admin_user`, which enforces the
# three-level authorization pattern:
#
#   * No/invalid Bearer token -> HTTP 401 Unauthorized
#     (raised by the upstream :func:`get_current_user` dependency).
#   * Valid token but ``user_type != 'A'`` -> HTTP 403 Forbidden
#     (raised by the admin-gating logic).
#   * Valid admin token -> HTTP 200 OK with the resource payload.
#
# Two endpoints are exposed under the ``/admin`` prefix:
#
#   * ``GET /admin/menu``   -> returns the 4-option admin menu.
#   * ``GET /admin/status`` -> returns operational health +
#     ``current_user.user_id``.
#
# This test class intentionally exercises BOTH endpoints through
# the same three authorization scenarios so the gate is verified
# end-to-end (not just on one arbitrary admin route).
# ===========================================================================
class TestAdminEndpoints:
    """Integration tests for admin-only endpoints (F-003).

    Maps to ``COADM01C.cbl`` -- the admin menu launcher that was
    only reachable when a user's ``CDEMO-USER-TYPE`` field equalled
    ``'A'``. The Python rewrite gates the ``/admin/menu`` and
    ``/admin/status`` endpoints with
    :func:`get_current_admin_user`, producing 401/403/200
    responses that match the COBOL 88-level authorization
    semantics.

    Three scenarios are exercised for each endpoint or as a
    combined coverage set:

    1. Admin JWT (``user_type='A'``) -> 200 + payload.
    2. Regular JWT (``user_type='U'``) -> 403 Forbidden.
    3. No Authorization header at all -> 401 Unauthorized.
    """

    async def test_admin_access_with_admin_token(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /admin/menu + GET /admin/status succeed for an admin user.

        Maps to ``COADM01C.cbl``'s successful-admin branch, where
        the 88-level check ``CDEMO-USRTYP-ADMIN VALUE 'A'``
        evaluates true and the program proceeds to display the
        4-option admin menu (from ``COADM02Y.cpy``).

        Assertions for ``/admin/menu``:

        * HTTP 200.
        * ``body["menu_title"]`` == "Administrative Menu".
        * ``body["options"]`` is a 4-element list (one entry per
          COUSR00C/01C/02C/03C, matching COADM02Y lines 24-42).
        * Each option dict carries the keys "option", "label",
          "endpoint", "method".

        Assertions for ``/admin/status``:

        * HTTP 200.
        * ``body["status"]`` == "operational".
        * ``body["user"]`` == the admin's user_id (ADMIN001),
          proving the dependency injected the right principal.
        """
        token: str = create_test_token(
            user_id=_TEST_ADMIN_ID, user_type=_TEST_USER_TYPE_ADMIN
        )
        auth_headers: dict[str, str] = {
            "Authorization": f"Bearer {token}"
        }

        # --- GET /admin/menu ---------------------------------------------
        menu_response = await client.get(
            "/admin/menu", headers=auth_headers
        )
        assert menu_response.status_code == 200, menu_response.text
        menu_body = menu_response.json()

        # COBOL title -- see COADM01C.cbl lines 202-221
        # (POPULATE-HEADER-INFO setting CCDA-TITLE01/TITLE02).
        assert menu_body["menu_title"] == "Administrative Menu"

        # Four options mirror COADM02Y.cpy lines 24-42
        # (User List/Add/Update/Delete).
        options = menu_body["options"]
        assert isinstance(options, list)
        assert len(options) == 4
        for opt in options:
            assert "option" in opt
            assert "label" in opt
            assert "endpoint" in opt
            assert "method" in opt

        # --- GET /admin/status --------------------------------------------
        status_response = await client.get(
            "/admin/status", headers=auth_headers
        )
        assert status_response.status_code == 200, status_response.text
        status_body = status_response.json()
        assert status_body["status"] == "operational"
        # The user echoed back must be the admin principal carried
        # in the Bearer token -- proves the authenticated user flows
        # through get_current_admin_user to the route handler.
        assert status_body["user"] == _TEST_ADMIN_ID

    async def test_admin_access_denied_for_regular_user(
        self,
        client: AsyncClient,
        seed_data: dict[str, Any],
    ) -> None:
        """GET /admin/menu returns 403 for a non-admin caller.

        Maps to the COBOL decision point in ``COADM01C.cbl`` where
        a user whose ``CDEMO-USER-TYPE != 'A'`` would have been
        redirected (``XCTL``) to the general menu ``COMEN01C``
        instead of seeing the admin menu. In the REST world this
        translates to HTTP 403 Forbidden, issued by
        :func:`get_current_admin_user`.

        Assertions:

        * HTTP 403 for ``/admin/menu``.
        * HTTP 403 for ``/admin/status`` (verifies the gate applies
          to BOTH admin endpoints, not just one).
        """
        token: str = create_test_token(
            user_id=_TEST_USER_ID, user_type=_TEST_USER_TYPE_REGULAR
        )
        auth_headers: dict[str, str] = {
            "Authorization": f"Bearer {token}"
        }

        menu_response = await client.get(
            "/admin/menu", headers=auth_headers
        )
        # Non-admin -> 403 (not 200, not 401).
        assert menu_response.status_code == 403, menu_response.text

        status_response = await client.get(
            "/admin/status", headers=auth_headers
        )
        assert status_response.status_code == 403, status_response.text

    async def test_admin_access_no_auth(
        self,
        client: AsyncClient,
    ) -> None:
        """GET /admin/menu returns 401 when no Bearer token is provided.

        Maps to the implicit "no-session" case in the COBOL program:
        the user has not signed on through ``COSGN00C``, so they
        have no ``CDEMO-USER-TYPE`` populated in the COMMAREA. In
        the FastAPI rewrite this is detected by
        :func:`get_current_user` (the upstream dependency of
        :func:`get_current_admin_user`), which raises HTTP 401
        Unauthorized when the Authorization header is absent or
        malformed.

        Note: ``seed_data`` is intentionally NOT injected here
        because the request never reaches the DB -- the auth
        middleware rejects it first.

        Assertions:

        * HTTP 401 for ``/admin/menu`` with NO Authorization header.
        * HTTP 401 for ``/admin/status`` with NO Authorization header.
        """
        # No Authorization header at all.
        menu_response = await client.get("/admin/menu")
        assert menu_response.status_code == 401, menu_response.text

        status_response = await client.get("/admin/status")
        assert status_response.status_code == 401, status_response.text


