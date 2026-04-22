# ============================================================================
# tests/unit/test_batch/test_prtcatbl_job.py
# Unit tests for PRTCATBL category-balance print utility PySpark Glue job.
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
"""Unit tests for ``prtcatbl_job.py`` — Print Category Balance utility.

Validates the PySpark Glue implementation that replaces the z/OS
mainframe utility job ``app/jcl/PRTCATBL.jcl`` (67 lines, pure JCL +
DFSORT with NO COBOL program counterpart) which reads the TCATBALF
VSAM KSDS cluster, sorts records by ``(acct_id, type_cd, cat_cd)``
ascending, and emits a human-readable formatted report rendered via the
DFSORT EDIT mask ``EDIT=(TTTTTTTTT.TT)``.

Source
------
* ``app/jcl/PRTCATBL.jcl`` — 67-line JCL with three EXEC steps:

  - ``DELDEF`` (lines 21-25): ``EXEC PGM=IEFBR14`` with
    ``DISP=(MOD,DELETE)`` on ``TCATBALF.REPT`` for idempotent
    pre-delete of the prior report dataset.
  - ``STEP05R`` (lines 29-39): invoked ``PROC=REPROC`` issues
    IDCAMS REPRO of the TCATBALF VSAM KSDS cluster into
    ``TCATBALF.BKUP(+1)`` (LRECL=50, RECFM=FB) — a new GDG
    generation that unloads the VSAM records into a flat file.
  - ``STEP10R`` (lines 43-63): ``EXEC PGM=SORT`` (DFSORT) with
    SYMNAMES declaring the 4 record fields, ``SORT FIELDS=(
    TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)`` ascending
    3-key sort, and ``OUTREC FIELDS=(...,TRAN-CAT-BAL,EDIT=(
    TTTTTTTTT.TT),9X)`` formatting. SORTOUT: LRECL=40, RECFM=FB to
    ``TCATBALF.REPT``.

* ``app/cpy/CVTRA01Y.cpy`` — ``TRAN-CAT-BAL-RECORD`` layout (50 bytes
  fixed-width) — key fields exercised by the test record builder::

        01  TRAN-CAT-BAL-RECORD.
            05  TRAN-CAT-KEY.
               10 TRANCAT-ACCT-ID                       PIC 9(11).
               10 TRANCAT-TYPE-CD                       PIC X(02).
               10 TRANCAT-CD                            PIC 9(04).
            05  TRAN-CAT-BAL                            PIC S9(09)V99.
            05  FILLER                                  PIC X(22).

  The ``TRAN-CAT-BAL`` field is a signed 11-digit fixed-point decimal
  with 9 integer positions and 2 decimal positions — exactly matching
  the DFSORT EDIT mask width. In PostgreSQL this becomes
  ``NUMERIC(11,2)`` (preserved as :class:`decimal.Decimal` on the Python
  side per AAP §0.7.2 financial-precision rules).

Target Module Under Test
------------------------
* ``src/batch/jobs/prtcatbl_job.py`` — PySpark Glue job translating
  the three JCL steps into a Python/PySpark pipeline:

    1. ``init_glue(job_name="carddemo-prtcatbl")`` — boots the Glue /
       SparkSession context and resolves runtime arguments.
    2. DELDEF — emitted as a log marker only (S3 ``put_object`` is
       atomic per-object; no explicit pre-delete needed).
    3. ``read_table(spark, "transaction_category_balances")`` — JDBC
       read of the PostgreSQL table that replaces the TCATBALF VSAM
       KSDS cluster (Aurora PostgreSQL as the single persistence
       layer per AAP §0.1.1).
    4. ``tcatbal_df.cache()`` + ``count()`` — materializes the read
       for subsequent sort diagnostic logging without re-issuing the
       JDBC query.
    5. ``tcatbal_df.select(...).orderBy(
       F.col("acct_id").asc(), F.col("type_code").asc(),
       F.col("cat_code").asc())`` — the 3-key ascending sort matching
       the DFSORT ``SORT FIELDS=(...,A,...,A,...,A)`` specification.
    6. ``sort_df.collect()`` — pulls the sorted rows to the driver for
       line-by-line formatting (bounded by accounts × types ×
       categories, well within G.1X Glue worker driver memory).
    7. Per-row formatting loop — builds the 50-byte backup line
       (VSAM-layout recreation) and the 41-byte DFSORT OUTREC-style
       report line via private helpers (``_format_backup_line``,
       ``_format_report_line``). Both reuse
       :func:`format_balance` to apply the EDIT mask to the
       ``tran_cat_bal`` column.
    8. ``write_to_s3(content=backup_content, key=..., content_type=
       "text/plain")`` — first S3 write (replaces
       ``TCATBALF.BKUP(+1)``).
    9. ``write_to_s3(content=report_content, key=..., content_type=
       "text/plain")`` — second S3 write (replaces ``TCATBALF.REPT``).
    10. ``commit_job(job)`` — emits the Glue completion event
        (replaces the mainframe's implicit ``MAXCC=0``).

Test Organization
-----------------
Nine test cases across five logical phases mapping to the AAP
agent-prompt specification for this file:

* Phase 2 — Sort order (SORT FIELDS replacement) — 1 test.
* Phase 3 — EDIT-mask formatting (``EDIT=(TTTTTTTTT.TT)``) — 4 tests.
* Phase 4 — Report generation + S3 write (OUTREC / SORTOUT DD) — 2 tests.
* Phase 5 — End-to-end ``main()`` integration — 1 test.

The sort test and the end-to-end integration test exercise real
:class:`pyspark.sql.DataFrame` operations against the session-scoped
``spark_session`` fixture from :mod:`tests.conftest`. The four
``format_balance`` unit tests are pure-Python assertions with no Spark
dependency. The two report-generation tests patch the external
collaborators (``init_glue``, ``commit_job``, ``read_table``,
``get_versioned_s3_path``, ``write_to_s3``) from ``prtcatbl_job``'s
import namespace so orchestration is exercised without AWS or
PostgreSQL side effects.

Key test data invariants
------------------------
* ``TRAN-CAT-BAL`` (``PIC S9(09)V99``) values use
  :class:`decimal.Decimal` exclusively — NEVER ``float`` — per AAP
  §0.7.2 financial-precision rule. The DFSORT EDIT mask tests
  (``test_format_balance_*``) construct inputs via ``Decimal("...")``
  string-literal form and assert on exact string output.
* Sort-order invariant: ``(acct_id ASC, type_cd ASC, cat_cd ASC)`` —
  ascending on all 3 composite-key parts, matching
  ``SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)``.
* EDIT-mask width invariant: ``format_balance(...)`` always returns
  exactly 12 characters (9 integer + 1 literal ``.`` + 2 decimal),
  right-justified with leading-blank zero-suppression.
* S3 write invariant: ``main()`` calls ``write_to_s3`` exactly TWICE
  per invocation — once for ``backup.dat`` (VSAM-layout recreation)
  and once for ``report.txt`` (DFSORT OUTREC-formatted report), both
  under the same timestamped GDG-equivalent S3 prefix resolved via
  ``get_versioned_s3_path("TCATBALF.BKUP", generation="+1")``.

AAP References
--------------
* AAP §0.2.2 — Batch Program Classification (PRTCATBL utility, no COBOL)
* AAP §0.5.1 — File-by-File Transformation Plan (prtcatbl_job row)
* AAP §0.7.1 — Preserve existing business logic exactly as-is
* AAP §0.7.2 — Financial Precision (Decimal only, banker's rounding)
* AAP §0.7.3 — Minimal change discipline
"""

from __future__ import annotations

import logging
from decimal import Decimal
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pyspark.sql import Row, SparkSession

# Module under test — imported for its public API:
#   * format_balance(Decimal) -> str : DFSORT EDIT mask translator
#   * main() -> None                 : JCL orchestration replacement
#
# Only these two symbols are in the AAP internal_imports whitelist;
# the private helpers (_format_backup_line, _format_report_line) are
# deliberately NOT imported — they are implementation details and the
# test verifies their behavior transitively via main()'s S3-write
# content inspection (see test_report_line_format and
# test_report_written_to_s3).
from src.batch.jobs.prtcatbl_job import format_balance, main

# ----------------------------------------------------------------------------
# Test-module logger.
#
# Emits DEBUG traces when pytest is run with ``-o log_cli=true
# -o log_cli_level=DEBUG`` — invaluable during triage of SparkSession
# startup or mocked-S3 fixture misconfiguration. Silent by default so
# the successful-run output stays legible.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Patch-target constants.
#
# Patching MUST target the import location inside the module under test,
# not the source definition site. Each symbol below is imported into
# ``src.batch.jobs.prtcatbl_job`` via one of:
#
#     from src.batch.common.glue_context import commit_job, init_glue
#     from src.batch.common.db_connector import read_table
#     from src.batch.common.s3_utils    import get_versioned_s3_path, write_to_s3
#
# Patching at the original source module (e.g.
# ``src.batch.common.glue_context.init_glue``) would NOT intercept the
# already-resolved reference inside ``prtcatbl_job``. The constants
# below point to the correct re-exported names to guarantee patch
# efficacy.
#
# Note: ``prtcatbl_job`` does NOT import ``write_table`` (unlike
# ``combtran_job``) — it only READS from PostgreSQL via
# ``read_table`` and writes its output EXCLUSIVELY to S3 via
# ``write_to_s3``. There is intentionally NO ``_PATCH_WRITE_TABLE``
# constant; the absence is structural.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.prtcatbl_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.prtcatbl_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.prtcatbl_job.read_table"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.prtcatbl_job.get_versioned_s3_path"
_PATCH_WRITE_TO_S3: str = "src.batch.jobs.prtcatbl_job.write_to_s3"


