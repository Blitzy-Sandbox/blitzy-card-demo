# ============================================================================
# CardDemo — Integration Test Package Init (Mainframe-to-Cloud migration)
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
"""CardDemo integration test suite. Tests real database operations (Testcontainers PostgreSQL) and API endpoint behavior (FastAPI TestClient) for all 22 features (F-001 through F-022). Source: COBOL online programs (app/cbl/CO*.cbl), JCL provisioning jobs (app/jcl/*.jcl), and COBOL copybook record layouts (app/cpy/*.cpy) — Mainframe-to-Cloud migration.

This package is the marker for the **integration** tier of the CardDemo
test pyramid. Like its parent ``tests`` package and its sibling
``tests.unit``, it is intentionally minimal:

* **No imports of test modules** — pytest auto-discovers ``test_*.py``
  files via the project's ``[tool.pytest.ini_options]`` configuration in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* **No shared fixtures or helpers** — fixtures live in
  ``tests/conftest.py`` (session-wide) and subpackage-local
  ``conftest.py`` files so that pytest's hierarchical fixture resolution
  applies and fixtures stay close to the tests that use them. Integration
  fixtures (Testcontainers PostgreSQL, moto AWS mocks, FastAPI
  TestClient) are scoped appropriately — typically ``module`` or
  ``session`` scope to amortize container startup cost.
* **No executable side effects** — importing ``tests.integration`` must
  be a no-op so that tooling (coverage reporters, IDE test runners, CI
  linters, mypy) can safely introspect the package without starting
  Docker containers, opening PostgreSQL connections, or constructing
  AWS clients.

Integration Test Scope
----------------------
Integration tests in this package exercise **real external services**
rather than mocks — the defining distinction from ``tests.unit``:

* **Database** — Real PostgreSQL 16 instance spun up via
  ``testcontainers[postgres]``. The Flyway-style migrations under
  ``db/migrations/`` (``V1__schema.sql``, ``V2__indexes.sql``,
  ``V3__seed_data.sql``) are applied before tests run, ensuring the
  11 tables (Account, Card, Customer, CardCrossReference, Transaction,
  TransactionCategoryBalance, DailyTransaction, DisclosureGroup,
  TransactionType, TransactionCategory, UserSecurity) and 3 B-tree
  indexes (replacing VSAM AIX paths) are provisioned exactly as the
  production Aurora PostgreSQL schema. This validates the
  VSAM → Aurora PostgreSQL migration end-to-end.
* **AWS services** — Mocked via the ``moto`` library rather than hitting
  live AWS. ``moto`` provides in-process fakes for S3 (GDG-equivalent
  output), SQS FIFO (report submission queue replacing CICS TDQ),
  Secrets Manager (database credentials), and Glue (job submission).
  This keeps integration tests hermetic and runnable in CI without
  AWS credentials.
* **FastAPI application** — Exercised via ``fastapi.testclient.TestClient``
  (or ``httpx.AsyncClient`` for async paths) against the in-process
  ASGI app with a real database session factory bound to the
  Testcontainers PostgreSQL instance. This validates the CICS → FastAPI
  migration end-to-end, including request routing, JWT auth middleware,
  Pydantic schema validation, SQLAlchemy ORM operations, and
  transactional rollback semantics (replacing CICS SYNCPOINT ROLLBACK).
* **Spark / Glue** — Integration tests for batch jobs use a local
  ``SparkSession`` against the Testcontainers PostgreSQL instance via
  JDBC (pg8000 / psycopg2). This exercises the PySpark DataFrame →
  Aurora PostgreSQL write path without needing AWS Glue; true
  end-to-end pipeline tests (Step Functions orchestration,
  S1→S2→S3→S4a∥S4b) live in ``tests.e2e``.

Planned Test Modules
--------------------
Per AAP §0.4.1 (Refactored Structure Planning) and §0.5.1 (File-by-File
Transformation Plan), the ``tests.integration`` package contains two
top-level test modules (populated by subsequent test-authoring passes):

* ``tests.integration.test_database``
    Validates the VSAM → PostgreSQL migration:

    - Schema DDL (``db/migrations/V1__schema.sql``) creates the 11
      tables with correct columns, NUMERIC(15,2) precision for
      monetary fields, composite primary keys for
      TransactionCategoryBalance / DisclosureGroup /
      TransactionCategory, and NOT NULL / UNIQUE / FK constraints
      matching VSAM cluster definitions (``app/jcl/ACCTFILE.jcl``,
      ``app/jcl/CARDFILE.jcl``, ``app/jcl/CUSTFILE.jcl``,
      ``app/jcl/TRANFILE.jcl``, ``app/jcl/XREFFILE.jcl``,
      ``app/jcl/TCATBALF.jcl``, ``app/jcl/DUSRSECJ.jcl``).
    - Index DDL (``db/migrations/V2__indexes.sql``) creates the 3
      B-tree indexes replacing VSAM AIX paths (card.acct_id,
      card_cross_reference.acct_id, transaction.proc_ts) per
      ``app/jcl/TRANIDX.jcl`` and ``app/catlg/LISTCAT.txt``.
    - Seed data (``db/migrations/V3__seed_data.sql``) loads the 9
      ASCII fixture files (50 accounts, 50 cards, 50 customers, 50
      xrefs, 50 TCBs, 51 disclosure groups, 18 categories, 7 types,
      10 users, daily transactions) with correct field widths and
      COBOL-packed-decimal → NUMERIC conversions.
    - SQLAlchemy ORM round-trip tests: insert, read, update, delete
      for every entity; composite-key lookups; optimistic-concurrency
      ``version_id_col`` semantics on Account and Card (replacing
      CICS READ UPDATE / REWRITE); cascading deletes and referential
      integrity.
    - Transactional patterns: SQLAlchemy session rollback on
      exception (replacing CICS SYNCPOINT ROLLBACK in F-005 Account
      Update and F-012 Bill Payment dual-write), SERIALIZABLE
      isolation level for financial operations.

* ``tests.integration.test_api_endpoints``
    Validates the CICS → FastAPI migration:

    - POST /auth/login (F-001) — BCrypt password verification
      (preserving COBOL-era ``app/cbl/COSGN00C.cbl`` security),
      JWT access-token issuance (replacing CICS COMMAREA session),
      401 on invalid credentials, rate-limiting headers.
    - GET /accounts/{id} (F-004) — 3-entity join (Account + Customer
      + CardCrossReference) replacing COBOL multi-READ logic in
      ``app/cbl/COACTVWC.cbl``; JWT-authenticated access; 404 on
      missing; response schema matches ``app/cpy-bms/COACTVW.CPY``
      symbolic map fields.
    - PUT /accounts/{id} (F-005) — Dual-write with transactional
      rollback replacing the ~4,236-line ``app/cbl/COACTUPC.cbl``
      SYNCPOINT ROLLBACK pattern; optimistic concurrency via
      ``@version``; 409 Conflict on version mismatch; 400 on
      validation failure.
    - GET /cards (F-006), GET /cards/{id} (F-007),
      PUT /cards/{id} (F-008) — Paginated list (7 rows/page),
      detail view, optimistic-concurrency update per
      ``app/cbl/COCRDLIC.cbl``, ``app/cbl/COCRDSLC.cbl``,
      ``app/cbl/COCRDUPC.cbl``.
    - GET /transactions (F-009), GET /transactions/{id} (F-010),
      POST /transactions (F-011) — Paginated list (10 rows/page),
      detail view, auto-ID + xref resolution per
      ``app/cbl/COTRN00C.cbl``, ``app/cbl/COTRN01C.cbl``,
      ``app/cbl/COTRN02C.cbl``.
    - POST /bills/pay (F-012) — Dual-write (Transaction INSERT +
      Account balance UPDATE) within one SQLAlchemy transaction,
      rollback on failure, per ``app/cbl/COBIL00C.cbl``.
    - POST /reports/submit (F-022) — Publishes to mocked SQS FIFO
      queue via moto (replacing CICS TDQ WRITEQ JOBS) per
      ``app/cbl/CORPT00C.cbl``.
    - GET /users, POST /users, PUT /users/{id}, DELETE /users/{id}
      (F-018 through F-021) — Full CRUD with BCrypt password
      hashing on create/update per ``app/cbl/COUSR00C.cbl``,
      ``app/cbl/COUSR01C.cbl``, ``app/cbl/COUSR02C.cbl``,
      ``app/cbl/COUSR03C.cbl``.
    - Global error handler — Validates that COBOL-equivalent error
      codes from ``app/cpy/CSMSG01Y.cpy`` and ``app/cpy/CSMSG02Y.cpy``
      surface as consistent JSON error envelopes.

Markers
-------
All tests in this package should be decorated with
``@pytest.mark.integration`` (registered in ``pyproject.toml`` under
``[tool.pytest.ini_options] markers``) so that selective execution is
possible:

* Run only integration tests:       ``pytest -m integration``
* Skip slow integration tests:      ``pytest -m "integration and not slow"``
* Run unit + integration:           ``pytest -m "unit or integration"``
* Skip integration (for fast CI):   ``pytest -m "not integration"``

Tests that spin up a Testcontainers PostgreSQL instance or any other
container-backed dependency should additionally be marked
``@pytest.mark.slow`` so that fast-feedback CI jobs can skip them via
``pytest -m "not slow"``.

Prerequisites
-------------
Integration tests require the following to be available on the host
running ``pytest``:

* **Docker** — Needed by ``testcontainers`` to start a PostgreSQL 16
  container. The ``Docker 28.5.2`` version validated during environment
  setup is sufficient.
* **Java 17** — Required by PySpark 3.5.6 for the local ``SparkSession``
  used by batch-job integration tests. ``JAVA_HOME`` must point at an
  OpenJDK 17 installation.
* **Python 3.11** — Matches the AWS Glue 5.1 runtime and the ECS
  container image.
* **Network access** to Docker Hub to pull the ``postgres:16-alpine``
  image on first run. Subsequent runs use the locally cached image.

No AWS credentials are required: all AWS services are mocked via
``moto``. No live network access to AWS is ever attempted.

Coverage Contribution
---------------------
Per AAP §0.7.2 (Testing Requirements) and the Mainframe-to-Cloud parity
contract, the combined unit + integration + e2e suites must achieve
**at least 80% line coverage** (enforced by ``--cov-fail-under=80`` in
``pyproject.toml``), matching parity with the 81.5% coverage of the
originating mainframe test harness. Integration tests contribute
coverage for code paths that are difficult to exercise with mocks:
ORM-level SQL generation, transaction isolation semantics, FastAPI
middleware interaction, and end-to-end JWT auth flows.

See Also
--------
``tests.__init__``        — Parent test-suite package marker and full
                            feature catalog.
``tests.unit.__init__``   — Sibling unit-test package marker.
``tests.conftest``        — Session-wide pytest fixtures (DB session,
                            FastAPI TestClient, Spark session, mocked
                            AWS services).
``db/migrations/``        — Flyway-style schema / index / seed SQL
                            applied by integration-test fixtures.
AAP §0.4.1                — Refactored Structure Planning
                            (``tests/integration/`` subtree).
AAP §0.5.1                — File-by-File Transformation Plan.
AAP §0.7.2                — Testing Requirements (pytest, Testcontainers,
                            moto, coverage targets).
"""
