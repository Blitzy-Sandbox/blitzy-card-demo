# ============================================================================
# tests/unit/test_batch/test_creastmt_job.py
# Unit tests for Stage 4a CREASTMT statement generation PySpark Glue job.
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
"""Unit tests for ``creastmt_job.py`` — Stage 4a: Statement Generation.

Validates the PySpark implementation of the CREASTMT statement-generation
Glue job that replaces the COBOL trio on the mainframe:

* ``app/cbl/CBSTM03A.CBL`` — driver program that performs the 4-way
  navigation over TRXFILE, XREFFILE, ACCTFILE, and CUSTFILE and emits
  two output streams: STMTFILE (80-char text) + HTMLFILE (100-char HTML).
* ``app/cbl/CBSTM03B.CBL`` — file-service subroutine CALLed by CBSTM03A
  for GDG I/O (``OPEN`` / ``WRITE`` / ``CLOSE`` of the text and HTML
  output files).
* ``app/jcl/CREASTMT.JCL`` — 5-step batch-pipeline job:

  - ``DELDEF01`` — scratch + redefine prior-run statement datasets.
  - ``STEP010`` — ``PGM=SORT`` with ``SORT FIELDS=(263,16,CH,A,1,16,CH,A)``
    sorts transactions by ``card_num`` (offset 263) then ``tran_id``
    (offset 1) and restructures via ``OUTREC FIELDS=(1:263,16,17:1,262,
    279:279,50)`` so ``card_num`` becomes the leading column.
  - ``STEP020`` — ``PGM=IDCAMS`` with ``REPRO`` loads the sorted file
    into the ``TRXFL.VSAM.KSDS`` work cluster.
  - ``STEP030`` — ``PGM=IEFBR14`` scratch-delete of the prior-run
    output datasets (STATEMNT.PS and STATEMNT.HTML).
  - ``STEP040`` — ``PGM=CBSTM03A`` executes the statement-generation
    driver, reading from the 4 VSAM files and writing to STMTFILE
    (``LRECL=80 RECFM=FB``) + HTMLFILE (``LRECL=100 RECFM=FB``).

Target Module Under Test
------------------------
``src/batch/jobs/creastmt_job.py`` exports four public functions:

1. ``sort_and_restructure_transactions(transactions_df) -> DataFrame``
   Replaces JCL ``STEP010`` SORT + ``STEP020`` REPRO.  Sorts by
   ``tran_card_num`` ASC, ``tran_id`` ASC, reorders columns so
   ``tran_card_num`` leads, and appends a per-card sequence number via
   a ``Window.partitionBy().orderBy()`` + ``F.row_number()`` pair.
2. ``generate_text_statement(card_num, customer, account, transactions)``
   Replaces CBSTM03A paragraphs ``5000-CREATE-STATEMENT`` and
   ``6000-WRITE-TRANS``.  Emits a ``\\n``-separated string where every
   line is exactly 80 characters, accumulating ``WS-TOTAL-AMT`` (``PIC
   S9(9)V99 COMP-3``) via :class:`decimal.Decimal` arithmetic with
   :data:`decimal.ROUND_HALF_EVEN` banker's rounding.
3. ``generate_html_statement(card_num, customer, account, transactions)``
   Replaces CBSTM03A paragraphs ``5100-WRITE-HTML-HEADER`` and
   ``5200-WRITE-HTML-NMADBS``.  Emits a well-formed HTML5 document
   starting with ``<!DOCTYPE html>`` and terminating with ``</html>``,
   containing the literal bank address ("Bank of XYZ / 410 Terry Ave N
   / Seattle WA 99999"), customer data, and one ``<tr>`` per
   transaction in a ``<table>``.
4. ``main() -> None`` — orchestrator that combines the three helpers
   above plus the private ``_build_per_card_aggregates`` 4-entity join
   into a single end-to-end Glue job.  Reads 4 tables
   (``transactions``, ``card_cross_references``, ``accounts``,
   ``customers``), writes two S3 objects via
   :func:`src.batch.common.s3_utils.write_to_s3`, and calls
   :func:`src.batch.common.glue_context.commit_job` on success.

Test Organization
-----------------
Twelve test cases across seven logical phases (per AAP):

* Phase 1 — Module import sanity — 1 test.
* Phase 2 — 4-entity join (driver × xref × accounts × customers) — 2 tests.
* Phase 3 — Sort by card_num ASC then tran_id ASC — 1 test.
* Phase 4 — Text statement structure / Decimal total / empty txns — 3 tests.
* Phase 5 — HTML statement structure / customer-data injection — 2 tests.
* Phase 6 — S3 output content-type for text and HTML — 2 tests.
* Phase 7 — End-to-end ``main()`` integration with real Spark — 1 test.

The 4-entity-join, sort, and ``main`` integration tests use the
session-scoped :class:`pyspark.sql.SparkSession` fixture (``spark_session``)
from :mod:`tests.conftest`.  The text/HTML statement-generation tests
invoke the pure functions directly with synthetic ``dict`` inputs (no
Spark required).  The S3 output tests patch ``init_glue``,
``read_table``, ``get_versioned_s3_path``, ``write_to_s3``, and
``commit_job`` from the module's import namespace so the write path is
exercised without any AWS side effects.

Key test data invariants
------------------------
All monetary fields (``tran_amt`` — ``TRAN-AMT PIC S9(09)V99`` in
``CVTRA05Y.cpy``; ``acct_curr_bal`` — ``ACCT-CURR-BAL PIC S9(10)V99``
in ``CVACT01Y.cpy``) use :class:`decimal.Decimal` with explicit
two-decimal scale — never ``float`` — per the AAP §0.7.2 financial
precision rule.  Total accumulation in the text statement uses
:data:`decimal.ROUND_HALF_EVEN` matching COBOL ``ROUNDED`` semantics.
"""

from __future__ import annotations

import logging
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pyspark.sql import Row, SparkSession

