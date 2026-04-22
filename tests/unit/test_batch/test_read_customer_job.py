# ============================================================================
# CardDemo — Unit tests for read_customer_job (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/CBCUS01C.cbl     — Customer diagnostic reader. Opens the
#                                CUSTFILE VSAM KSDS cluster (INDEXED
#                                SEQUENTIAL ACCESS), PERFORMs
#                                UNTIL END-OF-FILE = 'Y' issuing
#                                READ CUSTFILE-FILE INTO CUSTOMER-RECORD
#                                and emitting DISPLAY CUSTOMER-RECORD on
#                                each iteration. Bookended by the
#                                DISPLAY 'START OF EXECUTION OF PROGRAM
#                                CBCUS01C' and DISPLAY 'END OF EXECUTION
#                                OF PROGRAM CBCUS01C' statements at lines
#                                71 and 85 of the source.
#   * app/jcl/READCUST.jcl     — JCL job card (``//READCUST JOB ...``) +
#                                EXEC PGM=CBCUS01C in STEP05 with
#                                STEPLIB DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.LOADLIB and
#                                CUSTFILE DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
#                                plus SYSOUT + SYSPRINT = SYSOUT=*. These
#                                collapse into a single init_glue() +
#                                read_table(spark, "customers") invocation
#                                pair in the target PySpark module.
#   * app/cpy/CVCUS01Y.cpy     — CUSTOMER-RECORD layout (RECLN 500):
#                                CUST-ID                   PIC 9(09) —
#                                  9-digit customer id primary key,
#                                CUST-FIRST-NAME           PIC X(25),
#                                CUST-MIDDLE-NAME          PIC X(25),
#                                CUST-LAST-NAME            PIC X(25),
#                                CUST-ADDR-LINE-1          PIC X(50),
#                                CUST-ADDR-LINE-2          PIC X(50),
#                                CUST-ADDR-LINE-3          PIC X(50),
#                                CUST-ADDR-STATE-CD        PIC X(02),
#                                CUST-ADDR-COUNTRY-CD      PIC X(03),
#                                CUST-ADDR-ZIP             PIC X(10),
#                                CUST-PHONE-NUM-1          PIC X(15),
#                                CUST-PHONE-NUM-2          PIC X(15),
#                                CUST-SSN                  PIC 9(09),
#                                CUST-GOVT-ISSUED-ID       PIC X(20),
#                                CUST-DOB-YYYY-MM-DD       PIC X(10),
#                                CUST-EFT-ACCOUNT-ID       PIC X(10),
#                                CUST-PRI-CARD-HOLDER-IND  PIC X(01),
#                                CUST-FICO-CREDIT-SCORE    PIC 9(03),
#                                FILLER                    PIC X(168) —
#                                  VSAM slack padding dropped in Aurora.
# ----------------------------------------------------------------------------
# Target module under test: src/batch/jobs/read_customer_job.py.
# The PySpark Glue job replaces CBCUS01C.cbl + READCUST.jcl, collapsing
# the COBOL OPEN / READ-UNTIL-EOF / CLOSE sequence into a single
# read_table(spark, "customers") + cache + collect pipeline, and the
# terminal GOBACK + JCL MAXCC=0 success signal into a commit_job(job)
# call that notifies Step Functions of stage success.
#
# These tests verify behavioral parity with CBCUS01C.cbl by exercising
# the main() entry point with mocked Glue / JDBC dependencies — i.e.,
# they validate the *behavior* (single table read, per-record iteration,
# COBOL DISPLAY message preservation, commit signal) without requiring
# an actual AWS Glue runtime, a live Aurora PostgreSQL cluster, or a
# full local SparkSession. The six test cases map directly to the AAP
# agent-prompt's Phase 2 check-list for this file:
#
#   Test case                     | Verifies (COBOL source mapping)
#   ----------------------------------------------------------------------
#   test_reads_customers_table    | OPEN INPUT CUSTFILE-FILE
#                                 | (paragraph 0000-CUSTFILE-OPEN, lines
#                                 | 118-134 of CBCUS01C.cbl) + JCL
#                                 | //CUSTFILE DD DISP=SHR (line 9 of
#                                 | READCUST.jcl) → read_table(spark,
#                                 | "customers").
#   test_logs_start_message       | DISPLAY 'START OF EXECUTION OF
#                                 | PROGRAM CBCUS01C' (line 71 of
#                                 | CBCUS01C.cbl) preserved byte-exact
#                                 | in the CloudWatch log stream.
#   test_logs_end_message         | DISPLAY 'END OF EXECUTION OF
#                                 | PROGRAM CBCUS01C' (line 85 of
#                                 | CBCUS01C.cbl) preserved byte-exact
#                                 | in the CloudWatch log stream.
#   test_logs_record_count        | The PERFORM UNTIL END-OF-FILE loop
#                                 | (lines 74-81) iterates every row of
#                                 | the CUSTDATA cluster; translated to
#                                 | PySpark as a .count() call whose
#                                 | value is surfaced in a dedicated log
#                                 | line for operator verification.
#   test_iterates_all_records     | DISPLAY CUSTOMER-RECORD (line 78 and
#                                 | line 96) inside the 1000-CUSTFILE-
#                                 | GET-NEXT read loop → one
#                                 | logger.info() call per row
#                                 | materialised via DataFrame.collect().
#   test_commit_job_called        | Terminal GOBACK (line 87) + JCL
#                                 | MAXCC=0 (READCUST step completion)
#                                 | → commit_job(job) signalling Step
#                                 | Functions success.
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
"""Unit tests for :mod:`src.batch.jobs.read_customer_job`.

Validates behavioral parity with the original COBOL diagnostic program
``app/cbl/CBCUS01C.cbl`` plus its launcher ``app/jcl/READCUST.jcl``.
CBCUS01C is a *diagnostic / utility* batch program — it opens the
CUSTDATA VSAM KSDS cluster, reads every record sequentially, and
DISPLAYs each one to SYSOUT. It performs no data modification and has
no downstream dependencies: its sole purpose is to let an operator
verify the current contents of the customer master file after a data
migration or before launching the production batch pipeline.

COBOL -> Python Verification Surface
------------------------------------
==================================================  ==========================================
COBOL paragraph / statement                         Python test (this module)
==================================================  ==========================================
``OPEN INPUT CUSTFILE-FILE`` L120                   ``test_reads_customers_table``
``READ CUSTFILE-FILE INTO ...`` L93                 ``test_iterates_all_records``
``DISPLAY CUSTOMER-RECORD`` L78, L96                ``test_iterates_all_records``
``PERFORM UNTIL END-OF-FILE = 'Y'`` L74-81          ``test_logs_record_count``
``DISPLAY 'START OF EXECUTION ...CBCUS01C'`` L71    ``test_logs_start_message``
``DISPLAY 'END OF EXECUTION ...CBCUS01C'`` L85      ``test_logs_end_message``
``GOBACK`` L87 + JCL MAXCC=0                        ``test_commit_job_called``
==================================================  ==========================================

Mocking Strategy
----------------
The target module ``src.batch.jobs.read_customer_job`` imports its three
runtime dependencies at module-load time via::

    from src.batch.common.db_connector import read_table
    from src.batch.common.glue_context import commit_job, init_glue

Because the ``from ... import ...`` form creates new name bindings in
the *importing* module's namespace, every :func:`unittest.mock.patch`
call MUST target the ``read_customer_job`` namespace — NOT the
originating ``glue_context`` / ``db_connector`` modules. Patching at
the source module would rebind the name in the wrong namespace and
the mock would never be triggered. The ``_PATCH_*`` constants below
centralize these exact patch targets.

* ``init_glue`` is patched to return a ``(spark, None, job, args)``
  tuple without actually provisioning a SparkSession, GlueContext, or
  Glue Job object. The second and third elements match the target
  module's local-development fallback (``_GLUE_AVAILABLE=False``).
* ``read_table`` is patched to return a chainable :class:`MagicMock`
  DataFrame stand-in. The mock supports the full PySpark chain used by
  the target module (``cache()``, ``count()``, ``collect()``,
  ``unpersist()``) with ``cache().return_value = self`` so fluent-style
  chained calls share one tracked mock instance.
* ``commit_job`` is patched to allow assertion of invocation without
  touching a real Glue Job object.

Log Capture
-----------
pytest's ``caplog`` fixture integrates transparently with stdlib
``logging``. Each test that asserts on log messages registers a
capture handler at INFO level (the severity at which the target
module's ``logger.info(...)`` calls are emitted) and then inspects
``caplog.messages`` (the post-formatting message bodies) or
``caplog.records`` (the raw :class:`logging.LogRecord` instances).
Substring matching is used rather than byte-exact equality so the
structured-JSON envelope installed by ``init_glue._setup_logging`` in
production does not affect test pass/fail.

Test Isolation
--------------
Every test is hermetic — no filesystem I/O, no network I/O, no
database I/O, no AWS SDK calls, no SparkContext, no JVM. The full
suite runs in under a second on a laptop and is safe to execute in
the GitHub Actions CI pipeline defined in ``.github/workflows/ci.yml``.

See Also
--------
* AAP §0.2.2 — Batch Program Classification (CBCUS01C listed as a
  diagnostic reader utility alongside CBACT01C, CBACT02C, CBACT03C).
* AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue).
* AAP §0.5.1 — File-by-File Transformation Plan (read_customer_job entry).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve functionality
  exactly as-is; no algebraic simplification; minimal change).
* AAP §0.7.2 — Testing Requirements (pytest, moto, unittest.mock).
"""

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``logging``         — pytest ``caplog`` fixture integrates transparently
#                       with stdlib logging; we configure capture level
#                       via ``caplog.at_level(logging.INFO)`` so the
#                       module under test's ``logger.info(...)`` calls
#                       are captured as :class:`logging.LogRecord`
#                       instances. INFO is the level at which both the
#                       COBOL bookend messages and every
#                       per-CUSTOMER-RECORD DISPLAY equivalent are
#                       emitted.
# ``patch``, ``MagicMock``, ``call`` — :mod:`unittest.mock` primitives.
#                       * ``patch`` is used as a decorator on every test
#                         to replace ``init_glue`` / ``read_table`` /
#                         ``commit_job`` with mocks in the
#                         ``read_customer_job`` module's own namespace.
#                       * ``MagicMock`` creates the chainable DataFrame
#                         stand-ins returned by the mocked
#                         ``read_table``, plus the SparkSession / Glue
#                         Job object tuples consumed by main().
#                       * ``call`` is imported to make positional-vs-
#                         keyword argument matching explicit and
#                         grep-able in the test body (e.g.,
#                         ``call(mock_spark, "customers")``).
# ----------------------------------------------------------------------------
import logging
from unittest.mock import MagicMock, call, patch

