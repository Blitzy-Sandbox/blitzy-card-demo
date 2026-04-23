# ============================================================================
# CardDemo - Unit tests for daily_tran_driver_job (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/CBTRN01C.cbl     - Daily Transaction Driver batch program.
#                                PROGRAM-ID CBTRN01C. Opens six files
#                                (DALYTRAN sequential + CUSTFILE, XREFFILE,
#                                CARDFILE, ACCTFILE, TRANFILE all INDEXED
#                                RANDOM), iterates the daily-transaction
#                                staging feed, and performs two per-record
#                                lookups (paragraphs 2000-LOOKUP-XREF and
#                                3000-READ-ACCOUNT) to verify referential
#                                integrity before the downstream
#                                CBTRN02C (posttran_job) applies balances.
#   * app/cpy/CVTRA06Y.cpy     - DALYTRAN-RECORD layout (350 bytes):
#                                DALYTRAN-ID PIC X(16),
#                                DALYTRAN-TYPE-CD PIC X(02),
#                                DALYTRAN-CAT-CD PIC 9(04),
#                                DALYTRAN-AMT PIC S9(09)V99 (monetary
#                                amount; must stay Decimal per AAP
#                                Section 0.7.2),
#                                DALYTRAN-CARD-NUM PIC X(16),
#                                DALYTRAN-ORIG-TS PIC X(26),
#                                DALYTRAN-PROC-TS PIC X(26).
#   * app/cpy/CVTRA05Y.cpy     - TRAN-RECORD layout (350 bytes, mirror of
#                                DALYTRAN-RECORD with TRAN- field prefix).
# ----------------------------------------------------------------------------
# Target module under test: src/batch/jobs/daily_tran_driver_job.py.
# The PySpark Glue job replaces CBTRN01C.cbl, collapsing the six
# COBOL OPEN paragraphs (0000-DALYTRAN-OPEN through 0500-TRANFILE-OPEN)
# into six read_table() calls against Aurora PostgreSQL via JDBC, and
# the two per-record VSAM KEY lookups into a single multi-way
# PySpark DataFrame.join() chain:
#     daily ▹ card_cross_references ▹ customers ▹ accounts ▹ cards
#
# These tests verify behavioral parity with CBTRN01C.cbl by exercising
# the main() entry point with mocked Glue / JDBC dependencies — i.e.,
# they validate the *behavior* (six table reads, validation-join
# wiring, COBOL DISPLAY message preservation, commit signal) without
# requiring an actual AWS Glue runtime, live PostgreSQL, or a full
# SparkSession. All four test cases map to specific assertions in the
# AAP agent-prompt for this file:
#
#   Test case                      | Verifies (COBOL source mapping)
#   ---------------------------------------------------------------
#   test_reads_all_six_tables      | Six OPEN paragraphs (lines 160-165
#                                  | of CBTRN01C.cbl) → six read_table
#                                  | calls with exact table names in
#                                  | the exact COBOL file-control order.
#   test_validation_lookup_joins   | Paragraph 2000-LOOKUP-XREF (lines
#                                  | 227-239) + 3000-READ-ACCOUNT (lines
#                                  | 241-250) → four-way join chain on
#                                  | card_num / cust_id / acct_id /
#                                  | card_num with "inner" semantics
#                                  | matching COBOL INVALID KEY →
#                                  | SKIPPING TRANSACTION behavior.
#   test_log_messages_match_cobol  | DISPLAY 'START OF EXECUTION OF
#                                  | PROGRAM CBTRN01C' (line 160) +
#                                  | DISPLAY 'END OF EXECUTION OF
#                                  | PROGRAM CBTRN01C' (line 195)
#                                  | preserved verbatim.
#   test_commit_job_called         | Terminal GOBACK + JCL MAXCC=0
#                                  | (line 197) → commit_job(job)
#                                  | signalling Step Functions success.
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
"""Unit tests for :mod:`src.batch.jobs.daily_tran_driver_job`.

Validates behavioral parity with the original COBOL batch program
``app/cbl/CBTRN01C.cbl`` (the "daily transaction driver"). The driver
is a *gatekeeper* batch — it verifies referential integrity between
the inbound daily-transaction feed and the current master tables
(customers, card_cross_references, cards, accounts, transactions)
before the downstream ``posttran_job`` (``app/cbl/CBTRN02C.cbl``)
applies actual balance updates. A validation failure here halts the
pipeline before any irreversible write occurs — matching the original
JCL ``COND=(0,NE)`` abort semantics from ``app/jcl/POSTTRAN.jcl``.

COBOL -> Python Verification Surface
------------------------------------
============================================  =====================================
COBOL paragraph / statement                   Python test (this module)
============================================  =====================================
``OPEN INPUT DALYTRAN-FILE`` L168             ``test_reads_all_six_tables``
``OPEN INPUT CUSTOMER-FILE`` L169             ``test_reads_all_six_tables``
``OPEN INPUT XREF-FILE`` L170                 ``test_reads_all_six_tables``
``OPEN INPUT CARD-FILE`` L171                 ``test_reads_all_six_tables``
``OPEN INPUT ACCOUNT-FILE`` L172              ``test_reads_all_six_tables``
``OPEN INPUT TRANSACT-FILE`` L173             ``test_reads_all_six_tables``
``2000-LOOKUP-XREF`` L227-239                 ``test_validation_lookup_joins``
                                              (card_num → acct_id/cust_id)
``3000-READ-ACCOUNT`` L241-250                ``test_validation_lookup_joins``
                                              (acct_id → account record)
``DISPLAY 'START OF...'`` L160                ``test_log_messages_match_cobol``
``DISPLAY 'END OF...'`` L195                  ``test_log_messages_match_cobol``
``GOBACK`` L197 + JCL MAXCC=0                 ``test_commit_job_called``
============================================  =====================================

Mocking Strategy
----------------
* ``init_glue`` is patched in the ``daily_tran_driver_job`` namespace
  to return a ``(spark, None, job, args)`` tuple without actually
  provisioning a SparkSession, GlueContext, or Glue Job object.
* ``read_table`` is patched to return chainable :class:`MagicMock`
  DataFrames. Each mock DataFrame supports the full PySpark chain
  (``cache()``, ``alias()``, ``join()``, ``withColumn()``, ``count()``,
  ``select()``, ``take()``, ``unpersist()``) with ``return_value=self``
  so fluent-style chained calls share one tracked mock per table.
* ``commit_job`` is patched to allow assertion of invocation without
  touching a real Glue Job object.
* For :func:`test_validation_lookup_joins`, the module-local ``F``
  alias for :mod:`pyspark.sql.functions` is also patched — PySpark's
  ``F.col()`` otherwise requires an active ``SparkContext`` which we
  deliberately avoid for fast unit tests.

Financial Precision
-------------------
Per AAP Section 0.7.2 ("Financial precision"), all monetary values
in the target module flow through PySpark ``DecimalType`` columns
backed by Python :class:`decimal.Decimal`. The ``_MONETARY_ZERO``
module-level sentinel in :mod:`daily_tran_driver_job` is a
``Decimal("0.00")`` (COBOL ``PIC S9(n)V99`` two-decimal-place scale).
The imported :class:`Decimal` class is used by
:func:`test_log_messages_match_cobol` to verify the precision
contract log line emits the expected ``scale=-2`` token.

See Also
--------
* AAP Section 0.2.2 — batch program classification (CBTRN01C as driver).
* AAP Section 0.5.1 — file-by-file transformation plan.
* AAP Section 0.7.1 — preserve existing functionality exactly as-is.
* AAP Section 0.7.2 — financial-precision (Decimal only) rules.
"""

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``logging``         — pytest ``caplog`` fixture integrates with stdlib
#                       logging; we configure capture level via
#                       ``caplog.at_level(logging.INFO)`` so the module
#                       under test's ``logger.info(...)`` calls are
#                       captured as :class:`logging.LogRecord` instances.
# ``Decimal``         — Python standard library ``decimal.Decimal`` type
#                       used to verify the financial-precision contract
#                       enforced by :mod:`daily_tran_driver_job`. Imported
#                       here (matches the AAP external-imports schema
#                       for this test file) and referenced in
#                       ``test_log_messages_match_cobol`` to assert the
#                       ``Decimal("0.00").as_tuple().exponent == -2``
#                       precision contract is logged at runtime.
# ``patch``, ``MagicMock`` — :mod:`unittest.mock` primitives. ``patch``
#                       is used as a decorator on every test to replace
#                       ``init_glue`` / ``read_table`` / ``commit_job``
#                       (and ``F`` for the join test) with mocks in the
#                       ``daily_tran_driver_job`` module's own
#                       namespace. ``MagicMock`` creates the chainable
#                       DataFrame stand-ins returned by the mocked
#                       ``read_table``.
# ----------------------------------------------------------------------------
import logging
from decimal import Decimal
from unittest.mock import MagicMock, patch

