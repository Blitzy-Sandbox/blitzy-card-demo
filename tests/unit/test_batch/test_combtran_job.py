# ============================================================================
# tests/unit/test_batch/test_combtran_job.py
# Unit tests for Stage 3 COMBTRAN merge/sort PySpark Glue job.
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
"""Unit tests for ``combtran_job.py`` — Stage 3: Combined Transactions.

Validates the PySpark merge/sort implementation that replaces the Stage 3
pipeline step — a pure ``DFSORT`` + ``IDCAMS REPRO`` JCL-only job with
**no COBOL program counterpart**.

Source
------
* ``app/jcl/COMBTRAN.jcl`` — 53-line JCL with two EXEC steps:
    - ``STEP05R`` (lines 22-37): ``EXEC PGM=SORT`` reads the DD concatenation
      of ``TRANSACT.BKUP(0)`` + ``SYSTRAN(0)`` with SYMNAMES
      ``TRAN-ID,1,16,CH`` and executes ``SORT FIELDS=(TRAN-ID,A)``,
      writing the sorted output to ``TRANSACT.COMBINED(+1)``.
    - ``STEP10`` (lines 41-49): ``EXEC PGM=IDCAMS`` performs
      ``REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)`` to bulk-load the
      combined file into the ``TRANSACT.VSAM.KSDS`` master (replacing
      existing content wholesale).

Target Module Under Test
------------------------
* ``src/batch/jobs/combtran_job.py`` — PySpark Glue job translating the
  two JCL steps into a DataFrame pipeline:

    1. ``init_glue(job_name="carddemo-combtran")`` — boots the Spark/Glue
       context and resolves runtime arguments.
    2. ``get_versioned_s3_path(gdg, generation=...)`` — resolves the three
       GDG equivalents: BKUP(0) + SYSTRAN(0) prefixes for the upstream
       ``_SUCCESS`` marker probes, and COMBINED(+1) for the archive sink.
    3. ``read_from_s3(marker_key)`` — best-effort ``_SUCCESS`` marker probe
       (replaces the mainframe's ``IEF212I``/``IEF217I`` catalog-check).
    4. ``read_table(spark, "transactions")`` — opens the consolidated
       master table via JDBC (the PostgreSQL table already contains the
       records that z/OS kept in two physical DSNs).
    5. Two ``.filter()`` projections on the ``tran_source`` column split
       the master into the two source subsets (JCL DD concatenation
       provenance).
    6. ``.union()`` merges them back — the PySpark analogue of DD
       concatenation at the SORTIN port.
    7. ``.dropDuplicates([tran_id])`` — guards against upstream retry
       patterns that could produce duplicate IDs (IDCAMS REPRO into a
       unique-keyed KSDS would abend with ``IDC3302I`` on duplicates).
    8. ``.orderBy(F.col("tran_id").asc())`` — the PySpark analogue of
       ``SORT FIELDS=(TRAN-ID,A)``.
    9. ``.write.mode("overwrite").parquet(combined_uri)`` — the S3
       archive sink (replaces the ``SORTOUT DD`` with
       ``DISP=(NEW,CATLG,DELETE)``).
    10. ``write_table(sorted_df, "transactions", mode="overwrite")`` —
        the PostgreSQL write (replaces ``REPRO`` with
        ``TRUNCATE`` + ``INSERT``).
    11. ``commit_job(job)`` — emits the Glue bookmark/completion event
        (replaces the mainframe's implicit ``MAXCC=0``).

Test Organization
-----------------
Nine test cases across four logical phases (numbering mirrors the
underlying JCL/batch stage numbering; there is no "Phase 1" in this
module — Phase 1 is owned by ``test_posttran_job.py`` for the
POSTTRAN stage):

* Phase 2 — Union (DD concatenation replacement) — 3 tests.
* Phase 3 — Sort (``SORT FIELDS=(TRAN-ID,A)`` replacement) — 3 tests.
* Phase 4 — Write (REPRO replacement) — 2 tests.
* Phase 5 — End-to-end ``main()`` integration — 1 test.

The three Union and three Sort tests exercise PySpark DataFrame operations
directly against the session-scoped :class:`pyspark.sql.SparkSession`
fixture (``spark_session``) from :mod:`tests.conftest`; they do not
invoke ``main()``. The two Write tests and the ``main`` integration test
patch the external collaborators (``init_glue``, ``commit_job``,
``read_table``, ``write_table``, ``get_versioned_s3_path``,
``read_from_s3``) from the module's import namespace so the production
orchestration flow is exercised without AWS or PostgreSQL side effects.

Key test data invariant
-----------------------
All monetary fields (``tran_amt`` — ``TRAN-AMT PIC S9(09)V99`` in
``CVTRA05Y.cpy``) use :class:`decimal.Decimal` with explicit two-decimal
scale — never ``float`` — per the AAP §0.7.2 financial precision rule.
Although COMBTRAN performs no arithmetic on these fields (it only
unions, dedups, and sorts), the values must remain Decimal-precise
through the pipeline.
"""

from __future__ import annotations

import logging
from decimal import Decimal
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pyspark.sql import Row, SparkSession

# Module under test — imported for its main() entry point as the subject
# of the Phase 5 integration test. The function is re-exported from the
# production script so Step Functions orchestration and unit tests can
# both invoke the same callable without relying on ``python -m``-style
# module execution.
from src.batch.jobs.combtran_job import main

# ----------------------------------------------------------------------------
# Test-module logger.
#
# Emits DEBUG traces when pytest is run with ``-o log_cli=true
# -o log_cli_level=DEBUG`` — invaluable during triage of flaky SparkSession
# startup or JDBC fixture misconfiguration. Silent by default so the
# successful-run output stays legible.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Patch-target constants.
#
# Patching MUST target the import location inside the module under test,
# not the source definition site. Each symbol below is imported into
# ``src.batch.jobs.combtran_job`` via one of:
#
#     from src.batch.common.glue_context import commit_job, init_glue
#     from src.batch.common.db_connector import read_table, write_table
#     from src.batch.common.s3_utils    import get_versioned_s3_path, read_from_s3
#     from pyspark.sql import functions as F
#
# Patching at the original source module (e.g.
# ``src.batch.common.glue_context.init_glue``) would NOT intercept the
# already-resolved reference inside ``combtran_job``. The constants below
# point to the correct re-exported names to guarantee patch efficacy.
#
# Note on ``write_to_s3`` (QA Checkpoint 5 Issue 23 fix)
# ------------------------------------------------------
# The original implementation wrote the S3 archive via the native
# ``DataFrame.write.mode("overwrite").parquet(uri)`` chain, which
# required the ``hadoop-aws`` jar and S3A filesystem on the Spark
# classpath. That path is unavailable in AWS Glue 5.1 managed runtime
# and in the LocalStack developer setup, so the job was migrated to
# use :func:`src.batch.common.s3_utils.write_to_s3` (boto3-backed
# ``put_object``) — matching the pattern already proven by sibling
# jobs (intcalc_job, prtcatbl_job, creastmt_job). The resulting
# 350-byte CVTRA05Y fixed-width archive is written as a single
# ``text/plain`` object at the GDG(+1)-equivalent timestamped key.
# The ``_PATCH_WRITE_TO_S3`` patch target below intercepts this call
# so tests can assert on the archive key / content / content-type
# without real AWS traffic.
#
# ``combtran_job`` does NOT import ``get_connection_options`` — JDBC
# connectivity is handled inside ``write_table`` itself. We therefore
# have no corresponding patch target for that symbol; its absence is
# deliberate and structural.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.combtran_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.combtran_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.combtran_job.read_table"
_PATCH_WRITE_TABLE: str = "src.batch.jobs.combtran_job.write_table"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.combtran_job.get_versioned_s3_path"
_PATCH_READ_FROM_S3: str = "src.batch.jobs.combtran_job.read_from_s3"
_PATCH_WRITE_TO_S3: str = "src.batch.jobs.combtran_job.write_to_s3"
_PATCH_F: str = "src.batch.jobs.combtran_job.F"


# ============================================================================
# Canonical CVTRA05Y.cpy test-record schema.
#
# ``TRAN-RECORD`` in ``app/cpy/CVTRA05Y.cpy`` is a 350-byte fixed-width
# layout. The columns reproduced below are the ones materially exercised
# by ``combtran_job.main()`` (the filter on ``tran_source``, the sort
# key ``tran_id``, and the Decimal-precision ``tran_amt``). Auxiliary
# columns (``tran_type_cd``, ``tran_cat_cd``, ``tran_card_num``) are
# included to produce a realistic multi-column DataFrame rather than a
# degenerate single-column toy — this way the ``union()`` schema-
# compatibility assertions have meaningful content to compare.
# ============================================================================
# ``TRAN-SOURCE PIC X(10)`` — discriminator column. CBACT04C emits
# ``"System"`` for interest-generated rows (``SYSTRAN``); all other
# upstream writers emit ``"POS"``, ``"Online"``, etc. (``TRANSACT.BKUP``).
# This matches ``_SYSTRAN_SOURCE_VALUE = "System"`` in the module under
# test.
_NON_SYSTEM_SOURCE: str = "POS"
_SYSTEM_SOURCE: str = "System"