# ----------------------------------------------------------------------------
# Third-party imports — pytest 8.x test framework.
# ----------------------------------------------------------------------------
# pytest is loaded at test-discovery time by the project's
# pyproject.toml ``[tool.pytest.ini_options]`` configuration. The
# ``unit`` marker is registered in pyproject.toml and applied to
# every test in this module so ``pytest -m unit`` runs only the
# fast, hermetic, no-I/O suite. The ``caplog`` fixture provided by
# pytest is used in three of the six tests to capture log output.
# ----------------------------------------------------------------------------
import pytest

# ----------------------------------------------------------------------------
# First-party imports — module under test.
# ----------------------------------------------------------------------------
# ``main`` is the PySpark Glue job entry point that replaces CBCUS01C's
# PROCEDURE DIVISION paragraph-set. Calling ``main()`` under patched
# dependencies exercises the full CBCUS01C equivalent flow:
#
#   init_glue    (replaces JCL JOB + EXEC PGM=CBCUS01C + STEPLIB)
#     → DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
#     → read_table(spark, "customers")
#                (replaces OPEN INPUT CUSTFILE-FILE + READCUST.jcl
#                 //CUSTFILE DD DISP=SHR,
#                 DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS)
#     → df.cache() + df.count()
#                (single count-action materialisation of the lazy
#                 DataFrame; replaces the per-record READ + EOF
#                 check in paragraph 1000-CUSTFILE-GET-NEXT)
#     → for row in df.collect(): logger.info("CUSTOMER-RECORD: ...")
#                (replaces DISPLAY CUSTOMER-RECORD inside the
#                 PERFORM UNTIL END-OF-FILE loop, lines 74-81)
#     → df.unpersist()
#                (replaces CLOSE CUSTFILE-FILE in paragraph
#                 9000-CUSTFILE-CLOSE, lines 136-152)
#     → DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'
#     → commit_job  (replaces GOBACK + JCL MAXCC=0 at line 87)
#
# Only ``main`` is imported — the module's private constants
# (``_COBOL_START_MSG``, ``_COBOL_END_MSG``, ``_JOB_NAME``,
# ``_TABLE_NAME``) are intentionally NOT imported. The tests
# independently declare the expected string values below so that any
# future drift in the target module's constants is caught by the
# test suite rather than masked by a shared import. This discipline
# is precisely the point of behavioral-parity testing for the
# mainframe-to-cloud migration (AAP §0.7.1: "Preserve all existing
# functionality exactly as-is").
# ----------------------------------------------------------------------------
from src.batch.jobs.read_customer_job import main

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text constants from ``app/cbl/CBCUS01C.cbl``.
# These mirror the target module's ``_COBOL_START_MSG`` /
# ``_COBOL_END_MSG`` private constants — duplicated here rather than
# imported so the tests independently enforce the byte-exact string
# and would FAIL if the target module ever drifted from the COBOL
# source. This enforcement is precisely the point of behavioral-
# parity testing for the mainframe-to-cloud migration (AAP §0.7.1:
# "Preserve all existing functionality exactly as-is").
#
# Line references are to ``app/cbl/CBCUS01C.cbl`` as committed:
#   * Line 71:   DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
#   * Line 85:   DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.
# ----------------------------------------------------------------------------
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBCUS01C"
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBCUS01C"

