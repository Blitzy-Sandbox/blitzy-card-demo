# ============================================================================
# CardDemo — Unit tests for read_xref_job (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/CBACT03C.cbl     — Cross-reference diagnostic reader. Opens the
#                                XREFFILE VSAM KSDS cluster (INDEXED
#                                SEQUENTIAL ACCESS), PERFORMs
#                                UNTIL END-OF-FILE = 'Y' issuing
#                                READ XREFFILE-FILE INTO CARD-XREF-RECORD
#                                and emitting DISPLAY CARD-XREF-RECORD on
#                                each iteration. Bookended by the
#                                DISPLAY 'START OF EXECUTION OF PROGRAM
#                                CBACT03C' and DISPLAY 'END OF EXECUTION
#                                OF PROGRAM CBACT03C' statements at lines
#                                71 and 85 of the source.
#   * app/jcl/READXREF.jcl     — JCL job card (``//READXREF JOB ...``) +
#                                EXEC PGM=CBACT03C in STEP05 with
#                                STEPLIB DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.LOADLIB and
#                                XREFFILE DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
#                                plus SYSOUT + SYSPRINT = SYSOUT=*. These
#                                collapse into a single init_glue() +
#                                read_table(spark, "card_cross_references")
#                                invocation pair in the target PySpark
#                                module.
#   * app/cpy/CVACT03Y.cpy     — CARD-XREF-RECORD layout (RECLN 50):
#                                XREF-CARD-NUM  PIC X(16) — 16-byte card
#                                number primary key,
#                                XREF-CUST-ID   PIC 9(09) — 9-digit
#                                customer id foreign key to customers,
#                                XREF-ACCT-ID   PIC 9(11) — 11-digit
#                                account id foreign key to accounts,
#                                FILLER         PIC X(14) — VSAM slack
#                                padding dropped in Aurora.
# ----------------------------------------------------------------------------
# Target module under test: src/batch/jobs/read_xref_job.py.
# The PySpark Glue job replaces CBACT03C.cbl + READXREF.jcl, collapsing
# the COBOL OPEN / READ-UNTIL-EOF / CLOSE sequence into a single
# read_table(spark, "card_cross_references") + cache + collect
# pipeline, and the terminal GOBACK + JCL MAXCC=0 success signal into a
# commit_job(job) call that notifies Step Functions of stage success.
#
# These tests verify behavioral parity with CBACT03C.cbl by exercising
# the main() entry point with mocked Glue / JDBC dependencies — i.e.,
# they validate the *behavior* (single table read, per-record iteration,
# COBOL DISPLAY message preservation, commit signal) without requiring
# an actual AWS Glue runtime, a live Aurora PostgreSQL cluster, or a
# full local SparkSession. The six test cases map directly to the AAP
# agent-prompt's Phase 2 check-list for this file:
#
#   Test case                              | Verifies (COBOL source mapping)
#   ----------------------------------------------------------------------
#   test_reads_card_cross_references_table | OPEN INPUT XREFFILE-FILE
#                                          | (paragraph 0000-XREFFILE-OPEN,
#                                          | lines 118-134 of CBACT03C.cbl)
#                                          | + JCL //XREFFILE DD DISP=SHR
#                                          | (line 25 of READXREF.jcl)
#                                          | → read_table(spark,
#                                          |    "card_cross_references").
#   test_logs_start_message                | DISPLAY 'START OF EXECUTION
#                                          | OF PROGRAM CBACT03C' (line 71
#                                          | of CBACT03C.cbl) preserved
#                                          | byte-exact in the CloudWatch
#                                          | log stream.
#   test_logs_end_message                  | DISPLAY 'END OF EXECUTION
#                                          | OF PROGRAM CBACT03C' (line 85
#                                          | of CBACT03C.cbl) preserved
#                                          | byte-exact in the CloudWatch
#                                          | log stream.
#   test_logs_record_count                 | The PERFORM UNTIL END-OF-FILE
#                                          | loop (lines 74-81) iterates
#                                          | every row of the CARDXREF
#                                          | cluster; translated to PySpark
#                                          | as a .count() call whose
#                                          | value is surfaced in a
#                                          | dedicated log line for
#                                          | operator verification.
#   test_iterates_all_records              | DISPLAY CARD-XREF-RECORD
#                                          | (line 78 and line 96) inside
#                                          | the 1000-XREFFILE-GET-NEXT
#                                          | read loop → one
#                                          | logger.info() call per row
#                                          | materialised via
#                                          | DataFrame.collect().
#   test_commit_job_called                 | Terminal GOBACK (line 87) +
#                                          | JCL MAXCC=0 (READXREF step
#                                          | completion) → commit_job(job)
#                                          | signalling Step Functions
#                                          | success.
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
"""Unit tests for :mod:`src.batch.jobs.read_xref_job`.

Validates behavioral parity with the original COBOL diagnostic program
``app/cbl/CBACT03C.cbl`` plus its launcher ``app/jcl/READXREF.jcl``.
CBACT03C is a *diagnostic / utility* batch program — it opens the
CARDXREF VSAM KSDS cluster, reads every record sequentially, and
DISPLAYs each one to SYSOUT. It performs no data modification and has
no downstream dependencies: its sole purpose is to let an operator
verify the current contents of the cross-reference index after a data
migration or before launching the production batch pipeline.

COBOL -> Python Verification Surface
------------------------------------
==================================================  ==========================================
COBOL paragraph / statement                         Python test (this module)
==================================================  ==========================================
``OPEN INPUT XREFFILE-FILE`` L120                   ``test_reads_card_cross_references_table``
``READ XREFFILE-FILE INTO ...`` L93                 ``test_iterates_all_records``
``DISPLAY CARD-XREF-RECORD`` L78, L96               ``test_iterates_all_records``
``PERFORM UNTIL END-OF-FILE = 'Y'`` L74-81          ``test_logs_record_count``
``DISPLAY 'START OF EXECUTION ...CBACT03C'`` L71    ``test_logs_start_message``
``DISPLAY 'END OF EXECUTION ...CBACT03C'`` L85      ``test_logs_end_message``
``GOBACK`` L87 + JCL MAXCC=0                        ``test_commit_job_called``
==================================================  ==========================================

Mocking Strategy
----------------
The target module ``src.batch.jobs.read_xref_job`` imports its three
runtime dependencies at module-load time via::

    from src.batch.common.db_connector import read_table
    from src.batch.common.glue_context import commit_job, init_glue

Because the ``from ... import ...`` form creates new name bindings in
the *importing* module's namespace, every :func:`unittest.mock.patch`
call MUST target the ``read_xref_job`` namespace — NOT the
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
* AAP §0.2.2 — Batch Program Classification (CBACT03C listed as a
  diagnostic reader utility alongside CBACT01C, CBACT02C, CBCUS01C).
* AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue).
* AAP §0.5.1 — File-by-File Transformation Plan (read_xref_job entry).
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
#                       per-CARD-XREF-RECORD DISPLAY equivalent are
#                       emitted.
# ``patch``, ``MagicMock``, ``call`` — :mod:`unittest.mock` primitives.
#                       * ``patch`` is used as a decorator on every test
#                         to replace ``init_glue`` / ``read_table`` /
#                         ``commit_job`` with mocks in the
#                         ``read_xref_job`` module's own namespace.
#                       * ``MagicMock`` creates the chainable DataFrame
#                         stand-ins returned by the mocked
#                         ``read_table``, plus the SparkSession / Glue
#                         Job object tuples consumed by main().
#                       * ``call`` is imported to make positional-vs-
#                         keyword argument matching explicit and
#                         grep-able in the test body (e.g.,
#                         ``call(mock_spark, "card_cross_references")``).
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
# ``main`` is the PySpark Glue job entry point that replaces CBACT03C's
# PROCEDURE DIVISION paragraph-set. Calling ``main()`` under patched
# dependencies exercises the full CBACT03C equivalent flow:
#
#   init_glue    (replaces JCL JOB + EXEC PGM=CBACT03C + STEPLIB)
#     → DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'
#     → read_table(spark, "card_cross_references")
#                (replaces OPEN INPUT XREFFILE-FILE + READXREF.jcl
#                 //XREFFILE DD DISP=SHR,
#                 DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)
#     → df.cache() + df.count()
#                (single count-action materialisation of the lazy
#                 DataFrame; replaces the per-record READ + EOF
#                 check in paragraph 1000-XREFFILE-GET-NEXT)
#     → for row in df.collect(): logger.info("CARD-XREF-RECORD: ...")
#                (replaces DISPLAY CARD-XREF-RECORD inside the
#                 PERFORM UNTIL END-OF-FILE loop, lines 74-81)
#     → df.unpersist()
#                (replaces CLOSE XREFFILE-FILE in paragraph
#                 9000-XREFFILE-CLOSE, lines 136-152)
#     → DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'
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
from src.batch.jobs.read_xref_job import main

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text constants from ``app/cbl/CBACT03C.cbl``.
# These mirror the target module's ``_COBOL_START_MSG`` /
# ``_COBOL_END_MSG`` private constants — duplicated here rather than
# imported so the tests independently enforce the byte-exact string
# and would FAIL if the target module ever drifted from the COBOL
# source. This enforcement is precisely the point of behavioral-
# parity testing for the mainframe-to-cloud migration (AAP §0.7.1:
# "Preserve all existing functionality exactly as-is").
#
# Line references are to ``app/cbl/CBACT03C.cbl`` as committed:
#   * Line 71:   DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.
#   * Line 85:   DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.
# ----------------------------------------------------------------------------
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBACT03C"
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBACT03C"

# ----------------------------------------------------------------------------
# Canonical PostgreSQL table name for the CARDXREF VSAM cluster. Maps
# the JCL DD statement ``//XREFFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`` (READXREF.jcl lines 25-26)
# to the Aurora PostgreSQL table as defined in
# ``db/migrations/V1__schema.sql`` and canonicalized by
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["CARDXREF"]``.
# Declared here as a module-level constant so the single-table-read
# assertion in ``test_reads_card_cross_references_table`` is auditable.
# ----------------------------------------------------------------------------
_EXPECTED_TABLE_NAME: str = "card_cross_references"

# ----------------------------------------------------------------------------
# Canonical Glue job name for the CARDXREF diagnostic reader. The
# target module declares this as ``_JOB_NAME = "carddemo-read-xref"``;
# we duplicate here rather than import so any drift in the naming
# convention (from the ``carddemo-<job>`` pattern documented in
# AAP §0.5.1) is caught by this test suite.
# ----------------------------------------------------------------------------
_EXPECTED_JOB_NAME: str = "carddemo-read-xref"

# ----------------------------------------------------------------------------
# read_xref_job-namespace patch targets — the module-under-test re-binds
# ``init_glue`` / ``read_table`` / ``commit_job`` via ``from
# src.batch.common... import ...``. Every ``patch()`` call must target
# the ``read_xref_job`` namespace, NOT the original ``glue_context`` /
# ``db_connector`` definition sites. Centralised as constants to avoid
# typos across the six test functions and to make the mocking strategy
# grep-able from a single location.
# ----------------------------------------------------------------------------
_PATCH_INIT_GLUE = "src.batch.jobs.read_xref_job.init_glue"
_PATCH_READ_TABLE = "src.batch.jobs.read_xref_job.read_table"
_PATCH_COMMIT_JOB = "src.batch.jobs.read_xref_job.commit_job"


# ----------------------------------------------------------------------------
# Helper: mock DataFrame factory.
# ----------------------------------------------------------------------------
# The target module's main() chains PySpark DataFrame operations
# fluently — specifically the pattern::
#
#     xref_df = read_table(spark, _TABLE_NAME)
#     xref_df = xref_df.cache()
#     record_count = xref_df.count()
#     for row in xref_df.collect():
#         logger.info("CARD-XREF-RECORD: %s", row.asDict())
#     xref_df.unpersist()
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
def _make_mock_df(count_value: int = 0, rows: list[MagicMock] | None = None) -> MagicMock:
    """Build a chainable mock DataFrame for use with patched ``read_table``.

    Parameters
    ----------
    count_value
        Integer returned by the mock DataFrame's ``count()`` method.
        Setting this to ``0`` triggers the main() empty-table branch
        at the ``if record_count > 0`` check (target module line
        ~406), which skips the per-row iteration and logs the
        "empty table" informational message instead. Setting it to
        a positive value drives main() through the full
        ``collect()`` iteration loop — matching the COBOL
        PERFORM UNTIL END-OF-FILE loop (lines 74-81).
    rows
        List of row-like objects returned by the mock DataFrame's
        ``collect()`` method. Each element should be a
        :class:`MagicMock` whose ``asDict()`` method returns the
        CARD-XREF-RECORD dict to be logged. Defaults to an empty
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
    # reassignment pattern (``xref_df = xref_df.cache()``) preserves
    # the tracked mock. Without this, the subsequent ``xref_df.count()``
    # and ``xref_df.collect()`` would operate on an auto-generated
    # child mock with no configured behavior.
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
# ``for row in xref_df.collect(): logger.info("CARD-XREF-RECORD: %s",
# row.asDict())`` so the mocks returned by ``collect()`` must
# implement ``asDict()``. We build stand-ins by wrapping a MagicMock
# and configuring its ``asDict.return_value`` to an appropriate dict
# shaped like the CARD-XREF-RECORD layout (``app/cpy/CVACT03Y.cpy``).
#
# The three non-FILLER fields from the COBOL record layout are:
#   * XREF-CARD-NUM  PIC X(16)  → card_num   (string, 16-digit PAN)
#   * XREF-CUST-ID   PIC 9(09)  → cust_id    (int,  9-digit)
#   * XREF-ACCT-ID   PIC 9(11)  → acct_id    (int, 11-digit)
#
# The FILLER PIC X(14) is NOT represented in the Aurora schema (pure
# VSAM slack padding) per the target module's docstring (lines 65-68
# of ``src/batch/jobs/read_xref_job.py``), so it is omitted from the
# dict returned by asDict().
# ----------------------------------------------------------------------------
def _make_mock_row(card_num: str, cust_id: int, acct_id: int) -> MagicMock:
    """Build a mock PySpark Row with the CARD-XREF-RECORD layout.

    Parameters
    ----------
    card_num
        16-character primary account number. Matches COBOL
        XREF-CARD-NUM  PIC X(16).
    cust_id
        9-digit customer ID foreign key. Matches COBOL
        XREF-CUST-ID   PIC 9(09).
    acct_id
        11-digit account ID foreign key. Matches COBOL
        XREF-ACCT-ID   PIC 9(11).

    Returns
    -------
    MagicMock
        A mock Row whose ``asDict()`` method returns the
        three-field dict ``{"card_num": card_num, "cust_id":
        cust_id, "acct_id": acct_id}``.
    """
    row = MagicMock(name=f"MockRow(card_num={card_num})")
    row.asDict.return_value = {
        "card_num": card_num,
        "cust_id": cust_id,
        "acct_id": acct_id,
    }
    return row


