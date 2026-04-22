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
Nine test cases across five logical phases:

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
# Note: ``combtran_job`` does NOT import ``write_to_s3`` or
# ``get_connection_options`` — the S3 archive is written via the native
# ``DataFrame.write.mode("overwrite").parquet(uri)`` chain (not via the
# helper), and JDBC connectivity is handled inside ``write_table``
# itself. We therefore have no corresponding patch targets for those
# two symbols; their absence is deliberate and structural.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.combtran_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.combtran_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.combtran_job.read_table"
_PATCH_WRITE_TABLE: str = "src.batch.jobs.combtran_job.write_table"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.combtran_job.get_versioned_s3_path"
_PATCH_READ_FROM_S3: str = "src.batch.jobs.combtran_job.read_from_s3"
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

    Returns
    -------
    Row
        A PySpark Row whose field order and types are stable across
        invocations — critical because PySpark ``union()`` is
        positional (by column ordinal), not by column name. The
        helper guarantees that every DataFrame built from its output
        shares an identical schema.
    """
    return Row(
        tran_id=tran_id,
        tran_source=tran_source,
        tran_type_cd=tran_type_cd,
        tran_cat_cd=tran_cat_cd,
        tran_amt=tran_amt,
        tran_card_num=tran_card_num,
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
#     df.unpersist()         → None    (cleanup, return ignored)
#     df.write               → MagicMock (so .mode / .parquet chain
#                                          through auto-spec'd children)
#
# The ``df.write`` attribute is left as MagicMock's default (an
# auto-created child mock) because we want distinct call tracking for
# ``.mode(...)`` versus ``.parquet(...)``. This is the standard idiom
# for asserting PySpark writer chains.
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
    df.unpersist.return_value = None

    # The ``.write`` attribute is intentionally NOT overridden — we
    # rely on MagicMock's default auto-child behaviour so that
    # ``df.write.mode("overwrite")`` and its subsequent ``.parquet(uri)``
    # call are each independently tracked on the MagicMock tree. This
    # is the canonical pattern for asserting PySpark writer chains:
    #
    #     df.write.mode.assert_called_with("overwrite")
    #     df.write.mode.return_value.parquet.assert_called_with(uri)
    #
    # which collapses cleanly to a single attribute-access chain.

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

    # Column names MUST be preserved in order. The six columns below
    # are the CVTRA05Y subset exercised by combtran_job (id, source,
    # type_cd, cat_cd, amt, card_num). If any column is dropped or
    # reordered the assertion fires with a clear delta.
    expected_columns = [
        "tran_id",
        "tran_source",
        "tran_type_cd",
        "tran_cat_cd",
        "tran_amt",
        "tran_card_num",
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
# unique-keyed KSDS, which the PostgreSQL overwrite write otherwise
# would not reject).
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
#   1. ``sorted_df.write.mode("overwrite").parquet(combined_output_uri)``
#      — Parquet archive to S3 at the GDG-equivalent timestamp path.
#   2. ``write_table(sorted_df, _TABLE_NAME, mode="overwrite")`` —
#      PostgreSQL overwrite on the ``transactions`` table (the JDBC
#      writer interprets ``mode="overwrite"`` as ``TRUNCATE`` + ``INSERT``,
#      matching the mainframe REPRO's "replace all content" semantic).
#
# The two Phase-4 tests verify both writes are correctly invoked by
# ``main()``. They use ``_make_mock_df`` to replace the real Spark
# DataFrame with a chainable mock so the ``.write.mode(...).parquet(uri)``
# and ``write_table(df, table, mode="overwrite")`` invocations can be
# asserted cleanly without AWS or PostgreSQL side effects.
# ============================================================================
@pytest.mark.unit
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
    spark_session: SparkSession,
) -> None:
    """``main()`` archives the sorted combined DF to S3 via Parquet write.

    Verifies the chain
    ``sorted_df.write.mode("overwrite").parquet(combined_uri)``
    is invoked inside ``main()`` with the URI resolved by
    ``get_versioned_s3_path(_GDG_COMBINED, generation="+1")``.
    This is the PySpark analogue of the mainframe SORTOUT DD block
    in ``COMBTRAN.jcl`` lines 33-37:

        //SORTOUT  DD DISP=(NEW,CATLG,DELETE),
        //         UNIT=SYSDA,
        //         DCB=(*.SORTIN),
        //         SPACE=(CYL,(1,1),RLSE),
        //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)

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
    # ``.cache()``, ``.count()``, ``.write.mode().parquet()``, and
    # ``.unpersist()`` call in ``main()`` routes through one trackable
    # mock. ``count_value=5`` mimics the Phase-2 "3 + 2 = 5" scenario.
    mock_df = _make_mock_df(count_value=5)
    mock_read_table.return_value = mock_df

    # Resolve the three GDG paths. The first two (BKUP, SYSTRAN) are
    # consumed only by ``_probe_upstream_marker`` — the third
    # (COMBINED) is the archive target that we'll assert against.
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

    # (b) The sorted DataFrame's write chain was invoked with
    # ``mode="overwrite"`` — matching ``DISP=(NEW,CATLG,DELETE)``
    # semantics. The .mode() assertion is separated from the
    # .parquet() assertion to produce clear failure diagnostics
    # (a mode-only regression is a different fault class than a
    # path-only regression).
    mock_df.write.mode.assert_any_call("overwrite")

    # (c) ``.parquet(combined_output_uri)`` was invoked on the
    # writer chain — the archive is written to the COMBINED URI
    # resolved in step (a). The double-access
    # ``mock_df.write.mode.return_value.parquet`` is the canonical
    # idiom for asserting on PySpark writer-chain terminals without
    # triggering a new ``.mode()`` call during the assertion.
    mock_df.write.mode.return_value.parquet.assert_any_call(combined_output_uri)