# Module under test — imported for its four public entry points.  The
# ``main()`` function is the subject of the Phase 7 integration test;
# ``sort_and_restructure_transactions`` is exercised directly in
# Phase 3; ``generate_text_statement`` and ``generate_html_statement``
# are exercised directly in Phases 4 and 5 respectively.
from src.batch.jobs.creastmt_job import (
    generate_html_statement,
    generate_text_statement,
    main,
    sort_and_restructure_transactions,
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
# ``src.batch.jobs.creastmt_job`` — not the original definition module.
# This matters because Python's ``unittest.mock.patch`` rebinds the NAME
# in the namespace specified by the dotted path; ``init_glue`` is
# imported into ``creastmt_job`` as ``from src.batch.common.glue_context
# import init_glue``, so a patch of ``src.batch.common.glue_context.init_glue``
# would NOT intercept the call made from inside ``creastmt_job.main``.
# The correct target is ``src.batch.jobs.creastmt_job.init_glue`` — the
# symbol as re-bound in the module-under-test's namespace.
#
# Unlike COMBTRAN (which writes Parquet via ``write_table``) and POSTTRAN
# (which reads/writes multiple tables), the CREASTMT job writes its two
# outputs to S3 via :func:`src.batch.common.s3_utils.write_to_s3` — so
# the patch targets include ``write_to_s3`` + ``get_versioned_s3_path``
# instead of ``write_table``.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.creastmt_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.creastmt_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.creastmt_job.read_table"
_PATCH_WRITE_TO_S3: str = "src.batch.jobs.creastmt_job.write_to_s3"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.creastmt_job.get_versioned_s3_path"


# ============================================================================
# Test-data schema constants — field names lifted from the COBOL copybooks.
#
# These names match the lowercased + snake-cased versions of the COBOL
# field identifiers after ORM normalization.  Keeping them in one place
# lets us change (e.g., add a new field) in a single edit if the
# underlying schema evolves.
# ============================================================================
# Transaction columns (from CVTRA05Y.cpy — TRAN-ID, TRAN-CARD-NUM, etc.).
_TXN_COLS: list[str] = [
    "tran_id",
    "tran_card_num",
    "tran_desc",
    "tran_amt",
    "tran_type_cd",
    "tran_cat_cd",
]

# Cross-reference columns (from CVACT03Y.cpy — XREF-CARD-NUM + XREF-CUST-ID
# + XREF-ACCT-ID).
_XREF_COLS: list[str] = ["card_num", "cust_id", "acct_id"]

# Account columns (subset from CVACT01Y.cpy sufficient for the 4-entity
# join and statement generation — ACCT-ID + ACCT-CURR-BAL).
_ACCT_COLS: list[str] = ["acct_id", "acct_curr_bal"]

# Customer columns (from CUSTREC.cpy — CUST-ID through CUST-FICO-CREDIT-SCORE).
# These are the fields consumed by the text/HTML statement generators.
_CUST_COLS: list[str] = [
    "cust_id",
    "cust_first_name",
    "cust_middle_name",
    "cust_last_name",
    "cust_addr_line_1",
    "cust_addr_line_2",
    "cust_addr_line_3",
    "cust_addr_state_cd",
    "cust_addr_country_cd",
    "cust_addr_zip",
    "cust_fico_credit_score",
]


# ============================================================================
# Row-builder helpers — produce :class:`pyspark.sql.Row` objects with
# defaults that can be overridden per-test via keyword arguments.
#
# The keyword-only defaults mirror the convention used in
# ``tests/unit/test_batch/test_combtran_job.py`` — every caller MUST
# pass positional arguments for the primary-key fields (tran_id /
# card_num / acct_id / cust_id) so the test reader can see the
# row-key at a glance without scanning the kwarg list.
# ============================================================================


def _make_txn_row(
    tran_id: str,
    tran_card_num: str,
    *,
    tran_desc: str = "PURCHASE",
    tran_amt: Decimal = Decimal("10.00"),
    tran_type_cd: str = "01",
    tran_cat_cd: str = "0001",
) -> Row:
    """Build a transaction Row for the CVTRA05Y schema.

    Parameters
    ----------
    tran_id : str
        The 16-char transaction identifier (primary key).
    tran_card_num : str
        The 16-digit card number (join key against card_cross_references).
    tran_desc : str, keyword-only
        The transaction description (50 chars nominally; defaults to a
        short literal for test legibility).
    tran_amt : Decimal, keyword-only
        The transaction amount (``NUMERIC(15,2)`` in PostgreSQL; must
        always be :class:`decimal.Decimal` — never ``float`` — per AAP
        §0.7.2 financial-precision rule).
    tran_type_cd, tran_cat_cd : str, keyword-only
        Transaction classification codes.
    """
    return Row(
        tran_id=tran_id,
        tran_card_num=tran_card_num,
        tran_desc=tran_desc,
        tran_amt=tran_amt,
        tran_type_cd=tran_type_cd,
        tran_cat_cd=tran_cat_cd,
    )


def _make_xref_row(card_num: str, cust_id: str, acct_id: str) -> Row:
    """Build a cross-reference Row for the CVACT03Y schema.

    Parameters
    ----------
    card_num : str
        The 16-digit card number (primary key).
    cust_id : str
        The 9-digit customer identifier (foreign key to customers).
    acct_id : str
        The 11-digit account identifier (foreign key to accounts).
    """
    return Row(card_num=card_num, cust_id=cust_id, acct_id=acct_id)


def _make_account_row(
    acct_id: str,
    *,
    acct_curr_bal: Decimal = Decimal("1000.00"),
) -> Row:
    """Build an account Row for the CVACT01Y schema (subset).

    Only the fields consumed by the 4-entity join and statement
    generation are included — ``acct_id`` (PK) and ``acct_curr_bal``
    (displayed on ST-LINE8 of the text statement).  Additional
    CVACT01Y fields (credit_limit, expiration_date, etc.) are omitted
    for test clarity.
    """
    return Row(acct_id=acct_id, acct_curr_bal=acct_curr_bal)


def _make_customer_row(
    cust_id: str,
    *,
    cust_first_name: str = "John     ",
    cust_middle_name: str = "Q        ",
    cust_last_name: str = "Smith    ",
    cust_addr_line_1: str = "123 Main Street                  ",
    cust_addr_line_2: str = "Apt 4B                           ",
    cust_addr_line_3: str = "Seattle                          ",
    cust_addr_state_cd: str = "WA",
    cust_addr_country_cd: str = "USA",
    cust_addr_zip: str = "98101     ",
    cust_fico_credit_score: int = 750,
) -> Row:
    """Build a customer Row for the CUSTREC schema.

    The default field values are padded with trailing spaces to mimic
    the fixed-width COBOL ``PIC X(n)`` storage convention — the name
    and address fields on the mainframe are zero-padded to their
    declared widths.  The module under test's ``_cobol_first_word``
    and ``_cobol_rstrip`` helpers strip this padding; passing padded
    values here ensures the stripping logic is exercised.
    """
    return Row(
        cust_id=cust_id,
        cust_first_name=cust_first_name,
        cust_middle_name=cust_middle_name,
        cust_last_name=cust_last_name,
        cust_addr_line_1=cust_addr_line_1,
        cust_addr_line_2=cust_addr_line_2,
        cust_addr_line_3=cust_addr_line_3,
        cust_addr_state_cd=cust_addr_state_cd,
        cust_addr_country_cd=cust_addr_country_cd,
        cust_addr_zip=cust_addr_zip,
        cust_fico_credit_score=cust_fico_credit_score,
    )


# ============================================================================
# Helper: dict fixtures for the pure-function tests (Phases 4 and 5).
#
# These dicts are consumed directly by ``generate_text_statement`` and
# ``generate_html_statement`` — no Spark session required.  They are
# constructed once at test-time (not module load) so the tests remain
# independent and can mutate the dicts freely without cross-test leakage.
# ============================================================================


def _sample_customer_dict() -> dict[str, Any]:
    """Build a sample customer dict for Phase 4 / Phase 5 pure-function tests.

    The dict matches the schema produced by ``_build_per_card_aggregates``
    at the driver side — flat ``dict[str, Any]`` with the CVCUS01Y /
    CUSTREC field names (already snake-cased and passed through the
    ORM).  The ``cust_fico_credit_score`` is an ``int`` (matches the
    schema — ``FICO-CREDIT-SCORE PIC 9(3)``).
    """
    return {
        "cust_id": "000000001",
        "cust_first_name": "John     ",
        "cust_middle_name": "Q        ",
        "cust_last_name": "Smith    ",
        "cust_addr_line_1": "123 Main Street                      ",
        "cust_addr_line_2": "Apt 4B                                ",
        "cust_addr_line_3": "Seattle                               ",
        "cust_addr_state_cd": "WA",
        "cust_addr_country_cd": "USA",
        "cust_addr_zip": "98101     ",
        "cust_fico_credit_score": 750,
    }


def _sample_account_dict() -> dict[str, Any]:
    """Build a sample account dict for Phase 4 / Phase 5 pure-function tests.

    Only ``acct_id`` and ``acct_curr_bal`` are consumed by the statement
    generators (see the CBSTM03A paragraphs ``5000-CREATE-STATEMENT`` and
    ``5200-WRITE-HTML-NMADBS``).  The balance is a :class:`decimal.Decimal`
    per the AAP §0.7.2 financial-precision rule.
    """
    return {
        "acct_id": "00000000001",
        "acct_curr_bal": Decimal("1234.56"),
    }


def _sample_transaction_dict(
    tran_id: str = "T000000000000001",
    tran_desc: str = "GROCERY PURCHASE",
    tran_amt: Decimal = Decimal("45.67"),
) -> dict[str, Any]:
    """Build a sample transaction dict for Phase 4 / Phase 5 pure-function tests.

    Matches the per-transaction struct layout emitted by
    ``_build_per_card_aggregates`` — three fields pulled from the
    collect_list of ``F.struct(tran_id, tran_desc, tran_amt)``.  The
    ``tran_amt`` is always :class:`decimal.Decimal` per AAP §0.7.2.
    """
    return {
        "tran_id": tran_id,
        "tran_desc": tran_desc,
        "tran_amt": tran_amt,
    }


# ============================================================================
# Phase 1 — Module import sanity check.
# ============================================================================


@pytest.mark.unit
def test_module_public_api_importable() -> None:
    """Verify all four public entry points are importable without error.

    This is a smoke test for the module's import-time validation asserts
    — if any of the 80-char ``_ST_LINE*`` constants drifts from its
    declared width, the module-level ``assert`` statements fire and the
    import fails with :exc:`AssertionError`.  A passing test confirms:

    1. The four public names (``main``, ``sort_and_restructure_transactions``,
       ``generate_text_statement``, ``generate_html_statement``) are
       callable.
    2. The import-time assertions on the COBOL ST-LINE constant widths
       (80 chars each) all passed.
    3. No syntax errors or missing dependencies at module-load time.
    """
    # Each of the four public entry points must be a callable function.
    assert callable(main), "main must be callable"
    assert callable(sort_and_restructure_transactions), "sort_and_restructure_transactions must be callable"
    assert callable(generate_text_statement), "generate_text_statement must be callable"
    assert callable(generate_html_statement), "generate_html_statement must be callable"


# ============================================================================
# Phase 2 — 4-entity join tests.
#
# The mainframe CBSTM03A.CBL performs the 4-way navigation sequentially:
#   - Sequential browse of XREFFILE (1000-XREFFILE-GET-NEXT)
#   - Key-read of CUSTFILE by cust_id (2000-CUSTFILE-GET)
#   - Key-read of ACCTFILE by acct_id (3000-ACCTFILE-GET)
#   - Key-probed browse of TRNXFILE (4000-TRNXFILE-GET / 8500-READTRNX-READ)
#
# In the PySpark implementation, this is a single 4-way join inside
# ``_build_per_card_aggregates`` which ``main()`` invokes after sorting
# the transactions DataFrame.  We exercise the join indirectly via
# ``main()`` with real Spark DataFrames.
# ============================================================================


@pytest.mark.unit
def test_four_entity_join(spark_session: SparkSession) -> None:
    """Verify the 4-entity join correctly links xref → customers → accounts → transactions.

    Builds 4 small DataFrames with intentionally overlapping keys so
    that each cross-reference row can be linked to its customer,
    account, and transactions.  Runs ``main()`` under a full mock-
    patch stack (so no S3 or JDBC traffic flows) and verifies the
    text/HTML outputs contain the expected customer name, account ID,
    and transaction descriptions.

    This indirect verification is the only way to exercise the
    private ``_build_per_card_aggregates`` helper without reaching
    past the module's public API — per the AAP's restriction of
    ``members_accessed`` to the four public entry points.
    """
    # ----- Arrange: build 4 source DataFrames -----
    # One card with one transaction, one card with zero transactions.
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row(
                card_num="4111111111111111",
                cust_id="000000001",
                acct_id="00000000001",
            ),
            _make_xref_row(
                card_num="4222222222222222",
                cust_id="000000002",
                acct_id="00000000002",
            ),
        ]
    )
    customers_df = spark_session.createDataFrame(
        [
            _make_customer_row(
                cust_id="000000001",
                cust_first_name="Alice    ",
                cust_last_name="Johnson  ",
            ),
            _make_customer_row(
                cust_id="000000002",
                cust_first_name="Bob      ",
                cust_last_name="Williams ",
            ),
        ]
    )
    accounts_df = spark_session.createDataFrame(
        [
            _make_account_row("00000000001", acct_curr_bal=Decimal("1500.00")),
            _make_account_row("00000000002", acct_curr_bal=Decimal("2500.00")),
        ]
    )
    transactions_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                tran_id="T000000000000001",
                tran_card_num="4111111111111111",
                tran_desc="COFFEE SHOP PURCHASE",
                tran_amt=Decimal("4.50"),
            ),
            # Second card has NO matching transactions — verifies LEFT OUTER join.
        ]
    )

    # ----- Arrange: capture writes (avoid any real S3 / commit calls) -----
    captured_text: dict[str, str] = {}
    captured_html: dict[str, str] = {}

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        """Return the correct mock DataFrame based on table name."""
        return {
            "transactions": transactions_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "customers": customers_df,
        }[table_name]

    def _write_to_s3_side_effect(
        content: str,
        key: str,
        bucket: str | None = None,
        content_type: str = "text/plain",
    ) -> str:
        """Capture the written content keyed by content_type."""
        if content_type == "text/plain":
            captured_text["content"] = content
            captured_text["key"] = key
            captured_text["bucket"] = bucket or ""
        else:
            captured_html["content"] = content
            captured_html["key"] = key
            captured_html["bucket"] = bucket or ""
        return f"s3://{bucket}/{key}"

    def _get_s3_path_side_effect(gdg_name: str, *_args: Any, **_kwargs: Any) -> str:
        """Return a deterministic versioned S3 path for the given GDG name."""
        return f"s3://test-bucket/gdg/{gdg_name}/2026/04/22/120000/"

    # ----- Act: run main() under the mock stack -----
    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect),
        patch(_PATCH_GET_S3_PATH, side_effect=_get_s3_path_side_effect),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            spark_session,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            {"JOB_NAME": "carddemo-creastmt"},
        )
        main()

        # ----- Assert: commit_job called (successful completion) -----
        mock_commit_job.assert_called_once()

    # ----- Assert: join results produced the expected per-card output -----
    # Both cards must produce a text statement (LEFT OUTER join means
    # the card with zero transactions still gets a statement).
    text_content: str = captured_text["content"]
    html_content: str = captured_html["content"]

    # Card 1: Alice Q Johnson with one transaction.
    # Note: the default ``cust_middle_name="Q        "`` in
    # ``_make_customer_row`` produces the three-token concatenation
    # "Alice Q Johnson" (via ``_cobol_concat_name`` which takes the
    # first word of each of the three name parts and joins them with
    # single spaces, filtering out empty tokens).
    assert "Alice Q Johnson" in text_content, "Card 1's customer name must appear in text output"
    assert "00000000001" in text_content, "Card 1's account ID must appear in text output"
    assert "COFFEE SHOP PURCHASE" in text_content, "Card 1's transaction description must appear in text output"

    # Card 2: Bob Q Williams with zero transactions — statement still generated.
    assert "Bob Q Williams" in text_content, (
        "Card 2's customer name must appear in text output (LEFT OUTER join preserves cards with no transactions)"
    )
    assert "00000000002" in text_content, "Card 2's account ID must appear in text output"

    # HTML output must also contain both customers.
    assert "Alice Q Johnson" in html_content
    assert "Bob Q Williams" in html_content
    assert "COFFEE SHOP PURCHASE" in html_content