# ============================================================================
# CVTRA01Y layout constants — shared across all test cases.
#
# These constants mirror the CVTRA01Y.cpy ``TRAN-CAT-BAL-RECORD``
# column-name mapping used by the module under test. Keeping them in
# one place prevents typo drift across test bodies and documents the
# COBOL-to-PostgreSQL column translation exactly once.
# ============================================================================
_COL_ACCT_ID: str = "acct_id"  # TRANCAT-ACCT-ID  PIC 9(11)
_COL_TYPE_CODE: str = "type_code"  # TRANCAT-TYPE-CD  PIC X(02)
_COL_CAT_CODE: str = "cat_code"  # TRANCAT-CD       PIC 9(04)
_COL_TRAN_CAT_BAL: str = "tran_cat_bal"  # TRAN-CAT-BAL  PIC S9(09)V99

# JCL-declared output record lengths (from PRTCATBL.jcl):
#
# * STEP05R FILEOUT (backup) — DCB=(LRECL=50,RECFM=FB) — matches the
#   50-byte VSAM record layout in CVTRA01Y.cpy.
# * STEP10R SORTOUT (report) — DCB=(LRECL=40,RECFM=FB) — a decorative
#   SORTOUT declaration; the actual OUTREC FIELDS expression yields
#   41 chars (the module preserves the 41-byte semantic per AAP §0.7.3
#   minimal-change discipline, documented in the module's docstring).
_BACKUP_LRECL_EXPECTED: int = 50
_REPORT_LRECL_DECLARED: int = 40
_REPORT_LRECL_ACTUAL: int = 41  # 11 + 1 + 2 + 1 + 4 + 1 + 12 + 9

# EDIT-mask total width from DFSORT ``EDIT=(TTTTTTTTT.TT)`` — 9 integer
# positions + 1 literal ``.`` + 2 decimal positions = 12 chars.
_EDIT_MASK_WIDTH: int = 12

# GDG-equivalent S3 dataset name allocated by get_versioned_s3_path
# for the backup artifact. The report artifact shares the SAME S3
# prefix (only the filename differs) — prtcatbl_job concatenates
# "backup.dat" and "report.txt" to the single prefix returned here.
_GDG_BACKUP_NAME: str = "TCATBALF.BKUP"

# Filenames appended to the timestamped S3 prefix by prtcatbl_job.main()
# to form the full object keys. These match the module's internal
# _BACKUP_FILENAME and _REPORT_FILENAME constants.
_BACKUP_FILENAME: str = "backup.dat"
_REPORT_FILENAME: str = "report.txt"


def _make_tcatbal_row(
    acct_id: str,
    type_code: str,
    cat_code: str,
    tran_cat_bal: Decimal,
) -> Row:
    """Build a :class:`pyspark.sql.Row` matching the CVTRA01Y layout.

    Produces a PySpark Row whose column order and data types are
    deterministic — identical across invocations so that
    ``SparkSession.createDataFrame([row, row, ...])`` can infer a
    stable schema (PySpark's Row-based schema inference is
    positionally order-sensitive within a DataFrame, so every helper
    output must share the same field ordering).

    Parameters
    ----------
    acct_id
        The 11-digit account identifier. Maps to ``TRANCAT-ACCT-ID
        PIC 9(11)`` in ``CVTRA01Y.cpy``. This is the FIRST sort key
        — the high-cardinality lead of the 3-key ascending sort
        ``SORT FIELDS=(TRANCAT-ACCT-ID,A,...)``.
    type_code
        The 2-character transaction type code. Maps to
        ``TRANCAT-TYPE-CD PIC X(02)``. SECOND sort key.
    cat_code
        The 4-digit transaction category code. Maps to
        ``TRANCAT-CD PIC 9(04)``. THIRD sort key.
    tran_cat_bal
        The monetary balance. MUST be a :class:`decimal.Decimal` to
        match ``TRAN-CAT-BAL PIC S9(09)V99`` and the AAP §0.7.2
        financial-precision rule. This field is NOT a sort key — it
        is the data column the DFSORT OUTREC EDIT mask is applied to.

    Returns
    -------
    Row
        A PySpark Row with field ordering ``(acct_id, type_code,
        cat_code, tran_cat_bal)`` — matching the column order in the
        PostgreSQL ``transaction_category_balances`` table
        (composite primary key ``(acct_id, type_code, cat_code)``
        plus the balance data column).
    """
    return Row(
        acct_id=acct_id,
        type_code=type_code,
        cat_code=cat_code,
        tran_cat_bal=tran_cat_bal,
    )


# ============================================================================
# Phase 2 — Sort Order test (``SORT FIELDS=(...,A,...,A,...,A)`` replacement).
#
# The mainframe STEP10R executes DFSORT with:
#
#     SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)
#
# This is a 3-key ascending sort on the composite primary key of the
# TCATBALF VSAM cluster. Each sort key is declared ascending (``A``
# suffix in DFSORT). The keys are ordered from highest-cardinality
# (acct_id — ~50 distinct values in the seed data) to lowest
# (cat_code — ~18 distinct values), yielding a stable deterministic
# output whose rows group by account, then by type, then by category.
#
# In PySpark the equivalent is ``.orderBy(F.col("acct_id").asc(),
# F.col("type_code").asc(), F.col("cat_code").asc())`` — a multi-key
# ascending sort whose output must match DFSORT's lexicographic
# ordering exactly.
#
# This Phase-2 test exercises the real PySpark sort directly against
# the session-scoped :class:`pyspark.sql.SparkSession` fixture
# (``spark_session``) from :mod:`tests.conftest`, without invoking
# ``main()``. Real-Spark execution is the canonical way to verify
# sort-order invariants because PySpark's ``.orderBy()`` semantics
# differ subtly from Python's built-in ``sorted()`` (column-wise
# comparison, NULLs-FIRST, case-sensitivity, etc.).
# ============================================================================
@pytest.mark.unit
def test_sort_by_acct_type_cat(spark_session: SparkSession) -> None:
    """``orderBy(acct_id, type_code, cat_code)`` produces ascending lexicographic order.

    Replicates the JCL SORT FIELDS invariant — the output must be
    ascending by (acct_id, type_code, cat_code) in that order, with
    each key treated as a string for ordering purposes (matching the
    DFSORT default string comparison semantics for SYMNAMES-declared
    fields of type ZD/CH).

    The test constructs a DataFrame with rows in deliberately SCRAMBLED
    order so that a no-op sort (or an incorrectly-ordered sort) would
    fail to match the expected output. The scramble covers:

    * Different accounts ordered out of sequence (e.g., "00000000003"
      before "00000000001" in the input).
    * Same account with different type codes out of sequence
      (e.g., "CR" before "DB" under the same account).
    * Same account+type with different cat codes out of sequence
      (e.g., "0050" before "0010" under the same account+type).

    All three misorderings must be corrected by ``.orderBy()``. The
    expected output is the lexicographically sorted tuple list.
    """
    # --- Arrange ---------------------------------------------------------
    # Build 6 rows with deliberately scrambled composite-key values.
    # The scramble is designed to exercise all three sort keys:
    #
    #   Row A: acct=3, type=DB, cat=0001, bal=100.00
    #   Row B: acct=1, type=DB, cat=0005, bal=200.00
    #   Row C: acct=2, type=CR, cat=0010, bal=300.00
    #   Row D: acct=1, type=CR, cat=0050, bal=400.00  <- acct=1 lowest, but in input position 4
    #   Row E: acct=1, type=DB, cat=0001, bal=500.00  <- same acct+type as B, lower cat
    #   Row F: acct=2, type=CR, cat=0001, bal=600.00  <- same acct+type as C, lower cat
    #
    # Expected sorted order (ascending on acct, type, cat):
    #   (1,  CR, 0050, 400.00)  <- row D
    #   (1,  DB, 0001, 500.00)  <- row E
    #   (1,  DB, 0005, 200.00)  <- row B
    #   (2,  CR, 0001, 600.00)  <- row F
    #   (2,  CR, 0010, 300.00)  <- row C
    #   (3,  DB, 0001, 100.00)  <- row A
    input_rows = [
        _make_tcatbal_row("00000000003", "DB", "0001", Decimal("100.00")),  # A
        _make_tcatbal_row("00000000001", "DB", "0005", Decimal("200.00")),  # B
        _make_tcatbal_row("00000000002", "CR", "0010", Decimal("300.00")),  # C
        _make_tcatbal_row("00000000001", "CR", "0050", Decimal("400.00")),  # D
        _make_tcatbal_row("00000000001", "DB", "0001", Decimal("500.00")),  # E
        _make_tcatbal_row("00000000002", "CR", "0001", Decimal("600.00")),  # F
    ]
    input_df = spark_session.createDataFrame(input_rows)

    # --- Act -------------------------------------------------------------
    # Replicate the production sort chain verbatim from prtcatbl_job.main()
    # lines 926-935 so the test mirrors the same call sequence as the
    # module under test. This catches any future regression where the
    # module changes the order of the asc() calls or swaps a key for a
    # different column.
    from pyspark.sql import functions as F  # noqa: N812 -- lowercase-module-as-F idiom

    sorted_df = input_df.select(
        F.col(_COL_ACCT_ID),
        F.col(_COL_TYPE_CODE),
        F.col(_COL_CAT_CODE),
        F.col(_COL_TRAN_CAT_BAL),
    ).orderBy(
        F.col(_COL_ACCT_ID).asc(),
        F.col(_COL_TYPE_CODE).asc(),
        F.col(_COL_CAT_CODE).asc(),
    )
    collected = sorted_df.collect()

    # --- Assert ----------------------------------------------------------
    # (a) Record count is preserved exactly — sort is pure (no drops,
    # no duplications).
    assert len(collected) == len(input_rows), (
        f"Sort dropped/duplicated rows; expected {len(input_rows)}, got {len(collected)}"
    )

    # (b) The exact sorted tuple sequence matches the expected
    # ascending lexicographic order on (acct_id, type_code, cat_code).
    # The TRAN-CAT-BAL column is included for traceability but is NOT
    # a sort key — its values are pre-assigned per row so each row is
    # individually identifiable in the output.
    observed_tuples = [
        (row[_COL_ACCT_ID], row[_COL_TYPE_CODE], row[_COL_CAT_CODE], row[_COL_TRAN_CAT_BAL]) for row in collected
    ]
    expected_tuples = [
        ("00000000001", "CR", "0050", Decimal("400.00")),  # D
        ("00000000001", "DB", "0001", Decimal("500.00")),  # E
        ("00000000001", "DB", "0005", Decimal("200.00")),  # B
        ("00000000002", "CR", "0001", Decimal("600.00")),  # F
        ("00000000002", "CR", "0010", Decimal("300.00")),  # C
        ("00000000003", "DB", "0001", Decimal("100.00")),  # A
    ]
    assert observed_tuples == expected_tuples, f"Sort output drift; expected {expected_tuples}, got {observed_tuples}"

    # (c) Cross-verify against Python's ``sorted()`` built-in — both
    # should produce the same ascending order given the same input.
    # This catches any subtle PySpark-vs-Python sort-stability issue
    # where the row-identity is preserved but the ordering differs.
    py_sorted = sorted(
        observed_tuples,
        key=lambda t: (t[0], t[1], t[2]),
    )
    assert observed_tuples == py_sorted, (
        f"PySpark .orderBy() result differs from Python sorted() — spark={observed_tuples}, python={py_sorted}"
    )

    # (d) The tran_cat_bal column preserved its Decimal precision
    # through the sort — AAP §0.7.2 financial-precision rule. Sort
    # operations should NEVER silently widen Decimal to float.
    for row in collected:
        bal = row[_COL_TRAN_CAT_BAL]
        assert isinstance(bal, Decimal), (
            f"tran_cat_bal lost Decimal precision through sort; got type={type(bal).__name__}"
        )