# ----------------------------------------------------------------------------
# Canonical PostgreSQL table name for the CUSTDATA VSAM cluster. Maps
# the JCL DD statement ``//CUSTFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`` (READCUST.jcl lines 9-10)
# to the Aurora PostgreSQL table as defined in
# ``db/migrations/V1__schema.sql`` and canonicalized by
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["CUSTDATA"]``.
# Declared here as a module-level constant so the single-table-read
# assertion in ``test_reads_customers_table`` is auditable.
# ----------------------------------------------------------------------------
_EXPECTED_TABLE_NAME: str = "customers"

# ----------------------------------------------------------------------------
# Canonical Glue job name for the CUSTDATA diagnostic reader. The
# target module declares this as ``_JOB_NAME = "carddemo-read-customer"``;
# we duplicate here rather than import so any drift in the naming
# convention (from the ``carddemo-<job>`` pattern documented in
# AAP §0.5.1) is caught by this test suite.
# ----------------------------------------------------------------------------
_EXPECTED_JOB_NAME: str = "carddemo-read-customer"

# ----------------------------------------------------------------------------
# read_customer_job-namespace patch targets — the module-under-test
# re-binds ``init_glue`` / ``read_table`` / ``commit_job`` via
# ``from src.batch.common... import ...``. Every ``patch()`` call must
# target the ``read_customer_job`` namespace, NOT the original
# ``glue_context`` / ``db_connector`` definition sites. Centralised as
# constants to avoid typos across the six test functions and to make
# the mocking strategy grep-able from a single location.
# ----------------------------------------------------------------------------
_PATCH_INIT_GLUE = "src.batch.jobs.read_customer_job.init_glue"
_PATCH_READ_TABLE = "src.batch.jobs.read_customer_job.read_table"
_PATCH_COMMIT_JOB = "src.batch.jobs.read_customer_job.commit_job"


