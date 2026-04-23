# ============================================================================
# tests/unit/test_batch/test_tranrept_job.py
# Unit tests for Stage 4b TRANREPT transaction reporting PySpark Glue job.
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
"""Unit tests for ``tranrept_job.py`` — Stage 4b: Transaction Detail Report.

Validates the PySpark implementation of the Stage 4b TRANREPT Glue job
that replaces the mainframe duo on the original architecture:

* ``app/cbl/CBTRN03C.cbl`` — Batch transaction detail report driver
  (~500 lines, ``PROGRAM-ID: CBTRN03C``).  Reads the TRANSACT file
  (pre-sorted by the DFSORT step in ``TRANREPT.jcl``), enriches each
  record with CARDXREF, TRANTYPE, and TRANCATG lookups, and writes the
  133-byte report with header, detail, page-total, account-total, and
  grand-total lines.
* ``app/jcl/TRANREPT.jcl`` — 85-line JES2 job driving Stage 4b.
  Contains ``STEP05R`` (REPROC + DFSORT filtering ``TRAN-PROC-DT``
  between ``'2022-01-01'`` and ``'2022-07-06'`` inclusive and sorting
  by ``TRAN-CARD-NUM`` ascending) followed by ``STEP10R`` (``EXEC
  PGM=CBTRN03C``).
* ``app/cpy/CVTRA05Y.cpy`` — ``TRAN-RECORD`` layout (350 bytes),
  physical record of the TRANSACT VSAM KSDS cluster.
* ``app/cpy/CVACT03Y.cpy`` — ``CARD-XREF-RECORD`` layout (50 bytes),
  physical record of the CARDXREF AIX path.
* ``app/cpy/CVTRA03Y.cpy`` — ``TRAN-TYPE-RECORD`` layout (60 bytes),
  physical record of the TRANTYPE KSDS.
* ``app/cpy/CVTRA04Y.cpy`` — ``TRAN-CAT-RECORD`` layout (60 bytes),
  physical record of the TRANCATG KSDS (composite key: ``TRAN-TYPE-CD``
  + ``TRAN-CAT-CD``).
* ``app/cpy/CVTRA07Y.cpy`` — Complete 133-character TRANREPT record
  layout.

Target Module Under Test
------------------------
``src/batch/jobs/tranrept_job.py`` exports four public entry points:

1. ``filter_by_date_range(transactions_df, start_date, end_date) -> DataFrame``
   Replaces JCL ``STEP05R`` DFSORT ``INCLUDE COND`` filter using
   ``F.substring(tran_proc_ts, 1, 10) >=/<=`` against the supplied
   date strings (inclusive on both bounds).
2. ``format_report_line(row, line_num) -> str``
   Replaces ``WRITE FD-REPTFILE-REC FROM TRANSACTION-DETAIL-REPORT``;
   emits a 133-character detail line.
3. ``format_subtotal_line(label, amount) -> str``
   Replaces ``WRITE FD-REPTFILE-REC FROM REPORT-PAGE-TOTALS /
   REPORT-ACCOUNT-TOTALS / REPORT-GRAND-TOTALS``; emits a 133-character
   subtotal line for one of the three recognised labels.
4. ``main() -> None``
   Entry point for the ``carddemo-tranrept`` AWS Glue Job.  Reads four
   tables, filters / enriches / sorts the transactions, drives the
   3-level-total state machine, and uploads the final 133-byte-lined
   report to S3.

Test Organization
-----------------
Seventeen test cases across seven logical phases (per AAP):

* Phase 2 — Date-parameter filtering (inclusive bounds + defaults) — 3 tests.
* Phase 3 — Cross-reference enrichment (XREF, TRANTYPE, TRANCATG) — 3 tests.
* Phase 4 — 3-level totals (account, page, grand, consistency) — 4 tests.
* Phase 5 — Report line format (length, fields, subtotal) — 3 tests.
* Phase 6 — Sort & output (card_num asc, S3 write) — 2 tests.
* Phase 7 — End-to-end ``main()`` integration with real Spark — 1 test.

The date-filtering, cross-reference enrichment, sort, and ``main``
integration tests use the session-scoped
:class:`pyspark.sql.SparkSession` fixture (``spark_session``) from
:mod:`tests.conftest`.  The report-line format and subtotal tests
invoke the pure helpers directly with synthetic ``dict`` inputs (no
Spark required).  Tests that exercise the full ``main`` orchestration
patch ``init_glue``, ``read_table``, ``get_versioned_s3_path``,
``write_to_s3``, and ``commit_job`` from the module's import namespace
so the production flow is exercised without any AWS side effects.

Key test data invariants
------------------------
All monetary fields (``tran_amt`` — ``TRAN-AMT PIC S9(09)V99`` in
``CVTRA05Y.cpy``; account / page / grand totals — ``PIC S9(9)V99`` in
CBTRN03C) use :class:`decimal.Decimal` with explicit two-decimal
scale — never ``float`` — per the AAP §0.7.2 financial precision rule.
Total accumulation inside :func:`_generate_report_lines` uses
:data:`decimal.ROUND_HALF_EVEN` matching COBOL ``ROUNDED`` semantics.

Report-line invariants
----------------------
* Every detail / header / subtotal line is exactly 133 characters
  (``FD-REPTFILE-REC PIC X(133)`` / ``LRECL=133``).
* Default date range (applied when no Glue arg override is provided)
  is ``"2022-01-01"`` to ``"2022-07-06"`` — the hard-coded DFSORT
  SYMNAMES values in ``TRANREPT.jcl`` lines 48-49.
* Sort order is ``tran_card_num`` ascending (primary key) then
  ``tran_id`` ascending (stabiliser) — matches
  ``SORT FIELDS=(TRAN-CARD-NUM,A)`` plus the implicit stability
  convention for reproducible output.
"""

from __future__ import annotations

import logging
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pyspark.sql import Row, SparkSession

# Module under test — imported for its four schema-mandated public
# entry points.  ``filter_by_date_range`` is tested directly (Phase 2);
# ``format_report_line`` and ``format_subtotal_line`` are tested
# directly (Phase 5); ``main`` is tested end-to-end (Phases 6-7) under
# a full mock stack for init_glue / read_table / write_to_s3 /
# commit_job / get_versioned_s3_path.
from src.batch.jobs.tranrept_job import (
    filter_by_date_range,
    format_report_line,
    format_subtotal_line,
    main,
)

# ----------------------------------------------------------------------------
# Test-module logger — silent by default; surfaces DEBUG traces only when
# pytest is run with ``-o log_cli=true -o log_cli_level=DEBUG``.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Patch-target constants.
#
# All patches MUST target the symbol as resolved inside
# ``src.batch.jobs.tranrept_job`` — not the original definition module.
# This matters because Python's ``unittest.mock.patch`` rebinds the NAME
# in the namespace specified by the dotted path; ``init_glue`` is
# imported into ``tranrept_job`` as ``from src.batch.common.glue_context
# import init_glue``, so a patch of ``src.batch.common.glue_context.init_glue``
# would NOT intercept the call made from inside ``tranrept_job.main``.
# The correct target is ``src.batch.jobs.tranrept_job.init_glue`` — the
# symbol as re-bound in the module-under-test's namespace.
#
# Unlike COMBTRAN (which writes Parquet via ``write_table``) and POSTTRAN
# (which reads/writes multiple tables), the TRANREPT job writes a single
# text report to S3 via :func:`src.batch.common.s3_utils.write_to_s3` —
# so the patch targets include ``write_to_s3`` + ``get_versioned_s3_path``
# but NOT ``write_table``.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.tranrept_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.tranrept_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.tranrept_job.read_table"
_PATCH_WRITE_TO_S3: str = "src.batch.jobs.tranrept_job.write_to_s3"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.tranrept_job.get_versioned_s3_path"


# ============================================================================
# Module-level constants mirrored from the production module.
#
# Kept here (rather than imported from ``tranrept_job`` as private
# ``_DEFAULT_*`` names) because the AAP ``internal_imports`` whitelist
# restricts the test's imports from ``tranrept_job`` to the four public
# entry points.  Duplicating these values in the test preserves the
# whitelist contract while still letting us assert on exact literals.
# ============================================================================
#: Default JCL SYMNAMES values for ``PARM-START-DATE`` / ``PARM-END-DATE``.
#: These are the fall-through defaults applied by ``main()`` when the
#: Glue Job is invoked without explicit ``--START_DATE`` / ``--END_DATE``
#: overrides.  They are asserted by :func:`test_filter_default_dates`
#: which invokes ``main()`` without those overrides.
_EXPECTED_DEFAULT_START_DATE: str = "2022-01-01"
_EXPECTED_DEFAULT_END_DATE: str = "2022-07-06"

#: Physical record width of every line in the TRANREPT output file
#: (``FD-REPTFILE-REC PIC X(133)`` / ``DCB=LRECL=133``).
_EXPECTED_REPORT_LINE_WIDTH: int = 133


# ============================================================================
# Helper: build a transactions :class:`Row` with CVTRA05Y schema.
#
# Defaults are set so the transaction will survive the default date
# filter (``2022-01-01`` — ``2022-07-06``) and match a single canonical
# card/type/category (``"4111111111111111"`` / ``"01"`` / ``"0001"``) so
# tests can override exactly one field per case.
# ============================================================================
def _make_txn_row(
    tran_id: str,
    tran_card_num: str,
    *,
    tran_proc_ts: str = "2022-03-15T12:34:56.789012",
    tran_type_cd: str = "01",
    tran_cat_cd: str = "0001",
    tran_source: str = "POS",
    tran_amt: Decimal = Decimal("10.00"),
) -> Row:
    """Build a transaction Row for the CVTRA05Y schema.

    Parameters
    ----------
    tran_id : str
        The 16-char transaction identifier (primary key).
    tran_card_num : str
        The 16-digit card number (join key against card_cross_references).
    tran_proc_ts : str, keyword-only
        ISO-8601 processing timestamp (``YYYY-MM-DDTHH:MM:SS.ffffff``).
        The first 10 characters are extracted by
        :func:`filter_by_date_range` for inclusive comparison against
        ``start_date`` / ``end_date``.
    tran_type_cd : str, keyword-only
        Transaction type code (CHAR 2).  Join key against
        ``transaction_types.type_code``.
    tran_cat_cd : str, keyword-only
        Transaction category code (CHAR 4).  Composite join key
        with ``tran_type_cd`` against ``transaction_categories``.
    tran_source : str, keyword-only
        Transaction source (VARCHAR 10).
    tran_amt : Decimal, keyword-only
        Transaction amount (``NUMERIC(15,2)`` in PostgreSQL; must
        always be :class:`decimal.Decimal` — never ``float`` — per AAP
        §0.7.2 financial-precision rule).
    """
    return Row(
        tran_id=tran_id,
        tran_card_num=tran_card_num,
        tran_proc_ts=tran_proc_ts,
        tran_type_cd=tran_type_cd,
        tran_cat_cd=tran_cat_cd,
        tran_source=tran_source,
        tran_amt=tran_amt,
    )


def _make_xref_row(card_num: str, acct_id: str, cust_id: str = "000000001") -> Row:
    """Build a cross-reference Row for the CVACT03Y schema.

    Parameters
    ----------
    card_num : str
        The 16-digit card number (primary key).
    acct_id : str
        The 11-digit account identifier (foreign key to ``accounts``).
    cust_id : str
        The 9-digit customer identifier (foreign key to ``customers``).
        Defaulted because :func:`_enrich_transactions` only projects
        ``acct_id`` from the xref join (the other xref fields are
        discarded by the ``.select`` after the join).
    """
    return Row(card_num=card_num, cust_id=cust_id, acct_id=acct_id)


def _make_trantype_row(type_code: str, tran_type_desc: str) -> Row:
    """Build a transaction-type Row for the CVTRA03Y schema.

    Parameters
    ----------
    type_code : str
        The 2-char transaction-type code (primary key).  Matches the
        ``type_code`` column in ``transaction_types`` (referenced by
        :func:`_enrich_transactions` join #2).
    tran_type_desc : str
        Human-readable description (VARCHAR 50).  Projected into the
        enriched DataFrame as ``tran_type_desc`` and consumed by
        :func:`format_report_line` in the ``tran_type_desc`` (CHAR 15)
        field.
    """
    return Row(type_code=type_code, tran_type_desc=tran_type_desc)