# ----------------------------------------------------------------------------
# Test 1: main() reads the card_cross_references PostgreSQL table.
# ----------------------------------------------------------------------------
# Verifies that main() issues exactly one ``read_table(spark,
# "card_cross_references")`` call — the PySpark equivalent of the
# COBOL OPEN INPUT XREFFILE-FILE statement (paragraph 0000-XREFFILE-
# OPEN, line 120) combined with the JCL //XREFFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS binding (READXREF.jcl lines
# 25-26). The assertion catches three distinct failure modes:
#   1. Wrong table name — drift from "card_cross_references" would
#      break the mainframe-to-cloud VSAM-to-PostgreSQL mapping.
#   2. Extra table reads — the diagnostic reader must touch ONLY the
#      CARDXREF cluster; reading any other table would violate
#      CBACT03C's scope (it is strictly single-file).
#   3. Wrong SparkSession — read_table must receive the SparkSession
#      returned by init_glue, not a fresh/alternative one.
#
# The test also asserts init_glue was called with the canonical
# ``carddemo-read-xref`` job name, preserving the naming convention
# documented in AAP §0.5.1.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_reads_card_cross_references_table(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
) -> None:
    """``main()`` must call ``read_table(spark, "card_cross_references")``."""
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
    # Exactly ONE read_table call — CBACT03C is a single-file
    # diagnostic reader (FILE-CONTROL declares only XREFFILE-FILE).
    assert mock_read_table.call_count == 1, (
        f"Expected exactly 1 read_table() call (CBACT03C is a "
        f"single-file diagnostic reader — FILE-CONTROL declares "
        f"only XREFFILE-FILE); got {mock_read_table.call_count}"
    )

    # The call must use the canonical SparkSession + table-name pair.
    # ``call(mock_spark, "card_cross_references")`` is the literal
    # positional-argument signature expected by the target module's
    # ``read_table(spark, _TABLE_NAME)`` invocation. This assertion
    # would FAIL on any of:
    #   * Wrong SparkSession threaded through (different object id)
    #   * Wrong table name (drift from "card_cross_references")
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
# Test 2: COBOL start DISPLAY message is preserved byte-exact in logs.
# ----------------------------------------------------------------------------
# Verifies that main() emits the ``'START OF EXECUTION OF PROGRAM
# CBACT03C'`` bookend string from line 71 of ``app/cbl/CBACT03C.cbl``
# VERBATIM — AAP §0.7.1 mandates "Preserve all existing functionality
# exactly as-is." For an observability-facing migration this means the
# CloudWatch log stream for the Glue job must contain byte-for-byte
# the same operator-visible marker string as the original JES2 job log
# for CBACT03C. CloudWatch Logs Insights queries, alerting rules, and
# operator runbooks that key off this exact literal must continue to
# function after the mainframe-to-cloud migration.
#
# Uses pytest's ``caplog`` fixture to capture LogRecords emitted by
# the stdlib ``logging`` framework. Configures capture at INFO level
# (the severity at which main() emits both bookend messages). Because
# logger propagation is on by default, ``logger.info(...)`` calls
# inside main() propagate to the root logger and are captured
# regardless of which named logger emits them.
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
    """The CBACT03C 'START OF EXECUTION' DISPLAY must be preserved in logs."""
    # --- Arrange ----------------------------------------------------
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        MagicMock(name="MockGlueJob"),
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Empty card_cross_references ⇒ main() takes the empty-table
    # branch, which STILL emits the START bookend (and the END
    # bookend — verified separately in test_logs_end_message). This
    # keeps the test hermetic: no ``collect()`` / row iteration
    # dependencies.
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
    # order they were emitted. We match by substring so that any
    # structured-JSON envelope added by init_glue's _setup_logging
    # (in production) does not affect test pass/fail.
    captured_messages = list(caplog.messages)

    # Byte-exact CBACT03C START bookend string. This must NEVER drift
    # from the COBOL source — operator tooling, CloudWatch Logs
    # Insights queries, and runbooks all rely on matching this
    # literal text. If the target module's ``_COBOL_START_MSG``
    # constant is ever mutated, this assertion fails immediately
    # with a diff showing the captured vs expected text.
    assert any(
        _COBOL_START_MSG_EXPECTED in msg for msg in captured_messages
    ), (
        f"CBACT03C.cbl line 71 DISPLAY 'START OF EXECUTION OF "
        f"PROGRAM CBACT03C' not found in captured logs: "
        f"{captured_messages!r}"
    )

    # Defensive: the START message must be emitted at INFO level (not
    # DEBUG — which would be dropped by the production log handler
    # config — and not WARNING/ERROR — which would trigger spurious
    # alerting). We walk caplog.records to locate the START message
    # and verify its levelno matches the expected logging.INFO.
    start_records = [
        record
        for record in caplog.records
        if _COBOL_START_MSG_EXPECTED in record.getMessage()
    ]
    assert start_records, (
        f"No LogRecord containing {_COBOL_START_MSG_EXPECTED!r} was "
        f"captured; cannot verify emission level"
    )
    for record in start_records:
        assert record.levelno == logging.INFO, (
            f"CBACT03C START message must be emitted at INFO level "
            f"(not {logging.getLevelName(record.levelno)}); emission "
            f"at any other level would either be dropped by the "
            f"production log config or trigger spurious alerting"
        )