@pytest.mark.unit
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
    contents wholesale — the PySpark JDBC connector implements
    ``mode="overwrite"`` on PostgreSQL as ``TRUNCATE`` followed by
    ``INSERT``, matching the mainframe semantic exactly.
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
    # violating the mainframe's replacement invariant.
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
# and marker probe) are patched so the test runs without AWS or
# PostgreSQL dependencies — but the Spark computation itself is the
# real thing.
#
# What is verified end-to-end:
#   * Two data subsets are read (the master table is filtered into
#     ``backup_df`` + ``systran_df`` by the ``tran_source`` predicate).
#   * Union merges the two subsets (N + M rows).
#   * Sort applied ascending on ``tran_id`` (ASCII ascending order).
#   * Parquet archive written to the mocked COMBINED URI.
#   * ``write_table(sorted_df, "transactions", mode="overwrite")``
#     invoked for the PostgreSQL sink.
#   * ``commit_job(job)`` invoked at the end of the happy path.
#
# Because the S3 write calls ``sorted_df.write.mode("overwrite").parquet(uri)``
# and ``sorted_df`` is a real Spark DataFrame, we redirect the write
# target to a pytest ``tmp_path`` using a ``file://`` URI. Spark
# happily writes Parquet to the local filesystem; the test then
# verifies the output directory contains Parquet files with the
# expected record count and sort order.
# ============================================================================
@pytest.mark.unit
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
    spark_session: SparkSession,
    tmp_path: Any,
) -> None:
    """End-to-end ``main()`` execution with a real SparkSession.

    Constructs a real :class:`pyspark.sql.DataFrame` matching the
    CVTRA05Y.cpy 350-byte layout, containing 3 non-``System`` rows
    (the BKUP subset) and 2 ``System`` rows (the SYSTRAN subset).
    Runs ``main()`` against this data with all external
    collaborators patched, then asserts:

        * ``read_table`` was called for the ``transactions`` table.
        * The S3 archive was written (Parquet files present at the
          local-filesystem stand-in for the COMBINED URI).
        * ``write_table`` was called with the sorted DataFrame,
          ``"transactions"``, and ``mode="overwrite"``.
        * The DataFrame written contains 5 rows (3 + 2 = 5).
        * The DataFrame written is sorted ascending by ``tran_id``.
        * ``commit_job`` was invoked.

    ``tmp_path`` (pytest built-in fixture) provides a per-test
    isolated directory so concurrent pytest workers do not collide
    on the Parquet output path.
    """
    # ----- Arrange: real Spark session from conftest.py + tmp_path -----
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
    master_rows = [
        # Non-System rows (BKUP subset) — 3 rows.
        _make_txn_row(
            "TXNBKUP00000003",
            tran_source="POS",
            tran_amt=Decimal("300.00"),
        ),
        _make_txn_row(
            "TXNBKUP00000001",
            tran_source="Online",
            tran_amt=Decimal("100.00"),
        ),
        _make_txn_row(
            "TXNBKUP00000002",
            tran_source="API",
            tran_amt=Decimal("200.00"),
        ),
        # System rows (SYSTRAN subset) — 2 rows.
        _make_txn_row(
            "TXNSYS0000000002",
            tran_source="System",
            tran_amt=Decimal("0.50"),
        ),
        _make_txn_row(
            "TXNSYS0000000001",
            tran_source="System",
            tran_amt=Decimal("0.25"),
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

    # The S3 archive URI points at a local ``file://`` path under
    # tmp_path so Spark writes Parquet to disk. The BKUP and SYSTRAN
    # prefix URIs are still formatted as ``s3://`` because the
    # upstream-marker probe uses ``.split("/", 3)`` to recover the
    # bucket+key — a ``file://`` URL would break that heuristic but
    # the probe itself is wrapped in try/except and non-fatal, so
    # either form would work in practice. We use ``s3://`` for
    # realism.
    combined_output_dir = tmp_path / "combined_archive"
    # PySpark's ``.parquet()`` accepts ``file://`` URIs for local
    # writes; plain filesystem paths also work, but the ``file://``
    # scheme makes the intent explicit.
    combined_output_uri = f"file://{combined_output_dir}"

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
    posted_field_names = {f.name for f in posted["schema"].fields}
    expected_columns = {
        "tran_id",
        "tran_source",
        "tran_type_cd",
        "tran_cat_cd",
        "tran_amt",
        "tran_card_num",
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

    # (k) The S3 archive was actually written to the local
    # ``tmp_path`` — the Parquet output directory exists and
    # contains Parquet data files. Spark writes Parquet as a
    # directory containing ``_SUCCESS`` and ``part-*.parquet``.
    assert combined_output_dir.exists(), f"Expected S3 archive directory to exist at {combined_output_dir}; it does not"

    # Spark's Parquet writer emits files matching ``part-*`` along
    # with a ``_SUCCESS`` marker. At least one part file MUST be
    # present; zero part files would indicate the write was elided.
    parquet_files = list(combined_output_dir.glob("part-*.parquet"))
    assert len(parquet_files) >= 1, (
        f"Expected at least one part-*.parquet file in {combined_output_dir}; found {len(parquet_files)}"
    )

    # The ``_SUCCESS`` marker confirms the write completed fully
    # — this is the same marker that ``_probe_upstream_marker``
    # would look for in subsequent pipeline stages.
    success_marker = combined_output_dir / "_SUCCESS"
    assert success_marker.exists(), f"Expected _SUCCESS marker at {success_marker}; it does not exist"

    # (l) Re-read the S3 archive and verify its contents match
    # what write_table received. This cross-checks that both
    # sinks (S3 + PostgreSQL) received the same canonical sorted
    # DataFrame, satisfying the JCL invariant that STEP10 loads
    # from the archive produced by STEP05R.
    archived_df = spark_session.read.parquet(str(combined_output_dir))
    archived_ids = sorted(row["tran_id"] for row in archived_df.collect())
    assert archived_ids == expected_sorted_ids, (
        f"S3 archive contents drift; expected {expected_sorted_ids}, got {archived_ids}"
    )