def _make_txn_row(
    tran_id: str,
    *,
    tran_source: str = _NON_SYSTEM_SOURCE,
    tran_type_cd: str = "DB",
    tran_cat_cd: str = "0001",
    tran_amt: Decimal = Decimal("10.00"),
    tran_card_num: str = "4111111111111111",
    tran_desc: str = "",
    tran_merchant_id: str = "",
    tran_merchant_name: str = "",
    tran_merchant_city: str = "",
    tran_merchant_zip: str = "",
    tran_orig_ts: str = "",
    tran_proc_ts: str = "",
) -> Row:
    """Build a :class:`pyspark.sql.Row` matching the CVTRA05Y layout.

    Parameters
    ----------
    tran_id
        16-character transaction ID (``TRAN-ID PIC X(16)``). This is the
        primary key and the sort field (``SORT FIELDS=(TRAN-ID,A)``).
    tran_source
        10-character source discriminator (``TRAN-SOURCE PIC X(10)``).
        The module filters on this column: rows equal to ``"System"``
        go to the ``systran_df`` subset (SYSTRAN DD); all others go to
        the ``backup_df`` subset (TRANSACT.BKUP DD).
    tran_type_cd, tran_cat_cd
        Transaction type and category codes (not exercised by COMBTRAN
        beyond schema-compatibility; present for realism).
    tran_amt
        Transaction amount. MUST be a :class:`decimal.Decimal` to match
        ``TRAN-AMT PIC S9(09)V99`` from CVTRA05Y and the AAP §0.7.2
        financial-precision rule. COMBTRAN does not perform arithmetic
        on this column but it MUST survive the union/sort pipeline
        without precision loss.
    tran_card_num
        16-character card number (``TRAN-CARD-NUM``).
    tran_desc
        100-character transaction description (``TRAN-DESC PIC X(100)``).
        Defaults to empty string (space-padded by ``_format_combined_line``).
    tran_merchant_id
        9-character merchant ID (``TRAN-MERCHANT-ID PIC 9(09)``).
    tran_merchant_name
        50-character merchant name (``TRAN-MERCHANT-NAME PIC X(50)``).
    tran_merchant_city
        50-character merchant city (``TRAN-MERCHANT-CITY PIC X(50)``).
    tran_merchant_zip
        10-character merchant ZIP (``TRAN-MERCHANT-ZIP PIC X(10)``).
    tran_orig_ts
        26-character transaction origination timestamp
        (``TRAN-ORIG-TS PIC X(26)``).
    tran_proc_ts
        26-character transaction processing timestamp
        (``TRAN-PROC-TS PIC X(26)``).

    Returns
    -------
    Row
        A PySpark Row whose field order and types are stable across
        invocations — critical because PySpark ``union()`` is
        positional (by column ordinal), not by column name. The
        helper guarantees that every DataFrame built from its output
        shares an identical schema.

        ALL 13 CVTRA05Y columns are included so the resulting
        DataFrame can be consumed by :func:`_format_combined_line`
        (the byte-for-byte serialiser invoked by ``main()``'s
        Step 8 to build the 350-byte TRANSACT.COMBINED archive
        lines). Fields not supplied by the caller default to empty
        strings which ``_pad_right`` space-pads to the correct
        fixed width (AAP §0.7.2 minimal-change discipline —
        preserves COBOL's trailing-space behaviour on unfilled
        ``PIC X(n)`` picture clauses).
    """
    return Row(
        tran_id=tran_id,
        tran_type_cd=tran_type_cd,
        tran_cat_cd=tran_cat_cd,
        tran_source=tran_source,
        tran_desc=tran_desc,
        tran_amt=tran_amt,
        tran_merchant_id=tran_merchant_id,
        tran_merchant_name=tran_merchant_name,
        tran_merchant_city=tran_merchant_city,
        tran_merchant_zip=tran_merchant_zip,
        tran_card_num=tran_card_num,
        tran_orig_ts=tran_orig_ts,
        tran_proc_ts=tran_proc_ts,
    )


# ============================================================================
# Chainable MagicMock DataFrame helper.
#
# Several tests (Phase 4 and Phase 5 write verification) need to inspect
# the exact sequence of DataFrame-method calls issued by ``main()``. A
# plain ``MagicMock()`` would produce a fresh child mock on every chained
# call, forcing assertions to walk through ``return_value`` multiple
# times per assertion. The helper below builds a mock whose chainable
# operators all return the SAME mock — collapsing the fluent chain
# onto a single tracked instance for unambiguous ``.assert_called_*``.
#
# Chain semantics (what returns ``self`` vs something else):
#
#     df.filter(...)         → self    (both subsets)
#     df.union(...)          → self    (combined_df re-uses df tracking)
#     df.dropDuplicates(...) → self    (deduplicated_df)
#     df.orderBy(...)        → self    (sorted_df)
#     df.cache()             → self    (sorted_df.cache())
#     df.count()             → int     (drives the log emission only)
#     df.collect()           → list    (iterated by ``_format_combined_line``)
#     df.unpersist()         → None    (cleanup, return ignored)
#
# ``collect()`` defaults to an empty list so the boto3 ``write_to_s3``
# call in Step 8 of ``main()`` receives an empty ``content=""`` body —
# this keeps the mock-based tests focused on WHICH helper is called
# (``write_to_s3`` vs ``write_table``) and WITH WHAT key/content-type
# rather than on the exact bytes of the archive body. Tests that
# require realistic rows (Phase 5 integration test) override
# ``collect.return_value`` with a concrete list of Row objects.
# ============================================================================
def _make_mock_df(count_value: int = 5) -> MagicMock:
    """Return a chainable mock DataFrame for combtran_job pipeline tests.

    Parameters
    ----------
    count_value
        Integer returned by the mock DataFrame's ``count()`` method.
        The module issues three ``.count()`` calls in ``main()``:
        once on ``backup_df``, once on ``systran_df``, and once on
        ``sorted_df``. All three receive ``count_value`` when the
        same mock is returned from ``.filter()``. The default of
        5 (matching the Phase-2 "3 backup + 2 systran = 5 combined"
        scenario) produces realistic log messages.

    Returns
    -------
    MagicMock
        A mock DataFrame whose fluent-style methods collapse onto a
        single tracked instance for unambiguous assertion.
    """
    df = MagicMock(name="MockDataFrame")

    # Chainable DataFrame transformation methods — each returns the
    # same mock so the fluent-style PySpark expressions collapse onto
    # one tracked instance. This keeps ``assert_called_with(...)`` and
    # ``.call_count`` unambiguous in the test bodies below.
    df.filter.return_value = df
    df.union.return_value = df
    df.dropDuplicates.return_value = df
    df.orderBy.return_value = df
    df.cache.return_value = df

    # Terminal action methods.
    df.count.return_value = count_value
    # ``sorted_df.collect()`` is invoked by Step 8 of ``main()`` to
    # materialise the sorted DataFrame on the driver for the boto3
    # archive write. Default to an empty list so the mock-based
    # tests exercise the write path with a trivially empty body —
    # content / format assertions are covered by the Phase-5
    # integration test which provides real rows.
    df.collect.return_value = []
    df.unpersist.return_value = None

    return df


