# ============================================================================
# CardDemo — End-to-End Test Package Init (Mainframe-to-Cloud migration)
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
"""End-to-end tests for the CardDemo batch pipeline.

This package contains tests that verify the complete 5-stage batch pipeline:
Stage 1 (POSTTRAN) -> Stage 2 (INTCALC) -> Stage 3 (COMBTRAN)
-> Parallel(Stage 4a (CREASTMT), Stage 4b (TRANREPT))

Tests validate:

* Inter-stage data dependencies through Aurora PostgreSQL tables
* Stage failure halting downstream stages (JCL COND parameter behavior)
* Parallel execution of Stages 4a and 4b
* Step Functions state machine definition correctness
* Financial precision with ``Decimal`` throughout the pipeline
* Transaction validation cascade (reject codes 100-109)
* Interest calculation formula: ``(TRAN-CAT-BAL x DIS-INT-RATE) / 1200``
* DEFAULT/ZEROAPR disclosure group fallback logic

Source: Batch COBOL programs (``app/cbl/CB*.cbl``) + JCL jobs
(``app/jcl/*.jcl``) -- Mainframe-to-Cloud migration.

This package is the marker for the **end-to-end** tier of the CardDemo
test pyramid. Like its parent ``tests`` package and its siblings
``tests.unit`` and ``tests.integration``, it is intentionally minimal:

* **No imports of test modules** -- pytest auto-discovers ``test_*.py``
  files via the project's ``[tool.pytest.ini_options]`` configuration in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* **No shared fixtures or helpers** -- fixtures live in
  ``tests/conftest.py`` (session-wide) and subpackage-local
  ``conftest.py`` files so that pytest's hierarchical fixture resolution
  applies and fixtures stay close to the tests that use them. E2E
  fixtures (Step Functions execution context, Testcontainers PostgreSQL
  for pipeline data, moto AWS mocks for S3 / SQS, local ``SparkSession``
  for PySpark job execution) are typically ``session``-scoped to amortize
  container startup and Spark JVM warm-up costs across all e2e tests.
* **No executable side effects** -- importing ``tests.e2e`` must be a
  no-op so that tooling (coverage reporters, IDE test runners, CI
  linters, mypy) can safely introspect the package without starting
  Docker containers, opening PostgreSQL connections, constructing AWS
  clients, or spinning up a Spark JVM.

End-to-End Test Scope
---------------------
E2E tests in this package exercise the **full batch pipeline flow** from
end to end -- the defining distinction from ``tests.unit`` (isolated
units with mocks) and ``tests.integration`` (individual components
against real dependencies):

* **Pipeline orchestration** -- Validates the AWS Step Functions state
  machine definition (``src/batch/pipeline/step_functions_definition.json``)
  that replaces the JCL COND-parameter chaining from
  ``app/jcl/POSTTRAN.jcl``, ``app/jcl/INTCALC.jcl``,
  ``app/jcl/COMBTRAN.jcl``, ``app/jcl/CREASTMT.jcl``, and
  ``app/jcl/TRANREPT.jcl``. Tests assert:

  - Sequential execution of Stages 1 -> 2 -> 3.
  - Parallel execution of Stages 4a (CREASTMT) and 4b (TRANREPT)
    after Stage 3 completes successfully.
  - Downstream stages are halted if any upstream stage fails
    (matching JCL ``COND=(0,NE)`` / ``COND=(4,LT)`` semantics).
  - Retry and error-handling transitions in the state machine.

* **Inter-stage data handoff** -- Validates that each stage's output
  tables in Aurora PostgreSQL are correctly consumed by the next stage,
  replacing the shared VSAM dataset handoff pattern from the mainframe.
  For example, Stage 1 (POSTTRAN, ``app/cbl/CBTRN02C.cbl``) writes to
  ``transaction`` and ``transaction_category_balance`` tables, which are
  then read by Stage 2 (INTCALC, ``app/cbl/CBACT04C.cbl``) for interest
  computation, and so on through Stage 4.

* **Business-rule preservation** -- Validates that the COBOL business
  logic is faithfully translated to PySpark without simplification:

  - The 4-stage transaction validation cascade in POSTTRAN producing
    reject codes 100-109 (card not found, account mismatch, credit
    limit exceeded, expired card, etc.) per ``app/cbl/CBTRN02C.cbl``.
  - The interest calculation formula
    ``(TRAN-CAT-BAL x DIS-INT-RATE) / 1200`` preserved exactly (not
    algebraically simplified) per ``app/cbl/CBACT04C.cbl``.
  - The DEFAULT / ZEROAPR disclosure-group fallback logic when a
    specific account-group disclosure record is not found, per
    ``app/cpy/CVTRA02Y.cpy`` and the discgrp.txt fixture data.
  - The DFSORT + REPRO merge / sort semantics of COMBTRAN translated
    to a pure PySpark union + orderBy job per ``app/jcl/COMBTRAN.jcl``.
  - Statement generation producing both text and HTML output with a
    4-entity join per ``app/cbl/CBSTM03A.CBL`` and
    ``app/cbl/CBSTM03B.CBL``.
  - Three-level totals (account / type / grand) in the transaction
    report per ``app/cbl/CBTRN03C.cbl``.

* **Financial precision** -- Every stage writes monetary values as
  ``Decimal`` (preserving COBOL ``PIC S9(n)V99`` semantics) into
  ``NUMERIC(15,2)`` PostgreSQL columns. E2E tests assert exact
  ``Decimal`` equality (no floating-point epsilon tolerance) on
  balances, interest amounts, posting amounts, and statement totals
  read back from the database after each stage completes.

* **GDG / S3 outputs** -- Statement files (text + HTML) and transaction
  reports are written to versioned S3 paths via ``moto``-mocked S3
  (replacing the GDG generations from ``app/jcl/DEFGDGB.jcl``,
  ``app/jcl/REPTFILE.jcl``, ``app/jcl/DALYREJS.jcl``). E2E tests
  validate the correct number, naming convention, and content of
  generated S3 objects.

Planned Test Modules
--------------------
Per AAP §0.4.1 (Refactored Structure Planning) and §0.5.1 (File-by-File
Transformation Plan), the ``tests.e2e`` package is populated by
subsequent test-authoring passes with modules such as:

* ``tests.e2e.test_batch_pipeline``
    The canonical end-to-end test module. Exercises the full
    S1 -> S2 -> S3 -> Parallel(S4a, S4b) flow against:

    - A real PostgreSQL 16 instance (Testcontainers) pre-loaded with
      the Flyway migrations (``db/migrations/V1__schema.sql``,
      ``V2__indexes.sql``, ``V3__seed_data.sql``) so that reference
      data (transaction types, categories, disclosure groups) is
      available.
    - moto-mocked S3, SQS FIFO, and Secrets Manager so the batch jobs'
      AWS integrations execute without live AWS credentials.
    - A local ``SparkSession`` (``master("local[*]")``) driving the
      five PySpark job modules under ``src/batch/jobs/`` in the same
      order and with the same data dependencies as production.
    - Assertions on the post-pipeline database state: transactions
      inserted with correct statuses, reject rows for invalid daily
      transactions with the expected reject codes, balances updated
      with interest amounts, statement and report S3 objects
      generated with the expected record counts and totals.

Additional modules (pipeline failure handling, parallel-stage
verification, Step Functions state-machine validation, large-volume
performance smoke tests) may be added as the test suite matures.

Markers
-------
All tests in this package should be decorated with ``@pytest.mark.e2e``
(registered in ``pyproject.toml`` under ``[tool.pytest.ini_options]
markers``) so that selective execution is possible:

* Run only e2e tests:                  ``pytest -m e2e``
* Run everything except e2e:           ``pytest -m "not e2e"``
* Run integration + e2e combined:      ``pytest -m "integration or e2e"``

Because end-to-end pipeline tests are inherently slow (container
startup, Spark JVM warm-up, multi-stage execution, database round
trips), tests in this package should also be marked
``@pytest.mark.slow`` so that fast-feedback CI jobs can skip them via
``pytest -m "not slow"``.

Prerequisites
-------------
E2E tests require the following to be available on the host running
``pytest``:

* **Docker** -- Needed by ``testcontainers`` to start a PostgreSQL 16
  container (matching Aurora PostgreSQL compatibility). The
  ``Docker 28.5.2`` version validated during environment setup is
  sufficient.
* **Java 17** -- Required by PySpark 3.5.6 (the exact version pinned
  for AWS Glue 5.1 parity) for the local ``SparkSession`` that drives
  the five batch jobs. ``JAVA_HOME`` must point at an OpenJDK 17
  installation.
* **Python 3.11** -- Matches the AWS Glue 5.1 runtime and the ECS
  container image.
* **Network access** to Docker Hub on first run to pull the
  ``postgres:16-alpine`` image. Subsequent runs use the locally
  cached image.

No AWS credentials are required: all AWS services are mocked via
``moto`` (S3 for statement / report output, SQS FIFO for report
submission queue, Secrets Manager for database credentials,
Step Functions for pipeline orchestration validation). No live
network access to AWS is ever attempted.

Feature Coverage
----------------
Per AAP §0.2.2 (Batch Program Classification) and §0.7.2 (Testing
Requirements), the ``tests.e2e`` package covers the following features
at the end-to-end pipeline level:

* F-013 Batch transaction posting (POSTTRAN) -- ``app/cbl/CBTRN02C.cbl``,
  ``app/jcl/POSTTRAN.jcl``
* F-014 Batch interest calculation (INTCALC) -- ``app/cbl/CBACT04C.cbl``,
  ``app/jcl/INTCALC.jcl``
* F-015 Batch COMBTRAN merge / sort           -- ``app/jcl/COMBTRAN.jcl``
* F-016 Statement generation (CREASTMT)       -- ``app/cbl/CBSTM03A.CBL``,
  ``app/cbl/CBSTM03B.CBL``, ``app/jcl/CREASTMT.jcl``
* F-017 Transaction report (TRANREPT)         -- ``app/cbl/CBTRN03C.cbl``,
  ``app/jcl/TRANREPT.jcl``

Online-transaction features (F-001 through F-012 and F-018 through
F-022) are covered at lower test tiers; their end-to-end contract with
the batch pipeline is through the shared Aurora PostgreSQL tables
validated here.

Coverage Contribution
---------------------
Per AAP §0.7.2 (Testing Requirements) and the Mainframe-to-Cloud parity
contract, the combined unit + integration + e2e suites must achieve
**at least 80% line coverage** (enforced by ``--cov-fail-under=80`` in
``pyproject.toml``), matching parity with the 81.5% coverage of the
originating mainframe test harness. E2E tests contribute coverage
for code paths that are only exercised when multiple stages run
together: inter-stage schema contracts, Step Functions transition
logic, parallel-branch synchronization, and cross-stage error
propagation.

See Also
--------
``tests.__init__``             -- Parent test-suite package marker and
                                  full feature catalog.
``tests.unit.__init__``        -- Sibling unit-test package marker.
``tests.integration.__init__`` -- Sibling integration-test package
                                  marker.
``tests.conftest``             -- Session-wide pytest fixtures
                                  (DB session, FastAPI TestClient,
                                  Spark session, mocked AWS services).
``src/batch/jobs/``            -- PySpark Glue job modules under test.
``src/batch/pipeline/``        -- Step Functions state machine
                                  definition under test.
``db/migrations/``             -- Flyway-style schema / index / seed
                                  SQL applied by e2e-test fixtures.
AAP §0.2.2                     -- Batch Program Classification
                                  (10 programs -> PySpark on AWS Glue).
AAP §0.4.1                     -- Refactored Structure Planning
                                  (``tests/e2e/`` subtree).
AAP §0.5.1                     -- File-by-File Transformation Plan.
AAP §0.7.2                     -- Testing Requirements (pytest,
                                  Testcontainers, moto, coverage
                                  targets).
AAP §0.7.3                     -- Batch Pipeline Sequencing
                                  (S1 -> S2 -> S3 -> S4a || S4b).
"""