# ----------------------------------------------------------------------------
# Third-party imports — pytest 8.x test framework.
# ----------------------------------------------------------------------------
# pytest is loaded at test-discovery time by the project's
# pyproject.toml ``[tool.pytest.ini_options]`` configuration. The
# ``unit`` marker is registered in pyproject.toml and applied to
# every test in this module so ``pytest -m unit`` runs only the
# fast, hermetic, no-I/O suite.
# ----------------------------------------------------------------------------
import pytest

# ----------------------------------------------------------------------------
# First-party imports — module under test.
# ----------------------------------------------------------------------------
# ``main`` is the PySpark Glue job entry point that replaces CBTRN01C's
# MAIN-PARA paragraph. Calling ``main()`` under patched dependencies
# exercises the full COBOL MAIN-PARA equivalent flow:
#
#   init_glue (replaces JCL JOB + EXEC PGM=CBTRN01C + STEPLIB)
#     → DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'
#     → six read_table (replaces six OPEN paragraphs)
#     → cache + count each DataFrame
#     → (if non-empty) four-way inner join chain (replaces 2000-LOOKUP-
#       XREF + 3000-READ-ACCOUNT)
#     → DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'
#     → commit_job (replaces GOBACK + JCL MAXCC=0)
#
# ``_MONETARY_ZERO`` is the module-level ``Decimal("0.00")`` sentinel
# used by the module's own ``_log_monetary_precision_contract``. We
# import it to assert the financial-precision contract is preserved
# after the mainframe-to-cloud migration (AAP Section 0.7.2).
# ----------------------------------------------------------------------------
from src.batch.jobs.daily_tran_driver_job import _MONETARY_ZERO, main

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text constants from ``app/cbl/CBTRN01C.cbl``.
# These mirror the target module's ``_COBOL_START_MSG`` /
# ``_COBOL_END_MSG`` private constants — duplicated here rather than
# imported so the tests independently enforce the byte-exact string
# and would FAIL if the target module ever drifted from the COBOL
# source. That enforcement is precisely the point of behavioral-
# parity testing for the mainframe-to-cloud migration (AAP Section
# 0.7.1: "Preserve all existing functionality exactly as-is").
# ----------------------------------------------------------------------------
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBTRN01C"
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBTRN01C"