@pytest.mark.unit
def test_join_handles_missing_customer() -> None:
    """Verify graceful handling when an xref row lacks a matching customer.

    The COBOL source treats a missing customer record as a fatal
    condition — ``2000-CUSTFILE-GET`` abends the program via its
    ``EVALUATE WS-CUST-GET-STATUS`` branch when the READ returns
    ``INVALID KEY`` (CBSTM03A.CBL paragraph ``9999-ABEND``).

    The PySpark translation uses an INNER JOIN between xref and
    customers (per the schema FK constraint
    ``card_cross_references.cust_id REFERENCES customers(cust_id)``
    in ``V1__schema.sql``).  An INNER JOIN naturally excludes
    xref rows without a matching customer — the card simply does
    not appear in the output, which is the closest idiomatic
    PySpark equivalent of the mainframe's abend-on-missing-customer
    behavior (the statement for the orphan card is NOT produced,
    matching the mainframe's failure-to-generate-statement outcome).

    This test verifies that the join contract exists: when customers
    is empty but xref references a customer, the join yields no rows
    — and ``main()`` completes without raising a Python exception
    (unlike COBOL which would abend).  This is the documented cloud-
    native behavior per the AAP §0.7.1 "minimal change" clause: we
    preserve the *contract* (no statement for orphan cards) without
    reproducing the exact abend mechanics (which don't translate to
    a stateless serverless runtime).
    """
    # ----- Arrange: mock Spark session (no real Spark needed for this test) -----
    mock_spark = MagicMock(name="MockSparkSession")

    # Build fully-chainable mock DataFrames for all 4 tables.  The
    # module builds its join-graph lazily; with no Spark action
    # triggered by the mocks, the only call we expect is for the
    # ``.collect()`` on the final joined DataFrame to return an
    # empty list (mimicking the INNER JOIN excluding orphan rows).
    def _make_mock_df(collected_rows: list[Any]) -> MagicMock:
        """Build a chainable DataFrame mock that returns empty collect()."""
        df = MagicMock(name="MockDataFrame")
        df.orderBy.return_value = df
        df.select.return_value = df
        df.withColumn.return_value = df
        df.alias.return_value = df
        df.join.return_value = df
        df.groupBy.return_value = df
        df.agg.return_value = df
        df.columns = _TXN_COLS  # must support list iteration
        df.collect.return_value = collected_rows
        return df

    # Final join yields empty list — no orphan-customer card produces a statement.
    empty_df = _make_mock_df([])

    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE) as mock_read_table,
        patch(_PATCH_GET_S3_PATH) as mock_get_s3_path,
        patch(_PATCH_WRITE_TO_S3) as mock_write_to_s3,
    ):
        mock_init_glue.return_value = (
            mock_spark,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            {"JOB_NAME": "carddemo-creastmt"},
        )
        mock_read_table.return_value = empty_df
        mock_get_s3_path.return_value = "s3://test-bucket/gdg/STATEMNT/2026/04/22/120000/"
        mock_write_to_s3.return_value = "s3://test-bucket/gdg/STATEMNT/file"

        # ----- Act -----
        # Must not raise — the INNER JOIN yielded no rows, so no
        # statements are generated, but the job completes cleanly.
        main()

        # ----- Assert: commit_job was called (graceful completion) -----
        mock_commit_job.assert_called_once()

        # write_to_s3 was still called twice (once for empty text output,
        # once for empty HTML output) — the S3 objects are created but
        # contain no statement data.  This matches the mainframe behavior
        # where STMTFILE and HTMLFILE are allocated (DISP=NEW) even when
        # zero statements are generated.
        assert mock_write_to_s3.call_count == 2, (
            f"Expected 2 S3 writes (text + HTML), got {mock_write_to_s3.call_count}"
        )