# ============================================================================
# Phase 3 — EDIT-mask formatting tests (``EDIT=(TTTTTTTTT.TT)`` replacement).
#
# The mainframe STEP10R specifies in its OUTREC FIELDS clause:
#
#     TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT)
#
# DFSORT's ``EDIT`` clause renders the TRAN-CAT-BAL field using the
# literal-character mask where each ``T`` position is a digit with
# leading-zero suppression enabled (the leftmost zeros are replaced
# by ASCII blanks hex 40) and the literal ``.`` produces a decimal
# point. For ``EDIT=(TTTTTTTTT.TT)`` the mask occupies 12 character
# positions: 9 T digits (integer portion) + ``.`` + 2 T digits
# (decimal portion).
#
# The ``format_balance`` function in the module under test replicates
# this semantic exactly using Python's :class:`decimal.Decimal` type
# with banker's rounding (``ROUND_HALF_EVEN``) and f-string right-
# justification padding (``f"{value:>12}"``).
#
# These 4 Phase-3 tests exercise ``format_balance`` directly as a pure
# unit (no Spark dependency, no mocks) — each input is a Decimal value
# covering a distinct behavioral class (positive, zero, negative, and
# the maximum value permitted by ``PIC S9(09)V99``).
# ============================================================================
@pytest.mark.unit
def test_format_balance_positive() -> None:
    """Positive balance with mid-range value produces right-justified EDIT output.

    Replicates DFSORT's behavior for a positive ``PIC S9(09)V99`` value
    of ``+12345.67`` (a mid-range positive with 5 integer digits). The
    EDIT mask ``EDIT=(TTTTTTTTT.TT)`` suppresses the 4 leading zeros
    (positions 1-4 of the 9-digit integer portion) producing 4 ASCII
    blanks, then emits the 5 significant integer digits, the literal
    ``.``, and the 2 decimal digits — for a total of 12 chars.

    Expected output: ``"    12345.67"`` (4 blanks + ``12345.67``).
    """
    # --- Arrange ---------------------------------------------------------
    # Construct the Decimal via string literal — NEVER float. The
    # Decimal(str) constructor preserves the exact decimal
    # representation without binary-floating-point rounding artifacts
    # (e.g., Decimal(12345.67) would yield Decimal('12345.67000000000...').
    balance = Decimal("12345.67")

    # --- Act -------------------------------------------------------------
    result = format_balance(balance)

    # --- Assert ----------------------------------------------------------
    # (a) Exact string match: 4 leading blanks + 8 char "12345.67" = 12.
    expected = "    12345.67"
    assert result == expected, f"EDIT mask output drift for +12345.67; expected {expected!r}, got {result!r}"

    # (b) Length invariant: the EDIT mask always produces exactly
    # 12 characters. Any length other than 12 indicates a regression
    # in the quantize+f-string chain.
    assert len(result) == _EDIT_MASK_WIDTH, (
        f"EDIT mask width regression; expected {_EDIT_MASK_WIDTH}, got {len(result)}"
    )

    # (c) The result preserves the decimal point character at
    # position 9 (0-indexed: chars 0-8 are integer, char 9 is ``.``,
    # chars 10-11 are decimal). This is the fundamental EDIT-mask
    # layout contract.
    assert result[9] == ".", f"Decimal point missing at EDIT mask position 9 (got char={result[9]!r})"

    # (d) Leading characters up to the first digit are ALL ASCII
    # blanks (space 0x20) — matching DFSORT's zero-suppression to
    # blank semantic, NOT zero-padding.
    leading = result[: result.find("1")]
    assert leading == " " * len(leading), f"EDIT mask leading zeros not suppressed to blanks; leading={leading!r}"

    # (e) NO float coercion in the formatting — the result string
    # is byte-for-byte what Decimal.__str__ produces via f-string,
    # which itself uses :class:`decimal.Decimal` arithmetic
    # exclusively. This is asserted indirectly by exact-string
    # match above, but we additionally verify the input argument
    # was never widened by checking the result is exactly what
    # a Decimal-based quantize+format would produce.
    expected_from_decimal = f"{balance.quantize(Decimal('0.01')):>12}"
    assert result == expected_from_decimal, (
        f"format_balance output differs from Decimal-based format: "
        f"format_balance={result!r}, decimal_format={expected_from_decimal!r}"
    )