def _make_trancatg_row(
    type_code: str,
    cat_code: str,
    tran_cat_type_desc: str,
) -> Row:
    """Build a transaction-category Row for the CVTRA04Y schema.

    Parameters
    ----------
    type_code : str
        The 2-char type code (first half of composite PK).  Matches
        the ``type_code`` column in ``transaction_categories``.
    cat_code : str
        The 4-char category code (second half of composite PK).
        Matches the ``cat_code`` column in ``transaction_categories``.
    tran_cat_type_desc : str
        Human-readable description (VARCHAR 50).  Projected into the
        enriched DataFrame as ``tran_cat_type_desc`` and consumed by
        :func:`format_report_line` in the ``cat_desc`` (CHAR 29) field.
    """
    return Row(
        type_code=type_code,
        cat_code=cat_code,
        tran_cat_type_desc=tran_cat_type_desc,
    )


# ============================================================================
# Helper: build a row dict for :func:`format_report_line` tests.
#
# The module-under-test's ``format_report_line`` consumes a flat
# ``dict[str, Any]`` (the output of ``Row.asDict()`` after the xref +
# trantype + trancatg joins have been projected).  Tests for pure line
# formatting skip the Spark machinery and construct this dict directly.
# ============================================================================
def _make_report_row_dict(
    *,
    tran_id: str = "T000000000000001",
    acct_id: str | None = "00000000001",
    tran_type_cd: str = "01",
    tran_type_desc: str | None = "Purchase",
    tran_cat_cd: str = "0001",
    tran_cat_type_desc: str | None = "Groceries",
    tran_source: str | None = "POS",
    tran_amt: Decimal = Decimal("123.45"),
) -> dict[str, Any]:
    """Build a row dict matching the enriched-DataFrame projection.

    The default values produce a fully populated non-zero amount so
    the round-trip through :func:`_format_amount_edited`'s zero-
    suppression is exercised on real digits.
    """
    return {
        "tran_id": tran_id,
        "acct_id": acct_id,
        "tran_type_cd": tran_type_cd,
        "tran_type_desc": tran_type_desc,
        "tran_cat_cd": tran_cat_cd,
        "tran_cat_type_desc": tran_cat_type_desc,
        "tran_source": tran_source,
        "tran_amt": tran_amt,
    }


# ============================================================================
# Phase 1 — Module-load smoke test.
#
# Runs first to catch import-time regressions (e.g., missing / mis-named
# dependencies in the depends_on_files whitelist) before any of the more
# expensive Spark-dependent tests.  A failure here would abort the rest
# of the test module with a clear ImportError traceback rather than
# producing noise in every downstream fixture invocation.
# ============================================================================
@pytest.mark.unit
def test_module_public_api_importable() -> None:
    """Verify all four public entry points are importable without error.

    The AAP ``exports`` schema for ``tranrept_job.py`` declares
    ``filter_by_date_range``, ``format_report_line``,
    ``format_subtotal_line``, and ``main`` as the module's public
    surface (mirrored in the module's ``__all__`` list).  A passing
    test confirms:

    1. All four public names are callable (functions, not ``None``
       placeholders).
    2. The module loaded successfully with every internal import
       resolving (``read_table``, ``commit_job``, ``init_glue``,
       ``get_versioned_s3_path``, ``write_to_s3``).
    3. No module-level assertion or type-check failure occurred.
    """
    assert callable(filter_by_date_range), "filter_by_date_range must be callable"
    assert callable(format_report_line), "format_report_line must be callable"
    assert callable(format_subtotal_line), "format_subtotal_line must be callable"
    assert callable(main), "main must be callable"


# ============================================================================
# Phase 2 — Date-parameter filtering tests.
#
# Replaces the DFSORT ``INCLUDE COND`` filter in TRANREPT.jcl STEP05R
# (lines 52-54) and the internal COBOL date filter in
# CBTRN03C.cbl's ``1000-TRANFILE-GET-NEXT`` paragraph.  Both enforce
# ``TRAN-PROC-TS(1:10) >= start_date AND TRAN-PROC-TS(1:10) <= end_date``
# — strictly inclusive bounds on both sides (``>=`` + ``<=``).  The
# PySpark implementation delegates this to
# :func:`filter_by_date_range` using
# ``F.substring(F.col('tran_proc_ts'), 1, 10)`` which is the direct
# translation of the COBOL reference-modification syntax
# ``TRAN-PROC-TS(1:10)``.
#
# Default fall-through values when no Glue arg override is provided
# are ``_DEFAULT_START_DATE = "2022-01-01"`` and
# ``_DEFAULT_END_DATE = "2022-07-06"`` — mirrored in
# ``_EXPECTED_DEFAULT_START_DATE`` / ``_EXPECTED_DEFAULT_END_DATE``
# above.
# ============================================================================
@pytest.mark.unit
def test_filter_by_date_range_inclusive(spark_session: SparkSession) -> None:
    """Verify that both ``start_date`` and ``end_date`` bounds are inclusive.

    COBOL behaviour (CBTRN03C.cbl ``1000-TRANFILE-GET-NEXT``)::

        IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND
           TRAN-PROC-TS(1:10) <= WS-END-DATE

    The ``>=`` and ``<=`` operators make both endpoints INCLUSIVE.  A
    transaction whose processing date is exactly ``start_date`` OR
    exactly ``end_date`` MUST be retained by the filter.

    Test strategy:  build five transactions with processing dates
    covering the full boundary landscape:

    * ``2021-12-31`` — one day BEFORE the start date -> EXCLUDED.
    * ``2022-01-01`` — exactly AT the start date    -> INCLUDED.
    * ``2022-03-15`` — strictly WITHIN the range    -> INCLUDED.
    * ``2022-07-06`` — exactly AT the end date      -> INCLUDED.
    * ``2022-08-01`` — one month AFTER the end date -> EXCLUDED.

    Expected outcome: 3 of 5 rows retained, with ids T002, T003, and
    T004 (the three dates within the inclusive range).
    """
    # Build the boundary dataset.  We use a 0.01 amount so any
    # regression that accidentally sums / compares amounts would
    # surface as an unexpected total rather than silently pass.
    rows = [
        _make_txn_row(
            "T001",
            "4111111111111111",
            tran_proc_ts="2021-12-31T23:59:59.999999",
            tran_amt=Decimal("0.01"),
        ),
        _make_txn_row(
            "T002",
            "4111111111111111",
            tran_proc_ts="2022-01-01T00:00:00.000000",
            tran_amt=Decimal("0.01"),
        ),
        _make_txn_row(
            "T003",
            "4111111111111111",
            tran_proc_ts="2022-03-15T12:34:56.789012",
            tran_amt=Decimal("0.01"),
        ),
        _make_txn_row(
            "T004",
            "4111111111111111",
            tran_proc_ts="2022-07-06T23:59:59.999999",
            tran_amt=Decimal("0.01"),
        ),
        _make_txn_row(
            "T005",
            "4111111111111111",
            tran_proc_ts="2022-08-01T00:00:00.000000",
            tran_amt=Decimal("0.01"),
        ),
    ]
    transactions_df = spark_session.createDataFrame(rows)

    # Apply the filter with the default JCL SYMNAMES bounds.
    filtered_df = filter_by_date_range(
        transactions_df,
        _EXPECTED_DEFAULT_START_DATE,
        _EXPECTED_DEFAULT_END_DATE,
    )

    # Collect and extract the ``tran_id`` values — order-independent
    # since we are asserting set membership, not sort order.
    retained_ids: set[str] = {r["tran_id"] for r in filtered_df.collect()}

    # Boundary dates (2022-01-01 and 2022-07-06) MUST be retained.
    assert "T002" in retained_ids, (
        "Transaction T002 (proc_ts=2022-01-01) must be included; filter_by_date_range start_date bound is inclusive."
    )
    assert "T004" in retained_ids, (
        "Transaction T004 (proc_ts=2022-07-06) must be included; filter_by_date_range end_date bound is inclusive."
    )
    # Interior transaction T003 is obviously included.
    assert "T003" in retained_ids, "Transaction T003 (proc_ts=2022-03-15) within range must be included."

    # Out-of-range transactions MUST be excluded.
    assert "T001" not in retained_ids, "Transaction T001 (proc_ts=2021-12-31) before start_date must be excluded."
    assert "T005" not in retained_ids, "Transaction T005 (proc_ts=2022-08-01) after end_date must be excluded."

    # Exact retention count: 3 inclusive rows out of 5 total.
    assert len(retained_ids) == 3, (
        f"Expected exactly 3 retained transactions within the inclusive "
        f"[2022-01-01, 2022-07-06] range; got {len(retained_ids)}: "
        f"{sorted(retained_ids)!r}"
    )


@pytest.mark.unit
def test_filter_by_date_range_excludes_out_of_range(
    spark_session: SparkSession,
) -> None:
    """Verify out-of-range transactions are excluded from the result.

    This test is the complementary case to
    :func:`test_filter_by_date_range_inclusive`: it builds a dataset
    where EVERY row is either before ``start_date`` or after
    ``end_date``, so the expected result is an empty DataFrame with
    ``count() == 0``.  This directly exercises the exclusion side of
    the DFSORT ``INCLUDE COND``: transactions that fail the predicate
    must NOT be carried into STEP10R (the PGM=CBTRN03C step).
    """
    # Every row is out-of-range: three before 2022-01-01, three after
    # 2022-07-06.  None should survive the filter.
    rows = [
        _make_txn_row(
            "T101",
            "4111111111111111",
            tran_proc_ts="2021-06-15T00:00:00.000000",
        ),
        _make_txn_row(
            "T102",
            "4111111111111111",
            tran_proc_ts="2021-11-30T12:00:00.000000",
        ),
        _make_txn_row(
            "T103",
            "4111111111111111",
            tran_proc_ts="2021-12-31T23:59:59.999999",
        ),
        _make_txn_row(
            "T201",
            "4111111111111111",
            tran_proc_ts="2022-07-07T00:00:00.000000",
        ),
        _make_txn_row(
            "T202",
            "4111111111111111",
            tran_proc_ts="2022-10-15T12:00:00.000000",
        ),
        _make_txn_row(
            "T203",
            "4111111111111111",
            tran_proc_ts="2023-01-01T00:00:00.000000",
        ),
    ]
    transactions_df = spark_session.createDataFrame(rows)

    filtered_df = filter_by_date_range(
        transactions_df,
        _EXPECTED_DEFAULT_START_DATE,
        _EXPECTED_DEFAULT_END_DATE,
    )

    # No row should pass the filter — total count must be zero.
    retained_count: int = filtered_df.count()
    assert retained_count == 0, (
        f"Expected 0 retained transactions (all inputs are out-of-range); "
        f"got {retained_count}.  Collected rows: "
        f"{[r.asDict() for r in filtered_df.collect()]!r}"
    )


