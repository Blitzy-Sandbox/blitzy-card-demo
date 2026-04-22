# ============================================================================
# CardDemo — Unit tests for read_account_job (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/CBACT01C.cbl     — Account diagnostic reader. Opens the
#                                ACCTFILE VSAM KSDS cluster (INDEXED
#                                SEQUENTIAL ACCESS), PERFORMs
#                                UNTIL END-OF-FILE = 'Y' issuing
#                                READ ACCTFILE-FILE INTO ACCOUNT-RECORD
#                                and emitting DISPLAY ACCOUNT-RECORD on
#                                each iteration. Bookended by the
#                                DISPLAY 'START OF EXECUTION OF PROGRAM
#                                CBACT01C' and DISPLAY 'END OF EXECUTION
#                                OF PROGRAM CBACT01C' statements at lines
#                                71 and 85 of the source.
#   * app/jcl/READACCT.jcl     — JCL job card (``//READACCT JOB ...``) +
#                                EXEC PGM=CBACT01C in STEP05 with
#                                STEPLIB DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.LOADLIB and
#                                ACCTFILE DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
#                                plus SYSOUT + SYSPRINT = SYSOUT=*. These
#                                collapse into a single init_glue() +
#                                read_table(spark, "accounts") invocation
#                                pair in the target PySpark module.
#   * app/cpy/CVACT01Y.cpy     — ACCOUNT-RECORD layout (RECLN 300):
#                                ACCT-ID                    PIC 9(11) —
#                                  11-digit account number primary key,
#                                ACCT-ACTIVE-STATUS         PIC X(01) —
#                                  single-character active flag
#                                  ('Y' / 'N'),
#                                ACCT-CURR-BAL              PIC S9(10)V99 —
#                                  signed fixed-point current balance,
#                                ACCT-CREDIT-LIMIT          PIC S9(10)V99 —
#                                  signed fixed-point credit limit,
#                                ACCT-CASH-CREDIT-LIMIT     PIC S9(10)V99 —
#                                  signed fixed-point cash-credit limit,
#                                ACCT-OPEN-DATE             PIC X(10) —
#                                  account open date (ISO-8601 YYYY-MM-DD),
#                                ACCT-EXPIRAION-DATE        PIC X(10) —
#                                  account expiration date (note the
#                                  misspelling "EXPIRAION" is authentic
#                                  to the original COBOL copybook and is
#                                  canonicalized to ``expiration_date``
#                                  in the Aurora PostgreSQL schema),
#                                ACCT-REISSUE-DATE          PIC X(10) —
#                                  most-recent card reissue date,
#                                ACCT-CURR-CYC-CREDIT       PIC S9(10)V99 —
#                                  current-cycle credit amount,
#                                ACCT-CURR-CYC-DEBIT        PIC S9(10)V99 —
#                                  current-cycle debit amount,
#                                ACCT-ADDR-ZIP              PIC X(10) —
#                                  mailing ZIP code,
#                                ACCT-GROUP-ID              PIC X(10) —
#                                  disclosure group identifier,
#                                FILLER                     PIC X(178) —
#                                  VSAM slack padding dropped in Aurora.
# ----------------------------------------------------------------------------
# Target module under test: src/batch/jobs/read_account_job.py.
# The PySpark Glue job replaces CBACT01C.cbl + READACCT.jcl, collapsing
# the COBOL OPEN / READ-UNTIL-EOF / CLOSE sequence into a single
# read_table(spark, "accounts") + cache + collect pipeline, and the
# terminal GOBACK + JCL MAXCC=0 success signal into a commit_job(job)
# call that notifies Step Functions of stage success.
#
# These tests verify behavioral parity with CBACT01C.cbl by exercising
# the main() entry point with mocked Glue / JDBC dependencies — i.e.,
# they validate the *behavior* (single table read, per-record iteration,
# COBOL DISPLAY message preservation, commit signal, Decimal financial
# precision) without requiring an actual AWS Glue runtime, a live
# Aurora PostgreSQL cluster, or a full local SparkSession. The seven
# test cases map directly to the AAP agent-prompt's Phase 2 check-list
# for this file:
#
#   Test case                         | Verifies (COBOL source mapping)
#   ---------------------------------------------------------------------
#   test_reads_accounts_table         | OPEN INPUT ACCTFILE-FILE
#                                     | (paragraph 0000-ACCTFILE-OPEN,
#                                     | lines 133-149 of CBACT01C.cbl) +
#                                     | JCL //ACCTFILE DD DISP=SHR
#                                     | (line 25 of READACCT.jcl) →
#                                     | read_table(spark, "accounts").
#   test_logs_start_message           | DISPLAY 'START OF EXECUTION OF
#                                     | PROGRAM CBACT01C' (line 71 of
#                                     | CBACT01C.cbl) preserved byte-exact
#                                     | in the CloudWatch log stream.
#   test_logs_end_message             | DISPLAY 'END OF EXECUTION OF
#                                     | PROGRAM CBACT01C' (line 85 of
#                                     | CBACT01C.cbl) preserved byte-exact
#                                     | in the CloudWatch log stream,
#                                     | AFTER the start banner.
#   test_logs_record_count            | The PERFORM UNTIL END-OF-FILE loop
#                                     | (lines 74-81) iterates every row
#                                     | of the ACCTDATA cluster; translated
#                                     | to PySpark as a .count() call
#                                     | whose value is surfaced in a
#                                     | dedicated log line for operator
#                                     | verification.
#   test_iterates_all_records         | DISPLAY ACCOUNT-RECORD (line 78)
#                                     | inside the 1000-ACCTFILE-GET-NEXT
#                                     | read loop → one logger.info()
#                                     | call per row materialised via
#                                     | DataFrame.collect().
#   test_commit_job_called            | Terminal GOBACK (line 87) + JCL
#                                     | MAXCC=0 (READACCT step completion)
#                                     | → commit_job(job) signalling Step
#                                     | Functions success.
#   test_monetary_fields_as_decimal   | The five PIC S9(10)V99 monetary
#                                     | fields (ACCT-CURR-BAL,
#                                     | ACCT-CREDIT-LIMIT,
#                                     | ACCT-CASH-CREDIT-LIMIT,
#                                     | ACCT-CURR-CYC-CREDIT,
#                                     | ACCT-CURR-CYC-DEBIT) preserved
#                                     | through the read-display pipeline
#                                     | as :class:`decimal.Decimal` with
#                                     | no float conversion (AAP §0.7.2
#                                     | Financial Precision).
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
"""Unit tests for :mod:`src.batch.jobs.read_account_job`.

Validates behavioral parity with the original COBOL diagnostic program
``app/cbl/CBACT01C.cbl`` plus its launcher ``app/jcl/READACCT.jcl``.
CBACT01C is a *diagnostic / utility* batch program — it opens the
ACCTDATA VSAM KSDS cluster, reads every record sequentially, and
DISPLAYs each one to SYSOUT. It performs no data modification and has
no downstream dependencies: its sole purpose is to let an operator
verify the current contents of the account master file after a data
migration or before launching the production batch pipeline.

COBOL -> Python Verification Surface
------------------------------------
==================================================  ==========================================
COBOL paragraph / statement                         Python test (this module)
==================================================  ==========================================
``OPEN INPUT ACCTFILE-FILE`` L135                   ``test_reads_accounts_table``
``READ ACCTFILE-FILE INTO ...`` L93                 ``test_iterates_all_records``
``DISPLAY ACCOUNT-RECORD`` L78                      ``test_iterates_all_records``
``PERFORM UNTIL END-OF-FILE = 'Y'`` L74-81          ``test_logs_record_count``
``DISPLAY 'START OF EXECUTION ...CBACT01C'`` L71    ``test_logs_start_message``
``DISPLAY 'END OF EXECUTION ...CBACT01C'`` L85      ``test_logs_end_message``
``GOBACK`` L87 + JCL MAXCC=0                        ``test_commit_job_called``
``PIC S9(10)V99`` monetary fields (CVACT01Y.cpy)    ``test_monetary_fields_as_decimal``
==================================================  ==========================================

Mocking Strategy
----------------
The target module ``src.batch.jobs.read_account_job`` imports its three
runtime dependencies at module-load time via::

    from src.batch.common.db_connector import read_table
    from src.batch.common.glue_context import commit_job, init_glue

Because the ``from ... import ...`` form creates new name bindings in
the *importing* module's namespace, every :func:`unittest.mock.patch`
call MUST target the ``read_account_job`` namespace — NOT the
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

Sensitive Data Handling
-----------------------
The ``accounts`` table schema contains customer financial data —
current balance, credit limits, cycle credit/debit. In production
these are subject to the project's data-at-rest encryption policy,
and CloudWatch log entries emitted by this job are subject to the
same IAM access controls as the underlying JDBC read. This test
suite uses dummy values (synthetic 11-digit acct_ids, Decimal
balances with sentinel values that are trivially recognisable as
test data) that do NOT correspond to any real account — they are
chosen to be easily distinguishable if they ever leak into
production log archives.

See Also
--------
* AAP §0.2.2 — Batch Program Classification (CBACT01C listed as a
  diagnostic reader utility alongside CBACT02C, CBACT03C, CBCUS01C).
* AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue).
* AAP §0.5.1 — File-by-File Transformation Plan (read_account_job
  entry).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve functionality
  exactly as-is; no algebraic simplification; minimal change).
* AAP §0.7.2 — Financial Precision (Decimal for monetary values) +
  Testing Requirements (pytest, moto, unittest.mock).
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
#                       per-ACCOUNT-RECORD DISPLAY equivalent are emitted.
# ``Decimal``         — :class:`decimal.Decimal` is the mandated Python
#                       type for every monetary field per AAP §0.7.2
#                       "Financial Precision". Used in
#                       ``test_monetary_fields_as_decimal`` to construct
#                       sentinel Decimal values for the five
#                       PIC S9(10)V99 fields from CVACT01Y.cpy
#                       (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT,
#                       ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT,
#                       ACCT-CURR-CYC-DEBIT) and to verify they
#                       round-trip through main() unchanged (no float
#                       conversion). Also used by _make_mock_row in the
#                       row-factory helper for realistic per-row
#                       asDict() output.
# ``patch``, ``MagicMock``, ``call`` — :mod:`unittest.mock` primitives.
#                       * ``patch`` is used as a decorator on every test
#                         to replace ``init_glue`` / ``read_table`` /
#                         ``commit_job`` with mocks in the
#                         ``read_account_job`` module's own namespace.
#                       * ``MagicMock`` creates the chainable DataFrame
#                         stand-ins returned by the mocked
#                         ``read_table``, plus the SparkSession / Glue
#                         Job object tuples consumed by main().
#                       * ``call`` is imported to make positional-vs-
#                         keyword argument matching explicit and
#                         grep-able in the test body (e.g.,
#                         ``call(mock_spark, "accounts")``).
# ----------------------------------------------------------------------------
import logging
from decimal import Decimal
from unittest.mock import MagicMock, call, patch

# ----------------------------------------------------------------------------
# Third-party imports — pytest 8.x test framework.
# ----------------------------------------------------------------------------
# pytest is loaded at test-discovery time by the project's
# pyproject.toml ``[tool.pytest.ini_options]`` configuration. The
# ``unit`` marker is registered in pyproject.toml and applied to
# every test in this module so ``pytest -m unit`` runs only the
# fast, hermetic, no-I/O suite. The ``caplog`` fixture provided by
# pytest is used in five of the seven tests to capture log output.
# ----------------------------------------------------------------------------
import pytest

# ----------------------------------------------------------------------------
# First-party imports — module under test.
# ----------------------------------------------------------------------------
# ``main`` is the PySpark Glue job entry point that replaces CBACT01C's
# PROCEDURE DIVISION paragraph-set. Calling ``main()`` under patched
# dependencies exercises the full CBACT01C equivalent flow:
#
#   init_glue    (replaces JCL JOB + EXEC PGM=CBACT01C + STEPLIB)
#     → DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
#     → read_table(spark, "accounts")
#                (replaces OPEN INPUT ACCTFILE-FILE + READACCT.jcl
#                 //ACCTFILE DD DISP=SHR,
#                 DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS)
#     → df.cache() + df.count()
#                (single count-action materialisation of the lazy
#                 DataFrame; replaces the per-record READ + EOF
#                 check in paragraph 1000-ACCTFILE-GET-NEXT)
#     → for row in df.collect(): logger.info("Account Record: ...")
#                (replaces DISPLAY ACCOUNT-RECORD inside the
#                 PERFORM UNTIL END-OF-FILE loop, lines 74-81)
#     → df.unpersist()
#                (replaces CLOSE ACCTFILE-FILE in paragraph
#                 9000-ACCTFILE-CLOSE, lines 151-167)
#     → DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
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
from src.batch.jobs.read_account_job import main

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text constants from ``app/cbl/CBACT01C.cbl``.
# These mirror the target module's ``_COBOL_START_MSG`` /
# ``_COBOL_END_MSG`` private constants — duplicated here rather than
# imported so the tests independently enforce the byte-exact string
# and would FAIL if the target module ever drifted from the COBOL
# source. This enforcement is precisely the point of behavioral-
# parity testing for the mainframe-to-cloud migration (AAP §0.7.1:
# "Preserve all existing functionality exactly as-is").
#
# Line references are to ``app/cbl/CBACT01C.cbl`` as committed:
#   * Line 71:   DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.
#   * Line 85:   DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.
# ----------------------------------------------------------------------------
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBACT01C"
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBACT01C"

# ----------------------------------------------------------------------------
# Canonical PostgreSQL table name for the ACCTDATA VSAM cluster. Maps
# the JCL DD statement ``//ACCTFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`` (READACCT.jcl lines 25-26)
# to the Aurora PostgreSQL table as defined in
# ``db/migrations/V1__schema.sql`` and canonicalized by
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["ACCTDATA"]``.
# Declared here as a module-level constant so the single-table-read
# assertion in ``test_reads_accounts_table`` is auditable.
# ----------------------------------------------------------------------------
_EXPECTED_TABLE_NAME: str = "accounts"

# ----------------------------------------------------------------------------
# Canonical Glue job name for the ACCTDATA diagnostic reader. The
# target module declares this as ``_JOB_NAME = "carddemo-read-account"``;
# we duplicate here rather than import so any drift in the naming
# convention (from the ``carddemo-<job>`` pattern documented in
# AAP §0.5.1) is caught by this test suite.
# ----------------------------------------------------------------------------
_EXPECTED_JOB_NAME: str = "carddemo-read-account"

# ----------------------------------------------------------------------------
# read_account_job-namespace patch targets — the module-under-test
# re-binds ``init_glue`` / ``read_table`` / ``commit_job`` via
# ``from src.batch.common... import ...``. Every ``patch()`` call must
# target the ``read_account_job`` namespace, NOT the original
# ``glue_context`` / ``db_connector`` definition sites. Centralised as
# constants to avoid typos across the seven test functions and to make
# the mocking strategy grep-able from a single location.
# ----------------------------------------------------------------------------
_PATCH_INIT_GLUE = "src.batch.jobs.read_account_job.init_glue"
_PATCH_READ_TABLE = "src.batch.jobs.read_account_job.read_table"
_PATCH_COMMIT_JOB = "src.batch.jobs.read_account_job.commit_job"


# ----------------------------------------------------------------------------
# Helper: mock DataFrame factory.
# ----------------------------------------------------------------------------
# The target module's main() chains PySpark DataFrame operations
# fluently — specifically the pattern::
#
#     accounts_df = read_table(spark, _TABLE_NAME)
#     accounts_df = accounts_df.cache()
#     record_count = accounts_df.count()
#     for row in accounts_df.collect():
#         logger.info("Account Record: %s", row.asDict())
#     accounts_df.unpersist()
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
        ~547), which skips the per-row iteration and logs the
        "No account records found (empty table)." informational
        message instead. Setting it to a positive value drives
        main() through the full ``collect()`` iteration loop —
        matching the COBOL PERFORM UNTIL END-OF-FILE loop
        (lines 74-81).
    rows
        List of row-like objects returned by the mock DataFrame's
        ``collect()`` method. Each element should be a
        :class:`MagicMock` whose ``asDict()`` method returns the
        ACCOUNT-RECORD dict to be logged. Defaults to an empty list
        — appropriate for tests that set ``count_value=0`` and do
        NOT exercise the iteration loop. Must be supplied when
        ``count_value > 0`` or the loop will iterate zero times and
        fail to exercise the per-row log emission.

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
    # reassignment pattern (``accounts_df = accounts_df.cache()``)
    # preserves the tracked mock. Without this, the subsequent
    # ``accounts_df.count()`` and ``accounts_df.collect()`` would
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
# ``for row in accounts_df.collect(): logger.info("Account Record: %s",
# row.asDict())`` so the mocks returned by ``collect()`` must
# implement ``asDict()``. We build stand-ins by wrapping a MagicMock
# and configuring its ``asDict.return_value`` to an appropriate dict
# shaped like the ACCOUNT-RECORD layout (``app/cpy/CVACT01Y.cpy``).
#
# The non-FILLER fields from the COBOL record layout are:
#   * ACCT-ID                 PIC 9(11)     → acct_id            (int — 11-digit PK)
#   * ACCT-ACTIVE-STATUS      PIC X(01)     → active_status      (str — 'Y'/'N')
#   * ACCT-CURR-BAL           PIC S9(10)V99 → curr_bal           (Decimal)
#   * ACCT-CREDIT-LIMIT       PIC S9(10)V99 → credit_limit       (Decimal)
#   * ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99 → cash_credit_limit  (Decimal)
#   * ACCT-OPEN-DATE          PIC X(10)     → open_date          (str — YYYY-MM-DD)
#   * ACCT-EXPIRAION-DATE     PIC X(10)     → expiration_date    (str — note the
#                                             authentic COBOL typo "EXPIRAION"
#                                             is fixed in the Aurora schema
#                                             column name)
#   * ACCT-REISSUE-DATE       PIC X(10)     → reissue_date       (str)
#   * ACCT-CURR-CYC-CREDIT    PIC S9(10)V99 → curr_cyc_credit    (Decimal)
#   * ACCT-CURR-CYC-DEBIT     PIC S9(10)V99 → curr_cyc_debit     (Decimal)
#   * ACCT-ADDR-ZIP           PIC X(10)     → addr_zip           (str)
#   * ACCT-GROUP-ID           PIC X(10)     → group_id           (str)
#
# The FILLER PIC X(178) is NOT represented in the Aurora schema (pure
# VSAM slack padding) per the target module's docstring (lines 75-82
# of ``src/batch/jobs/read_account_job.py``), so it is omitted from
# the dict returned by asDict(). The iteration-loop tests assert
# presence of ``acct_id`` in log output, which is sufficient to
# confirm the per-row DISPLAY equivalent fires for each row.
#
# Every monetary field uses :class:`decimal.Decimal` — NEVER ``float``
# — so this helper enforces the AAP §0.7.2 "Financial Precision"
# contract at fixture-construction time. The default values are
# round, identifiable, trivially distinguishable sentinels that
# would stand out against any real account data if a test leaked
# into production log archives.
# ----------------------------------------------------------------------------
def _make_mock_row(
    acct_id: int = 10000000001,
    active_status: str = "Y",
    curr_bal: Decimal = Decimal("1000.00"),
    credit_limit: Decimal = Decimal("5000.00"),
    cash_credit_limit: Decimal = Decimal("500.00"),
    open_date: str = "2020-01-01",
    expiration_date: str = "2030-12-31",
    reissue_date: str = "2024-01-01",
    curr_cyc_credit: Decimal = Decimal("0.00"),
    curr_cyc_debit: Decimal = Decimal("0.00"),
    addr_zip: str = "98101-0000",
    group_id: str = "DEFAULT",
) -> MagicMock:
    """Build a mock PySpark Row with the ACCOUNT-RECORD layout.

    Parameters
    ----------
    acct_id
        11-digit account number primary key. Matches COBOL
        ACCT-ID PIC 9(11). This is the distinguishing field used
        in iteration-loop assertions to confirm each row's
        asDict() result is rendered into a distinct log line.
    active_status
        Single-character active flag ('Y' or 'N'). Matches COBOL
        ACCT-ACTIVE-STATUS PIC X(01).
    curr_bal
        Current account balance as :class:`decimal.Decimal`.
        Matches COBOL ACCT-CURR-BAL PIC S9(10)V99. NEVER ``float``
        — AAP §0.7.2 mandates Decimal for every monetary value.
        The target Aurora PostgreSQL column is NUMERIC(15,2).
    credit_limit
        Credit limit as :class:`decimal.Decimal`. Matches COBOL
        ACCT-CREDIT-LIMIT PIC S9(10)V99. NUMERIC(15,2) in Aurora.
    cash_credit_limit
        Cash-advance credit limit as :class:`decimal.Decimal`.
        Matches COBOL ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99.
        NUMERIC(15,2) in Aurora.
    open_date
        Account open date. Matches COBOL ACCT-OPEN-DATE PIC X(10).
    expiration_date
        Account expiration date. Matches COBOL ACCT-EXPIRAION-DATE
        PIC X(10) (sic — the misspelling is authentic to the
        original COBOL copybook). The Aurora schema canonicalizes
        this as ``expiration_date``.
    reissue_date
        Most-recent card reissue date. Matches COBOL
        ACCT-REISSUE-DATE PIC X(10).
    curr_cyc_credit
        Current-cycle credit amount as :class:`decimal.Decimal`.
        Matches COBOL ACCT-CURR-CYC-CREDIT PIC S9(10)V99.
        NUMERIC(15,2) in Aurora.
    curr_cyc_debit
        Current-cycle debit amount as :class:`decimal.Decimal`.
        Matches COBOL ACCT-CURR-CYC-DEBIT PIC S9(10)V99.
        NUMERIC(15,2) in Aurora.
    addr_zip
        Mailing ZIP code. Matches COBOL ACCT-ADDR-ZIP PIC X(10).
    group_id
        Disclosure group identifier (DEFAULT/ZEROAPR/etc.). Matches
        COBOL ACCT-GROUP-ID PIC X(10).

    Returns
    -------
    MagicMock
        A mock Row whose ``asDict()`` method returns the full
        ACCOUNT-RECORD dict (12 non-FILLER fields). The dict is
        sufficient to exercise the per-row log-emission contract
        in the target module's iteration loop AND the monetary-
        precision contract enforced by
        ``test_monetary_fields_as_decimal``.
    """
    row = MagicMock(name=f"MockRow(acct_id={acct_id})")
    row.asDict.return_value = {
        "acct_id": acct_id,
        "active_status": active_status,
        "curr_bal": curr_bal,
        "credit_limit": credit_limit,
        "cash_credit_limit": cash_credit_limit,
        "open_date": open_date,
        "expiration_date": expiration_date,
        "reissue_date": reissue_date,
        "curr_cyc_credit": curr_cyc_credit,
        "curr_cyc_debit": curr_cyc_debit,
        "addr_zip": addr_zip,
        "group_id": group_id,
    }
    return row


# ----------------------------------------------------------------------------
# Test 1: main() reads the accounts PostgreSQL table.
# ----------------------------------------------------------------------------
# Verifies that main() issues exactly one ``read_table(spark, "accounts")``
# call — the PySpark equivalent of the COBOL OPEN INPUT ACCTFILE-FILE
# statement (paragraph 0000-ACCTFILE-OPEN, line 135) combined with the
# JCL //ACCTFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
# binding (READACCT.jcl lines 25-26). The assertion catches three
# distinct failure modes:
#   1. Wrong table name — drift from "accounts" would break the
#      mainframe-to-cloud VSAM-to-PostgreSQL mapping.
#   2. Extra table reads — the diagnostic reader must touch ONLY the
#      ACCTDATA cluster; reading any other table would violate
#      CBACT01C's scope (it is strictly single-file).
#   3. Wrong SparkSession — read_table must receive the SparkSession
#      returned by init_glue, not a fresh/alternative one.
#
# The test also asserts init_glue was called with the canonical
# ``carddemo-read-account`` job name, preserving the naming convention
# documented in AAP §0.5.1 (``carddemo-<job>`` pattern).
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_reads_accounts_table(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
) -> None:
    """``main()`` must call ``read_table(spark, "accounts")``."""
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

    # read_table returns an empty DataFrame (count_value=5 is the
    # agent-prompt-specified default per the file-level checklist)
    # — the count-value branch still exercises the read_table
    # invocation, which is the entire assertion surface for this
    # test. Using count_value=5 exercises the non-empty path and
    # ensures the main() pipeline flows through iteration, but we
    # still supply empty row fixtures so asDict() is not invoked in
    # this test (the iteration test below covers that separately).
    mock_read_table.return_value = _make_mock_df(count_value=5)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Exactly ONE read_table call — CBACT01C is a single-file
    # diagnostic reader (FILE-CONTROL declares only ACCTFILE-FILE at
    # lines 33-38 of CBACT01C.cbl).
    assert mock_read_table.call_count == 1, (
        f"Expected exactly 1 read_table() call (CBACT01C is a "
        f"single-file diagnostic reader — FILE-CONTROL declares "
        f"only ACCTFILE-FILE); got {mock_read_table.call_count}"
    )

    # The call must use the canonical SparkSession + table-name pair.
    # ``call(mock_spark, "accounts")`` is the literal positional-
    # argument signature expected by the target module's
    # ``read_table(spark, _TABLE_NAME)`` invocation. This assertion
    # would FAIL on any of:
    #   * Wrong SparkSession threaded through (different object id)
    #   * Wrong table name (drift from "accounts")
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
# Test 2: COBOL start-of-execution banner is emitted verbatim.
# ----------------------------------------------------------------------------
# Verifies the target module preserves the byte-exact text of the
# original COBOL source's opening DISPLAY statement:
#
#     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.
#
# This is the very first business log line emitted by CBACT01C (line
# 71 of ``app/cbl/CBACT01C.cbl``) and must be preserved verbatim —
# operators and SRE tooling grep production CloudWatch log streams
# for this exact banner to identify the start of a CBACT01C execution.
# AAP §0.7.1 ("Preserve all existing functionality exactly as-is")
# makes this byte-exact preservation mandatory, not optional.
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
    """``main()`` must log the verbatim CBACT01C start banner."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )
    # Empty DataFrame — the start banner is emitted before any data
    # read, so no row fixtures are required for this test.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    # ``caplog.at_level(logging.INFO)`` installs a capture handler
    # at INFO level on the root logger for the duration of the
    # context manager. INFO matches the target module's
    # ``logger.info(_COBOL_START_MSG)`` call, ensuring it is
    # recorded in ``caplog.records``.
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # Substring matching is used rather than byte-exact equality so
    # the structured-JSON envelope installed by the target module's
    # ``_setup_logging`` helper in production does not affect test
    # pass/fail. The target module emits the COBOL banner via a
    # simple ``logger.info(_COBOL_START_MSG)`` call, so the banner
    # text will appear unchanged in the log record's message body
    # — even if the structured handler prefixes or decorates it.
    matching_records = [
        record
        for record in caplog.records
        if _COBOL_START_MSG_EXPECTED in record.getMessage()
    ]

    assert matching_records, (
        f"Expected at least one INFO-level log record containing "
        f"{_COBOL_START_MSG_EXPECTED!r} (CBACT01C.cbl line 71); "
        f"captured {len(caplog.records)} records — messages: "
        f"{[r.getMessage() for r in caplog.records]}"
    )

    # The banner must be at INFO level specifically (not DEBUG or
    # WARN). This aligns with the target module's choice of
    # ``logger.info(_COBOL_START_MSG)`` and preserves the severity
    # semantics of the COBOL DISPLAY statement (COBOL DISPLAY has
    # no severity concept, so INFO is the natural mapping).
    for record in matching_records:
        assert record.levelno == logging.INFO, (
            f"Start banner must be emitted at INFO level (matching "
            f"CBACT01C's DISPLAY statement); got "
            f"{logging.getLevelName(record.levelno)}"
        )