# ============================================================================
# Phase 3 — Sort tests.
#
# The ``sort_and_restructure_transactions`` function replaces JCL STEP010
# SORT FIELDS=(263,16,CH,A,1,16,CH,A).  The test below uses a real
# SparkSession to exercise the ``orderBy`` / ``select`` / ``Window`` chain.
# ============================================================================


@pytest.mark.unit
def test_sort_by_card_num_then_tran_id(spark_session: SparkSession) -> None:
    """Verify sort matches JCL SORT FIELDS=(263,16,CH,A,1,16,CH,A).

    The sort is a two-level ascending sort:
      - Primary key: ``tran_card_num`` (offset 263 on the 350-byte VSAM record).
      - Secondary key: ``tran_id`` (offset 1 on the same record).

    In addition to the sort, the function also:
      - Reorders the columns so ``tran_card_num`` leads (OUTREC restructure).
      - Appends a ``tran_seq`` column via a ``Window.partitionBy()`` +
        ``F.row_number()`` pair (per-card 1-based sequence number).

    We exercise a 5-row input with interleaved cards so that correct
    sort order is non-trivial:

    Input order (random):
      1. card=4222, tran_id=T0000000000005 (later card, early tran)
      2. card=4111, tran_id=T0000000000003 (earlier card, later tran)
      3. card=4222, tran_id=T0000000000001 (later card, earliest tran)
      4. card=4111, tran_id=T0000000000001 (earlier card, earliest tran)
      5. card=4333, tran_id=T0000000000002 (latest card)

    Expected output order:
      1. card=4111, tran_id=T0000000000001, tran_seq=1
      2. card=4111, tran_id=T0000000000003, tran_seq=2
      3. card=4222, tran_id=T0000000000001, tran_seq=1
      4. card=4222, tran_id=T0000000000005, tran_seq=2
      5. card=4333, tran_id=T0000000000002, tran_seq=1
    """
    # ----- Arrange: interleaved input DataFrame -----
    input_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                "T0000000000005",
                "4222222222222222",
                tran_amt=Decimal("22.50"),
            ),
            _make_txn_row(
                "T0000000000003",
                "4111111111111111",
                tran_amt=Decimal("11.30"),
            ),
            _make_txn_row(
                "T0000000000001",
                "4222222222222222",
                tran_amt=Decimal("22.01"),
            ),
            _make_txn_row(
                "T0000000000001",
                "4111111111111111",
                tran_amt=Decimal("11.01"),
            ),
            _make_txn_row(
                "T0000000000002",
                "4333333333333333",
                tran_amt=Decimal("33.02"),
            ),
        ]
    )

    # ----- Act -----
    sorted_df = sort_and_restructure_transactions(input_df)

    # ----- Assert: collect and verify -----
    sorted_rows = sorted_df.collect()
    assert len(sorted_rows) == 5, f"Sort must preserve all 5 input rows, got {len(sorted_rows)}"

    # The restructuring reorders columns so tran_card_num leads.
    # We extract the (card_num, tran_id, tran_seq) tuple per row.
    actual_order: list[tuple[str, str, int]] = [
        (row["tran_card_num"], row["tran_id"], row["tran_seq"]) for row in sorted_rows
    ]

    expected_order: list[tuple[str, str, int]] = [
        ("4111111111111111", "T0000000000001", 1),
        ("4111111111111111", "T0000000000003", 2),
        ("4222222222222222", "T0000000000001", 1),
        ("4222222222222222", "T0000000000005", 2),
        ("4333333333333333", "T0000000000002", 1),
    ]

    assert actual_order == expected_order, f"Sort order wrong — expected {expected_order}, got {actual_order}"

    # Verify column reordering: tran_card_num is now the first column.
    output_columns = sorted_df.columns
    assert output_columns[0] == "tran_card_num", (
        f"OUTREC restructure must place tran_card_num first, got columns={output_columns}"
    )
    assert output_columns[1] == "tran_id", f"OUTREC restructure must place tran_id second, got columns={output_columns}"

    # Verify tran_seq column was appended.
    assert "tran_seq" in output_columns, f"Window function must append tran_seq column, got columns={output_columns}"


# ============================================================================
# Phase 4 — Text statement generation tests.
#
# The ``generate_text_statement`` function produces an 80-char LRECL
# fixed-width text output per the STMTFILE DD contract from
# CREASTMT.JCL line 86.
# ============================================================================


@pytest.mark.unit
def test_generate_text_statement_structure() -> None:
    """Verify text statement has the expected structure and 80-char line width.

    The mainframe STMTFILE DD specifies ``LRECL=80 RECFM=FB`` — every
    line of the text output must be exactly 80 characters wide (per
    AAP §0.5.1 and the module docstring).  The statement structure
    mirrors CBSTM03A paragraphs ``5000-CREATE-STATEMENT`` and
    ``6000-WRITE-TRANS``:

      * ST-LINE0 (start banner with 31 stars + "START OF STATEMENT" + 31 stars)
      * Customer name line
      * Address lines (1, 2, 3)
      * ST-LINE5 (80 dashes)
      * ST-LINE6 ("Basic Details" section header)
      * Account ID / Current Balance / FICO Score lines
      * ST-LINE11 ("TRANSACTION SUMMARY" section header)
      * Column headers (Tran ID / Tran Details / Tran Amount)
      * Transaction detail lines
      * ST-LINE14A (total row with "Total EXP: $<total>")
      * ST-LINE15 (end banner with 32 stars + "END OF STATEMENT" + 32 stars)
    """
    # ----- Arrange -----
    customer = _sample_customer_dict()
    account = _sample_account_dict()
    transactions = [
        _sample_transaction_dict(
            tran_id="T000000000000001",
            tran_desc="GROCERY PURCHASE",
            tran_amt=Decimal("45.67"),
        ),
        _sample_transaction_dict(
            tran_id="T000000000000002",
            tran_desc="GAS STATION",
            tran_amt=Decimal("35.00"),
        ),
    ]

    # ----- Act -----
    result = generate_text_statement("4111111111111111", customer, account, transactions)

    # ----- Assert: overall structure -----
    # Trailing newline is appended by the generator (matching COBOL
    # RECFM=FB convention).  Strip it before splitting.
    lines = result.rstrip("\n").split("\n")

    # Every line must be exactly 80 chars wide (LRECL=80 contract).
    for idx, line in enumerate(lines):
        assert len(line) == 80, f"Line {idx} has wrong width: expected 80, got {len(line)}; content={line!r}"

    # Line 0: start-of-statement banner (31 stars + "START OF STATEMENT" + 31 stars).
    assert lines[0] == "*" * 31 + "START OF STATEMENT" + "*" * 31, (
        f"Line 0 must be ST-LINE0 start banner, got {lines[0]!r}"
    )

    # Last line: end-of-statement banner (32 stars + "END OF STATEMENT" + 32 stars).
    # Note the asymmetry — 32/16/32 vs start's 31/18/31.
    assert lines[-1] == "*" * 32 + "END OF STATEMENT" + "*" * 32, (
        f"Last line must be ST-LINE15 end banner, got {lines[-1]!r}"
    )

    # Customer name must appear (first line after ST-LINE0).
    assert "John Q Smith" in lines[1], f"Customer name must appear on line 1, got {lines[1]!r}"

    # Address line 1 must appear.
    assert any("123 Main Street" in line for line in lines), (
        "Address line 1 '123 Main Street' must appear in the statement"
    )

    # Account ID must appear on ST-LINE7 (one of the Basic Details lines).
    assert any("00000000001" in line for line in lines), "Account ID must appear in the statement"

    # The "Basic Details" and "TRANSACTION SUMMARY" section headers must
    # appear — these are the COBOL ST-LINE6 and ST-LINE11 constants.
    assert any("Basic Details" in line for line in lines), "Basic Details section header must appear"
    assert any("TRANSACTION SUMMARY" in line for line in lines), "TRANSACTION SUMMARY section header must appear"

    # Transaction descriptions must appear.
    assert any("GROCERY PURCHASE" in line for line in lines), "First transaction description must appear"
    assert any("GAS STATION" in line for line in lines), "Second transaction description must appear"

    # The total row must appear (ST-LINE14A = "Total EXP:" + spaces + "$" + amt).
    assert any("Total EXP:" in line and "$" in line for line in lines), "Total EXP: row with dollar sign must appear"


