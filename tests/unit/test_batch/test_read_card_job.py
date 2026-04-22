# ============================================================================
# CardDemo — Unit tests for read_card_job (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/CBACT02C.cbl     — Card diagnostic reader. Opens the
#                                CARDFILE VSAM KSDS cluster (INDEXED
#                                SEQUENTIAL ACCESS), PERFORMs
#                                UNTIL END-OF-FILE = 'Y' issuing
#                                READ CARDFILE-FILE INTO CARD-RECORD
#                                and emitting DISPLAY CARD-RECORD on
#                                each iteration. Bookended by the
#                                DISPLAY 'START OF EXECUTION OF PROGRAM
#                                CBACT02C' and DISPLAY 'END OF EXECUTION
#                                OF PROGRAM CBACT02C' statements at lines
#                                71 and 85 of the source.
#   * app/jcl/READCARD.jcl     — JCL job card (``//READCARD JOB ...``) +
#                                EXEC PGM=CBACT02C in STEP05 with
#                                STEPLIB DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.LOADLIB and
#                                CARDFILE DD DISP=SHR,
#                                DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
#                                plus SYSOUT + SYSPRINT = SYSOUT=*. These
#                                collapse into a single init_glue() +
#                                read_table(spark, "cards") invocation
#                                pair in the target PySpark module.
#   * app/cpy/CVACT02Y.cpy     — CARD-RECORD layout (RECLN 150):
#                                CARD-NUM                   PIC X(16) —
#                                  16-character card number primary key
#                                  (card-PAN; stored encrypted at rest
#                                  per the project's data-protection
#                                  policy),
#                                CARD-ACCT-ID               PIC 9(11) —
#                                  11-digit account id foreign key to
#                                  accounts,
#                                CARD-CVV-CD                PIC 9(03) —
#                                  3-digit CVV (encrypted at rest),
#                                CARD-EMBOSSED-NAME         PIC X(50) —
#                                  cardholder name as embossed on card,
#                                CARD-EXPIRAION-DATE        PIC X(10) —
#                                  card expiration date (note the
#                                  misspelling "EXPIRAION" is authentic
#                                  to the original COBOL copybook and
#                                  is canonicalized to ``expiration_date``
#                                  in the Aurora PostgreSQL schema),
#                                CARD-ACTIVE-STATUS         PIC X(01) —
#                                  single-character active flag
#                                  ('Y' / 'N'),
#                                FILLER                     PIC X(59) —
#                                  VSAM slack padding dropped in Aurora.
# ----------------------------------------------------------------------------
# Target module under test: src/batch/jobs/read_card_job.py.
# The PySpark Glue job replaces CBACT02C.cbl + READCARD.jcl, collapsing
# the COBOL OPEN / READ-UNTIL-EOF / CLOSE sequence into a single
# read_table(spark, "cards") + cache + collect pipeline, and the
# terminal GOBACK + JCL MAXCC=0 success signal into a commit_job(job)
# call that notifies Step Functions of stage success.
#
# These tests verify behavioral parity with CBACT02C.cbl by exercising
# the main() entry point with mocked Glue / JDBC dependencies — i.e.,
# they validate the *behavior* (single table read, per-record iteration,
# COBOL DISPLAY message preservation, commit signal) without requiring
# an actual AWS Glue runtime, a live Aurora PostgreSQL cluster, or a
# full local SparkSession. The six test cases map directly to the AAP
# agent-prompt's Phase 2 check-list for this file:
#
#   Test case                     | Verifies (COBOL source mapping)
#   ----------------------------------------------------------------------
#   test_reads_cards_table        | OPEN INPUT CARDFILE-FILE
#                                 | (paragraph 0000-CARDFILE-OPEN, lines
#                                 | 118-134 of CBACT02C.cbl) + JCL
#                                 | //CARDFILE DD DISP=SHR (line 25 of
#                                 | READCARD.jcl) → read_table(spark,
#                                 | "cards").
#   test_logs_start_message       | DISPLAY 'START OF EXECUTION OF
#                                 | PROGRAM CBACT02C' (line 71 of
#                                 | CBACT02C.cbl) preserved byte-exact
#                                 | in the CloudWatch log stream.
#   test_logs_end_message         | DISPLAY 'END OF EXECUTION OF
#                                 | PROGRAM CBACT02C' (line 85 of
#                                 | CBACT02C.cbl) preserved byte-exact
#                                 | in the CloudWatch log stream.
#   test_logs_record_count        | The PERFORM UNTIL END-OF-FILE loop
#                                 | (lines 74-81) iterates every row of
#                                 | the CARDDATA cluster; translated to
#                                 | PySpark as a .count() call whose
#                                 | value is surfaced in a dedicated log
#                                 | line for operator verification.
#   test_iterates_all_records     | DISPLAY CARD-RECORD (line 78 and
#                                 | line 96) inside the 1000-CARDFILE-
#                                 | GET-NEXT read loop → one
#                                 | logger.info() call per row
#                                 | materialised via DataFrame.collect().
#   test_commit_job_called        | Terminal GOBACK (line 87) + JCL
#                                 | MAXCC=0 (READCARD step completion)
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
"""Unit tests for :mod:`src.batch.jobs.read_card_job`.

Validates behavioral parity with the original COBOL diagnostic program
``app/cbl/CBACT02C.cbl`` plus its launcher ``app/jcl/READCARD.jcl``.
CBACT02C is a *diagnostic / utility* batch program — it opens the
CARDDATA VSAM KSDS cluster, reads every record sequentially, and
DISPLAYs each one to SYSOUT. It performs no data modification and has
no downstream dependencies: its sole purpose is to let an operator
verify the current contents of the card master file after a data
migration or before launching the production batch pipeline.

COBOL -> Python Verification Surface
------------------------------------
==================================================  ==========================================
COBOL paragraph / statement                         Python test (this module)
==================================================  ==========================================
``OPEN INPUT CARDFILE-FILE`` L120                   ``test_reads_cards_table``
``READ CARDFILE-FILE INTO ...`` L93                 ``test_iterates_all_records``
``DISPLAY CARD-RECORD`` L78, L96                    ``test_iterates_all_records``
``PERFORM UNTIL END-OF-FILE = 'Y'`` L74-81          ``test_logs_record_count``
``DISPLAY 'START OF EXECUTION ...CBACT02C'`` L71    ``test_logs_start_message``
``DISPLAY 'END OF EXECUTION ...CBACT02C'`` L85      ``test_logs_end_message``
``GOBACK`` L87 + JCL MAXCC=0                        ``test_commit_job_called``
==================================================  ==========================================

Mocking Strategy
----------------
The target module ``src.batch.jobs.read_card_job`` imports its three
runtime dependencies at module-load time via::

    from src.batch.common.db_connector import read_table
    from src.batch.common.glue_context import commit_job, init_glue

Because the ``from ... import ...`` form creates new name bindings in
the *importing* module's namespace, every :func:`unittest.mock.patch`
call MUST target the ``read_card_job`` namespace — NOT the originating
``glue_context`` / ``db_connector`` modules. Patching at the source
module would rebind the name in the wrong namespace and the mock
would never be triggered. The ``_PATCH_*`` constants below centralize
these exact patch targets.

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
The ``cards`` table schema contains payment card industry (PCI)
sensitive fields — card PAN (``card_num``) and CVV (``cvv_cd``). In
production these are encrypted at rest per the project's data-
protection policy, and CloudWatch log entries emitted by this job
are subject to the same IAM access controls as the underlying JDBC
read. This test suite uses dummy values (all-ones, all-twos, all-
threes synthetic PANs) that do NOT correspond to any real payment
card — they are chosen to be trivially recognisable as test data
if they ever leak into production log archives.

See Also
--------
* AAP §0.2.2 — Batch Program Classification (CBACT02C listed as a
  diagnostic reader utility alongside CBACT01C, CBACT03C, CBCUS01C).
* AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue).
* AAP §0.5.1 — File-by-File Transformation Plan (read_card_job entry).
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
#                       per-CARD-RECORD DISPLAY equivalent are emitted.
# ``patch``, ``MagicMock``, ``call`` — :mod:`unittest.mock` primitives.
#                       * ``patch`` is used as a decorator on every test
#                         to replace ``init_glue`` / ``read_table`` /
#                         ``commit_job`` with mocks in the
#                         ``read_card_job`` module's own namespace.
#                       * ``MagicMock`` creates the chainable DataFrame
#                         stand-ins returned by the mocked
#                         ``read_table``, plus the SparkSession / Glue
#                         Job object tuples consumed by main().
#                       * ``call`` is imported to make positional-vs-
#                         keyword argument matching explicit and
#                         grep-able in the test body (e.g.,
#                         ``call(mock_spark, "cards")``).
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
# ``main`` is the PySpark Glue job entry point that replaces CBACT02C's
# PROCEDURE DIVISION paragraph-set. Calling ``main()`` under patched
# dependencies exercises the full CBACT02C equivalent flow:
#
#   init_glue    (replaces JCL JOB + EXEC PGM=CBACT02C + STEPLIB)
#     → DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
#     → read_table(spark, "cards")
#                (replaces OPEN INPUT CARDFILE-FILE + READCARD.jcl
#                 //CARDFILE DD DISP=SHR,
#                 DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS)
#     → df.cache() + df.count()
#                (single count-action materialisation of the lazy
#                 DataFrame; replaces the per-record READ + EOF
#                 check in paragraph 1000-CARDFILE-GET-NEXT)
#     → for row in df.collect(): logger.info("CARD-RECORD: ...")
#                (replaces DISPLAY CARD-RECORD inside the
#                 PERFORM UNTIL END-OF-FILE loop, lines 74-81)
#     → df.unpersist()
#                (replaces CLOSE CARDFILE-FILE in paragraph
#                 9000-CARDFILE-CLOSE, lines 136-152)
#     → DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
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
from src.batch.jobs.read_card_job import main

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text constants from ``app/cbl/CBACT02C.cbl``.
# These mirror the target module's ``_COBOL_START_MSG`` /
# ``_COBOL_END_MSG`` private constants — duplicated here rather than
# imported so the tests independently enforce the byte-exact string
# and would FAIL if the target module ever drifted from the COBOL
# source. This enforcement is precisely the point of behavioral-
# parity testing for the mainframe-to-cloud migration (AAP §0.7.1:
# "Preserve all existing functionality exactly as-is").
#
# Line references are to ``app/cbl/CBACT02C.cbl`` as committed:
#   * Line 71:   DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.
#   * Line 85:   DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.
# ----------------------------------------------------------------------------
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBACT02C"
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBACT02C"

# ----------------------------------------------------------------------------
# Canonical PostgreSQL table name for the CARDDATA VSAM cluster. Maps
# the JCL DD statement ``//CARDFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`` (READCARD.jcl lines 25-26)
# to the Aurora PostgreSQL table as defined in
# ``db/migrations/V1__schema.sql`` and canonicalized by
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["CARDDATA"]``.
# Declared here as a module-level constant so the single-table-read
# assertion in ``test_reads_cards_table`` is auditable.
# ----------------------------------------------------------------------------
_EXPECTED_TABLE_NAME: str = "cards"

# ----------------------------------------------------------------------------
# Canonical Glue job name for the CARDDATA diagnostic reader. The
# target module declares this as ``_JOB_NAME = "carddemo-read-card"``;
# we duplicate here rather than import so any drift in the naming
# convention (from the ``carddemo-<job>`` pattern documented in
# AAP §0.5.1) is caught by this test suite.
# ----------------------------------------------------------------------------
_EXPECTED_JOB_NAME: str = "carddemo-read-card"

# ----------------------------------------------------------------------------
# read_card_job-namespace patch targets — the module-under-test
# re-binds ``init_glue`` / ``read_table`` / ``commit_job`` via
# ``from src.batch.common... import ...``. Every ``patch()`` call must
# target the ``read_card_job`` namespace, NOT the original
# ``glue_context`` / ``db_connector`` definition sites. Centralised as
# constants to avoid typos across the six test functions and to make
# the mocking strategy grep-able from a single location.
# ----------------------------------------------------------------------------
_PATCH_INIT_GLUE = "src.batch.jobs.read_card_job.init_glue"
_PATCH_READ_TABLE = "src.batch.jobs.read_card_job.read_table"
_PATCH_COMMIT_JOB = "src.batch.jobs.read_card_job.commit_job"


# ----------------------------------------------------------------------------
# Helper: mock DataFrame factory.
# ----------------------------------------------------------------------------
# The target module's main() chains PySpark DataFrame operations
# fluently — specifically the pattern::
#
#     cards_df = read_table(spark, _TABLE_NAME)
#     cards_df = cards_df.cache()
#     record_count = cards_df.count()
#     for row in cards_df.collect():
#         logger.info("CARD-RECORD: %s", row.asDict())
#     cards_df.unpersist()
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
        ~445), which skips the per-row iteration and logs the
        "No card records found" informational message instead.
        Setting it to a positive value drives main() through the
        full ``collect()`` iteration loop — matching the COBOL
        PERFORM UNTIL END-OF-FILE loop (lines 74-81).
    rows
        List of row-like objects returned by the mock DataFrame's
        ``collect()`` method. Each element should be a
        :class:`MagicMock` whose ``asDict()`` method returns the
        CARD-RECORD dict to be logged. Defaults to an empty list
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
    # reassignment pattern (``cards_df = cards_df.cache()``) preserves
    # the tracked mock. Without this, the subsequent
    # ``cards_df.count()`` and ``cards_df.collect()`` would operate
    # on an auto-generated child mock with no configured behavior.
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
# ``for row in cards_df.collect(): logger.info("CARD-RECORD: %s",
# row.asDict())`` so the mocks returned by ``collect()`` must
# implement ``asDict()``. We build stand-ins by wrapping a MagicMock
# and configuring its ``asDict.return_value`` to an appropriate dict
# shaped like the CARD-RECORD layout (``app/cpy/CVACT02Y.cpy``).
#
# The non-FILLER fields from the COBOL record layout are:
#   * CARD-NUM             PIC X(16)  → card_num         (str, 16-char PK)
#   * CARD-ACCT-ID         PIC 9(11)  → acct_id          (int, 11-digit FK)
#   * CARD-CVV-CD          PIC 9(03)  → cvv_cd           (int, 3-digit)
#   * CARD-EMBOSSED-NAME   PIC X(50)  → embossed_name    (str)
#   * CARD-EXPIRAION-DATE  PIC X(10)  → expiration_date  (str — note the
#                                      authentic COBOL typo is fixed in
#                                      the Aurora schema column name)
#   * CARD-ACTIVE-STATUS   PIC X(01)  → active_status    (str — 'Y'/'N')
#
# The FILLER PIC X(59) is NOT represented in the Aurora schema (pure
# VSAM slack padding) per the target module's docstring (lines 72-82
# of ``src/batch/jobs/read_card_job.py``), so it is omitted from the
# dict returned by asDict(). The iteration-loop tests assert presence
# of ``card_num`` in log output, which is sufficient to confirm the
# per-row DISPLAY equivalent fires for each row.
#
# PCI-DSS note: the ``card_num`` and ``cvv_cd`` fields are payment
# card industry sensitive. This test fixture uses clearly synthetic
# values (16-digit all-N repeating patterns, all-N 3-digit CVVs)
# that would be trivially identifiable as test data if they ever
# leaked into production log archives.
# ----------------------------------------------------------------------------
def _make_mock_row(
    card_num: str,
    acct_id: int = 10000000001,
    cvv_cd: int = 123,
    embossed_name: str = "TEST CARDHOLDER",
    expiration_date: str = "2030-12-31",
    active_status: str = "Y",
) -> MagicMock:
    """Build a mock PySpark Row with the CARD-RECORD layout.

    Parameters
    ----------
    card_num
        16-character card number primary key. Matches COBOL
        CARD-NUM PIC X(16). This is the distinguishing field used
        in iteration-loop assertions to confirm each row's
        asDict() result is rendered into a distinct log line.
        Callers should supply synthetic (non-real) 16-digit
        strings to ensure test fixtures cannot be mistaken for
        real payment card data if log archives are inspected.
    acct_id
        11-digit account foreign key. Matches COBOL
        CARD-ACCT-ID PIC 9(11).
    cvv_cd
        3-digit card verification value. Matches COBOL
        CARD-CVV-CD PIC 9(03). Note: in production this field is
        encrypted at rest per the project's data protection
        policy; this test fixture uses a non-real synthetic value.
    embossed_name
        Cardholder name as embossed on the physical card. Matches
        COBOL CARD-EMBOSSED-NAME PIC X(50).
    expiration_date
        Card expiration date. Matches COBOL
        CARD-EXPIRAION-DATE PIC X(10) (sic — the misspelling is
        authentic to the original COBOL copybook). The Aurora
        schema canonicalizes this as ``expiration_date``.
    active_status
        Single-character active flag ('Y' or 'N'). Matches COBOL
        CARD-ACTIVE-STATUS PIC X(01).

    Returns
    -------
    MagicMock
        A mock Row whose ``asDict()`` method returns a subset of
        the CARD-RECORD dict. The subset is sufficient to exercise
        the per-row log-emission contract in the target module's
        iteration loop.
    """
    row = MagicMock(name=f"MockRow(card_num={card_num})")
    row.asDict.return_value = {
        "card_num": card_num,
        "acct_id": acct_id,
        "cvv_cd": cvv_cd,
        "embossed_name": embossed_name,
        "expiration_date": expiration_date,
        "active_status": active_status,
    }
    return row


# ----------------------------------------------------------------------------
# Test 2: COBOL start-of-execution banner is emitted verbatim.
# ----------------------------------------------------------------------------
# Verifies the target module preserves the byte-exact text of the
# original COBOL source's opening DISPLAY statement:
#
#     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.
#
# This is the very first business log line emitted by CBACT02C (line
# 71 of ``app/cbl/CBACT02C.cbl``) and must be preserved verbatim —
# operators and SRE tooling grep production CloudWatch log streams
# for this exact banner to identify the start of a CBACT02C execution.
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
    """``main()`` must log the verbatim CBACT02C start banner."""
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
        f"{_COBOL_START_MSG_EXPECTED!r} (CBACT02C.cbl line 71); "
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
            f"CBACT02C's DISPLAY statement); got "
            f"{logging.getLevelName(record.levelno)}"
        )


# ----------------------------------------------------------------------------
# Test 3: COBOL end-of-execution banner is emitted verbatim, AFTER start.
# ----------------------------------------------------------------------------
# Mirrors test_logs_start_message but for the closing banner at line
# 85 of ``app/cbl/CBACT02C.cbl``:
#
#     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.
#
# In addition to verifying the banner text is present, this test
# enforces temporal ordering — the end banner MUST appear after the
# start banner in the log stream. This is obvious in the COBOL source
# (line 85 > line 71) but must be validated in the Python translation
# because PySpark's lazy evaluation could theoretically allow the end
# banner to interleave with async log emissions from Spark workers.
# Strict ordering guarantees the banner semantics remain meaningful:
# operators can bracket the entire CBACT02C execution by these two
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
    """``main()`` must log the verbatim CBACT02C end banner after start."""
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
    row = _make_mock_row(card_num="4111111111111111")
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
        f"{_COBOL_END_MSG_EXPECTED!r} (CBACT02C.cbl line 85); "
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
        f"(CBACT02C.cbl line 85 > line 71); "
        f"start_indices={start_indices}, end_indices={end_indices}"
    )


# ----------------------------------------------------------------------------
# Test 4: total record count is logged for operator visibility.
# ----------------------------------------------------------------------------
# CBACT02C's original COBOL implementation does NOT emit a dedicated
# record-count line — it simply DISPLAYs each record and the operator
# counts them by reading SYSOUT. The Python translation (target
# module, lines ~430-440) takes advantage of PySpark's lazy-
# evaluation model: it calls ``df.count()`` once (a Spark action
# that materialises the full table) and emits a single log line of
# the form::
#
#     cards record count: <N>
#
# This preserves diagnostic fidelity — the operator can see at a
# glance how many rows were read — while avoiding the O(N) SYSOUT
# scanning that the COBOL equivalent required. The count() call
# also enables the ``if record_count > 0`` branch at target module
# line ~445 that skips the per-row iteration when the table is
# empty, preventing a (benign but noisy) empty-iteration log cascade.
#
# The fixture uses 50 rows to match the production seed size from
# ``db/migrations/V3__seed_data.sql`` (50 cards) — this choice makes
# the test both realistic and a sentinel for future schema drift: if
# a developer accidentally changes the count-extraction logic to
# silently truncate at, say, 100, the assertion value ``50`` would
# still pass, but the fixture size would need adjusting in concert
# — making the test a meaningful regression tripwire.
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
        _make_mock_row(card_num=f"4111{i:012d}") for i in range(expected_count)
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
        f"Expected exactly 1 count() action on the cards DataFrame "
        f"(multiple count() calls would double/triple the Aurora "
        f"read IOPS); got {mock_df.count.call_count}"
    )

    # Contract 2: the DataFrame was cached before the count() action
    # so that the subsequent collect() iteration does not re-trigger
    # a second table scan. The target module's
    # ``cards_df = cards_df.cache()`` pattern (line ~435) is a
    # critical performance optimisation for the Aurora JDBC read:
    # without it, count() and collect() would each issue a full
    # SELECT, doubling the wall-clock latency.
    mock_df.cache.assert_called_once()

    # Contract 3: the count value appears in the log stream. We use
    # case-insensitive substring matching so the test is robust to
    # the exact phrasing of the log line (current target module
    # uses "cards record count: %d", but any variation such as
    # "Record count: 50" or "CARDS COUNT = 50" would also satisfy
    # the operator-visibility contract).
    all_messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "record count" in all_messages.lower(), (
        f"Expected a log line containing 'record count' (case-"
        f"insensitive); captured messages:\n{all_messages}"
    )
    assert str(expected_count) in all_messages, (
        f"Expected the count value {expected_count} to appear in "
        f"a log line; captured messages:\n{all_messages}"
    )


# ----------------------------------------------------------------------------
# Test 5: every record is iterated (DISPLAY CARD-RECORD equivalent),
#         with PCI-DSS-compliant masking of PAN and CVV values.
# ----------------------------------------------------------------------------
# The target module's per-row iteration at line ~463::
#
#     for row in cards_df.collect():
#         logger.info("CARD-RECORD: %s", _mask_card_record(row.asDict()))
#
# is the direct Python translation of the COBOL inner-loop DISPLAY
# at line 78 (inside the PERFORM UNTIL END-OF-FILE = 'Y' loop)::
#
#     DISPLAY CARD-RECORD.
#
# However, unlike the COBOL source which emitted the full CARD-RECORD
# (including the full PAN and CVV) to SYSOUT, the Python translation
# applies PCI-DSS v4.0 Requirement 3.3.1 masking to the PAN
# (``card_num``: first-6 + "******" + last-4) and PCI-DSS
# Requirement 3.2.1 removal of the CVV (``cvv_cd``: omitted entirely)
# before emitting the log line.  CloudWatch log persistence qualifies
# as "storage" under PCI-DSS scope, so the original COBOL
# full-record emission would be a compliance violation in the cloud-
# hosted context.
#
# This test uses 3 distinct rows with sentinel card-number values
# ("1111111111111111", "2222222222222222", "3333333333333333") that
# are trivially distinguishable in the captured log output.  Their
# PCI-DSS-masked equivalents are "111111******1111",
# "222222******2222", and "333333******3333" respectively (16-char
# length preserved: 6 + 6 + 4 = 16).
#
# The test asserts:
#   1. ``collect()`` was called exactly once (not per-row, which
#      would be catastrophic for the Aurora JDBC connection).
#   2. Each row's ``asDict()`` was called exactly once (each row
#      is materialised into a log line exactly once).
#   3. Exactly N log lines start with "CARD-RECORD:" — matching the
#      target module's format string "CARD-RECORD: %s".
#   4. Each sentinel's PCI-DSS-masked card number appears somewhere
#      in the captured log output — proving no row was silently
#      dropped AND proving the masking function was applied.
#   5. (NEGATIVE — PCI-DSS Req 3.3.1 regression guard) No full PAN
#      (the raw sentinel card numbers) appears anywhere in the
#      captured log output.  Catches a regression where someone
#      accidentally removes the ``_mask_card_record`` call and
#      restores the un-masked COBOL behaviour.
#   6. (NEGATIVE — PCI-DSS Req 3.2.1 regression guard) No CVV
#      value (``cvv_cd``) appears anywhere in the captured log
#      output.  SAD (Sensitive Authentication Data) must never be
#      stored after authorization, and CloudWatch log archives
#      qualify as storage.
#   7. ``embossed_name`` (a NON-PCI-restricted field) still appears
#      in the log output — proving the masking function is
#      selective (only masks PAN, removes CVV) and does not
#      over-redact legitimate diagnostic fields.
#
# The sentinel values double as documentation: "1111...", "2222...",
# "3333..." make it obvious at a glance which rows a test run saw.
# All three are PCI-safe synthetic values — none corresponds to a
# real issued card number.  The distinct CVV values (see fixture
# below) are used to verify Contract 6 catches any CVV regression.
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
    """``main()`` must emit one CARD-RECORD log line per row."""
    # --- Arrange ----------------------------------------------------
    mock_spark = MagicMock(name="MockSparkSession")
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        mock_spark,
        None,
        mock_job,
        {"JOB_NAME": _EXPECTED_JOB_NAME},
    )

    # Three distinct synthetic card numbers — the repeating-digit
    # pattern ensures they are trivially distinguishable in the log
    # output and are clearly NOT real PANs. Each row carries a
    # distinct acct_id, embossed_name, AND cvv_cd so asDict()
    # produces unique dicts for each row.  The distinct CVV values
    # (555, 666, 777) are used in Contract 6 below to verify the
    # PCI-DSS Req 3.2.1 removal is applied to EVERY row — a
    # regression that only omitted one row's CVV would fail only
    # one assertion, but we want every row checked.
    #
    # CVV VALUE SELECTION RATIONALE (non-collision guarantee):
    # The CVV values are deliberately chosen to use ONLY digits from
    # the set {5, 6, 7} — digits that appear NOWHERE in the other
    # fixture values that ARE expected to appear in the log output:
    #   * Masked PANs:     "111111******1111", "222222******2222",
    #                      "333333******3333"   (digits 1/2/3 + '*')
    #   * acct_ids:        10000000001/002/003  (digits 0 and 1/2/3)
    #   * expiration_date: "2030-12-31"         (digits 0/1/2/3 + '-')
    #   * active_status:   "Y" or "N"           (letters only)
    #   * embossed_name:   "ALPHA TESTER" etc.  (letters only)
    # Therefore a substring match on "555", "666", or "777" in the
    # captured log output reliably proves that a CVV leaked — no
    # false positives from coincidental substring collisions with
    # the PCI-DSS-compliant fields that SHOULD remain visible.
    sentinel_row_1 = _make_mock_row(
        card_num="1111111111111111",
        acct_id=10000000001,
        cvv_cd=555,
        embossed_name="ALPHA TESTER",
        active_status="Y",
    )
    sentinel_row_2 = _make_mock_row(
        card_num="2222222222222222",
        acct_id=10000000002,
        cvv_cd=666,
        embossed_name="BETA TESTER",
        active_status="Y",
    )
    sentinel_row_3 = _make_mock_row(
        card_num="3333333333333333",
        acct_id=10000000003,
        cvv_cd=777,
        embossed_name="GAMMA TESTER",
        active_status="N",
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
        f"Expected exactly 1 collect() call on the cards DataFrame "
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
            f"(card_num={row.asDict.return_value['card_num']}); "
            f"got {row.asDict.call_count}"
        )

    # Contract 3: exactly N log lines carry the "CARD-RECORD:"
    # prefix — matching the target module's
    # ``logger.info("CARD-RECORD: %s", row.asDict())`` format.
    card_record_messages = [
        record.getMessage()
        for record in caplog.records
        if "CARD-RECORD" in record.getMessage()
    ]
    assert len(card_record_messages) == len(row_fixtures), (
        f"Expected exactly {len(row_fixtures)} 'CARD-RECORD:' log "
        f"lines (one per row in the collect() iteration); got "
        f"{len(card_record_messages)} — messages: "
        f"{card_record_messages}"
    )

    # Contract 4: every sentinel's PCI-DSS-MASKED card_num appears
    # in the joined log output. The joined-then-searched approach is
    # robust to whether the target module uses %s formatting,
    # f-strings, or explicit ``str(dict)`` rendering — the masked
    # value will appear verbatim in any of these forms.
    #
    # PCI-DSS Req 3.3.1 masking format: first-6 + "******" + last-4.
    # For 16-digit PANs, the masked form preserves the overall
    # length (6 + 6 + 4 = 16 chars):
    #   "1111111111111111" → "111111******1111"
    #   "2222222222222222" → "222222******2222"
    #   "3333333333333333" → "333333******3333"
    all_card_record_output = "\n".join(card_record_messages)
    for row in row_fixtures:
        full_pan = row.asDict.return_value["card_num"]
        # Build the expected PCI-DSS-masked form:
        # first-6 + "******" + last-4.
        masked_pan = f"{full_pan[:6]}******{full_pan[-4:]}"
        assert masked_pan in all_card_record_output, (
            f"Expected PCI-DSS-masked card_num {masked_pan!r} "
            f"(derived from sentinel {full_pan!r}) to appear in "
            f"the 'CARD-RECORD:' log output — proves both that no "
            f"row was silently dropped during iteration AND that "
            f"the ``_mask_card_record`` helper was applied (PCI-DSS "
            f"Req 3.3.1 — first-6 + last-4 masking); full output:"
            f"\n{all_card_record_output}"
        )

    # Contract 5 (NEGATIVE — PCI-DSS Req 3.3.1 regression guard):
    # No FULL PAN (the raw sentinel 16-digit card number) may
    # appear anywhere in the captured log output.  This catches a
    # regression where someone accidentally removes the
    # ``_mask_card_record`` call around ``row.asDict()`` in
    # ``src/batch/jobs/read_card_job.py`` and restores the
    # un-masked COBOL "DISPLAY CARD-RECORD" behaviour (which would
    # leak full PANs to CloudWatch log archives — PCI-DSS Req
    # 3.3.1 says only the first 6 and last 4 digits may be
    # displayed when the PAN is rendered; everything else must
    # be masked).
    for row in row_fixtures:
        full_pan = row.asDict.return_value["card_num"]
        assert full_pan not in all_card_record_output, (
            f"PCI-DSS Req 3.3.1 VIOLATION: full PAN {full_pan!r} "
            f"(unmasked 16-digit card number) appeared in the "
            f"'CARD-RECORD:' log output.  The ``_mask_card_record`` "
            f"helper must be applied before logging to mask all "
            f"but the first-6 and last-4 digits.  Full captured "
            f"output:\n{all_card_record_output}"
        )

    # Contract 6 (NEGATIVE — PCI-DSS Req 3.2.1 regression guard):
    # No CVV value (``cvv_cd``) may appear anywhere in the
    # captured log output.  Sensitive Authentication Data (SAD —
    # CVV / CAV2 / CID / CVV2) must NEVER be stored after
    # authorization under PCI-DSS Req 3.2.1, and CloudWatch log
    # archives qualify as storage.  The ``_mask_card_record``
    # helper achieves this by OMITTING the ``cvv_cd`` key entirely
    # (not just masking it).
    #
    # Defence-in-depth: we also assert the string "cvv" (the
    # field name itself) is absent — this catches a regression
    # where someone masks the value but leaves the key/value pair
    # in the dict (which would still expose the CVV value in the
    # dict representation's keys).  Note that "cvv" is lowercased
    # to avoid matching accidental mentions of e.g. "CVV-CD" in
    # an unrelated error-handling path.
    for row in row_fixtures:
        cvv_value = row.asDict.return_value["cvv_cd"]
        cvv_str = str(cvv_value)
        assert cvv_str not in all_card_record_output, (
            f"PCI-DSS Req 3.2.1 VIOLATION: CVV value "
            f"{cvv_str!r} appeared in the 'CARD-RECORD:' log "
            f"output.  Sensitive Authentication Data (SAD — "
            f"CVV / CAV2 / CID / CVV2) must NEVER be written to "
            f"CloudWatch log archives.  The ``_mask_card_record`` "
            f"helper must OMIT the ``cvv_cd`` key entirely "
            f"(not just mask its value).  Full captured "
            f"output:\n{all_card_record_output}"
        )
    assert "cvv_cd" not in all_card_record_output, (
        f"PCI-DSS Req 3.2.1 DEFENCE-IN-DEPTH VIOLATION: the "
        f"string 'cvv_cd' (the field name) appeared in the "
        f"'CARD-RECORD:' log output.  The ``_mask_card_record`` "
        f"helper must OMIT the ``cvv_cd`` key entirely from the "
        f"logged dict — leaving just the key (even with a masked "
        f"value) still signals the field exists.  Full captured "
        f"output:\n{all_card_record_output}"
    )

    # Contract 7 (POSITIVE — selective-redaction guard): The
    # ``embossed_name`` field (a NON-PCI-restricted diagnostic
    # field — it is not SAD, not a PAN, and appears on the
    # physical card in plain text) must STILL appear in the log
    # output.  This proves the ``_mask_card_record`` helper is
    # SELECTIVE in what it redacts (PAN + CVV only) and does not
    # over-redact legitimate diagnostic fields.  Without this
    # assertion, a lazy "redact everything" regression would
    # silently pass Contracts 4-6.
    for row in row_fixtures:
        embossed_name = row.asDict.return_value["embossed_name"]
        assert embossed_name in all_card_record_output, (
            f"Expected non-PCI-restricted embossed_name "
            f"{embossed_name!r} to appear in the 'CARD-RECORD:' "
            f"log output — proves the ``_mask_card_record`` "
            f"helper is selective (masks PAN + removes CVV only, "
            f"preserves other diagnostic fields).  Full output:\n"
            f"{all_card_record_output}"
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
# ``app/cbl/CBACT02C.cbl`` combined with the JCL success contract
# (MAXCC=0 propagating to READCARD's return code). In the JCL
# pipeline, a successful GOBACK allows the next //STEP to execute;
# in AWS, a successful commit_job allows the next state machine
# transition.
#
# This test uses an EMPTY DataFrame (count_value=0) to deliberately
# exercise the target module's empty-table branch. commit_job MUST
# fire even when the cards table has zero rows — the diagnostic
# reader has still "succeeded" in that case (it executed to
# completion without error). A naive implementation that gated
# commit_job behind ``if record_count > 0`` would leave the Step
# Functions stage hung forever if the upstream load pipeline had
# failed to seed any cards.
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
# Test 1: main() reads the cards PostgreSQL table.
# ----------------------------------------------------------------------------
# Verifies that main() issues exactly one ``read_table(spark, "cards")``
# call — the PySpark equivalent of the COBOL OPEN INPUT CARDFILE-FILE
# statement (paragraph 0000-CARDFILE-OPEN, line 120) combined with the
# JCL //CARDFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
# binding (READCARD.jcl lines 25-26). The assertion catches three
# distinct failure modes:
#   1. Wrong table name — drift from "cards" would break the
#      mainframe-to-cloud VSAM-to-PostgreSQL mapping.
#   2. Extra table reads — the diagnostic reader must touch ONLY the
#      CARDDATA cluster; reading any other table would violate
#      CBACT02C's scope (it is strictly single-file).
#   3. Wrong SparkSession — read_table must receive the SparkSession
#      returned by init_glue, not a fresh/alternative one.
#
# The test also asserts init_glue was called with the canonical
# ``carddemo-read-card`` job name, preserving the naming convention
# documented in AAP §0.5.1.
# ----------------------------------------------------------------------------
@pytest.mark.unit
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_INIT_GLUE)
def test_reads_cards_table(
    mock_init_glue: MagicMock,
    mock_read_table: MagicMock,
    mock_commit_job: MagicMock,
) -> None:
    """``main()`` must call ``read_table(spark, "cards")``."""
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
    # Exactly ONE read_table call — CBACT02C is a single-file
    # diagnostic reader (FILE-CONTROL declares only CARDFILE-FILE).
    assert mock_read_table.call_count == 1, (
        f"Expected exactly 1 read_table() call (CBACT02C is a "
        f"single-file diagnostic reader — FILE-CONTROL declares "
        f"only CARDFILE-FILE); got {mock_read_table.call_count}"
    )

    # The call must use the canonical SparkSession + table-name pair.
    # ``call(mock_spark, "cards")`` is the literal positional-
    # argument signature expected by the target module's
    # ``read_table(spark, _TABLE_NAME)`` invocation. This assertion
    # would FAIL on any of:
    #   * Wrong SparkSession threaded through (different object id)
    #   * Wrong table name (drift from "cards")
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