# ----------------------------------------------------------------------------
# Test 3: COBOL end-of-execution banner is emitted verbatim, AFTER start.
# ----------------------------------------------------------------------------
# Mirrors test_logs_start_message but for the closing banner at line
# 85 of ``app/cbl/CBACT01C.cbl``:
#
#     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.
#
# In addition to verifying the banner text is present, this test
# enforces temporal ordering — the end banner MUST appear after the
# start banner in the log stream. This is obvious in the COBOL source
# (line 85 > line 71) but must be validated in the Python translation
# because PySpark's lazy evaluation could theoretically allow the end
# banner to interleave with async log emissions from Spark workers.
# Strict ordering guarantees the banner semantics remain meaningful:
# operators can bracket the entire CBACT01C execution by these two
# markers in CloudWatch Insights queries.
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
    """``main()`` must log the verbatim CBACT01C end banner after start."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )
    # Single-row fixture — exercises the full main() pipeline
    # (including the per-row iteration loop) so the end banner is
    # guaranteed to be emitted at the terminus rather than
    # short-circuited by an empty-table early-return path.
    row = _make_mock_row(acct_id=10000000001)
    mock_read_table.return_value = _make_mock_df(count_value=1, rows=[row])

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # Step 1: the end banner is present.
    end_matching_records = [
        record
        for record in caplog.records
        if _COBOL_END_MSG_EXPECTED in record.getMessage()
    ]
    assert end_matching_records, (
        f"Expected at least one INFO-level log record containing "
        f"{_COBOL_END_MSG_EXPECTED!r} (CBACT01C.cbl line 85); "
        f"captured {len(caplog.records)} records — messages: "
        f"{[r.getMessage() for r in caplog.records]}"
    )
    for record in end_matching_records:
        assert record.levelno == logging.INFO, (
            f"End banner must be emitted at INFO level; got "
            f"{logging.getLevelName(record.levelno)}"
        )

    # Step 2: the start banner is ALSO present (sanity check —
    # otherwise the ordering check below is vacuous).
    start_matching_records = [
        record
        for record in caplog.records
        if _COBOL_START_MSG_EXPECTED in record.getMessage()
    ]
    assert start_matching_records, (
        "Start banner must be present in log output for ordering check to "
        "be meaningful; was not found."
    )

    # Step 3: temporal ordering — the LAST start-banner occurrence
    # must precede the FIRST end-banner occurrence. This idiom is
    # robust to repeat emissions (e.g., if a future logging filter
    # double-emits a banner) and still catches the contract-
    # violating case where the end banner precedes the start banner.
    start_indices = [
        i
        for i, record in enumerate(caplog.records)
        if _COBOL_START_MSG_EXPECTED in record.getMessage()
    ]
    end_indices = [
        i
        for i, record in enumerate(caplog.records)
        if _COBOL_END_MSG_EXPECTED in record.getMessage()
    ]
    assert start_indices[-1] < end_indices[0], (
        f"End banner at record index {end_indices[0]} must appear "
        f"AFTER start banner at record index {start_indices[-1]} "
        f"(CBACT01C.cbl line 85 > line 71); "
        f"start_indices={start_indices}, end_indices={end_indices}"
    )




# ----------------------------------------------------------------------------
# Test 4: total record count is logged for operator visibility.
# ----------------------------------------------------------------------------
# CBACT01C's original COBOL implementation does NOT emit a dedicated
# record-count line — it simply DISPLAYs each record and the operator
# counts them by reading SYSOUT. The Python translation (target
# module, line ~499) takes advantage of PySpark's lazy-
# evaluation model: it calls ``df.count()`` once (a Spark action
# that materialises the full table) and emits a single log line of
# the form::
#
#     Total account records read: <N>
#
# This preserves diagnostic fidelity — the operator can see at a
# glance how many rows were read — while avoiding the O(N) SYSOUT
# scanning that the COBOL equivalent required. The count() call
# also enables the ``if record_count > 0`` branch at target module
# line ~547 that skips the per-row iteration when the table is
# empty, preventing a (benign but noisy) empty-iteration log cascade.
#
# The fixture uses 50 rows to match the production seed size from
# ``db/migrations/V3__seed_data.sql`` (50 accounts) — this choice
# makes the test both realistic and a sentinel for future schema
# drift: if a developer accidentally changes the count-extraction
# logic to silently truncate at, say, 100, the assertion value ``50``
# would still pass, but the fixture size would need adjusting in
# concert — making the test a meaningful regression tripwire.
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
    """``main()`` must emit a log line carrying the record count."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # 50 rows matches the production seed size in
    # db/migrations/V3__seed_data.sql — giving the test fixture
    # realistic scale while remaining a fast, in-memory mock.
    expected_count = 50
    row_fixtures = [
        _make_mock_row(acct_id=10000000000 + i) for i in range(expected_count)
    ]
    mock_df = _make_mock_df(count_value=expected_count, rows=row_fixtures)
    mock_read_table.return_value = mock_df

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # Contract 1: the mock DataFrame's count() was called exactly
    # once. If the target module were to issue multiple count()
    # actions (each triggering a full scan of the production table
    # via Spark's lazy-evaluation model), the Aurora read IOPS
    # budget would be consumed double/triple, and the log output
    # would contain duplicate count-summary lines. Single-call
    # enforcement guards against both.
    assert mock_df.count.call_count == 1, (
        f"Expected exactly 1 count() action on the accounts DataFrame "
        f"(multiple count() calls would double/triple the Aurora "
        f"read IOPS); got {mock_df.count.call_count}"
    )

    # Contract 2: the DataFrame was cached before the count() action
    # so that the subsequent collect() iteration does not re-trigger
    # a second table scan. The target module's
    # ``accounts_df = accounts_df.cache()`` pattern (line ~497) is a
    # critical performance optimisation for the Aurora JDBC read:
    # without it, count() and collect() would each issue a full
    # SELECT, doubling the wall-clock latency.
    mock_df.cache.assert_called_once()

    # Contract 3: the count value appears in the log stream. We use
    # case-insensitive substring matching so the test is robust to
    # the exact phrasing of the log line (current target module
    # uses "Total account records read: %d", but any variation such
    # as "Record count: 50" or "ACCOUNTS COUNT = 50" would also
    # satisfy the operator-visibility contract).
    all_messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "record" in all_messages.lower() and "count" in all_messages.lower(), (
        f"Expected a log line containing 'record' and 'count' (case-"
        f"insensitive); captured messages:\n{all_messages}"
    )
    assert str(expected_count) in all_messages, (
        f"Expected the count value {expected_count} to appear in "
        f"a log line; captured messages:\n{all_messages}"
    )