# ----------------------------------------------------------------------------
# Canonical PostgreSQL table names, in the exact order the target
# module reads them. This sequence mirrors the six COBOL OPEN
# paragraphs (0000-DALYTRAN-OPEN → 0500-TRANFILE-OPEN) from
# ``app/cbl/CBTRN01C.cbl`` FILE-CONTROL (lines 28-62). Encoded here
# as a constant tuple so the expected sequence is auditable from
# the test body and can be referenced by multiple tests.
# ----------------------------------------------------------------------------
_EXPECTED_TABLE_READ_ORDER: tuple[str, ...] = (
    "daily_transactions",  # replaces DALYTRAN-FILE (CVTRA06Y, sequential)
    "customers",  # replaces CUSTOMER-FILE (CVCUS01Y, indexed)
    "card_cross_references",  # replaces XREF-FILE     (CVACT03Y, indexed)
    "cards",  # replaces CARD-FILE     (CVACT02Y, indexed)
    "accounts",  # replaces ACCOUNT-FILE  (CVACT01Y, indexed)
    "transactions",  # replaces TRANSACT-FILE (CVTRA05Y, indexed)
)

# ----------------------------------------------------------------------------
# Glue-namespace patch targets — the module-under-test re-binds these
# symbols via ``from src.batch.common... import ...`` so every patch
# call must target the ``daily_tran_driver_job`` namespace, NOT the
# original definition site. These are centralized as constants to
# avoid typos across the four test functions.
# ----------------------------------------------------------------------------
_PATCH_INIT_GLUE = "src.batch.jobs.daily_tran_driver_job.init_glue"
_PATCH_READ_TABLE = "src.batch.jobs.daily_tran_driver_job.read_table"
_PATCH_COMMIT_JOB = "src.batch.jobs.daily_tran_driver_job.commit_job"
_PATCH_F = "src.batch.jobs.daily_tran_driver_job.F"