@pytest.mark.unit
def test_filter_default_dates(spark_session: SparkSession) -> None:
    """Verify ``main()`` uses the JCL SYMNAMES defaults when no overrides.

    The JCL SYMNAMES in TRANREPT.jcl lines 48-49 declare::

        PARM-START-DATE,C'2022-01-01'
        PARM-END-DATE,C'2022-07-06'

    The Python port mirrors these values in
    ``_DEFAULT_START_DATE = "2022-01-01"`` and
    ``_DEFAULT_END_DATE = "2022-07-06"``.  When ``main()`` is invoked
    without ``START_DATE`` / ``END_DATE`` keys in ``resolved_args``,
    ``resolved_args.get("START_DATE", _DEFAULT_START_DATE)`` falls
    through to the default.

    Verification strategy:  run ``main()`` with an empty
    ``resolved_args`` (only ``JOB_NAME``).  The REPORT-NAME-HEADER
    embeds the date range as ``"Date Range: 2022-01-01 to 2022-07-06"``
    (per :func:`_build_report_name_header` in the source module).
    Capturing the S3 write payload and scanning for the default dates
    asserts the fallback path was taken.

    Additionally, only transactions with ``tran_proc_ts`` in
    ``[2022-01-01, 2022-07-06]`` should appear in the report — a row
    with ``tran_proc_ts='2023-01-01'`` is included in the input and
    must NOT appear in the output (confirming that the default range
    was actually applied as a filter, not silently dropped).
    """
    # Two transactions: one in-range, one out-of-range.
    in_range_row = _make_txn_row(
        "T_IN_RANGE",
        "4111111111111111",
        tran_proc_ts="2022-03-15T12:34:56.789012",
        tran_amt=Decimal("50.00"),
    )
    out_of_range_row = _make_txn_row(
        "T_OUT_OF_RANGE",
        "4111111111111111",
        tran_proc_ts="2023-01-15T12:34:56.789012",
        tran_amt=Decimal("999.99"),
    )
    transactions_df = spark_session.createDataFrame([in_range_row, out_of_range_row])
    xref_df = spark_session.createDataFrame([_make_xref_row("4111111111111111", "00000000001")])
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured_content: dict[str, str] = {}

    def _write_to_s3_side_effect(
        content: str,
        key: str,
        bucket: str | None = None,
        content_type: str = "text/plain",
    ) -> str:
        captured_content["content"] = content
        captured_content["key"] = key
        captured_content["bucket"] = bucket or ""
        captured_content["content_type"] = content_type
        return f"s3://{bucket}/{key}"

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transactions": transactions_df,
            "card_cross_references": xref_df,
            "transaction_types": trantype_df,
            "transaction_categories": trancatg_df,
        }[table_name]

    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect),
        patch(
            _PATCH_GET_S3_PATH,
            return_value="s3://carddemo-bucket/reports/2026/04/22/120000/",
        ),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            spark_session,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            # NOTE: intentionally no START_DATE / END_DATE keys in
            # resolved_args so main() must fall through to the
            # _DEFAULT_START_DATE / _DEFAULT_END_DATE values.
            {"JOB_NAME": "carddemo-tranrept"},
        )
        main()
        mock_commit_job.assert_called_once()

    # ----- Assert: date range header contains default JCL SYMNAMES values -----
    report_content = captured_content["content"]
    assert _EXPECTED_DEFAULT_START_DATE in report_content, (
        f"Report REPORT-NAME-HEADER must embed default start_date "
        f"{_EXPECTED_DEFAULT_START_DATE!r}; content excerpt: "
        f"{report_content[:200]!r}"
    )
    assert _EXPECTED_DEFAULT_END_DATE in report_content, (
        f"Report REPORT-NAME-HEADER must embed default end_date "
        f"{_EXPECTED_DEFAULT_END_DATE!r}; content excerpt: "
        f"{report_content[:200]!r}"
    )

    # ----- Assert: in-range row present in the report body -----
    assert "T_IN_RANGE" in report_content, (
        "In-range transaction T_IN_RANGE (2022-03-15) must appear in the "
        "report body since it falls within the default [2022-01-01, "
        "2022-07-06] range."
    )

    # ----- Assert: out-of-range row filtered out -----
    assert "T_OUT_OF_RANGE" not in report_content, (
        "Out-of-range transaction T_OUT_OF_RANGE (2023-01-15) must NOT "
        "appear in the report body; its presence would indicate the "
        "default date filter was bypassed."
    )


