# ============================================================================
# CardDemo — Unit Test Package Init (Mainframe-to-Cloud migration)
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
"""CardDemo unit test suite. Contains unit tests for all models, services, routers, and batch job transformation functions. Tests cover behavioral parity with all 22 features (F-001 through F-022) from the original COBOL/CICS/VSAM mainframe application. Source: Mainframe-to-Cloud migration.

This package is the marker for the **unit** tier of the CardDemo test
pyramid. Like its parent ``tests`` package, it is intentionally minimal:

* **No imports of test modules** — pytest auto-discovers ``test_*.py``
  files via the project's ``[tool.pytest.ini_options]`` configuration in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* **No shared fixtures or helpers** — fixtures live in ``tests/conftest.py``
  (session-wide) and subpackage-local ``conftest.py`` files so pytest's
  hierarchical fixture resolution applies and fixtures stay close to the
  tests that use them.
* **No executable side effects** — importing ``tests.unit`` must be a
  no-op so that tooling (coverage reporters, IDE test runners, CI linters,
  mypy) can safely introspect the package without triggering database
  connections, AWS client construction, or Spark context creation.

Unit Test Scope
---------------
Unit tests in this package are **fast and isolated**. External dependencies
are mocked rather than exercised:

* **Database** — Use in-memory SQLite or mocked SQLAlchemy sessions.
  Real PostgreSQL integration tests live in ``tests.integration``.
* **AWS services** — Use ``moto`` to mock S3, SQS, Secrets Manager, and
  Glue. No live AWS calls.
* **Spark / Glue** — Use a local ``SparkSession`` with in-memory DataFrames
  rather than an AWS Glue runtime. The ``GlueContext`` is stubbed or
  replaced by a thin adapter.
* **HTTP / FastAPI** — Use ``fastapi.testclient.TestClient`` or
  ``httpx.AsyncClient`` against the in-process ASGI app; no external
  network calls.

Subpackages
-----------
The ``tests.unit`` package is organized into four subpackages, one per
architectural layer of the migrated application (per AAP §0.4.1):

* ``tests.unit.test_models``   — Tests for SQLAlchemy ORM models under
                                 ``src/shared/models/`` (Account, Card,
                                 Customer, CardCrossReference, Transaction,
                                 TransactionCategoryBalance, DailyTransaction,
                                 DisclosureGroup, TransactionType,
                                 TransactionCategory, UserSecurity).
                                 Validates primary keys, composite keys,
                                 NUMERIC(15,2) financial precision,
                                 ``@version`` optimistic-locking columns,
                                 and VSAM copybook field parity.
* ``tests.unit.test_services`` — Tests for business-logic service modules
                                 under ``src/api/services/`` (auth, account,
                                 card, transaction, bill, report, user).
                                 Covers the COBOL PROCEDURE DIVISION logic
                                 translated to Python, including the 4-stage
                                 validation cascade, dual-write patterns,
                                 optimistic concurrency, and BCrypt
                                 password handling.
* ``tests.unit.test_routers``  — Tests for FastAPI route handlers under
                                 ``src/api/routers/`` (auth, account, card,
                                 transaction, bill, report, user, admin).
                                 Validates request/response Pydantic
                                 schemas (derived from BMS symbolic maps),
                                 HTTP status codes, JWT authentication
                                 middleware, and pagination semantics
                                 (7 rows/page for cards, 10 rows/page
                                 for transactions).
* ``tests.unit.test_batch``    — Tests for PySpark batch job modules under
                                 ``src/batch/jobs/`` (posttran, intcalc,
                                 combtran, creastmt, tranrept, prtcatbl,
                                 daily_tran_driver, read_*). Covers the
                                 5-stage pipeline transformations with
                                 in-memory DataFrames, reject-code
                                 generation (100-109), interest formula
                                 ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200``,
                                 DEFAULT/ZEROAPR disclosure-group fallback,
                                 and 3-level totals for reporting.

Feature-to-Subpackage Coverage
------------------------------
Per AAP §0.7.2 (Testing Requirements), every migrated feature maps to
at least one unit-test module in one of the subpackages above:

* F-001 Sign-on / authentication              -> ``test_services``, ``test_routers``
* F-002 Main menu navigation                  -> ``test_routers``
* F-003 Admin menu navigation                 -> ``test_routers``
* F-004 Account view                          -> ``test_services``, ``test_routers``
* F-005 Account update (SYNCPOINT ROLLBACK)   -> ``test_services``, ``test_routers``
* F-006 Card list (7 rows/page)               -> ``test_services``, ``test_routers``
* F-007 Card detail view                      -> ``test_services``, ``test_routers``
* F-008 Card update (optimistic concurrency)  -> ``test_services``, ``test_routers``
* F-009 Transaction list (10 rows/page)       -> ``test_services``, ``test_routers``
* F-010 Transaction detail view               -> ``test_services``, ``test_routers``
* F-011 Transaction add (auto-ID + xref)      -> ``test_services``, ``test_routers``
* F-012 Bill payment (dual-write)             -> ``test_services``, ``test_routers``
* F-013 Batch transaction posting (POSTTRAN)  -> ``test_batch``
* F-014 Batch interest calculation (INTCALC)  -> ``test_batch``
* F-015 Batch COMBTRAN merge/sort             -> ``test_batch``
* F-016 Statement generation (CREASTMT)       -> ``test_batch``
* F-017 Transaction report (TRANREPT)         -> ``test_batch``
* F-018 User list                             -> ``test_services``, ``test_routers``
* F-019 User add (BCrypt hashing)             -> ``test_services``, ``test_routers``
* F-020 User update                           -> ``test_services``, ``test_routers``
* F-021 User delete                           -> ``test_services``, ``test_routers``
* F-022 Report submission (TDQ -> SQS FIFO)   -> ``test_services``, ``test_routers``

All feature tests exercise ORM-model invariants from ``test_models`` as
a shared foundation (e.g., Account balance precision, Card optimistic-
locking version columns, Transaction composite-key uniqueness).

Markers
-------
All tests in this package should be decorated with ``@pytest.mark.unit``
(registered in ``pyproject.toml`` under ``[tool.pytest.ini_options]
markers``) so that selective execution is possible:

* Run only unit tests:          ``pytest -m unit``
* Skip slow unit tests:         ``pytest -m "unit and not slow"``
* Run unit + integration:       ``pytest -m "unit or integration"``

Coverage Target
---------------
Per AAP §0.7.2 and the Mainframe-to-Cloud parity contract, the combined
unit + integration + e2e suites must achieve **at least 80% line
coverage** (enforced by ``--cov-fail-under=80`` in ``pyproject.toml``),
matching parity with the 81.5% coverage of the originating mainframe
test harness. Unit tests are expected to contribute the majority of this
coverage given their fast execution and broad scope.

See Also
--------
``tests.__init__``     — Parent test-suite package marker and full
                         feature catalog.
``tests.conftest``     — Session-wide pytest fixtures (DB session,
                         FastAPI TestClient, Spark session, mocked AWS).
AAP §0.4.1             — Refactored Structure Planning (``tests/unit/``
                         subtree).
AAP §0.5.1             — File-by-File Transformation Plan.
AAP §0.7.2             — Testing Requirements (pytest, Testcontainers,
                         moto, coverage targets).
"""