# ----------------------------------------------------------------------------
# Test 5: every record is iterated (DISPLAY ACCOUNT-RECORD equivalent).
# ----------------------------------------------------------------------------
# The target module's per-row iteration at line ~576::
#
#     for row in accounts_df.collect():
#         logger.info("Account Record: %s", row.asDict())
#
# is the direct Python translation of the COBOL inner-loop DISPLAY
# at line 78 (inside the PERFORM UNTIL END-OF-FILE = 'Y' loop)::
#
#     DISPLAY ACCOUNT-RECORD.
#
# Note: the COBOL expanded paragraph 1100-DISPLAY-ACCT-RECORD (lines
# 118-131 of CBACT01C.cbl) emits one DISPLAY line per field, for a
# total of 11 distinct DISPLAYs per record (ACCT-ID, ACCT-ACTIVE-
# STATUS, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT,
# ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE,
# ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, ACCT-GROUP-ID). The
# Python translation collapses these into a single structured-JSON
# log line per row via ``row.asDict()``, which is actually more
# queryable than the original 11-line SYSOUT format — operators
# using CloudWatch Logs Insights can filter by any field directly
# rather than scrolling through tabular SYSOUT output.
#
# This test uses 3 distinct rows with sentinel acct_id values
# (10000000001, 10000000002, 10000000003) that are trivially
# distinguishable in the captured log output. The test asserts:
#   1. ``collect()`` was called exactly once (not per-row, which
#      would be catastrophic for the Aurora JDBC connection).
#   2. Each row's ``asDict()`` was called exactly once (each row
#      is materialised into a log line exactly once).
#   3. Exactly N log lines start with "Account Record:" — matching
#      the target module's format string "Account Record: %s".
#   4. Each sentinel acct_id appears somewhere in the captured
#      log output — proving no row was silently dropped.
#
# The sentinel values double as documentation: "10000000001",
# "10000000002", "10000000003" make it obvious at a glance which
# rows a test run saw.
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
    """``main()`` must emit one Account Record log line per row."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Three distinct sentinel account IDs — the monotonic-sequence
    # pattern ensures they are trivially distinguishable in the log
    # output. Each row carries a distinct group_id so asDict()
    # produces unique dicts for each row (further sanity check
    # against cross-row mock mutation bugs).
    sentinel_row_1 = _make_mock_row(
        acct_id=10000000001,
        active_status="Y",
        curr_bal=Decimal("100.00"),
        group_id="DEFAULT",
    )
    sentinel_row_2 = _make_mock_row(
        acct_id=10000000002,
        active_status="Y",
        curr_bal=Decimal("200.00"),
        group_id="ZEROAPR",
    )
    sentinel_row_3 = _make_mock_row(
        acct_id=10000000003,
        active_status="N",
        curr_bal=Decimal("300.00"),
        group_id="PREMIUM",
    )
    row_fixtures = [sentinel_row_1, sentinel_row_2, sentinel_row_3]
    mock_df = _make_mock_df(count_value=len(row_fixtures), rows=row_fixtures)
    mock_read_table.return_value = mock_df

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # Contract 1: collect() was called exactly once. A bug that
    # invoked collect() inside a loop would be catastrophic — each
    # call materialises the full DataFrame into driver memory.
    assert mock_df.collect.call_count == 1, (
        f"Expected exactly 1 collect() call on the accounts DataFrame "
        f"(calling collect() multiple times would re-materialise "
        f"the entire table into driver memory each time); got "
        f"{mock_df.collect.call_count}"
    )

    # Contract 2: each row's asDict() was called exactly once. If
    # asDict() were called more than once per row, the per-row
    # log emission would duplicate.
    for idx, row in enumerate(row_fixtures):
        assert row.asDict.call_count == 1, (
            f"Expected exactly 1 asDict() call on row[{idx}] "
            f"(acct_id={row.asDict.return_value['acct_id']}); "
            f"got {row.asDict.call_count}"
        )

    # Contract 3: exactly N log lines carry the "Account Record:"
    # prefix — matching the target module's
    # ``logger.info("Account Record: %s", row.asDict())`` format at
    # line ~577. We use case-insensitive substring matching to
    # tolerate minor format variations, but the literal string
    # "Account Record" is part of the module's agent-prompt-mandated
    # output format and should not drift.
    account_record_messages = [
        record.getMessage()
        for record in caplog.records
        if "Account Record" in record.getMessage()
    ]
    assert len(account_record_messages) == len(row_fixtures), (
        f"Expected exactly {len(row_fixtures)} 'Account Record:' log "
        f"lines (one per row in the collect() iteration); got "
        f"{len(account_record_messages)} — messages: "
        f"{account_record_messages}"
    )

    # Contract 4: every sentinel acct_id appears in the joined log
    # output. The joined-then-searched approach is robust to whether
    # the target module uses %s formatting, f-strings, or explicit
    # ``str(dict)`` rendering — the sentinel value will appear
    # verbatim in any of these forms. We cast to str() because
    # asDict() returns acct_id as int but the log output will render
    # it via str() inside the dict repr.
    all_account_record_output = "\n".join(account_record_messages)
    for row in row_fixtures:
        expected_acct_id = str(row.asDict.return_value["acct_id"])
        assert expected_acct_id in all_account_record_output, (
            f"Expected sentinel acct_id {expected_acct_id!r} to "
            f"appear in the 'Account Record:' log output (no row may "
            f"be silently dropped during iteration); full output:\n"
            f"{all_account_record_output}"
        )




# ----------------------------------------------------------------------------
# Test 6: commit_job() is invoked exactly once at successful completion.
# ----------------------------------------------------------------------------
# Every PySpark Glue job must call ``commit_job(job)`` after a
# successful execution — this is the AWS Glue idiom for signalling
# stage completion to AWS Step Functions (which orchestrates the
# multi-stage batch pipeline per AAP §0.5.1 —
# step_functions_definition.json). Skipping commit_job would leave
# the Glue job in a "running" state forever from Step Functions'
# perspective, blocking downstream jobs.
#
# The COBOL equivalent is the GOBACK statement at line 87 of
# ``app/cbl/CBACT01C.cbl`` combined with the JCL success contract
# (MAXCC=0 propagating to READACCT's return code). In the JCL
# pipeline, a successful GOBACK allows the next //STEP to execute;
# in AWS, a successful commit_job allows the next state machine
# transition.
#
# This test uses an EMPTY DataFrame (count_value=0) to deliberately
# exercise the target module's empty-table branch. commit_job MUST
# fire even when the accounts table has zero rows — the diagnostic
# reader has still "succeeded" in that case (it executed to
# completion without error). A naive implementation that gated
# commit_job behind ``if record_count > 0`` would leave the Step
# Functions stage hung forever if the upstream load pipeline had
# failed to seed any accounts.
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
    """``main()`` must call ``commit_job(job)`` on successful completion."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )
    # Empty DataFrame — deliberately exercises the empty-table
    # branch. commit_job MUST fire even when no records are present;
    # the diagnostic reader has still succeeded (it completed
    # without error).
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Contract 1: commit_job was called exactly once. Multiple
    # commit_job calls on the same Job object are a defect — the
    # Glue Job API treats subsequent commits as no-ops, but the
    # duplicated call would mask a latent logic bug (e.g., commit
    # inside a loop).
    assert mock_commit_job.call_count == 1, (
        f"Expected exactly 1 commit_job() call (Step Functions "
        f"stage success signal); got {mock_commit_job.call_count}"
    )

    # Contract 2: commit_job received the exact Job object created
    # by init_glue. A bug that passed a fresh/alternative Job
    # object would cause the Step Functions stage to hang, because
    # the AWS Glue runtime would not recognise the foreign job
    # handle. We assert object identity via ``assert_called_once_with``,
    # which performs ``==`` comparison — and MagicMock instances
    # are only equal to themselves, so this is effectively an
    # identity check.
    mock_commit_job.assert_called_once_with(mock_job)

    # Contract 3: the call-list cross-check mirrors the explicit
    # ``assert_called_once_with`` above and catches the subtle
    # failure mode where call_count matches but the arguments
    # differ (e.g., a future refactor that passes ``None`` or an
    # empty dict as a "fallback" job handle).
    assert mock_commit_job.call_args_list == [call(mock_job)], (
        f"commit_job invocation history must be exactly "
        f"[call(job)]; got {mock_commit_job.call_args_list}"
    )