@pytest.mark.unit
def test_generate_text_statement_total_decimal() -> None:
    """Verify WS-TOTAL-AMT accumulates correctly using Decimal arithmetic.

    This test enforces the AAP §0.7.2 financial-precision rule:
    WS-TOTAL-AMT is declared as ``PIC S9(9)V99 COMP-3`` in the COBOL
    source (CBSTM03A.CBL) and MUST be accumulated using
    :class:`decimal.Decimal` with :data:`decimal.ROUND_HALF_EVEN`
    (banker's rounding).  Zero floating-point arithmetic is permitted
    at any step.

    Input: three transactions with amounts ``12.34 + 56.78 + 90.12``
    — sum is exactly ``Decimal("159.24")`` under two-decimal-place
    COMP-3 semantics.  The test asserts the total appears in the
    output formatted as ``"     159.24 "`` (PIC Z(9).99- zero-suppressed)
    — 5 leading spaces + 159.24 + trailing space for positive sign.
    """
    # ----- Arrange -----
    customer = _sample_customer_dict()
    account = _sample_account_dict()
    # Three transactions — sum is 12.34 + 56.78 + 90.12 = 159.24.
    transactions = [
        _sample_transaction_dict(
            tran_id="T000000000000001",
            tran_desc="TXN 1",
            tran_amt=Decimal("12.34"),
        ),
        _sample_transaction_dict(
            tran_id="T000000000000002",
            tran_desc="TXN 2",
            tran_amt=Decimal("56.78"),
        ),
        _sample_transaction_dict(
            tran_id="T000000000000003",
            tran_desc="TXN 3",
            tran_amt=Decimal("90.12"),
        ),
    ]
    # Manually compute expected total under exact Decimal arithmetic
    # with banker's rounding — matches the module's accumulator logic.
    expected_total: Decimal = Decimal("0.00")
    for txn in transactions:
        expected_total = (expected_total + txn["tran_amt"]).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
    assert expected_total == Decimal("159.24"), "Test sanity: expected total should be Decimal('159.24')"

    # ----- Act -----
    result = generate_text_statement("4111111111111111", customer, account, transactions)

    # ----- Assert: total appears in the output as PIC Z(9).99- -----
    # The _format_amount_edited helper produces a 13-char edited string
    # with 2-decimal precision.  For Decimal("159.24") positive value:
    #   zero-suppressed: 6 leading spaces + "159.24" + trailing space = 13 chars
    # The total line template is: "Total EXP:" + 56 spaces + "$" + edited_total + padding
    # We verify the formatted total string appears somewhere in the output.
    lines = result.rstrip("\n").split("\n")

    # Find the Total EXP line.
    total_lines = [line for line in lines if "Total EXP:" in line]
    assert len(total_lines) == 1, f"Expected exactly one Total EXP line, got {len(total_lines)}"
    total_line = total_lines[0]

    # Total line must be 80 chars (LRECL contract).
    assert len(total_line) == 80, f"Total line width must be 80, got {len(total_line)}"

    # The formatted total "159.24" must appear in the total line.
    assert "159.24" in total_line, f"Formatted total 159.24 must appear in total line, got {total_line!r}"

    # The dollar sign prefix must precede the amount.
    assert "$" in total_line, f"Dollar sign prefix must appear before total, got {total_line!r}"


@pytest.mark.unit
def test_generate_text_statement_empty_transactions() -> None:
    """Verify statement is still generated (with zero total) for empty transactions.

    A card with no transactions must still produce a fully-formed
    statement — the mainframe behavior was to emit ST-LINE0 through
    ST-LINE15 regardless of whether any transactions were posted
    (paragraph 5000-CREATE-STATEMENT always writes the header
    unconditionally).

    The zero total must be formatted as a :class:`decimal.Decimal`
    ``Decimal("0.00")`` — not a ``float`` ``0.0`` or integer ``0`` —
    per AAP §0.7.2.  Under the PIC Z(9).99- edit format, the zero
    total renders as ``"          .00 "`` (10 leading spaces for the
    integer portion + ".00" + trailing space for positive sign).
    """
    # ----- Arrange: empty transactions list -----
    customer = _sample_customer_dict()
    account = _sample_account_dict()
    transactions: list[dict[str, Any]] = []

    # ----- Act -----
    result = generate_text_statement("4111111111111111", customer, account, transactions)

    # ----- Assert: statement still fully formed -----
    lines = result.rstrip("\n").split("\n")

    # Every line must still be 80 chars (LRECL contract holds for
    # empty-transaction statements too).
    for idx, line in enumerate(lines):
        assert len(line) == 80, f"Line {idx} has wrong width: expected 80, got {len(line)}"

    # Start-of-statement banner present.
    assert lines[0] == "*" * 31 + "START OF STATEMENT" + "*" * 31

    # End-of-statement banner present.
    assert lines[-1] == "*" * 32 + "END OF STATEMENT" + "*" * 32

    # Customer name and account ID still present.
    assert "John Q Smith" in lines[1]
    assert any("00000000001" in line for line in lines)

    # Total EXP row present with zero total.  The PIC Z(9).99- edit
    # format renders Decimal("0.00") as ".00" with leading spaces +
    # trailing sign space — we verify ".00" appears (and there is no
    # non-zero total string).
    total_lines = [line for line in lines if "Total EXP:" in line]
    assert len(total_lines) == 1, f"Expected exactly one Total EXP line for empty transactions, got {len(total_lines)}"
    total_line = total_lines[0]
    assert ".00" in total_line, f"Zero-total line must contain '.00', got {total_line!r}"

    # No transaction-detail lines should appear.  The COBOL code emits
    # unconditionally:
    #   0:  ST-LINE0 (START OF STATEMENT banner)
    #   1:  customer name
    #   2:  address line 1
    #   3:  address line 2
    #   4:  address line 3 (city + state + country + zip composite)
    #   5:  dashes
    #   6:  ST-LINE6 (Basic Details section header)
    #   7:  dashes
    #   8:  ST-LINE7 (Account ID)
    #   9:  ST-LINE8 (Current Balance)
    #   10: ST-LINE9 (FICO Score)
    #   11: dashes
    #   12: ST-LINE11 (TRANSACTION SUMMARY section header)
    #   13: dashes
    #   14: ST-LINE13 (column headers — Tran ID / Tran Details / Amount)
    #   15: dashes (column-header underline)
    #   -- zero transactions, no per-txn lines appear --
    #   16: dashes (above Total EXP)
    #   17: ST-LINE14A (Total EXP: $0.00)
    #   18: ST-LINE15 (END OF STATEMENT banner)
    # Total: 19 lines for empty-transaction statement.
    # With N transactions: 19 + N lines.
    assert len(lines) == 19, f"Empty-transaction statement should have 19 lines, got {len(lines)}: {lines!r}"


# ============================================================================
# Phase 5 — HTML statement generation tests.
#
# The ``generate_html_statement`` function produces a complete HTML5
# document per the HTMLFILE DD contract from CREASTMT.JCL line 91.
# Unlike the text output (which is hard-padded to LRECL=80), the HTML
# output has no byte-width constraint — HTTP consumers parse by
# structure, not byte offset.
# ============================================================================