# ============================================================================
# Phase 2 — Union tests (DD concatenation replacement).
#
# The mainframe JCL performs DD concatenation at the SORTIN port:
#
#     //SORTIN   DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
#     //         DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.SYSTRAN(0)
#
# This concatenation stream is fed to DFSORT as a single logical input.
# In PySpark the equivalent is ``DataFrame.union(other)`` — a positional
# merge (by column ordinal, not by column name) that produces a new
# DataFrame whose row count equals the sum of the inputs.
#
# The three Phase-2 tests exercise this union directly using the
# session-scoped :class:`pyspark.sql.SparkSession` fixture, without
# invoking ``main()``. They validate the fundamental guarantee that no
# rows are dropped or duplicated during the DD-concatenation step.
# ============================================================================
@pytest.mark.unit
def test_union_two_sources(spark_session: SparkSession) -> None:
    """``backup_df.union(systran_df)`` produces 3 + 2 = 5 rows.

    Replicates the JCL DD-concatenation numerical invariant — the
    output record count must equal the sum of the two input record
    counts. Uses realistic transaction records matching ``CVTRA05Y.cpy``
    with ``Decimal`` monetary values (AAP §0.7.2).
    """
    # --- Arrange ---------------------------------------------------------
    # ``backup_df`` — 3 transactions representing the TRANSACT.BKUP(0)
    # feed. Sources are non-"System" values (POS, Online, API) which
    # is exactly what ``combtran_job.main()``'s filter expression
    # ``tran_source != 'System'`` admits.
    backup_rows = [
        _make_txn_row(
            "TRANBKUP0000001",
            tran_source="POS",
            tran_amt=Decimal("25.00"),
        ),
        _make_txn_row(
            "TRANBKUP0000002",
            tran_source="Online",
            tran_amt=Decimal("150.50"),
        ),
        _make_txn_row(
            "TRANBKUP0000003",
            tran_source="API",
            tran_amt=Decimal("999.99"),
        ),
    ]
    backup_df = spark_session.createDataFrame(backup_rows)

    # ``systran_df`` — 2 transactions representing the SYSTRAN(0)
    # feed. Source is always ``"System"`` (per CBACT04C paragraph
    # ``1300-B-WRITE-TX`` line 484: ``MOVE 'System' TO TRAN-SOURCE``).
    systran_rows = [
        _make_txn_row(
            "TRANSYS00000001",
            tran_source="System",
            tran_amt=Decimal("1.50"),
        ),
        _make_txn_row(
            "TRANSYS00000002",
            tran_source="System",
            tran_amt=Decimal("2.75"),
        ),
    ]
    systran_df = spark_session.createDataFrame(systran_rows)

    # --- Act -------------------------------------------------------------
    combined_df = backup_df.union(systran_df)

    # --- Assert ----------------------------------------------------------
    # Primary invariant: 3 + 2 = 5. If PySpark's union ever silently
    # dedup-ed or dropped rows the assertion would fail, catching the
    # regression before it hits production.
    assert combined_df.count() == 5, f"union() must preserve all input rows (3 + 2 = 5); got {combined_df.count()}"

    # Secondary assertion: the union result contains BOTH source
    # subsets — i.e., rows from each input are reachable after the
    # merge. We verify by collecting tran_ids and checking set
    # membership.
    combined_ids = {row["tran_id"] for row in combined_df.collect()}
    assert "TRANBKUP0000001" in combined_ids
    assert "TRANBKUP0000003" in combined_ids
    assert "TRANSYS00000001" in combined_ids
    assert "TRANSYS00000002" in combined_ids


@pytest.mark.unit
def test_union_output_count_equals_sum(spark_session: SparkSession) -> None:
    """For any N-row backup and M-row systran, output count = N + M.

    Parameterised version of ``test_union_two_sources`` with N=7 and
    M=4 to verify the count invariant holds for non-trivial arities.
    The numbers are chosen so neither input dominates (eliminating the
    degenerate case of an empty input) and so their sum (11) is
    prime (eliminating the risk of a silent even-division half-drop).
    """
    # --- Arrange ---------------------------------------------------------
    n_backup = 7
    m_systran = 4

    # Generate backup rows with distinct tran_ids so the count is
    # unambiguous. The Decimal amounts are varied to mimic a real
    # transaction stream (no two amounts identical).
    backup_rows = [
        _make_txn_row(
            f"TXB{i:013d}",
            tran_source="POS",
            tran_amt=Decimal(f"{10 * (i + 1)}.00"),
        )
        for i in range(n_backup)
    ]
    backup_df = spark_session.createDataFrame(backup_rows)

    # Generate systran rows with distinct tran_ids disjoint from
    # backup to ensure union output has exactly N + M unique rows.
    systran_rows = [
        _make_txn_row(
            f"TXS{i:013d}",
            tran_source="System",
            tran_amt=Decimal(f"0.{5 * (i + 1):02d}"),
        )
        for i in range(m_systran)
    ]
    systran_df = spark_session.createDataFrame(systran_rows)

    # --- Act -------------------------------------------------------------
    combined_df = backup_df.union(systran_df)
    combined_count = combined_df.count()

    # --- Assert ----------------------------------------------------------
    # Exact arithmetic identity: no record must be dropped nor
    # duplicated by the union operator. This test catches any future
    # regression where Spark silently deduplicates during union
    # (``unionByName(allowMissingColumns=...)``-style surprises).
    expected_total = n_backup + m_systran
    assert combined_count == expected_total, (
        f"union() row count = {combined_count}, expected {expected_total} (N={n_backup} + M={m_systran})"
    )


@pytest.mark.unit
def test_union_preserves_all_columns(spark_session: SparkSession) -> None:
    """Union output schema matches both input schemas (CVTRA05Y layout).

    PySpark ``union()`` requires positional schema compatibility — the
    column count, ordinal positions, and data types must match between
    the two inputs. This test verifies that DataFrames built from the
    canonical ``_make_txn_row()`` helper are schema-compatible and that
    the union result preserves every column of the inputs.

    Because both subsets in the production flow derive from filtering
    the same ``master_txns_df`` (itself read from the ``transactions``
    table via JDBC), their schemas are guaranteed identical upstream
    of the union — but the test guards against future refactors that
    might reshape one subset (e.g., projecting additional columns)
    without reshaping the other.
    """
    # --- Arrange ---------------------------------------------------------
    backup_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                "TRANBKUP0000100",
                tran_source="POS",
                tran_amt=Decimal("42.42"),
            ),
        ]
    )
    systran_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                "TRANSYS00000100",
                tran_source="System",
                tran_amt=Decimal("7.77"),
            ),
        ]
    )

    # --- Act -------------------------------------------------------------
    combined_df = backup_df.union(systran_df)

    # --- Assert ----------------------------------------------------------
    # The two inputs share an identical schema (produced by the same
    # helper) — verify this precondition explicitly.
    assert backup_df.schema == systran_df.schema, (
        "Precondition violated: input DataFrames have divergent schemas; "
        f"backup={backup_df.schema}, systran={systran_df.schema}"
    )

    # The union output's schema must equal the inputs' (which are
    # identical to each other). This is the positional-compatibility
    # contract — ``union()`` never widens or narrows the schema.
    assert combined_df.schema == backup_df.schema, (
        f"union() schema drift; input={backup_df.schema}, output={combined_df.schema}"
    )

    # Column names MUST be preserved in order. All 13 CVTRA05Y.cpy
    # fields are present in the ``_make_txn_row`` helper (QA
    # Checkpoint 5 Issue 23 fix expanded the helper to produce
    # rows that work with the fixed-width archive formatter); the
    # union MUST preserve the positional order that createDataFrame
    # derived from the Row constructor. If any column is dropped
    # or reordered the assertion fires with a clear delta.
    expected_columns = [
        "tran_id",
        "tran_type_cd",
        "tran_cat_cd",
        "tran_source",
        "tran_desc",
        "tran_amt",
        "tran_merchant_id",
        "tran_merchant_name",
        "tran_merchant_city",
        "tran_merchant_zip",
        "tran_card_num",
        "tran_orig_ts",
        "tran_proc_ts",
    ]
    assert combined_df.columns == expected_columns, (
        f"Column ordering changed after union; expected {expected_columns}, got {combined_df.columns}"
    )

    # Explicitly verify the Decimal column survived the union with
    # its DecimalType intact (precision/scale may be inferred, but
    # the type family MUST remain Decimal — any degradation to
    # DoubleType would silently violate AAP §0.7.2).
    tran_amt_field = next(f for f in combined_df.schema.fields if f.name == "tran_amt")
    assert "decimal" in tran_amt_field.dataType.simpleString().lower(), (
        f"tran_amt column lost Decimal precision during union; got dataType={tran_amt_field.dataType.simpleString()}"
    )


