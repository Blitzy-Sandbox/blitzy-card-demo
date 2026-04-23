# ============================================================================
# CardDemo — Router Unit Test Package Init (Mainframe-to-Cloud migration)
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
"""Unit tests for FastAPI REST API routers.

Converted from 18 online CICS COBOL programs (``app/cbl/CO*.cbl``).

Test modules:

* ``test_auth_router``        — ``COSGN00C.cbl`` (F-001) — Sign-on/authentication
* ``test_account_router``     — ``COACTVWC.cbl`` (F-004) + ``COACTUPC.cbl`` (F-005)
  — Account view/update
* ``test_card_router``        — ``COCRDLIC.cbl`` (F-006) + ``COCRDSLC.cbl`` (F-007)
  + ``COCRDUPC.cbl`` (F-008) — Card list/detail/update
* ``test_transaction_router`` — ``COTRN00C.cbl`` (F-009) + ``COTRN01C.cbl`` (F-010)
  + ``COTRN02C.cbl`` (F-011) — Transaction list/detail/add
* ``test_bill_router``        — ``COBIL00C.cbl`` (F-012) — Bill payment
* ``test_report_router``      — ``CORPT00C.cbl`` (F-022) — Report submission
* ``test_user_router``        — ``COUSR00C-03C.cbl`` (F-018 through F-021)
  — User CRUD
* ``test_admin_router``       — ``COADM01C.cbl`` (F-003) — Admin menu

Source: Mainframe-to-Cloud migration.

This package is the marker for the **router** subtree of the unit-test
pyramid. Like its parent packages (``tests``, ``tests.unit``, and its
siblings ``tests.unit.test_models`` and ``tests.unit.test_services``), it
is intentionally minimal:

* **No imports of test modules** — pytest auto-discovers ``test_*.py``
  files via the project's ``[tool.pytest.ini_options]`` configuration in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* **No shared fixtures or helpers** — fixtures live in
  ``tests/conftest.py`` (session-wide) and subpackage-local
  ``conftest.py`` files so that pytest's hierarchical fixture resolution
  applies and fixtures stay close to the tests that use them.
* **No executable side effects** — importing
  ``tests.unit.test_routers`` must be a no-op so that tooling (coverage
  reporters, IDE test runners, CI linters, mypy) can safely introspect
  the package without triggering database connections, AWS client
  construction, or Spark context creation.

Router-to-COBOL Mapping
-----------------------
Per AAP §0.5.1 (File-by-File Transformation Plan), the 8 routers under
``src/api/routers/`` encapsulate the HTTP surface translated from the
18 online CICS COBOL programs under ``app/cbl/CO*.cbl``. Each router
aggregates the CICS SEND MAP / RECEIVE MAP contracts from one or more
COBOL programs into a cohesive REST resource:

========================================  ==========================================  ============================================================
Router (``src/api/routers/``)             COBOL Program(s) (``app/cbl/``)             Feature ID(s) & Function
========================================  ==========================================  ============================================================
``auth_router`` (``/auth``)               ``COSGN00C.cbl``                            F-001 Sign-on / authentication (BCrypt + JWT)
``account_router`` (``/accounts``)        ``COACTVWC.cbl`` + ``COACTUPC.cbl``         F-004 Account view (3-entity join) /
                                                                                      F-005 Account update (SYNCPOINT ROLLBACK, dual-write)
``card_router`` (``/cards``)              ``COCRDLIC.cbl`` + ``COCRDSLC.cbl`` +       F-006 Card list (7 rows/page) /
                                          ``COCRDUPC.cbl``                            F-007 Card detail view /
                                                                                      F-008 Card update (optimistic concurrency)
``transaction_router`` (``/transactions``)  ``COTRN00C.cbl`` + ``COTRN01C.cbl`` +     F-009 Transaction list (10 rows/page) /
                                          ``COTRN02C.cbl``                            F-010 Transaction detail view /
                                                                                      F-011 Transaction add (auto-ID + xref resolution)
``bill_router`` (``/bills``)              ``COBIL00C.cbl``                            F-012 Bill payment (dual-write: Transaction INSERT +
                                                                                              Account balance UPDATE, atomic)
``report_router`` (``/reports``)          ``CORPT00C.cbl``                            F-022 Report submission (TDQ WRITEQ JOBS -> SQS FIFO)
``user_router`` (``/users``)              ``COUSR00C.cbl`` + ``COUSR01C.cbl`` +       F-018 User list /
                                          ``COUSR02C.cbl`` + ``COUSR03C.cbl``         F-019 User add (BCrypt hashing) /
                                                                                      F-020 User update /
                                                                                      F-021 User delete
``admin_router`` (``/admin``)             ``COADM01C.cbl``                            F-003 Admin menu navigation (4-option dispatch)
========================================  ==========================================  ============================================================

Per AAP §0.4.3 (Design Pattern Applications), these routers apply:

* **Dependency Injection** — FastAPI ``Depends()`` for database sessions
  (``get_db``), authenticated user (``get_current_user``), and admin-only
  authorization (``get_current_admin_user``) — replacing the CICS
  COMMAREA (``app/cpy/COCOM01Y.cpy``) parameter-passing idiom used by the
  original COBOL programs to thread user identity/user type across
  transactions.
* **Factory Pattern** — Pydantic response models (derived from BMS
  symbolic maps in ``app/cpy-bms/*.CPY``) construct response objects
  from database entities, replacing COBOL ``MOVE`` statements that
  populated BMS screen maps.
* **Stateless Authentication** — JWT bearer tokens (``python-jose``)
  replace the CICS ``RETURN TRANSID COMMAREA`` session state from
  ``COMEN01C.cbl`` / ``COADM01C.cbl``, allowing horizontal scaling
  across ECS Fargate tasks.

Test Module Scope
-----------------
Each router is expected to have a corresponding ``test_<router>.py``
module in this package. Router tests are distinct from service tests
(``tests.unit.test_services``) in that they exercise the **HTTP
surface** rather than the business logic. Test modules are expected
to exercise the following dimensions:

* **Request validation** — Pydantic request models (derived from BMS
  symbolic maps in ``app/cpy-bms/*.CPY``) reject malformed payloads
  with HTTP 422, preserving the COBOL field-level input edits (numeric,
  length, range checks) from each original program.
* **Response shape** — Pydantic response models match the BMS output
  field layouts (AI/AO areas in ``app/cpy-bms/*.CPY``) on both happy
  path and error responses, including the human-readable ``WS-MESSAGE``
  and ``WS-ERR-FLG`` equivalents.
* **HTTP status codes** — success paths return 200/201/204 as
  appropriate; validation failures return 422; authentication failures
  return 401; authorization failures return 403; entity-not-found
  returns 404; optimistic-concurrency collisions (F-008 Card Update)
  return 409.
* **Authentication middleware** — requests without a valid JWT token
  are rejected with HTTP 401; expired tokens are rejected with HTTP 401
  carrying an ``expired`` error code; missing tokens on protected
  routes are rejected with HTTP 401. This replaces the CICS sign-on
  validation from ``COSGN00C.cbl`` that populated COMMAREA user
  identity fields (``CDEMO-USER-ID``, ``CDEMO-USER-TYPE``).
* **Authorization (admin-only)** — admin-only routes (``/admin/*``,
  ``/users/*`` mutation routes) reject regular users (JWT claim
  ``user_type == 'U'``) with HTTP 403, preserving the ``88 CDEMO-
  USRTYP-ADMIN VALUE 'A'`` 88-level condition check from
  ``COADM01C.cbl``.
* **Pagination semantics** — F-006 Card list returns exactly 7 items
  per page (matching the ``CA1`` through ``CA7`` BMS map rows), and
  F-009 Transaction list returns exactly 10 items per page (matching
  the ``TRN01`` through ``TRN10`` BMS map rows). Next-page/previous-page
  tokens mirror the CICS PF7 / PF8 key semantics from the original
  list programs.
* **Dependency overrides** — tests use
  ``app.dependency_overrides[get_db]`` and
  ``app.dependency_overrides[get_current_user]`` (from
  ``tests/conftest.py``) to inject the per-test database session and
  mock authenticated user, replacing the CICS region's global
  configuration.
* **Financial precision** — every monetary request/response field
  serializes as a JSON string (e.g., ``"1234.56"``) and round-trips
  through :class:`decimal.Decimal` without loss of precision.
  Assertions use ``Decimal(response.json()["amount"]) == Decimal("50.00")``
  semantics to preserve COBOL ``PIC S9(n)V99`` precision per AAP §0.7.1.
* **Dual-write atomicity** — F-005 Account Update and F-012 Bill
  Payment tests assert that a mid-request database error produces HTTP
  500 **and** leaves the database in the pre-request state, mirroring
  ``EXEC CICS SYNCPOINT ROLLBACK`` behavior.
* **SQS FIFO publish** — F-022 Report Submission tests assert that a
  POST to ``/reports/submit`` publishes exactly one message to the
  mocked SQS FIFO queue (``carddemo-reports.fifo``) with a payload
  matching the TDQ record layout from ``CORPT00C.cbl`` WRITEQ TD JOBS.

Test Isolation — Mocked Dependencies
------------------------------------
Unit tests in this package are **fast and isolated**. Per AAP §0.7.2:

* **Database** — Tests use an in-memory SQLite ``AsyncSession`` injected
  via ``app.dependency_overrides[get_db]`` with fixture data created by
  factory-boy factories from ``tests/conftest.py`` (``AccountFactory``,
  ``CardFactory``, etc.). Real PostgreSQL integration tests live in
  ``tests.integration``.
* **AWS services** — Tests use ``moto.mock_aws`` to mock S3 (replaces
  GDG from ``app/jcl/DEFGDGB.jcl``), SQS FIFO (replaces CICS TDQ from
  ``CORPT00C.cbl``), and Secrets Manager (replaces RACF credentials).
  No live AWS calls.
* **HTTP client** — Tests use ``httpx.AsyncClient`` with
  ``ASGITransport(app=test_app)`` (``tests/conftest.py::client``,
  ``admin_client``, ``regular_client``) for in-process ASGI requests
  without a real network socket. Synchronous tests may alternatively use
  ``fastapi.testclient.TestClient``.
* **JWT / python-jose** — Tests use real ``jose.jwt.encode`` calls with
  a test-only secret key from
  ``tests/conftest.py::create_test_token``. No live identity provider.

Markers
-------
All tests in this package should be decorated with ``@pytest.mark.unit``
(registered in ``pyproject.toml`` under
``[tool.pytest.ini_options] markers``) so that selective execution is
possible:

* Run only router unit tests:     ``pytest tests/unit/test_routers -m unit``
* Run all unit tests:              ``pytest -m unit``
* Skip slow router tests:          ``pytest -m "unit and not slow"``

Tests that exercise BCrypt hashing on the auth router (``test_auth_router``)
may be additionally decorated with ``@pytest.mark.slow`` if their
execution time exceeds a few seconds (BCrypt cost factor 12 is the
default and can be slow under CPU contention).

Feature Coverage
----------------
Per AAP §0.7.2 (Testing Requirements), every online-facing migrated
feature maps to at least one test module in this package:

* F-001 Sign-on / authentication              -> ``test_auth_router``
* F-003 Admin menu navigation                 -> ``test_admin_router``
* F-004 Account view                          -> ``test_account_router``
* F-005 Account update (SYNCPOINT ROLLBACK)   -> ``test_account_router``
* F-006 Card list (7 rows/page)               -> ``test_card_router``
* F-007 Card detail view                      -> ``test_card_router``
* F-008 Card update (optimistic concurrency)  -> ``test_card_router``
* F-009 Transaction list (10 rows/page)       -> ``test_transaction_router``
* F-010 Transaction detail view               -> ``test_transaction_router``
* F-011 Transaction add (auto-ID + xref)      -> ``test_transaction_router``
* F-012 Bill payment (dual-write)             -> ``test_bill_router``
* F-018 User list                             -> ``test_user_router``
* F-019 User add (BCrypt hashing)             -> ``test_user_router``
* F-020 User update                           -> ``test_user_router``
* F-021 User delete                           -> ``test_user_router``
* F-022 Report submission (TDQ -> SQS FIFO)   -> ``test_report_router``

Features F-002 (Main menu navigation — ``COMEN01C.cbl``) is implicitly
covered by the router-include configuration tests in
``test_admin_router`` and the full router surface exercised across all
other modules; ``COMEN01C.cbl``'s XCTL dispatch is replaced by FastAPI
``app.include_router()`` calls in ``src/api/main.py``, which are
exercised by every router test in this package.

All batch features (F-013 Batch transaction posting, F-014 Batch
interest calculation, F-015 COMBTRAN merge/sort, F-016 Statement
generation, F-017 Transaction report) are covered in the sibling
package ``tests.unit.test_batch``, not here.

Coverage Target
---------------
Per AAP §0.7.2 and the Mainframe-to-Cloud parity contract, the combined
unit + integration + e2e suites must achieve **at least 80% line
coverage** (enforced by ``--cov-fail-under=80`` in ``pyproject.toml``),
matching parity with the 81.5% coverage of the originating mainframe
test harness. Router-layer unit tests are expected to contribute
meaningful coverage of the HTTP surface (``src/api/routers/``) —
request parsing, response serialization, status-code mapping, and
dependency wiring — while delegating business-logic coverage to the
sibling ``tests.unit.test_services`` package.

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
                                   router handlers operate on via the
                                   service layer.
``tests.unit.test_services``     — Sibling unit-test package covering
                                   the 7 service classes that routers
                                   delegate business logic to.
``tests.unit.test_batch``        — Sibling unit-test package covering
                                   the PySpark batch jobs (F-013
                                   through F-017).
``tests.conftest``               — Session-wide pytest fixtures (DB
                                   session, FastAPI ``TestClient`` and
                                   ``httpx.AsyncClient`` fixtures
                                   ``client`` / ``admin_client`` /
                                   ``regular_client``, mocked AWS
                                   services, JWT token helpers).
``src.api.routers``              — The 8 FastAPI router modules under
                                   test (auth, account, card,
                                   transaction, bill, report, user,
                                   admin).
``src.api.main``                 — The FastAPI application factory
                                   (``create_app()``) that wires the
                                   routers into a single ASGI app,
                                   replacing the CICS XCTL dispatch
                                   from ``COMEN01C.cbl`` /
                                   ``COADM01C.cbl``.
AAP §0.4.1                       — Refactored Structure Planning
                                   (``tests/unit/test_routers/``
                                   subtree).
AAP §0.4.3                       — Design Pattern Applications
                                   (Dependency Injection, Factory,
                                   Stateless Authentication).
AAP §0.5.1                       — File-by-File Transformation Plan
                                   (router-to-COBOL mapping).
AAP §0.7.1                       — Financial precision requirements
                                   (COBOL ``PIC S9(n)V99`` ->
                                   ``decimal.Decimal``).
AAP §0.7.2                       — Testing Requirements (pytest as
                                   primary test framework, moto for
                                   AWS mocking, parity with 81.5%
                                   originating coverage).
"""