# ----------------------------------------------------------------------------
# Test 3: COBOL end DISPLAY message is preserved byte-exact in logs.
# ----------------------------------------------------------------------------
# Mirror of ``test_logs_start_message`` but for the CBACT03C END
# bookend (line 85 of ``app/cbl/CBACT03C.cbl``). The two tests are
# kept separate (rather than merged into a single
# ``test_bookend_messages``) so the AAP agent-prompt's six-test
# check-list maps 1:1 to six pytest functions; a failure on either
# bookend points directly at which DISPLAY drifted from the COBOL
# source.
#
# Additionally asserts that the START message precedes the END
# message in emission order — preserving the COBOL PROCEDURE DIVISION
# control flow (execution begins, body runs, execution ends).
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
    """The CBACT03C 'END OF EXECUTION' DISPLAY must be preserved in logs."""
    # --- Arrange ----------------------------------------------------
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        MagicMock(name="MockGlueJob"),
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Empty card_cross_references ⇒ main() reaches the END bookend
    # via the empty-table branch. The END message is emitted AFTER
    # the unpersist() cleanup and BEFORE commit_job(), regardless of
    # whether the iteration loop ran — so this empty-table test is
    # sufficient to verify the END bookend contract.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    captured_messages = list(caplog.messages)

    # Byte-exact CBACT03C END bookend string.
    assert any(
        _COBOL_END_MSG_EXPECTED in msg for msg in captured_messages
    ), (
        f"CBACT03C.cbl line 85 DISPLAY 'END OF EXECUTION OF "
        f"PROGRAM CBACT03C' not found in captured logs: "
        f"{captured_messages!r}"
    )

    # Defensive: END message must be emitted at INFO level.
    end_records = [
        record
        for record in caplog.records
        if _COBOL_END_MSG_EXPECTED in record.getMessage()
    ]
    assert end_records, (
        f"No LogRecord containing {_COBOL_END_MSG_EXPECTED!r} was "
        f"captured; cannot verify emission level"
    )
    for record in end_records:
        assert record.levelno == logging.INFO, (
            f"CBACT03C END message must be emitted at INFO level; got "
            f"{logging.getLevelName(record.levelno)}"
        )

    # Ordering — the START message must precede the END message.
    # We locate each message in the caplog.records list and compare
    # indices. Multiple matches are handled by taking the FIRST
    # START and the LAST END, preserving the COBOL PROCEDURE DIVISION
    # semantic ("execution begins, then ends").
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
    assert start_indices and end_indices, (
        "Both CBACT03C bookend messages (START and END) must appear "
        "at least once in the captured log stream"
    )
    assert start_indices[0] < end_indices[-1], (
        f"CBACT03C bookend ordering violated — START record index "
        f"{start_indices[0]} must precede END record index "
        f"{end_indices[-1]} (COBOL PROCEDURE DIVISION flow: line 71 "
        f"before line 85)"
    )