# ----------------------------------------------------------------------------
# Helper: mock DataFrame factory.
# ----------------------------------------------------------------------------
# The target module's main() chains PySpark DataFrame operations
# fluently — specifically the pattern::
#
#     customers_df = read_table(spark, _TABLE_NAME)
#     customers_df = customers_df.cache()
#     record_count = customers_df.count()
#     for row in customers_df.collect():
#         logger.info("CUSTOMER-RECORD: %s", row.asDict())
#     customers_df.unpersist()
#
# A plain ``MagicMock()`` would produce a FRESH child mock on each
# chained call, making invocation assertions clumsy (each call site
# would mutate a different descendant mock). For example, without the
# factory below, ``df.cache().count()`` would return a new mock on
# ``count()`` that is distinct from ``df.count()`` — so asserting
# ``df.count.return_value == 50`` would have no effect on the value
# observed by main().
#
# This helper configures a MagicMock whose ``cache`` method returns
# the SAME mock instance, so the fluent chain collapses to a single
# tracked mock per DataFrame. ``count()`` returns an integer so
# main()'s ``if record_count > 0`` branch can be steered
# deterministically. ``collect()`` returns a list of row-like mocks
# so the per-record ``DISPLAY`` equivalent loop can be exercised.
#
# * ``cache() → self``           — keeps the cached reference shared.
# * ``count() → count_value``    — drives the > 0 branch selection.
# * ``collect() → rows``         — drives the per-record log iteration.
# * ``unpersist() → None``       — cleanup path calls this before end.
# ----------------------------------------------------------------------------
def _make_mock_df(
    count_value: int = 0, rows: list[MagicMock] | None = None
) -> MagicMock:
    """Build a chainable mock DataFrame for use with patched ``read_table``.

    Parameters
    ----------
    count_value
        Integer returned by the mock DataFrame's ``count()`` method.
        Setting this to ``0`` triggers the main() empty-table branch
        at the ``if record_count > 0`` check (target module line
        ~440), which skips the per-row iteration and logs the
        "empty table" informational message instead. Setting it to
        a positive value drives main() through the full
        ``collect()`` iteration loop — matching the COBOL
        PERFORM UNTIL END-OF-FILE loop (lines 74-81).
    rows
        List of row-like objects returned by the mock DataFrame's
        ``collect()`` method. Each element should be a
        :class:`MagicMock` whose ``asDict()`` method returns the
        CUSTOMER-RECORD dict to be logged. Defaults to an empty
        list — appropriate for tests that set ``count_value=0``
        and do NOT exercise the iteration loop. Must be supplied
        when ``count_value > 0`` or the loop will iterate zero
        times and fail to exercise the per-row log emission.

    Returns
    -------
    MagicMock
        A mock DataFrame whose ``cache()`` method returns the same
        mock (so the fluent chain ``df.cache().count()`` collapses to
        a single tracked instance), ``count()`` returns the
        caller-supplied integer, ``collect()`` returns the
        caller-supplied list, and ``unpersist()`` returns ``None``.
    """
    df = MagicMock(name="MockDataFrame")
    # Chainable method: cache() returns self so the target module's
    # reassignment pattern (``customers_df = customers_df.cache()``)
    # preserves the tracked mock. Without this, the subsequent
    # ``customers_df.count()`` and ``customers_df.collect()`` would
    # operate on an auto-generated child mock with no configured
    # behavior.
    df.cache.return_value = df
    # Terminal action methods.
    df.count.return_value = count_value
    df.collect.return_value = rows if rows is not None else []
    df.unpersist.return_value = None
    return df


# ----------------------------------------------------------------------------
# Helper: mock Row factory.
# ----------------------------------------------------------------------------
# PySpark Row objects expose an ``asDict()`` method that returns
# ``{column_name: column_value, ...}``. The target module iterates
# ``for row in customers_df.collect(): logger.info("CUSTOMER-RECORD:
# %s", row.asDict())`` so the mocks returned by ``collect()`` must
# implement ``asDict()``. We build stand-ins by wrapping a MagicMock
# and configuring its ``asDict.return_value`` to an appropriate dict
# shaped like the CUSTOMER-RECORD layout (``app/cpy/CVCUS01Y.cpy``).
#
# The primary non-FILLER fields from the COBOL record layout are:
#   * CUST-ID                  PIC 9(09)  → cust_id      (int, 9-digit PK)
#   * CUST-FIRST-NAME          PIC X(25)  → first_name   (str)
#   * CUST-LAST-NAME           PIC X(25)  → last_name    (str)
#   * CUST-ADDR-STATE-CD       PIC X(02)  → addr_state_cd (str)
#   * CUST-ADDR-ZIP            PIC X(10)  → addr_zip     (str)
#   * CUST-SSN                 PIC 9(09)  → ssn          (int)
#   * CUST-FICO-CREDIT-SCORE   PIC 9(03)  → fico_credit_score (int)
#
# The FILLER PIC X(168) is NOT represented in the Aurora schema (pure
# VSAM slack padding) per the target module's docstring (lines 82-88
# of ``src/batch/jobs/read_customer_job.py``), so it is omitted from
# the dict returned by asDict(). Other fields (middle name, address
# lines, phone numbers, DOB, EFT account, cardholder indicator, govt
# ID) are also representable but omitted from the minimal stand-in
# to keep the test fixtures grep-able; the iteration-loop tests
# assert presence of ``cust_id`` in log output, which is sufficient
# to confirm the per-row DISPLAY equivalent fires for each row.
# ----------------------------------------------------------------------------
def _make_mock_row(
    cust_id: int,
    first_name: str = "TESTFIRST",
    last_name: str = "TESTLAST",
    addr_state_cd: str = "NY",
    addr_zip: str = "10001",
    ssn: int = 123456789,
    fico_credit_score: int = 750,
) -> MagicMock:
    """Build a mock PySpark Row with the CUSTOMER-RECORD layout.

    Parameters
    ----------
    cust_id
        9-digit customer ID primary key. Matches COBOL
        CUST-ID PIC 9(09). This is the distinguishing field used
        in iteration-loop assertions to confirm each row's
        asDict() result is rendered into a distinct log line.
    first_name
        Customer first name. Matches COBOL CUST-FIRST-NAME PIC X(25).
    last_name
        Customer last name. Matches COBOL CUST-LAST-NAME PIC X(25).
    addr_state_cd
        Two-letter state code. Matches COBOL
        CUST-ADDR-STATE-CD PIC X(02).
    addr_zip
        ZIP code. Matches COBOL CUST-ADDR-ZIP PIC X(10).
    ssn
        9-digit social security number. Matches COBOL
        CUST-SSN PIC 9(09). Note: the ``customers`` table schema
        intentionally stores PII per the project's data protection
        policy; this test fixture uses dummy values that do not
        correspond to any real individual.
    fico_credit_score
        3-digit FICO credit score. Matches COBOL
        CUST-FICO-CREDIT-SCORE PIC 9(03).

    Returns
    -------
    MagicMock
        A mock Row whose ``asDict()`` method returns a subset of
        the CUSTOMER-RECORD dict. The subset is sufficient to
        exercise the per-row log-emission contract in the target
        module's iteration loop.
    """
    row = MagicMock(name=f"MockRow(cust_id={cust_id})")
    row.asDict.return_value = {
        "cust_id": cust_id,
        "first_name": first_name,
        "last_name": last_name,
        "addr_state_cd": addr_state_cd,
        "addr_zip": addr_zip,
        "ssn": ssn,
        "fico_credit_score": fico_credit_score,
    }
    return row