# ============================================================================
# Phase 3 — Sort tests (``SORT FIELDS=(TRAN-ID,A)`` replacement).
#
# The mainframe JCL's SYMNAMES and SYSIN DD blocks declare the sort
# contract:
#
#     //SYMNAMES DD *
#     TRAN-ID,1,16,CH           ← sort key: 16 chars, offset 1, character
#     //SYSIN    DD *
#      SORT FIELDS=(TRAN-ID,A)  ← ascending order
#
# In PySpark the equivalent is ``DataFrame.orderBy(F.col("tran_id").asc())``
# (or the ``orderBy`` convenience form on the string column name). The
# three Phase-3 tests verify the sort semantic is preserved: ascending
# order on the ``tran_id`` column, row count invariance, and the
# documented deduplication behaviour for duplicate keys (the production
# module calls ``dropDuplicates(["tran_id"])`` BEFORE the sort to guard
# against ``IDC3302I DUPLICATE RECORD`` aborts on the downstream
# unique-keyed KSDS. Because the PostgreSQL ``transactions`` table
# declares ``tran_id`` as its PRIMARY KEY (and ``write_table`` uses
# ``truncate="true"`` to preserve the PRIMARY KEY on overwrite), the
# downstream JDBC INSERT would ALSO fail on duplicate ``tran_id``
# values via a unique-constraint violation — but dropping duplicates
# at the PySpark layer keeps the error surface at a single, well-
# understood location and avoids partial-load rollbacks on the
# PostgreSQL side).
# ============================================================================
@pytest.mark.unit
def test_sort_ascending_by_tran_id(spark_session: SparkSession) -> None:
    """``orderBy(tran_id.asc())`` produces ASCII-ascending ``tran_id`` order.

    Input order:   [C0003, A0001, B0002]
    Expected:      [A0001, B0002, C0003]

    The expected ordering follows standard ASCII-ordinal comparison
    (which matches the mainframe's EBCDIC-ordinal character comparison
    for the uppercase-alphanumeric range used by transaction IDs —
    see the ``TRAN-ID,1,16,CH`` SYMNAMES declaration in the source JCL
    where ``CH`` denotes character-field comparison, not numeric).
    """
    # --- Arrange ---------------------------------------------------------
    # Three rows whose tran_ids are intentionally out-of-order to
    # exercise the sort operator. Amounts vary to confirm that non-
    # sort-key columns are carried through the sort without reorder.
    unsorted_rows = [
        _make_txn_row(
            "C0003",
            tran_source="POS",
            tran_amt=Decimal("30.00"),
        ),
        _make_txn_row(
            "A0001",
            tran_source="System",
            tran_amt=Decimal("10.00"),
        ),
        _make_txn_row(
            "B0002",
            tran_source="Online",
            tran_amt=Decimal("20.00"),
        ),
    ]
    unsorted_df = spark_session.createDataFrame(unsorted_rows)

    # --- Act -------------------------------------------------------------
    # Use the string-column form here because the production code's
    # ``F.col("tran_id").asc()`` form is already tested implicitly
    # in the Phase-5 integration test — this test instead verifies the
    # simpler idiomatic form, giving coverage of both spellings.
    sorted_df = unsorted_df.orderBy("tran_id")

    # --- Assert ----------------------------------------------------------
    # Collect to the driver for ordering inspection. The orderBy is a
    # global (driver-side) sort — the result is guaranteed to be
    # totally ordered across partitions.
    sorted_ids = [row["tran_id"] for row in sorted_df.collect()]
    assert sorted_ids == ["A0001", "B0002", "C0003"], (
        f"Sort order drift; expected [A0001, B0002, C0003], got {sorted_ids}"
    )

    # Non-sort-key columns must travel with their rows through the
    # sort — the row keyed on A0001 MUST still carry tran_amt=10.00,
    # source=System, etc. If row-tuple integrity were lost the
    # assertion below would fire.
    first_row = sorted_df.collect()[0]
    assert first_row["tran_id"] == "A0001"
    assert first_row["tran_source"] == "System"
    assert first_row["tran_amt"] == Decimal("10.00")


@pytest.mark.unit
def test_sort_preserves_record_count(spark_session: SparkSession) -> None:
    """``orderBy`` is a pure reordering — input and output row counts match.

    Spark's ``orderBy`` is a transformation, not an aggregation — no
    rows are added or removed. This test guards against any future
    optimiser regression where the planner might fold a sort into a
    window or aggregation and silently drop rows.
    """
    # --- Arrange ---------------------------------------------------------
    # Ten rows is enough to exercise the sort across more than one
    # task under ``local[1]`` shuffle (even with the single-partition
    # config) while staying quick for unit-test turnaround.
    input_count = 10
    rows = [
        _make_txn_row(
            f"TXN{i:013d}",
            # Alternate source values to match the real pipeline's
            # mix — this also prevents the sort from being a no-op
            # on a single-source stream.
            tran_source="System" if i % 2 == 0 else "POS",
            tran_amt=Decimal(f"{i + 1}.00"),
        )
        for i in range(input_count)
    ]
    input_df = spark_session.createDataFrame(rows)

    # Precondition sanity check — the input df has the row count we
    # supplied.
    assert input_df.count() == input_count

    # --- Act -------------------------------------------------------------
    sorted_df = input_df.orderBy("tran_id")

    # --- Assert ----------------------------------------------------------
    # The primary invariant: sort preserves row count.
    assert sorted_df.count() == input_count, (
        f"Sort dropped or added rows; input={input_count}, output={sorted_df.count()}"
    )

    # Collect both dataframes and verify the multiset of tran_ids is
    # identical — not just the count but the actual membership. This
    # catches any silent row-replacement regressions that a simple
    # count comparison would miss.
    input_ids = sorted(row["tran_id"] for row in input_df.collect())
    output_ids = sorted(row["tran_id"] for row in sorted_df.collect())
    assert input_ids == output_ids, f"Sort mutated row membership; input={input_ids}, output={output_ids}"


@pytest.mark.unit
def test_sort_handles_duplicate_tran_ids(spark_session: SparkSession) -> None:
    """``dropDuplicates(["tran_id"]).orderBy(tran_id)`` matches production.

    The production module issues
    ``combined_df.dropDuplicates([_SORT_COLUMN]).orderBy(F.col(_SORT_COLUMN).asc())``
    to guard against the upstream rerun / partial-failure edge case
    where the unioned stream contains duplicate transaction IDs. On a
    real mainframe this would cause IDCAMS REPRO to abend with
    ``IDC3302I DUPLICATE RECORD`` when loading the unique-keyed KSDS;
    in the PySpark flow the deduplication is an explicit pre-sort
    step.

    This test verifies the combined behaviour: input with duplicate
    tran_ids is deduplicated THEN sorted, producing a unique,
    ascending-ordered result.
    """
    # --- Arrange ---------------------------------------------------------
    # Five input rows — three of which share ``tran_id="A0001"``.
    # The duplicates carry DIFFERENT non-key fields (different
    # sources, different amounts) so we can verify which duplicate
    # ``dropDuplicates`` selects. Spark's dropDuplicates is
    # non-deterministic for non-key columns under the default
    # behaviour, so the test only asserts on the key column —
    # which is the only guarantee the JCL sort relies on.
    rows_with_duplicates = [
        _make_txn_row(
            "A0001",
            tran_source="POS",
            tran_amt=Decimal("10.00"),
        ),
        _make_txn_row(
            "A0001",  # duplicate
            tran_source="Online",
            tran_amt=Decimal("99.99"),
        ),
        _make_txn_row(
            "C0003",
            tran_source="System",
            tran_amt=Decimal("30.00"),
        ),
        _make_txn_row(
            "A0001",  # duplicate
            tran_source="API",
            tran_amt=Decimal("1.00"),
        ),
        _make_txn_row(
            "B0002",
            tran_source="POS",
            tran_amt=Decimal("20.00"),
        ),
    ]
    input_df = spark_session.createDataFrame(rows_with_duplicates)

    # Precondition sanity — the raw input contains 5 rows and
    # 3 distinct tran_ids.
    assert input_df.count() == 5
    distinct_input_ids = {row["tran_id"] for row in input_df.collect()}
    assert distinct_input_ids == {"A0001", "B0002", "C0003"}

    # --- Act -------------------------------------------------------------
    # Replicate the production pipeline's dedup-then-sort sequence:
    #   combined_df = backup_df.union(systran_df)           — omitted (already combined)
    #   deduplicated_df = combined_df.dropDuplicates(["tran_id"])
    #   sorted_df = deduplicated_df.orderBy(F.col("tran_id").asc())
    deduplicated_df = input_df.dropDuplicates(["tran_id"])
    sorted_df = deduplicated_df.orderBy("tran_id")

    # --- Assert ----------------------------------------------------------
    # Exactly 3 unique tran_ids must remain after deduplication — the
    # two duplicate A0001 rows collapse into one.
    assert sorted_df.count() == 3, (
        f"dropDuplicates on tran_id did not collapse duplicates; expected 3 unique rows, got {sorted_df.count()}"
    )

    # The surviving tran_ids, sorted ascending, must be exactly
    # [A0001, B0002, C0003]. Any row ordering drift (e.g., if the
    # sort were accidentally descending) would fire this assertion.
    sorted_ids = [row["tran_id"] for row in sorted_df.collect()]
    assert sorted_ids == ["A0001", "B0002", "C0003"], (
        f"dedup+sort output drift; expected [A0001, B0002, C0003], got {sorted_ids}"
    )

    # Exactly ONE row should carry ``tran_id == "A0001"`` — the
    # dedup collapsed the three duplicate inputs into a single
    # survivor. Which duplicate survives is implementation-defined
    # (Spark does not guarantee stability for non-key columns on
    # dropDuplicates), so we only assert on the count, not on
    # which amount or source is retained.
    a0001_rows = [row for row in sorted_df.collect() if row["tran_id"] == "A0001"]
    assert len(a0001_rows) == 1, f"Exactly one row should carry tran_id='A0001' after dedup; got {len(a0001_rows)}"