# ----------------------------------------------------------------------------
# Helper: mock DataFrame factory.
# ----------------------------------------------------------------------------
# The target module's main() chains many PySpark DataFrame operations
# fluently — e.g., ``df.cache().count()`` and
# ``daily_trans_df.alias("d").join(x_alias, ..., how="inner")``. A
# plain ``MagicMock()`` would produce a fresh child mock on each
# chained call, making invocation assertions clumsy (each call site
# would mutate a different descendant mock).
#
# This helper configures a MagicMock whose ``cache``, ``alias``,
# ``join``, and ``withColumn`` methods all return the SAME mock
# instance, so the fluent chain collapses to a single tracked mock
# per table. ``count()`` is configured to return an integer so the
# ``daily_trans_count == 0`` early-exit branch in main() can be
# steered deterministically by passing the ``count_value`` argument.
#
# * ``cache() → self``           — keeps the cached reference shared.
# * ``alias("<n>") → self``      — same mock is the aliased projection.
# * ``join(...) → self``         — join-chain accumulates on one mock.
# * ``withColumn(...) → self``   — run_marker tag preserves the mock.
# * ``select(...) → self``       — projection before ``take()``.
# * ``take(n) → []``             — empty list ⇒ anti-join loops skip.
# * ``count() → count_value``    — drives the ``== 0`` early-exit test.
# * ``unpersist() → None``       — cleanup path calls this on each df.
# ----------------------------------------------------------------------------
def _make_mock_df(count_value: int = 0) -> MagicMock:
    """Build a chainable mock DataFrame for use with patched ``read_table``.

    Parameters
    ----------
    count_value
        Integer returned by the mock DataFrame's ``count()`` method.
        Setting this to ``0`` triggers the main() early-exit branch
        at CBTRN01C-parity line ``if daily_trans_count == 0``,
        which skips the validation-join chain and goes straight to
        the commit-job path. Setting it to a positive value drives
        main() through the full validation-lookup flow.

    Returns
    -------
    MagicMock
        A mock DataFrame whose chainable methods all return ``self``
        (the same mock), enabling fluent-style invocation tracking.
    """
    df = MagicMock(name="MockDataFrame")
    # Chainable methods — each returns the same mock so the
    # fluent-style PySpark expressions collapse onto one tracked
    # instance. This keeps ``assert_called_with(...)`` and
    # ``.call_count`` unambiguous in the test body.
    df.cache.return_value = df
    df.alias.return_value = df
    df.join.return_value = df
    df.withColumn.return_value = df
    df.select.return_value = df
    # Terminal action methods. ``take(n)`` returns an empty list so
    # the anti-join diagnostic loops in main() (``for row in ...``)
    # iterate zero times. ``count()`` returns the caller-specified
    # integer to steer the early-exit branch. ``unpersist()``
    # returns None — its return value is discarded in the cleanup
    # loop.
    df.take.return_value = []
    df.count.return_value = count_value
    df.unpersist.return_value = None
    return df


# ----------------------------------------------------------------------------
# Local fallback ``spark_session`` fixture.
# ----------------------------------------------------------------------------
# The AAP agent-prompt for this file explicitly declares that
# ``test_validation_lookup_joins(spark_session)`` receives a
# ``spark_session`` fixture. The project-wide ``tests/conftest.py``
# will ultimately provide a shared session-scoped SparkSession
# fixture — but that conftest.py is a separate AAP target scheduled
# for creation in the same one-phase execution. Until it is
# committed, this module-local fixture provides a minimal
# stand-in so the test can be collected, executed, and compiled
# in isolation. When conftest.py is eventually in place, pytest's
# fixture resolution still picks the most-specific definition
# (the local one in this file), which is harmless because none
# of the four tests actually executes PySpark against a live Spark
# cluster — every ``F.col``, ``read_table``, and ``init_glue`` call
# is patched, and the fixture is used only as an opaque token
# passed to ``init_glue``'s mocked return value.
# ----------------------------------------------------------------------------
@pytest.fixture
def spark_session() -> MagicMock:
    """Return a :class:`MagicMock` standing in for a SparkSession.

    All four tests patch ``init_glue`` to bypass real Spark
    provisioning, so no live ``SparkSession`` is required. The
    fixture name matches the AAP-declared signature
    ``test_validation_lookup_joins(spark_session)`` and is
    compatible with any future conftest.py-provided session-scoped
    SparkSession fixture (pytest's fixture-resolution rule honours
    the nearest definition, so the tests remain hermetic under
    either configuration).
    """
    return MagicMock(name="MockSparkSession")