# ----------------------------------------------------------------------------
# Test 1: main() reads the customers PostgreSQL table.
# ----------------------------------------------------------------------------
# Verifies that main() issues exactly one ``read_table(spark,
# "customers")`` call — the PySpark equivalent of the COBOL OPEN INPUT
# CUSTFILE-FILE statement (paragraph 0000-CUSTFILE-OPEN, line 120)
# combined with the JCL //CUSTFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS binding (READCUST.jcl lines
# 9-10). The assertion catches three distinct failure modes:
#   1. Wrong table name — drift from "customers" would break the
#      mainframe-to-cloud VSAM-to-PostgreSQL mapping.
#   2. Extra table reads — the diagnostic reader must touch ONLY the
#      CUSTDATA cluster; reading any other table would violate
#      CBCUS01C's scope (it is strictly single-file).
#   3. Wrong SparkSession — read_table must receive the SparkSession
#      returned by init_glue, not a fresh/alternative one.
#
# The test also asserts init_glue was called with the canonical
# ``carddemo-read-customer`` job name, preserving the naming convention
# documented in AAP §0.5.1.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_reads_customers_table(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
) -> None:
    """``main()`` must call ``read_table(spark, "customers")``."""
    # --- Arrange ----------------------------------------------------
    # init_glue returns the canonical 4-tuple (spark, glue_context,
    # job, resolved_args). glue_context and job are ``None`` in
    # local-dev (matches the target module's own fallback path when
    # _GLUE_AVAILABLE is False) — the test only needs ``job`` to be a
    # unique object so we can assert commit_job(job) was called with
    # it.
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # read_table returns an empty DataFrame — the empty-table branch
    # still exercises the read_table invocation, which is the entire
    # assertion surface for this test. No per-row iteration is
    # required to validate the table-read contract.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Exactly ONE read_table call — CBCUS01C is a single-file
    # diagnostic reader (FILE-CONTROL declares only CUSTFILE-FILE).
    assert mock_read_table.call_count == 1, (
        f"Expected exactly 1 read_table() call (CBCUS01C is a "
        f"single-file diagnostic reader — FILE-CONTROL declares "
        f"only CUSTFILE-FILE); got {mock_read_table.call_count}"
    )

    # The call must use the canonical SparkSession + table-name pair.
    # ``call(mock_spark, "customers")`` is the literal positional-
    # argument signature expected by the target module's
    # ``read_table(spark, _TABLE_NAME)`` invocation. This assertion
    # would FAIL on any of:
    #   * Wrong SparkSession threaded through (different object id)
    #   * Wrong table name (drift from "customers")
    #   * Additional keyword arguments (read_table signature is
    #     positional-only for these two parameters)
    mock_read_table.assert_called_once_with(mock_spark, _EXPECTED_TABLE_NAME)

    # Explicit call-list cross-check — ``call_args_list`` vs the
    # expected ``[call(...)]`` single-entry list. This is defensive
    # overlap with ``assert_called_once_with`` above but phrases the
    # contract as "the total invocation history equals exactly this
    # list," which catches subtle bugs that the call-count assertion
    # might miss (e.g., duplicate recording due to mock reconfiguration).
    assert mock_read_table.call_args_list == [
        call(mock_spark, _EXPECTED_TABLE_NAME)
    ], (
        f"read_table invocation history must be exactly "
        f"[call(spark, {_EXPECTED_TABLE_NAME!r})]; "
        f"got {mock_read_table.call_args_list}"
    )

    # init_glue was called exactly once with the module's canonical
    # job name — this matches the naming convention documented in
    # AAP §0.5.1 (``carddemo-<job>`` pattern) and aligns the Glue
    # job definition in ``infra/glue-job-configs/`` with the script
    # entry point here.
    mock_init_glue.assert_called_once_with(job_name=_EXPECTED_JOB_NAME)

    # commit_job must be invoked after the successful diagnostic run
    # to signal Step Functions of stage completion. Verified in depth
    # by ``test_commit_job_called`` below; here we just sanity-check
    # the happy-path reaches it.
    mock_commit_job.assert_called_once_with(mock_job)