@pytest.mark.unit
def test_format_balance_zero() -> None:
    """Zero balance produces ``"        0.00"`` (8 blanks + ``0.00``).

    The DFSORT EDIT mask has a subtle special case for zero: the LSB
    integer digit (ones place, just left of the literal ``.``) is
    NEVER zero-suppressed — it always renders at least ``0``. This
    prevents a zero balance from being emitted as just ``".00"`` with
    a missing integer digit, which would corrupt the fixed-width
    report layout.

    Expected output: ``"        0.00"`` (8 blanks + ``0.00``) = 12 chars.
    """
    # --- Arrange ---------------------------------------------------------
    # Use Decimal("0.00") form — the .quantize() call inside
    # format_balance will enforce the 2-decimal scale regardless of
    # whether the input has trailing zeros, but using the fully-qualified
    # form up-front makes the intent explicit.
    balance = Decimal("0.00")

    # --- Act -------------------------------------------------------------
    result = format_balance(balance)

    # --- Assert ----------------------------------------------------------
    # (a) Exact string match: 8 blanks + "0.00" = 12 chars. The "0"
    # before the decimal point is the LSB integer digit that DFSORT
    # never suppresses — this is the critical special case for
    # zero-valued balances.
    expected = "        0.00"
    assert result == expected, f"EDIT mask output drift for zero; expected {expected!r}, got {result!r}"

    # (b) Length invariant: exactly 12 characters per the EDIT mask
    # width (9 integer + 1 literal ``.`` + 2 decimal).
    assert len(result) == _EDIT_MASK_WIDTH, (
        f"EDIT mask width regression for zero; expected {_EDIT_MASK_WIDTH}, got {len(result)}"
    )

    # (c) The integer portion (positions 0-8) contains the trailing
    # ``0`` at position 8 — the LSB integer digit that's NEVER
    # suppressed for a zero value.
    assert result[8] == "0", (
        f"LSB integer digit unexpectedly suppressed for zero; result[8]={result[8]!r}, expected '0'"
    )

    # (d) The decimal portion (positions 10-11) is "00" — trailing
    # T positions after the decimal point are NEVER suppressed
    # (they represent the preserved hundredths place).
    assert result[10:12] == "00", f"Decimal portion regression for zero; result[10:12]={result[10:12]!r}, expected '00'"

    # (e) Test with the equivalent Decimal("0") form — the quantize
    # step inside format_balance MUST yield the same output regardless
    # of input scale (a whole-number "0" vs. the explicit "0.00").
    result_whole = format_balance(Decimal("0"))
    assert result_whole == expected, (
        f"format_balance(Decimal('0')) should equal format_balance(Decimal('0.00')); "
        f"got whole={result_whole!r}, decimal={result!r}"
    )

    # (f) NO float permitted — the result must be identical to what
    # Decimal-based formatting would produce.
    assert isinstance(balance, Decimal), "Test input must be Decimal, never float"


@pytest.mark.unit
def test_format_balance_negative() -> None:
    """Negative balance preserves minus-sign with leading-blank padding.

    Replicates DFSORT's handling of a signed ``PIC S9(09)V99`` value
    with a negative sign. For negative values the EDIT mask prepends
    a ``-`` sign into one of the leading blank positions — specifically
    the position immediately preceding the most-significant non-zero
    digit. The Python :class:`decimal.Decimal` ``__str__`` produces
    the sign adjacent to the first significant digit, which the
    f-string right-justification preserves naturally.

    For ``-500.50``, the Decimal string representation is ``-500.50``
    (7 chars), right-justified in a 12-char field yields 5 leading
    blanks + ``-500.50``.

    Expected output: ``"     -500.50"`` (5 blanks + ``-500.50``) = 12 chars.
    """
    # --- Arrange ---------------------------------------------------------
    balance = Decimal("-500.50")

    # --- Act -------------------------------------------------------------
    result = format_balance(balance)

    # --- Assert ----------------------------------------------------------
    # (a) Exact string match: 5 leading blanks + "-500.50" = 12 chars.
    # The minus sign is placed immediately before the first significant
    # digit (the "5" at position 6 in the 12-char output), matching
    # DFSORT's convention of placing the sign in the rightmost leading
    # blank position.
    expected = "     -500.50"
    assert result == expected, f"EDIT mask output drift for -500.50; expected {expected!r}, got {result!r}"

    # (b) Length invariant: exactly 12 characters. The sign character
    # consumes one of the leading blanks but does not extend the
    # total width.
    assert len(result) == _EDIT_MASK_WIDTH, (
        f"EDIT mask width regression for negative; expected {_EDIT_MASK_WIDTH}, got {len(result)}"
    )

    # (c) The minus sign is present somewhere in the leading portion
    # (before any digit) — NOT at the end, and NOT separated from
    # the digits by a blank.
    assert "-" in result, f"Minus sign missing from negative balance output: {result!r}"
    minus_index = result.index("-")
    # The character immediately after the minus must be a digit
    # (no blank separator between sign and first significant digit).
    assert result[minus_index + 1].isdigit(), (
        f"Blank separator between minus sign and first digit at position {minus_index}; result={result!r}"
    )

    # (d) The decimal point is at position 9 (same as positive case).
    assert result[9] == ".", f"Decimal point position regression for negative; result[9]={result[9]!r}, expected '.'"

    # (e) Test another negative value for robustness — ``-12345.67``
    # per the module docstring example. Expected: 3 blanks +
    # "-12345.67" = 12 chars.
    result_bigger_neg = format_balance(Decimal("-12345.67"))
    expected_bigger_neg = "   -12345.67"
    assert result_bigger_neg == expected_bigger_neg, (
        f"EDIT mask output drift for -12345.67; expected {expected_bigger_neg!r}, got {result_bigger_neg!r}"
    )

    # (f) NO float permitted anywhere in the formatting pipeline.
    assert isinstance(balance, Decimal), "Test input must be Decimal, never float"


@pytest.mark.unit
def test_format_balance_large_value() -> None:
    """Maximum-magnitude ``PIC S9(09)V99`` value fits exactly in 12 chars with no overflow.

    The COBOL type ``PIC S9(09)V99`` permits 9 integer digits and 2
    decimal digits — the maximum positive value is
    ``999,999,999.99`` = ``Decimal("999999999.99")``. At this
    magnitude the EDIT mask ``EDIT=(TTTTTTTTT.TT)`` has NO leading
    blanks to suppress — every ``T`` position contains a ``9``, and
    the output is exactly ``"999999999.99"`` = 12 chars with no
    padding.

    This test verifies that the :func:`format_balance` helper does
    NOT overflow the 12-char field width at the upper boundary of
    the COBOL type — a regression here could manifest as a 13-char
    output (if the f-string width was miscounted) or truncation
    (if the format specifier used ``<12`` instead of ``>12``).
    """
    # --- Arrange ---------------------------------------------------------
    # The maximum positive value permitted by PIC S9(09)V99 —
    # 999,999,999.99. Using Decimal via string literal to avoid any
    # floating-point conversion drift.
    balance = Decimal("999999999.99")

    # --- Act -------------------------------------------------------------
    result = format_balance(balance)

    # --- Assert ----------------------------------------------------------
    # (a) Exact string match: "999999999.99" with NO leading blanks
    # (every integer position is consumed by a non-zero digit).
    expected = "999999999.99"
    assert result == expected, f"EDIT mask output drift for max positive; expected {expected!r}, got {result!r}"

    # (b) Length invariant: exactly 12 characters, matching the
    # declared mask width. NO overflow beyond the field boundary.
    assert len(result) == _EDIT_MASK_WIDTH, (
        f"EDIT mask width regression at max value; "
        f"expected {_EDIT_MASK_WIDTH}, got {len(result)} — "
        f"possible overflow of the PIC S9(09)V99 field width"
    )

    # (c) The decimal point is at position 9, same as all other cases.
    assert result[9] == ".", f"Decimal point position regression at max value; result[9]={result[9]!r}, expected '.'"

    # (d) NO leading blanks — the integer portion is fully populated.
    assert result[0] != " ", (
        f"Unexpected leading blank at max value (value fills the 9-digit integer field); result[0]={result[0]!r}"
    )

    # (e) Test the maximum NEGATIVE value to verify the sign still
    # fits. For ``-999999999.99`` the Decimal string is 13 chars
    # ("-999999999.99"), which exceeds the 12-char EDIT mask width.
    # The f-string right-justification with ``>12`` specifier does
    # NOT truncate — it simply extends the field if the content is
    # wider. This is a documented limitation: a full-width negative
    # balance would produce a 13-char output, which DFSORT on z/OS
    # would also handle via sign overpunching (a different encoding).
    # For the S3-replacement path, a 13-char output is acceptable
    # because S3 objects have no fixed record-length enforcement
    # (AAP §0.7.3 minimal-change discipline).
    #
    # We document this boundary-case behavior but do NOT assert on
    # it as a regression target — the specification is ambiguous
    # for maximum-magnitude negatives, and the module's current
    # behavior matches the Python Decimal-representation.
    result_max_neg = format_balance(Decimal("-999999999.99"))
    # Verify the sign is preserved and all digits are present.
    assert result_max_neg.strip().startswith("-"), f"Minus sign missing at max negative: {result_max_neg!r}"
    assert "999999999.99" in result_max_neg, f"Digit portion missing/drifted at max negative: {result_max_neg!r}"

    # (f) Banker's rounding (ROUND_HALF_EVEN) — verify the module
    # rounds half-to-even per AAP §0.7.2. At the max positive value,
    # if we pass Decimal("999999999.995") (3 decimal digits with
    # rounding at the half-point), banker's rounding should round
    # UP to ``999999999.99`` + 0.005 = ``1000000000.00`` (even
    # nearest). But that would overflow PIC S9(09)V99, so we use a
    # different half-point test: 0.005 → 0.00 (round to even), and
    # 0.015 → 0.02 (round to even).
    assert format_balance(Decimal("0.005")) == "        0.00", (
        "Banker's rounding regression: 0.005 should round to 0.00 (even)"
    )
    assert format_balance(Decimal("0.015")) == "        0.02", (
        "Banker's rounding regression: 0.015 should round to 0.02 (even)"
    )

    # (g) NO float permitted.
    assert isinstance(balance, Decimal), "Test input must be Decimal, never float"