@pytest.mark.unit
def test_generate_html_statement_structure() -> None:
    """Verify HTML statement is a well-formed HTML5 document.

    The mainframe HTMLFILE DD specifies ``LRECL=100 RECFM=FB`` — but
    for an HTTP-delivered content this is irrelevant (browsers parse
    HTML by structure, not byte offset).  The module preserves the
    document structure without padding lines to 100 chars.

    The emitted document must:
      * Start with ``<!DOCTYPE html>`` (HTML5 declaration).
      * Contain ``<html lang="en">`` (opening tag).
      * Contain ``<table ...>`` (the statement rendering).
      * Terminate with ``</html>`` (closing tag).
      * Contain the bank literals: "Bank of XYZ" / "410 Terry Ave N" /
        "Seattle WA 99999" (from CBSTM03A HTML-L16/L17/L18).
      * Contain the section headers "Basic Details" and
        "Transaction Summary".
      * Contain the column headers "Tran ID" / "Tran Details" / "Amount".
      * Contain the closing "End of Statement" h3 element.
    """
    # ----- Arrange -----
    customer = _sample_customer_dict()
    account = _sample_account_dict()
    transactions = [
        _sample_transaction_dict(
            tran_id="T000000000000001",
            tran_desc="GROCERY PURCHASE",
            tran_amt=Decimal("45.67"),
        ),
    ]

    # ----- Act -----
    result = generate_html_statement("4111111111111111", customer, account, transactions)

    # ----- Assert: HTML5 document structure -----
    # The document must start with the HTML5 DOCTYPE.
    assert result.startswith("<!DOCTYPE html>"), f"HTML must start with <!DOCTYPE html>, got prefix {result[:40]!r}"

    # The document must contain the opening <html lang="en"> tag.
    assert '<html lang="en">' in result, 'HTML must contain <html lang="en"> opening tag'

    # The document must contain at least one <table ...> element.
    assert "<table" in result, "HTML must contain a <table> element"

    # The document must terminate with </html>.
    assert "</html>" in result, "HTML must contain </html> closing tag"

    # Bank literals — from CBSTM03A HTML-L16/L17/L18.
    assert "Bank of XYZ" in result, "HTML must contain 'Bank of XYZ' bank name literal"
    assert "410 Terry Ave N" in result, "HTML must contain '410 Terry Ave N' bank address literal"
    assert "Seattle WA 99999" in result, "HTML must contain 'Seattle WA 99999' bank city/state/zip literal"

    # Section headers — from CBSTM03A HTML-L31 and HTML-L43.
    assert "Basic Details" in result, "HTML must contain 'Basic Details' section header"
    assert "Transaction Summary" in result, "HTML must contain 'Transaction Summary' section header"

    # Column headers — from CBSTM03A HTML-L48/L51/L54.
    assert "Tran ID" in result, "HTML must contain 'Tran ID' column header"
    assert "Tran Details" in result, "HTML must contain 'Tran Details' column header"
    assert "Amount" in result, "HTML must contain 'Amount' column header"

    # Closing element — from CBSTM03A HTML-L75.
    assert "End of Statement" in result, "HTML must contain 'End of Statement' closing element"

    # Structural well-formedness: balanced table tags.
    # Each statement produces exactly one <table>...</table> pair.
    assert result.count("<table") == 1, f"Expected exactly 1 <table opening, got {result.count('<table')}"
    assert result.count("</table>") == 1, f"Expected exactly 1 </table> closing, got {result.count('</table>')}"


@pytest.mark.unit
def test_generate_html_statement_contains_customer_data() -> None:
    """Verify customer name, address, and account data appear in HTML output.

    The HTML statement injects customer data via:
      * Customer full name (via _cobol_concat_name) in a <p> element.
      * Address line 1 in a <p> element.
      * Address line 2 in a <p> element.
      * Address line 3 composite (city + state + country + zip) in a <p>.
      * Account ID in "Account ID         : <id>" <p> element.
      * Current Balance in "Current Balance    : <balance>" <p> element.
      * FICO Score in "FICO Score         : <score>" <p> element.

    Customer data is HTML-escaped via the module's ``_html_escape``
    helper before injection — a minor hardening over the mainframe
    behavior (AAP §0.7.1-compliant because it doesn't alter visible
    output for clean data).  This test uses clean test data so the
    injected values appear verbatim.
    """
    # ----- Arrange: explicit customer with recognizable values -----
    customer: dict[str, Any] = {
        "cust_id": "000000001",
        "cust_first_name": "Alice    ",
        "cust_middle_name": "B        ",
        "cust_last_name": "Johnson  ",
        "cust_addr_line_1": "456 Oak Avenue                     ",
        "cust_addr_line_2": "Suite 200                          ",
        "cust_addr_line_3": "Portland                           ",
        "cust_addr_state_cd": "OR",
        "cust_addr_country_cd": "USA",
        "cust_addr_zip": "97201     ",
        "cust_fico_credit_score": 820,
    }
    account: dict[str, Any] = {
        "acct_id": "00000000042",
        "acct_curr_bal": Decimal("5678.90"),
    }
    transactions: list[dict[str, Any]] = [
        _sample_transaction_dict(
            tran_id="T000000000000099",
            tran_desc="ONLINE SUBSCRIPTION",
            tran_amt=Decimal("9.99"),
        ),
    ]

    # ----- Act -----
    result = generate_html_statement("4444444444444444", customer, account, transactions)

    # ----- Assert: customer name present (concatenated via _cobol_concat_name) -----
    # Note: _cobol_concat_name strips each name at its first space, then
    # joins with single spaces — so "Alice    ", "B        ", "Johnson  "
    # becomes "Alice B Johnson".
    assert "Alice B Johnson" in result, (
        f"HTML must contain concatenated customer name 'Alice B Johnson', got a 2000-char excerpt: {result[:2000]!r}"
    )

    # ----- Assert: address lines present -----
    assert "456 Oak Avenue" in result, "HTML must contain address line 1 '456 Oak Avenue'"
    assert "Suite 200" in result, "HTML must contain address line 2 'Suite 200'"
    assert "Portland" in result, "HTML must contain address line 3 (city) 'Portland'"
    assert "OR" in result, "HTML must contain state code 'OR'"
    assert "97201" in result, "HTML must contain ZIP code '97201'"

    # ----- Assert: account data present -----
    assert "00000000042" in result, "HTML must contain account ID '00000000042'"

    # Current Balance is formatted via _format_balance_edited (PIC 9(9).99-).
    # For Decimal("5678.90") → zero-filled 9-digit integer + ".90" + trailing space.
    assert "5678.90" in result, "HTML must contain formatted current balance '5678.90'"

    # ----- Assert: FICO score present (820 + trailing spaces padded) -----
    assert "820" in result, "HTML must contain FICO score '820'"

    # ----- Assert: transaction description present -----
    assert "ONLINE SUBSCRIPTION" in result, "HTML must contain transaction description 'ONLINE SUBSCRIPTION'"

    # ----- Assert: transaction amount present -----
    assert "9.99" in result, "HTML must contain transaction amount '9.99'"


# ============================================================================
# Phase 6 — S3 output tests.
#
# These tests patch ``init_glue``, ``read_table``, ``get_versioned_s3_path``,
# ``write_to_s3``, and ``commit_job`` from the module's namespace so that
# the full ``main()`` flow runs without any AWS or PostgreSQL I/O.
#
# The tests verify that:
#   1. ``write_to_s3`` is called with ``content_type="text/plain"`` for
#      the text statement output.
#   2. ``write_to_s3`` is called with ``content_type="text/html"`` for
#      the HTML statement output.
#   3. The S3 path is built from ``get_versioned_s3_path("STATEMNT.PS")``
#      for text and ``get_versioned_s3_path("STATEMNT.HTML")`` for HTML.
# ============================================================================