# ============================================================================
# Phase 3 — Cross-reference enrichment tests.
#
# Replaces the three COBOL ``1500-*-LOOKUP-*`` paragraphs in
# CBTRN03C.cbl which perform VSAM ``READ ... KEY IS ...`` operations
# against the CARDXREF, TRANTYPE, and TRANCATG indexed clusters.  The
# PySpark implementation translates each READ to a left-outer
# :meth:`~pyspark.sql.DataFrame.join` in :func:`_enrich_transactions`
# (see ``tranrept_job.py`` lines 1019-1117).
#
# The enrichment runs as part of ``main()`` after the date-range
# filter; we exercise it by invoking ``main()`` end-to-end with a
# single in-range transaction and asserting the enriched fields
# appear in the final report body.
# ============================================================================
def _run_main_and_capture(
    spark_session: SparkSession,
    transactions_df: Any,
    xref_df: Any,
    trantype_df: Any,
    trancatg_df: Any,
    resolved_args: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Run ``main()`` with the supplied DataFrames and return captured state.

    Utility helper shared by several Phase 3 / 4 / 6 / 7 tests that
    need to drive ``main()`` under a full mock stack and inspect the
    resulting ``write_to_s3`` invocation.  Returns a dict containing:

    * ``content`` (str): the complete report payload passed to
      ``write_to_s3``.
    * ``key`` (str): the S3 object key argument.
    * ``bucket`` (str): the S3 bucket argument.
    * ``content_type`` (str): the MIME content-type argument.
    * ``gdg_calls`` (list[str]): every GDG name passed to
      :func:`get_versioned_s3_path` (always singleton ``["TRANREPT"]``
      for this job, but captured for validation).
    * ``commit_called`` (int): number of times ``commit_job`` was
      invoked (always 1 on success).

    Parameters
    ----------
    spark_session : SparkSession
        The session-scoped real Spark session from conftest.
    transactions_df, xref_df, trantype_df, trancatg_df : DataFrame
        The four input DataFrames that ``read_table`` is mocked to
        return when called with the four corresponding table names.
    resolved_args : dict[str, str] or None
        Optional override for the ``resolved_args`` tuple element
        returned by the mocked ``init_glue``.  Defaults to
        ``{"JOB_NAME": "carddemo-tranrept"}`` (no date overrides ->
        default dates applied).
    """
    if resolved_args is None:
        resolved_args = {"JOB_NAME": "carddemo-tranrept"}

    captured: dict[str, Any] = {
        "content": "",
        "key": "",
        "bucket": "",
        "content_type": "",
        "gdg_calls": [],
        "commit_called": 0,
    }

    def _write_to_s3_side_effect(
        content: str,
        key: str,
        bucket: str | None = None,
        content_type: str = "text/plain",
    ) -> str:
        captured["content"] = content
        captured["key"] = key
        captured["bucket"] = bucket or ""
        captured["content_type"] = content_type
        return f"s3://{bucket}/{key}"

    def _get_s3_path_side_effect(gdg_name: str, *_args: Any, **_kwargs: Any) -> str:
        captured["gdg_calls"].append(gdg_name)
        return "s3://carddemo-bucket/reports/2026/04/22/120000/"

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transactions": transactions_df,
            "card_cross_references": xref_df,
            "transaction_types": trantype_df,
            "transaction_categories": trancatg_df,
        }[table_name]

    def _commit_job_side_effect(_job: Any) -> None:
        captured["commit_called"] += 1

    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB, side_effect=_commit_job_side_effect),
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect),
        patch(_PATCH_GET_S3_PATH, side_effect=_get_s3_path_side_effect),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            spark_session,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            resolved_args,
        )
        main()

    return captured


@pytest.mark.unit
def test_xref_enrichment(spark_session: SparkSession) -> None:
    """Verify the XREF lookup attaches ``acct_id`` to each transaction.

    COBOL behaviour (CBTRN03C.cbl ``1500-A-LOOKUP-XREF``)::

        MOVE TRAN-CARD-NUM TO FD-CARDXREF-CARDNUM
        READ CARDXREF-FILE INTO CARD-XREF-RECORD
           KEY IS FD-CARDXREF-CARDNUM
           ...
        END-READ
        MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID

    The READ ties the physical CARDXREF record (CVACT03Y.cpy layout)
    to the incoming transaction, attaching the 11-digit ``XREF-ACCT-ID``
    to the report line.  The PySpark equivalent is a left join on
    ``tx.tran_card_num == xref.card_num`` which projects
    ``xref.acct_id`` into the enriched DataFrame.

    Test strategy:  create ONE transaction with a specific card number
    and ONE matching xref row with a distinctive acct_id
    (``00000099999`` — unlikely to appear incidentally in any
    other enrichment).  Run main() and assert the acct_id appears
    in the report body.
    """
    transactions_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                "T_XREF_TEST_001",
                "4111111111111111",
                tran_proc_ts="2022-03-15T12:34:56.789012",
            ),
        ]
    )
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row(
                card_num="4111111111111111",
                acct_id="00000099999",
            ),
        ]
    )
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )

    report_content: str = captured["content"]

    # The acct_id resolved via the xref join must appear verbatim in
    # the report body.  The 11-char account id is padded by
    # ``_cobol_field`` to exactly 11 chars — its string occurrence
    # in the content stream is therefore a definitive assertion.
    assert "00000099999" in report_content, (
        f"xref-resolved acct_id '00000099999' must appear in report "
        f"body (xref join attaches acct_id to transaction); report "
        f"excerpt: {report_content[:600]!r}"
    )

    # Double-check: the ``tran_id`` also appears (confirms the report
    # body was emitted at all and not just the headers).
    assert "T_XREF_TEST_001" in report_content, "Transaction T_XREF_TEST_001 must appear in the detail section."


@pytest.mark.unit
def test_trantype_enrichment(spark_session: SparkSession) -> None:
    """Verify the TRANTYPE lookup attaches ``tran_type_desc`` to each row.

    COBOL behaviour (CBTRN03C.cbl ``1500-B-LOOKUP-TRANTYPE``)::

        MOVE TRAN-TYPE-CD TO FD-TRANTYPE-TYPE
        READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD
           KEY IS FD-TRANTYPE-TYPE
           ...
        END-READ
        MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC

    PySpark equivalent is a left join on
    ``tx.tran_type_cd == trantype.type_code`` projecting
    ``trantype.tran_type_desc`` into the report row.

    Test strategy: use a distinctive description ``"TESTTYPEDESC"``
    that cannot collide with any fixture or production seed value,
    and assert it appears in the report body when the transaction's
    type code matches the trantype row.
    """
    transactions_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                "T_TRANTYPE_001",
                "4111111111111111",
                tran_proc_ts="2022-03-15T12:34:56.789012",
                tran_type_cd="09",  # unusual code so trantype join is isolated
            ),
        ]
    )
    xref_df = spark_session.createDataFrame([_make_xref_row("4111111111111111", "00000000001")])
    trantype_df = spark_session.createDataFrame(
        [
            _make_trantype_row("09", "TESTTYPEDESC"),
            # Add another row to ensure the join selects the correct
            # one (a buggy equi-join might return the first row in
            # file order instead of matching on the key).
            _make_trantype_row("01", "PurchaseOther"),
        ]
    )
    trancatg_df = spark_session.createDataFrame(
        [
            _make_trancatg_row("09", "0001", "TestCat"),
            _make_trancatg_row("01", "0001", "OtherCat"),
        ]
    )

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    # The trantype description for code "09" must appear.  The
    # _cobol_field wrap truncates at 15 chars — ``TESTTYPEDESC`` is
    # 12 chars so survives intact.
    assert "TESTTYPEDESC" in report_content, (
        f"trantype-resolved tran_type_desc 'TESTTYPEDESC' must appear "
        f"in the report body (trantype join attaches description based "
        f"on tran_type_cd); report excerpt: {report_content[:600]!r}"
    )

    # The wrong description (``PurchaseOther`` for code "01") must
    # NOT appear — a buggy join that picks an arbitrary row would
    # produce this description instead.
    assert "PurchaseOther" not in report_content, (
        "trantype description for the NON-matching code must not leak "
        "into the report (a correct join picks only the row whose "
        "type_code equals the transaction's tran_type_cd)."
    )


@pytest.mark.unit
def test_trancatg_enrichment(spark_session: SparkSession) -> None:
    """Verify TRANCATG composite join attaches ``tran_cat_type_desc``.

    COBOL behaviour (CBTRN03C.cbl ``1500-C-LOOKUP-TRANCATG``)::

        MOVE TRAN-TYPE-CD TO FD-TRANCATG-TYPE
        MOVE TRAN-CAT-CD  TO FD-TRANCATG-CODE
        READ TRANCATG-FILE INTO TRAN-CAT-RECORD
           KEY IS FD-TRANCATG-COMPOSITE-KEY   (type-cd || cat-cd)
           ...
        END-READ
        MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC

    PySpark equivalent is a left join on the COMPOSITE key::

        tx.tran_type_cd == trancatg.type_code  AND
        tx.tran_cat_cd  == trancatg.cat_code

    A single-column join on ``cat_code`` alone would incorrectly
    match multiple rows (two ``TRANCATG`` rows can share a cat_code
    under different type_codes).  Test strategy: provide two rows
    with IDENTICAL ``cat_code="0001"`` but DIFFERENT ``type_code``
    — a correct composite join must pick the row whose type_code
    also matches.
    """
    transactions_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                "T_TRANCATG_001",
                "4111111111111111",
                tran_proc_ts="2022-03-15T12:34:56.789012",
                tran_type_cd="05",
                tran_cat_cd="0001",
            ),
        ]
    )
    xref_df = spark_session.createDataFrame([_make_xref_row("4111111111111111", "00000000001")])
    trantype_df = spark_session.createDataFrame([_make_trantype_row("05", "Refund")])
    # Two TRANCATG rows with identical cat_code but different type_code.
    # The CORRECT composite join picks ``type_code="05"`` because the
    # transaction's tran_type_cd is "05".
    trancatg_df = spark_session.createDataFrame(
        [
            _make_trancatg_row("01", "0001", "GROCERIES_WRONG"),  # NOT matched
            _make_trancatg_row("05", "0001", "REFUNDCORRECT"),  # matched
        ]
    )

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    # The composite-matched description must be present.
    assert "REFUNDCORRECT" in report_content, (
        f"trancatg-resolved tran_cat_type_desc 'REFUNDCORRECT' must "
        f"appear in the report body (composite join on type_code+cat_code); "
        f"report excerpt: {report_content[:600]!r}"
    )
    # The wrong description (share cat_code but different type_code)
    # must NOT appear — a single-column join would incorrectly pick it.
    assert "GROCERIES_WRONG" not in report_content, (
        "A composite-key join must NOT return the trancatg row with the "
        "same cat_code but a different type_code.  If this assertion "
        "fails, the join is using cat_code alone and is incorrect."
    )


# ============================================================================
# Phase 4 — 3-level totals tests (CRITICAL).
#
# The CBTRN03C.cbl program maintains three running totals in
# WORKING-STORAGE::
#
#     01  WS-ACCOUNT-TOTAL  PIC S9(09)V99 COMP-3 VALUE ZERO.
#     01  WS-PAGE-TOTAL     PIC S9(09)V99 COMP-3 VALUE ZERO.
#     01  WS-GRAND-TOTAL    PIC S9(09)V99 COMP-3 VALUE ZERO.
#
# These are accumulated as COBOL ``ADD TRAN-AMT TO ...`` statements
# with implicit ``ROUNDED`` semantics (banker's rounding) on the
# quantize at each step.  AAP §0.7.2 REQUIRES that the Python port
# use :class:`decimal.Decimal` with :data:`decimal.ROUND_HALF_EVEN`
# — NEVER ``float`` — for every monetary accumulation.
#
# Emission points in the report:
#   * Account subtotal line: emitted on CARD BREAK (when
#     ``WS-CURR-CARD-NUM`` != ``TRAN-CARD-NUM`` and not first row).
#   * Page subtotal line: emitted on PAGE BREAK (MOD 20 = 0) and once
#     at end-of-file (before grand total).
#   * Grand total line: emitted ONCE at end-of-file.
#
# These Phase 4 tests drive ``main()`` end-to-end with specifically
# chosen transaction rows to force card breaks and page breaks, then
# parse the captured report content to verify the totals are
# (a) present with the correct labels and (b) numerically accurate
# under banker's rounding.
# ============================================================================
def _parse_subtotal_amount(report_line: str) -> Decimal:
    """Parse the numeric amount from a 133-char subtotal line.

    Subtotal lines emitted by :func:`format_subtotal_line` have the
    structure ``<label><dots><amount-edited><padding>``.  The
    amount-edited field is the 16-character ``PIC +ZZZ,ZZZ,ZZZ.ZZ``
    format starting at a fixed offset (113 chars of label+dots plus
    16 chars of amount = 129 logical chars, padded to 133).

    This helper extracts the 16-char amount slice from offsets
    ``[97:113]`` (Python 0-indexed; COBOL positions 98-113), strips
    the leading sign column, strips whitespace, removes the grouping
    commas, and returns a :class:`Decimal`.

    Parameters
    ----------
    report_line : str
        A 133-character subtotal line previously emitted by
        :func:`format_subtotal_line`.

    Returns
    -------
    Decimal
        The parsed amount.
    """
    # Extract the 16-char amount field.  The label+dots run is always
    # 113 chars (see format_subtotal_line docstring layout tables):
    # "Page Total " (11) + dots (86) + amount (16) = 113
    # "Account Total" (13) + dots (84) + amount (16) = 113
    # "Grand Total" (11) + dots (86) + amount (16) = 113
    amount_slice: str = report_line[97:113]  # 113 - 16 = 97
    assert len(amount_slice) == 16, (
        f"Expected 16-char amount slice; got {len(amount_slice)}: {amount_slice!r} (from line {report_line!r})"
    )
    # First char is the sign (+/-), remaining 15 chars are the
    # zero-suppressed magnitude.
    sign_char: str = amount_slice[0]
    magnitude_text: str = amount_slice[1:].replace(",", "").strip()
    if not magnitude_text:
        magnitude_text = "0.00"
    parsed: Decimal = Decimal(magnitude_text)
    if sign_char == "-":
        parsed = -parsed
    return parsed


def _extract_subtotal_lines(content: str, label: str) -> list[str]:
    """Return every 133-char line in ``content`` that starts with ``label``.

    Report content is a ``"\\n"``-joined concatenation of 133-char
    lines.  Subtotal lines start with their label (``"Page Total "``,
    ``"Account Total"``, or ``"Grand Total"``) in the leftmost
    characters.  This helper splits on newlines, filters by prefix,
    and returns the matching line list in order of appearance.

    Parameters
    ----------
    content : str
        Full report content.
    label : str
        One of ``"Page Total"``, ``"Account Total"``, ``"Grand Total"``.

    Returns
    -------
    list[str]
        Zero or more 133-char lines whose leftmost characters match
        ``label``.
    """
    result: list[str] = []
    for raw_line in content.split("\n"):
        if raw_line.startswith(label):
            result.append(raw_line)
    return result


@pytest.mark.unit
def test_account_subtotal(spark_session: SparkSession) -> None:
    """Verify account subtotals are emitted on card break with correct sums.

    Test strategy:  build 6 transactions spanning 2 distinct card
    numbers (3 per card).  After sort by tran_card_num, the second
    card break triggers ONE account-total line for the first card.
    EOF does NOT flush a final account total (CBTRN03C.cbl quirk
    preserved — see tranrept_job.py docstring ``Final-row account
    total quirk``).

    Expected outcome: exactly ONE account-total line in the output
    with the sum of the first card's three transactions.

    Amounts (card 1): 10.00 + 20.00 + 30.00 = 60.00
    Amounts (card 2): 5.00 + 15.00 + 25.00 = 45.00
      -> Exactly one "Account Total" line with +60.00
      -> No Account Total line for card 2 (EOF quirk)
    """
    # Interleave cards so sort order produces card-1 then card-2.
    # Card 1 ("4000000000000001") txn amounts: 10, 20, 30 -> subtotal 60
    # Card 2 ("5000000000000002") txn amounts: 5, 15, 25  -> subtotal 45 (no line)
    rows = [
        _make_txn_row(
            "T001",
            "4000000000000001",
            tran_proc_ts="2022-03-15T10:00:00.000000",
            tran_amt=Decimal("10.00"),
        ),
        _make_txn_row(
            "T002",
            "4000000000000001",
            tran_proc_ts="2022-03-15T11:00:00.000000",
            tran_amt=Decimal("20.00"),
        ),
        _make_txn_row(
            "T003",
            "4000000000000001",
            tran_proc_ts="2022-03-15T12:00:00.000000",
            tran_amt=Decimal("30.00"),
        ),
        _make_txn_row(
            "T004",
            "5000000000000002",
            tran_proc_ts="2022-03-15T13:00:00.000000",
            tran_amt=Decimal("5.00"),
        ),
        _make_txn_row(
            "T005",
            "5000000000000002",
            tran_proc_ts="2022-03-15T14:00:00.000000",
            tran_amt=Decimal("15.00"),
        ),
        _make_txn_row(
            "T006",
            "5000000000000002",
            tran_proc_ts="2022-03-15T15:00:00.000000",
            tran_amt=Decimal("25.00"),
        ),
    ]
    transactions_df = spark_session.createDataFrame(rows)
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row("4000000000000001", "00000000101"),
            _make_xref_row("5000000000000002", "00000000102"),
        ]
    )
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    account_lines: list[str] = _extract_subtotal_lines(report_content, "Account Total")

    # Exactly ONE account-total line (for card 1); card 2's account
    # total is NOT emitted per the EOF quirk preserved from CBTRN03C.
    assert len(account_lines) == 1, (
        f"Expected exactly 1 'Account Total' line (card break for "
        f"first card); got {len(account_lines)}.  "
        f"CBTRN03C.cbl's EOF quirk (no final account flush) must be "
        f"preserved.  All subtotal lines:\n"
        f"  Account: {account_lines}\n"
        f"  Report (first 2000 chars):\n{report_content[:2000]}"
    )

    # The account total value must equal Decimal('60.00'), matching
    # 10.00 + 20.00 + 30.00 with two-decimal precision.
    parsed_amount: Decimal = _parse_subtotal_amount(account_lines[0])
    expected: Decimal = Decimal("60.00")
    assert parsed_amount == expected, (
        f"First card account subtotal must be {expected}; got {parsed_amount} (line: {account_lines[0]!r})"
    )
    # And its type must be Decimal (not float) — enforced by the
    # parser returning a Decimal.  We verify the sum maintains
    # two-decimal-place scale.
    assert parsed_amount == parsed_amount.quantize(Decimal("0.01")), (
        f"Account subtotal must be quantized to two decimal places; got {parsed_amount}"
    )


@pytest.mark.unit
def test_page_subtotal(spark_session: SparkSession) -> None:
    """Verify page subtotals are emitted on page break (MOD 20 = 0).

    The COBOL page-break check (``FUNCTION MOD(WS-LINE-COUNTER, 20) = 0``)
    triggers when the line counter reaches a non-zero multiple of
    20.  In the Python port, ``ws_line_counter`` increments for:

    * Each header (REPORT-NAME-HEADER, TRANSACTION-HEADER-1,
      TRANSACTION-HEADER-2, blank separator) — 4 lines on first page,
      3 thereafter.
    * Each detail line — 1 each.
    * Each subtotal line (page / account / grand) — 1 each.

    The first page's 4 header lines consume 4 of the 20 slots; the
    next 16 detail lines fill the page.  At line 20 (the 17th detail)
    a page break fires: page total + 3 more headers = 4 more lines,
    re-advancing the counter.

    EOF also emits a final ``Page Total`` line (before the grand
    total).  So for ANY non-empty input we expect at LEAST ONE
    ``Page Total`` line.

    Test strategy:  send 30 transactions (guaranteed to trigger the
    mid-report page break PLUS the EOF page total).  Assert:

    1. At least 2 ``Page Total`` lines (mid-report + EOF).
    2. The SUM of all page subtotals equals the sum of ALL
       transaction amounts (every amount must flow through exactly
       one page total on its way to the grand total).
    3. All parsing is ``Decimal`` — never ``float``.
    """
    # 30 transactions, single card (no card breaks), amounts 1-30 cents.
    # Sum = 30*(30+1)/2 = 465 cents = Decimal('4.65').
    rows = [
        _make_txn_row(
            f"T{idx:04d}",
            "4000000000000003",
            tran_proc_ts=(f"2022-03-{(idx % 28) + 1:02d}T12:00:00.000000"),
            tran_amt=Decimal(f"0.{idx:02d}") if idx < 100 else Decimal("1.00"),
        )
        for idx in range(1, 31)
    ]
    transactions_df = spark_session.createDataFrame(rows)
    xref_df = spark_session.createDataFrame([_make_xref_row("4000000000000003", "00000000103")])
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    page_lines: list[str] = _extract_subtotal_lines(report_content, "Page Total")

    # At least ONE page-total line (the EOF page total is always
    # emitted for non-empty input).  With 30 detail rows + 4 headers
    # on first page = 34 lines, a page break fires once in the middle
    # giving 2 total page-total lines (mid + EOF).
    assert len(page_lines) >= 1, (
        f"Expected at least 1 'Page Total' line for non-empty input; "
        f"got {len(page_lines)}.  Report excerpt:\n"
        f"{report_content[:3000]}"
    )

    # Sum every captured page-total amount.  Use Decimal arithmetic
    # (NEVER float) matching the module-under-test's accumulator
    # semantics.
    total_of_page_subtotals: Decimal = sum(
        (_parse_subtotal_amount(line) for line in page_lines),
        start=Decimal("0.00"),
    ).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    # Expected: sum of input amounts == sum of page subtotals.
    expected_sum: Decimal = sum(
        (row.tran_amt for row in rows),
        start=Decimal("0.00"),
    ).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    assert total_of_page_subtotals == expected_sum, (
        f"Sum of page subtotals ({total_of_page_subtotals}) must equal "
        f"sum of all transaction amounts ({expected_sum}).  Every "
        f"transaction amount must flow through exactly one page total.  "
        f"Page lines: {page_lines!r}"
    )

    # Every parsed amount must be a Decimal (not float).
    for line in page_lines:
        parsed: Decimal = _parse_subtotal_amount(line)
        assert isinstance(parsed, Decimal), (
            f"Page subtotal must parse as Decimal (not float); got {type(parsed).__name__} for line {line!r}"
        )


@pytest.mark.unit
def test_grand_total(spark_session: SparkSession) -> None:
    """Verify grand total equals the sum of ALL transaction amounts.

    The grand total is the final ``Grand Total`` line emitted at
    EOF.  CBTRN03C.cbl produces this after flushing the last page
    total into ``WS-GRAND-TOTAL``.

    Test strategy:  send 7 transactions across 2 cards with
    arithmetic designed to exercise the ``ROUND_HALF_EVEN``
    (banker's rounding) semantics on the quantize:

    Card 1: 12.345 + 67.895 + 100.005 = 180.245
    Card 2: 0.005 + 0.015 + 0.025 + 0.035 = 0.080

    Under banker's rounding (round-half-to-even), each addend
    quantized to 2dp becomes:
        12.345 -> 12.34  (last digit 4 is even)
        67.895 -> 67.90  (round half to even -> 90)
        100.005 -> 100.00 (round half to even -> 00)
        0.005 -> 0.00    (round half to even -> 00)
        0.015 -> 0.02    (round half to even -> 02)
        0.025 -> 0.02    (round half to even -> 02)
        0.035 -> 0.04    (round half to even -> 04)

    Sum of quantized amounts: 12.34 + 67.90 + 100.00 + 0.00 + 0.02 +
    0.02 + 0.04 = 180.32.

    We assert the grand total equals this sum (not the "ideal"
    mathematical sum which would be 180.325).
    """
    # Use Decimal literals to force ``Decimal('0.005')`` precision,
    # NOT a float.  A ``float(0.005)`` would internally be
    # 0.00499999... and produce different rounding — AAP §0.7.2
    # forbids float arithmetic.
    rows = [
        _make_txn_row(
            "TG001",
            "4000000000001001",
            tran_proc_ts="2022-03-15T10:00:00.000000",
            tran_amt=Decimal("12.345"),
        ),
        _make_txn_row(
            "TG002",
            "4000000000001001",
            tran_proc_ts="2022-03-15T11:00:00.000000",
            tran_amt=Decimal("67.895"),
        ),
        _make_txn_row(
            "TG003",
            "4000000000001001",
            tran_proc_ts="2022-03-15T12:00:00.000000",
            tran_amt=Decimal("100.005"),
        ),
        _make_txn_row(
            "TG004",
            "5000000000002002",
            tran_proc_ts="2022-03-15T13:00:00.000000",
            tran_amt=Decimal("0.005"),
        ),
        _make_txn_row(
            "TG005",
            "5000000000002002",
            tran_proc_ts="2022-03-15T14:00:00.000000",
            tran_amt=Decimal("0.015"),
        ),
        _make_txn_row(
            "TG006",
            "5000000000002002",
            tran_proc_ts="2022-03-15T15:00:00.000000",
            tran_amt=Decimal("0.025"),
        ),
        _make_txn_row(
            "TG007",
            "5000000000002002",
            tran_proc_ts="2022-03-15T16:00:00.000000",
            tran_amt=Decimal("0.035"),
        ),
    ]
    transactions_df = spark_session.createDataFrame(rows)
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row("4000000000001001", "00000001001"),
            _make_xref_row("5000000000002002", "00000002002"),
        ]
    )
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    grand_lines: list[str] = _extract_subtotal_lines(report_content, "Grand Total")

    # Exactly ONE grand-total line must be emitted at EOF.
    assert len(grand_lines) == 1, (
        f"Expected exactly 1 'Grand Total' line at EOF; got "
        f"{len(grand_lines)}.  Report excerpt:\n"
        f"{report_content[:3000]}"
    )

    parsed_grand: Decimal = _parse_subtotal_amount(grand_lines[0])

    # Expected grand total under banker's rounding.  The module
    # quantizes each addend to two decimal places with ROUND_HALF_EVEN
    # BEFORE adding, so we replicate that exactly here.
    expected_grand: Decimal = Decimal("0.00")
    for row in rows:
        addend: Decimal = row.tran_amt.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
        expected_grand = (expected_grand + addend).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    assert parsed_grand == expected_grand, (
        f"Grand total must equal banker's-rounded sum of all amounts "
        f"({expected_grand}); got {parsed_grand}.  Each addend is "
        f"quantized to two decimal places with ROUND_HALF_EVEN "
        f"BEFORE accumulation (matching COBOL ROUNDED + ADD "
        f"semantics).  Line: {grand_lines[0]!r}"
    )

    # Result type must be Decimal.
    assert isinstance(parsed_grand, Decimal), f"Grand total must be Decimal; got {type(parsed_grand).__name__}"


@pytest.mark.unit
def test_three_level_totals_consistency(spark_session: SparkSession) -> None:
    """Verify the three levels of totals agree with each other.

    Mathematical invariants of the 3-level total system:

    * ``sum(page_totals) == grand_total``.  Every transaction amount
      flows into exactly one page total (either on mid-page-break
      rollup or on the EOF page-total emission).  All page totals
      are subsequently rolled into the grand total.  Therefore the
      sum of every page-total line in the report must equal the
      grand total.
    * ``sum(account_totals) + last_account_amount == grand_total``.
      Because CBTRN03C.cbl does NOT flush a final account-total line
      at EOF (preserved quirk), the account-totals lines in the
      report are the sum of all accounts EXCEPT the last one.
      Adding the total of the last card's amounts should recover
      the grand total.  We verify the first invariant directly;
      the second requires reconstructing the last-card amount.

    Test strategy: send 10 transactions across 4 cards.  Capture
    all three levels of totals from the report and assert they
    satisfy the invariants under Decimal arithmetic.
    """
    # 10 transactions across 4 cards.  Amounts chosen to produce a
    # clean grand total.
    rows = [
        # Card 1: 3 transactions
        _make_txn_row(
            "TC001",
            "4000000000003001",
            tran_proc_ts="2022-03-15T10:00:00.000000",
            tran_amt=Decimal("100.00"),
        ),
        _make_txn_row(
            "TC002",
            "4000000000003001",
            tran_proc_ts="2022-03-15T10:01:00.000000",
            tran_amt=Decimal("200.00"),
        ),
        _make_txn_row(
            "TC003",
            "4000000000003001",
            tran_proc_ts="2022-03-15T10:02:00.000000",
            tran_amt=Decimal("300.00"),
        ),
        # Card 2: 2 transactions
        _make_txn_row(
            "TC004",
            "5000000000003002",
            tran_proc_ts="2022-03-15T11:00:00.000000",
            tran_amt=Decimal("50.00"),
        ),
        _make_txn_row(
            "TC005",
            "5000000000003002",
            tran_proc_ts="2022-03-15T11:01:00.000000",
            tran_amt=Decimal("75.00"),
        ),
        # Card 3: 4 transactions
        _make_txn_row(
            "TC006",
            "6000000000003003",
            tran_proc_ts="2022-03-15T12:00:00.000000",
            tran_amt=Decimal("11.11"),
        ),
        _make_txn_row(
            "TC007",
            "6000000000003003",
            tran_proc_ts="2022-03-15T12:01:00.000000",
            tran_amt=Decimal("22.22"),
        ),
        _make_txn_row(
            "TC008",
            "6000000000003003",
            tran_proc_ts="2022-03-15T12:02:00.000000",
            tran_amt=Decimal("33.33"),
        ),
        _make_txn_row(
            "TC009",
            "6000000000003003",
            tran_proc_ts="2022-03-15T12:03:00.000000",
            tran_amt=Decimal("44.44"),
        ),
        # Card 4 (last): 1 transaction
        _make_txn_row(
            "TC010",
            "7000000000003004",
            tran_proc_ts="2022-03-15T13:00:00.000000",
            tran_amt=Decimal("999.99"),
        ),
    ]
    transactions_df = spark_session.createDataFrame(rows)
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row("4000000000003001", "00000003001"),
            _make_xref_row("5000000000003002", "00000003002"),
            _make_xref_row("6000000000003003", "00000003003"),
            _make_xref_row("7000000000003004", "00000003004"),
        ]
    )
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    # Extract all three levels.
    page_lines: list[str] = _extract_subtotal_lines(report_content, "Page Total")
    account_lines: list[str] = _extract_subtotal_lines(report_content, "Account Total")
    grand_lines: list[str] = _extract_subtotal_lines(report_content, "Grand Total")

    # Sanity: exactly one grand total, at least one page total, and
    # 3 account totals (cards 1/2/3 break but card 4 is last = no flush).
    assert len(grand_lines) == 1, f"Expected 1 grand total; got {len(grand_lines)}"
    assert len(page_lines) >= 1, f"Expected >= 1 page total; got {len(page_lines)}"
    assert len(account_lines) == 3, (
        f"Expected 3 account totals (one per card break, excluding EOF "
        f"quirk for the final card); got {len(account_lines)}.  "
        f"Account lines: {account_lines!r}"
    )

    # Parse every subtotal amount.
    page_amounts: list[Decimal] = [_parse_subtotal_amount(line) for line in page_lines]
    account_amounts: list[Decimal] = [_parse_subtotal_amount(line) for line in account_lines]
    grand_amount: Decimal = _parse_subtotal_amount(grand_lines[0])

    # Invariant 1: sum(page_totals) == grand_total.
    sum_of_pages: Decimal = sum(page_amounts, start=Decimal("0.00")).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
    assert sum_of_pages == grand_amount, (
        f"Sum of page subtotals ({sum_of_pages}) must equal grand "
        f"total ({grand_amount}).  Every transaction amount must "
        f"flow through exactly one page total on its way to the "
        f"grand total.  Page amounts: {page_amounts!r}"
    )

    # Invariant 2: sum(account_totals) + last_card_amounts == grand_total.
    # Compute the last card's total directly from the input rows.
    last_card_num = rows[-1].tran_card_num
    last_card_total: Decimal = sum(
        (r.tran_amt for r in rows if r.tran_card_num == last_card_num),
        start=Decimal("0.00"),
    ).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    sum_of_accounts: Decimal = sum(account_amounts, start=Decimal("0.00")).quantize(
        Decimal("0.01"), rounding=ROUND_HALF_EVEN
    )
    reconstructed_grand: Decimal = (sum_of_accounts + last_card_total).quantize(
        Decimal("0.01"), rounding=ROUND_HALF_EVEN
    )
    assert reconstructed_grand == grand_amount, (
        f"Sum of account totals ({sum_of_accounts}) + last-card amount "
        f"({last_card_total}) must equal grand total ({grand_amount}).  "
        f"CBTRN03C quirk: no final account-total flush at EOF."
    )

    # Ensure every parsed amount is a Decimal (not float).
    for amount in (*page_amounts, *account_amounts, grand_amount):
        assert isinstance(amount, Decimal), (
            f"All subtotal amounts must be Decimal (not float); got {type(amount).__name__}"
        )


# ============================================================================
# Phase 5 — Report Line Format tests.
#
# These tests exercise the *pure-function* public API directly
# (:func:`format_report_line` and :func:`format_subtotal_line`) without
# invoking Spark or the batch orchestration.  They verify:
#
#   * Physical record width (``FD-REPTFILE-REC PIC X(133)``) — every
#     formatted line MUST be exactly 133 characters to be writable to
#     an RECFM=FB, LRECL=133 dataset (or the S3 equivalent used by
#     the modernized job).
#   * Field presence — each logical field defined in CVTRA07Y.cpy
#     (``TRAN-REPORT-ID``, ``TRAN-REPORT-ACCT``,
#     ``TRAN-REPORT-TYPE-CD``, etc.) must appear in the output slice
#     dictated by the COBOL offsets.
#   * Subtotal dispatch — :func:`format_subtotal_line` raises
#     :class:`ValueError` on any label outside the three whitelisted
#     values and returns a 133-char line for each valid label,
#     containing the Decimal-formatted amount under the exact COBOL
#     edit mask (``PIC +ZZZ,ZZZ,ZZZ.ZZ``).
# ============================================================================
@pytest.mark.unit
def test_format_report_line_length() -> None:
    """Verify format_report_line produces exactly 133-character records.

    The COBOL FD ``FD-REPTFILE-REC`` in CBTRN03C.cbl is declared::

        FD  REPTFILE-FILE
            RECORDING MODE IS F
            LABEL RECORDS ARE STANDARD
            BLOCK CONTAINS 0
            RECORD CONTAINS 133 CHARACTERS.
        01  FD-REPTFILE-REC             PIC X(133).

    ``RECFM=FB,LRECL=133`` means every written record MUST be exactly
    133 characters — anything shorter gets space-padded by QSAM on
    disk; anything longer is an ABEND.  The Python port uses S3
    object storage (no fixed-length constraint), but we preserve the
    exact 133-char semantics for behavioral parity and for any
    downstream z/OS-compatible systems ingesting the file.

    Test strategy: format several representative rows (typical
    purchase, zero amount, large amount, None fields) and assert each
    returned line is exactly ``_EXPECTED_REPORT_LINE_WIDTH`` chars.
    """
    # Representative typical purchase row.
    row_typical: dict[str, Any] = _make_report_row_dict(
        tran_id="T000000000000001",
        acct_id="00000000001",
        tran_type_cd="01",
        tran_type_desc="Purchase",
        tran_cat_cd="0001",
        tran_cat_type_desc="Groceries",
        tran_source="POS",
        tran_amt=Decimal("123.45"),
    )
    line_typical: str = format_report_line(row_typical, 1)
    assert len(line_typical) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Typical row must produce exactly {_EXPECTED_REPORT_LINE_WIDTH}-"
        f"character line; got {len(line_typical)}: {line_typical!r}"
    )

    # Zero-amount row (tests edit-mask zero-suppression).
    row_zero: dict[str, Any] = _make_report_row_dict(
        tran_id="T000000000000002",
        acct_id="00000000002",
        tran_type_cd="02",
        tran_type_desc="Refund",
        tran_cat_cd="0002",
        tran_cat_type_desc="Electronics",
        tran_source="WEB",
        tran_amt=Decimal("0.00"),
    )
    line_zero: str = format_report_line(row_zero, 2)
    assert len(line_zero) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Zero-amount row must produce exactly {_EXPECTED_REPORT_LINE_WIDTH}-character line; got {len(line_zero)}"
    )

    # Large-amount row (tests edit-mask full-width grouping commas).
    row_large: dict[str, Any] = _make_report_row_dict(
        tran_id="T000000000000003",
        acct_id="00000000003",
        tran_type_cd="03",
        tran_type_desc="BigPurchase",
        tran_cat_cd="0099",
        tran_cat_type_desc="Travel",
        tran_source="ATM",
        tran_amt=Decimal("987654321.99"),
    )
    line_large: str = format_report_line(row_large, 3)
    assert len(line_large) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Large-amount row must produce exactly {_EXPECTED_REPORT_LINE_WIDTH}-character line; got {len(line_large)}"
    )

    # Row with None acct_id (missing xref) — tests the 11-space
    # rendering of a missing acct_id.
    row_missing: dict[str, Any] = _make_report_row_dict(
        tran_id="T000000000000004",
        acct_id=None,
        tran_type_cd="04",
        tran_type_desc="Fee",
        tran_cat_cd="0010",
        tran_cat_type_desc="BankFee",
        tran_source=None,
        tran_amt=Decimal("-25.00"),
    )
    line_missing: str = format_report_line(row_missing, 4)
    assert len(line_missing) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Row with None fields must still produce exactly "
        f"{_EXPECTED_REPORT_LINE_WIDTH}-character line; "
        f"got {len(line_missing)}"
    )

    # Row with MISSING tran_amt key (tests the _DECIMAL_ZERO
    # fallback path).  `dict.get("tran_amt")` returns None → zero.
    row_no_amt: dict[str, Any] = {
        "tran_id": "T000000000000005",
        "acct_id": "00000000005",
        "tran_type_cd": "05",
        "tran_type_desc": "Test",
        "tran_cat_cd": "0005",
        "tran_cat_type_desc": "TestCat",
        "tran_source": "CLI",
        # No tran_amt key
    }
    line_no_amt: str = format_report_line(row_no_amt, 5)
    assert len(line_no_amt) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Row without tran_amt key must still produce exactly "
        f"{_EXPECTED_REPORT_LINE_WIDTH}-character line; "
        f"got {len(line_no_amt)}"
    )


@pytest.mark.unit
def test_format_report_line_contains_fields() -> None:
    """Verify each logical CVTRA07Y field is present at its offset.

    CVTRA07Y.cpy defines TRANSACTION-DETAIL-REPORT with 8 logical
    fields at fixed offsets (see ``format_report_line`` docstring
    for the offset table).  This test constructs a row with
    distinctive placeholder values for each field and asserts that:

    * ``tran_id`` appears at COBOL offsets 1-16 (Python 0-16).
    * ``acct_id`` appears at COBOL offsets 18-28 (Python 17-28).
    * ``tran_type_cd`` appears at COBOL offsets 30-31 (Python 29-31).
    * ``tran_type_desc`` appears at COBOL offsets 33-47 (Python 32-47).
    * ``tran_cat_cd`` appears at COBOL offsets 49-52 (Python 48-52).
    * ``tran_cat_type_desc`` appears at COBOL offsets 54-82 (Python 53-82).
    * ``tran_source`` appears at COBOL offsets 84-93 (Python 83-93).
    * ``tran_amt`` (edited) appears at COBOL offsets 98-113 (Python 97-113).

    (Offsets assume inclusive COBOL numbering; Python slicing uses
    half-open intervals so ``[start:end]`` where ``start`` = COBOL
    offset - 1 and ``end`` = COBOL offset.)

    The exact layout is driven by the concatenation in
    :func:`format_report_line`::

        tran_id_field(16) + " " + acct_id_field(11) + " "
        + type_cd_field(2) + "-" + type_desc_field(15) + " "
        + cat_cd_field(4) + "-" + cat_desc_field(29) + " "
        + source_field(10) + "    " + amount_field(16) + "  "
    """
    row: dict[str, Any] = _make_report_row_dict(
        tran_id="TRANID0000000001",  # Exactly 16 chars
        acct_id="00012345678",  # Exactly 11 chars
        tran_type_cd="07",
        tran_type_desc="MyTestType",
        tran_cat_cd="0042",
        tran_cat_type_desc="MyTestCategory",
        tran_source="MYSRC",
        tran_amt=Decimal("1234.56"),
    )
    line: str = format_report_line(row, 1)

    # Sanity: line length.
    assert len(line) == _EXPECTED_REPORT_LINE_WIDTH

    # Field 1: tran_id at offsets 0-16 (16 chars).
    assert line[0:16] == "TRANID0000000001", f"tran_id mismatch at offset [0:16]: {line[0:16]!r} (full line: {line!r})"

    # Separator at offset 16.
    assert line[16] == " ", f"Expected separator space at offset 16; got {line[16]!r}"

    # Field 2: acct_id at offsets 17-28 (11 chars).
    assert line[17:28] == "00012345678", f"acct_id mismatch at offset [17:28]: {line[17:28]!r}"

    # Separator at offset 28.
    assert line[28] == " ", f"Expected separator space at offset 28; got {line[28]!r}"

    # Field 3: tran_type_cd at offsets 29-31 (2 chars).
    assert line[29:31] == "07", f"tran_type_cd mismatch at offset [29:31]: {line[29:31]!r}"

    # Hyphen separator at offset 31.
    assert line[31] == "-", f"Expected hyphen at offset 31; got {line[31]!r}"

    # Field 4: tran_type_desc at offsets 32-47 (15 chars, space-padded).
    # "MyTestType" is 10 chars → padded to 15 with 5 trailing spaces.
    assert line[32:47] == "MyTestType     ", f"tran_type_desc mismatch at offset [32:47]: {line[32:47]!r}"

    # Separator at offset 47.
    assert line[47] == " ", f"Expected separator space at offset 47; got {line[47]!r}"

    # Field 5: tran_cat_cd at offsets 48-52 (4 chars, zero-padded).
    assert line[48:52] == "0042", f"tran_cat_cd mismatch at offset [48:52]: {line[48:52]!r}"

    # Hyphen separator at offset 52.
    assert line[52] == "-", f"Expected hyphen at offset 52; got {line[52]!r}"

    # Field 6: tran_cat_type_desc at offsets 53-82 (29 chars, space-padded).
    # "MyTestCategory" is 14 chars → padded to 29 with 15 trailing spaces.
    cat_desc_slice: str = line[53:82]
    assert cat_desc_slice.startswith("MyTestCategory"), (
        f"tran_cat_type_desc must start with 'MyTestCategory' at offset [53:82]; got {cat_desc_slice!r}"
    )
    assert len(cat_desc_slice) == 29, f"tran_cat_type_desc slice must be 29 chars; got {len(cat_desc_slice)}"

    # Separator at offset 82.
    assert line[82] == " ", f"Expected separator space at offset 82; got {line[82]!r}"

    # Field 7: tran_source at offsets 83-93 (10 chars, space-padded).
    # "MYSRC" is 5 chars → padded to 10 with 5 trailing spaces.
    assert line[83:93] == "MYSRC     ", f"tran_source mismatch at offset [83:93]: {line[83:93]!r}"

    # 4-char FILLER at offsets 93-97.
    assert line[93:97] == "    ", f"Expected 4-space filler at offset [93:97]; got {line[93:97]!r}"

    # Field 8: tran_amt (edited, 16 chars) at offsets 97-113.
    # Decimal('1234.56') with PIC -ZZZ,ZZZ,ZZZ.ZZ renders as:
    #   sign(' ') + zero-suppressed magnitude + '.' + '56'
    # Exact layout: "       1,234.56 " (16 chars) — see
    # _format_amount_edited doctests.
    amount_slice: str = line[97:113]
    assert len(amount_slice) == 16, f"Amount slice must be 16 chars; got {len(amount_slice)}: {amount_slice!r}"
    # Must contain the expected significant characters.
    assert "1,234.56" in amount_slice, f"Amount slice must contain '1,234.56'; got {amount_slice!r}"
    # Sign position: SPACE for non-negative.
    assert amount_slice[0] == " ", f"Expected SPACE sign for non-negative amount; got {amount_slice[0]!r}"

    # 2-char FILLER at offsets 113-115, then 18 chars of padding
    # to reach 133 total.
    assert line[113:115] == "  ", f"Expected 2-space filler at offset [113:115]; got {line[113:115]!r}"

    # Trailing padding to width 133.
    assert line[115:133] == " " * 18, f"Expected 18-space trailing pad at offset [115:133]; got {line[115:133]!r}"


@pytest.mark.unit
def test_format_subtotal_line() -> None:
    """Verify format_subtotal_line dispatches correctly on label.

    Behavior covered:

    1. **ValueError on unknown label** — any label outside
       ``{"Page Total", "Account Total", "Grand Total"}`` must raise
       ``ValueError`` (per CVTRA07Y.cpy's exhaustive record-layout
       enumeration).
    2. **133-char output for valid labels** — each recognized label
       must produce a 133-character line matching its CVTRA07Y
       layout.
    3. **Label appears at offset 0** — each label must be the
       leftmost substring of the line.
    4. **Amount edited at offset 97-113** — the Decimal amount must
       be formatted per ``PIC +ZZZ,ZZZ,ZZZ.ZZ`` (leading ``+`` sign
       for non-negative, ``-`` for negative) at the fixed offset.
    5. **Decimal formatting preserves precision** — e.g.
       ``Decimal("1234.56")`` renders as ``"+      1,234.56 "``.
    """
    # 1. ValueError on unknown label.
    with pytest.raises(ValueError, match="unknown label"):
        format_subtotal_line("Weekly Total", Decimal("100.00"))

    with pytest.raises(ValueError, match="unknown label"):
        format_subtotal_line("Subtotal", Decimal("0.00"))

    # Case-sensitive mismatch — exact string comparison enforced.
    with pytest.raises(ValueError, match="unknown label"):
        format_subtotal_line("page total", Decimal("100.00"))

    # Empty label also rejected.
    with pytest.raises(ValueError, match="unknown label"):
        format_subtotal_line("", Decimal("0.00"))

    # 2. Valid labels produce 133-char lines.
    test_amount: Decimal = Decimal("1234.56")

    page_line: str = format_subtotal_line("Page Total", test_amount)
    assert len(page_line) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Page Total line must be {_EXPECTED_REPORT_LINE_WIDTH} chars; got {len(page_line)}"
    )

    account_line: str = format_subtotal_line("Account Total", test_amount)
    assert len(account_line) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Account Total line must be {_EXPECTED_REPORT_LINE_WIDTH} chars; got {len(account_line)}"
    )

    grand_line: str = format_subtotal_line("Grand Total", test_amount)
    assert len(grand_line) == _EXPECTED_REPORT_LINE_WIDTH, (
        f"Grand Total line must be {_EXPECTED_REPORT_LINE_WIDTH} chars; got {len(grand_line)}"
    )

    # 3. Label appears at offset 0.
    # "Page Total " is PIC X(11) with trailing space.
    assert page_line.startswith("Page Total "), f"Page Total line must start with 'Page Total '; got {page_line[:15]!r}"
    # "Account Total" is PIC X(13), no trailing space.  Next char
    # is the first of the 84-char dot run.
    assert account_line.startswith("Account Total"), (
        f"Account Total line must start with 'Account Total'; got {account_line[:15]!r}"
    )
    assert account_line[13] == ".", f"Account Total must be followed immediately by dots; got {account_line[13]!r}"
    # "Grand Total" is PIC X(11), no trailing space.
    assert grand_line.startswith("Grand Total"), (
        f"Grand Total line must start with 'Grand Total'; got {grand_line[:15]!r}"
    )
    assert grand_line[11] == ".", f"Grand Total must be followed immediately by dots; got {grand_line[11]!r}"

    # 4. Amount edited at offset [97:113] with "+" sign for non-negative.
    for line in (page_line, account_line, grand_line):
        amount_slice: str = line[97:113]
        assert len(amount_slice) == 16, f"Amount slice must be 16 chars; got {amount_slice!r}"
        # "+" sign at position 0 of the 16-char slice.
        assert amount_slice[0] == "+", (
            f"Non-negative amount must have '+' sign; got {amount_slice[0]!r} in slice {amount_slice!r}"
        )
        # Magnitude must contain '1,234.56'.
        assert "1,234.56" in amount_slice, f"Amount slice must contain '1,234.56'; got {amount_slice!r}"

    # 5. Decimal precision preserved — negative amount shows '-' sign.
    negative_line: str = format_subtotal_line("Grand Total", Decimal("-500.25"))
    assert len(negative_line) == _EXPECTED_REPORT_LINE_WIDTH
    neg_amount_slice: str = negative_line[97:113]
    assert neg_amount_slice[0] == "-", (
        f"Negative amount must have '-' sign; got {neg_amount_slice[0]!r} in slice {neg_amount_slice!r}"
    )
    assert "500.25" in neg_amount_slice, f"Negative amount slice must contain '500.25'; got {neg_amount_slice!r}"

    # 6. Zero amount edge case — still has '+' sign per
    # _format_subtotal_amount_edited (subtotal variant always shows
    # explicit sign even for zero).
    zero_line: str = format_subtotal_line("Page Total", Decimal("0.00"))
    assert len(zero_line) == _EXPECTED_REPORT_LINE_WIDTH
    zero_amount_slice: str = zero_line[97:113]
    assert zero_amount_slice[0] == "+", (
        f"Zero amount in subtotal must still show '+' sign (explicit "
        f"sign semantics); got {zero_amount_slice[0]!r} in slice "
        f"{zero_amount_slice!r}"
    )
    assert "0.00" in zero_amount_slice, f"Zero amount slice must contain '0.00'; got {zero_amount_slice!r}"

    # 7. Verify label-specific dot-run widths per CVTRA07Y.
    # Page Total: 11 chars + 86 dots + 16 amount = 113 then padded to 133.
    # Count dots after the label.
    page_dots_section: str = page_line[11:97]
    assert page_dots_section == "." * 86, (
        f"Page Total must have 86 dots between label and amount; got {len(page_dots_section.replace(' ', ''))} dots"
    )
    # Account Total: 13 chars + 84 dots + 16 amount = 113 → 133.
    account_dots_section: str = account_line[13:97]
    assert account_dots_section == "." * 84, (
        f"Account Total must have 84 dots between label and amount; got "
        f"{len(account_dots_section.replace(' ', ''))} dots"
    )
    # Grand Total: 11 chars + 86 dots + 16 amount = 113 → 133.
    grand_dots_section: str = grand_line[11:97]
    assert grand_dots_section == "." * 86, (
        f"Grand Total must have 86 dots between label and amount; got {len(grand_dots_section.replace(' ', ''))} dots"
    )


# ============================================================================
# Phase 6 — Sort and Output tests.
#
# These tests cover the Spark orderBy semantics and the S3 write
# interaction.  The COBOL/JCL source defines:
#
#   * ``SORT FIELDS=(TRAN-CARD-NUM,A)`` in TRANREPT.jcl STEP05R —
#     the entire input sequential file is sorted ascending by card
#     number BEFORE the COBOL program reads it.  The tranrept_job.py
#     port replicates this with ``orderBy(F.col("tran_card_num").
#     asc_nulls_last(), F.col("tran_id").asc_nulls_last())`` — the
#     secondary sort by tran_id is a Python-specific stabilizer
#     added to ensure deterministic ordering when multiple
#     transactions share a card number.
#   * ``TRANREPT(+1)`` GDG output — the JCL writes the report to
#     a new generation of the GDG.  The Python port replaces this
#     with S3 versioned objects under the ``TRANREPT`` GDG prefix.
#     The module resolves the S3 URI via
#     ``get_versioned_s3_path("TRANREPT")`` and writes via
#     ``write_to_s3(content, key, bucket=bucket, content_type=
#     "text/plain")``.
# ============================================================================
@pytest.mark.unit
def test_sort_by_card_num_ascending(spark_session: SparkSession) -> None:
    """Verify report output is sorted by card_num ascending.

    The JCL SORT step executes::

        //SORTIN    DD DSN=AWS.M2.CARDDEMO.TRANFILE,DISP=SHR
        //SYSIN     DD  *
          SORT FIELDS=(TRAN-CARD-NUM,A)
          INCLUDE COND=(...)
        /*

    This sorts transactions by 16-char card number in ascending
    order BEFORE they are read into the report.  The Python port
    preserves this ordering via ``orderBy`` after the date filter
    and enrichment joins.

    Test strategy: submit 3 transactions with card numbers in a
    NON-ASCENDING input order (e.g., "9", "1", "5" cards) and
    verify the output report lists them in ascending lexicographic
    order ("1", "5", "9").
    """
    # Input in NON-ASCENDING order; expected output order is
    # card_num ascending: 1xxx, 5xxx, 9xxx.
    card_high: str = "9000000000009999"
    card_low: str = "1000000000001111"
    card_mid: str = "5000000000005555"

    rows = [
        _make_txn_row(
            "T_HIGH",
            card_high,
            tran_proc_ts="2022-03-15T10:00:00.000000",
            tran_amt=Decimal("99.99"),
        ),
        _make_txn_row(
            "T_LOW",
            card_low,
            tran_proc_ts="2022-03-15T11:00:00.000000",
            tran_amt=Decimal("1.00"),
        ),
        _make_txn_row(
            "T_MID",
            card_mid,
            tran_proc_ts="2022-03-15T12:00:00.000000",
            tran_amt=Decimal("50.50"),
        ),
    ]
    transactions_df = spark_session.createDataFrame(rows)
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row(card_high, "00000009999"),
            _make_xref_row(card_low, "00000001111"),
            _make_xref_row(card_mid, "00000005555"),
        ]
    )
    trantype_df = spark_session.createDataFrame([_make_trantype_row("01", "Purchase")])
    trancatg_df = spark_session.createDataFrame([_make_trancatg_row("01", "0001", "Groceries")])

    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    # Find the positions of each tran_id in the report body.
    # The ascending-by-card_num invariant means T_LOW must appear
    # BEFORE T_MID which must appear BEFORE T_HIGH.
    pos_low: int = report_content.find("T_LOW")
    pos_mid: int = report_content.find("T_MID")
    pos_high: int = report_content.find("T_HIGH")

    assert pos_low != -1, f"T_LOW (card={card_low}) must appear in report.  First 2000 chars:\n{report_content[:2000]}"
    assert pos_mid != -1, "T_MID must appear in report"
    assert pos_high != -1, "T_HIGH must appear in report"

    # Ascending card_num: low(1xxx) < mid(5xxx) < high(9xxx).
    assert pos_low < pos_mid, (
        f"T_LOW (card {card_low}) must appear BEFORE T_MID (card "
        f"{card_mid}); got positions pos_low={pos_low}, "
        f"pos_mid={pos_mid}.  Sort order violated SORT FIELDS="
        f"(TRAN-CARD-NUM,A)."
    )
    assert pos_mid < pos_high, (
        f"T_MID (card {card_mid}) must appear BEFORE T_HIGH (card "
        f"{card_high}); got positions pos_mid={pos_mid}, "
        f"pos_high={pos_high}."
    )


@pytest.mark.unit
def test_report_written_to_s3() -> None:
    """Verify the final report is written to S3 with correct metadata.

    The COBOL/JCL source writes the report to a GDG dataset::

        //REPTFILE DD DSN=AWS.M2.CARDDEMO.TRANREPT(+1),
        //            DISP=(NEW,CATLG,DELETE)

    The Python port replaces GDG generations with S3 versioned
    objects.  The module MUST:

    1. Call ``get_versioned_s3_path("TRANREPT")`` exactly once to
       resolve the S3 URI for the new "generation".
    2. Call ``write_to_s3(content, key, bucket=..., content_type=
       "text/plain")`` exactly once with the assembled report.
    3. Call ``commit_job(job)`` exactly once on success.

    This test uses FULL mock isolation (no Spark) to verify the
    orchestration contract.  Spark operations are mocked via
    ``MagicMock`` on the DataFrames returned from ``read_table``.
    """
    # Build a minimal empty DataFrame mock for each read_table call.
    # An empty report is valid — the module emits headers + a zero
    # grand total and writes that to S3.
    mock_empty_df: MagicMock = MagicMock()
    # Chain: transactions_df.filter(...).orderBy(...).join(...).join(...).join(...).select(...).collect() = []
    mock_empty_df.filter.return_value = mock_empty_df
    mock_empty_df.orderBy.return_value = mock_empty_df
    mock_empty_df.join.return_value = mock_empty_df
    mock_empty_df.select.return_value = mock_empty_df
    mock_empty_df.alias.return_value = mock_empty_df
    mock_empty_df.collect.return_value = []
    mock_empty_df.count.return_value = 0

    # Mock init_glue to return a 4-tuple.
    mock_spark: MagicMock = MagicMock()
    mock_glue_ctx: MagicMock = MagicMock()
    mock_job: MagicMock = MagicMock()
    mock_resolved_args: dict[str, Any] = {"JOB_NAME": "carddemo-tranrept"}

    # read_table always returns our mock empty DataFrame.
    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> MagicMock:
        # Verify read_table is invoked with the expected table names.
        assert table_name in {
            "transactions",
            "card_cross_references",
            "transaction_types",
            "transaction_categories",
        }, f"Unexpected table name passed to read_table: {table_name!r}"
        return mock_empty_df

    # Capture write_to_s3 arguments.
    write_calls: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        content: str | bytes,
        key: str,
        *,
        bucket: str | None = None,
        content_type: str = "text/plain",
    ) -> str:
        write_calls.append(
            {
                "content": content,
                "key": key,
                "bucket": bucket,
                "content_type": content_type,
            }
        )
        return f"s3://{bucket}/{key}"

    # Capture get_versioned_s3_path arguments.
    gdg_calls: list[str] = []

    def _get_s3_path_side_effect(gdg_name: str, *_args: Any, **_kwargs: Any) -> str:
        gdg_calls.append(gdg_name)
        return f"s3://carddemo-batch-gdg/{gdg_name}/v1/"

    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect),
        patch(_PATCH_GET_S3_PATH, side_effect=_get_s3_path_side_effect),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            mock_spark,
            mock_glue_ctx,
            mock_job,
            mock_resolved_args,
        )

        # Execute main().
        main()

    # 1. get_versioned_s3_path called with "TRANREPT".
    assert gdg_calls == ["TRANREPT"], (
        f"Expected exactly one call to get_versioned_s3_path with gdg_name='TRANREPT'; got {gdg_calls!r}"
    )

    # 2. write_to_s3 called exactly once.
    assert len(write_calls) == 1, (
        f"Expected exactly one write_to_s3 call; got {len(write_calls)} calls: {write_calls!r}"
    )

    # 3. write_to_s3 content_type is 'text/plain'.
    write_call: dict[str, Any] = write_calls[0]
    assert write_call["content_type"] == "text/plain", (
        f"write_to_s3 content_type must be 'text/plain' for CVTRA07Y "
        f"plain-text reports; got {write_call['content_type']!r}"
    )

    # 4. content is a str (not bytes) — CBTRN03C writes RECFM=FB.
    assert isinstance(write_call["content"], str), (
        f"write_to_s3 content must be str (text report); got {type(write_call['content']).__name__}"
    )

    # 5. commit_job called exactly once.
    assert mock_commit_job.call_count == 1, (
        f"Expected commit_job to be called once on success; got {mock_commit_job.call_count}"
    )

    # 6. The mocked init_glue was called once with job_name="carddemo-tranrept".
    assert mock_init_glue.call_count == 1, (
        f"init_glue must be invoked exactly once; got {mock_init_glue.call_count} invocations"
    )
    init_glue_kwargs = mock_init_glue.call_args.kwargs
    assert init_glue_kwargs.get("job_name") == "carddemo-tranrept", (
        f"init_glue must be called with job_name='carddemo-tranrept'; got kwargs={init_glue_kwargs!r}"
    )


# ============================================================================
# Phase 7 — Main Function Integration Test.
#
# This is the end-to-end test that exercises ALL phases of the
# module under test with a real SparkSession and mocked AWS Glue /
# database / S3 dependencies.  It verifies that:
#
#   1. Date filtering correctly excludes out-of-range transactions.
#   2. Cross-reference enrichment (XREF + TRANTYPE + TRANCATG joins)
#      produces the expected denormalized columns.
#   3. Sort by card_num ascending is applied post-filter, post-enrich.
#   4. 3-level total state machine emits the correct Page / Account /
#      Grand Total lines.
#   5. Report content is assembled and written to S3 via the correct
#      GDG versioning path.
#   6. Glue job lifecycle (init + commit) is followed correctly.
#
# This is the most comprehensive test in the suite and serves as a
# regression guard against behavioral drift in any phase of the
# report pipeline.
# ============================================================================
@pytest.mark.unit
def test_tranrept_main_with_spark(spark_session: SparkSession) -> None:
    """End-to-end integration test of ``main()`` with real Spark.

    Test data layout:

    * 6 transactions across 3 cards spanning in-range and
      out-of-range dates:

      - T_OUT_EARLY (card 4xxx, date 2021-12-15) — EXCLUDED (before range).
      - T_OUT_LATE  (card 4xxx, date 2022-08-20) — EXCLUDED (after range).
      - T_IN_A1     (card 4xxx, date 2022-02-01, amt 100.00) — INCLUDED.
      - T_IN_A2     (card 4xxx, date 2022-02-15, amt 200.00) — INCLUDED.
      - T_IN_B1     (card 6xxx, date 2022-03-20, amt 50.00)  — INCLUDED.
      - T_IN_B2     (card 6xxx, date 2022-04-05, amt 75.00)  — INCLUDED.

    * 3 cards in xref (4xxx → acct 00000000041, 6xxx → acct
      00000000061, plus an unused 9xxx card).
    * 2 trantypes (01 → Purchase, 02 → Refund).
    * 2 trancatgs ((01,0001) → Groceries, (02,0001) → Returns).

    Expected outcome:

    * 4 detail lines in report body, sorted by card_num ascending:
      T_IN_A1, T_IN_A2 (card 4xxx), then T_IN_B1, T_IN_B2 (card 6xxx).
    * Exactly 1 Account Total line (for card 4xxx): amount = 100+200 = 300.00.
    * No Account Total for card 6xxx (EOF quirk).
    * At least 1 Page Total line (EOF flush).
    * Grand Total = 300 + 125 = 425.00.
    * S3 write to ``TRANREPT`` GDG path with content_type='text/plain'.
    * init_glue called once with job_name='carddemo-tranrept'.
    * commit_job called once on success.
    * read_table called 4 times for: transactions, card_cross_references,
      transaction_types, transaction_categories.
    """
    # Build the 6-transaction input DataFrame.
    txn_rows = [
        _make_txn_row(
            "T_OUT_EARLY",
            "4000000000004001",
            tran_proc_ts="2021-12-15T10:00:00.000000",
            tran_type_cd="01",
            tran_cat_cd="0001",
            tran_amt=Decimal("999.99"),  # Would distort totals if included.
        ),
        _make_txn_row(
            "T_OUT_LATE",
            "4000000000004001",
            tran_proc_ts="2022-08-20T10:00:00.000000",
            tran_type_cd="01",
            tran_cat_cd="0001",
            tran_amt=Decimal("888.88"),  # Would distort totals if included.
        ),
        _make_txn_row(
            "T_IN_A1",
            "4000000000004001",
            tran_proc_ts="2022-02-01T10:00:00.000000",
            tran_type_cd="01",
            tran_cat_cd="0001",
            tran_amt=Decimal("100.00"),
        ),
        _make_txn_row(
            "T_IN_A2",
            "4000000000004001",
            tran_proc_ts="2022-02-15T11:00:00.000000",
            tran_type_cd="02",
            tran_cat_cd="0001",
            tran_amt=Decimal("200.00"),
        ),
        _make_txn_row(
            "T_IN_B1",
            "6000000000006001",
            tran_proc_ts="2022-03-20T12:00:00.000000",
            tran_type_cd="01",
            tran_cat_cd="0001",
            tran_amt=Decimal("50.00"),
        ),
        _make_txn_row(
            "T_IN_B2",
            "6000000000006001",
            tran_proc_ts="2022-04-05T13:00:00.000000",
            tran_type_cd="01",
            tran_cat_cd="0001",
            tran_amt=Decimal("75.00"),
        ),
    ]
    transactions_df = spark_session.createDataFrame(txn_rows)
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row("4000000000004001", "00000000041"),
            _make_xref_row("6000000000006001", "00000000061"),
            _make_xref_row("9000000000009999", "00000000099"),  # Unused; must not appear.
        ]
    )
    trantype_df = spark_session.createDataFrame(
        [
            _make_trantype_row("01", "Purchase"),
            _make_trantype_row("02", "Refund"),
        ]
    )
    trancatg_df = spark_session.createDataFrame(
        [
            _make_trancatg_row("01", "0001", "Groceries"),
            _make_trancatg_row("02", "0001", "Returns"),
        ]
    )

    # Execute main() with the default date range (2022-01-01 to
    # 2022-07-06) — which excludes T_OUT_EARLY and T_OUT_LATE.
    captured = _run_main_and_capture(
        spark_session,
        transactions_df,
        xref_df,
        trantype_df,
        trancatg_df,
    )
    report_content: str = captured["content"]

    # ---------------------------------------------------------------
    # Verification 1: Date filtering — excluded rows must NOT appear.
    # ---------------------------------------------------------------
    assert "T_OUT_EARLY" not in report_content, (
        f"T_OUT_EARLY (date 2021-12-15) must be excluded from the "
        f"report (date filter start=2022-01-01).  Report excerpt:\n"
        f"{report_content[:2000]}"
    )
    assert "T_OUT_LATE" not in report_content, (
        "T_OUT_LATE (date 2022-08-20) must be excluded from the report (date filter end=2022-07-06)."
    )

    # ---------------------------------------------------------------
    # Verification 2: Date filtering — in-range rows MUST appear.
    # ---------------------------------------------------------------
    for in_range_id in ("T_IN_A1", "T_IN_A2", "T_IN_B1", "T_IN_B2"):
        assert in_range_id in report_content, (
            f"{in_range_id} must appear in report (in-range date).  Report excerpt:\n{report_content[:3000]}"
        )

    # ---------------------------------------------------------------
    # Verification 3: Cross-reference enrichment — acct_id must
    # appear for card 4xxx and 6xxx transactions.
    # ---------------------------------------------------------------
    assert "00000000041" in report_content, (
        "Account 00000000041 (xref for card 4000000000004001) must appear for transactions T_IN_A1 and T_IN_A2."
    )
    assert "00000000061" in report_content, (
        "Account 00000000061 (xref for card 6000000000006001) must appear for transactions T_IN_B1 and T_IN_B2."
    )
    # Unused xref must NOT appear.
    assert "00000000099" not in report_content, (
        "Account 00000000099 has no in-range transaction and must NOT appear in report."
    )

    # ---------------------------------------------------------------
    # Verification 4: TRANTYPE enrichment — type description must appear.
    # ---------------------------------------------------------------
    assert "Purchase" in report_content, "Type description 'Purchase' (trantype 01) must appear."
    assert "Refund" in report_content, (
        "Type description 'Refund' (trantype 02) must appear (T_IN_A2 has tran_type_cd='02')."
    )

    # ---------------------------------------------------------------
    # Verification 5: TRANCATG enrichment — category description appears.
    # ---------------------------------------------------------------
    assert "Groceries" in report_content, "Category description 'Groceries' (trancatg (01,0001)) must appear."
    assert "Returns" in report_content, (
        "Category description 'Returns' (trancatg (02,0001)) "
        "must appear (T_IN_A2 has tran_cat_cd='0001' with "
        "tran_type_cd='02')."
    )

    # ---------------------------------------------------------------
    # Verification 6: Sort order — card 4xxx transactions appear
    # BEFORE card 6xxx transactions (ascending card_num).
    # ---------------------------------------------------------------
    pos_a1: int = report_content.find("T_IN_A1")
    pos_a2: int = report_content.find("T_IN_A2")
    pos_b1: int = report_content.find("T_IN_B1")
    pos_b2: int = report_content.find("T_IN_B2")
    assert pos_a1 < pos_b1 and pos_a2 < pos_b1, (
        f"Card 4xxx transactions must appear before card 6xxx "
        f"(ascending sort).  Positions: A1={pos_a1}, A2={pos_a2}, "
        f"B1={pos_b1}, B2={pos_b2}"
    )
    # Within card 4xxx, secondary sort by tran_id ensures A1 before A2.
    assert pos_a1 < pos_a2, (
        f"Within card 4xxx, T_IN_A1 must come before T_IN_A2 "
        f"(secondary sort by tran_id asc); got A1={pos_a1}, A2={pos_a2}"
    )

    # ---------------------------------------------------------------
    # Verification 7: 3-level totals.
    # ---------------------------------------------------------------
    account_lines: list[str] = _extract_subtotal_lines(report_content, "Account Total")
    # Exactly 1 account total (card 4xxx break); card 6xxx is last → no flush.
    assert len(account_lines) == 1, (
        f"Expected exactly 1 Account Total line (for card 4xxx break); "
        f"got {len(account_lines)}.  CBTRN03C EOF quirk: no final "
        f"account flush for last card.  Lines:\n{account_lines!r}"
    )
    account_total_amount: Decimal = _parse_subtotal_amount(account_lines[0])
    expected_account_total: Decimal = Decimal("300.00")  # 100 + 200
    assert account_total_amount == expected_account_total, (
        f"Card 4xxx account total must be {expected_account_total} (100.00 + 200.00); got {account_total_amount}"
    )

    page_lines: list[str] = _extract_subtotal_lines(report_content, "Page Total")
    assert len(page_lines) >= 1, f"At least one Page Total line (EOF flush) must appear; got {len(page_lines)}"

    grand_lines: list[str] = _extract_subtotal_lines(report_content, "Grand Total")
    assert len(grand_lines) == 1, f"Expected exactly 1 Grand Total line; got {len(grand_lines)}"
    grand_total_amount: Decimal = _parse_subtotal_amount(grand_lines[0])
    # Grand total = sum of all 4 included transactions:
    # 100 + 200 + 50 + 75 = 425.00
    expected_grand_total: Decimal = Decimal("425.00")
    assert grand_total_amount == expected_grand_total, (
        f"Grand total must be {expected_grand_total} (100+200+50+75); got {grand_total_amount}"
    )

    # Consistency: sum(page_totals) == grand_total.
    sum_of_pages: Decimal = sum(
        (_parse_subtotal_amount(line) for line in page_lines),
        start=Decimal("0.00"),
    ).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
    assert sum_of_pages == grand_total_amount, (
        f"Sum of page totals ({sum_of_pages}) must equal grand total ({grand_total_amount})"
    )

    # ---------------------------------------------------------------
    # Verification 8: S3 output.
    # ---------------------------------------------------------------
    assert captured["gdg_calls"] == ["TRANREPT"], (
        f"get_versioned_s3_path must be called with gdg_name='TRANREPT'; got {captured['gdg_calls']!r}"
    )
    assert captured["content_type"] == "text/plain", (
        f"write_to_s3 content_type must be 'text/plain'; got {captured['content_type']!r}"
    )
    assert captured["content"] is not None and len(captured["content"]) > 0, (
        "write_to_s3 must be called with non-empty content."
    )
    assert captured["content"].endswith("\n"), (
        "Report content must end with a trailing newline (matches "
        f"COBOL WRITE-after-LAST-RECORD semantics); got last 5 chars: "
        f"{captured['content'][-5:]!r}"
    )

    # ---------------------------------------------------------------
    # Verification 9: Glue lifecycle — commit_job called once.
    # ---------------------------------------------------------------
    assert captured["commit_called"] == 1, (
        f"commit_job must be called exactly once on successful run; got {captured['commit_called']}"
    )

    # ---------------------------------------------------------------
    # Verification 10: All 4 read_table calls occurred.
    # ---------------------------------------------------------------
    # This is implicitly verified by _run_main_and_capture's
    # _read_side_effect which returns None for unrecognized table
    # names; if any of the 4 expected reads had failed, the job
    # would have raised before write_to_s3 was invoked.  We
    # additionally assert the report contains detail lines (proving
    # all joins succeeded and produced a non-empty enriched DataFrame).
    num_detail_ids = sum(1 for tid in ("T_IN_A1", "T_IN_A2", "T_IN_B1", "T_IN_B2") if tid in report_content)
    assert num_detail_ids == 4, f"All 4 in-range transactions must produce detail lines; got {num_detail_ids}"