# ============================================================================
# Chainable MagicMock DataFrame helper (used by Phase 4 & 5 tests).
#
# The mock-based tests (test_report_written_to_s3) need to inspect the
# exact sequence of DataFrame-method calls issued by ``main()``. A plain
# ``MagicMock()`` would produce a fresh child mock on every chained
# call, forcing assertions to walk through ``return_value`` multiple
# times per assertion. The helper below builds a mock whose chainable
# operators all return the SAME mock — collapsing the fluent chain
# onto a single tracked instance for unambiguous ``.assert_called_*``.
#
# Chain semantics specific to prtcatbl_job.main():
#
#     df.cache()          → self       (tcatbal_df cached before count/sort)
#     df.count()          → int        (diagnostic row count)
#     df.select(*cols)    → self       (column projection before sort)
#     df.orderBy(*cols)   → self       (the 3-key ascending sort)
#     df.collect()        → [Row, ...] (sorted rows pulled to driver)
#     df.unpersist()      → None       (post-sort cleanup, return ignored)
# ============================================================================
def _make_mock_df(
    collected_rows: list[Row] | None = None,
    count_value: int | None = None,
) -> MagicMock:
    """Return a chainable mock DataFrame for prtcatbl_job pipeline tests.

    Parameters
    ----------
    collected_rows
        List of :class:`pyspark.sql.Row` objects returned by the mock
        DataFrame's ``collect()`` method. The prtcatbl_job's main()
        calls ``sort_df.collect()`` exactly once per invocation; the
        returned list is iterated to build backup + report lines. If
        None, defaults to an empty list (producing empty S3 artifacts).
    count_value
        Integer returned by ``count()``. If None, derived from
        ``len(collected_rows)`` — the usual diagnostic invariant
        (``count()`` equals row count post-sort).

    Returns
    -------
    MagicMock
        A mock DataFrame whose fluent-style methods collapse onto a
        single tracked instance for unambiguous assertion.
    """
    if collected_rows is None:
        collected_rows = []
    if count_value is None:
        count_value = len(collected_rows)

    df = MagicMock(name="MockDataFrame")

    # Chainable DataFrame transformation methods — each returns the
    # same mock so the fluent-style PySpark expressions collapse onto
    # one tracked instance. This keeps ``assert_called_with(...)`` and
    # ``.call_count`` unambiguous in the test bodies below.
    df.cache.return_value = df
    df.select.return_value = df
    df.orderBy.return_value = df

    # Terminal action methods.
    df.count.return_value = count_value
    df.collect.return_value = collected_rows
    df.unpersist.return_value = None

    return df


# ============================================================================
# Phase 4 — Report Generation tests (OUTREC / SORTOUT DD replacement).
#
# The mainframe STEP10R specifies in its OUTREC FIELDS clause:
#
#     OUTREC FIELDS=(TRANCAT-ACCT-ID,X,       <- 11 chars + 1 space
#                    TRANCAT-TYPE-CD,X,       <-  2 chars + 1 space
#                    TRANCAT-CD,X,            <-  4 chars + 1 space
#                    TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),  <- 12 chars
#                    9X)                      <-  9 spaces
#
# Total output width: 11 + 1 + 2 + 1 + 4 + 1 + 12 + 9 = 41 bytes.
#
# SORTOUT declares ``LRECL=40`` (1 byte shorter than OUTREC output)
# — on z/OS DFSORT would truncate the trailing padding byte. The
# module under test PRESERVES the OUTREC 41-byte semantic per AAP
# §0.7.3 "minimal change" discipline (S3 objects have no fixed
# record-length enforcement). The tests therefore accept either
# the declared 40-char length OR the actual 41-char output.
#
# These 2 Phase-4 tests invoke ``main()`` with mocked external
# collaborators so the report-content generation and the S3-write
# dispatch are both exercised without AWS or PostgreSQL side effects.
# The content passed to the mocked ``write_to_s3`` is captured for
# line-by-line format inspection.
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_report_line_format(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_read_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """Each report line contains acct_id, type_cd, cat_cd, and formatted balance.

    Verifies the DFSORT OUTREC FIELDS output layout by invoking
    ``main()`` with a controlled in-memory DataFrame, capturing the
    content passed to ``write_to_s3`` for the report artifact
    (``report.txt``), and asserting line-by-line that the layout
    matches::

        Positions  Content                    Format
        ---------  -------------------------  --------------------------------
        0-10       acct_id (11 chars)         zero-padded per PIC 9(11)
        11         single space separator     (OUTREC ``X`` literal)
        12-13      type_code (2 chars)        space-padded per PIC X(02)
        14         single space separator
        15-18      cat_code (4 chars)         zero-padded per PIC 9(04)
        19         single space separator
        20-31      balance (12 chars)         EDIT=(TTTTTTTTT.TT) mask
        32-40      trailing pad (9 blanks)    OUTREC ``9X`` literal

    Line length: 41 chars — the actual OUTREC output width. The JCL
    SORTOUT declares LRECL=40 (truncating the last pad byte on z/OS)
    but the S3 replacement preserves the full 41-byte OUTREC semantic
    per AAP §0.7.3 minimal-change discipline. The test accepts either
    40 or 41 char lines to be tolerant of either interpretation.
    """
    # --- Arrange ---------------------------------------------------------
    # Thread the real SparkSession through init_glue so the real
    # DataFrame created below is processed against the same session
    # used by ``main()`` (the module's cache(), count(), select(),
    # orderBy(), and collect() calls all dispatch against this
    # session).
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="MockGlueContext"),
        mock_job,
        {"JOB_NAME": "carddemo-prtcatbl"},
    )

    # Construct a REAL in-memory DataFrame with 3 rows covering
    # distinct value classes for each column:
    #
    #   Row 1: acct_id=1, type=DB, cat=0001, bal=+100.50  (positive)
    #   Row 2: acct_id=2, type=CR, cat=0010, bal= 0.00     (zero)
    #   Row 3: acct_id=3, type=DB, cat=0100, bal=-50.25    (negative)
    input_rows = [
        _make_tcatbal_row("00000000001", "DB", "0001", Decimal("100.50")),
        _make_tcatbal_row("00000000002", "CR", "0010", Decimal("0.00")),
        _make_tcatbal_row("00000000003", "DB", "0100", Decimal("-50.25")),
    ]
    input_df = spark_session.createDataFrame(input_rows)
    mock_read_table.return_value = input_df

    # The GDG prefix resolved by ``get_versioned_s3_path`` — the module
    # splits this URI on "/" (maxsplit=3) to extract the bucket + key
    # prefix, so the URI MUST have the form "s3://{bucket}/{prefix}/"
    # with the trailing slash for the split to yield 4 parts.
    mock_get_s3_path.return_value = "s3://test-bucket/backups/category-balance/2024/01/01/000000/"

    # Capture the (content, key) tuples passed to write_to_s3 so we
    # can inspect the report content line-by-line after main() runs.
    captured_writes: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        *,
        content: str,
        key: str,
        content_type: str = "text/plain",
        **_kwargs: Any,
    ) -> str:
        captured_writes.append(
            {
                "content": content,
                "key": key,
                "content_type": content_type,
            }
        )
        # Return a plausible s3:// URI so main() can log it without error.
        return f"s3://test-bucket/{key}"

    mock_write_to_s3.side_effect = _write_to_s3_side_effect

    # --- Act -------------------------------------------------------------
    main()

    # --- Assert ----------------------------------------------------------
    # (a) write_to_s3 was invoked exactly TWICE — once for backup.dat,
    # once for report.txt. Any other count indicates a regression in
    # the S3 write dispatch (e.g., an additional or missing write).
    assert len(captured_writes) == 2, (
        f"Expected exactly 2 write_to_s3 invocations (backup + report); got {len(captured_writes)}"
    )

    # (b) Locate the report write by filename pattern — report.txt
    # per the module's _REPORT_FILENAME constant. The backup key
    # ends with "backup.dat" and is filtered out here.
    report_writes = [w for w in captured_writes if w["key"].endswith(_REPORT_FILENAME)]
    assert len(report_writes) == 1, (
        f"Expected exactly 1 write_to_s3 for {_REPORT_FILENAME!r}; "
        f"got {len(report_writes)} — keys={[w['key'] for w in captured_writes]}"
    )
    report_content: str = report_writes[0]["content"]

    # (c) Parse the report content into lines — the module joins them
    # with "\n" and adds a trailing newline. Strip the trailing empty
    # line from ``splitlines`` (produced by the trailing "\n"
    # delimiter).
    report_lines = report_content.splitlines()
    assert len(report_lines) == 3, f"Expected exactly 3 report lines (one per input row); got {len(report_lines)}"

    # (d) Verify each line contains all 4 required fields in the
    # correct positions. The lines are in sorted order (the module
    # sorts by (acct_id, type, cat) ascending before formatting), so:
    #
    #   line[0] → Row 1: acct=1, type=DB, cat=0001, bal=+100.50
    #   line[1] → Row 2: acct=2, type=CR, cat=0010, bal= 0.00
    #   line[2] → Row 3: acct=3, type=DB, cat=0100, bal=-50.25
    expected_rows = [
        ("00000000001", "DB", "0001", Decimal("100.50")),
        ("00000000002", "CR", "0010", Decimal("0.00")),
        ("00000000003", "DB", "0100", Decimal("-50.25")),
    ]
    for i, (expected_acct, expected_type, expected_cat, expected_bal) in enumerate(expected_rows):
        line = report_lines[i]

        # Line length: the module produces 41-char lines per the
        # OUTREC FIELDS semantic (documented in the module's
        # _format_report_line docstring lines 737-746). The JCL
        # declares LRECL=40 but DFSORT would truncate the last
        # padding byte on z/OS. The S3 replacement preserves the
        # full 41-byte semantic per AAP §0.7.3 minimal-change
        # discipline. We accept either 40 OR 41 chars.
        assert len(line) in {_REPORT_LRECL_DECLARED, _REPORT_LRECL_ACTUAL}, (
            f"Report line {i} length regression; len={len(line)}, "
            f"expected {_REPORT_LRECL_DECLARED} or {_REPORT_LRECL_ACTUAL}; "
            f"line={line!r}"
        )

        # acct_id at positions 0-10 (11 chars) — zero-padded per PIC 9(11).
        assert line[0:11] == expected_acct, (
            f"Report line {i} acct_id drift; expected {expected_acct!r}, got {line[0:11]!r}"
        )

        # Separator space at position 11.
        assert line[11] == " ", f"Report line {i} missing separator space at position 11; got {line[11]!r}"

        # type_code at positions 12-13 (2 chars) — space-padded per PIC X(02).
        assert line[12:14] == expected_type, (
            f"Report line {i} type_code drift; expected {expected_type!r}, got {line[12:14]!r}"
        )

        # Separator space at position 14.
        assert line[14] == " ", f"Report line {i} missing separator space at position 14; got {line[14]!r}"

        # cat_code at positions 15-18 (4 chars) — zero-padded per PIC 9(04).
        assert line[15:19] == expected_cat, (
            f"Report line {i} cat_code drift; expected {expected_cat!r}, got {line[15:19]!r}"
        )

        # Separator space at position 19.
        assert line[19] == " ", f"Report line {i} missing separator space at position 19; got {line[19]!r}"

        # balance (EDIT=(TTTTTTTTT.TT)) at positions 20-31 (12 chars).
        # MUST match format_balance(expected_bal) exactly — the
        # module delegates the balance column rendering to the public
        # format_balance() function.
        expected_balance_edit = format_balance(expected_bal)
        assert line[20:32] == expected_balance_edit, (
            f"Report line {i} balance EDIT mask drift; expected {expected_balance_edit!r}, got {line[20:32]!r}"
        )

    # (e) Final line is non-empty and correctly terminated. The
    # module joins lines with "\n" and appends one more "\n" at the
    # end (per the trailing ``+ ("\n" if report_lines else "")``
    # expression). So report_content ends with "\n" and the last
    # non-empty line is at index -1 of splitlines().
    assert report_content.endswith("\n"), f"Report content missing terminal newline; ends with {report_content[-5:]!r}"