# ----------------------------------------------------------------------------
# Test 2: main() logs the COBOL START-OF-EXECUTION banner.
# ----------------------------------------------------------------------------
# Verifies behavioral parity with CBCUS01C.cbl line 71:
#   ``DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.``
#
# The COBOL DISPLAY statement writes to SYSOUT — the mainframe's
# default unbuffered stdout. Its Python equivalent must:
#   1. Use the logging framework (not print()) — so operators can
#      filter / structure log output in CloudWatch.
#   2. Emit at INFO level — DISPLAY is purely informational in CBCUS01C
#      (it does not indicate errors or warnings).
#   3. Preserve the EXACT text "START OF EXECUTION OF PROGRAM CBCUS01C"
#      so that downstream log-parsing / alerting rules written against
#      the original mainframe SYSOUT continue to match.
#
# The test captures all log records at INFO+ via pytest's ``caplog``
# fixture and asserts the expected substring is present in at least
# one INFO-level record's message.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_logs_start_message(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """``main()`` must log the COBOL START-OF-EXECUTION banner."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    # ``caplog.at_level(logging.INFO)`` both raises the capture
    # threshold and emits INFO-level records to the capture buffer.
    # We capture the entire main() call within this context so every
    # log call in the module's execution path is recorded.
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # Pull the full list of captured log messages. We do a substring
    # match (not byte-exact equality) because the logger may prepend
    # or wrap the message with structured metadata (e.g., timestamp,
    # level name, module path) when the caplog formatter is active.
    captured_messages = [record.getMessage() for record in caplog.records]

    # Debugging-friendly assertion: print the full captured buffer in
    # the failure message so a failure reveals what WAS logged vs
    # what was expected.
    assert any(
        _COBOL_START_MSG_EXPECTED in msg for msg in captured_messages
    ), (
        f"Expected a log message containing "
        f"{_COBOL_START_MSG_EXPECTED!r} (COBOL DISPLAY parity with "
        f"CBCUS01C.cbl line 71), but no captured log record contained "
        f"that substring.\n"
        f"Captured messages ({len(captured_messages)}):\n  "
        + "\n  ".join(repr(m) for m in captured_messages)
    )

    # Level assertion — the START banner must be INFO (not DEBUG /
    # WARNING / ERROR). DISPLAY in CBCUS01C is purely informational.
    start_records_at_info = [
        record
        for record in caplog.records
        if _COBOL_START_MSG_EXPECTED in record.getMessage()
        and record.levelno == logging.INFO
    ]
    assert len(start_records_at_info) >= 1, (
        f"Expected at least one INFO-level log record containing "
        f"{_COBOL_START_MSG_EXPECTED!r}; got "
        f"{len(start_records_at_info)} matching records at INFO level. "
        f"DISPLAY in CBCUS01C is informational — it must not be "
        f"downgraded to DEBUG nor upgraded to WARNING/ERROR."
    )