# ============================================================================
# Phase 4 — Write tests (``REPRO`` replacement).
#
# The mainframe's STEP10 performs two operations in sequence:
#
#   1. STEP05R writes the sorted combined stream to a NEW GDG
#      generation at ``DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)``
#      — an archive snapshot with ``DISP=(NEW,CATLG,DELETE)`` so the
#      catalog retains the generation for downstream audit.
#   2. STEP10 then bulk-loads that archive into the master KSDS via
#      ``IDCAMS REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)`` — the
#      ``TRANSACT.VSAM.KSDS`` is replaced wholesale with the sorted
#      combined content.
#
# In the target module these become:
#
#   1. ``write_to_s3(content=<350-byte CVTRA05Y lines>,
#                    key=<prefix>/TRANSACT.COMBINED.txt,
#                    content_type="text/plain")`` — the boto3
#      ``put_object`` archive write to S3 at the GDG-equivalent
#      timestamp path. Replaces the original
#      ``sorted_df.write.mode("overwrite").parquet(uri)`` chain which
#      required the ``hadoop-aws`` jar and S3A filesystem that are
#      unavailable in AWS Glue 5.1 managed runtime and LocalStack
#      developer setup (QA Checkpoint 5 Issue 23 fix).
#   2. ``write_table(sorted_df, _TABLE_NAME, mode="overwrite")`` —
#      PostgreSQL overwrite on the ``transactions`` table. NOTE on
#      JDBC semantics: Spark's JDBC writer's default behavior for
#      ``mode="overwrite"`` is ``DROP TABLE`` + ``CREATE TABLE`` +
#      ``INSERT`` — which would destroy PRIMARY KEY constraints,
#      indexes, foreign keys, and the ``version_id`` optimistic-
#      concurrency column defined in ``db/migrations/V1__schema.sql``.
#      To preserve the schema, ``src.batch.common.db_connector.
#      write_table`` explicitly sets the JDBC option
#      ``truncate="true"`` which changes Spark's overwrite
#      implementation to ``TRUNCATE TABLE`` + ``INSERT`` (preserving
#      all DDL). The ``TRUNCATE`` + ``INSERT`` behavior is what
#      matches the mainframe REPRO's "replace all content" semantic
#      on a KSDS — and what Step Functions / downstream stages
#      (POSTTRAN, INTCALC, CREASTMT, TRANREPT, API layer) rely on.
#
# The two Phase-4 tests verify both writes are correctly invoked by
# ``main()``. They use ``_make_mock_df`` to replace the real Spark
# DataFrame with a chainable mock so the ``write_to_s3(...)`` and
# ``write_table(df, table, mode="overwrite")`` invocations can be
# asserted cleanly without AWS or PostgreSQL side effects.
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_F)
@patch(_PATCH_READ_FROM_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_write_to_s3_combined_archive(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_read_from_s3: MagicMock,
    mock_f: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """``main()`` archives the sorted combined DF to S3 via ``write_to_s3``.

    Verifies that ``main()`` invokes
    ``write_to_s3(content=..., key=<prefix>/TRANSACT.COMBINED.txt,
    content_type="text/plain")`` where ``<prefix>`` is derived from
    ``get_versioned_s3_path("TRANSACT.COMBINED", generation="+1")``.
    This is the boto3-backed analogue of the mainframe SORTOUT DD
    block in ``COMBTRAN.jcl`` lines 33-37:

        //SORTOUT  DD DISP=(NEW,CATLG,DELETE),
        //         UNIT=SYSDA,
        //         DCB=(*.SORTIN),
        //         SPACE=(CYL,(1,1),RLSE),
        //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)

    Prior to the QA Checkpoint 5 Issue 23 fix, the archive was written
    via the native ``sorted_df.write.mode("overwrite").parquet(uri)``
    chain, which required the ``hadoop-aws`` jar + S3A filesystem
    configuration on the Spark classpath. That path is unavailable in
    AWS Glue 5.1 managed runtime and LocalStack developer setups,
    producing ``UnsupportedFileSystemException: No FileSystem for
    scheme "s3"``. The resolution migrated Step 8 to the
    ``write_to_s3`` boto3 helper pattern proven by sibling jobs
    (intcalc_job, prtcatbl_job, creastmt_job) — writing a single
    350-byte CVTRA05Y fixed-width text object with ``content_type=
    "text/plain"``.

    The ``spark_session`` parameter satisfies the AAP agent-prompt
    contract (all 9 tests receive ``spark_session``) but the test
    body uses a mock DataFrame internally — real Spark is not needed
    because the write target is inspected on the mock and no actual
    DataFrame execution occurs.
    """
    # --- Arrange ---------------------------------------------------------
    # Mock ``init_glue`` to return the canonical 4-tuple shape:
    # (SparkSession, GlueContext, Job, resolved_args). We thread the
    # session-scoped fixture through as the Spark placeholder so the
    # test signature's use of ``spark_session`` is a real contract
    # rather than a stylistic formality — even though the real
    # session is not exercised beyond identity comparisons inside
    # the module.
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="MockGlueContext"),
        mock_job,
        {"JOB_NAME": "carddemo-combtran"},
    )

    # Mock ``read_table`` to return a chainable DataFrame so every
    # ``.filter()``, ``.union()``, ``.dropDuplicates()``, ``.orderBy()``,
    # ``.cache()``, ``.count()``, ``.collect()``, and ``.unpersist()``
    # call in ``main()`` routes through one trackable mock.
    # ``count_value=5`` mimics the Phase-2 "3 + 2 = 5" scenario.
    # The default ``collect() -> []`` behaviour keeps the archive
    # body empty (trivial content) — content/format assertions are
    # covered by the Phase-5 integration test which supplies real rows.
    mock_df = _make_mock_df(count_value=5)
    mock_read_table.return_value = mock_df

    # Resolve the three GDG paths. The first two (BKUP, SYSTRAN) are
    # consumed only by ``_probe_upstream_marker`` — the third
    # (COMBINED) is the archive target that we'll assert against.
    # The COMBINED URI MUST end with a trailing slash so the
    # ``split("/", 3)`` in ``main()`` Step 8 correctly decomposes it
    # into ``["s3:", "", "{bucket}", "{key_prefix}/"]``.
    backup_prefix = "s3://test-bucket/backups/transactions/"
    systran_prefix = "s3://test-bucket/generated/system-transactions/"
    combined_output_uri = "s3://test-bucket/combined/transactions/2024/01/01/000000/"

    def _s3_path_side_effect(gdg_name: str, generation: str = "+1", **_kwargs: Any) -> str:
        return {
            ("TRANSACT.BKUP", "0"): backup_prefix,
            ("SYSTRAN", "0"): systran_prefix,
            ("TRANSACT.COMBINED", "+1"): combined_output_uri,
        }[(gdg_name, generation)]

    mock_get_s3_path.side_effect = _s3_path_side_effect

    # Upstream _SUCCESS marker probe returns empty bytes (marker
    # present) so the warning branch of ``_probe_upstream_marker``
    # is not taken. ``read_from_s3`` returns bytes in the real API.
    mock_read_from_s3.return_value = b""

    # F.col / F.lit are invoked inside main() to build the filter
    # predicates. Because the DataFrame itself is a MagicMock whose
    # .filter() returns self regardless of the predicate, the exact
    # expression value is irrelevant — F is patched only so
    # ``F.col`` and ``F.lit`` don't require a real SparkSession to
    # construct Column objects. MagicMock's auto-child handles this
    # automatically; we just need the symbol replaced.
    _ = mock_f  # asserted indirectly below

    # Mock ``write_to_s3`` to return a plausible s3:// URI so the
    # logger.info in main() can format it without error. The concrete
    # URI value is only used for log emission — the assertions below
    # focus on the call args / kwargs.
    mock_write_to_s3.return_value = "s3://test-bucket/combined/transactions/2024/01/01/000000/TRANSACT.COMBINED.txt"

    # --- Act -------------------------------------------------------------
    # Invoke the production entry point. The entire pipeline runs
    # against the mocked read_table + chainable DataFrame.
    main()

    # --- Assert ----------------------------------------------------------
    # (a) ``get_versioned_s3_path`` was invoked for the COMBINED GDG
    # with ``generation="+1"`` — the equivalent of the JCL SORTOUT
    # DSN specification ``DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)``.
    # The call tracker records all three get_versioned_s3_path
    # invocations; we use a positive-match assertion for COMBINED.
    combined_calls = [
        call
        for call in mock_get_s3_path.call_args_list
        if call.args[0] == "TRANSACT.COMBINED" or call.kwargs.get("gdg_name") == "TRANSACT.COMBINED"
    ]
    assert len(combined_calls) == 1, (
        f"Expected exactly 1 get_versioned_s3_path call for TRANSACT.COMBINED; "
        f"got {len(combined_calls)} across {mock_get_s3_path.call_args_list}"
    )
    # The generation argument is ``+1`` per the JCL SORTOUT DSN.
    # Accept either positional or keyword form for flexibility.
    call = combined_calls[0]
    generation = call.kwargs.get("generation") or (call.args[1] if len(call.args) > 1 else None)
    assert generation == "+1", f"COMBINED GDG resolved with generation={generation!r}, expected '+1'"

    # (b) ``write_to_s3`` was invoked exactly once — the single boto3
    # ``put_object`` archive write at the GDG(+1) timestamp prefix.
    # Multiple invocations would indicate an accidental double-write
    # or a refactor that split the archive into multiple objects.
    mock_write_to_s3.assert_called_once()

    # (c) Inspect the call kwargs to verify the key, content_type,
    # and content arguments. ``main()`` uses keyword arguments
    # exclusively for the ``write_to_s3`` invocation (per the
    # ``write_to_s3(content=..., key=..., content_type=...)``
    # signature visible in the source module).
    write_call = mock_write_to_s3.call_args
    kwargs = write_call.kwargs

    # (c.1) The ``content_type`` MUST be ``"text/plain"`` — the
    # archive is a CVTRA05Y fixed-width plain-text file, NOT a
    # binary Parquet file (which was the pre-Issue-23 behaviour).
    # A regression to ``"application/octet-stream"`` or
    # ``"application/vnd.apache.parquet"`` would indicate the boto3
    # migration was partially undone.
    assert kwargs.get("content_type") == "text/plain", (
        f"write_to_s3 called with content_type={kwargs.get('content_type')!r}, "
        f"expected 'text/plain' (CVTRA05Y archive is plain-text)"
    )

    # (c.2) The ``key`` MUST end with the archive filename
    # ``TRANSACT.COMBINED.txt`` and MUST start with the prefix
    # derived from the COMBINED GDG URI. ``main()`` Step 8 splits
    # the URI ``s3://{bucket}/{prefix}/`` with ``maxsplit=3`` to
    # extract ``{prefix}/`` then appends ``_COMBINED_FILENAME``.
    expected_key = "combined/transactions/2024/01/01/000000/TRANSACT.COMBINED.txt"
    assert kwargs.get("key") == expected_key, (
        f"write_to_s3 called with key={kwargs.get('key')!r}, "
        f"expected {expected_key!r} — the key MUST be derived "
        f"from the COMBINED GDG URI + _COMBINED_FILENAME"
    )

    # (c.3) The ``content`` MUST be a string (the ``write_to_s3``
    # signature is ``content: str``) — not bytes and not a file
    # handle. For this mock-based test the default empty-collect
    # path produces an empty string body; Phase-5 covers the
    # non-empty format assertions.
    content = kwargs.get("content")
    assert isinstance(content, str), (
        f"write_to_s3 called with content of type {type(content).__name__}, "
        f"expected str — the CVTRA05Y archive is built via "
        f"'\\n'.join(lines) which produces a string"
    )