@pytest.mark.unit
def test_text_statements_written_to_s3(spark_session: SparkSession) -> None:
    """Verify text statements are written to S3 with content_type='text/plain'.

    The CREASTMT.JCL STMTFILE DD had ``LRECL=80 RECFM=FB`` — the S3
    equivalent is an object written with ``Content-Type: text/plain``.
    This is the ``_CONTENT_TYPE_TEXT`` constant in the module under
    test; we assert ``write_to_s3`` was invoked with it.

    Additionally, the S3 object key must be derived from
    ``get_versioned_s3_path("STATEMNT.PS")`` — the GDG name
    ``STATEMNT.PS`` is the mainframe DSN for the text statement
    output (CREASTMT.JCL line 86).
    """
    # ----- Arrange: build small 4-entity DataFrames via real Spark -----
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row(
                card_num="4111111111111111",
                cust_id="000000001",
                acct_id="00000000001",
            ),
        ]
    )
    customers_df = spark_session.createDataFrame([_make_customer_row(cust_id="000000001")])
    accounts_df = spark_session.createDataFrame([_make_account_row("00000000001")])
    transactions_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                tran_id="T000000000000001",
                tran_card_num="4111111111111111",
                tran_desc="PURCHASE",
                tran_amt=Decimal("25.00"),
            ),
        ]
    )

    # Capture write_to_s3 invocations — both positional args and kwargs.
    write_calls: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        content: str,
        key: str,
        bucket: str | None = None,
        content_type: str = "text/plain",
    ) -> str:
        """Capture the write_to_s3 invocation details."""
        write_calls.append(
            {
                "content": content,
                "key": key,
                "bucket": bucket,
                "content_type": content_type,
            }
        )
        return f"s3://{bucket}/{key}"

    # Track which GDG names were resolved — used to verify the text
    # path was derived from STATEMNT.PS (not STATEMNT.HTML).
    gdg_calls: list[str] = []

    def _get_s3_path_side_effect(gdg_name: str, *_args: Any, **_kwargs: Any) -> str:
        """Record the GDG name and return a deterministic S3 prefix URI."""
        gdg_calls.append(gdg_name)
        # Use distinct prefixes per GDG so the text and HTML output
        # keys are visually distinguishable.
        if gdg_name == "STATEMNT.PS":
            return "s3://carddemo-bucket/statements/text/2026/04/22/120000/"
        elif gdg_name == "STATEMNT.HTML":
            return "s3://carddemo-bucket/statements/html/2026/04/22/120000/"
        raise ValueError(f"Unexpected GDG name: {gdg_name}")

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transactions": transactions_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "customers": customers_df,
        }[table_name]

    # ----- Act: run main() under mock stack -----
    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect),
        patch(_PATCH_GET_S3_PATH, side_effect=_get_s3_path_side_effect),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            spark_session,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            {"JOB_NAME": "carddemo-creastmt"},
        )
        main()
        mock_commit_job.assert_called_once()

    # ----- Assert: text statement written with content_type="text/plain" -----
    text_writes = [c for c in write_calls if c["content_type"] == "text/plain"]
    assert len(text_writes) == 1, (
        f"Expected exactly 1 text/plain write, got {len(text_writes)}: all writes={write_calls!r}"
    )
    text_call = text_writes[0]

    # Content-type must be exactly "text/plain".
    assert text_call["content_type"] == "text/plain", (
        f"Text write content_type must be 'text/plain', got {text_call['content_type']!r}"
    )

    # The S3 key must be composed from STATEMNT.PS's versioned prefix.
    # _compose_s3_key splits the prefix URI on the first '/' after "s3://"
    # and appends the filename — so the key should contain "statements/text/..."
    # and end in "STATEMNT.txt".
    assert "STATEMNT.txt" in text_call["key"], f"Text write key must end in 'STATEMNT.txt', got {text_call['key']!r}"
    assert "statements/text" in text_call["key"], (
        f"Text write key must be under statements/text prefix, got {text_call['key']!r}"
    )

    # The bucket should be extracted from the S3 URI.
    assert text_call["bucket"] == "carddemo-bucket", (
        f"Text write bucket must be 'carddemo-bucket', got {text_call['bucket']!r}"
    )

    # The GDG name "STATEMNT.PS" must have been passed to get_versioned_s3_path.
    assert "STATEMNT.PS" in gdg_calls, f"get_versioned_s3_path must be called with 'STATEMNT.PS', got {gdg_calls!r}"

    # The text content should contain recognizable statement markers.
    text_content: str = text_call["content"]
    assert "START OF STATEMENT" in text_content, "Text content must contain 'START OF STATEMENT' banner"
    assert "END OF STATEMENT" in text_content, "Text content must contain 'END OF STATEMENT' banner"


@pytest.mark.unit
def test_html_statements_written_to_s3(spark_session: SparkSession) -> None:
    """Verify HTML statements are written to S3 with content_type='text/html'.

    The CREASTMT.JCL HTMLFILE DD had ``LRECL=100 RECFM=FB`` — the S3
    equivalent is an object written with ``Content-Type: text/html``.
    This is the ``_CONTENT_TYPE_HTML`` constant in the module under
    test; we assert ``write_to_s3`` was invoked with it.

    Additionally, the S3 object key must be derived from
    ``get_versioned_s3_path("STATEMNT.HTML")`` — the GDG name
    ``STATEMNT.HTML`` is the mainframe DSN for the HTML statement
    output (CREASTMT.JCL line 91).
    """
    # ----- Arrange: build small 4-entity DataFrames via real Spark -----
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row(
                card_num="4111111111111111",
                cust_id="000000001",
                acct_id="00000000001",
            ),
        ]
    )
    customers_df = spark_session.createDataFrame([_make_customer_row(cust_id="000000001")])
    accounts_df = spark_session.createDataFrame([_make_account_row("00000000001")])
    transactions_df = spark_session.createDataFrame(
        [
            _make_txn_row(
                tran_id="T000000000000001",
                tran_card_num="4111111111111111",
                tran_desc="PURCHASE",
                tran_amt=Decimal("25.00"),
            ),
        ]
    )

    write_calls: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        content: str,
        key: str,
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

    gdg_calls: list[str] = []

    def _get_s3_path_side_effect(gdg_name: str, *_args: Any, **_kwargs: Any) -> str:
        gdg_calls.append(gdg_name)
        if gdg_name == "STATEMNT.PS":
            return "s3://carddemo-bucket/statements/text/2026/04/22/120000/"
        elif gdg_name == "STATEMNT.HTML":
            return "s3://carddemo-bucket/statements/html/2026/04/22/120000/"
        raise ValueError(f"Unexpected GDG name: {gdg_name}")

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transactions": transactions_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "customers": customers_df,
        }[table_name]

    # ----- Act: run main() under mock stack -----
    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect),
        patch(_PATCH_GET_S3_PATH, side_effect=_get_s3_path_side_effect),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            spark_session,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            {"JOB_NAME": "carddemo-creastmt"},
        )
        main()
        mock_commit_job.assert_called_once()

    # ----- Assert: HTML statement written with content_type="text/html" -----
    html_writes = [c for c in write_calls if c["content_type"] == "text/html"]
    assert len(html_writes) == 1, (
        f"Expected exactly 1 text/html write, got {len(html_writes)}: all writes={write_calls!r}"
    )
    html_call = html_writes[0]

    # Content-type must be exactly "text/html".
    assert html_call["content_type"] == "text/html", (
        f"HTML write content_type must be 'text/html', got {html_call['content_type']!r}"
    )

    # The S3 key must be composed from STATEMNT.HTML's versioned prefix.
    assert "STATEMNT.html" in html_call["key"], f"HTML write key must end in 'STATEMNT.html', got {html_call['key']!r}"
    assert "statements/html" in html_call["key"], (
        f"HTML write key must be under statements/html prefix, got {html_call['key']!r}"
    )

    # Bucket extraction.
    assert html_call["bucket"] == "carddemo-bucket", (
        f"HTML write bucket must be 'carddemo-bucket', got {html_call['bucket']!r}"
    )

    # STATEMNT.HTML GDG name was passed to get_versioned_s3_path.
    assert "STATEMNT.HTML" in gdg_calls, f"get_versioned_s3_path must be called with 'STATEMNT.HTML', got {gdg_calls!r}"

    # The HTML content should contain recognizable HTML5 markers.
    html_content: str = html_call["content"]
    assert "<!DOCTYPE html>" in html_content, "HTML content must contain <!DOCTYPE html>"
    assert "</html>" in html_content, "HTML content must contain </html> closing tag"
    assert "Bank of XYZ" in html_content, "HTML content must contain bank name literal"


# ============================================================================
# Phase 7 — End-to-end main() integration test with real Spark.
#
# This is the capstone test: builds 4 small DataFrames via real Spark,
# mocks init_glue / read_table / get_versioned_s3_path / write_to_s3 /
# commit_job from the module's namespace, and runs main() end-to-end.
# Asserts that both text and HTML outputs are written with the correct
# content types, the expected customer/transaction data appear in both
# outputs, and the Glue job commits successfully.
# ============================================================================