# ----------------------------------------------------------------------------
# Test 4: main() logs the DataFrame record count.
# ----------------------------------------------------------------------------
# Verifies that main() logs a record-count line whose value matches
# the ``count()`` value returned by the mock DataFrame. This is the
# PySpark equivalent of the COBOL PERFORM UNTIL END-OF-FILE loop's
# implicit "count of records processed" — in the mainframe, operators
# would count lines in the JES SYSOUT spool to verify the iteration
# exhaustively read the cluster; in the cloud, a single structured
# log line surfaces the count directly.
#
# The 50-row fixture used in this test matches the seeded
# ``card_cross_references`` table in ``db/migrations/V3__seed_data.sql``
# (per the setup status log: "626 rows loaded (50 accounts, 50 cards,
# 50 customers, 50 xrefs, ...)"). Any future change to the fixture
# row count is trivially reflected by updating the ``count_value=50``
# argument below.
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
    """``main()`` must log the card_cross_references record count."""
    # --- Arrange ----------------------------------------------------
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        MagicMock(name="MockGlueJob"),
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Seed the mock DataFrame with count=50, matching the fixture
    # size in ``db/migrations/V3__seed_data.sql``. We also supply a
    # non-empty rows list so main() takes the > 0 branch (iteration
    # loop); this is the code path actually exercised in production
    # against the seeded database.
    expected_count = 50
    rows = [
        _make_mock_row(
            # 16-char card_num (PIC X(16)): literal zero-padded
            # format commonly seen in the seeded fixture.
            card_num=f"4111{1000000000 + i:012d}",
            # 9-digit cust_id (PIC 9(09)).
            cust_id=100000000 + i,
            # 11-digit acct_id (PIC 9(11)).
            acct_id=10000000000 + i,
        )
        for i in range(expected_count)
    ]
    mock_read_table.return_value = _make_mock_df(
        count_value=expected_count, rows=rows
    )

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    captured_messages = list(caplog.messages)

    # At least ONE log message must surface the record count to
    # operators. The target module uses the format string
    # ``"card_cross_references record count: %d"`` — we search for
    # the integer 50 rendered as its decimal string in any log
    # message that also mentions "record count" so we are not
    # confused by unrelated numeric content (e.g., a timestamp).
    count_messages = [
        msg
        for msg in captured_messages
        if "record count" in msg.lower() and str(expected_count) in msg
    ]
    assert count_messages, (
        f"Expected at least one log message containing 'record count' "
        f"and the integer {expected_count!r}; captured messages: "
        f"{captured_messages!r}"
    )

    # Defensive: the DataFrame's count() method must have been called
    # exactly once (the target module caches the DataFrame once and
    # calls count() once to drive the > 0 branch). A higher count
    # would indicate the target module re-executes the JDBC query
    # multiple times, wasting Aurora round-trips. A zero count would
    # indicate main() skipped the count entirely and the log line
    # came from somewhere else — a semantic drift we want to catch.
    mock_df = mock_read_table.return_value
    assert mock_df.count.call_count == 1, (
        f"DataFrame.count() must be called exactly once (the target "
        f"module caches + counts once); got {mock_df.count.call_count}"
    )

    # count() must be called AFTER cache() so the subsequent collect()
    # operation shares the same materialised DataFrame. If the order
    # were reversed, the JDBC query would run twice.
    mock_df.cache.assert_called_once()