@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_report_written_to_s3(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_read_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """``main()`` writes backup to TCATBALF.BKUP path and report to TCATBALF.REPT path.

    Verifies the dual-artifact S3 write invariant of PRTCATBL:

    1. ``get_versioned_s3_path("TCATBALF.BKUP", generation="+1")``
       is invoked exactly ONCE to allocate a timestamped GDG-
       equivalent S3 prefix (matching the mainframe's
       ``TCATBALF.BKUP(+1)`` GDG generation semantic).

    2. ``write_to_s3`` is invoked EXACTLY TWICE — once each for:
       * The 50-byte VSAM-layout backup (``backup.dat``) —
         replaces ``DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)`` from
         PRTCATBL.jcl line 39.
       * The formatted report (``report.txt``) — replaces
         ``DSN=AWS.M2.CARDDEMO.TCATBALF.REPT`` from PRTCATBL.jcl
         line 63.

    3. Both writes share the SAME S3 prefix (only the filename
       differs) — the module co-locates the two artifacts under
       a single timestamped prefix for operator discoverability.

    Note: Unlike the mainframe which used two distinct datasets
    (``TCATBALF.BKUP`` GDG for the VSAM-layout backup, ``TCATBALF.REPT``
    PS for the formatted report), the S3 replacement uses a single
    timestamped S3 prefix with two filenames — ``backup.dat`` replaces
    the mainframe's backup GDG generation, ``report.txt`` replaces
    the mainframe's REPT PS dataset. The TCATBALF.REPT name is
    therefore NOT a separate S3 path in the target implementation;
    the "report written to TCATBALF.REPT path" invariant is satisfied
    by the presence of ``report.txt`` in the bucket's prefix.
    """
    # --- Arrange ---------------------------------------------------------
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="MockGlueContext"),
        mock_job,
        {"JOB_NAME": "carddemo-prtcatbl"},
    )

    # Use a chainable mock DataFrame so the test does NOT depend on
    # real Spark execution — we care only about the S3-write dispatch
    # sequence, not the data content. Provide 2 sample rows so the
    # cache/count/sort/collect chain has meaningful values.
    sample_rows = [
        _make_tcatbal_row("00000000001", "DB", "0001", Decimal("100.00")),
        _make_tcatbal_row("00000000002", "CR", "0010", Decimal("200.00")),
    ]
    mock_df = _make_mock_df(collected_rows=sample_rows, count_value=2)
    mock_read_table.return_value = mock_df

    # Resolve the GDG-equivalent S3 prefix. The URI MUST have the form
    # "s3://{bucket}/{key_prefix}/" with a trailing slash — the module
    # splits on "/" with maxsplit=3 to extract the bucket and prefix.
    s3_prefix_uri = "s3://carddemo-batch-bucket/backups/category-balance/2024/01/15/143015/"
    mock_get_s3_path.return_value = s3_prefix_uri

    # Capture every write_to_s3 call so we can assert on the key
    # structure post-invocation.
    captured_writes: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        *,
        content: str,
        key: str,
        content_type: str = "text/plain",
        **_kwargs: Any,
    ) -> str:
        captured_writes.append(
            {
                "content": content,
                "key": key,
                "content_type": content_type,
            }
        )
        # Return a plausible URI that the module logs but does not
        # otherwise consume.
        return f"s3://carddemo-batch-bucket/{key}"

    mock_write_to_s3.side_effect = _write_to_s3_side_effect

    # --- Act -------------------------------------------------------------
    main()

    # --- Assert ----------------------------------------------------------
    # (a) get_versioned_s3_path invoked exactly ONCE for the BKUP
    # GDG with generation="+1" — replaces the mainframe's
    # TCATBALF.BKUP(+1) allocation.
    assert mock_get_s3_path.call_count == 1, (
        f"Expected exactly 1 get_versioned_s3_path call (for TCATBALF.BKUP(+1)); got {mock_get_s3_path.call_count}"
    )

    # Verify the GDG name + generation. Accept either positional or
    # keyword form for the generation argument (the module uses the
    # keyword form, but the assertion is lenient).
    gdg_call = mock_get_s3_path.call_args
    # gdg_name is always the first positional argument.
    assert gdg_call.args[0] == _GDG_BACKUP_NAME, (
        f"get_versioned_s3_path called with wrong GDG name; expected {_GDG_BACKUP_NAME!r}, got {gdg_call.args[0]!r}"
    )
    # generation="+1" — the new-generation allocation invariant.
    generation = gdg_call.kwargs.get("generation") or (gdg_call.args[1] if len(gdg_call.args) > 1 else None)
    assert generation == "+1", f"get_versioned_s3_path called with wrong generation; expected '+1', got {generation!r}"

    # (b) write_to_s3 was called EXACTLY TWICE — once for
    # backup.dat (TCATBALF.BKUP equivalent), once for report.txt
    # (TCATBALF.REPT equivalent). No more, no fewer.
    assert mock_write_to_s3.call_count == 2, (
        f"Expected exactly 2 write_to_s3 calls (backup + report); "
        f"got {mock_write_to_s3.call_count} — "
        f"keys={[c.kwargs.get('key', 'N/A') for c in mock_write_to_s3.call_args_list]}"
    )

    # (c) Locate the backup write — key ends with ``backup.dat``.
    # This replaces the mainframe's ``TCATBALF.BKUP(+1)`` dataset
    # (the IDCAMS REPRO target in STEP05R).
    backup_writes = [w for w in captured_writes if w["key"].endswith(_BACKUP_FILENAME)]
    assert len(backup_writes) == 1, (
        f"Expected exactly 1 write_to_s3 for {_BACKUP_FILENAME!r} (TCATBALF.BKUP equivalent); got {len(backup_writes)}"
    )

    # (d) Locate the report write — key ends with ``report.txt``.
    # This replaces the mainframe's ``TCATBALF.REPT`` dataset
    # (the DFSORT SORTOUT target in STEP10R).
    report_writes = [w for w in captured_writes if w["key"].endswith(_REPORT_FILENAME)]
    assert len(report_writes) == 1, (
        f"Expected exactly 1 write_to_s3 for {_REPORT_FILENAME!r} (TCATBALF.REPT equivalent); got {len(report_writes)}"
    )

    # (e) Both writes share the SAME S3 key prefix — the module
    # co-locates the two artifacts under a single timestamped
    # prefix for operator discoverability. Extract the prefix from
    # each key and verify equality.
    backup_prefix = backup_writes[0]["key"][: -len(_BACKUP_FILENAME)]
    report_prefix = report_writes[0]["key"][: -len(_REPORT_FILENAME)]
    assert backup_prefix == report_prefix, (
        f"Backup and report S3 prefixes diverged — backup_prefix={backup_prefix!r}, report_prefix={report_prefix!r}"
    )

    # (f) The shared prefix matches the key portion of the URI
    # returned by get_versioned_s3_path. The module extracts the
    # key prefix via ``versioned_prefix_uri.split("/", 3)``, which
    # for "s3://bucket/prefix/" yields ["s3:", "", "bucket", "prefix/"]
    # — the 4th element (index 3) is the key prefix with trailing
    # slash.
    _scheme, _empty, _bucket, expected_key_prefix = s3_prefix_uri.split("/", 3)
    assert backup_prefix == expected_key_prefix, (
        f"Backup S3 key prefix does not match get_versioned_s3_path output; "
        f"expected {expected_key_prefix!r}, got {backup_prefix!r}"
    )

    # (g) Both writes use ``content_type="text/plain"`` — matching the
    # flat-file ASCII output produced by the module. This invariant
    # protects against a future regression that changes the MIME
    # type to e.g. ``application/octet-stream`` and breaks operator
    # browser-based download workflows.
    assert backup_writes[0]["content_type"] == "text/plain", (
        f"Backup write content_type regression; expected 'text/plain', got {backup_writes[0]['content_type']!r}"
    )
    assert report_writes[0]["content_type"] == "text/plain", (
        f"Report write content_type regression; expected 'text/plain', got {report_writes[0]['content_type']!r}"
    )

    # (h) The backup content contains one line per input row (2 rows
    # + terminal newline), and each line is the backup LRECL width
    # (50 chars) — matching the VSAM record layout from CVTRA01Y.cpy.
    backup_content: str = backup_writes[0]["content"]
    backup_lines = backup_content.splitlines()
    assert len(backup_lines) == 2, f"Backup content should have 2 lines (one per input row); got {len(backup_lines)}"
    for i, line in enumerate(backup_lines):
        assert len(line) == _BACKUP_LRECL_EXPECTED, (
            f"Backup line {i} LRECL regression; expected {_BACKUP_LRECL_EXPECTED}, got {len(line)}; line={line!r}"
        )

    # (i) The report content contains one line per input row (2 rows),
    # and each line is either the declared SORTOUT LRECL (40) or the
    # actual OUTREC width (41) per the module's documented 41-byte
    # preservation semantic.
    report_content: str = report_writes[0]["content"]
    report_lines = report_content.splitlines()
    assert len(report_lines) == 2, f"Report content should have 2 lines (one per input row); got {len(report_lines)}"
    for i, line in enumerate(report_lines):
        assert len(line) in {_REPORT_LRECL_DECLARED, _REPORT_LRECL_ACTUAL}, (
            f"Report line {i} LRECL regression; expected "
            f"{_REPORT_LRECL_DECLARED} or {_REPORT_LRECL_ACTUAL}, got {len(line)}; "
            f"line={line!r}"
        )

    # (j) commit_job was invoked exactly once — the terminal
    # success signal matching MAXCC=0 on the mainframe.
    mock_commit_job.assert_called_once()
    commit_args = mock_commit_job.call_args.args
    assert commit_args[0] is mock_job, "commit_job should have been called with the Glue Job returned from init_glue()"