@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_F)
@patch(_PATCH_READ_FROM_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_write_to_postgres_overwrite_mode(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_read_from_s3: MagicMock,
    mock_f: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """``main()`` writes the sorted combined DF to ``transactions`` with overwrite.

    Verifies that
    ``write_table(sorted_df, "transactions", mode="overwrite")``
    is invoked by ``main()`` — the PySpark analogue of the mainframe
    IDCAMS REPRO step in ``COMBTRAN.jcl`` lines 41-49:

        //STEP10 EXEC PGM=IDCAMS
        //TRANSACT DD DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
        //TRANVSAM DD DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
        //SYSIN    DD *
           REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)

    where ``REPRO`` on a unique-keyed KSDS replaces the cluster's
    contents wholesale. The Spark JDBC connector's default
    ``mode="overwrite"`` behavior would execute ``DROP TABLE`` +
    ``CREATE TABLE`` + ``INSERT`` — which would destroy the
    PRIMARY KEY, indexes, and ``version_id`` optimistic-concurrency
    column declared in ``db/migrations/V1__schema.sql``. To preserve
    the schema, :func:`src.batch.common.db_connector.write_table`
    sets the JDBC option ``truncate="true"``, which changes Spark's
    overwrite implementation to ``TRUNCATE`` + ``INSERT`` — matching
    the mainframe REPRO semantic exactly AND leaving all DDL
    (constraints, indexes, version_id default) intact so that
    downstream stages and the API layer continue to function.
    """
    # --- Arrange ---------------------------------------------------------
    # Same mock topology as the S3-archive test — the two phases
    # share a ``main()`` invocation target and differ only in which
    # write path is asserted.
    mock_job = MagicMock(name="MockGlueJob")
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="MockGlueContext"),
        mock_job,
        {"JOB_NAME": "carddemo-combtran"},
    )

    # The same chainable mock DataFrame pattern — read_table returns
    # a mock whose fluent chain collapses onto one tracked instance.
    # ``count_value=7`` is just a realistic non-zero integer so the
    # log messages (``combined %d records``) have non-placeholder
    # values.
    mock_df = _make_mock_df(count_value=7)
    mock_read_table.return_value = mock_df

    mock_get_s3_path.side_effect = lambda gdg_name, generation="+1", **_kw: {
        ("TRANSACT.BKUP", "0"): "s3://test-bucket/backups/transactions/",
        ("SYSTRAN", "0"): "s3://test-bucket/generated/system-transactions/",
        ("TRANSACT.COMBINED", "+1"): ("s3://test-bucket/combined/transactions/2024/01/01/000000/"),
    }[(gdg_name, generation)]

    mock_read_from_s3.return_value = b""
    _ = mock_f  # F.col / F.lit stubs — chain-through only

    # ``write_to_s3`` is patched so the boto3 archive write doesn't
    # hit real AWS credentials. The concrete URI return value is
    # only used for log emission; this test focuses exclusively on
    # the ``write_table`` JDBC write assertions.
    mock_write_to_s3.return_value = "s3://test-bucket/combined/transactions/2024/01/01/000000/TRANSACT.COMBINED.txt"

    # --- Act -------------------------------------------------------------
    main()

    # --- Assert ----------------------------------------------------------
    # (a) ``write_table`` was invoked exactly once — the module
    # writes the master table in a single overwrite transaction.
    # Multiple invocations would indicate an accidental double-write
    # or a refactor that split the write into multiple steps without
    # transactional atomicity.
    assert mock_write_table.call_count == 1, (
        f"Expected exactly 1 write_table() invocation; "
        f"got {mock_write_table.call_count} — "
        f"calls={mock_write_table.call_args_list}"
    )

    # (b) The write targeted the ``transactions`` table (matching
    # ``_TABLE_NAME`` constant in the module under test, which is
    # itself derived from the mainframe's TRANVSAM file-control
    # declaration). The call_args tuple is (positional, keyword) —
    # extract the second positional argument (table name).
    write_call = mock_write_table.call_args
    args = write_call.args
    kwargs = write_call.kwargs
    assert len(args) >= 2, f"write_table invoked with fewer than 2 positional args; args={args}, kwargs={kwargs}"

    # The first positional is the DataFrame (our mock), the second
    # is the table name.
    written_df = args[0]
    table_name = args[1]

    assert written_df is mock_df, (
        "write_table received a different DataFrame than the one "
        "read/sorted by main() — this suggests the sort chain was "
        "short-circuited or the wrong reference was passed"
    )

    assert table_name == "transactions", (
        f"write_table targeted the wrong table; expected 'transactions', got {table_name!r}"
    )

    # (c) The mode MUST be ``"overwrite"`` — this is the REPRO
    # semantic (replace-all). An ``"append"`` regression would
    # silently duplicate records on every rerun of the job,
    # violating the mainframe's replacement invariant. Note that
    # ``src.batch.common.db_connector.write_table`` sets the JDBC
    # option ``truncate="true"`` whenever ``mode="overwrite"`` so
    # the actual SQL behavior is ``TRUNCATE`` + ``INSERT`` (NOT
    # ``DROP`` + ``CREATE``) — this preserves the PRIMARY KEY,
    # indexes, and ``version_id`` optimistic-concurrency column
    # on the ``transactions`` table.
    mode = kwargs.get("mode")
    assert mode == "overwrite", (
        f"write_table called with mode={mode!r}, expected 'overwrite' "
        f"(IDCAMS REPRO replaces VSAM content wholesale — append "
        f"would accumulate records across reruns)"
    )


