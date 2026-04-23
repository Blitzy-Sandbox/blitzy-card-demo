# ============================================================================
# CardDemo — Test Suite Package Init (Mainframe-to-Cloud migration)
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
"""CardDemo test suite. Covers unit tests for models/services/routers/batch jobs, integration tests with PostgreSQL via Testcontainers, and end-to-end batch pipeline tests. Source: Mainframe-to-Cloud migration test coverage for 22 features (F-001 through F-022).

This package is the top-level marker for all CardDemo automated tests. It is
intentionally **minimal**:

* No imports of test modules — pytest discovers ``test_*.py`` files
  automatically per the project's ``[tool.pytest.ini_options]`` in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* No shared fixtures or helpers — those live in ``tests/conftest.py`` and
  subpackage ``conftest.py`` files so that pytest's fixture-resolution
  rules apply and fixtures stay close to the tests that use them.
* No executable side effects — importing ``tests`` must be a no-op so
  that tooling (coverage, IDE test runners, CI linters) can safely
  introspect the package without triggering database connections,
  AWS client initialization, or Spark context creation.

Planned subpackages (populated by subsequent test-authoring passes):

* ``tests.unit``        — Fast, isolated tests for models, schemas, services,
                          routers, and PySpark transformations. Mocks used
                          for external dependencies (DB, AWS, Spark).
* ``tests.integration`` — Tests against a real PostgreSQL 16 instance via
                          ``testcontainers[postgres]`` and mocked AWS
                          services via ``moto``. Covers the ORM layer,
                          database migrations (Flyway-style
                          ``db/migrations/*.sql``), and service-to-database
                          integration paths.
* ``tests.e2e``         — End-to-end batch pipeline tests exercising the
                          full 5-stage flow (POSTTRAN -> INTCALC -> COMBTRAN
                          -> (CREASTMT || TRANREPT)) and the FastAPI HTTP
                          surface via ``httpx.AsyncClient``.

Markers registered in ``pyproject.toml``:

* ``@pytest.mark.unit``        — Unit tests (fast, isolated).
* ``@pytest.mark.integration`` — Integration tests (require external services).
* ``@pytest.mark.e2e``         — End-to-end pipeline tests.
* ``@pytest.mark.slow``        — Tests that take more than a few seconds.

Feature Coverage Targets
------------------------
Per AAP §0.7.2 (Testing Requirements) and the Mainframe-to-Cloud parity
contract, automated tests must cover all 22 features F-001 through F-022:

* F-001 Sign-on / authentication              (``app/cbl/COSGN00C.cbl``)
* F-002 Main menu navigation                  (``app/cbl/COMEN01C.cbl``)
* F-003 Admin menu navigation                 (``app/cbl/COADM01C.cbl``)
* F-004 Account view                          (``app/cbl/COACTVWC.cbl``)
* F-005 Account update (SYNCPOINT ROLLBACK)   (``app/cbl/COACTUPC.cbl``)
* F-006 Card list (7 rows/page)               (``app/cbl/COCRDLIC.cbl``)
* F-007 Card detail view                      (``app/cbl/COCRDSLC.cbl``)
* F-008 Card update (optimistic concurrency)  (``app/cbl/COCRDUPC.cbl``)
* F-009 Transaction list (10 rows/page)       (``app/cbl/COTRN00C.cbl``)
* F-010 Transaction detail view               (``app/cbl/COTRN01C.cbl``)
* F-011 Transaction add (auto-ID + xref)      (``app/cbl/COTRN02C.cbl``)
* F-012 Bill payment (dual-write)             (``app/cbl/COBIL00C.cbl``)
* F-013 Batch transaction posting (POSTTRAN)  (``app/cbl/CBTRN02C.cbl``)
* F-014 Batch interest calculation (INTCALC)  (``app/cbl/CBACT04C.cbl``)
* F-015 Batch COMBTRAN merge/sort             (``app/jcl/COMBTRAN.jcl``)
* F-016 Statement generation (CREASTMT)       (``app/cbl/CBSTM03A.CBL``)
* F-017 Transaction report (TRANREPT)         (``app/cbl/CBTRN03C.cbl``)
* F-018 User list                             (``app/cbl/COUSR00C.cbl``)
* F-019 User add (BCrypt hashing)             (``app/cbl/COUSR01C.cbl``)
* F-020 User update                           (``app/cbl/COUSR02C.cbl``)
* F-021 User delete                           (``app/cbl/COUSR03C.cbl``)
* F-022 Report submission (TDQ -> SQS FIFO)   (``app/cbl/CORPT00C.cbl``)

Target coverage: at least 80% line coverage (enforced by
``--cov-fail-under=80`` in ``pyproject.toml``) with parity against the
81.5% coverage of the originating mainframe test harness.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``tests/`` tree)
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.2 — Testing Requirements (pytest, Testcontainers, moto)
"""