# ----------------------------------------------------------------------------
# Test 1: All six PostgreSQL tables are read, in COBOL order.
# ----------------------------------------------------------------------------
# Verifies that main() issues exactly six ``read_table(spark,
# <table>)`` calls — one per COBOL OPEN paragraph — and that the
# table names and order match the six VSAM clusters declared in
# ``app/cbl/CBTRN01C.cbl`` FILE-CONTROL (lines 28-62):
#
#   SELECT DALYTRAN-FILE  ASSIGN TO DALYTRAN  (sequential)
#   SELECT CUSTOMER-FILE  ASSIGN TO CUSTFILE  (indexed random)
#   SELECT XREF-FILE      ASSIGN TO XREFFILE  (indexed random)
#   SELECT CARD-FILE      ASSIGN TO CARDFILE  (indexed random)
#   SELECT ACCOUNT-FILE   ASSIGN TO ACCTFILE  (indexed random)
#   SELECT TRANSACT-FILE  ASSIGN TO TRANFILE  (indexed random)
#
# These become ``read_table(spark, "daily_transactions")`` ...
# ``read_table(spark, "transactions")`` in the PySpark migration.
#
# The test uses ``count_value=0`` so main() takes the early-exit
# branch after counting rows — this keeps the test hermetic
# (no F.col/join behaviour exercised) and isolates the assertion
# surface to the six read_table invocations.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_reads_all_six_tables(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
) -> None:
    """``main()`` must issue exactly six ``read_table`` calls in COBOL order."""
    # --- Arrange ----------------------------------------------------
    # init_glue returns the canonical 4-tuple (spark, glue_context,
    # job, resolved_args). glue_context and job are ``None`` in
    # local-dev (matches the target module's own fallback path) —
    # the test only needs ``job`` to be a unique object so we can
    # assert commit_job(job) was called with it.
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (mock_spark, None, mock_job, {"JOB_NAME": "test"})

    # Every read_table call returns a DataFrame whose count() is 0
    # — this makes main() take the early-exit branch immediately
    # after counting daily_transactions, skipping the validation
    # joins (and thus any dependency on an active SparkContext
    # for F.col()). The cache() / count() / unpersist() chain is
    # still exercised on all six tables, so the six read_table
    # invocations are fully reachable.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Exactly six read_table calls (one per COBOL OPEN paragraph).
    assert mock_read_table.call_count == 6, (
        f"Expected 6 read_table() calls (one per COBOL OPEN paragraph "
        f"in CBTRN01C.cbl); got {mock_read_table.call_count}"
    )

    # The second positional argument to each read_table call is the
    # PostgreSQL table name (the first is the SparkSession). Extract
    # them in invocation order and verify byte-exact match against
    # the canonical CBTRN01C file-control sequence.
    actual_table_order = tuple(call_record.args[1] for call_record in mock_read_table.call_args_list)
    assert actual_table_order == _EXPECTED_TABLE_READ_ORDER, (
        f"Table read order deviates from CBTRN01C.cbl FILE-CONTROL "
        f"(lines 28-62): expected {_EXPECTED_TABLE_READ_ORDER}, "
        f"got {actual_table_order}"
    )

    # Every read_table() call must be invoked with the SparkSession
    # returned by init_glue — this enforces that main() threads the
    # Spark context correctly through each JDBC read.
    for call_record in mock_read_table.call_args_list:
        assert call_record.args[0] is mock_spark, (
            "Every read_table(spark, <table>) call must pass the SparkSession returned by init_glue"
        )

    # init_glue was called exactly once with the module's canonical
    # job name — this matches the naming convention documented in
    # AAP Section 0.5.1 (``carddemo-<job>`` pattern).
    mock_init_glue.assert_called_once_with(job_name="carddemo-daily-tran-driver")

    # commit_job must be invoked exactly once on the early-exit
    # branch — replaces the COBOL terminal GOBACK at line 197
    # even when no daily transactions are present. The argument
    # must be the same ``job`` object returned by init_glue.
    mock_commit_job.assert_called_once_with(mock_job)