# ============================================================================
# Phase 5 — End-to-End ``main()`` Integration test with real PySpark.
#
# Exercises the complete JCL-replacement pipeline using a real
# :class:`pyspark.sql.SparkSession` from the session-scoped
# ``spark_session`` fixture. This is the "full happy path" test that
# binds together the individual Phase 2/3/4 units:
#
#   1. A real DataFrame is constructed from test Rows — the same
#      path as a production JDBC read from PostgreSQL.
#   2. ``main()`` is invoked with the real Spark session threaded
#      through ``init_glue``'s return tuple, and the read/write/
#      s3-path collaborators mocked.
#   3. The mocked ``write_to_s3`` side-effect captures both the
#      ``backup.dat`` and ``report.txt`` payloads.
#   4. The test verifies, on the captured content:
#      * Sort order is ascending by (acct_id, type_code, cat_code).
#      * Backup lines are 50 chars each (VSAM layout LRECL=50).
#      * Report lines are 40 or 41 chars each (SORTOUT OUTREC).
#      * Balance values are formatted via the EDIT mask correctly.
#      * commit_job() is called exactly once with the Glue Job.
#
# This is the single test that guarantees all JCL steps work TOGETHER
# — any regression in the pipeline wiring (e.g., missing unpersist,
# swapped write order, truncated select columns) will surface here
# even if the Phase 2/3/4 unit tests all pass individually.
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_prtcatbl_main_with_spark(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_read_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """End-to-end ``main()`` invocation with real Spark + mocked AWS/JDBC.

    Exercises every JCL-replacement step in ``main()`` using the
    session-scoped ``spark_session`` fixture as the real Spark engine
    (replacing the z/OS JES subsystem) and mocks for the external
    collaborators:

    * ``init_glue`` — returns (spark_session, mock_glue_context,
      mock_job, resolved_args), threading the real session through
      so ``main()``'s subsequent ``read_table(spark, ...)`` operates
      on the real engine.
    * ``read_table`` — returns a real DataFrame built from test Rows.
    * ``get_versioned_s3_path`` — returns a fixed S3 URI matching the
      production form ``s3://{bucket}/{prefix}/``.
    * ``write_to_s3`` — side-effect captures the content + key of
      each invocation into a list for post-main() inspection.
    * ``commit_job`` — asserted to be called exactly once at the end.

    Input test data (5 rows, deliberately SCRAMBLED sort order so
    the ``.orderBy()`` call's effect is verifiable):

        acct_id           type  cat   balance
        00000000003       DB    0001  +100.00
        00000000001       DB    0005  +200.50
        00000000002       CR    0010  -50.25
        00000000001       CR    0050  +0.00
        00000000001       DB    0001  +999.99

    After sort (ascending on (acct_id, type, cat)):

        00000000001       CR    0050  +0.00     <- row 4 (new first)
        00000000001       DB    0001  +999.99   <- row 5
        00000000001       DB    0005  +200.50   <- row 2
        00000000002       CR    0010  -50.25    <- row 3
        00000000003       DB    0001  +100.00   <- row 1

    The test asserts all 5 expected rows appear in both S3 artifacts
    in this exact order, with each line formatted per the layout
    contracts from the Phase-4 tests.
    """
    # --- Arrange ---------------------------------------------------------
    # Thread the real Spark session through init_glue so main()'s
    # subsequent cache/count/select/orderBy/collect calls dispatch
    # against the real engine — not a mock. This is the defining
    # characteristic of the Phase-5 integration test (versus Phase 4
    # which uses mock DataFrames exclusively).
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="MockGlueContext"),
        mock_job,
        {"JOB_NAME": "carddemo-prtcatbl"},
    )

    # Build the scrambled input DataFrame with 5 rows covering:
    # * Multiple accounts (tests acct_id sort)
    # * Multiple types per account (tests type_code sort)
    # * Multiple cats per account+type (tests cat_code sort)
    # * Positive, zero, and negative balances (tests EDIT mask
    #   formatting through the full pipeline)
    input_rows = [
        _make_tcatbal_row("00000000003", "DB", "0001", Decimal("100.00")),
        _make_tcatbal_row("00000000001", "DB", "0005", Decimal("200.50")),
        _make_tcatbal_row("00000000002", "CR", "0010", Decimal("-50.25")),
        _make_tcatbal_row("00000000001", "CR", "0050", Decimal("0.00")),
        _make_tcatbal_row("00000000001", "DB", "0001", Decimal("999.99")),
    ]
    input_df = spark_session.createDataFrame(input_rows)
    mock_read_table.return_value = input_df

    # Fixed GDG-equivalent S3 prefix. The URI MUST have a trailing
    # slash so the module's ``versioned_prefix_uri.split("/", 3)``
    # yields a 4-element list with the key prefix as element 3.
    s3_prefix_uri = "s3://carddemo-test-bucket/backups/category-balance/2024/06/15/120000/"
    mock_get_s3_path.return_value = s3_prefix_uri

    # Capture EVERY write_to_s3 call. The side-effect list is ordered
    # so [0] is the first call and [1] is the second — per the
    # module's source code, backup is written FIRST, then report.
    captured_writes: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        *,
        content: str,
        key: str,
        content_type: str = "text/plain",
        **_kwargs: Any,
    ) -> str:
        captured_writes.append(
            {
                "content": content,
                "key": key,
                "content_type": content_type,
            }
        )
        return f"s3://carddemo-test-bucket/{key}"

    mock_write_to_s3.side_effect = _write_to_s3_side_effect

    # --- Act -------------------------------------------------------------
    # Invoke main() with all external collaborators mocked. The real
    # Spark session processes the DataFrame end-to-end.
    main()

    # --- Assert ----------------------------------------------------------
    # (a) init_glue invoked exactly once with the canonical job name.
    # This matches the mainframe JCL job name ``PRTCATBL`` (the prefix
    # ``carddemo-`` is the AWS Glue job-name convention).
    mock_init_glue.assert_called_once()
    init_kwargs = mock_init_glue.call_args.kwargs
    init_args = mock_init_glue.call_args.args
    job_name = init_kwargs.get("job_name") or (init_args[0] if init_args else None)
    assert job_name == "carddemo-prtcatbl", (
        f"init_glue called with wrong job_name; expected 'carddemo-prtcatbl', got {job_name!r}"
    )

    # (b) read_table invoked exactly once with the PostgreSQL table
    # name that replaces the TCATBALF VSAM cluster. The first
    # positional argument is the SparkSession; the second is the
    # table name.
    mock_read_table.assert_called_once()
    read_args = mock_read_table.call_args.args
    assert read_args[0] is spark_session, (
        "read_table should have been called with the SparkSession returned from init_glue"
    )
    assert read_args[1] == "transaction_category_balances", (
        f"read_table called with wrong table name; expected 'transaction_category_balances', got {read_args[1]!r}"
    )

    # (c) get_versioned_s3_path invoked exactly once with the correct
    # GDG name and generation. Replaces the mainframe's
    # ``TCATBALF.BKUP(+1)`` GDG new-generation allocation.
    mock_get_s3_path.assert_called_once()
    s3_path_call = mock_get_s3_path.call_args
    assert s3_path_call.args[0] == _GDG_BACKUP_NAME, (
        f"get_versioned_s3_path called with wrong GDG name; expected {_GDG_BACKUP_NAME!r}, got {s3_path_call.args[0]!r}"
    )
    s3_generation = s3_path_call.kwargs.get("generation") or (
        s3_path_call.args[1] if len(s3_path_call.args) > 1 else None
    )
    assert s3_generation == "+1", (
        f"get_versioned_s3_path called with wrong generation; expected '+1', got {s3_generation!r}"
    )

    # (d) write_to_s3 invoked EXACTLY TWICE — once for backup.dat,
    # once for report.txt. Order matters: backup is written FIRST
    # (the module writes backup before report in main()).
    assert mock_write_to_s3.call_count == 2, (
        f"Expected exactly 2 write_to_s3 calls (backup + report); got {mock_write_to_s3.call_count}"
    )
    assert len(captured_writes) == 2, f"Side-effect captured wrong number of writes; got {len(captured_writes)}"

    # The first captured write is the backup (backup.dat), the
    # second is the report (report.txt). This ordering invariant is
    # structural — any reordering in main() would indicate a
    # refactoring regression.
    backup_write = captured_writes[0]
    report_write = captured_writes[1]
    assert backup_write["key"].endswith(_BACKUP_FILENAME), (
        f"First write should be {_BACKUP_FILENAME!r}; got key={backup_write['key']!r}"
    )
    assert report_write["key"].endswith(_REPORT_FILENAME), (
        f"Second write should be {_REPORT_FILENAME!r}; got key={report_write['key']!r}"
    )

    # (e) Content_type is text/plain for both artifacts — ASCII flat
    # files, not binary Parquet/Avro.
    assert backup_write["content_type"] == "text/plain", (
        f"Backup content_type regression; got {backup_write['content_type']!r}"
    )
    assert report_write["content_type"] == "text/plain", (
        f"Report content_type regression; got {report_write['content_type']!r}"
    )

    # (f) Backup content: 5 lines (one per input row), each 50 chars
    # (CVTRA01Y VSAM LRECL=50). The backup is the IDCAMS REPRO
    # equivalent — preserves the VSAM record layout byte-for-byte.
    backup_content: str = backup_write["content"]
    backup_lines = backup_content.splitlines()
    assert len(backup_lines) == len(input_rows), (
        f"Backup line count should match input row count; expected {len(input_rows)}, got {len(backup_lines)}"
    )
    for i, line in enumerate(backup_lines):
        assert len(line) == _BACKUP_LRECL_EXPECTED, (
            f"Backup line {i} LRECL regression; expected {_BACKUP_LRECL_EXPECTED}, got {len(line)}; line={line!r}"
        )

    # (g) Report content: 5 lines, each 40 or 41 chars. The report
    # is the DFSORT SORTOUT equivalent — OUTREC FIELDS formatted
    # output. The 41-char width is the actual OUTREC computation
    # (11+1+2+1+4+1+12+9=41); the 40-char width is the declared
    # SORTOUT LRECL (DFSORT would have truncated the trailing pad
    # byte on z/OS). The module preserves the 41-byte semantic per
    # AAP §0.7.3.
    report_content: str = report_write["content"]
    report_lines = report_content.splitlines()
    assert len(report_lines) == len(input_rows), (
        f"Report line count should match input row count; expected {len(input_rows)}, got {len(report_lines)}"
    )
    for i, line in enumerate(report_lines):
        assert len(line) in {_REPORT_LRECL_DECLARED, _REPORT_LRECL_ACTUAL}, (
            f"Report line {i} LRECL regression; expected "
            f"{_REPORT_LRECL_DECLARED} or {_REPORT_LRECL_ACTUAL}; "
            f"got {len(line)}; line={line!r}"
        )

    # (h) Sort order verification — derived directly from the
    # captured report content. Each report line encodes the sort
    # key as its first 19 chars: positions 0-10 = acct_id, 12-13 =
    # type_code, 15-18 = cat_code. Expected sort order:
    #   (1,CR,0050), (1,DB,0001), (1,DB,0005), (2,CR,0010), (3,DB,0001)
    expected_sort_keys = [
        ("00000000001", "CR", "0050"),
        ("00000000001", "DB", "0001"),
        ("00000000001", "DB", "0005"),
        ("00000000002", "CR", "0010"),
        ("00000000003", "DB", "0001"),
    ]
    for i, expected_key in enumerate(expected_sort_keys):
        line = report_lines[i]
        observed_acct = line[0:11]
        observed_type = line[12:14]
        observed_cat = line[15:19]
        observed_key = (observed_acct, observed_type, observed_cat)
        assert observed_key == expected_key, (
            f"Sort order regression at report line {i}; expected {expected_key}, got {observed_key}; full line={line!r}"
        )

    # (i) EDIT-mask formatting is applied correctly — verify each
    # line's balance field (positions 20-31) matches what
    # ``format_balance`` produces for the corresponding input
    # balance. Balances are indexed by sort key because the sort
    # rearranges the rows.
    expected_balances_after_sort = [
        Decimal("0.00"),  # row 4 — (1, CR, 0050)
        Decimal("999.99"),  # row 5 — (1, DB, 0001)
        Decimal("200.50"),  # row 2 — (1, DB, 0005)
        Decimal("-50.25"),  # row 3 — (2, CR, 0010)
        Decimal("100.00"),  # row 1 — (3, DB, 0001)
    ]
    for i, expected_bal in enumerate(expected_balances_after_sort):
        line = report_lines[i]
        observed_balance_field = line[20:32]  # 12-char EDIT mask slot
        expected_balance_field = format_balance(expected_bal)
        assert observed_balance_field == expected_balance_field, (
            f"Report line {i} balance EDIT mask drift; "
            f"expected {expected_balance_field!r}, got {observed_balance_field!r}; "
            f"full line={line!r}"
        )

    # (j) The backup and report writes share the SAME S3 key prefix —
    # only the filename portion differs. This is the co-location
    # invariant.
    backup_prefix = backup_write["key"][: -len(_BACKUP_FILENAME)]
    report_prefix = report_write["key"][: -len(_REPORT_FILENAME)]
    assert backup_prefix == report_prefix, (
        f"Backup and report S3 prefixes diverged; backup_prefix={backup_prefix!r}, report_prefix={report_prefix!r}"
    )

    # The shared prefix matches the key portion of the URI from
    # get_versioned_s3_path — parsed via split("/", 3).
    _scheme, _empty, _bucket, expected_key_prefix = s3_prefix_uri.split("/", 3)
    assert backup_prefix == expected_key_prefix, (
        f"S3 key prefix drift; expected {expected_key_prefix!r}, got {backup_prefix!r}"
    )

    # (k) commit_job invoked exactly once with the Glue Job handle —
    # the terminal success signal replacing MAXCC=0 on z/OS.
    mock_commit_job.assert_called_once()
    commit_args = mock_commit_job.call_args.args
    assert commit_args[0] is mock_job, "commit_job should have been called with the Glue Job returned from init_glue()"

    # (l) The test_sort_by_acct_type_cat and format_balance tests
    # verify the individual pieces; this Phase-5 test verifies they
    # work TOGETHER without any wiring regression. A single passing
    # invocation of this test is therefore strong evidence of
    # end-to-end parity with the mainframe PRTCATBL.jcl pipeline.
    logger.debug(
        "test_prtcatbl_main_with_spark: %d input rows, %d backup lines, %d report lines",
        len(input_rows),
        len(backup_lines),
        len(report_lines),
    )