# ----------------------------------------------------------------------------
# Test 5: main() iterates every row via DataFrame.collect().
# ----------------------------------------------------------------------------
# Verifies that main() emits one log line per row returned by
# ``DataFrame.collect()`` — the PySpark equivalent of the COBOL
# ``DISPLAY CARD-XREF-RECORD`` statement inside the
# ``PERFORM UNTIL END-OF-FILE`` loop (lines 74-81). This is the core
# diagnostic behaviour of CBACT03C: dump every record to SYSOUT.
#
# Uses ``DataFrame.collect()`` rather than a streaming iteration
# because:
#   (a) CARDXREF is a lookup table of O(N=cards) rows with small
#       per-row footprint (card_num + cust_id + acct_id ≈ 36 bytes
#       excluding VSAM slack).
#   (b) The diagnostic purpose of the original program is precisely
#       to dump every row to SYSOUT — so materialising the full
#       result set driver-side is appropriate and faithful to the
#       original mainframe execution model.
#
# The test uses 3 distinct rows with distinguishable card_num values
# so per-row invocations can be attributed unambiguously, and verifies
# both:
#   * Each row's asDict() is called exactly once.
#   * The log stream contains exactly 3 "CARD-XREF-RECORD:" lines.
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
    """``main()`` must log one line per row from ``DataFrame.collect()``."""
    # --- Arrange ----------------------------------------------------
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        MagicMock(name="MockGlueJob"),
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Three distinguishable rows — distinct card_num / cust_id /
    # acct_id so per-row log line attribution is unambiguous. Any
    # drift in the iteration (e.g., skipping the last row, emitting
    # duplicates, re-ordering) produces a different log stream and
    # the assertions below will FAIL with a clear diff.
    row1 = _make_mock_row(
        card_num="4111111111111111", cust_id=100000001, acct_id=10000000001
    )
    row2 = _make_mock_row(
        card_num="4111111111111112", cust_id=100000002, acct_id=10000000002
    )
    row3 = _make_mock_row(
        card_num="4111111111111113", cust_id=100000003, acct_id=10000000003
    )
    rows = [row1, row2, row3]

    mock_read_table.return_value = _make_mock_df(
        count_value=len(rows), rows=rows
    )

    # --- Act --------------------------------------------------------
    with caplog.at_level(logging.INFO):
        main()

    # --- Assert -----------------------------------------------------
    captured_messages = list(caplog.messages)

    # (a) The target module emits "CARD-XREF-RECORD: <dict>" on each
    # iteration. Count how many captured messages match that
    # pattern; the result must equal the number of rows in the
    # mock DataFrame. Using substring match so the exact format
    # string used by logger.info(...) is implementation-flexible
    # (e.g., the target module could reasonably use
    # "CARD-XREF-RECORD: %s" or "CARD-XREF-RECORD: {dict}" without
    # changing the contract).
    per_record_log_lines = [
        msg for msg in captured_messages if "CARD-XREF-RECORD" in msg
    ]
    assert len(per_record_log_lines) == len(rows), (
        f"Expected exactly {len(rows)} 'CARD-XREF-RECORD:' log lines "
        f"(one per row returned by collect()); got "
        f"{len(per_record_log_lines)}. Captured messages: "
        f"{captured_messages!r}"
    )

    # (b) Each row's asDict() must have been called exactly once —
    # the target module invokes row.asDict() to render the record
    # as a structured JSON payload for CloudWatch Logs Insights
    # querying. Additional invocations would indicate the target
    # module is re-materialising the row dict unnecessarily
    # (performance regression); zero invocations would indicate
    # row iteration is happening but asDict() is not, which would
    # produce opaque logs for operators.
    for row in rows:
        row.asDict.assert_called_once()

    # (c) Every distinct row's card_num must appear somewhere in the
    # log stream — this confirms the iteration processed EACH row
    # distinctly (rather than, e.g., iterating the first row N
    # times). We scan the joined log text because the target
    # module's exact format string for rendering the dict is not
    # prescribed beyond "contains the dict contents."
    joined_log = "\n".join(captured_messages)
    for row in rows:
        expected_card_num = row.asDict.return_value["card_num"]
        assert expected_card_num in joined_log, (
            f"Row with card_num={expected_card_num!r} not found in "
            f"captured log stream; iteration may have skipped the "
            f"row. Log content: {joined_log!r}"
        )

    # (d) DataFrame.collect() must be called exactly once (the
    # target module materialises the DataFrame once and iterates
    # the driver-side list). Multiple collect() calls would
    # duplicate JDBC traffic for no benefit.
    mock_df = mock_read_table.return_value
    assert mock_df.collect.call_count == 1, (
        f"DataFrame.collect() must be called exactly once; "
        f"got {mock_df.collect.call_count}"
    )