# ----------------------------------------------------------------------------
# Test 2: Validation-lookup join chain mirrors CBTRN01C paragraphs
# 2000-LOOKUP-XREF and 3000-READ-ACCOUNT.
# ----------------------------------------------------------------------------
# The target module collapses CBTRN01C's per-record random reads
# (INDEXED RANDOM VSAM reads in paragraphs 2000-LOOKUP-XREF and
# 3000-READ-ACCOUNT) into a single four-way inner-join chain:
#
#   daily_transactions d
#     INNER JOIN card_cross_references x
#        ON d.dalytran_card_num = x.card_num           -- 2000-LOOKUP-XREF
#     INNER JOIN customers cust
#        ON x.cust_id = cust.cust_id                   -- referential
#     INNER JOIN accounts a
#        ON x.acct_id = a.acct_id                      -- 3000-READ-ACCOUNT
#     INNER JOIN cards card
#        ON d.dalytran_card_num = card.card_num        -- referential
#
# The transactions master table is read for counts/auditing but is
# NOT joined — this test explicitly verifies that, preserving
# CBTRN01C's scope (the driver does not read or modify TRANFILE;
# that's the job of CBTRN02C / posttran_job).
#
# Because PySpark's ``F.col()`` requires an active SparkContext to
# construct Column references (and we deliberately avoid spinning
# up a real SparkSession for unit test speed), we also patch the
# module-local ``F`` alias for ``pyspark.sql.functions``. Under
# this patch, ``F.col("<expr>")`` returns a MagicMock Column
# stand-in whose ``__eq__`` result is passed verbatim to the
# (also-mocked) ``.join()``. We recover the original column
# references from ``mock_f.col.call_args_list``.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_validation_lookup_joins(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    mock_f: MagicMock,
    spark_session: MagicMock,
) -> None:
    """``main()`` must join daily ▹ xref ▹ customers ▹ accounts ▹ cards."""
    # --- Arrange ----------------------------------------------------
    # init_glue returns the (spark, glue_context, job, args) tuple;
    # we thread the fixture-provided spark_session through so the
    # test name's AAP-declared ``spark_session`` parameter is used
    # as a real contract rather than a stylistic formality.
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (spark_session, None, mock_job, {"JOB_NAME": "test"})

    # Create a distinct mock DataFrame for each of the six
    # read_table calls so we can attribute alias() / join() /
    # count() invocations to the correct table. The order MUST
    # match the target module's ``read_table(..., "<table>")``
    # call sequence, which in turn mirrors CBTRN01C file-control.
    # We set count_value=1 so main() bypasses the early-exit
    # branch and exercises the validation-join chain.
    daily_trans_df = _make_mock_df(count_value=1)  # "daily_transactions"
    customers_df = _make_mock_df(count_value=1)  # "customers"
    xref_df = _make_mock_df(count_value=1)  # "card_cross_references"
    cards_df = _make_mock_df(count_value=1)  # "cards"
    accounts_df = _make_mock_df(count_value=1)  # "accounts"
    transactions_df = _make_mock_df(count_value=1)  # "transactions" — not joined

    # side_effect yields these six mocks in order on successive
    # read_table() calls. This is the canonical pytest-mock
    # idiom for "each call gets a different return value".
    mock_read_table.side_effect = [
        daily_trans_df,
        customers_df,
        xref_df,
        cards_df,
        accounts_df,
        transactions_df,
    ]

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # (a) Each of the FIVE tables that participate in the join
    # chain has its ``.alias()`` method called with the exact
    # alias string used in the target module's F.col() expressions.
    # These alias strings are the contract between the alias()
    # calls and the F.col() column references — any drift in one
    # will produce Spark AnalysisException at runtime.
    daily_trans_df.alias.assert_any_call("d")  # 2000-LOOKUP-XREF left side
    xref_df.alias.assert_any_call("x")  # 2000-LOOKUP-XREF right side
    customers_df.alias.assert_any_call("cust")  # customer profile sanity join
    accounts_df.alias.assert_any_call("a")  # 3000-READ-ACCOUNT right side
    cards_df.alias.assert_any_call("card")  # active-card referential check

    # (b) The transactions master table is READ (for counts and
    # auditing) but is NOT joined — this explicitly preserves
    # CBTRN01C's scope. The driver program does not touch
    # TRANFILE beyond opening it; posting to the transaction
    # ledger is the responsibility of CBTRN02C / posttran_job.
    transactions_df.alias.assert_not_called()

    # (c) The join chain is accumulated on the daily_trans_df mock
    # because all chainable methods return ``self``. Four
    # inner joins must have been issued in exactly the
    # documented order: daily ▹ xref → +customers → +accounts
    # → +cards. This count directly maps the FOUR logical
    # lookups collapsed from CBTRN01C.cbl (three explicit — two
    # in 2000-LOOKUP-XREF and one in 3000-READ-ACCOUNT —
    # plus the referential card-master check).
    assert daily_trans_df.join.call_count == 4, (
        f"Expected 4 joins in the validation-lookup chain "
        f"(daily ▹ xref ▹ customers ▹ accounts ▹ cards); "
        f"got {daily_trans_df.join.call_count}"
    )

    # (d) Every join must use ``how="inner"`` — this matches the
    # COBOL ``INVALID KEY → MOVE 4 TO WS-...-READ-STATUS``
    # branches (lines 229-232 for XREF, lines 243-247 for
    # ACCOUNT) which DROP the record from downstream
    # processing. Any ``left`` / ``outer`` drift would violate
    # CBTRN01C's "SKIPPING TRANSACTION ID-" behaviour.
    for join_call in daily_trans_df.join.call_args_list:
        how_value = join_call.kwargs.get("how")
        assert how_value == "inner", (
            f"Validation joins must use inner-join semantics to match COBOL INVALID KEY → skip; got how={how_value!r}"
        )

    # (e) The column references used in the first join pair must
    # include both sides of paragraph 2000-LOOKUP-XREF:
    # ``d.dalytran_card_num`` = ``x.card_num``. We recover the
    # set of F.col() arguments from the call log; these are
    # the raw column-path strings passed to PySpark.
    col_arg_log = [col_call.args[0] for col_call in mock_f.col.call_args_list]

    # 2000-LOOKUP-XREF join condition (line 233 of CBTRN01C.cbl:
    # ``READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM``).
    assert "d.dalytran_card_num" in col_arg_log, (
        "Join 1 (CBTRN01C 2000-LOOKUP-XREF) must reference d.dalytran_card_num on the LEFT side"
    )
    assert "x.card_num" in col_arg_log, "Join 1 (CBTRN01C 2000-LOOKUP-XREF) must reference x.card_num on the RIGHT side"

    # Customer referential join — x.cust_id = cust.cust_id.
    assert "x.cust_id" in col_arg_log, "Customer referential join must reference x.cust_id"
    assert "cust.cust_id" in col_arg_log, "Customer referential join must reference cust.cust_id"

    # 3000-READ-ACCOUNT (line 245: ``READ ACCOUNT-FILE INTO
    # ACCOUNT-RECORD KEY IS FD-ACCT-ID``).
    assert "x.acct_id" in col_arg_log, "Join 3 (CBTRN01C 3000-READ-ACCOUNT) must reference x.acct_id on the LEFT side"
    assert "a.acct_id" in col_arg_log, "Join 3 (CBTRN01C 3000-READ-ACCOUNT) must reference a.acct_id on the RIGHT side"

    # Active-card referential check — d.dalytran_card_num = card.card_num.
    assert "card.card_num" in col_arg_log, "Active-card referential join must reference card.card_num"

    # (f) commit_job is invoked AFTER the full validation flow
    # completes — preserving the COBOL terminal GOBACK (line 197)
    # and JCL MAXCC=0 success signal.
    mock_commit_job.assert_called_once_with(mock_job)