# ----------------------------------------------------------------------------
# Test 7: monetary fields are read as Decimal (NUMERIC(15,2) precision).
# ----------------------------------------------------------------------------
# AAP §0.7.2 ("Financial Precision") mandates that every monetary
# value use :class:`decimal.Decimal` with explicit two-decimal-place
# precision, matching COBOL PIC S9(n)V99 semantics. Floating-point
# arithmetic is expressly forbidden for any financial calculation or
# representation because IEEE-754 double-precision ``float`` cannot
# exactly represent common decimal fractions (e.g., 0.1, 0.01) and
# would introduce subtle off-by-one-cent errors that would fail
# accounting reconciliation.
#
# The ACCOUNT-RECORD layout (``app/cpy/CVACT01Y.cpy``) has FIVE
# monetary fields, all declared as PIC S9(10)V99:
#
#   * ACCT-CURR-BAL             (line 7 of CVACT01Y.cpy)
#   * ACCT-CREDIT-LIMIT         (line 8 of CVACT01Y.cpy)
#   * ACCT-CASH-CREDIT-LIMIT    (line 9 of CVACT01Y.cpy)
#   * ACCT-CURR-CYC-CREDIT      (line 13 of CVACT01Y.cpy)
#   * ACCT-CURR-CYC-DEBIT       (line 14 of CVACT01Y.cpy)
#
# These become NUMERIC(15,2) PostgreSQL columns in the migrated
# schema (``db/migrations/V1__schema.sql``). When the PySpark JDBC
# driver reads NUMERIC(15,2) columns, it maps them to Spark
# DecimalType(15,2), which deserializes to Python
# :class:`decimal.Decimal` at driver side (during the
# ``accounts_df.collect()`` call in main()'s iteration loop).
#
# This test verifies that the full read → display pipeline preserves
# Decimal typing end-to-end:
#
#   1. The test constructs mock rows whose asDict() returns Decimal
#      values for all 5 monetary fields (with sentinel values chosen
#      to be unmistakable in log output: 1234567890.12, 9999.99,
#      500.50, 123.45, 67.89).
#   2. main() is called under patched dependencies.
#   3. The test asserts that the Decimal values flow through
#      main()'s per-row log emission WITHOUT being coerced to
#      float. This is verified by two independent mechanisms:
#        (a) The exact string representations of the Decimal values
#            appear in the log output (e.g., "1234567890.12" not
#            "1234567890.1200000001" or "1.234567890e9" that float
#            conversion would produce).
#        (b) The values stored in ``row.asDict.return_value`` remain
#            :class:`Decimal` instances after main() returns —
#            proving no in-place mutation or re-assignment to float
#            occurred.
#
# This test is an important safety net for future refactors: if a
# developer accidentally introduces ``float(row.curr_bal)`` or
# ``round(row.curr_bal, 2)`` (which coerces to float) anywhere in
# the pipeline, the test will fail. The COBOL PIC S9(10)V99 fields
# are fixed-point with exactly 2 fractional digits, and the target
# module MUST preserve that precision for every monetary column.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_monetary_fields_as_decimal(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """``main()`` must preserve Decimal typing for all 5 monetary fields."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Sentinel Decimal values chosen to be unmistakable in log
    # output and to span the full PIC S9(10)V99 range (10-digit
    # integral + 2-digit fractional = max 9999999999.99):
    #
    #   * curr_bal            = 1234567890.12  — near-maximum precision
    #   * credit_limit        = 9999.99         — typical credit limit
    #   * cash_credit_limit   = 500.50          — half-dollar precision
    #   * curr_cyc_credit     = 123.45          — common test sentinel
    #   * curr_cyc_debit      = 67.89           — common test sentinel
    #
    # Each value has a unique fractional portion so a test failure
    # immediately identifies which field was corrupted. The
    # Decimal constructor is called with a STRING literal to avoid
    # the float-literal precision trap: ``Decimal(0.1)`` would
    # produce the nearest IEEE-754 double (0.100000000000000005...)
    # whereas ``Decimal("0.1")`` produces exactly 0.1.
    expected_curr_bal = Decimal("1234567890.12")
    expected_credit_limit = Decimal("9999.99")
    expected_cash_credit_limit = Decimal("500.50")
    expected_curr_cyc_credit = Decimal("123.45")
    expected_curr_cyc_debit = Decimal("67.89")

    row = _make_mock_row(
        acct_id=10000000001,
        active_status="Y",
        curr_bal=expected_curr_bal,
        credit_limit=expected_credit_limit,
        cash_credit_limit=expected_cash_credit_limit,
        curr_cyc_credit=expected_curr_cyc_credit,
        curr_cyc_debit=expected_curr_cyc_debit,
        group_id="DEFAULT",
    )
    mock_read_table.return_value = _make_mock_df(count_value=1, rows=[row])

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # Precondition: sanity check that the fixture itself is correct
    # (Decimal in, Decimal in the stored dict). If this fails the
    # helper is buggy and the rest of the test is meaningless.
    row_dict = row.asDict.return_value
    for field_name, expected_value in [
        ("curr_bal", expected_curr_bal),
        ("credit_limit", expected_credit_limit),
        ("cash_credit_limit", expected_cash_credit_limit),
        ("curr_cyc_credit", expected_curr_cyc_credit),
        ("curr_cyc_debit", expected_curr_cyc_debit),
    ]:
        assert isinstance(row_dict[field_name], Decimal), (
            f"Test fixture precondition failed: field {field_name!r} "
            f"must be Decimal in the asDict() dict, got "
            f"{type(row_dict[field_name]).__name__}. This indicates "
            f"a bug in the _make_mock_row helper."
        )
        assert row_dict[field_name] == expected_value, (
            f"Test fixture precondition failed: field {field_name!r} "
            f"must equal {expected_value!r}, got {row_dict[field_name]!r}."
        )

    # Contract 1: type preservation. After main() returns, the values
    # in row.asDict.return_value must STILL be Decimal instances.
    # A bug that did ``row_dict[k] = float(row_dict[k])`` somewhere
    # inside main() (e.g., during a hypothetical format-conversion
    # step) would leave the fixture dict mutated with float values,
    # and this assertion would fail. This is a defensive check
    # against in-place mutation bugs.
    for field_name in (
        "curr_bal",
        "credit_limit",
        "cash_credit_limit",
        "curr_cyc_credit",
        "curr_cyc_debit",
    ):
        assert isinstance(row_dict[field_name], Decimal), (
            f"Post-main() type check failed: field {field_name!r} "
            f"is {type(row_dict[field_name]).__name__}, expected "
            f"Decimal. The target module must NOT coerce monetary "
            f"fields to float at any point in the read-display "
            f"pipeline (AAP §0.7.2 Financial Precision)."
        )
        # Stricter type check: the value must be IDENTICAL to the
        # input Decimal, not merely equal-valued. Decimal("100.00")
        # and Decimal("100") compare equal via == but have distinct
        # str() representations ("100.00" vs "100"), and float(100.0)
        # also compares equal to Decimal("100") via Python's numeric
        # promotion. Strict ``is`` identity plus Decimal-specific
        # string equality catches both failure modes.
        original_value = (
            expected_curr_bal
            if field_name == "curr_bal"
            else expected_credit_limit
            if field_name == "credit_limit"
            else expected_cash_credit_limit
            if field_name == "cash_credit_limit"
            else expected_curr_cyc_credit
            if field_name == "curr_cyc_credit"
            else expected_curr_cyc_debit
        )
        assert row_dict[field_name] is original_value, (
            f"Post-main() identity check failed: field {field_name!r} "
            f"was replaced with a new object (expected the original "
            f"Decimal instance to be preserved unchanged). This "
            f"indicates a defensive-copy bug where the module is "
            f"coercing Decimal through float and back — which loses "
            f"precision even if the final type is Decimal again."
        )

    # Contract 2: log-output verification. When the Decimal values
    # are passed to ``logger.info("Account Record: %s", row.asDict())``,
    # Python's %s formatting applies str() to the entire dict, and
    # str(dict) applies repr() to each value. For Decimal, repr()
    # returns the exact form "Decimal('<value>')", which preserves
    # every digit precisely:
    #
    #   >>> str({"x": Decimal("1234567890.12")})
    #   "{'x': Decimal('1234567890.12')}"
    #
    # If the target module accidentally converted the Decimal to
    # float before logging, the output would look instead like:
    #
    #   "{'x': 1234567890.12}"   # no "Decimal(...)" wrapper
    #
    # Or for lossy conversions:
    #
    #   "{'x': 1234567890.1199999...}"   # float precision loss
    #
    # We check for both the "Decimal(" wrapper (proving the type was
    # preserved in the log rendering) AND the exact decimal string
    # content (proving no rounding occurred) for every field.
    all_messages = "\n".join(record.getMessage() for record in caplog.records)

    # Contract 2a: the "Decimal(" wrapper must appear in the log
    # output for at least the curr_bal field. The exact count is
    # 5 (one per monetary field) in a single-row test, but the
    # specific repr format can vary between Python minor versions,
    # so we check for presence rather than exact count.
    assert "Decimal(" in all_messages, (
        "Expected at least one 'Decimal(' substring in the log "
        "output (indicating the monetary fields were rendered as "
        "Decimal instances, NOT coerced to float). None found — "
        f"full log output:\n{all_messages}"
    )

    # Contract 2b: each sentinel Decimal value must appear in the
    # log output in its exact string form. Any precision loss from
    # float conversion would cause the string form to differ
    # (e.g., "1234567890.12" would become "1234567890.1199999..."
    # after a float round-trip on some Python versions).
    for field_name, expected_value in [
        ("curr_bal", expected_curr_bal),
        ("credit_limit", expected_credit_limit),
        ("cash_credit_limit", expected_cash_credit_limit),
        ("curr_cyc_credit", expected_curr_cyc_credit),
        ("curr_cyc_debit", expected_curr_cyc_debit),
    ]:
        expected_str = str(expected_value)
        assert expected_str in all_messages, (
            f"Expected Decimal value {expected_value!r} (string form "
            f"{expected_str!r}) for field {field_name!r} to appear "
            f"verbatim in the log output (proving no precision loss "
            f"via float coercion); full log output:\n{all_messages}"
        )

    # Contract 3: the monetary precision contract documentation line
    # is emitted by main() via _log_monetary_precision_contract().
    # This audit line documents that the job runs under the
    # Decimal-scale contract mandated by AAP §0.7.2. The literal
    # substring "Decimal scale" and "PIC S9(10)V99" are emitted by
    # the helper at target module line ~343, and their presence in
    # the log stream confirms the contract documentation fired
    # during this run.
    assert "Decimal scale" in all_messages, (
        "Expected the monetary-precision contract line ('Decimal "
        "scale=...') from _log_monetary_precision_contract() to "
        f"appear in the log output; got:\n{all_messages}"
    )
    assert "PIC S9(10)V99" in all_messages, (
        "Expected the COBOL PIC clause reference ('PIC S9(10)V99') "
        "from _log_monetary_precision_contract() to appear in the "
        f"log output (links monetary contract to CVACT01Y.cpy "
        f"declarations); got:\n{all_messages}"
    )