# ============================================================================
# Phase 5 — ``main()`` end-to-end integration test.
#
# Exercises the full ``combtran_job.main()`` pipeline against a REAL
# SparkSession using real in-memory DataFrames. All external
# collaborators (Glue bootstrap, JDBC read/write, S3 path resolution
# and marker probe, boto3 S3 write helper) are patched so the test
# runs without AWS or PostgreSQL dependencies — but the Spark
# computation itself is the real thing.
#
# What is verified end-to-end:
#   * Two data subsets are read (the master table is filtered into
#     ``backup_df`` + ``systran_df`` by the ``tran_source`` predicate).
#   * Union merges the two subsets (N + M rows).
#   * Sort applied ascending on ``tran_id`` (ASCII ascending order).
#   * CVTRA05Y 350-byte fixed-width archive written to S3 via the
#     ``write_to_s3`` boto3 helper at the mocked COMBINED URI.
#   * ``write_table(sorted_df, "transactions", mode="overwrite")``
#     invoked for the PostgreSQL sink.
#   * ``commit_job(job)`` invoked at the end of the happy path.
#
# Prior to the QA Checkpoint 5 Issue 23 fix, the S3 write called
# ``sorted_df.write.mode("overwrite").parquet(uri)`` natively, which
# required ``hadoop-aws`` + S3A filesystem on the Spark classpath —
# unavailable in AWS Glue 5.1 managed runtime and LocalStack dev
# setup (``UnsupportedFileSystemException: No FileSystem for scheme
# "s3"``). The migration routes the archive through
# :func:`src.batch.common.s3_utils.write_to_s3` (boto3-backed
# ``put_object``), producing a single 350-byte fixed-width text
# object at ``<prefix>/TRANSACT.COMBINED.txt``. This test captures
# the boto3 call via ``side_effect`` and validates the archive
# content matches CVTRA05Y.cpy layout — a stronger check than the
# Parquet schema probe of the pre-Issue-23 version.
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_READ_FROM_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_combtran_main_with_spark(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_read_from_s3: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """End-to-end ``main()`` execution with a real SparkSession.

    Constructs a real :class:`pyspark.sql.DataFrame` matching the
    CVTRA05Y.cpy 350-byte layout, containing 3 non-``System`` rows
    (the BKUP subset) and 2 ``System`` rows (the SYSTRAN subset).
    Runs ``main()`` against this data with all external
    collaborators patched, then asserts:

        * ``read_table`` was called for the ``transactions`` table.
        * The S3 archive was written via ``write_to_s3`` with
          ``content_type="text/plain"`` and a key ending in
          ``TRANSACT.COMBINED.txt``.
        * The archive content contains 5 fixed-width 350-byte
          CVTRA05Y lines sorted ascending by ``tran_id``.
        * ``write_table`` was called with the sorted DataFrame,
          ``"transactions"``, and ``mode="overwrite"``.
        * The DataFrame written contains 5 rows (3 + 2 = 5).
        * The DataFrame written is sorted ascending by ``tran_id``.
        * Decimal precision (CVTRA05Y.cpy monetary fields) preserved
          through the pipeline.
        * ``commit_job`` was invoked.
    """
    # ----- Arrange: real Spark session from conftest.py -----
    # init_glue returns the 4-tuple shape expected by main(): the
    # real spark_session is threaded through so the DataFrame
    # computations use the same session as the test fixtures below.
    mock_job = MagicMock(name="RealGlueJob")
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="RealGlueContext"),
        mock_job,
        {"JOB_NAME": "carddemo-combtran"},
    )

    # Build a real master DataFrame matching CVTRA05Y.cpy. Three
    # rows have ``tran_source != "System"`` (BKUP subset) and two
    # have ``tran_source == "System"`` (SYSTRAN subset). The input
    # is deliberately unsorted so the sort step's effect is visible
    # in the output.
    #
    # The tran_id values are left as 15-character strings (one less
    # than CVTRA05Y's 16-byte PIC X(16)) so that ``_pad_right`` in
    # ``_format_combined_line`` pads them with a single trailing
    # space — demonstrating the fixed-width behaviour while keeping
    # IDs human-readable in test assertions.
    master_rows = [
        # Non-System rows (BKUP subset) — 3 rows.
        _make_txn_row(
            "TXNBKUP00000003",
            tran_source="POS",
            tran_amt=Decimal("300.00"),
            tran_card_num="4111111111111113",
        ),
        _make_txn_row(
            "TXNBKUP00000001",
            tran_source="Online",
            tran_amt=Decimal("100.00"),
            tran_card_num="4111111111111111",
        ),
        _make_txn_row(
            "TXNBKUP00000002",
            tran_source="API",
            tran_amt=Decimal("200.00"),
            tran_card_num="4111111111111112",
        ),
        # System rows (SYSTRAN subset) — 2 rows.
        _make_txn_row(
            "TXNSYS0000000002",
            tran_source="System",
            tran_amt=Decimal("0.50"),
            tran_card_num="4111111111111115",
        ),
        _make_txn_row(
            "TXNSYS0000000001",
            tran_source="System",
            tran_amt=Decimal("0.25"),
            tran_card_num="4111111111111114",
        ),
    ]
    master_df = spark_session.createDataFrame(master_rows)

    # ``read_table(spark, "transactions")`` is called once by main()
    # to fetch the consolidated master. Return our real DataFrame
    # so the subsequent filter/union/sort/write operations run
    # against real Spark semantics (and real Decimal precision).
    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        assert table_name == "transactions", f"main() should only read the 'transactions' table; got {table_name!r}"
        return master_df

    mock_read_table.side_effect = _read_side_effect

    # The COMBINED URI is a pure s3:// URI (with trailing slash so
    # ``.split("/", 3)`` in main() Step 8 decomposes it as
    # ``["s3:", "", "test-bucket", "combined/transactions/.../"]``).
    # No local filesystem is involved — the archive goes through
    # ``write_to_s3`` which is patched below to capture content.
    combined_output_uri = "s3://test-bucket/combined/transactions/2024/01/01/000000/"
    expected_archive_key = "combined/transactions/2024/01/01/000000/TRANSACT.COMBINED.txt"

    def _get_s3_path_side_effect(gdg_name: str, generation: str = "+1", **_kwargs: Any) -> str:
        if gdg_name == "TRANSACT.COMBINED" and generation == "+1":
            return combined_output_uri
        if gdg_name == "TRANSACT.BKUP" and generation == "0":
            return "s3://test-bucket/backups/transactions/"
        if gdg_name == "SYSTRAN" and generation == "0":
            return "s3://test-bucket/generated/system-transactions/"
        raise AssertionError(f"Unexpected get_versioned_s3_path call: gdg_name={gdg_name!r}, generation={generation!r}")

    mock_get_s3_path.side_effect = _get_s3_path_side_effect

    # Upstream _SUCCESS marker probes — return empty bytes to signal
    # the marker is present. The probe return value is logged but
    # does not affect control flow.
    mock_read_from_s3.return_value = b""

    # Capture the ``write_to_s3`` invocation so we can inspect the
    # CVTRA05Y fixed-width content that main() builds. The side
    # effect records the full kwargs dict so every argument
    # (content, key, content_type, and any future extension) is
    # available to assertions.
    captured_writes: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        *,
        content: str,
        key: str,
        content_type: str = "text/plain",
        **_extra: Any,
    ) -> str:
        captured_writes.append(
            {
                "content": content,
                "key": key,
                "content_type": content_type,
            }
        )
        # Return a plausible s3:// URI so main()'s logger.info
        # "wrote combined archive to %s" formats without error.
        return f"s3://test-bucket/{key}"

    mock_write_to_s3.side_effect = _write_to_s3_side_effect

    # Capture the DataFrame passed to write_table so we can inspect
    # its contents after main() completes. This is the canonical
    # pattern for asserting on Spark outputs without writing to a
    # real database.
    written_dataframes: dict[str, Any] = {}

    def _write_side_effect(df_arg: Any, table_name: str, **kwargs: Any) -> None:
        # Materialize the DataFrame immediately (collect rows) so
        # that the assertions below run against stable data rather
        # than against a lazy reference that may be invalidated by
        # the main()-scope ``unpersist()``.
        written_dataframes[table_name] = {
            "rows": df_arg.collect(),
            "schema": df_arg.schema,
            "mode": kwargs.get("mode"),
        }

    mock_write_table.side_effect = _write_side_effect

    # ----- Act -----
    # Invoke main(). The happy path completes without raising.
    main()

    # ----- Assert -----
    # (a) ``commit_job`` was invoked exactly once — the job-success
    # signal matching the mainframe's implicit MAXCC=0.
    mock_commit_job.assert_called_once()
    commit_args = mock_commit_job.call_args.args
    assert commit_args[0] is mock_job, "commit_job should have been called with the Glue Job returned from init_glue()"

    # (b) read_table was called exactly once for the 'transactions'
    # table. The module reads the consolidated master then splits
    # via filter — no additional read_table calls should fire.
    assert mock_read_table.call_count == 1, (
        f"Expected exactly 1 read_table() invocation; got {mock_read_table.call_count}"
    )

    # (c) Three get_versioned_s3_path calls: BKUP(0), SYSTRAN(0),
    # COMBINED(+1). Order matches the source module's call sequence.
    assert mock_get_s3_path.call_count == 3, (
        f"Expected exactly 3 get_versioned_s3_path() invocations "
        f"(BKUP, SYSTRAN, COMBINED); got {mock_get_s3_path.call_count}"
    )

    # (d) write_table was invoked exactly once — the single REPRO
    # replacement transaction.
    assert mock_write_table.call_count == 1, (
        f"Expected exactly 1 write_table() invocation; got {mock_write_table.call_count}"
    )

    # (e) The write target was ``transactions``, the mode was
    # ``overwrite``, and the DataFrame contained the combined
    # sorted content.
    assert "transactions" in written_dataframes, (
        f"Expected write_table to target 'transactions' table; targeted={list(written_dataframes.keys())}"
    )
    posted = written_dataframes["transactions"]
    assert posted["mode"] == "overwrite", f"write_table mode={posted['mode']!r}, expected 'overwrite'"

    # (f) The DataFrame written contains all 5 input rows (3 backup
    # + 2 systran — no duplicates so dropDuplicates is a no-op).
    posted_rows = posted["rows"]
    assert len(posted_rows) == 5, (
        f"Expected 5 posted rows (3 backup + 2 systran); "
        f"got {len(posted_rows)} — rows={[r['tran_id'] for r in posted_rows]}"
    )

    # (g) The posted rows are sorted ASCENDING by tran_id — the
    # fundamental invariant of the COMBTRAN step, matching
    # ``SORT FIELDS=(TRAN-ID,A)``.
    posted_ids = [row["tran_id"] for row in posted_rows]
    assert posted_ids == sorted(posted_ids), f"Posted rows are not sorted ascending by tran_id; got {posted_ids}"

    # And the exact expected order (after sorting the 5 input IDs):
    expected_sorted_ids = [
        "TXNBKUP00000001",
        "TXNBKUP00000002",
        "TXNBKUP00000003",
        "TXNSYS0000000001",
        "TXNSYS0000000002",
    ]
    assert posted_ids == expected_sorted_ids, (
        f"Posted rows sort drift; expected {expected_sorted_ids}, got {posted_ids}"
    )

    # (h) The posted DataFrame's schema preserves all CVTRA05Y
    # columns (no projection happened during union/sort/dedup).
    # All 13 CVTRA05Y fields must be present — the sort/union/
    # dropDuplicates sequence MUST NOT project away any columns.
    posted_field_names = {f.name for f in posted["schema"].fields}
    expected_columns = {
        "tran_id",
        "tran_source",
        "tran_type_cd",
        "tran_cat_cd",
        "tran_desc",
        "tran_amt",
        "tran_merchant_id",
        "tran_merchant_name",
        "tran_merchant_city",
        "tran_merchant_zip",
        "tran_card_num",
        "tran_orig_ts",
        "tran_proc_ts",
    }
    assert posted_field_names == expected_columns, (
        f"Posted DataFrame schema drift; expected {expected_columns}, got {posted_field_names}"
    )

    # (i) ``tran_amt`` column preserved its Decimal precision —
    # AAP §0.7.2 financial precision rule. Any degradation to
    # DoubleType would silently violate the contract.
    tran_amt_field = next(f for f in posted["schema"].fields if f.name == "tran_amt")
    assert "decimal" in tran_amt_field.dataType.simpleString().lower(), (
        f"tran_amt lost Decimal precision; got dataType={tran_amt_field.dataType.simpleString()}"
    )

    # (j) The individual Decimal values are preserved exactly (no
    # rounding drift). Verify by matching IDs to known amounts.
    amt_by_id = {row["tran_id"]: row["tran_amt"] for row in posted_rows}
    assert amt_by_id["TXNBKUP00000001"] == Decimal("100.00")
    assert amt_by_id["TXNBKUP00000002"] == Decimal("200.00")
    assert amt_by_id["TXNBKUP00000003"] == Decimal("300.00")
    assert amt_by_id["TXNSYS0000000001"] == Decimal("0.25")
    assert amt_by_id["TXNSYS0000000002"] == Decimal("0.50")

    # (k) The S3 archive was written via ``write_to_s3`` exactly
    # once — the single boto3 ``put_object`` replacing the original
    # Parquet-to-S3A chain. Multiple invocations would indicate an
    # accidental double-write or a split archive.
    assert len(captured_writes) == 1, (
        f"Expected exactly 1 write_to_s3() invocation; "
        f"got {len(captured_writes)} — "
        f"writes={[{'key': w['key'], 'content_bytes': len(w['content'])} for w in captured_writes]}"
    )

    archive_write = captured_writes[0]

    # (k.1) ``content_type`` MUST be ``"text/plain"`` — the archive
    # is a fixed-width CVTRA05Y text file, NOT binary Parquet.
    assert archive_write["content_type"] == "text/plain", (
        f"Archive write content_type={archive_write['content_type']!r}, expected 'text/plain'"
    )

    # (k.2) The ``key`` MUST match the GDG-equivalent archive path
    # — derived from ``get_versioned_s3_path("TRANSACT.COMBINED",
    # "+1")`` URI prefix + ``_COMBINED_FILENAME``.
    assert archive_write["key"] == expected_archive_key, (
        f"Archive write key={archive_write['key']!r}, expected {expected_archive_key!r}"
    )

    # (l) Parse the fixed-width archive body and verify its contents
    # match the sorted DataFrame that was posted to PostgreSQL.
    # The body is a newline-joined sequence of 350-byte CVTRA05Y
    # lines (with a trailing newline from main()'s
    # ``"\n".join(lines) + "\n"`` construction).
    archive_content = archive_write["content"]
    assert isinstance(archive_content, str), f"Archive content should be str; got {type(archive_content).__name__}"

    # Strip the trailing newline (added by main() after join) before
    # splitting — otherwise splitlines would yield an extra empty
    # trailing line.
    archive_lines = archive_content.rstrip("\n").split("\n")
    assert len(archive_lines) == 5, (
        f"Expected 5 CVTRA05Y lines in archive; got {len(archive_lines)} — "
        f"line lengths={[len(line) for line in archive_lines]}"
    )

    # (l.1) Each line MUST be exactly 350 bytes — the CVTRA05Y.cpy
    # LRECL. The ``_format_combined_line`` function raises ValueError
    # if the length is wrong, so this assertion is a belt-and-braces
    # integrity check against future refactors.
    for idx, line in enumerate(archive_lines):
        assert len(line) == 350, (
            f"Archive line {idx} has length {len(line)}, expected 350 "
            f"(CVTRA05Y.cpy LRECL). Line content (first 60 bytes): "
            f"{line[:60]!r}"
        )

    # (l.2) The lines are sorted ascending by tran_id (bytes 0-15
    # of each line, with tran_id padded to 16 bytes). Extract the
    # tran_id prefix from each line and verify the order matches
    # the DataFrame sort.
    archive_tran_ids = [line[:16].rstrip() for line in archive_lines]
    assert archive_tran_ids == expected_sorted_ids, (
        f"Archive tran_id order drift; expected {expected_sorted_ids}, got {archive_tran_ids}"
    )

    # (l.3) Cross-check: the tran_id order in the S3 archive
    # matches the tran_id order in the PostgreSQL write. This
    # satisfies the JCL invariant that STEP10 (REPRO into KSDS)
    # loads from the archive produced by STEP05R — the two sinks
    # must receive the same canonical sorted stream.
    assert archive_tran_ids == posted_ids, (
        f"S3 archive and PostgreSQL write received divergent "
        f"canonical streams. Archive IDs={archive_tran_ids}, "
        f"PostgreSQL IDs={posted_ids}"
    )