# ----------------------------------------------------------------------------
# Test 3: COBOL DISPLAY messages are preserved byte-exact in logs.
# ----------------------------------------------------------------------------
# Verifies that main() emits the two bookend DISPLAY strings from
# ``app/cbl/CBTRN01C.cbl`` VERBATIM — AAP Section 0.7.1 mandates
# "Preserve all existing functionality exactly as-is." For an
# observability-facing migration this means the CloudWatch log
# stream for the Glue job must contain byte-for-byte the same
# operator-visible marker strings as the original JES2 job log.
#
# Uses pytest's ``caplog`` fixture to capture LogRecords emitted
# by the stdlib ``logging`` framework. Configures capture at INFO
# level (the level at which main() emits both bookend messages).
#
# Additionally exercises the Decimal precision-contract log line
# that main() emits via ``_log_monetary_precision_contract``,
# using the imported ``Decimal`` class to compute the expected
# scale token in the log message. This provides concrete runtime
# coverage of the :class:`Decimal` import declared in this test
# file's external-imports schema.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_log_messages_match_cobol(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Both CBTRN01C bookend DISPLAY messages must appear verbatim in logs."""
    # --- Arrange ----------------------------------------------------
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        MagicMock(name="MockGlueJob"),
        {"JOB_NAME": "test"},
    )

    # Empty daily-transaction feed ⇒ main() takes the early-exit
    # branch, which still emits BOTH bookend messages (lines 160 and
    # 195 of CBTRN01C.cbl) before committing. This keeps the test
    # hermetic: no F.col / SparkContext / join-chain dependencies.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    # caplog.at_level registers a handler on the root logger at INFO
    # severity. Because logger propagation is on by default, every
    # ``logger.info(...)`` call inside main() is captured as a
    # LogRecord regardless of which named logger emits it.
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    # caplog.messages is the list of formatted log messages in the
    # order they were emitted. We match by substring so the
    # structured-JSON envelope added by init_glue's _setup_logging
    # (in production) does not affect test pass/fail.
    captured_messages = list(caplog.messages)

    # (a) Byte-exact CBTRN01C bookend strings. These must NEVER
    # drift from the COBOL source — operator tooling, CloudWatch
    # Logs Insights queries, and runbooks all rely on matching
    # this literal text.
    assert any(_COBOL_START_MSG_EXPECTED in msg for msg in captured_messages), (
        f"CBTRN01C.cbl line 160 DISPLAY 'START OF EXECUTION OF "
        f"PROGRAM CBTRN01C' not found in captured logs: "
        f"{captured_messages!r}"
    )
    assert any(_COBOL_END_MSG_EXPECTED in msg for msg in captured_messages), (
        f"CBTRN01C.cbl line 195 DISPLAY 'END OF EXECUTION OF "
        f"PROGRAM CBTRN01C' not found in captured logs: "
        f"{captured_messages!r}"
    )

    # (b) Ordering — the START message must precede the END
    # message. We locate each message in the caplog.records list
    # and compare indices. Multiple matches are handled by
    # taking the FIRST START and the LAST END, preserving the
    # COBOL MAIN-PARA semantic ("execution begins, then ends").
    start_indices = [i for i, record in enumerate(caplog.records) if _COBOL_START_MSG_EXPECTED in record.getMessage()]
    end_indices = [i for i, record in enumerate(caplog.records) if _COBOL_END_MSG_EXPECTED in record.getMessage()]
    assert start_indices and end_indices, "Both COBOL bookend messages must appear at least once"
    assert start_indices[0] < end_indices[-1], (
        f"CBTRN01C bookend ordering violated — START index "
        f"{start_indices[0]} must come before END index "
        f"{end_indices[-1]}"
    )

    # (c) Monetary precision contract. The target module emits
    # a single informational line documenting that monetary
    # columns flow as Python Decimal with two-decimal-place
    # scale — this is the post-migration equivalent of
    # COBOL ``PIC S9(n)V99`` and is required by AAP §0.7.2.
    # We compute the EXPECTED scale token using the same
    # Decimal API the target module uses, then verify the
    # computed token appears in the log line. This exercises
    # the imported Decimal class in a meaningful assertion.
    #
    # Decimal.as_tuple().exponent is typed as ``int |
    # Literal['n', 'N', 'F']`` (non-finite Decimals return
    # string sentinels for NaN / Infinity). Decimal("0.00")
    # is finite so the exponent is always an int, but we
    # guard with ``isinstance`` to satisfy mypy's strict
    # narrowing and make the invariant explicit to readers.
    raw_scale_token = Decimal("0.00").as_tuple().exponent
    assert isinstance(raw_scale_token, int), (
        "Decimal('0.00') is finite, its exponent must be an int "
        f"(not a non-finite sentinel like 'n'/'N'/'F'); got {raw_scale_token!r}"
    )
    expected_scale_token: int = raw_scale_token  # == -2
    assert expected_scale_token == -2, (
        "Decimal('0.00') must have scale -2 (two decimal places) to match COBOL PIC S9(n)V99 semantics"
    )
    assert _MONETARY_ZERO == Decimal("0.00"), (
        "Target module's _MONETARY_ZERO sentinel must equal "
        "Decimal('0.00') to preserve the monetary precision contract "
        "from AAP Section 0.7.2"
    )
    precision_marker = f"Decimal scale={expected_scale_token}"
    assert any(precision_marker in msg for msg in captured_messages), (
        f"Monetary precision contract log line must include {precision_marker!r}; captured: {captured_messages!r}"
    )


