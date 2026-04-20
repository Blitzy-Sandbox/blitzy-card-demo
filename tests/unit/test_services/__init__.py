# ============================================================================
# CardDemo — Service Unit Test Package Init (Mainframe-to-Cloud migration)
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
"""Unit tests for API service layer. Tests all 7 service classes covering Features F-001, F-004 through F-012, F-018 through F-022. Services tested with mocked database sessions (AsyncSession). All monetary assertions use Decimal comparison. Source: Online CICS COBOL programs (app/cbl/CO*.cbl) — Mainframe-to-Cloud migration.

This package is the marker for the **service** subtree of the unit-test
pyramid. Like its parent packages (``tests``, ``tests.unit``, and its
sibling ``tests.unit.test_models``), it is intentionally minimal:

* **No imports of test modules** — pytest auto-discovers ``test_*.py``
  files via the project's ``[tool.pytest.ini_options]`` configuration in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* **No shared fixtures or helpers** — fixtures live in
  ``tests/conftest.py`` (session-wide) and subpackage-local
  ``conftest.py`` files so that pytest's hierarchical fixture resolution
  applies and fixtures stay close to the tests that use them.
* **No executable side effects** — importing
  ``tests.unit.test_services`` must be a no-op so that tooling
  (coverage reporters, IDE test runners, CI linters, mypy) can safely
  introspect the package without triggering database connections,
  AWS client construction, or Spark context creation.

Service-to-COBOL Mapping
------------------------
Per AAP §0.5.1 (File-by-File Transformation Plan), the 7 service classes
under ``src/api/services/`` encapsulate the business logic translated
from online CICS COBOL programs under ``app/cbl/CO*.cbl``. Each service
aggregates the PROCEDURE DIVISION logic from one or more COBOL programs
into a cohesive domain module:

========================================  ==========================================  ============================================================
Service (``src/api/services/``)           COBOL Program(s) (``app/cbl/``)             Feature ID(s) & Function
========================================  ==========================================  ============================================================
``auth_service.AuthService``              ``COSGN00C.cbl``                            F-001 Sign-on / authentication (BCrypt + JWT)
``account_service.AccountService``        ``COACTVWC.cbl`` + ``COACTUPC.cbl``         F-004 Account view (3-entity join) /
                                                                                      F-005 Account update (SYNCPOINT ROLLBACK, dual-write)
``card_service.CardService``              ``COCRDLIC.cbl`` + ``COCRDSLC.cbl`` +       F-006 Card list (7 rows/page) /
                                          ``COCRDUPC.cbl``                            F-007 Card detail view /
                                                                                      F-008 Card update (optimistic concurrency)
``transaction_service.Transaction`` …     ``COTRN00C.cbl`` + ``COTRN01C.cbl`` +       F-009 Transaction list (10 rows/page) /
                                          ``COTRN02C.cbl``                            F-010 Transaction detail view /
                                                                                      F-011 Transaction add (auto-ID + xref resolution)
``bill_service.BillService``              ``COBIL00C.cbl``                            F-012 Bill payment (dual-write: Transaction INSERT +
                                                                                              Account balance UPDATE, atomic)
``report_service.ReportService``          ``CORPT00C.cbl``                            F-022 Report submission (TDQ WRITEQ JOBS -> SQS FIFO)
``user_service.UserService``              ``COUSR00C.cbl`` + ``COUSR01C.cbl`` +       F-018 User list /
                                          ``COUSR02C.cbl`` + ``COUSR03C.cbl``         F-019 User add (BCrypt hashing) /
                                                                                      F-020 User update /
                                                                                      F-021 User delete
========================================  ==========================================  ============================================================

Per AAP §0.4.3 (Design Pattern Applications), these services apply:

* **Service Layer** — separates business logic from API routing, replacing
  the COBOL PROCEDURE DIVISION paragraphs.
* **Repository Pattern** — SQLAlchemy ORM models (injected via
  ``AsyncSession``) encapsulate all data access, replacing VSAM READ /
  WRITE / REWRITE / DELETE.
* **Transactional Outbox** — SQLAlchemy session context managers with
  rollback-on-exception replace CICS SYNCPOINT ROLLBACK (F-005 Account
  Update, F-012 Bill Payment).
* **Optimistic Concurrency** — SQLAlchemy ``version_id`` column replaces
  the CICS READ UPDATE / REWRITE pattern (F-008 Card Update).
* **Stateless Authentication** — JWT tokens (``python-jose``) replace the
  CICS COMMAREA session (``app/cpy/COCOM01Y.cpy``) threading of user
  identity / user type between transactions.

Test Module Scope
-----------------
Each service is expected to have a corresponding ``test_<service>.py``
module in this package. Test modules are expected to exercise the
following dimensions:

* **Happy path** — each public service method returns the correct
  result for valid input (e.g., ``AccountService.get_account(acct_id)``
  returns an ``AccountView`` matching the 3-entity join produced by
  ``COACTVWC.cbl``).
* **Error handling** — each service method raises the COBOL-equivalent
  error condition (mapped to a service-layer exception) for invalid
  input (e.g., lookup miss, validation failure, concurrent update
  collision).
* **Financial precision** — every monetary parameter and return value
  uses :class:`decimal.Decimal`, never :class:`float`. Assertions use
  ``Decimal("123.45") == result.amount`` semantics to preserve COBOL
  ``PIC S9(n)V99`` precision per AAP §0.7.2.
* **Transactional semantics** — dual-write paths (F-005 Account Update,
  F-012 Bill Payment) are tested for atomic rollback on mid-transaction
  failure, mirroring ``EXEC CICS SYNCPOINT ROLLBACK`` behavior.
* **Optimistic concurrency** — F-008 Card Update tests assert that a
  concurrent write raises :class:`sqlalchemy.orm.exc.StaleDataError`,
  preserving CICS READ UPDATE / REWRITE collision semantics.
* **Authentication + authorization** — F-001 Sign-on tests assert that
  BCrypt password verification rejects invalid passwords and that
  generated JWT tokens include the ``sub``, ``user_id``, and
  ``user_type`` claims matching the COMMAREA fields in
  ``app/cpy/COCOM01Y.cpy``.
* **AWS integration** — F-022 Report submission tests assert that the
  service publishes a single SQS FIFO message per submission and that
  the payload matches the TDQ record layout from ``CORPT00C.cbl`` WRITEQ
  TD JOBS. AWS clients are mocked via ``moto.mock_aws`` (no live
  calls).
* **Pagination semantics** — F-006 Card list and F-009 Transaction list
  tests assert that page size is exactly 7 and 10 respectively (matching
  the BMS map row counts) and that next-page tokens / PF7/PF8
  equivalents are generated correctly.

Test Isolation — Mocked Dependencies
------------------------------------
Unit tests in this package are **fast and isolated**. Per AAP §0.7.2:

* **Database** — Tests use an in-memory SQLite ``AsyncSession`` or
  a mocked ``AsyncSession`` (``unittest.mock.AsyncMock``). Real
  PostgreSQL integration tests live in ``tests.integration``.
* **AWS services** — Tests use ``moto.mock_aws`` to mock S3 (replaces
  GDG from ``app/jcl/DEFGDGB.jcl``), SQS FIFO (replaces CICS TDQ from
  ``CORPT00C.cbl``), and Secrets Manager (replaces RACF credentials).
  No live AWS calls.
* **BCrypt / passlib** — Use real BCrypt hashing (fast enough for unit
  tests), with a known test password per the fixture in
  ``tests/conftest.py::UserSecurityFactory``.
* **JWT / python-jose** — Use real ``jose.jwt.encode`` /
  ``jose.jwt.decode`` calls with a test-only secret key from
  ``tests/conftest.py::create_test_token``. No live identity provider.

Markers
-------
All tests in this package should be decorated with ``@pytest.mark.unit``
(registered in ``pyproject.toml`` under
``[tool.pytest.ini_options] markers``) so that selective execution is
possible:

* Run only service unit tests:    ``pytest tests/unit/test_services -m unit``
* Run all unit tests:              ``pytest -m unit``
* Skip slow service tests:         ``pytest -m "unit and not slow"``

Tests that exercise BCrypt hashing or JWT encoding may be additionally
decorated with ``@pytest.mark.slow`` if their execution time exceeds a
few seconds (BCrypt cost factor 12 is the default and can be slow
under CPU contention).

Feature Coverage
----------------
Per AAP §0.7.2 (Testing Requirements), every online-facing migrated
feature maps to at least one test module in this package:

* F-001 Sign-on / authentication              -> ``test_auth_service``
* F-004 Account view                          -> ``test_account_service``
* F-005 Account update (SYNCPOINT ROLLBACK)   -> ``test_account_service``
* F-006 Card list (7 rows/page)               -> ``test_card_service``
* F-007 Card detail view                      -> ``test_card_service``
* F-008 Card update (optimistic concurrency)  -> ``test_card_service``
* F-009 Transaction list (10 rows/page)       -> ``test_transaction_service``
* F-010 Transaction detail view               -> ``test_transaction_service``
* F-011 Transaction add (auto-ID + xref)      -> ``test_transaction_service``
* F-012 Bill payment (dual-write)             -> ``test_bill_service``
* F-018 User list                             -> ``test_user_service``
* F-019 User add (BCrypt hashing)             -> ``test_user_service``
* F-020 User update                           -> ``test_user_service``
* F-021 User delete                           -> ``test_user_service``
* F-022 Report submission (TDQ -> SQS FIFO)   -> ``test_report_service``

Features F-002 (Main menu navigation), F-003 (Admin menu navigation),
and all batch features (F-013 through F-017) are covered in sibling
packages (``tests.unit.test_routers`` and ``tests.unit.test_batch``
respectively).

Coverage Target
---------------
Per AAP §0.7.2 and the Mainframe-to-Cloud parity contract, the combined
unit + integration + e2e suites must achieve **at least 80% line
coverage** (enforced by ``--cov-fail-under=80`` in ``pyproject.toml``),
matching parity with the 81.5% coverage of the originating mainframe
test harness. Service-layer unit tests are expected to contribute the
majority of this coverage for online programs (``src/api/services/``)
given their breadth (7 services * ~5-10 test methods each) and their
direct coverage of COBOL PROCEDURE DIVISION business logic.

See Also
--------
``tests.__init__``               — Parent test-suite package marker
                                   and full feature catalog (F-001
                                   through F-022).
``tests.unit.__init__``          — Parent unit-test package marker
                                   describing the full unit-test
                                   subpackage layout.
``tests.unit.test_models``       — Sibling unit-test package covering
                                   the 11 SQLAlchemy ORM models that
                                   services operate on.
``tests.unit.test_routers``      — Sibling unit-test package covering
                                   the FastAPI route handlers that
                                   invoke these services.
``tests.unit.test_batch``        — Sibling unit-test package covering
                                   the PySpark batch jobs (F-013
                                   through F-017).
``tests.conftest``               — Session-wide pytest fixtures (DB
                                   session, FastAPI TestClient, Spark
                                   session, mocked AWS services,
                                   factory-boy factories for the 11
                                   entities, JWT token helpers).
``src.api.services``             — The 7 service classes under test.
AAP §0.4.1                       — Refactored Structure Planning
                                   (``tests/unit/test_services/``
                                   subtree).
AAP §0.4.3                       — Design Pattern Applications (Service
                                   Layer, Repository, Transactional
                                   Outbox, Optimistic Concurrency,
                                   Stateless Auth).
AAP §0.5.1                       — File-by-File Transformation Plan
                                   (service-to-COBOL mapping).
AAP §0.7.1                       — Financial precision requirements
                                   (COBOL ``PIC S9(n)V99`` ->
                                   ``decimal.Decimal``).
AAP §0.7.2                       — Testing Requirements (pytest as
                                   primary test framework, moto for
                                   AWS mocking, parity with 81.5%
                                   originating coverage).
"""
