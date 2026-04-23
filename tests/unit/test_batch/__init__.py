# ============================================================================
# CardDemo — Batch Unit Test Package Init (Mainframe-to-Cloud migration)
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
"""Unit tests for PySpark batch jobs (``src/batch/jobs/``).

Tests validate behavioral parity of PySpark Glue jobs with their original
COBOL batch programs from the CardDemo mainframe application. The 11 test
modules in this package exercise the 10 batch COBOL programs
(``app/cbl/CB*.cbl`` / ``app/cbl/CB*.CBL``) plus the DFSORT+REPRO-only
merge stage (``app/jcl/COMBTRAN.jcl``) after their conversion to PySpark
3.5.6 / AWS Glue 5.1.

Pipeline Stage Tests
--------------------
* ``test_posttran_job``           — Stage 1 — Transaction posting
                                    (``CBTRN02C.cbl`` + ``POSTTRAN.jcl``).
* ``test_intcalc_job``            — Stage 2 — Interest calculation
                                    (``CBACT04C.cbl`` + ``INTCALC.jcl``).
* ``test_combtran_job``           — Stage 3 — Merge / sort transactions
                                    (``COMBTRAN.jcl`` — DFSORT + REPRO,
                                    no COBOL source).
* ``test_creastmt_job``           — Stage 4a — Statement generation
                                    (``CBSTM03A.CBL`` + ``CBSTM03B.CBL``
                                    + ``CREASTMT.jcl``).
* ``test_tranrept_job``           — Stage 4b — Transaction reporting
                                    (``CBTRN03C.cbl`` + ``TRANREPT.jcl``).

Utility Tests
-------------
* ``test_prtcatbl_job``           — Category-balance print
                                    (``PRTCATBL.jcl`` — no COBOL source,
                                    pure IDCAMS print).
* ``test_daily_tran_driver_job``  — Daily transaction driver
                                    (``CBTRN01C.cbl``).

Diagnostic Reader Tests
-----------------------
* ``test_read_account_job``       — Account reader (``CBACT01C.cbl`` +
                                    ``READACCT.jcl``).
* ``test_read_card_job``          — Card reader (``CBACT02C.cbl`` +
                                    ``READCARD.jcl``).
* ``test_read_customer_job``      — Customer reader (``CBCUS01C.cbl`` +
                                    ``READCUST.jcl``).
* ``test_read_xref_job``          — Cross-reference reader
                                    (``CBACT03C.cbl`` + ``READXREF.jcl``).

Source: Batch COBOL programs (``app/cbl/CB*.cbl``) + JCL jobs
(``app/jcl/*.jcl``) — Mainframe-to-Cloud migration.

This package is the marker for the **batch** subtree of the unit-test
pyramid. Like its parent packages (``tests``, ``tests.unit``) and its
siblings (``tests.unit.test_models``, ``tests.unit.test_services``,
``tests.unit.test_routers``), it is intentionally minimal:

* **No imports of test modules** — pytest auto-discovers ``test_*.py``
  files via the project's ``[tool.pytest.ini_options]`` configuration in
  ``pyproject.toml`` (``testpaths = ["tests"]``, ``python_files =
  ["test_*.py"]``).
* **No shared fixtures or helpers** — fixtures live in
  ``tests/conftest.py`` (session-wide) and subpackage-local
  ``conftest.py`` files so that pytest's hierarchical fixture resolution
  applies and fixtures stay close to the tests that use them. A local
  ``SparkSession`` fixture is expected in ``tests/conftest.py`` or in a
  ``tests/unit/test_batch/conftest.py`` for batch-specific setup.
* **No executable side effects** — importing
  ``tests.unit.test_batch`` must be a no-op so that tooling (coverage
  reporters, IDE test runners, CI linters, mypy) can safely introspect
  the package without triggering database connections, AWS client
  construction, or Spark context creation.

Job-to-COBOL Mapping
--------------------
Per AAP §0.5.1 (File-by-File Transformation Plan), the 11 PySpark job
scripts under ``src/batch/jobs/`` map one-to-one to the 10 batch COBOL
programs under ``app/cbl/CB*.cbl`` plus the JCL-only DFSORT + REPRO
merge stage. Each test module validates behavioral parity with the
original mainframe program:

===============================  =============================================  ====================================================================
Job (``src/batch/jobs/``)        Test Module (``tests/unit/test_batch/``)       COBOL / JCL Source
===============================  =============================================  ====================================================================
``posttran_job``                 ``test_posttran_job``                          ``app/cbl/CBTRN02C.cbl`` + ``app/jcl/POSTTRAN.jcl``
``intcalc_job``                  ``test_intcalc_job``                           ``app/cbl/CBACT04C.cbl`` + ``app/jcl/INTCALC.jcl``
``combtran_job``                 ``test_combtran_job``                          ``app/jcl/COMBTRAN.jcl`` (DFSORT + IDCAMS REPRO — no COBOL source)
``creastmt_job``                 ``test_creastmt_job``                          ``app/cbl/CBSTM03A.CBL`` + ``app/cbl/CBSTM03B.CBL`` + ``app/jcl/CREASTMT.jcl``
``tranrept_job``                 ``test_tranrept_job``                          ``app/cbl/CBTRN03C.cbl`` + ``app/jcl/TRANREPT.jcl``
``prtcatbl_job``                 ``test_prtcatbl_job``                          ``app/jcl/PRTCATBL.jcl`` (no COBOL source — pure IDCAMS print)
``daily_tran_driver_job``        ``test_daily_tran_driver_job``                 ``app/cbl/CBTRN01C.cbl``
``read_account_job``             ``test_read_account_job``                      ``app/cbl/CBACT01C.cbl`` + ``app/jcl/READACCT.jcl``
``read_card_job``                ``test_read_card_job``                         ``app/cbl/CBACT02C.cbl`` + ``app/jcl/READCARD.jcl``
``read_customer_job``            ``test_read_customer_job``                     ``app/cbl/CBCUS01C.cbl`` + ``app/jcl/READCUST.jcl``
``read_xref_job``                ``test_read_xref_job``                         ``app/cbl/CBACT03C.cbl`` + ``app/jcl/READXREF.jcl``
===============================  =============================================  ====================================================================

Per AAP §0.4.4 (Batch Layer — AWS Glue), each batch COBOL program
becomes one PySpark script in ``src/batch/jobs/`` running on AWS Glue
5.1 (Spark 3.5.6, Python 3.11). The COMBTRAN stage (Stage 3) — which
uses DFSORT + REPRO with no COBOL program — translates to a pure
PySpark merge/sort job.

Batch Pipeline Execution Order
------------------------------
The 5-stage batch pipeline preserves the original JCL execution order
exactly. A failure in any upstream stage halts downstream stages,
mirroring the JCL ``COND`` parameter semantics (AAP §0.7.2 "Batch
Pipeline Sequencing")::

    Stage 1 (POSTTRAN)  →  Stage 2 (INTCALC)  →  Stage 3 (COMBTRAN)
                                                          │
                                      ┌───────────────────┴───────────────────┐
                                      ▼                                       ▼
                            Stage 4a (CREASTMT)                     Stage 4b (TRANREPT)

End-to-end pipeline tests that exercise the full stage chain live in
the sibling package ``tests.e2e`` (``test_batch_pipeline.py``); unit
tests in this package exercise each stage *in isolation* with in-memory
PySpark DataFrames and mocked Aurora PostgreSQL / S3 / SQS dependencies.

Test Module Scope
-----------------
Each job is expected to have a corresponding ``test_<job>.py`` module
in this package. Batch-job unit tests are distinct from model tests
(``tests.unit.test_models``) in that they exercise **pure PySpark
transformation functions** rather than ORM invariants. Test modules
are expected to exercise the following dimensions:

* **Transformation purity** — the core stage function (e.g.,
  ``posttran_job.validate_transactions``,
  ``intcalc_job.calculate_interest``) accepts one or more input
  DataFrames and returns one or more output DataFrames without
  performing any I/O (Aurora reads/writes, S3 writes, SQS publishes).
  This purity discipline makes the transforms trivial to test with
  in-memory DataFrames constructed from Python literals.

* **Reject-code preservation** (F-013 POSTTRAN) — the 4-stage
  validation cascade in ``posttran_job`` must produce **exactly** the
  reject codes 100–109 from the original ``CBTRN02C.cbl`` validation
  logic:

  * 100 — Daily-transaction record layout invalid
  * 101 — Card cross-reference (XREF) not found
  * 102 — Account not found for the cross-referenced card
  * 103 — Transaction type code invalid
  * 104 — Transaction category code invalid
  * 105 — Transaction amount signed-decimal parse failure
  * 106 — Account credit-limit exceeded
  * 107 — Account cash-credit-limit exceeded
  * 108 — Transaction category balance record not found
  * 109 — Catch-all / dual-write transaction failure

  Tests assert that input rows crafted to trigger each condition
  produce the corresponding code on the reject DataFrame (``DALYREJS``
  equivalent) and do **not** produce output rows on the posted
  DataFrame.

* **Interest formula parity** (F-014 INTCALC) — the per-(account, type,
  category) interest formula
  ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` from ``CBACT04C.cbl`` must
  not be algebraically simplified (AAP §0.7.1 "Refactoring-Specific
  Rules"). Tests assert that
  ``intcalc_job.calculate_interest(balance, rate)`` returns
  ``(balance * rate) / 1200`` with :class:`decimal.Decimal` operands
  and ``ROUND_HALF_EVEN`` (banker's rounding) — never floating point.
  Representative test cases cover positive balance × positive rate,
  zero balance → zero interest, and the DEFAULT / ZEROAPR fallback
  below.

* **Disclosure-group fallback** (F-014 INTCALC) — when no
  disclosure-group record exists for the specific
  (``ACCT-GROUP-ID``, ``TRAN-TYPE-CD``, ``TRAN-CAT-CD``) combination,
  the ``intcalc_job`` cascades lookup to the DEFAULT group; if the
  account is under a zero-APR promotional program, the ZEROAPR
  fallback forces a 0.00% rate. Tests assert that missing-group lookups
  resolve to DEFAULT rates and that ZEROAPR overrides DEFAULT when
  present on the account.

* **Statement-generation parity** (F-016 CREASTMT) — the
  ``creastmt_job`` 4-entity join
  (Account ⋈ Customer ⋈ CardCrossReference ⋈ Transaction) from
  ``CBSTM03A.CBL`` / ``CBSTM03B.CBL`` must produce text and HTML
  statement outputs whose monetary totals match the sum of the
  month's posted transactions exactly. Tests assert statement totals,
  line formatting, and the plain-text / HTML branch equivalence.

* **3-level totals parity** (F-017 TRANREPT) — the ``tranrept_job``
  control-break logic from ``CBTRN03C.cbl`` must produce
  per-account, per-page, and grand-total subtotals identical to the
  COBOL output. Tests assert that totals roll up correctly and that
  the date-filter predicate only includes transactions within the
  specified window.

* **Merge / sort semantics** (F-015 COMBTRAN) — the
  ``combtran_job`` must union the posted day's transactions with the
  master transaction history, deduplicate by ``TRAN-ID``, and order
  by ``TRAN-ID`` ascending, reproducing the output of the original
  DFSORT + REPRO step. Tests assert that duplicate IDs are deduped,
  that ordering is stable across runs, and that no rows are lost.

* **Financial precision** — every monetary column (``tran_amt``,
  ``curr_bal``, ``credit_limit``, ``tran_cat_bal``, ``dis_int_rate``)
  round-trips through :class:`decimal.Decimal` with
  ``ROUND_HALF_EVEN`` (banker's rounding) — never ``float`` — matching
  the COBOL ``PIC S9(n)V99`` ``ROUNDED`` semantics of the original
  programs (AAP §0.7.1 "Financial Precision"). Assertions use
  ``Decimal("123.45") == row.amount`` semantics rather than ``float``
  equality.

* **Reader parity** — diagnostic reader jobs
  (``read_account_job``, ``read_card_job``, ``read_customer_job``,
  ``read_xref_job``) produce row counts and column layouts identical
  to their COBOL counterparts (``CBACT01C``, ``CBACT02C``,
  ``CBCUS01C``, ``CBACT03C``). Tests assert row counts against the
  seeded fixture data (50 accounts, 50 cards, 50 customers, 50
  cross-references per ``db/migrations/V3__seed_data.sql``).

* **Driver parity** (``test_daily_tran_driver_job``) — the
  ``daily_tran_driver_job`` must correctly parse fixed-width records
  from the inbound daily-transaction file (``app/data/ASCII/dailytran.txt``
  layout) into the ``daily_transactions`` staging table, preserving
  field boundaries from ``CBTRN01C.cbl``.

Test Isolation — Mocked Dependencies
------------------------------------
Unit tests in this package are **fast and isolated**. Per AAP §0.7.2:

* **Spark / Glue** — Tests use a local in-process
  :class:`pyspark.sql.SparkSession` (constructed via a
  ``spark_session`` fixture in ``tests/conftest.py``) with in-memory
  DataFrames built from Python literals or dict lists. No AWS Glue
  runtime is required. The ``awsglue.context.GlueContext`` is stubbed
  or wrapped by a thin adapter so tests can target transform functions
  directly. Job scripts that require a live ``GlueContext`` at
  top-level are refactored to split I/O (Glue-specific) from
  transformation (pure PySpark) so the transformation can be unit-tested
  independently.

* **Database** — Tests do **not** connect to Aurora PostgreSQL. JDBC
  reads/writes are mocked at the transformation boundary by injecting
  in-memory DataFrames for inputs and asserting on returned DataFrames
  for outputs. Real PostgreSQL integration tests live in
  ``tests.integration``.

* **AWS services** — Tests use ``moto.mock_aws`` to mock S3 (replaces
  GDG from ``app/jcl/DEFGDGB.jcl``, used by ``creastmt_job`` and
  ``tranrept_job`` for statement/report output), Secrets Manager
  (replaces RACF credentials used by
  ``src/batch/common/db_connector.py``), and CloudWatch for metrics
  emission. No live AWS calls.

* **File I/O** — Any fixed-width file parsing (e.g.,
  ``CBTRN01C.cbl``'s ``DAILYTRAN-FILE`` input) uses in-memory bytes
  or a ``tmp_path`` fixture; tests never depend on absolute filesystem
  paths or external data lakes.

Markers
-------
All tests in this package should be decorated with ``@pytest.mark.unit``
(registered in ``pyproject.toml`` under
``[tool.pytest.ini_options] markers``) so that selective execution is
possible:

* Run only batch unit tests:      ``pytest tests/unit/test_batch -m unit``
* Run all unit tests:              ``pytest -m unit``
* Skip slow batch tests:           ``pytest -m "unit and not slow"``

Tests that construct a :class:`pyspark.sql.SparkSession` and exercise
non-trivial DataFrame operations may be additionally decorated with
``@pytest.mark.slow`` if their execution time exceeds a few seconds
(Spark session cold-start and JVM startup dominate wall time for the
first test in a process).

Feature Coverage
----------------
Per AAP §0.7.2 (Testing Requirements), every batch-layer migrated
feature maps to at least one test module in this package:

* F-013 Batch transaction posting (POSTTRAN)   -> ``test_posttran_job``
* F-014 Batch interest calculation (INTCALC)   -> ``test_intcalc_job``
* F-015 Batch COMBTRAN merge / sort            -> ``test_combtran_job``
* F-016 Statement generation (CREASTMT)        -> ``test_creastmt_job``
* F-017 Transaction report (TRANREPT)          -> ``test_tranrept_job``

The remaining 6 test modules
(``test_prtcatbl_job``, ``test_daily_tran_driver_job``,
``test_read_account_job``, ``test_read_card_job``,
``test_read_customer_job``, ``test_read_xref_job``) exercise the
pre-pipeline and utility / diagnostic jobs that have no dedicated
feature ID but are nevertheless in scope per AAP §0.3.1 (Exhaustively
In Scope → Batch Programs).

All online-facing features (F-001 through F-012 and F-018 through
F-022) are covered in sibling packages (``tests.unit.test_services``
and ``tests.unit.test_routers``), not here.

Coverage Target
---------------
Per AAP §0.7.2 and the Mainframe-to-Cloud parity contract, the combined
unit + integration + e2e suites must achieve **at least 80% line
coverage** (enforced by ``--cov-fail-under=80`` in ``pyproject.toml``),
matching parity with the 81.5% coverage of the originating mainframe
test harness. Batch-layer unit tests are expected to contribute the
majority of this coverage for the PySpark job modules
(``src/batch/jobs/``) given their direct coverage of COBOL
PROCEDURE DIVISION logic (validation cascades, interest arithmetic,
control-break totals) and their fast execution against in-memory
DataFrames.

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
                                   batch jobs read from / write to
                                   via JDBC.
``tests.unit.test_services``     — Sibling unit-test package covering
                                   the online-API service layer
                                   (F-001 and F-004 through F-012,
                                   F-018 through F-022).
``tests.unit.test_routers``      — Sibling unit-test package covering
                                   the online-API router layer
                                   (F-001 through F-012, F-018
                                   through F-022).
``tests.e2e``                    — End-to-end pipeline tests exercising
                                   the full 5-stage flow
                                   (POSTTRAN -> INTCALC -> COMBTRAN
                                   -> (CREASTMT || TRANREPT)) against
                                   a live PostgreSQL via Testcontainers.
``tests.conftest``               — Session-wide pytest fixtures
                                   (``spark_session``, DB session,
                                   FastAPI TestClient, mocked AWS
                                   services, factory-boy factories
                                   for the 11 entities).
``src.batch.jobs``               — The 11 PySpark job scripts under
                                   test (posttran, intcalc, combtran,
                                   creastmt, tranrept, prtcatbl,
                                   daily_tran_driver, read_account,
                                   read_card, read_customer, read_xref).
``src.batch.common``             — Shared infrastructure
                                   (``glue_context.py``,
                                   ``db_connector.py``, ``s3_utils.py``)
                                   that every job imports.
``src.batch.pipeline``           — AWS Step Functions state-machine
                                   definition
                                   (``step_functions_definition.json``).
AAP §0.2.2                       — Batch Program Classification
                                   (10 COBOL programs → PySpark).
AAP §0.4.1                       — Refactored Structure Planning
                                   (``tests/unit/test_batch/`` subtree).
AAP §0.4.4                       — Batch Layer architectural decisions
                                   (AWS Glue 5.1, Spark 3.5.6,
                                   Python 3.11).
AAP §0.5.1                       — File-by-File Transformation Plan
                                   (batch-job-to-COBOL mapping).
AAP §0.7.1                       — Refactoring-Specific Rules
                                   (preserve business logic exactly:
                                   4-stage validation cascade, interest
                                   formula not algebraically simplified,
                                   DEFAULT/ZEROAPR fallback, financial
                                   precision via Decimal).
AAP §0.7.2                       — Testing Requirements (pytest as
                                   primary test framework, moto for
                                   AWS mocking, Testcontainers for
                                   PostgreSQL, parity with 81.5%
                                   originating coverage) and Batch
                                   Pipeline Sequencing.
"""