# ----------------------------------------------------------------------------
# Test 4: commit_job(job) is called after processing completes.
# ----------------------------------------------------------------------------
# Verifies that main() invokes ``commit_job(job)`` exactly once,
# passing the SAME Job object that init_glue returned. This
# replaces the COBOL terminal GOBACK statement (line 197 of
# CBTRN01C.cbl) and the JCL ``MAXCC=0`` success signal — both
# of which notify the batch scheduler (JES2 / Step Functions)
# that the job completed successfully and the next stage may run.
#
# Even on the empty-feed early-exit path, commit_job MUST be
# called (a "driver produced zero validation failures" outcome
# is a legitimate success, just as COBOL CBTRN01C would GOBACK
# with MAXCC=0 on an empty DALYTRAN feed).
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
    """``commit_job(job)`` must be invoked after processing (replaces GOBACK)."""
    # --- Arrange ----------------------------------------------------
    # Uniquely identifiable mock Job so we can positively assert
    # that the SAME object flows init_glue → commit_job. Any
    # wrapping / reassignment of ``job`` in main() would cause
    # ``assert_called_once_with(mock_job)`` to fail with a
    # clear diff — this is deliberate.
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        mock_job,
        {"JOB_NAME": "test"},
    )

    # Empty feed ⇒ early-exit path; commit_job is still called.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Exactly one commit_job(job) invocation. A second call would
    # indicate a double-commit bug; zero calls would indicate
    # main() silently swallowed an error path or broke out of
    # the success branch without signalling completion. Both
    # are failure modes; ``assert_called_once_with`` catches
    # both simultaneously.
    mock_commit_job.assert_called_once_with(mock_job)

    # Defensive: commit_job must be the same callable mock we
    # patched into the daily_tran_driver_job namespace. If
    # :func:`main` ever imported a different commit_job (for
    # example, via lazy reimport in some error-handling
    # branch) the patch would miss it and this assertion
    # would catch that drift.
    assert mock_commit_job.call_count == 1, (
        f"commit_job must be invoked exactly once per main() run; got {mock_commit_job.call_count}"
    )