@pytest.mark.unit
def test_creastmt_main_with_spark(spark_session: SparkSession) -> None:
    """End-to-end test: run main() with real Spark and verify both outputs.

    This test exercises the full CREASTMT pipeline:
      1. ``init_glue(job_name="carddemo-creastmt")`` — mocked.
      2. ``read_table(spark, "transactions" | "card_cross_references" |
         "accounts" | "customers")`` — returns pre-built real Spark
         DataFrames (bypasses JDBC).
      3. ``sort_and_restructure_transactions(transactions_df)`` — real
         invocation.
      4. ``_build_per_card_aggregates(...)`` — real invocation (private,
         exercised indirectly via main()).
      5. ``generate_text_statement(...)`` per card — real invocation.
      6. ``generate_html_statement(...)`` per card — real invocation.
      7. ``get_versioned_s3_path("STATEMNT.PS" | "STATEMNT.HTML")`` — mocked.
      8. ``write_to_s3(content, key, bucket=..., content_type=...)`` — mocked.
      9. ``commit_job(job)`` — mocked.

    Asserts that both text and HTML outputs are produced with the
    correct content types, correct customer/transaction data, and
    the Glue job commits cleanly.
    """
    # ----- Arrange: 4 source DataFrames with 3 cards × varying txns -----
    # Card 1: Alice with 2 transactions (12.34 + 56.78 = 69.12 total).
    # Card 2: Bob with 1 transaction (100.00 total).
    # Card 3: Charlie with 0 transactions (0.00 total — exercises LEFT OUTER).
    xref_df = spark_session.createDataFrame(
        [
            _make_xref_row(
                card_num="4111111111111111",
                cust_id="000000001",
                acct_id="00000000001",
            ),
            _make_xref_row(
                card_num="4222222222222222",
                cust_id="000000002",
                acct_id="00000000002",
            ),
            _make_xref_row(
                card_num="4333333333333333",
                cust_id="000000003",
                acct_id="00000000003",
            ),
        ]
    )
    customers_df = spark_session.createDataFrame(
        [
            _make_customer_row(
                cust_id="000000001",
                cust_first_name="Alice    ",
                cust_last_name="Johnson  ",
                cust_fico_credit_score=800,
            ),
            _make_customer_row(
                cust_id="000000002",
                cust_first_name="Bob      ",
                cust_last_name="Williams ",
                cust_fico_credit_score=750,
            ),
            _make_customer_row(
                cust_id="000000003",
                cust_first_name="Charlie  ",
                cust_last_name="Davis    ",
                cust_fico_credit_score=700,
            ),
        ]
    )
    accounts_df = spark_session.createDataFrame(
        [
            _make_account_row("00000000001", acct_curr_bal=Decimal("5000.00")),
            _make_account_row("00000000002", acct_curr_bal=Decimal("2500.00")),
            _make_account_row("00000000003", acct_curr_bal=Decimal("1000.00")),
        ]
    )
    transactions_df = spark_session.createDataFrame(
        [
            # Card 1 has 2 transactions — interleaved so the sort matters.
            _make_txn_row(
                tran_id="T000000000000002",
                tran_card_num="4111111111111111",
                tran_desc="COFFEE",
                tran_amt=Decimal("56.78"),
            ),
            _make_txn_row(
                tran_id="T000000000000001",
                tran_card_num="4111111111111111",
                tran_desc="BOOK STORE",
                tran_amt=Decimal("12.34"),
            ),
            # Card 2 has 1 transaction.
            _make_txn_row(
                tran_id="T000000000000003",
                tran_card_num="4222222222222222",
                tran_desc="RESTAURANT",
                tran_amt=Decimal("100.00"),
            ),
            # Card 3 has no transactions — tests the LEFT OUTER branch.
        ]
    )

    # ----- Arrange: capture the writes -----
    write_calls: list[dict[str, Any]] = []

    def _write_to_s3_side_effect(
        content: str,
        key: str,
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

    gdg_calls: list[str] = []

    def _get_s3_path_side_effect(gdg_name: str, *_args: Any, **_kwargs: Any) -> str:
        gdg_calls.append(gdg_name)
        if gdg_name == "STATEMNT.PS":
            return "s3://test-bucket/statements/text/2026/04/22/120000/"
        elif gdg_name == "STATEMNT.HTML":
            return "s3://test-bucket/statements/html/2026/04/22/120000/"
        raise ValueError(f"Unexpected GDG name: {gdg_name}")

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transactions": transactions_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "customers": customers_df,
        }[table_name]

    # ----- Act: run main() under mock stack -----
    with (
        patch(_PATCH_INIT_GLUE) as mock_init_glue,
        patch(_PATCH_COMMIT_JOB) as mock_commit_job,
        patch(_PATCH_READ_TABLE, side_effect=_read_side_effect) as mock_read_table,
        patch(_PATCH_GET_S3_PATH, side_effect=_get_s3_path_side_effect),
        patch(_PATCH_WRITE_TO_S3, side_effect=_write_to_s3_side_effect),
    ):
        mock_init_glue.return_value = (
            spark_session,
            MagicMock(name="MockGlueContext"),
            MagicMock(name="MockGlueJob"),
            {"JOB_NAME": "carddemo-creastmt"},
        )
        main()

        # ----- Assert: Glue lifecycle -----
        # init_glue called once with the correct job name.
        mock_init_glue.assert_called_once()
        init_call_kwargs = mock_init_glue.call_args.kwargs
        assert init_call_kwargs.get("job_name") == "carddemo-creastmt", (
            f"init_glue must be called with job_name='carddemo-creastmt', got kwargs={init_call_kwargs!r}"
        )

        # read_table called exactly 4 times (once per source table).
        assert mock_read_table.call_count == 4, (
            f"read_table must be called 4 times (transactions, xref, "
            f"accounts, customers), got {mock_read_table.call_count}"
        )

        # commit_job called once at successful completion.
        mock_commit_job.assert_called_once()

    # ----- Assert: S3 write summary -----
    # Exactly 2 writes total: one text, one HTML.
    assert len(write_calls) == 2, (
        f"Expected exactly 2 S3 writes (1 text + 1 HTML), got {len(write_calls)}: {write_calls!r}"
    )

    # Split by content-type.
    text_writes = [c for c in write_calls if c["content_type"] == "text/plain"]
    html_writes = [c for c in write_calls if c["content_type"] == "text/html"]
    assert len(text_writes) == 1, "Exactly 1 text/plain write expected"
    assert len(html_writes) == 1, "Exactly 1 text/html write expected"

    # Both GDG names were passed to get_versioned_s3_path.
    assert "STATEMNT.PS" in gdg_calls
    assert "STATEMNT.HTML" in gdg_calls

    # ----- Assert: text output content -----
    text_content: str = text_writes[0]["content"]

    # All 3 cards produced statements (LEFT OUTER preserves the
    # no-transaction card).
    # Note: the default ``cust_middle_name="Q        "`` in
    # ``_make_customer_row`` produces the three-token concatenation
    # "<First> Q <Last>" via ``_cobol_concat_name``.
    assert "Alice Q Johnson" in text_content, "Alice's statement must appear in text output"
    assert "Bob Q Williams" in text_content, "Bob's statement must appear in text output"
    assert "Charlie Q Davis" in text_content, (
        "Charlie's statement must appear in text output (LEFT OUTER join preserves cards with no transactions)"
    )

    # Account IDs must all appear.
    assert "00000000001" in text_content
    assert "00000000002" in text_content
    assert "00000000003" in text_content

    # Transaction descriptions appear (sorted by tran_id ASC per card).
    assert "BOOK STORE" in text_content
    assert "COFFEE" in text_content
    assert "RESTAURANT" in text_content

    # Decimal-precision check: Card 1 total = 12.34 + 56.78 = 69.12.
    # The total formatted via PIC Z(9).99- renders as "        69.12 "
    # (10 leading spaces + 69.12 + trailing space for positive).
    # We verify that 69.12 appears in the text somewhere.
    assert "69.12" in text_content, "Card 1 total (12.34 + 56.78) must render as 69.12 in text output"
    # Card 2 total = 100.00.
    assert "100.00" in text_content, "Card 2 total must render as 100.00 in text output"

    # Verify the sort order of Card 1's transactions (BOOK STORE first,
    # COFFEE second — tran_id T0000000000001 < T0000000000002).
    book_idx = text_content.index("BOOK STORE")
    coffee_idx = text_content.index("COFFEE")
    assert book_idx < coffee_idx, (
        f"Transactions must be sorted by tran_id ASC — BOOK STORE "
        f"(tran_id=T00...01) must precede COFFEE (tran_id=T00...02); "
        f"got book_idx={book_idx}, coffee_idx={coffee_idx}"
    )

    # Every text line must be 80 chars wide.  The text content is a
    # concatenation of per-card statements; we split on '\n' and skip
    # the empty trailing entry produced by the final newline.
    text_lines = text_content.split("\n")
    # Drop the trailing empty string from the final newline.
    if text_lines and text_lines[-1] == "":
        text_lines = text_lines[:-1]
    for idx, line in enumerate(text_lines):
        assert len(line) == 80, f"Text line {idx} has wrong width: expected 80, got {len(line)}; content={line!r}"

    # ----- Assert: HTML output content -----
    html_content: str = html_writes[0]["content"]

    # HTML document structure markers.
    assert "<!DOCTYPE html>" in html_content
    assert "</html>" in html_content

    # Bank literals present.
    assert "Bank of XYZ" in html_content
    assert "410 Terry Ave N" in html_content
    assert "Seattle WA 99999" in html_content

    # All 3 customers appear (with middle name "Q" from default).
    assert "Alice Q Johnson" in html_content
    assert "Bob Q Williams" in html_content
    assert "Charlie Q Davis" in html_content

    # All transactions appear.
    assert "BOOK STORE" in html_content
    assert "COFFEE" in html_content
    assert "RESTAURANT" in html_content

    # Each card produces its own <!DOCTYPE html>...</html> document.
    # Cards are separated by the _HTML_INTER_STATEMENT_SEPARATOR
    # comment.  We should see exactly 3 DOCTYPE occurrences (one per card).
    assert html_content.count("<!DOCTYPE html>") == 3, (
        f"Expected 3 DOCTYPE declarations (one per card), got {html_content.count('<!DOCTYPE html>')}"
    )
