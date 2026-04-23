# ============================================================================
# CardDemo - Database Integration Tests (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * VSAM DEFINE CLUSTER JCL jobs - app/jcl/ACCTFILE.jcl, CARDFILE.jcl,
#     CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl, TCATBALF.jcl, DUSRSECJ.jcl,
#     TRANIDX.jcl  (VSAM -> Aurora PostgreSQL)
#   * COBOL copybook record layouts - app/cpy/CVACT01Y.cpy, CVACT02Y.cpy,
#     CVACT03Y.cpy, CVCUS01Y.cpy, CVTRA05Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy,
#     CVTRA03Y.cpy, CVTRA04Y.cpy, CVTRA06Y.cpy, CSUSR01Y.cpy
#   * CICS online COBOL programs - app/cbl/COACTUPC.cbl (SYNCPOINT ROLLBACK),
#     COCRDUPC.cbl (READ UPDATE / REWRITE - optimistic concurrency)
#   * Fixture data - app/data/ASCII/acctdata.txt, carddata.txt, custdata.txt
# ----------------------------------------------------------------------------
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
"""Integration tests for CardDemo Aurora PostgreSQL database operations using Testcontainers. Validates VSAM-to-PostgreSQL migration for all 11 entity tables. Source: JCL provisioning jobs (app/jcl/*.jcl) and COBOL copybook record layouts (app/cpy/*.cpy) - Mainframe-to-Cloud migration.

This module is the canonical integration-test surface for the CardDemo
relational data layer. It exercises REAL PostgreSQL 16 (via
``testcontainers[postgres]``) - never SQLite, never an in-memory mock - so
that every behaviour asserted here reflects exactly what Aurora PostgreSQL
16 will do in production (AAP Section 0.7.2 "Integration tests with real
PostgreSQL").

Scope
-----
Twelve test classes validate, end-to-end, the behaviour that replaces the
mainframe VSAM KSDS persistence layer with Aurora PostgreSQL:

1. ``TestSchemaCreation``
   - every VSAM ``DEFINE CLUSTER`` in ``app/jcl/*.jcl`` is materialised as
   a ``CREATE TABLE`` with the correct columns, types, primary keys, and
   composite keys derived from the originating COBOL copybook layouts.

2. ``TestAccountCRUD``, ``TestCardCRUD``, ``TestCustomerCRUD``,
   ``TestTransactionCRUD``, ``TestCardCrossReferenceCRUD``,
   ``TestUserSecurityCRUD``
   - VSAM ``READ`` / ``WRITE`` / ``REWRITE`` / ``DELETE`` operations translate
   faithfully to SQL ``SELECT`` / ``INSERT`` / ``UPDATE`` / ``DELETE`` via the
   SQLAlchemy 2.x async ORM, preserving byte-exact values through the round
   trip.

3. ``TestConstraints``
   - VSAM ``DUPREC`` (duplicate-record) conditions map to PostgreSQL unique
   constraint violations (``IntegrityError``); NOT NULL semantics for
   required COBOL fields carry through to SQL constraints.

4. ``TestCompositePrimaryKeys``
   - composite keys declared in COBOL copybooks - ``TRAN-CAT-KEY``
   (CVTRA01Y.cpy, 3-part), ``DIS-GROUP-KEY`` (CVTRA02Y.cpy, 3-part), and the
   2-part ``TRAN-CAT-CD`` (CVTRA04Y.cpy) - map correctly to PostgreSQL
   multi-column primary keys.

5. ``TestTransactionRollback``
   - CICS ``SYNCPOINT ROLLBACK`` (COACTUPC.cbl, ~line 953) is preserved via
   SQLAlchemy's transactional context managers; dual-write atomicity
   (COBIL00C.cbl - Transaction INSERT + Account REWRITE) is guaranteed.

6. ``TestOptimisticConcurrency``
   - CICS ``READ UPDATE`` / ``REWRITE`` locking (COACTUPC.cbl, COCRDUPC.cbl)
   is replaced by SQLAlchemy's ``version_id_col`` feature; concurrent
   writers observing a stale ``version_id`` surface
   :class:`sqlalchemy.orm.exc.StaleDataError`.

7. ``TestMonetaryPrecision``
   - COBOL ``PIC S9(n)V99`` monetary semantics round-trip through PostgreSQL
   ``NUMERIC(15, 2)`` with EXACT :class:`decimal.Decimal` equality - no
   epsilon tolerance, no floating-point coercion. This is the most
   load-bearing correctness contract in the migration (AAP Section 0.7.2
   "Financial Precision").

Test Environment
----------------
* Database  - Real PostgreSQL 16 container spawned by
  :class:`testcontainers.postgres.PostgresContainer`. Matches Aurora
  PostgreSQL 16 compatibility declared in AAP Section 0.6.2 (AWS Service
  Dependencies).
* Driver    - :mod:`asyncpg` (the URL produced by
  ``PostgresContainer.get_connection_url(driver='asyncpg')`` is used
  verbatim for :func:`sqlalchemy.ext.asyncio.create_async_engine`).
* Schema    - Produced on module setup by
  ``Base.metadata.create_all(conn)`` (async variant); dropped on
  module teardown. This replaces every ``IDCAMS DEFINE CLUSTER`` step
  from the JCL provisioning jobs.
* Isolation - The per-test ``session`` fixture nests each test's work
  inside a SAVEPOINT; ``AsyncSession`` is configured with
  ``join_transaction_mode='create_savepoint'`` so that in-test calls to
  ``session.commit()`` translate to SAVEPOINT release rather than a real
  commit to the underlying connection. The outer transaction is
  unconditionally rolled back at teardown, giving every test a pristine
  database view. This mirrors the CICS ``SYNCPOINT ROLLBACK`` semantics of
  the source COBOL programs (COACTUPC.cbl ~line 953).

Monetary Precision Contract
---------------------------
Every financial assertion uses :class:`decimal.Decimal` with EXACT equality
- NEVER floating-point comparison, NEVER ``abs(a - b) < epsilon`` tolerance.
This is non-negotiable per AAP Section 0.7.2 "Financial Precision":
``Python decimal.Decimal with explicit two-decimal-place precision, matching
COBOL PIC S9(n)V99 semantics``. A single relaxation of this contract would
cascade through POSTTRAN (CBTRN02C.cbl), INTCALC (CBACT04C.cbl), CREASTMT
(CBSTM03A.CBL), and TRANREPT (CBTRN03C.cbl), corrupting billions of cents
of computed state over the life of the platform.

See Also
--------
AAP Section 0.2.3 - Feature classification (F-001 through F-022).
AAP Section 0.5.1 - File-by-File Transformation Plan - ``tests/integration/test_database.py``.
AAP Section 0.6.2 - AWS Service Dependencies (Aurora PostgreSQL, Testcontainers).
AAP Section 0.7.1 - Refactoring-Specific Rules (preserve behaviour; atomic dual-write;
optimistic concurrency).
AAP Section 0.7.2 - Financial Precision, Security, Testing Requirements.
``app/cbl/COACTUPC.cbl`` - Account Update with ``SYNCPOINT ROLLBACK``
(replaced by SQLAlchemy transactional rollback).
``app/cbl/COCRDUPC.cbl`` - Card Update with ``READ UPDATE`` / ``REWRITE``
(replaced by ``version_id_col`` optimistic concurrency).
``app/cpy/CVACT01Y.cpy`` - Account record layout (300B).
``app/cpy/CVACT02Y.cpy`` - Card record layout (150B).
``app/cpy/CVACT03Y.cpy`` - Card cross-reference record layout (50B).
``app/cpy/CVCUS01Y.cpy`` - Customer record layout (500B).
``app/cpy/CVTRA05Y.cpy`` - Transaction record layout (350B).
``app/cpy/CVTRA01Y.cpy`` - Transaction category balance record layout (50B).
``app/cpy/CVTRA02Y.cpy`` - Disclosure group record layout (50B).
``app/cpy/CVTRA03Y.cpy`` - Transaction type record layout (60B).
``app/cpy/CVTRA04Y.cpy`` - Transaction category record layout (60B).
``app/cpy/CVTRA06Y.cpy`` - Daily transaction staging record layout (350B).
``app/cpy/CSUSR01Y.cpy`` - User security record layout (80B).
``app/jcl/ACCTFILE.jcl`` - Account VSAM DEFINE CLUSTER (RECSZ(300 300), KEYS(11 0)).
``app/jcl/CARDFILE.jcl`` - Card VSAM DEFINE CLUSTER (RECSZ(150 150), KEYS(16 0)).
``app/jcl/CUSTFILE.jcl`` - Customer VSAM DEFINE CLUSTER (RECSZ(500 500), KEYS(9 0)).
``app/jcl/TRANFILE.jcl`` - Transaction VSAM DEFINE CLUSTER (RECSZ(350 350), KEYS(16 0)).
``app/jcl/XREFFILE.jcl`` - Cross-reference VSAM DEFINE CLUSTER (RECSZ(50 50), KEYS(16 0)).
``app/jcl/TCATBALF.jcl`` - Transaction category balance VSAM DEFINE CLUSTER (KEYS(17 0)).
``app/jcl/DUSRSECJ.jcl`` - User security VSAM DEFINE CLUSTER (RECSZ(80 80), KEYS(8 0)).
``app/jcl/TRANIDX.jcl`` - Transaction secondary index DEFINE PATH.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Standard library imports
# ---------------------------------------------------------------------------
from collections.abc import AsyncIterator, Iterator
from decimal import Decimal

# ---------------------------------------------------------------------------
# Third-party imports
# ---------------------------------------------------------------------------
import pytest
import pytest_asyncio
from sqlalchemy import Connection, inspect, select
from sqlalchemy.engine.interfaces import ReflectedColumn, ReflectedIndex
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.ext.asyncio.engine import AsyncEngine
from sqlalchemy.orm.exc import StaleDataError
from testcontainers.postgres import PostgresContainer

# ---------------------------------------------------------------------------
# First-party imports - every entity model in the CardDemo data layer.
#
# The package initializer ``src.shared.models/__init__.py`` eagerly re-exports
# all 11 entity classes, guaranteeing that every table is registered on
# ``Base.metadata`` by the time ``Base.metadata.create_all()`` runs in the
# ``create_tables`` fixture (replacing the JCL VSAM DEFINE CLUSTER jobs).
# ---------------------------------------------------------------------------
from src.shared.models import Base
from src.shared.models.account import Account
from src.shared.models.card import Card
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.customer import Customer
from src.shared.models.daily_transaction import DailyTransaction
from src.shared.models.disclosure_group import DisclosureGroup
from src.shared.models.transaction import Transaction
from src.shared.models.transaction_category import TransactionCategory
from src.shared.models.transaction_category_balance import TransactionCategoryBalance
from src.shared.models.transaction_type import TransactionType
from src.shared.models.user_security import UserSecurity

# ---------------------------------------------------------------------------
# Module-level markers:
# 1. ``pytest.mark.integration`` - selects this file when ``pytest -m
#    integration`` is used; registered in pyproject.toml
#    [tool.pytest.ini_options] markers.
# 2. ``pytest.mark.asyncio(loop_scope="module")`` - forces all async tests in
#    this module to share a single module-scoped asyncio event loop. This is
#    REQUIRED for the module-scoped ``async_engine`` and ``create_tables``
#    fixtures to work: asyncpg connections opened in one event loop cannot
#    be reused in another (they raise "Task got Future attached to a
#    different loop"). Aligning the test loop scope with the fixture loop
#    scope eliminates cross-loop futures.
# ---------------------------------------------------------------------------
pytestmark = [
    pytest.mark.integration,
    pytest.mark.asyncio(loop_scope="module"),
]


# ---------------------------------------------------------------------------
# Monetary precision test samples. Defined here (not inline) to make the
# "exact equality" contract visible at a glance. Every value is explicitly
# two-decimal-place canonical to match the COBOL V99 scale. Changing any
# of these constants is equivalent to changing the COBOL field definition
# and must be flagged in code review (AAP Section 0.7.1 "preserve existing
# functionality").
# ---------------------------------------------------------------------------
_DECIMAL_PRECISION_SAMPLES = [
    Decimal("0.00"),
    Decimal("0.01"),
    Decimal("100.00"),
    Decimal("1234.56"),
    Decimal("12345678.99"),
    Decimal("-500.25"),
    Decimal("9999999999.99"),  # PIC S9(10)V99 max
    Decimal("-9999999999.99"),  # PIC S9(10)V99 min (signed)
]


# ===========================================================================
# PHASE 2 - TEST FIXTURES
# ===========================================================================
# Testcontainers PostgreSQL replaces VSAM provisioning.
# ===========================================================================


# ---------------------------------------------------------------------------
# Fixture: postgres_container  (module scope)
# ---------------------------------------------------------------------------
# Replaces IDCAMS DEFINE CLUSTER from app/jcl/ACCTFILE.jcl, CARDFILE.jcl,
# CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl, TCATBALF.jcl, DUSRSECJ.jcl.
# Each of those JCL jobs provisioned one VSAM cluster on a z/OS volume; the
# single ``postgres:16`` container spun up here hosts the entire relational
# schema that supersedes all 10 VSAM KSDS + 3 AIX clusters.
#
# Module-scoped so that the container is started once per test module and
# reused across all tests in the module - container startup is the slowest
# operation in this suite (typically 3 - 5 s cold start).
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def postgres_container() -> Iterator[PostgresContainer]:
    """Start a real PostgreSQL 16 container and yield it.

    The container image ``postgres:16`` is chosen to match Aurora
    PostgreSQL 16 compatibility declared in AAP Section 0.6.2 (AWS Service
    Dependencies). Using the real PostgreSQL engine - rather than
    SQLite or an in-memory stub - is required by AAP Section 0.7.2
    "Integration tests with real PostgreSQL (via Testcontainers)".

    Yields
    ------
    PostgresContainer
        A running PostgreSQL 16 container with a ``test`` database,
        ``test``/``test`` credentials, and an exposed host port. Callers
        retrieve the connection URL via
        :py:meth:`PostgresContainer.get_connection_url`.
    """
    # Passing ``driver=None`` means no default psycopg2 prefix is added;
    # the async engine fixture below requests the asyncpg variant
    # explicitly via ``get_connection_url(driver='asyncpg')``.
    container = PostgresContainer("postgres:16", driver=None)
    container.start()
    try:
        yield container
    finally:
        container.stop()


# ---------------------------------------------------------------------------
# Fixture: async_engine  (module scope)
# ---------------------------------------------------------------------------
# Creates a SQLAlchemy 2.x AsyncEngine backed by the Testcontainers
# PostgreSQL instance above. The engine is constructed with the explicit
# asyncpg driver so every session / connection spawned from it runs real
# async I/O against the database (not a synchronous shim).
# ---------------------------------------------------------------------------
@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def async_engine(
    postgres_container: PostgresContainer,
) -> AsyncIterator[AsyncEngine]:
    """Yield a SQLAlchemy 2.x async engine bound to the test PostgreSQL.

    The URL format is ``postgresql+asyncpg://test:test@host:port/test`` -
    the ``+asyncpg`` driver tag tells SQLAlchemy to dispatch all I/O to
    the asyncpg driver, required for
    :class:`~sqlalchemy.ext.asyncio.AsyncSession`. The engine is disposed
    on module teardown to release pooled connections before the
    container is stopped.
    """
    url = postgres_container.get_connection_url(driver="asyncpg")
    engine = create_async_engine(url, echo=False, future=True)
    try:
        yield engine
    finally:
        # dispose() closes the underlying connection pool; calling this
        # before the Testcontainer is stopped avoids a noisy pool-closed
        # warning and mirrors the DISPOSE recipe from SQLAlchemy 2.x.
        await engine.dispose()


# ---------------------------------------------------------------------------
# Fixture: create_tables  (module scope, autouse=True)
# ---------------------------------------------------------------------------
# Schema creation replaces VSAM provisioning:
#   app/jcl/ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl, TRANFILE.jcl,
#   XREFFILE.jcl, TCATBALF.jcl, DUSRSECJ.jcl, TRANIDX.jcl.
# A single ``Base.metadata.create_all()`` call materialises all 11 tables
# plus the secondary-index B-trees declared via ``__table_args__`` on
# ``Card``, ``CardCrossReference``, and ``Transaction`` - faithfully
# replicating the 10 VSAM KSDS + 3 AIX cluster set.
# ---------------------------------------------------------------------------
@pytest_asyncio.fixture(scope="module", loop_scope="module", autouse=True)
async def create_tables(async_engine: AsyncEngine) -> AsyncIterator[None]:
    """Create the full CardDemo schema, yield, then drop the schema.

    This is marked ``autouse=True`` so every test in the module runs
    against a populated schema - no test needs to explicitly request it.
    Schema creation runs under ``async with engine.begin() as conn`` so
    the DDL executes inside a single transaction, matching the atomic
    PROVISION step semantics of the JCL provisioning jobs.
    """
    # Schema creation replaces VSAM provisioning: ACCTFILE.jcl,
    # CARDFILE.jcl, CUSTFILE.jcl, TRANFILE.jcl, XREFFILE.jcl,
    # TCATBALF.jcl, DUSRSECJ.jcl - one call, all 11 tables.
    async with async_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    try:
        yield
    finally:
        # Tear down the schema so a subsequent module run starts clean.
        # This also mirrors a "DELETE CLUSTER" teardown step.
        async with async_engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)


# ---------------------------------------------------------------------------
# Fixture: session  (function scope)
# ---------------------------------------------------------------------------
# SAVEPOINT rollback mirrors CICS SYNCPOINT ROLLBACK from COACTUPC.cbl -
# every test is wrapped in an outer transaction unconditionally rolled
# back on teardown, and the AsyncSession is configured so in-test commits
# release a SAVEPOINT rather than the outer transaction.
# ---------------------------------------------------------------------------
@pytest_asyncio.fixture(loop_scope="module")
async def session(async_engine: AsyncEngine) -> AsyncIterator[AsyncSession]:
    """Yield a SAVEPOINT-wrapped AsyncSession; roll back on teardown.

    The SAVEPOINT pattern ensures each test gets a clean slate without
    paying for a full ``CREATE TABLE`` cycle per test:

    1. A fresh connection is checked out of the engine's pool.
    2. An outer transaction is started on that connection.
    3. An AsyncSession is bound to the connection with
       ``join_transaction_mode='create_savepoint'`` - this tells
       SQLAlchemy to represent ``session.commit()`` as ``RELEASE
       SAVEPOINT`` and ``session.rollback()`` as ``ROLLBACK TO
       SAVEPOINT``, never affecting the outer transaction.
    4. The test body runs; state it persists via the session is
       visible to subsequent session reads within the same test.
    5. On teardown, the session is closed and the outer transaction is
       unconditionally rolled back, discarding every mutation the test
       performed.

    This is the canonical SQLAlchemy 2.x "rollback-after-test" idiom
    adapted for the async engine. See AAP Section 0.7.1 - rollback tests
    must preserve the CICS SYNCPOINT ROLLBACK semantics of COACTUPC.cbl.
    """
    # Check out a dedicated connection; the outer transaction lives on
    # this connection only, so any engine-wide pool activity is isolated.
    async with async_engine.connect() as connection:
        transaction = await connection.begin()
        try:
            async_session_factory = async_sessionmaker(
                bind=connection,
                class_=AsyncSession,
                expire_on_commit=False,
                # create_savepoint maps session.commit() onto RELEASE
                # SAVEPOINT so the outer transaction remains intact
                # until the fixture rolls it back at teardown.
                join_transaction_mode="create_savepoint",
            )
            async with async_session_factory() as async_session:
                yield async_session
        finally:
            # Unconditional rollback regardless of test outcome - mirrors
            # the defence-in-depth SYNCPOINT ROLLBACK pattern used
            # pervasively in the COBOL source programs.
            if transaction.is_active:
                await transaction.rollback()


# ---------------------------------------------------------------------------
# Helper builders - return a fully-populated entity instance with sensible
# defaults. Centralising this in helpers keeps test bodies focused on the
# behaviour under test rather than boilerplate field initialisation.
# Each helper's keyword arguments override the default values for
# test-specific assertions.
# ---------------------------------------------------------------------------
def _make_account(
    acct_id: str = "00000000001",
    active_status: str = "Y",
    curr_bal: Decimal = Decimal("1000.00"),
    credit_limit: Decimal = Decimal("5000.00"),
    cash_credit_limit: Decimal = Decimal("1000.00"),
    open_date: str = "2020-01-01",
    expiration_date: str = "2030-12-31",
    reissue_date: str = "2023-01-01",
    curr_cyc_credit: Decimal = Decimal("0.00"),
    curr_cyc_debit: Decimal = Decimal("0.00"),
    addr_zip: str = "12345",
    group_id: str = "DEFAULT",
) -> Account:
    """Return a fully-populated Account for CRUD / constraint tests."""
    return Account(
        acct_id=acct_id,
        active_status=active_status,
        curr_bal=curr_bal,
        credit_limit=credit_limit,
        cash_credit_limit=cash_credit_limit,
        open_date=open_date,
        expiration_date=expiration_date,
        reissue_date=reissue_date,
        curr_cyc_credit=curr_cyc_credit,
        curr_cyc_debit=curr_cyc_debit,
        addr_zip=addr_zip,
        group_id=group_id,
    )


def _make_card(
    card_num: str = "4111111111111111",
    acct_id: str = "00000000001",
    cvv_cd: str = "123",
    embossed_name: str = "JOHN DOE",
    expiration_date: str = "2030-12-31",
    active_status: str = "Y",
) -> Card:
    """Return a fully-populated Card for CRUD / constraint tests."""
    return Card(
        card_num=card_num,
        acct_id=acct_id,
        cvv_cd=cvv_cd,
        embossed_name=embossed_name,
        expiration_date=expiration_date,
        active_status=active_status,
    )


def _make_customer(
    cust_id: str = "000000001",
    first_name: str = "JOHN",
    middle_name: str = "QUINCY",
    last_name: str = "DOE",
    addr_line_1: str = "123 MAIN ST",
    ssn: str = "123456789",
    dob: str = "1980-05-15",
    fico_credit_score: int = 750,
) -> Customer:
    """Return a fully-populated Customer for CRUD / constraint tests."""
    return Customer(
        cust_id=cust_id,
        first_name=first_name,
        middle_name=middle_name,
        last_name=last_name,
        addr_line_1=addr_line_1,
        ssn=ssn,
        dob=dob,
        fico_credit_score=fico_credit_score,
    )


def _make_transaction(
    tran_id: str = "0000000000000001",
    type_cd: str = "01",
    cat_cd: str = "1001",
    source: str = "POS",
    description: str = "TEST TRANSACTION",
    amount: Decimal = Decimal("50.00"),
    card_num: str = "4111111111111111",
    orig_ts: str = "2024-01-01-12.00.00.000000",
    proc_ts: str = "2024-01-01-12.00.01.000000",
) -> Transaction:
    """Return a fully-populated Transaction for CRUD tests."""
    return Transaction(
        tran_id=tran_id,
        type_cd=type_cd,
        cat_cd=cat_cd,
        source=source,
        description=description,
        amount=amount,
        card_num=card_num,
        orig_ts=orig_ts,
        proc_ts=proc_ts,
    )


def _make_user_security(
    user_id: str = "USER0001",
    first_name: str = "TEST",
    last_name: str = "USER",
    # 60-char BCrypt digest placeholder - real salt/cost/hash tested in
    # auth-specific test modules; here the value's shape (length 60,
    # leading "$2b$") is what matters for the DB schema.
    password: str = "$2b$12$KIXvPdN.zWPlL0GjHJHQF.5w.cN0CrAqTnVOkOcHyNI/e0ZfKuYTu",
    usr_type: str = "U",
) -> UserSecurity:
    """Return a fully-populated UserSecurity for CRUD tests."""
    return UserSecurity(
        user_id=user_id,
        first_name=first_name,
        last_name=last_name,
        password=password,
        usr_type=usr_type,
    )


def _make_card_cross_reference(
    card_num: str = "4111111111111111",
    cust_id: str = "000000001",
    acct_id: str = "00000000001",
) -> CardCrossReference:
    """Return a fully-populated CardCrossReference."""
    return CardCrossReference(
        card_num=card_num,
        cust_id=cust_id,
        acct_id=acct_id,
    )


# ===========================================================================
# PHASE 3 - SCHEMA CREATION TESTS
# ===========================================================================
# Validates VSAM DEFINE CLUSTER -> CREATE TABLE migration.
#
# Every VSAM KSDS provisioned by an ``IDCAMS DEFINE CLUSTER`` step in
# ``app/jcl/*.jcl`` must materialise as a PostgreSQL table with:
#   * The column set derived from its originating COBOL copybook
#     (``app/cpy/CV*.cpy``).
#   * A primary key matching the VSAM KEYS(...) declaration.
#   * Secondary indexes matching any VSAM AIX PATH declarations.
# ---------------------------------------------------------------------------
class TestSchemaCreation:
    """Validate migration of 10 VSAM KSDS + 3 AIX to 11 relational tables.

    These tests introspect the live PostgreSQL schema via SQLAlchemy's
    Inspector rather than the ORM's in-Python model metadata; this
    catches any drift between the ORM model definitions and the DDL
    actually emitted by ``Base.metadata.create_all()``.
    """

    # All tests rely on ``async_engine`` rather than ``session`` - schema
    # introspection works at the engine/connection level, not through
    # the ORM session's identity map.

    async def test_all_11_tables_created(self, async_engine: AsyncEngine) -> None:
        """Every VSAM cluster maps to exactly one PostgreSQL table.

        # Validates migration of 10 VSAM KSDS + 3 AIX to 11 relational
        # tables (AAP Section 0.2.1 - VSAM catalog enumeration).
        """
        expected = {
            "accounts",  # ACCTFILE.jcl KEYS(11 0), RECSZ(300 300)
            "cards",  # CARDFILE.jcl KEYS(16 0), RECSZ(150 150)
            "customers",  # CUSTFILE.jcl KEYS(9 0),  RECSZ(500 500)
            "card_cross_references",  # XREFFILE.jcl KEYS(16 0), RECSZ(50 50)
            "transactions",  # TRANFILE.jcl KEYS(16 0), RECSZ(350 350)
            "transaction_category_balances",  # TCATBALF.jcl KEYS(17 0)
            "daily_transactions",  # DALYTRAN (CBTRN01C) staging
            "disclosure_groups",  # TRANDISC  (reference data)
            "transaction_types",  # TRANTYPE  (reference data, 7 rows)
            "transaction_categories",  # TRANCATG  (reference data, 18 rows)
            "user_security",  # DUSRSECJ.jcl KEYS(8 0), RECSZ(80 80)
        }

        # Cross-check that every ORM model advertises a tablename that
        # matches its expected entry above - this guards against silent
        # drift between the model classes and the DDL migration file
        # (db/migrations/V1__schema.sql).  DailyTransaction and
        # TransactionType are explicitly validated here per AAP schema
        # requirements (staging table + reference data table).
        assert DailyTransaction.__tablename__ == "daily_transactions", (
            "DailyTransaction model must map to 'daily_transactions' (CVTRA06Y.cpy, pre-POSTTRAN driver CBTRN01C.cbl)."
        )
        assert TransactionType.__tablename__ == "transaction_types", (
            "TransactionType model must map to 'transaction_types' (CVTRA03Y.cpy, TRANTYPE.jcl seed data, 7 rows)."
        )
        # Validate that the required DailyTransaction columns exist on
        # the ORM class - catches missing columns early (before touching
        # the live database).  Column descriptors are InstrumentedAttribute
        # objects bound at class declaration time.
        assert hasattr(DailyTransaction, "tran_id")
        assert hasattr(DailyTransaction, "type_cd")
        assert hasattr(DailyTransaction, "amount")
        assert hasattr(DailyTransaction, "card_num")
        # TransactionType reference data must expose tran_type + description
        # (CVTRA03Y.cpy: TRAN-TYPE 2-char PK + TRAN-TYPE-DESC 50-char).
        assert hasattr(TransactionType, "tran_type")
        assert hasattr(TransactionType, "description")

        def _inspect_tables(sync_conn: Connection) -> set[str]:
            return set(inspect(sync_conn).get_table_names())

        async with async_engine.connect() as conn:
            actual = await conn.run_sync(_inspect_tables)

        assert expected.issubset(actual), (
            f"Missing tables: expected at least {expected}, found {actual}. Missing: {expected - actual}."
        )
        # Reject stray tables - each extra table is a migration defect
        # because every table must originate from an explicit VSAM-to-
        # Aurora mapping (AAP Section 0.2.1).
        unexpected = actual - expected
        assert not unexpected, (
            f"Unexpected tables present: {unexpected}. Only the 11 tables mapped from VSAM KSDS are allowed."
        )

    async def test_account_table_columns(self, async_engine: AsyncEngine) -> None:
        """Account schema matches CVACT01Y.cpy record layout.

        # Schema matches ACCOUNT-RECORD from app/cpy/CVACT01Y.cpy
        # (RECLN 300) - ACCTFILE.jcl KEYS(11 0).
        """

        def _get_columns(sync_conn: Connection) -> dict[str, ReflectedColumn]:
            inspector = inspect(sync_conn)
            return {c["name"]: c for c in inspector.get_columns("accounts")}

        def _get_pk(sync_conn: Connection) -> list[str]:
            inspector = inspect(sync_conn)
            pk = inspector.get_pk_constraint("accounts")
            cols: list[str] = pk["constrained_columns"]
            return cols

        async with async_engine.connect() as conn:
            columns = await conn.run_sync(_get_columns)
            pk_cols = await conn.run_sync(_get_pk)

        # Field inventory derived from CVACT01Y.cpy (ACCT-ID through
        # ACCT-GROUP-ID) plus the ``version_id`` OCC counter that
        # replaces CICS READ-UPDATE pessimistic locking (AAP Section 0.4.3).
        #
        # Physical DB column names preserve the COBOL ``ACCT-`` prefix
        # (V1__schema.sql DDL and the ``mapped_column`` positional first
        # argument in ``src/shared/models/account.py``). The Python
        # attribute keys (via ``key="..."``) drop the prefix for
        # ergonomic ORM use, but SQLAlchemy's ``inspector.get_columns()``
        # reflects the physical schema — i.e. the prefixed names.
        required = {
            "acct_id",
            "acct_active_status",
            "acct_curr_bal",
            "acct_credit_limit",
            "acct_cash_credit_limit",
            "acct_open_date",
            "acct_expiration_date",
            "acct_reissue_date",
            "acct_curr_cyc_credit",
            "acct_curr_cyc_debit",
            "acct_addr_zip",
            "acct_group_id",
            "version_id",
        }
        assert required.issubset(columns.keys()), f"accounts table missing columns: {required - set(columns.keys())}"

        # Primary key must be ``acct_id`` (COBOL PIC 9(11) -> String(11)
        # preserving leading zeros; VSAM KEYS(11 0)).
        assert pk_cols == ["acct_id"], f"accounts PK must be ['acct_id'], got {pk_cols}"

        # Monetary columns must all be NUMERIC(15, 2) for exact decimal
        # representation matching COBOL PIC S9(10)V99.
        for mon_col in (
            "acct_curr_bal",
            "acct_credit_limit",
            "acct_cash_credit_limit",
            "acct_curr_cyc_credit",
            "acct_curr_cyc_debit",
        ):
            ct = columns[mon_col]["type"]
            assert getattr(ct, "precision", None) == 15, (
                f"accounts.{mon_col} must be NUMERIC(15, 2); got precision={getattr(ct, 'precision', None)}"
            )
            assert getattr(ct, "scale", None) == 2, (
                f"accounts.{mon_col} must be NUMERIC(15, 2); got scale={getattr(ct, 'scale', None)}"
            )

    async def test_card_table_columns(self, async_engine: AsyncEngine) -> None:
        """Card schema matches CVACT02Y.cpy record layout.

        # Schema matches CARD-RECORD from app/cpy/CVACT02Y.cpy
        # (RECLN 150) - CARDFILE.jcl KEYS(16 0).
        """

        def _get_columns(sync_conn: Connection) -> dict[str, ReflectedColumn]:
            inspector = inspect(sync_conn)
            return {c["name"]: c for c in inspector.get_columns("cards")}

        def _get_pk(sync_conn: Connection) -> list[str]:
            inspector = inspect(sync_conn)
            cols: list[str] = inspector.get_pk_constraint("cards")["constrained_columns"]
            return cols

        def _get_indexes(sync_conn: Connection) -> list[ReflectedIndex]:
            indexes: list[ReflectedIndex] = list(inspect(sync_conn).get_indexes("cards"))
            return indexes

        async with async_engine.connect() as conn:
            columns = await conn.run_sync(_get_columns)
            pk_cols = await conn.run_sync(_get_pk)
            indexes = await conn.run_sync(_get_indexes)

        # Physical DB column names use the COBOL ``CARD-`` prefix
        # (except the PK ``card_num`` which preserves the VSAM KEYS(16
        # 0) name directly). See V1__schema.sql DDL and
        # ``src/shared/models/card.py`` ``mapped_column`` positional
        # first arguments.
        required = {
            "card_num",
            "card_acct_id",
            "card_cvv_cd",
            "card_embossed_name",
            "card_expiration_date",
            "card_active_status",
            "version_id",  # OCC column
        }
        assert required.issubset(columns.keys()), f"cards table missing columns: {required - set(columns.keys())}"
        # VSAM KEYS(16 0) -> String(16) PK preserving leading zeros
        # (leading zeros lost under Integer representation because COBOL
        # PIC X(16) includes alphanumeric card numbers).
        assert pk_cols == ["card_num"], f"cards PK must be ['card_num'], got {pk_cols}"

        # VSAM AIX on acct_id -> non-unique B-tree index
        # (ix_card_acct_id). Replaces CARD.AIX.ACCT from z/OS catalog.
        index_names = {idx["name"] for idx in indexes}
        assert "ix_card_acct_id" in index_names, (
            f"cards must have 'ix_card_acct_id' B-tree index on acct_id; found indexes: {index_names}"
        )

    async def test_customer_table_columns(self, async_engine: AsyncEngine) -> None:
        """Customer schema matches CVCUS01Y.cpy record layout.

        # Schema matches CUSTOMER-RECORD from app/cpy/CVCUS01Y.cpy
        # (RECLN 500) - CUSTFILE.jcl KEYS(9 0).
        """

        def _get_columns(sync_conn: Connection) -> dict[str, ReflectedColumn]:
            return {c["name"]: c for c in inspect(sync_conn).get_columns("customers")}

        def _get_pk(sync_conn: Connection) -> list[str]:
            cols: list[str] = inspect(sync_conn).get_pk_constraint("customers")["constrained_columns"]
            return cols

        async with async_engine.connect() as conn:
            columns = await conn.run_sync(_get_columns)
            pk_cols = await conn.run_sync(_get_pk)

        # Physical DB column names use the COBOL ``CUST-`` prefix.
        # See V1__schema.sql DDL and ``src/shared/models/customer.py``
        # ``mapped_column`` positional first arguments.
        required = {
            "cust_id",
            "cust_first_name",
            "cust_middle_name",
            "cust_last_name",
            "cust_addr_line_1",
            "cust_ssn",
            "cust_dob_yyyy_mm_dd",
            "cust_fico_credit_score",
        }
        assert required.issubset(columns.keys()), f"customers table missing columns: {required - set(columns.keys())}"
        assert pk_cols == ["cust_id"], f"customers PK must be ['cust_id'], got {pk_cols}"

    async def test_transaction_table_columns(self, async_engine: AsyncEngine) -> None:
        """Transaction schema matches CVTRA05Y.cpy record layout.

        # Schema matches TRAN-RECORD from app/cpy/CVTRA05Y.cpy
        # (RECLN 350) - TRANFILE.jcl KEYS(16 0).
        """

        def _get_columns(sync_conn: Connection) -> dict[str, ReflectedColumn]:
            return {c["name"]: c for c in inspect(sync_conn).get_columns("transactions")}

        def _get_pk(sync_conn: Connection) -> list[str]:
            cols: list[str] = inspect(sync_conn).get_pk_constraint("transactions")["constrained_columns"]
            return cols

        def _get_indexes(sync_conn: Connection) -> list[ReflectedIndex]:
            indexes: list[ReflectedIndex] = list(inspect(sync_conn).get_indexes("transactions"))
            return indexes

        async with async_engine.connect() as conn:
            columns = await conn.run_sync(_get_columns)
            pk_cols = await conn.run_sync(_get_pk)
            indexes = await conn.run_sync(_get_indexes)

        # Physical DB column names use the COBOL ``TRAN-`` prefix
        # (except the PK ``tran_id`` which is itself prefixed). See
        # V1__schema.sql DDL and ``src/shared/models/transaction.py``
        # ``mapped_column`` positional first arguments.
        required = {
            "tran_id",
            "tran_type_cd",
            "tran_cat_cd",
            "tran_source",
            "tran_desc",
            "tran_amt",
            "tran_card_num",
            "tran_orig_ts",
            "tran_proc_ts",
        }
        assert required.issubset(columns.keys()), (
            f"transactions table missing columns: {required - set(columns.keys())}"
        )
        assert pk_cols == ["tran_id"], f"transactions PK must be ['tran_id'], got {pk_cols}"
        # CRITICAL precision - COBOL PIC S9(09)V99 maps to NUMERIC(15, 2).
        amount_column = columns["tran_amt"]
        ct = amount_column["type"]
        assert getattr(ct, "precision", None) == 15, (
            f"transactions.tran_amt must be NUMERIC(15, 2); got precision={getattr(ct, 'precision', None)}"
        )
        assert getattr(ct, "scale", None) == 2, (
            f"transactions.tran_amt must be NUMERIC(15, 2); got scale={getattr(ct, 'scale', None)}"
        )

        # VSAM AIX on proc_ts -> non-unique B-tree index from
        # TRANIDX.jcl.
        index_names = {idx["name"] for idx in indexes}
        assert "ix_transaction_proc_ts" in index_names, (
            f"transactions must have 'ix_transaction_proc_ts' B-tree index on proc_ts; found: {index_names}"
        )

    async def test_transaction_category_balance_composite_pk(self, async_engine: AsyncEngine) -> None:
        """Transaction-category-balance has 3-part composite PK.

        # Composite PK matches TRAN-CAT-KEY from app/cpy/CVTRA01Y.cpy -
        # TCATBALF.jcl KEYS(17 0)  (17 bytes = 11 + 2 + 4 concatenated).
        """

        def _get_pk(sync_conn: Connection) -> list[str]:
            cols: list[str] = inspect(sync_conn).get_pk_constraint("transaction_category_balances")[
                "constrained_columns"
            ]
            return cols

        def _get_columns(sync_conn: Connection) -> dict[str, ReflectedColumn]:
            return {c["name"]: c for c in inspect(sync_conn).get_columns("transaction_category_balances")}

        async with async_engine.connect() as conn:
            pk_cols = await conn.run_sync(_get_pk)
            columns = await conn.run_sync(_get_columns)

        # Composite PK is the 17-byte VSAM key decomposed into its
        # three COBOL components:
        #   TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02)
        #   + TRANCAT-CD PIC 9(04).
        #
        # Physical DB column names per V1__schema.sql and the
        # ``mapped_column`` positional first arguments in
        # ``src/shared/models/transaction_category_balance.py`` —
        # ``type_code`` and ``cat_code`` (not ``type_cd``/``cat_cd``,
        # which are Python attribute keys via ``key="..."``).
        assert set(pk_cols) == {"acct_id", "type_code", "cat_code"}, (
            f"transaction_category_balances PK must be {{'acct_id', 'type_code', 'cat_code'}}, got {pk_cols}"
        )

        # Balance column (physical name ``tran_cat_bal``) must be
        # NUMERIC(15, 2) - matches COBOL TRAN-CAT-BAL PIC S9(09)V99
        # scale.
        balance_type = columns["tran_cat_bal"]["type"]
        assert getattr(balance_type, "precision", None) == 15, (
            "transaction_category_balances.balance must be NUMERIC(15, 2)"
        )
        assert getattr(balance_type, "scale", None) == 2, "transaction_category_balances.balance must be NUMERIC(15, 2)"

    async def test_disclosure_group_composite_pk(self, async_engine: AsyncEngine) -> None:
        """Disclosure-group has 3-part composite PK.

        # Composite PK matches DIS-GROUP-KEY from app/cpy/CVTRA02Y.cpy.
        # Interest-rate reference table consulted by CBACT04C.cbl
        # (INTCALC stage) for per-group rate lookup with DEFAULT /
        # ZEROAPR fallback semantics.
        """

        def _get_pk(sync_conn: Connection) -> list[str]:
            cols: list[str] = inspect(sync_conn).get_pk_constraint("disclosure_groups")["constrained_columns"]
            return cols

        def _get_columns(sync_conn: Connection) -> dict[str, ReflectedColumn]:
            return {c["name"]: c for c in inspect(sync_conn).get_columns("disclosure_groups")}

        async with async_engine.connect() as conn:
            pk_cols = await conn.run_sync(_get_pk)
            columns = await conn.run_sync(_get_columns)

        # Physical DB column names use the COBOL ``DIS-`` prefix per
        # V1__schema.sql and the ``mapped_column`` positional first
        # arguments in ``src/shared/models/disclosure_group.py``.
        assert set(pk_cols) == {
            "dis_acct_group_id",
            "dis_tran_type_cd",
            "dis_tran_cat_cd",
        }, (
            f"disclosure_groups PK must be composite of "
            f"(dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd); "
            f"got {pk_cols}"
        )
        # int_rate (physical name ``dis_int_rate``) is Numeric(6, 2) -
        # narrower than the Numeric(15, 2) used for monetary balances
        # because COBOL DIS-INT-RATE is PIC S9(04)V99 (max 9999.99 -
        # expressed as decimal APR).
        int_rate_type = columns["dis_int_rate"]["type"]
        assert getattr(int_rate_type, "precision", None) == 6, "disclosure_groups.int_rate must be NUMERIC(6, 2)"
        assert getattr(int_rate_type, "scale", None) == 2, "disclosure_groups.int_rate must be NUMERIC(6, 2)"

    async def test_transaction_category_composite_pk(self, async_engine: AsyncEngine) -> None:
        """Transaction-category has 2-part composite PK.

        # Composite PK matches TRAN-CAT-KEY from app/cpy/CVTRA04Y.cpy.
        # Reference table populated by TRANCATG.jcl (18 rows) - the
        # transaction-type -> category relationship.
        """

        def _get_pk(sync_conn: Connection) -> list[str]:
            cols: list[str] = inspect(sync_conn).get_pk_constraint("transaction_categories")["constrained_columns"]
            return cols

        async with async_engine.connect() as conn:
            pk_cols = await conn.run_sync(_get_pk)

        # Physical DB column names per V1__schema.sql and the
        # ``mapped_column`` positional first arguments in
        # ``src/shared/models/transaction_category.py`` — ``type_code``
        # and ``cat_code`` (not ``type_cd``/``cat_cd``, which are
        # Python attribute keys via ``key="..."``).
        assert set(pk_cols) == {"type_code", "cat_code"}, (
            f"transaction_categories PK must be composite of (type_code, cat_code); got {pk_cols}"
        )


# ===========================================================================
# PHASE 4 - CRUD OPERATION TESTS
# ===========================================================================
# Validates VSAM READ/WRITE/REWRITE/DELETE -> SQL INSERT/SELECT/UPDATE/DELETE.
# One class per entity, four tests per class (create, read, update, delete).
# ===========================================================================


class TestAccountCRUD:
    """CRUD operations for the Account entity.

    # Maps to VSAM operations on ACCTDAT file (COACTVWC.cbl for READ,
    # COACTUPC.cbl for WRITE/REWRITE/DELETE). Source copybook:
    # app/cpy/CVACT01Y.cpy (300-byte record, 11-digit key).
    """

    async def test_create_account(self, session: AsyncSession) -> None:
        """INSERT account with all fields - VSAM WRITE replacement.

        # VSAM WRITE replaces - EXEC CICS WRITE FILE('ACCTDAT')
        """
        # Create with leading-zero 11-digit account ID (COBOL PIC 9(11)
        # preserved as String(11) to keep the leading zeros).
        acct = _make_account(
            acct_id="00000000001",
            curr_bal=Decimal("1000.00"),
            credit_limit=Decimal("5000.00"),
        )
        session.add(acct)
        await session.flush()

        # Round-trip via fresh query to verify the INSERT went to DB.
        retrieved = await session.get(Account, "00000000001")
        assert retrieved is not None
        assert retrieved.acct_id == "00000000001"
        # Exact Decimal comparison - no float tolerance.
        assert retrieved.curr_bal == Decimal("1000.00")
        assert retrieved.credit_limit == Decimal("5000.00")
        assert retrieved.cash_credit_limit == Decimal("1000.00")
        assert retrieved.active_status == "Y"
        assert retrieved.group_id == "DEFAULT"

    async def test_read_account(self, session: AsyncSession) -> None:
        """SELECT account by primary key - VSAM READ replacement.

        # VSAM READ replaces - EXEC CICS READ FILE('ACCTDAT') RIDFLD(...)
        """
        acct = _make_account(
            acct_id="00000000002",
            curr_bal=Decimal("2500.75"),
            addr_zip="99999",
        )
        session.add(acct)
        await session.flush()

        # Use select() for explicit SQL emission rather than get(), to
        # exercise the query builder path used by real services.
        result = await session.execute(select(Account).where(Account.acct_id == "00000000002"))
        retrieved = result.scalar_one()
        assert retrieved.acct_id == "00000000002"
        assert retrieved.curr_bal == Decimal("2500.75")
        assert retrieved.addr_zip == "99999"

    async def test_update_account(self, session: AsyncSession) -> None:
        """UPDATE account fields - VSAM REWRITE replacement.

        # VSAM REWRITE replaces - EXEC CICS REWRITE FILE('ACCTDAT')
        # from COACTUPC.cbl
        """
        acct = _make_account(
            acct_id="00000000003",
            curr_bal=Decimal("100.00"),
        )
        session.add(acct)
        await session.flush()

        # Modify the balance, commit (SAVEPOINT release), re-read.
        acct.curr_bal = Decimal("250.50")
        acct.curr_cyc_debit = Decimal("150.50")
        await session.flush()

        # Force a refresh from the DB by expiring the cached attributes.
        await session.refresh(acct)
        assert acct.curr_bal == Decimal("250.50")
        assert acct.curr_cyc_debit == Decimal("150.50")

    async def test_delete_account(self, session: AsyncSession) -> None:
        """DELETE account by primary key - VSAM DELETE replacement.

        # VSAM DELETE replaces - EXEC CICS DELETE FILE('ACCTDAT')
        """
        acct = _make_account(acct_id="00000000004")
        session.add(acct)
        await session.flush()

        # Verify presence before deletion.
        assert await session.get(Account, "00000000004") is not None

        await session.delete(acct)
        await session.flush()

        # Expire cache and verify the record is gone.
        session.expire_all()
        assert await session.get(Account, "00000000004") is None


class TestCardCRUD:
    """CRUD operations for the Card entity.

    # Maps to VSAM operations on CARDDAT file (COCRDLIC.cbl /
    # COCRDSLC.cbl for READ, COCRDUPC.cbl for REWRITE). Source copybook:
    # app/cpy/CVACT02Y.cpy (150-byte record, 16-char key).
    """

    async def test_create_card(self, session: AsyncSession) -> None:
        """INSERT card with all fields - VSAM WRITE replacement.

        # VSAM WRITE replaces - EXEC CICS WRITE FILE('CARDDAT')
        """
        card = _make_card(
            card_num="4111111111111111",
            acct_id="00000000001",
            cvv_cd="123",
        )
        session.add(card)
        await session.flush()

        retrieved = await session.get(Card, "4111111111111111")
        assert retrieved is not None
        # CVV preserved as String(3) - COBOL PIC 9(03) keeps leading
        # zeros ("023" would survive round-trip).
        assert retrieved.cvv_cd == "123"
        assert retrieved.acct_id == "00000000001"
        assert retrieved.embossed_name == "JOHN DOE"
        assert retrieved.active_status == "Y"
        # SQLAlchemy's version_id_col mechanism assigns the initial
        # version value on INSERT via version_id_generator (which
        # defaults to ``lambda v: (v or 0) + 1``), so a freshly-INSERTed
        # row arrives with version_id == 1 even though the ORM column
        # default is 0. This is SQLAlchemy's documented behavior for
        # the versioning feature.
        assert retrieved.version_id == 1

    async def test_read_card(self, session: AsyncSession) -> None:
        """SELECT card by primary key - VSAM READ replacement."""
        card = _make_card(
            card_num="4111111111111112",
            acct_id="00000000005",
            embossed_name="JANE SMITH",
        )
        session.add(card)
        await session.flush()

        result = await session.execute(select(Card).where(Card.card_num == "4111111111111112"))
        retrieved = result.scalar_one()
        assert retrieved.card_num == "4111111111111112"
        assert retrieved.acct_id == "00000000005"
        assert retrieved.embossed_name == "JANE SMITH"

    async def test_update_card(self, session: AsyncSession) -> None:
        """UPDATE card fields - VSAM REWRITE replacement.

        # VSAM REWRITE replaces - EXEC CICS REWRITE FILE('CARDDAT')
        # from COCRDUPC.cbl with optimistic-concurrency check via
        # version_id.
        """
        card = _make_card(
            card_num="4111111111111113",
            active_status="Y",
        )
        session.add(card)
        await session.flush()

        # Update active_status, commit, re-read - version increments
        # because __mapper_args__ version_id_col is set.
        card.active_status = "N"
        await session.flush()
        await session.refresh(card)
        assert card.active_status == "N"
        # Version advances 1 -> 2 on UPDATE: INSERT set it to 1 via the
        # version_id_generator, and the subsequent UPDATE increments it.
        assert card.version_id == 2

    async def test_delete_card(self, session: AsyncSession) -> None:
        """DELETE card by primary key - VSAM DELETE replacement."""
        card = _make_card(card_num="4111111111111114")
        session.add(card)
        await session.flush()

        assert await session.get(Card, "4111111111111114") is not None

        await session.delete(card)
        await session.flush()

        session.expire_all()
        assert await session.get(Card, "4111111111111114") is None


class TestCustomerCRUD:
    """CRUD operations for the Customer entity.

    # Maps to VSAM operations on CUSTDAT file. Source copybook:
    # app/cpy/CVCUS01Y.cpy (500-byte record, 9-digit key).
    """

    async def test_create_customer(self, session: AsyncSession) -> None:
        """INSERT customer with all fields - VSAM WRITE replacement.

        # VSAM WRITE replaces - EXEC CICS WRITE FILE('CUSTDAT')
        """
        cust = _make_customer(
            cust_id="000000001",
            first_name="ALICE",
            last_name="JOHNSON",
            ssn="987654321",
            fico_credit_score=780,
        )
        session.add(cust)
        await session.flush()

        retrieved = await session.get(Customer, "000000001")
        assert retrieved is not None
        # Leading zeros preserved - COBOL PIC 9(09) semantics.
        assert retrieved.cust_id == "000000001"
        # SSN stored as String(9) to preserve leading zeros
        # (COBOL PIC 9(09)).
        assert retrieved.ssn == "987654321"
        assert retrieved.first_name == "ALICE"
        assert retrieved.last_name == "JOHNSON"
        assert retrieved.fico_credit_score == 780

    async def test_read_customer(self, session: AsyncSession) -> None:
        """SELECT customer by primary key - VSAM READ replacement."""
        cust = _make_customer(
            cust_id="000000002",
            first_name="BOB",
            last_name="WILLIAMS",
            ssn="111222333",
        )
        session.add(cust)
        await session.flush()

        result = await session.execute(select(Customer).where(Customer.cust_id == "000000002"))
        retrieved = result.scalar_one()
        assert retrieved.first_name == "BOB"
        assert retrieved.last_name == "WILLIAMS"
        assert retrieved.ssn == "111222333"

    async def test_update_customer(self, session: AsyncSession) -> None:
        """UPDATE customer fields - VSAM REWRITE replacement."""
        cust = _make_customer(
            cust_id="000000003",
            first_name="CHARLIE",
            fico_credit_score=650,
        )
        session.add(cust)
        await session.flush()

        # Update FICO score - numeric field, not monetary.
        cust.fico_credit_score = 720
        cust.first_name = "CHARLES"
        await session.flush()
        await session.refresh(cust)
        assert cust.fico_credit_score == 720
        assert cust.first_name == "CHARLES"

    async def test_delete_customer(self, session: AsyncSession) -> None:
        """DELETE customer by primary key - VSAM DELETE replacement."""
        cust = _make_customer(cust_id="000000004")
        session.add(cust)
        await session.flush()

        assert await session.get(Customer, "000000004") is not None

        await session.delete(cust)
        await session.flush()

        session.expire_all()
        assert await session.get(Customer, "000000004") is None


class TestTransactionCRUD:
    """CRUD operations for the Transaction entity.

    # Maps to VSAM operations on TRANSACT file (COTRN00C.cbl /
    # COTRN01C.cbl for READ, COTRN02C.cbl for WRITE). Source copybook:
    # app/cpy/CVTRA05Y.cpy (350-byte record, 16-char key).
    # Amount is COBOL PIC S9(09)V99 -> NUMERIC(15, 2).
    """

    async def test_create_transaction(self, session: AsyncSession) -> None:
        """INSERT transaction with monetary amount.

        # VSAM WRITE replaces - EXEC CICS WRITE FILE('TRANSACT')
        # from COTRN02C.cbl
        """
        tran = _make_transaction(
            tran_id="0000000000000001",
            amount=Decimal("50.00"),
        )
        session.add(tran)
        await session.flush()

        retrieved = await session.get(Transaction, "0000000000000001")
        assert retrieved is not None
        # CRITICAL - exact Decimal equality, NOT epsilon-based.
        assert retrieved.amount == Decimal("50.00")
        # type() check guards against any silent float coercion in the
        # driver layer - Decimal must survive the round-trip as Decimal.
        assert isinstance(retrieved.amount, Decimal)
        assert retrieved.type_cd == "01"
        assert retrieved.cat_cd == "1001"
        assert retrieved.card_num == "4111111111111111"

    async def test_read_transaction(self, session: AsyncSession) -> None:
        """SELECT transaction by primary key - VSAM READ replacement."""
        tran = _make_transaction(
            tran_id="0000000000000002",
            amount=Decimal("123.45"),
            source="WEB",
        )
        session.add(tran)
        await session.flush()

        result = await session.execute(select(Transaction).where(Transaction.tran_id == "0000000000000002"))
        retrieved = result.scalar_one()
        assert retrieved.amount == Decimal("123.45")
        assert retrieved.source == "WEB"

    async def test_update_transaction(self, session: AsyncSession) -> None:
        """UPDATE transaction fields."""
        tran = _make_transaction(
            tran_id="0000000000000003",
            amount=Decimal("75.00"),
            description="ORIGINAL DESC",
        )
        session.add(tran)
        await session.flush()

        tran.amount = Decimal("80.25")
        tran.description = "UPDATED DESC"
        await session.flush()
        await session.refresh(tran)
        # Exact decimal comparison preserved.
        assert tran.amount == Decimal("80.25")
        assert tran.description == "UPDATED DESC"

    async def test_delete_transaction(self, session: AsyncSession) -> None:
        """DELETE transaction by primary key."""
        tran = _make_transaction(tran_id="0000000000000004")
        session.add(tran)
        await session.flush()

        assert await session.get(Transaction, "0000000000000004") is not None

        await session.delete(tran)
        await session.flush()
        session.expire_all()
        assert await session.get(Transaction, "0000000000000004") is None


class TestCardCrossReferenceCRUD:
    """CRUD operations for the CardCrossReference entity.

    # Maps to VSAM operations on XREFFILE/CARDXREF.VSAM.KSDS. Source
    # copybook: app/cpy/CVACT03Y.cpy (50-byte record, 16-char key).
    # Links card_num -> acct_id and card_num -> cust_id.
    """

    async def test_create_xref(self, session: AsyncSession) -> None:
        """INSERT cross-reference - VSAM WRITE replacement.

        # VSAM WRITE replaces - EXEC CICS WRITE FILE('CARDXREF')
        """
        xref = _make_card_cross_reference(
            card_num="4111111111111115",
            cust_id="000000001",
            acct_id="00000000001",
        )
        session.add(xref)
        await session.flush()

        retrieved = await session.get(CardCrossReference, "4111111111111115")
        assert retrieved is not None
        assert retrieved.card_num == "4111111111111115"
        assert retrieved.cust_id == "000000001"
        assert retrieved.acct_id == "00000000001"

    async def test_read_xref(self, session: AsyncSession) -> None:
        """SELECT cross-reference by primary key."""
        xref = _make_card_cross_reference(
            card_num="4111111111111116",
            cust_id="000000002",
            acct_id="00000000002",
        )
        session.add(xref)
        await session.flush()

        result = await session.execute(
            select(CardCrossReference).where(CardCrossReference.card_num == "4111111111111116")
        )
        retrieved = result.scalar_one()
        # All three fields preserve leading zeros.
        assert retrieved.cust_id == "000000002"
        assert retrieved.acct_id == "00000000002"

    async def test_update_xref(self, session: AsyncSession) -> None:
        """UPDATE cross-reference fields."""
        xref = _make_card_cross_reference(
            card_num="4111111111111117",
            cust_id="000000003",
            acct_id="00000000003",
        )
        session.add(xref)
        await session.flush()

        # Re-point the card to a different account (rare but possible
        # in card-replacement scenarios).
        xref.acct_id = "00000000010"
        await session.flush()
        await session.refresh(xref)
        assert xref.acct_id == "00000000010"

    async def test_delete_xref(self, session: AsyncSession) -> None:
        """DELETE cross-reference by primary key."""
        xref = _make_card_cross_reference(card_num="4111111111111118")
        session.add(xref)
        await session.flush()

        assert await session.get(CardCrossReference, "4111111111111118") is not None

        await session.delete(xref)
        await session.flush()
        session.expire_all()
        assert await session.get(CardCrossReference, "4111111111111118") is None


class TestUserSecurityCRUD:
    """CRUD operations for the UserSecurity entity.

    # Maps to VSAM operations on USRSEC file (COSGN00C.cbl for READ
    # during auth, COUSR01C/02C/03C.cbl for CRUD admin). Source
    # copybook: app/cpy/CSUSR01Y.cpy (80-byte record, 8-char key).
    # Password upgraded from COBOL PIC X(08) to String(60) for BCrypt
    # ($2b$... format).
    """

    async def test_create_user(self, session: AsyncSession) -> None:
        """INSERT user with BCrypt hashed password.

        # VSAM WRITE replaces - EXEC CICS WRITE FILE('USRSEC')
        # from COUSR01C.cbl
        """
        user = _make_user_security(
            user_id="USER0001",
            first_name="ADMIN",
            last_name="USER",
            usr_type="A",
        )
        session.add(user)
        await session.flush()

        retrieved = await session.get(UserSecurity, "USER0001")
        assert retrieved is not None
        assert retrieved.user_id == "USER0001"
        # Password stored as full 60-char BCrypt hash, NOT plaintext.
        assert retrieved.password.startswith("$2b$")
        assert len(retrieved.password) == 60
        assert retrieved.usr_type == "A"

    async def test_read_user(self, session: AsyncSession) -> None:
        """SELECT user by usr_id (8-char PK) - VSAM READ replacement."""
        user = _make_user_security(
            user_id="USER0002",
            first_name="REGULAR",
            last_name="USER",
            usr_type="U",
        )
        session.add(user)
        await session.flush()

        result = await session.execute(select(UserSecurity).where(UserSecurity.user_id == "USER0002"))
        retrieved = result.scalar_one()
        assert retrieved.first_name == "REGULAR"
        assert retrieved.usr_type == "U"

    async def test_update_user(self, session: AsyncSession) -> None:
        """UPDATE user fields - VSAM REWRITE replacement.

        # Maps to COUSR02C.cbl - admin user-update transaction.
        """
        user = _make_user_security(
            user_id="USER0003",
            usr_type="U",
        )
        session.add(user)
        await session.flush()

        # Promote user to admin (usr_type U -> A) and update the
        # password hash.
        user.usr_type = "A"
        new_hash = "$2b$12$abcdefghijklmnopqrstuvwxyz.0123456789ABCDEFGHIJKL0123"
        assert len(new_hash) == 60  # sanity check the test data
        user.password = new_hash
        await session.flush()
        await session.refresh(user)
        assert user.usr_type == "A"
        assert user.password == new_hash

    async def test_delete_user(self, session: AsyncSession) -> None:
        """DELETE user - VSAM DELETE replacement.

        # Maps to COUSR03C.cbl - admin user-delete transaction.
        """
        user = _make_user_security(user_id="USER0004")
        session.add(user)
        await session.flush()

        assert await session.get(UserSecurity, "USER0004") is not None

        await session.delete(user)
        await session.flush()

        session.expire_all()
        assert await session.get(UserSecurity, "USER0004") is None


# ===========================================================================
# PHASE 5 - CONSTRAINT VALIDATION TESTS
# ===========================================================================
# Validates relational integrity (PK uniqueness, NOT NULL) that replaces
# VSAM semantics (DUPREC for duplicate keys, implicit NOT NULL for fixed
# record layouts).
# ===========================================================================


class TestConstraints:
    """Constraint validation - PK uniqueness, NOT NULL, precision.

    # Maps VSAM DUPREC condition (duplicate-key error on WRITE) to
    # PostgreSQL primary-key uniqueness violations raised as
    # sqlalchemy.exc.IntegrityError. Also validates COBOL fixed-width
    # record semantics (no field is truly optional in a VSAM record)
    # via NOT NULL constraints.
    """

    async def test_account_primary_key_uniqueness(self, session: AsyncSession) -> None:
        """INSERT duplicate acct_id must raise IntegrityError.

        # Maps VSAM DUPREC from ACCTFILE.jcl - when a WRITE is issued
        # against an existing key, CICS returns DUPREC and COBOL code
        # traps the EIBRESP.
        """
        acct1 = _make_account(acct_id="00000000099")
        session.add(acct1)
        await session.flush()

        # Second INSERT with same PK - must fail with IntegrityError.
        acct2 = _make_account(
            acct_id="00000000099",
            curr_bal=Decimal("500.00"),
        )
        session.add(acct2)
        with pytest.raises(IntegrityError):
            await session.flush()
        # Rollback to clean SAVEPOINT so the outer teardown succeeds.
        await session.rollback()

    async def test_card_primary_key_uniqueness(self, session: AsyncSession) -> None:
        """INSERT duplicate card_num must raise IntegrityError.

        # Maps VSAM DUPREC from CARDFILE.jcl.
        """
        card1 = _make_card(card_num="4111111111199991")
        session.add(card1)
        await session.flush()

        card2 = _make_card(card_num="4111111111199991", cvv_cd="456")
        session.add(card2)
        with pytest.raises(IntegrityError):
            await session.flush()
        await session.rollback()

    async def test_transaction_not_null_constraints(self, session: AsyncSession) -> None:
        """INSERT transaction with NULL required field must fail.

        # COBOL fixed-width record semantics: no field in a VSAM record
        # can be null - every byte has a defined layout. PostgreSQL
        # enforces this via NOT NULL on all required columns.
        """
        # Build a Transaction with type_cd explicitly set to None
        # to bypass the helper's default value. SQLAlchemy will try
        # to INSERT NULL and PostgreSQL will reject with
        # NotNullViolation (wrapped in IntegrityError).
        tran = Transaction(
            tran_id="0000000000099999",
            type_cd=None,
            cat_cd="1001",
            source="POS",
            description="NULL CONSTRAINT TEST",
            amount=Decimal("10.00"),
            merchant_id="000000001",
            merchant_name="MERCHANT",
            merchant_city="CITY",
            merchant_zip="10001",
            card_num="4111111111111111",
            orig_ts="2024-01-01-00.00.00",
            proc_ts="2024-01-01-00.00.00",
        )
        session.add(tran)
        with pytest.raises(IntegrityError):
            await session.flush()
        await session.rollback()

    async def test_user_security_not_null(self, session: AsyncSession) -> None:
        """INSERT user with NULL password must raise IntegrityError.

        # Critical security invariant - users cannot exist without a
        # credential. Maps to COUSR01C.cbl's mandatory password input.
        """
        user = UserSecurity(
            user_id="USER0099",
            first_name="NO",
            last_name="PASS",
            password=None,
            usr_type="U",
        )
        session.add(user)
        with pytest.raises(IntegrityError):
            await session.flush()
        await session.rollback()

    async def test_monetary_field_precision(self, session: AsyncSession) -> None:
        """Validates COBOL PIC S9(10)V99 -> NUMERIC(15,2) precision.

        # This test proves that the COBOL signed decimal format
        # PIC S9(10)V99 (values from -9999999999.99 to +9999999999.99)
        # round-trips through NUMERIC(15, 2) with NO loss of precision
        # and NO silent float coercion.
        """
        # Insert near-maximum positive value (10 integer digits +
        # 2 fractional).
        max_val = Decimal("9999999999.99")
        acct = _make_account(
            acct_id="00000000088",
            curr_bal=max_val,
            credit_limit=max_val,
        )
        session.add(acct)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(Account, "00000000088")
        assert retrieved is not None
        # CRITICAL - exact equality, no epsilon.
        assert retrieved.curr_bal == max_val
        assert retrieved.credit_limit == max_val
        assert isinstance(retrieved.curr_bal, Decimal)
        assert isinstance(retrieved.credit_limit, Decimal)

        # Insert minimum non-zero value (smallest representable unit
        # in a 2-decimal-place field - one cent).
        min_val = Decimal("0.01")
        acct2 = _make_account(
            acct_id="00000000087",
            curr_bal=min_val,
        )
        session.add(acct2)
        await session.flush()
        session.expire_all()

        retrieved2 = await session.get(Account, "00000000087")
        assert retrieved2 is not None
        assert retrieved2.curr_bal == min_val


# ===========================================================================
# PHASE 6 - COMPOSITE PRIMARY KEY TESTS
# ===========================================================================
# Validates multi-column PK semantics for the three entities that use
# composite keys - TransactionCategoryBalance (3-part), DisclosureGroup
# (3-part), TransactionCategory (2-part). These replace VSAM composite
# keys where KEYS(n 0) described byte offsets into concatenated subkeys.
# ===========================================================================


class TestCompositePrimaryKeys:
    """Composite primary key behaviour tests.

    # Maps to VSAM composite-key semantics where multiple adjacent
    # fields form the KSDS key. All three PostgreSQL PKs must enforce
    # uniqueness across the tuple, not per-column.
    """

    async def test_transaction_category_balance_composite_pk_insert(self, session: AsyncSession) -> None:
        """INSERT TCB with 3-part PK (acct_id, type_cd, cat_cd).

        # Maps to TRAN-CAT-KEY from app/cpy/CVTRA01Y.cpy:
        #   TRANCAT-ACCT-ID  PIC 9(11)   - 11 bytes
        #   TRANCAT-TYPE-CD  PIC X(02)   - 2 bytes
        #   TRANCAT-CD       PIC 9(04)   - 4 bytes
        # Total 17-byte KSDS key (TCATBALF.jcl KEYS(17 0)).
        """
        tcb = TransactionCategoryBalance(
            acct_id="00000000001",
            type_cd="01",
            cat_cd="1001",
            balance=Decimal("125.50"),
        )
        session.add(tcb)
        await session.flush()

        # Retrieve via composite key tuple - SQLAlchemy accepts a tuple
        # of PK values for session.get() on composite PK.
        retrieved = await session.get(TransactionCategoryBalance, ("00000000001", "01", "1001"))
        assert retrieved is not None
        assert retrieved.acct_id == "00000000001"
        assert retrieved.type_cd == "01"
        assert retrieved.cat_cd == "1001"
        assert retrieved.balance == Decimal("125.50")

    async def test_transaction_category_balance_duplicate_pk(self, session: AsyncSession) -> None:
        """INSERT TCB with duplicate 3-part PK must raise IntegrityError.

        # VSAM DUPREC equivalent - exact key match triggers uniqueness
        # violation.
        """
        tcb1 = TransactionCategoryBalance(
            acct_id="00000000002",
            type_cd="02",
            cat_cd="2001",
            balance=Decimal("50.00"),
        )
        session.add(tcb1)
        await session.flush()

        # Exact same 3-part key - must fail.
        tcb2 = TransactionCategoryBalance(
            acct_id="00000000002",
            type_cd="02",
            cat_cd="2001",
            balance=Decimal("999.99"),  # different balance doesn't save it
        )
        session.add(tcb2)
        with pytest.raises(IntegrityError):
            await session.flush()
        await session.rollback()

    async def test_transaction_category_balance_partial_key_overlap(self, session: AsyncSession) -> None:
        """Partial-key overlap must succeed - same acct_id+type_cd,
        different cat_cd.

        # Validates that composite PK enforces the full tuple, not
        # any subset. This is critical because VSAM KSDS keys are
        # byte-concatenated: ("00000000001" + "01" + "1001") and
        # ("00000000001" + "01" + "2002") are different keys.
        """
        tcb1 = TransactionCategoryBalance(
            acct_id="00000000003",
            type_cd="03",
            cat_cd="3001",
            balance=Decimal("10.00"),
        )
        tcb2 = TransactionCategoryBalance(
            acct_id="00000000003",
            type_cd="03",
            cat_cd="3002",  # different cat_cd - should succeed
            balance=Decimal("20.00"),
        )
        tcb3 = TransactionCategoryBalance(
            acct_id="00000000003",
            type_cd="04",  # different type_cd - should succeed
            cat_cd="3001",
            balance=Decimal("30.00"),
        )
        session.add_all([tcb1, tcb2, tcb3])
        # Must NOT raise - all three have unique composite keys.
        await session.flush()

        # Retrieve all three independently.
        r1 = await session.get(TransactionCategoryBalance, ("00000000003", "03", "3001"))
        r2 = await session.get(TransactionCategoryBalance, ("00000000003", "03", "3002"))
        r3 = await session.get(TransactionCategoryBalance, ("00000000003", "04", "3001"))
        assert r1 is not None and r1.balance == Decimal("10.00")
        assert r2 is not None and r2.balance == Decimal("20.00")
        assert r3 is not None and r3.balance == Decimal("30.00")

    async def test_disclosure_group_composite_pk_insert(self, session: AsyncSession) -> None:
        """INSERT DisclosureGroup with 3-part PK.

        # Maps to DIS-GROUP-KEY from app/cpy/CVTRA02Y.cpy:
        #   DIS-ACCT-GROUP-ID  PIC X(10)
        #   DIS-TRAN-TYPE-CD   PIC X(02)
        #   DIS-TRAN-CAT-CD    PIC 9(04)
        """
        dg = DisclosureGroup(
            acct_group_id="DEFAULT",
            tran_type_cd="01",
            tran_cat_cd="1001",
            int_rate=Decimal("18.99"),
        )
        session.add(dg)
        await session.flush()

        retrieved = await session.get(DisclosureGroup, ("DEFAULT", "01", "1001"))
        assert retrieved is not None
        assert retrieved.acct_group_id == "DEFAULT"
        assert retrieved.int_rate == Decimal("18.99")

    async def test_disclosure_group_default_and_zeroapr(self, session: AsyncSession) -> None:
        """Both DEFAULT and ZEROAPR groups must coexist.

        # DEFAULT/ZEROAPR groups from discgrp.txt - used by INTCALC
        # (CBACT04C.cbl) for interest rate fallback. The algorithm is:
        # 1. Try to find a disclosure group matching the account's
        #    group_id.
        # 2. If not found, fall back to DEFAULT.
        # 3. ZEROAPR is a special marker group for 0% promotional
        #    accounts.
        """
        dg_default = DisclosureGroup(
            acct_group_id="DEFAULT",
            tran_type_cd="02",
            tran_cat_cd="2001",
            int_rate=Decimal("19.99"),
        )
        dg_zeroapr = DisclosureGroup(
            acct_group_id="ZEROAPR",
            tran_type_cd="02",
            tran_cat_cd="2001",
            int_rate=Decimal("0.00"),
        )
        session.add_all([dg_default, dg_zeroapr])
        await session.flush()

        # Both must be retrievable independently.
        default_row = await session.get(DisclosureGroup, ("DEFAULT", "02", "2001"))
        zeroapr_row = await session.get(DisclosureGroup, ("ZEROAPR", "02", "2001"))
        assert default_row is not None
        assert zeroapr_row is not None
        assert default_row.int_rate == Decimal("19.99")
        assert zeroapr_row.int_rate == Decimal("0.00")

        # Validate that INTCALC's fallback lookup pattern is supported
        # - query by acct_group_id IN ('ZEROAPR', 'DEFAULT') with
        # priority ordering.
        result = await session.execute(
            select(DisclosureGroup).where(
                DisclosureGroup.tran_type_cd == "02",
                DisclosureGroup.tran_cat_cd == "2001",
                DisclosureGroup.acct_group_id.in_(["ZEROAPR", "DEFAULT"]),
            )
        )
        rows = result.scalars().all()
        assert len(rows) == 2
        group_ids = {r.acct_group_id for r in rows}
        assert group_ids == {"DEFAULT", "ZEROAPR"}

    async def test_transaction_category_composite_pk_insert(self, session: AsyncSession) -> None:
        """INSERT TransactionCategory with 2-part PK (type_cd, cat_cd).

        # Maps to TRAN-CAT-KEY from app/cpy/CVTRA04Y.cpy:
        #   TRAN-TYPE-CD  PIC X(02)
        #   TRAN-CAT-CD   PIC 9(04)
        # Reference table loaded by TRANCATG.jcl with 18 categories.
        """
        cat = TransactionCategory(
            type_cd="05",
            cat_cd="5001",
            description="TEST CATEGORY",
        )
        session.add(cat)
        await session.flush()

        retrieved = await session.get(TransactionCategory, ("05", "5001"))
        assert retrieved is not None
        assert retrieved.type_cd == "05"
        assert retrieved.cat_cd == "5001"
        assert retrieved.description == "TEST CATEGORY"

        # Verify partial-key overlap is allowed (same type_cd, different
        # cat_cd).
        cat2 = TransactionCategory(
            type_cd="05",
            cat_cd="5002",
            description="SECOND CATEGORY",
        )
        session.add(cat2)
        await session.flush()
        retrieved2 = await session.get(TransactionCategory, ("05", "5002"))
        assert retrieved2 is not None
        assert retrieved2.description == "SECOND CATEGORY"


# ===========================================================================
# PHASE 7 - TRANSACTION ROLLBACK TESTS
# ===========================================================================
# Validates CICS SYNCPOINT ROLLBACK migration - test that errors within
# the session transaction boundary unwind ALL mutations atomically.
# Uses SQLAlchemy's begin_nested() to create inner SAVEPOINTs inside the
# outer SAVEPOINT-wrapped test session.
# ===========================================================================


class TestTransactionRollback:
    """Transactional rollback tests.

    # Mirrors CICS SYNCPOINT ROLLBACK from COACTUPC.cbl (~line 953) and
    # COBIL00C.cbl. The COBOL programs use SYNCPOINT ROLLBACK when a
    # REWRITE fails or when business-logic checks detect a conflict.
    """

    async def test_session_rollback_on_error(self, session: AsyncSession) -> None:
        """Inner-transaction rollback discards uncommitted mutations.

        # Mirrors CICS SYNCPOINT ROLLBACK from COACTUPC.cbl -
        # transactional integrity.
        """
        # Start a nested SAVEPOINT; mutations within it can be rolled
        # back independently of the outer fixture SAVEPOINT.
        try:
            async with session.begin_nested():
                acct = _make_account(
                    acct_id="00000000055",
                    curr_bal=Decimal("777.00"),
                )
                session.add(acct)
                await session.flush()

                # Simulate a business-rule violation or an external
                # error - raising inside begin_nested() triggers
                # automatic ROLLBACK TO SAVEPOINT.
                raise RuntimeError("Simulated business-rule violation")
        except RuntimeError:
            # Expected - swallow so the test can assert the rollback.
            pass

        # The account must NOT be persisted - SAVEPOINT rolled back.
        # Expire so we re-read from the database rather than the
        # identity map cache.
        session.expire_all()
        retrieved = await session.get(Account, "00000000055")
        assert retrieved is None

    async def test_dual_write_rollback(self, session: AsyncSession) -> None:
        """Dual-write atomicity - Transaction INSERT + Account UPDATE.

        # Dual-write atomicity from COBIL00C.cbl - Transaction INSERT +
        # Account REWRITE must be atomic. If either operation fails,
        # BOTH must roll back. This is the hallmark of CICS SYNCPOINT
        # in a multi-file update scenario.
        """
        # Seed an Account that the dual-write will "debit" via
        # balance reduction.
        original_balance = Decimal("1000.00")
        seed_acct = _make_account(
            acct_id="00000000066",
            curr_bal=original_balance,
        )
        session.add(seed_acct)
        await session.flush()

        # Dual-write attempt that will fail partway through.
        try:
            async with session.begin_nested():
                # Step 1 - INSERT a new Transaction (mimicking
                # COBIL00C.cbl bill-payment logic).
                tran = _make_transaction(
                    tran_id="0000000000000055",
                    amount=Decimal("500.00"),
                    description="BILL PAYMENT",
                )
                session.add(tran)
                await session.flush()

                # Step 2 - UPDATE the Account balance atomically with
                # the INSERT.
                seed_acct.curr_bal = Decimal("500.00")
                await session.flush()

                # Step 3 - simulate a late failure (e.g., external
                # service call, validation, or downstream I/O error).
                raise RuntimeError("Dual-write failure after partial updates")
        except RuntimeError:
            pass

        # Both the Transaction INSERT and the Account UPDATE must have
        # been rolled back together.
        session.expire_all()
        tran_check = await session.get(Transaction, "0000000000000055")
        assert tran_check is None
        acct_check = await session.get(Account, "00000000066")
        assert acct_check is not None
        # Balance reverted to the pre-dual-write seed value.
        assert acct_check.curr_bal == original_balance

    async def test_savepoint_rollback(self, session: AsyncSession) -> None:
        """Nested SAVEPOINT - inner failure must not affect outer work.

        # Validates that failure within an inner SAVEPOINT does not
        # cascade to the enclosing transaction - matching CICS
        # SYNCPOINT behavior within larger transaction contexts.
        """
        # Outer mutation that should survive the inner rollback.
        outer_acct = _make_account(
            acct_id="00000000077",
            curr_bal=Decimal("100.00"),
        )
        session.add(outer_acct)
        await session.flush()

        # Inner SAVEPOINT that will be rolled back.
        try:
            async with session.begin_nested():
                inner_acct = _make_account(
                    acct_id="00000000078",
                    curr_bal=Decimal("200.00"),
                )
                session.add(inner_acct)
                await session.flush()
                raise RuntimeError("Inner failure")
        except RuntimeError:
            pass

        # Outer record must still be present.
        session.expire_all()
        outer_check = await session.get(Account, "00000000077")
        assert outer_check is not None
        assert outer_check.curr_bal == Decimal("100.00")

        # Inner record must be gone.
        inner_check = await session.get(Account, "00000000078")
        assert inner_check is None


# ===========================================================================
# PHASE 8 - OPTIMISTIC CONCURRENCY TESTS
# ===========================================================================
# Validates the CICS READ UPDATE -> version_id migration. COBOL
# programs COACTUPC.cbl and COCRDUPC.cbl rely on CICS's locking via
# READ UPDATE / REWRITE; the target uses SQLAlchemy's
# version_id_col for OPTIMISTIC concurrency (conflicts detected at
# commit time, not read time).
#
# These tests REQUIRE dedicated sessions (not the SAVEPOINT-wrapped
# session fixture) because version conflict detection relies on real
# UPDATE operations being committed and then re-read by a second
# session. Each test manages its own session lifecycle and cleans up
# test data in a try/finally block.
# ===========================================================================


class TestOptimisticConcurrency:
    """Optimistic-concurrency tests using version_id.

    # Maps CICS READ UPDATE / REWRITE from COACTUPC.cbl (~line 953) and
    # COCRDUPC.cbl to SQLAlchemy's version_id_col mechanism.
    # Conflicts surface as sqlalchemy.orm.exc.StaleDataError when a
    # session attempts to UPDATE a row whose version has already been
    # incremented by another transaction.
    """

    async def test_account_version_increments_on_update(self, async_engine: AsyncEngine) -> None:
        """UPDATE increments Account.version_id by 1.

        # Mirrors CICS READ UPDATE from COACTUPC.cbl - after REWRITE,
        # the underlying row tracking must advance.
        """
        sessionmaker = async_sessionmaker(
            bind=async_engine,
            class_=AsyncSession,
            expire_on_commit=False,
        )
        test_acct_id = "00000000061"
        try:
            # Create + commit.
            async with sessionmaker() as s:
                acct = _make_account(acct_id=test_acct_id)
                s.add(acct)
                await s.commit()
                # Initial INSERT sets version to 1 via SQLAlchemy's
                # default version_id_generator (lambda v: (v or 0) + 1).
                assert acct.version_id == 1

            # Update + commit - version must advance.
            async with sessionmaker() as s:
                loaded1 = await s.get(Account, test_acct_id)
                assert loaded1 is not None
                assert loaded1.version_id == 1
                loaded1.curr_bal = Decimal("2000.00")
                await s.commit()
                assert loaded1.version_id == 2

            # Second update - version 2 -> 3.
            async with sessionmaker() as s:
                loaded2 = await s.get(Account, test_acct_id)
                assert loaded2 is not None
                assert loaded2.version_id == 2
                loaded2.curr_bal = Decimal("3000.00")
                await s.commit()
                assert loaded2.version_id == 3
        finally:
            # Cleanup - remove the test row so subsequent tests start
            # clean.
            async with sessionmaker() as s:
                cleanup = await s.get(Account, test_acct_id)
                if cleanup is not None:
                    await s.delete(cleanup)
                    await s.commit()

    async def test_account_concurrent_update_conflict(self, async_engine: AsyncEngine) -> None:
        """Concurrent update detection via StaleDataError.

        # Optimistic concurrency replaces CICS READ UPDATE from
        # COACTUPC.cbl (4,236 lines). Two concurrent sessions read the
        # same Account at version 1 (the version_id_generator sets
        # version to 1 on INSERT); the first commits successfully and
        # bumps version to 2; the second fails with StaleDataError
        # because its expected version (1) no longer matches.
        """
        sessionmaker = async_sessionmaker(
            bind=async_engine,
            class_=AsyncSession,
            expire_on_commit=False,
        )
        test_acct_id = "00000000062"
        try:
            # Seed the account.
            async with sessionmaker() as s:
                s.add(_make_account(acct_id=test_acct_id))
                await s.commit()

            # Session A and Session B both read the account at
            # version 1.
            session_a = sessionmaker()
            session_b = sessionmaker()
            try:
                acct_a = await session_a.get(Account, test_acct_id)
                acct_b = await session_b.get(Account, test_acct_id)
                assert acct_a is not None
                assert acct_b is not None
                assert acct_a.version_id == 1
                assert acct_b.version_id == 1

                # Session A commits first - version 1 -> 2.
                acct_a.curr_bal = Decimal("1500.00")
                await session_a.commit()
                assert acct_a.version_id == 2

                # Session B attempts to commit with stale version 1.
                # StaleDataError must be raised (wraps PostgreSQL's
                # "0 rows updated" detection).
                acct_b.curr_bal = Decimal("9999.99")
                with pytest.raises(StaleDataError):
                    await session_b.commit()
                # After StaleDataError, session_b is in a failed state;
                # rollback to release the in-progress transaction.
                await session_b.rollback()
            finally:
                await session_a.close()
                await session_b.close()

            # Verify the winner (Session A) was persisted.
            async with sessionmaker() as s:
                final = await s.get(Account, test_acct_id)
                assert final is not None
                assert final.curr_bal == Decimal("1500.00")
                assert final.version_id == 2
        finally:
            # Cleanup.
            async with sessionmaker() as s:
                cleanup = await s.get(Account, test_acct_id)
                if cleanup is not None:
                    await s.delete(cleanup)
                    await s.commit()

    async def test_card_version_increments_on_update(self, async_engine: AsyncEngine) -> None:
        """UPDATE increments Card.version_id by 1.

        # Optimistic concurrency replaces CICS READ UPDATE from
        # COCRDUPC.cbl.
        """
        sessionmaker = async_sessionmaker(
            bind=async_engine,
            class_=AsyncSession,
            expire_on_commit=False,
        )
        test_card_num = "4111111111111161"
        try:
            async with sessionmaker() as s:
                card = _make_card(card_num=test_card_num)
                s.add(card)
                await s.commit()
                # INSERT sets version to 1 via version_id_generator.
                assert card.version_id == 1

            async with sessionmaker() as s:
                loaded_card = await s.get(Card, test_card_num)
                assert loaded_card is not None
                assert loaded_card.version_id == 1
                loaded_card.active_status = "N"
                await s.commit()
                # UPDATE advances version 1 -> 2.
                assert loaded_card.version_id == 2
        finally:
            async with sessionmaker() as s:
                cleanup_card = await s.get(Card, test_card_num)
                if cleanup_card is not None:
                    await s.delete(cleanup_card)
                    await s.commit()

    async def test_card_concurrent_update_conflict(self, async_engine: AsyncEngine) -> None:
        """Concurrent Card update triggers StaleDataError.

        # Optimistic concurrency replaces CICS READ UPDATE from
        # COCRDUPC.cbl.
        """
        sessionmaker = async_sessionmaker(
            bind=async_engine,
            class_=AsyncSession,
            expire_on_commit=False,
        )
        test_card_num = "4111111111111162"
        try:
            async with sessionmaker() as s:
                s.add(_make_card(card_num=test_card_num))
                await s.commit()

            session_a = sessionmaker()
            session_b = sessionmaker()
            try:
                card_a = await session_a.get(Card, test_card_num)
                card_b = await session_b.get(Card, test_card_num)
                assert card_a is not None
                assert card_b is not None
                # Both sessions read the card at version 1 (INSERT set it).
                assert card_a.version_id == 1
                assert card_b.version_id == 1

                # Session A wins - version 1 -> 2.
                card_a.active_status = "N"
                await session_a.commit()
                assert card_a.version_id == 2

                # Session B loses - StaleDataError (its expected
                # version is still 1 but the row is now at version 2).
                card_b.active_status = "S"  # suspended
                with pytest.raises(StaleDataError):
                    await session_b.commit()
                await session_b.rollback()
            finally:
                await session_a.close()
                await session_b.close()

            # Verify Session A's winning state persisted.
            async with sessionmaker() as s:
                final = await s.get(Card, test_card_num)
                assert final is not None
                assert final.active_status == "N"
                assert final.version_id == 2
        finally:
            async with sessionmaker() as s:
                cleanup_card = await s.get(Card, test_card_num)
                if cleanup_card is not None:
                    await s.delete(cleanup_card)
                    await s.commit()


# ===========================================================================
# PHASE 9 - MONETARY FIELD PRECISION TESTS
# ===========================================================================
# Validates COBOL PIC S9(n)V99 -> PostgreSQL NUMERIC(15, 2) precision
# preservation across the round-trip. ALL comparisons use exact Decimal
# equality - NO epsilon-based float tolerance - because financial
# calculations in COBOL are exact by definition (decimal arithmetic,
# not floating-point).
#
# Per AAP section 0.7.2:
#   "All monetary values must use Python decimal.Decimal with explicit
#    two-decimal-place precision, matching COBOL PIC S9(n)V99 semantics"
# ===========================================================================


class TestMonetaryPrecision:
    """Monetary field precision round-trip tests.

    # Validates that Decimal values survive the full round-trip:
    # Python Decimal -> SQLAlchemy Numeric -> PostgreSQL NUMERIC(15, 2)
    # -> asyncpg -> SQLAlchemy Numeric -> Python Decimal, with EXACT
    # equality preserved at every step.
    """

    async def test_account_balance_decimal_precision(self, session: AsyncSession) -> None:
        """Store and retrieve exact Decimal for Account.curr_bal.

        # Validates COBOL PIC S9(10)V99 -> Python Decimal -> PostgreSQL
        # NUMERIC(15, 2).
        """
        original = Decimal("12345678.99")
        acct = _make_account(
            acct_id="00000000041",
            curr_bal=original,
        )
        session.add(acct)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(Account, "00000000041")
        assert retrieved is not None
        # EXACT equality - NOT abs(a - b) < epsilon.
        assert retrieved.curr_bal == original
        # Reinforce that the type is Decimal, not float - no silent
        # coercion in the driver layer.
        assert isinstance(retrieved.curr_bal, Decimal)
        # Decimal string representation must match byte-for-byte to
        # prove no silent rounding occurred.
        assert str(retrieved.curr_bal) == "12345678.99"

    async def test_account_balance_two_decimal_places(self, session: AsyncSession) -> None:
        """Verify NUMERIC(15, 2) normalises to exactly 2 decimal places.

        # COBOL PIC S9(10)V99 has exactly 2 implied decimal places.
        # PostgreSQL's NUMERIC(15, 2) preserves 2 decimal places on
        # read-back. A value of 100.1 stored must return 100.10.
        """
        stored = Decimal("100.1")  # one decimal place
        acct = _make_account(
            acct_id="00000000042",
            curr_bal=stored,
        )
        session.add(acct)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(Account, "00000000042")
        assert retrieved is not None
        # Numeric equality holds: 100.1 == 100.10.
        assert retrieved.curr_bal == Decimal("100.10")
        # The stored representation must have two decimal places,
        # proving the scale was respected on round-trip.
        assert retrieved.curr_bal.as_tuple().exponent == -2
        assert str(retrieved.curr_bal) == "100.10"

    async def test_account_balance_large_value(self, session: AsyncSession) -> None:
        """Max-value precision test for PIC S9(10)V99.

        # Max value for COBOL PIC S9(10)V99 = 9999999999.99
        # (ten integer digits + two fractional digits).
        """
        max_val = Decimal("9999999999.99")
        acct = _make_account(
            acct_id="00000000043",
            curr_bal=max_val,
            credit_limit=max_val,
            cash_credit_limit=max_val,
            curr_cyc_credit=max_val,
            curr_cyc_debit=max_val,
        )
        session.add(acct)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(Account, "00000000043")
        assert retrieved is not None
        # Every monetary field must round-trip exactly.
        assert retrieved.curr_bal == max_val
        assert retrieved.credit_limit == max_val
        assert retrieved.cash_credit_limit == max_val
        assert retrieved.curr_cyc_credit == max_val
        assert retrieved.curr_cyc_debit == max_val
        assert str(retrieved.curr_bal) == "9999999999.99"

    async def test_account_balance_negative(self, session: AsyncSession) -> None:
        """Negative monetary value - COBOL PIC S9(10)V99 'S' is signed.

        # Maps to COBOL PIC S9(10)V99 where 'S' indicates signed.
        # Negative balances represent overpayments/credits.
        """
        neg_val = Decimal("-500.25")
        acct = _make_account(
            acct_id="00000000044",
            curr_bal=neg_val,
        )
        session.add(acct)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(Account, "00000000044")
        assert retrieved is not None
        assert retrieved.curr_bal == neg_val
        assert retrieved.curr_bal < Decimal("0")
        assert str(retrieved.curr_bal) == "-500.25"

    async def test_account_balance_zero(self, session: AsyncSession) -> None:
        """Zero-balance precision - 0.00 must equal 0.00.

        # Zero is the default for newly-opened accounts. Must round-trip
        # as Decimal("0.00") with two decimal places preserved.
        """
        zero = Decimal("0.00")
        acct = _make_account(
            acct_id="00000000045",
            curr_bal=zero,
        )
        session.add(acct)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(Account, "00000000045")
        assert retrieved is not None
        # Numeric equality and non-negativity.
        assert retrieved.curr_bal == zero
        assert retrieved.curr_bal == Decimal("0")
        # But the preserved representation carries 2-decimal-place scale.
        assert retrieved.curr_bal.as_tuple().exponent == -2

    async def test_transaction_amount_precision(self, session: AsyncSession) -> None:
        """Precision test for Transaction.amount.

        # COBOL PIC S9(09)V99 -> NUMERIC(15, 2). Nine integer digits +
        # two fractional = 11 significant digits per value.
        """
        # Max positive value for PIC S9(09)V99.
        max_tran = Decimal("999999999.99")
        tran_max = _make_transaction(
            tran_id="0000000000000041",
            amount=max_tran,
        )
        session.add(tran_max)

        # Negative amount (refund/chargeback).
        neg_amount = Decimal("-1234567.89")
        tran_neg = _make_transaction(
            tran_id="0000000000000042",
            amount=neg_amount,
        )
        session.add(tran_neg)

        # Small-penny amount.
        penny = Decimal("0.01")
        tran_penny = _make_transaction(
            tran_id="0000000000000043",
            amount=penny,
        )
        session.add(tran_penny)

        await session.flush()
        session.expire_all()

        r_max = await session.get(Transaction, "0000000000000041")
        r_neg = await session.get(Transaction, "0000000000000042")
        r_penny = await session.get(Transaction, "0000000000000043")
        assert r_max is not None
        assert r_neg is not None
        assert r_penny is not None
        assert r_max.amount == max_tran
        assert r_neg.amount == neg_amount
        assert r_penny.amount == penny
        assert isinstance(r_max.amount, Decimal)
        assert str(r_max.amount) == "999999999.99"
        assert str(r_neg.amount) == "-1234567.89"
        assert str(r_penny.amount) == "0.01"

    async def test_category_balance_precision(self, session: AsyncSession) -> None:
        """Precision test for TransactionCategoryBalance.balance.

        # Maps to PIC S9(09)V99 from app/cpy/CVTRA01Y.cpy. Same format
        # as Transaction.amount - stored as NUMERIC(15, 2).
        """
        # Seed three TCB rows with different precision samples.
        tcb1 = TransactionCategoryBalance(
            acct_id="00000000051",
            type_cd="01",
            cat_cd="1001",
            balance=Decimal("999999999.99"),
        )
        tcb2 = TransactionCategoryBalance(
            acct_id="00000000052",
            type_cd="02",
            cat_cd="2001",
            balance=Decimal("-50.00"),
        )
        tcb3 = TransactionCategoryBalance(
            acct_id="00000000053",
            type_cd="03",
            cat_cd="3001",
            balance=Decimal("0.01"),
        )
        session.add_all([tcb1, tcb2, tcb3])
        await session.flush()
        session.expire_all()

        r1 = await session.get(TransactionCategoryBalance, ("00000000051", "01", "1001"))
        r2 = await session.get(TransactionCategoryBalance, ("00000000052", "02", "2001"))
        r3 = await session.get(TransactionCategoryBalance, ("00000000053", "03", "3001"))
        assert r1 is not None
        assert r2 is not None
        assert r3 is not None
        assert r1.balance == Decimal("999999999.99")
        assert r2.balance == Decimal("-50.00")
        assert r3.balance == Decimal("0.01")
        assert isinstance(r1.balance, Decimal)

    async def test_disclosure_group_int_rate_precision(self, session: AsyncSession) -> None:
        """Precision test for DisclosureGroup.int_rate - Numeric(6, 2).

        # Maps to COBOL PIC S9(04)V99 -> NUMERIC(6, 2) from
        # app/cpy/CVTRA02Y.cpy. Four integer digits + two fractional =
        # six significant digits. Typical APRs are 0.00% - 99.99%
        # though the field supports up to 9999.99 for unusual cases.
        """
        dg = DisclosureGroup(
            acct_group_id="TESTGRP",
            tran_type_cd="01",
            tran_cat_cd="1001",
            int_rate=Decimal("18.99"),
        )
        session.add(dg)
        await session.flush()
        session.expire_all()

        retrieved = await session.get(DisclosureGroup, ("TESTGRP", "01", "1001"))
        assert retrieved is not None
        assert retrieved.int_rate == Decimal("18.99")
        assert isinstance(retrieved.int_rate, Decimal)
        assert retrieved.int_rate.as_tuple().exponent == -2
        assert str(retrieved.int_rate) == "18.99"

        # Seed additional boundary cases.
        zero_apr = DisclosureGroup(
            acct_group_id="TESTGRP",
            tran_type_cd="02",
            tran_cat_cd="2001",
            int_rate=Decimal("0.00"),
        )
        max_apr = DisclosureGroup(
            acct_group_id="TESTGRP",
            tran_type_cd="03",
            tran_cat_cd="3001",
            int_rate=Decimal("9999.99"),  # field max
        )
        session.add_all([zero_apr, max_apr])
        await session.flush()
        session.expire_all()

        r_zero = await session.get(DisclosureGroup, ("TESTGRP", "02", "2001"))
        r_max = await session.get(DisclosureGroup, ("TESTGRP", "03", "3001"))
        assert r_zero is not None
        assert r_max is not None
        assert r_zero.int_rate == Decimal("0.00")
        assert r_max.int_rate == Decimal("9999.99")
        assert str(r_zero.int_rate) == "0.00"
        assert str(r_max.int_rate) == "9999.99"