# ----------------------------------------------------------------------------
# Test 3: main() logs the COBOL END-OF-EXECUTION banner AFTER the start.
# ----------------------------------------------------------------------------
# Verifies behavioral parity with CBCUS01C.cbl line 85:
#   ``DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.``
#
# Two invariants are asserted:
#   (A) The END banner IS logged (substring match) at INFO level.
#   (B) The END banner is logged AFTER the START banner — i.e., the
#       two DISPLAYs bookend the execution window, matching the COBOL
#       paragraph ordering (START displayed before the READ loop,
#       END displayed after the loop exits and before GOBACK).
#
# Invariant (B) is especially important because the Python
# implementation has more intermediate log calls (resolved args,
# opening table, record count, per-row dump, etc.) — any of which
# could accidentally precede the "END" message if the code path is
# broken (e.g., early-return without reaching the end banner).
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_logs_end_message(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """``main()`` must log the COBOL END-OF-EXECUTION banner after START."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )
    # Use a single-row DataFrame so the read/iterate/close flow runs
    # through all stages of the state machine; this exposes ordering
    # bugs where the end banner might fire too early.
    mock_read_table.return_value = _make_mock_df(
        count_value=1, rows=[_make_mock_row(cust_id=1)]
    )

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    captured_messages = [record.getMessage() for record in caplog.records]

    # Invariant (A): END banner is present.
    assert any(
        _COBOL_END_MSG_EXPECTED in msg for msg in captured_messages
    ), (
        f"Expected a log message containing "
        f"{_COBOL_END_MSG_EXPECTED!r} (COBOL DISPLAY parity with "
        f"CBCUS01C.cbl line 85), but no captured log record contained "
        f"that substring.\n"
        f"Captured messages ({len(captured_messages)}):\n  "
        + "\n  ".join(repr(m) for m in captured_messages)
    )

    # Level assertion — the END banner must be INFO, matching the
    # informational semantics of COBOL DISPLAY.
    end_records_at_info = [
        record
        for record in caplog.records
        if _COBOL_END_MSG_EXPECTED in record.getMessage()
        and record.levelno == logging.INFO
    ]
    assert len(end_records_at_info) >= 1, (
        f"Expected at least one INFO-level log record containing "
        f"{_COBOL_END_MSG_EXPECTED!r}; got "
        f"{len(end_records_at_info)} matching records at INFO level."
    )

    # Invariant (B): END banner follows START banner in the log
    # stream. We compute the ordered indices of records that contain
    # either banner and assert the last START index precedes the
    # first END index.
    start_indices = [
        i
        for i, msg in enumerate(captured_messages)
        if _COBOL_START_MSG_EXPECTED in msg
    ]
    end_indices = [
        i
        for i, msg in enumerate(captured_messages)
        if _COBOL_END_MSG_EXPECTED in msg
    ]

    # Both lists must be non-empty for the ordering comparison to be
    # meaningful (otherwise the assertion is vacuously true and
    # masks bugs). The (A)-style assertions above already require
    # ``len(end_indices) >= 1``; we additionally require at least one
    # START index so the ordering check has a reference point.
    assert len(start_indices) >= 1, (
        "Expected at least one START log line to establish ordering "
        "reference; got zero. (Covered by test_logs_start_message "
        "but re-checked here to ensure test_logs_end_message is a "
        "well-defined ordering test.)"
    )
    assert len(end_indices) >= 1, (
        "Expected at least one END log line to establish ordering "
        "reference; got zero."
    )

    # Strict-precedence check: the LAST START occurrence must come
    # before the FIRST END occurrence. Using last-start / first-end
    # is the most defensive variant because:
    #   * Multiple START lines (unlikely but defensive) would not
    #     mask an early END.
    #   * Multiple END lines (unlikely but defensive) would not mask
    #     a late START.
    assert start_indices[-1] < end_indices[0], (
        f"START banner must precede END banner in log stream. "
        f"start_indices={start_indices}, end_indices={end_indices}. "
        f"This violates the COBOL paragraph ordering where "
        f"'START OF EXECUTION' is DISPLAYed before the READ loop "
        f"and 'END OF EXECUTION' is DISPLAYed after the loop exits."
    )




# ----------------------------------------------------------------------------
# Test 4: main() logs the record count.
# ----------------------------------------------------------------------------
# Verifies behavioral parity with CBCUS01C.cbl paragraph
# 1000-CUSTFILE-GET-NEXT (lines 143-156). In COBOL, the program does
# not explicitly DISPLAY a "record count" — it simply READs until
# EOF. However, the modernized Python implementation ADDS a defensive
# count-and-log step ("customers record count: %d") to aid
# CloudWatch-based operational monitoring (AAP §0.7.2
# "Monitoring Requirements").
#
# This test asserts that:
#   1. The DataFrame's ``count()`` method is called (so the value
#      is actually determined, not hardcoded).
#   2. The log stream contains a message referencing both "record
#      count" (case-insensitive) and the exact numeric value.
#   3. The DataFrame is cached before count is computed — this is
#      critical for performance, since otherwise the Spark plan
#      would be re-executed for both count() and collect(),
#      doubling JDBC load on the Aurora cluster.
#
# Uses a 50-row fixture to match the baseline CUSTDATA seed size
# (AAP §0.2.4 "9 fixture files: custdata.txt 50 customers").
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_logs_record_count(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """``main()`` must log the customer table record count."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # 50 rows matches the CUSTDATA seed fixture cardinality per
    # AAP §0.2.4. Using the exact production row count in tests
    # makes the test a truer reflection of the deployed dataset and
    # surfaces any off-by-one logging bugs against the real data.
    expected_count = 50
    mock_rows = [_make_mock_row(cust_id=i) for i in range(1, expected_count + 1)]
    mock_df = _make_mock_df(count_value=expected_count, rows=mock_rows)
    mock_read_table.return_value = mock_df

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    captured_messages = [record.getMessage() for record in caplog.records]

    # The count() method must have been invoked exactly once on the
    # DataFrame — verifies that main() actually computes the count
    # rather than hard-coding a value. Any count.call_count != 1
    # signals a regression (e.g., duplicate count evaluation causing
    # double JDBC load).
    assert mock_df.count.call_count == 1, (
        f"Expected DataFrame.count() to be called exactly 1 time "
        f"(to determine record count for logging); got "
        f"{mock_df.count.call_count} calls. Multiple calls would "
        f"cause redundant JDBC query execution against Aurora."
    )

    # The DataFrame must be cached BEFORE count+collect are
    # performed, otherwise the Spark DAG would be re-evaluated for
    # each action. cache() is a fluent no-op on mocks, but the call
    # must still be recorded.
    mock_df.cache.assert_called_once()

    # The log stream must contain at least one message that
    # references both the phrase "record count" (case-insensitive)
    # and the exact numeric value (50). Using both conditions
    # guards against false positives (e.g., "cust_id = 50" would
    # match the number alone but is not a count log line).
    count_log_lines = [
        msg
        for msg in captured_messages
        if "record count" in msg.lower() and str(expected_count) in msg
    ]
    assert len(count_log_lines) >= 1, (
        f"Expected at least 1 log line referencing both "
        f"'record count' (case-insensitive) and the value "
        f"{expected_count}; got {len(count_log_lines)}.\n"
        f"Captured messages ({len(captured_messages)}):\n  "
        + "\n  ".join(repr(m) for m in captured_messages)
    )


# ----------------------------------------------------------------------------
# Test 5: main() iterates ALL customer records and logs each row.
# ----------------------------------------------------------------------------
# Verifies behavioral parity with CBCUS01C.cbl paragraph
# 1000-CUSTFILE-GET-NEXT (lines 143-156):
#   ``READ CUSTFILE-FILE INTO CUSTOMER-RECORD``
#   ``DISPLAY CUSTOMER-RECORD``
#
# The COBOL program reads each record from the VSAM cluster into the
# CUSTOMER-RECORD working-storage group item, then DISPLAYs the
# entire 500-byte record to SYSOUT. The Python equivalent:
#   ``for row in customers_df.collect():``
#   ``    logger.info("CUSTOMER-RECORD: %s", row.asDict())``
#
# This test asserts:
#   1. DataFrame.collect() is called exactly once (iteration driver).
#   2. Each row's asDict() method is called exactly once (the row
#      must be rendered to a dict for the log message).
#   3. The log stream contains one "CUSTOMER-RECORD" line per row
#      (cardinality preservation — no rows dropped or duplicated).
#   4. Each row's distinguishing cust_id appears in the log stream
#      (correct row-to-log mapping — no row is silently replaced
#      by another).
#
# Uses 3 rows (not 50) to keep assertion output concise while still
# exercising the multi-row iteration path.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_iterates_all_records(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """``main()`` must iterate and log every row in the DataFrame."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Three distinct rows with sentinel cust_id values. We use
    # unambiguous 9-digit values that are unlikely to be a false
    # positive match in any structural log metadata (e.g., PID,
    # timestamp, line number).
    row_1 = _make_mock_row(
        cust_id=100000001,
        first_name="ALICE",
        last_name="SMITH",
        addr_state_cd="NY",
        addr_zip="10001",
        ssn=111111111,
        fico_credit_score=800,
    )
    row_2 = _make_mock_row(
        cust_id=200000002,
        first_name="BOB",
        last_name="JONES",
        addr_state_cd="CA",
        addr_zip="90001",
        ssn=222222222,
        fico_credit_score=650,
    )
    row_3 = _make_mock_row(
        cust_id=300000003,
        first_name="CAROL",
        last_name="DAVIS",
        addr_state_cd="TX",
        addr_zip="75001",
        ssn=333333333,
        fico_credit_score=720,
    )
    rows = [row_1, row_2, row_3]

    mock_df = _make_mock_df(count_value=len(rows), rows=rows)
    mock_read_table.return_value = mock_df

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    captured_messages = [record.getMessage() for record in caplog.records]

    # Exactly one collect() call — this is the iteration driver.
    # Multiple calls would cause duplicate JDBC load; zero calls
    # would mean no rows were fetched.
    assert mock_df.collect.call_count == 1, (
        f"Expected DataFrame.collect() to be called exactly 1 time "
        f"(single iteration pass); got {mock_df.collect.call_count}"
    )

    # Each row's asDict() must be called exactly once — the Python
    # target renders each row to a dict via ``row.asDict()`` before
    # logging, and duplicate rendering would be wasteful.
    for idx, row in enumerate(rows, start=1):
        row.asDict.assert_called_once()
        assert row.asDict.call_count == 1, (
            f"Row {idx} (cust_id={row.asDict.return_value['cust_id']}): "
            f"asDict() must be called exactly once; got "
            f"{row.asDict.call_count}"
        )

    # Cardinality preservation: exactly N CUSTOMER-RECORD log lines
    # for N rows. We search for the "CUSTOMER-RECORD" marker which
    # is the literal prefix used by the target module's per-row
    # logger.info call.
    per_record_log_lines = [
        msg for msg in captured_messages if "CUSTOMER-RECORD" in msg
    ]
    assert len(per_record_log_lines) == len(rows), (
        f"Expected exactly {len(rows)} 'CUSTOMER-RECORD' log lines "
        f"(one per row); got {len(per_record_log_lines)}.\n"
        f"Matching log lines:\n  "
        + "\n  ".join(repr(m) for m in per_record_log_lines)
    )

    # Row-to-log mapping: each row's distinguishing cust_id must
    # appear in the joined log stream. Joining into a single string
    # is sufficient because the cust_id values are globally unique
    # sentinels — a collision with metadata is extremely unlikely.
    joined_log = "\n".join(per_record_log_lines)
    for row in rows:
        expected_cust_id = row.asDict.return_value["cust_id"]
        assert str(expected_cust_id) in joined_log, (
            f"Expected cust_id {expected_cust_id} to appear in the "
            f"CUSTOMER-RECORD log lines (row-to-log mapping "
            f"verification); not found.\n"
            f"Joined CUSTOMER-RECORD lines:\n{joined_log}"
        )


# ----------------------------------------------------------------------------
# Test 6: main() invokes commit_job() exactly once with the Glue job.
# ----------------------------------------------------------------------------
# Verifies behavioral parity with the JCL job-success semantics of
# READCUST.jcl:
#   * COBOL ``GOBACK`` (CBCUS01C.cbl line 86) returns control to the
#     CALLER with RETURN-CODE set (defaults to 0 on success).
#   * JCL step ``//STEP05 EXEC PGM=CBCUS01C`` reports MAXCC=0 when
#     the program returns cleanly.
#   * The z/OS JES dispatcher only marks the job as "COMPLETE" when
#     all steps report MAXCC <= 4.
#
# In the Python / AWS Glue equivalent:
#   * ``commit_job(job)`` calls ``job.commit()`` on the Glue Job
#     object, which persists bookmarks and marks the run as
#     SUCCEEDED in the Glue control plane.
#   * Step Functions uses the Glue run status to advance the
#     pipeline state machine (AAP §0.4.4 "Step Functions").
#
# Failure modes this test catches:
#   * commit_job NOT called — Glue would mark the run FAILED despite
#     successful diagnostic output, stalling Step Functions.
#   * commit_job called MULTIPLE times — Glue would raise an error
#     on the second call (bookmark already advanced), crashing the
#     job after apparent success.
#   * commit_job called with WRONG argument (not the Glue job
#     returned by init_glue) — wrong bookmark would be committed.
#
# Uses an empty DataFrame to exercise the commit-on-empty path:
# the COBOL program always issues GOBACK regardless of record count
# (even a zero-record CUSTFILE returns MAXCC=0), so the Python
# equivalent must commit even when no rows were found.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_commit_job_called(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
) -> None:
    """``main()`` must invoke ``commit_job(job)`` exactly once."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Empty DataFrame — verifies the commit fires even when there
    # are no records. The COBOL program always reaches GOBACK after
    # hitting EOF, regardless of whether the file had any records,
    # so the Python equivalent must commit in the empty branch too.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Exactly ONE commit_job call — multiple calls would crash Glue
    # on the second invocation; zero calls would leave the run in
    # an uncommitted state.
    assert mock_commit_job.call_count == 1, (
        f"Expected commit_job() to be called exactly 1 time "
        f"(matching COBOL GOBACK / JCL MAXCC=0 semantics); got "
        f"{mock_commit_job.call_count}"
    )

    # The commit MUST receive the Glue job object returned by
    # init_glue — anything else (None, a different mock, keyword
    # args) indicates a bug in how main() threads the job handle
    # through the execution state.
    mock_commit_job.assert_called_once_with(mock_job)

    # Explicit call-list cross-check — catches accidental double
    # recording (e.g., commit_job invoked both from happy-path and
    # error-handler branches, which would corrupt the Glue bookmark).
    assert mock_commit_job.call_args_list == [call(mock_job)], (
        f"commit_job invocation history must be exactly "
        f"[call(mock_job)]; got {mock_commit_job.call_args_list}"
    )