# ----------------------------------------------------------------------------
# Test 6: commit_job(job) is called after processing completes.
# ----------------------------------------------------------------------------
# Verifies that main() invokes ``commit_job(job)`` exactly once,
# passing the SAME Job object that init_glue returned. This replaces
# the COBOL terminal GOBACK statement (line 87 of CBACT03C.cbl) and
# the JCL ``MAXCC=0`` success signal — both of which notify the batch
# scheduler (JES2 / Step Functions) that the job completed
# successfully and the next stage may run.
#
# Even on the empty-feed path (card_cross_references has zero rows),
# commit_job MUST be called — a "diagnostic reader produced zero
# records" outcome is a legitimate success (empty-but-valid table is
# semantically distinct from a JDBC error). This mirrors COBOL
# CBACT03C's behaviour: the PERFORM UNTIL END-OF-FILE loop exits
# cleanly on the first APPL-EOF and the GOBACK fires with MAXCC=0.
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
    # Uniquely identifiable mock Job so we can positively assert that
    # the SAME object flows init_glue → commit_job. Any wrapping /
    # reassignment of ``job`` in main() would cause
    # ``assert_called_once_with(mock_job)`` to fail with a clear diff
    # showing the actual vs expected objects. This is deliberate — if
    # a future refactor of main() accidentally shadows the ``job``
    # local with a different object, this test catches it.
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Empty feed ⇒ happy-path ends via the empty-table branch;
    # commit_job is still called. This test deliberately exercises
    # the empty-table path (rather than the iteration path) to
    # demonstrate that commit_job is reached regardless of table
    # size — CBACT03C's behaviour on an empty cluster is equivalent
    # to its behaviour on a 50-row cluster: GOBACK with MAXCC=0.
    mock_read_table.return_value = _make_mock_df(count_value=0)

    # --- Act --------------------------------------------------------
    main()

    # --- Assert -----------------------------------------------------
    # Exactly one commit_job(job) invocation. A second call would
    # indicate a double-commit bug (Glue would reject the second
    # commit as "already committed"); zero calls would indicate
    # main() silently swallowed an error path or broke out of the
    # success branch without signalling completion. Both are failure
    # modes; ``assert_called_once_with`` catches both simultaneously
    # and also validates the argument identity.
    mock_commit_job.assert_called_once_with(mock_job)

    # Defensive: commit_job must be the same callable mock we patched
    # into the read_xref_job namespace. If :func:`main` ever imported
    # a different commit_job (for example, via lazy reimport in some
    # error-handling branch) the patch would miss it and this
    # assertion would catch that drift by showing call_count != 1.
    assert mock_commit_job.call_count == 1, (
        f"commit_job must be invoked exactly once per main() run "
        f"(matches the single JCL MAXCC=0 signal at step completion); "
        f"got {mock_commit_job.call_count}"
    )

    # The call's single positional argument must be the ``job``
    # object returned by init_glue. ``call(mock_job)`` captures that
    # literal signature — keyword arguments would indicate drift in
    # the commit_job API contract with src/batch/common/glue_context.py.
    assert mock_commit_job.call_args_list == [call(mock_job)], (
        f"commit_job invocation history must be exactly "
        f"[call(<job from init_glue>)]; got "
        f"{mock_commit_job.call_args_list}"
    )
