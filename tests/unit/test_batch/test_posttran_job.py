# ============================================================================
# Source: app/cbl/CBTRN02C.cbl      — Transaction Posting Engine (~580 lines)
#         app/jcl/POSTTRAN.jcl      — JCL orchestration for Stage 1
#         app/cpy/CVTRA06Y.cpy      — DALYTRAN-RECORD (350B, daily feed)
#         app/cpy/CVTRA05Y.cpy      — TRAN-RECORD (350B, posted output)
#         app/cpy/CVACT03Y.cpy      — CARD-XREF-RECORD (50B, card→acct)
#         app/cpy/CVACT01Y.cpy      — ACCOUNT-RECORD (300B)
#         app/cpy/CVTRA01Y.cpy      — TRAN-CAT-BAL-RECORD (50B)
#
# Target module: src/batch/jobs/posttran_job.py
#
# Test-case mapping (AAP §0.5.1, test instructions):
#   Phase 2 — 4-stage validation cascade (8 tests)
#     test_validate_valid_transaction             — all 4 stages pass
#     test_validate_reject_100_invalid_card       — Stage 1 → 100
#     test_validate_reject_101_account_not_found  — Stage 2 → 101
#     test_validate_reject_102_overlimit          — Stage 3 → 102
#     test_validate_reject_102_exact_limit_passes — boundary: == passes
#     test_validate_reject_103_expired_account    — Stage 4 → 103
#     test_validate_cascade_stops_at_first_failure
#     test_validate_overlimit_check_before_expiration
#   Phase 3 — Transaction/reject builders (2 tests)
#     test_build_posted_transaction_field_mapping
#     test_build_reject_record_format
#   Phase 4 — TCATBAL create-or-update (2 tests)
#     test_update_tcatbal_create_new_record
#     test_update_tcatbal_update_existing_record
#   Phase 5 — Account balance update (2 tests)
#     test_update_account_balance_credit_transaction
#     test_update_account_balance_debit_transaction
#   Phase 6 — Return-code / counter semantics (3 tests)
#     test_return_code_4_when_rejects_exist
#     test_return_code_0_when_no_rejects
#     test_processed_and_rejected_counts
#   Phase 7 — PySpark integration (1 test)
#     test_posttran_main_with_spark(spark_session)
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
"""Unit tests for ``src.batch.jobs.posttran_job``.

This module validates behavioural parity between the Python/PySpark
POSTTRAN job and the original COBOL source program
``app/cbl/CBTRN02C.cbl`` (Stage 1 of the 5-stage batch pipeline).  It
is the most critical test suite in the batch layer because the
downstream stages (INTCALC, COMBTRAN, CREASTMT, TRANREPT) all consume
the artefacts (posted transactions, updated account balances, updated
TCATBAL, reject file) produced by POSTTRAN — any drift between the
mainframe source semantics and the cloud implementation surfaces here
first.

COBOL → Python Verification Surface
-----------------------------------
=================================  ===========================================
COBOL construct                    Python symbol under test
=================================  ===========================================
paragraph 1500-VALIDATE-TRAN       ``validate_transaction``
paragraph 1500-A-LOOKUP-XREF       ``validate_transaction`` Stage 1 (→ 100)
paragraph 1500-B-LOOKUP-ACCT       ``validate_transaction`` Stage 2 (→ 101)
overlimit check (lines 403-413)    ``validate_transaction`` Stage 3 (→ 102)
expiration check (lines 414-420)   ``validate_transaction`` Stage 4 (→ 103)
paragraph 2000-POST-TRANSACTION    ``build_posted_transaction``
paragraph 2500-WRITE-REJECT-REC    ``build_reject_record`` (430-byte layout)
paragraph 2700-UPDATE-TCATBAL      ``update_tcatbal`` (2700-A + 2700-B)
paragraph 2800-UPDATE-ACCOUNT-REC  ``update_account_balance``
COBOL line 232 (MOVE 4 TO RC)      ``main`` → ``sys.exit(4)`` on reject_count
entry point / PROCEDURE DIVISION   ``main`` (end-to-end integration)
REJECT-CODE constants              ``REJECT_INVALID_CARD`` (100),
                                   ``REJECT_ACCT_NOT_FOUND`` (101),
                                   ``REJECT_OVERLIMIT`` (102),
                                   ``REJECT_EXPIRED`` (103),
                                   ``REJECT_ACCT_REWRITE_FAIL`` (109)
=================================  ===========================================

Mocking Strategy
----------------
The POSTTRAN job has three external-to-Python side effects: AWS Glue /
Spark lifecycle (``init_glue`` / ``commit_job``), JDBC I/O against
Aurora PostgreSQL (``read_table`` / ``write_table`` /
``get_connection_options``), and S3 uploads for the reject file
(``get_versioned_s3_path`` / ``write_to_s3``).  For the unit-level
tests in this module every one of those surfaces is replaced with a
:class:`unittest.mock.MagicMock` scoped via
:func:`unittest.mock.patch` at the *target-module* namespace (i.e.,
patching ``src.batch.jobs.posttran_job.read_table`` rather than
``src.batch.common.db_connector.read_table``) so the tests are
hermetic and do not require any running infrastructure.

The Phase 7 integration test (``test_posttran_main_with_spark``) uses
a **real** :class:`pyspark.sql.SparkSession` from the project-wide
``spark_session`` fixture declared in ``tests/conftest.py`` — the
Glue / JDBC / S3 layers are still mocked, but the Spark DataFrame
operations (``createDataFrame``, ``orderBy``, ``withColumn``,
``toLocalIterator``) execute for real so that the end-to-end flow is
validated against actual PySpark semantics.

Financial Precision
-------------------
Every monetary test value is a :class:`decimal.Decimal` — never a
:class:`float`.  This is required by AAP §0.7.2 which mandates COBOL
``PIC S9(n)V99`` precision parity via Python's
:class:`decimal.Decimal` type.  Assertions compare
:class:`Decimal` values byte-for-byte; even a single ``float`` in a
test input would introduce binary-floating-point drift and cause
false-positive / false-negative outcomes in the overlimit check.

See Also
--------
:mod:`src.batch.jobs.posttran_job` — module under test.
:mod:`tests.unit.test_batch.test_daily_tran_driver_job` — reference
    test pattern for Stage 0 (CBTRN01C daily transaction driver).
:mod:`tests.conftest` — source of the project-wide ``spark_session``
    fixture used by the Phase 7 integration test.
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``logging``     — configured by ``caplog`` fixture for capturing the
#                   COBOL-equivalent DISPLAY messages emitted by the job.
# ``Decimal``     — COBOL PIC S9(n)V99 equivalent.  Every monetary test
#                   value is a Decimal per AAP §0.7.2.
# ``MagicMock`` /
# ``patch``       — isolation of the module under test from Glue / JDBC /
#                   S3 dependencies.
# ----------------------------------------------------------------------------
import logging
from decimal import Decimal
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pyspark.sql import Row, SparkSession

# ----------------------------------------------------------------------------
# First-party imports — symbols under test.
# ----------------------------------------------------------------------------
# The full list of imported names is declared in the AAP
# internal_imports schema for this file: the five public functions
# implementing CBTRN02C's main business logic, the ``main`` entry
# point, and the five REJECT_* integer constants that carry the COBOL
# reject-code literals verbatim (100, 101, 102, 103, 109).
# ----------------------------------------------------------------------------
from src.batch.jobs.posttran_job import (
    REJECT_ACCT_NOT_FOUND,
    REJECT_ACCT_REWRITE_FAIL,
    REJECT_EXPIRED,
    REJECT_INVALID_CARD,
    REJECT_OVERLIMIT,
    build_posted_transaction,
    build_reject_record,
    main,
    update_account_balance,
    update_tcatbal,
    validate_transaction,
)

# ============================================================================
# Expected COBOL-verbatim text — compared against actual log / reject
# payload emissions to verify AAP §0.7.1 behavioural parity.
# ============================================================================
#: The exact banner the COBOL source program emits at start of
#: execution (``DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.``).
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBTRN02C"

#: The exact banner the COBOL source program emits at end of
#: execution (``DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'.``).
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBTRN02C"

#: The exact reject-description text for code 100 (Stage 1 failure:
#: card cross-reference lookup miss).  Byte-for-byte identical to
#: the COBOL literal in paragraph 1500-A-LOOKUP-XREF.
_REJECT_DESC_100: str = "INVALID CARD NUMBER FOUND"

#: The exact reject-description text for code 101 (Stage 2 failure:
#: account lookup miss).  Byte-for-byte identical to the COBOL
#: literal in paragraph 1500-B-LOOKUP-ACCT.
_REJECT_DESC_101: str = "ACCOUNT RECORD NOT FOUND"

#: The exact reject-description text for code 102 (Stage 3 failure:
#: credit-limit breach via ``curr_cyc_credit - curr_cyc_debit +
#: tran_amt > credit_limit``).
_REJECT_DESC_102: str = "OVERLIMIT TRANSACTION"

#: The exact reject-description text for code 103 (Stage 4 failure:
#: transaction received after the account's expiration date).
_REJECT_DESC_103: str = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"


# ============================================================================
# Reject-code integer-value sanity tests — verify the five public
# constants carry the exact COBOL literals.  These are not
# behavioural tests but catch accidental refactors of the module's
# reject-code enumeration (e.g., someone renumbering to 0-4 for
# readability would break reconciliation with legacy reject files).
# ============================================================================
@pytest.mark.unit
def test_reject_code_constants_exact_values() -> None:
    """Verify the 5 REJECT_* constants carry the exact COBOL literals.

    Corresponds to the COBOL ``MOVE nnn TO WS-VALIDATION-FAIL-REASON``
    statements in CBTRN02C.cbl paragraphs 1500-A-LOOKUP-XREF (100),
    1500-B-LOOKUP-ACCT (101, 102, 103), and 2800-UPDATE-ACCOUNT-REC
    (109 — REWRITE INVALID KEY branch).

    Any change to these numeric literals would break binary
    compatibility with pre-existing reject files produced by either
    the mainframe COBOL run OR prior Python runs — a catastrophic
    reconciliation break.  This test locks the values at compile
    time.
    """
    assert REJECT_INVALID_CARD == 100
    assert REJECT_ACCT_NOT_FOUND == 101
    assert REJECT_OVERLIMIT == 102
    assert REJECT_EXPIRED == 103
    # 109 is reserved for the ``update_account_balance`` REWRITE
    # INVALID KEY failure branch (CBTRN02C.cbl lines 545-560).  It
    # is defined as a module-level constant but not emitted by
    # ``validate_transaction`` — the rewrite path runs later in
    # main() after validation passes.
    assert REJECT_ACCT_REWRITE_FAIL == 109
    # Defensive: each constant is an int (not a string / Decimal).
    assert isinstance(REJECT_INVALID_CARD, int)
    assert isinstance(REJECT_ACCT_NOT_FOUND, int)
    assert isinstance(REJECT_OVERLIMIT, int)
    assert isinstance(REJECT_EXPIRED, int)
    assert isinstance(REJECT_ACCT_REWRITE_FAIL, int)


# ============================================================================
# Patch-target constants — fully-qualified module paths of the
# runtime dependencies imported by src/batch/jobs/posttran_job.py.
# Each constant is the string form pytest / unittest.mock.patch
# requires.  Centralising these strings prevents drift between tests
# and keeps the "patch at the target module's namespace" idiom
# consistent across every test below.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.posttran_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.posttran_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.posttran_job.read_table"
_PATCH_WRITE_TABLE: str = "src.batch.jobs.posttran_job.write_table"
_PATCH_GET_CONN_OPTS: str = "src.batch.jobs.posttran_job.get_connection_options"
_PATCH_WRITE_TO_S3: str = "src.batch.jobs.posttran_job.write_to_s3"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.posttran_job.get_versioned_s3_path"

#: Patch target for :func:`src.batch.common.db_connector.write_table_idempotent`
#: at the posttran_job import boundary.  ``write_table_idempotent`` is the
#: Issue 22 (QA Checkpoint 5) fix that makes Stage 4a POSTTRAN idempotent by
#: reading the current ``transactions`` table, left-anti-joining to find new
#: rows, then appending only those.  The inner ``read_table(...)`` call
#: resolves against ``src.batch.common.db_connector.read_table`` (the sibling
#: function in the same module), NOT the module-level re-import in
#: posttran_job — so patching ``_PATCH_READ_TABLE`` alone does NOT intercept
#: the JDBC query inside ``write_table_idempotent``.  Tests that invoke the
#: real ``main()`` (e.g. Phase 7 ``test_posttran_main_with_spark``) must
#: patch this symbol at the posttran_job boundary to prevent a real
#: PostgreSQL connection attempt (which fails with ``FATAL: password
#: authentication failed``).  The mock's ``side_effect`` must forward the
#: DataFrame to ``mock_write_table`` so the existing ``_write_side_effect``
#: capture logic continues to populate ``written_dataframes["transactions"]``.
_PATCH_WRITE_TABLE_IDEMPOTENT: str = "src.batch.jobs.posttran_job.write_table_idempotent"

#: Patch target for the :mod:`pyspark.sql.functions` alias ``F``.
#: Required by Phase 6 tests that use pure ``MagicMock`` DataFrames
#: (no real :class:`pyspark.SparkContext`).  The production ``main()``
#: calls ``F.col(...)`` and ``F.lit(...)`` which, when invoked on the
#: real :mod:`pyspark.sql.functions` module without an active
#: ``SparkContext._active_spark_context``, raise ``AssertionError``
#: (see ``pyspark.sql.functions._invoke_function``).  Patching the
#: whole ``F`` alias with a :class:`MagicMock` short-circuits both
#: calls so the mock-DataFrame chain (``orderBy(...).withColumn(...)
#: .toLocalIterator()``) returns the pre-wired mock object without
#: ever touching real Spark machinery.  Phase 7 (``test_posttran_
#: main_with_spark``) does NOT patch F because it uses the real
#: :func:`conftest.spark_session` fixture.
_PATCH_F: str = "src.batch.jobs.posttran_job.F"


# ============================================================================
# Helper: mock DataFrame factory.
# ============================================================================
def _make_mock_df(
    rows: list[dict[str, Any]] | None = None,
    count_value: int | None = None,
) -> MagicMock:
    """Build a chainable mock DataFrame for use with patched ``read_table``.

    The target module's ``main()`` chains many PySpark DataFrame
    operations fluently (``.cache()``, ``.count()``, ``.collect()``,
    ``.orderBy(...).withColumn(...).toLocalIterator()``).  A plain
    :class:`unittest.mock.MagicMock` would produce a fresh child
    mock on each chained call, making invocation assertions clumsy.
    This helper wires the chain so that all the fluent methods
    return the same mock, while the terminal data-access methods
    (``collect`` / ``toLocalIterator`` / ``count``) return concrete
    Python values that the module-under-test consumes directly.

    Parameters
    ----------
    rows : list[dict] | None
        The list of row-dicts that ``collect()`` and
        ``toLocalIterator()`` should yield.  Each dict represents a
        single row; they are wrapped in ``MagicMock`` objects whose
        ``asDict()`` method returns the dict itself (matching the
        pattern used by :meth:`pyspark.sql.Row.asDict`).  Defaults
        to an empty list.
    count_value : int | None
        Value returned by ``count()``.  Defaults to ``len(rows)`` if
        ``rows`` is supplied, else ``0``.

    Returns
    -------
    MagicMock
        A mock DataFrame whose chainable methods return ``self``
        (the same mock), and whose terminal access methods return
        either the supplied ``rows`` or their derived integer count.
    """
    actual_rows = rows or []
    actual_count = count_value if count_value is not None else len(actual_rows)

    df = MagicMock(name="MockDataFrame")
    # Chainable fluent methods — all return the same mock so
    # downstream assertions can inspect one shared object.
    df.cache.return_value = df
    df.orderBy.return_value = df
    df.withColumn.return_value = df
    df.select.return_value = df
    df.alias.return_value = df
    df.join.return_value = df
    df.filter.return_value = df
    df.where.return_value = df

    # Row objects for the terminal access paths.  Wrapping each dict
    # in a MagicMock with asDict() → dict mirrors the behaviour of
    # :meth:`pyspark.sql.Row.asDict` which the module-under-test
    # invokes during its per-row iteration.
    row_mocks: list[MagicMock] = []
    for row_dict in actual_rows:
        row_mock = MagicMock(name="MockRow")
        row_mock.asDict.return_value = row_dict
        row_mocks.append(row_mock)

    # Terminal access methods.  ``collect()`` and ``toLocalIterator()``
    # both yield the row mocks; ``count()`` returns the supplied /
    # derived integer; ``unpersist()`` returns None (its value is
    # discarded in the cleanup loop).
    df.collect.return_value = row_mocks
    df.toLocalIterator.return_value = iter(row_mocks)
    df.count.return_value = actual_count
    df.unpersist.return_value = None
    return df


# ============================================================================
# Fixtures: canonical sample data used across multiple validation-cascade
# tests.  Defining them at module scope (rather than inlining per test)
# keeps the arrange blocks terse and encodes the "default valid
# transaction" contract in one place — any change to the sample layout
# is made in exactly one location.
# ============================================================================
@pytest.fixture
def sample_tran() -> dict[str, Any]:
    """Return a canonical daily-transaction dict that passes every stage.

    The fields mirror the ``daily_transactions`` table columns in
    ``db/migrations/V1__schema.sql`` (which in turn mirror the COBOL
    copybook ``CVTRA06Y.cpy``).  All monetary fields use
    :class:`Decimal`; all CHAR(n) fields use stripped strings.
    """
    return {
        "dalytran_id": "TRAN0000000000001",  # PIC X(16), trimmed
        "dalytran_type_cd": "DB",
        "dalytran_cat_cd": "0001",
        "dalytran_source": "POS",
        "dalytran_desc": "SAMPLE PURCHASE",
        "dalytran_amt": Decimal("100.00"),
        "dalytran_merchant_id": "000000100",
        "dalytran_merchant_name": "ACME STORE",
        "dalytran_merchant_city": "SEATTLE",
        "dalytran_merchant_zip": "98101",
        "dalytran_card_num": "4111111111111111",
        "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
        "dalytran_proc_ts": "2024-06-15-12.00.00.000000",
    }


@pytest.fixture
def sample_xref_lookup() -> dict[str, dict[str, Any]]:
    """Return a canonical xref lookup with one valid card→account entry."""
    return {
        "4111111111111111": {
            "card_num": "4111111111111111",
            "cust_id": "000000001",
            "acct_id": "00000000001",
        }
    }


@pytest.fixture
def sample_account_lookup() -> dict[str, dict[str, Any]]:
    """Return a canonical account lookup with one valid account.

    The sample account has a large credit limit and a far-future
    expiration date so validation Stages 3 and 4 pass by default.
    """
    return {
        "00000000001": {
            "acct_id": "00000000001",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("500.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("100.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "DEFAULT",
            "version_id": 0,
        }
    }


# ============================================================================
# PHASE 2 — 4-STAGE VALIDATION CASCADE (8 tests)
#
# Verifies that ``validate_transaction`` implements the COBOL
# paragraph 1500-VALIDATE-TRAN cascade verbatim: sequential checks
# that stop at the first failure and emit the exact reject codes
# (100, 101, 102, 103) and description strings from
# CBTRN02C.cbl lines 370-422.
# ============================================================================
@pytest.mark.unit
def test_validate_valid_transaction(
    sample_tran: dict[str, Any],
    sample_xref_lookup: dict[str, dict[str, Any]],
    sample_account_lookup: dict[str, dict[str, Any]],
) -> None:
    """Happy path — every stage of the 4-stage cascade passes.

    Corresponds to the COBOL paragraph 1500-VALIDATE-TRAN fall-
    through where every nested ``IF`` guard's success branch is
    taken.  ``validate_transaction`` must return
    ``(True, 0, "")`` — the sentinel success tuple.

    Arranged inputs: a single daily transaction whose card_num is
    present in the xref lookup, whose xref-derived acct_id is
    present in the account lookup, whose overlimit formula
    evaluates below the credit limit, and whose origination date
    is before the account's expiration date.

    Act: invoke ``validate_transaction`` with the sample dict and
    the two lookups.

    Assert: the tuple is ``(True, 0, "")`` — success signals an
    empty description so downstream logic can rely on
    ``bool(reject_desc) is False`` as a short-hand for "pass".
    """
    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, sample_xref_lookup, sample_account_lookup)

    assert is_valid is True
    assert reject_code == 0
    assert reject_desc == ""


@pytest.mark.unit
def test_validate_reject_100_invalid_card(
    sample_tran: dict[str, Any],
    sample_account_lookup: dict[str, dict[str, Any]],
) -> None:
    """Stage 1 — unknown card_num → reject code 100.

    Corresponds to COBOL paragraph 1500-A-LOOKUP-XREF (CBTRN02C.cbl
    lines 380-392): ``INVALID KEY`` branch moves literal ``100`` to
    ``WS-VALIDATION-FAIL-REASON`` and ``'INVALID CARD NUMBER FOUND'``
    to ``WS-VALIDATION-FAIL-REASON-DESC``.

    The sample transaction's card_num is deliberately NOT in the
    xref_lookup passed to :func:`validate_transaction`, forcing the
    Stage 1 failure branch.  The account_lookup is unused (Stage 2
    is not reached) but is supplied to keep the call signature
    faithful to production usage.

    Asserts the exact values from the REJECT_INVALID_CARD constant
    and the COBOL-verbatim description text.
    """
    # Empty xref_lookup → any card_num lookup misses.
    empty_xref: dict[str, dict[str, Any]] = {}

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, empty_xref, sample_account_lookup)

    assert is_valid is False
    assert reject_code == REJECT_INVALID_CARD
    assert reject_code == 100  # Defensive: exact literal match.
    assert reject_desc == _REJECT_DESC_100


@pytest.mark.unit
def test_validate_reject_101_account_not_found(
    sample_tran: dict[str, Any],
    sample_xref_lookup: dict[str, dict[str, Any]],
) -> None:
    """Stage 2 — xref OK but acct_id missing from account_lookup → 101.

    Corresponds to COBOL paragraph 1500-B-LOOKUP-ACCT (CBTRN02C.cbl
    lines 393-402): ``INVALID KEY`` branch on the ACCOUNT-FILE
    read moves literal ``101`` to ``WS-VALIDATION-FAIL-REASON`` and
    ``'ACCOUNT RECORD NOT FOUND'`` to the description.

    Arranged: xref_lookup DOES contain the card_num, but the
    account_lookup is empty — the xref-derived acct_id is not
    found.  This forces the cascade to reach Stage 2 and fail
    there (Stage 3 and Stage 4 must NOT be evaluated).
    """
    # Empty account_lookup → Stage 2 miss after Stage 1 hit.
    empty_account: dict[str, dict[str, Any]] = {}

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, sample_xref_lookup, empty_account)

    assert is_valid is False
    assert reject_code == REJECT_ACCT_NOT_FOUND
    assert reject_code == 101
    assert reject_desc == _REJECT_DESC_101


@pytest.mark.unit
def test_validate_reject_102_overlimit(
    sample_tran: dict[str, Any],
    sample_xref_lookup: dict[str, dict[str, Any]],
) -> None:
    """Stage 3 — overlimit breach → reject code 102.

    Corresponds to COBOL paragraph 1500-B-LOOKUP-ACCT overlimit
    compute (CBTRN02C.cbl lines 403-413):

    .. code-block:: cobol

        COMPUTE WS-TEMP-BAL =
            ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT.
        IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
            CONTINUE
        ELSE
            MOVE 102 TO WS-VALIDATION-FAIL-REASON
            MOVE 'OVERLIMIT TRANSACTION' TO WS-VALIDATION-FAIL-REASON-DESC
        END-IF.

    Arithmetic (exact COBOL formula, NOT simplified per AAP §0.7.1):

    * curr_cyc_credit = Decimal("4900.00")
    * curr_cyc_debit  = Decimal("0.00")
    * tran_amt        = Decimal("200.00")
    * temp_bal        = 4900 - 0 + 200 = Decimal("5100.00")
    * credit_limit    = Decimal("5000.00")
    * 5000 >= 5100 is FALSE → code 102.
    """
    # Construct an account whose credit-limit arithmetic triggers
    # the overlimit branch.  Expiration date is far future so
    # Stage 4 would NOT be reached even if Stage 3 passed.
    account_lookup: dict[str, dict[str, Any]] = {
        "00000000001": {
            "acct_id": "00000000001",
            "acct_curr_bal": Decimal("4900.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_curr_cyc_credit": Decimal("4900.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            "acct_expiration_date": "2030-12-31",
        }
    }
    # Tran_amt of 200.00 pushes the formula over the credit limit
    # by exactly $100 (5100 temp_bal vs 5000 limit).
    sample_tran["dalytran_amt"] = Decimal("200.00")

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, sample_xref_lookup, account_lookup)

    assert is_valid is False
    assert reject_code == REJECT_OVERLIMIT
    assert reject_code == 102
    assert reject_desc == _REJECT_DESC_102


@pytest.mark.unit
def test_validate_reject_102_exact_limit_passes(
    sample_tran: dict[str, Any],
    sample_xref_lookup: dict[str, dict[str, Any]],
) -> None:
    """Boundary — credit_limit == temp_bal must PASS (not reject).

    Critical boundary test.  The COBOL source uses ``IF
    ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`` (inclusive ``>=``) for the
    pass branch, so equal values are VALID.  The Python
    implementation must mirror this: ``if credit_limit <
    temp_bal`` for the ELSE (reject) branch means equal values
    fall through to the non-reject path.

    This test arranges the formula so that temp_bal exactly equals
    credit_limit — any implementation that uses strict ``>``
    instead of ``>=`` (or strict ``<=`` instead of strict ``<``)
    would wrongly flag this as an overlimit rejection.

    Arithmetic:
    * curr_cyc_credit = Decimal("5000.00")
    * curr_cyc_debit  = Decimal("0.00")
    * tran_amt        = Decimal("0.00")
    * temp_bal        = 5000 - 0 + 0 = Decimal("5000.00")
    * credit_limit    = Decimal("5000.00")
    * 5000 >= 5000 is TRUE → pass Stage 3.
    """
    account_lookup: dict[str, dict[str, Any]] = {
        "00000000001": {
            "acct_id": "00000000001",
            "acct_curr_bal": Decimal("0.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_curr_cyc_credit": Decimal("5000.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            # Far-future expiration so Stage 4 passes too.
            "acct_expiration_date": "2030-12-31",
        }
    }
    # Zero-amount transaction gives an integer-exact boundary.
    sample_tran["dalytran_amt"] = Decimal("0.00")

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, sample_xref_lookup, account_lookup)

    # Equal → boundary passes → success tuple.
    assert is_valid is True
    assert reject_code == 0
    assert reject_desc == ""


# ============================================================================
# PHASE 3 — TRANSACTION POSTING BUILDERS (2 tests)
#
# Verifies that ``build_posted_transaction`` produces the 13-field
# posted-record contract (matching CVTRA05Y.cpy) with correct
# DALYTRAN-to-TRAN prefix remapping, DB2-format processing
# timestamp, and Decimal-scaled amount.  Also verifies that
# ``build_reject_record`` produces the 430-byte COBOL-compatible
# reject record layout (350B transaction data + 80B validation
# trailer).
# ============================================================================
@pytest.mark.unit
def test_build_posted_transaction_field_mapping() -> None:
    """Verify the 11 prefix-remapped fields + tran_amt + tran_proc_ts.

    Corresponds to COBOL paragraph 2000-POST-TRANSACTION (CBTRN02C.cbl
    lines 424-438) where each ``DALYTRAN-*`` field is MOVEd to its
    ``TRAN-*`` counterpart in the output record, the DALYTRAN-AMT
    is copied directly (preserving PIC S9(09)V99 precision), and
    ``TRAN-PROC-TS`` is generated via ``FUNCTION CURRENT-DATE``
    formatted to DB2 timestamp layout ``YYYY-MM-DD-HH.MM.SS.UUUUUU``.

    Field-mapping table (verified against source module line 752
    of posttran_job.py):

    =====================================  =========================
    input (DALYTRAN-*)                     output (TRAN-*)
    =====================================  =========================
    dalytran_id                            tran_id
    dalytran_type_cd                       tran_type_cd
    dalytran_cat_cd                        tran_cat_cd
    dalytran_source                        tran_source
    dalytran_desc                          tran_desc
    dalytran_amt           (→ Decimal)     tran_amt
    dalytran_merchant_id                   tran_merchant_id
    dalytran_merchant_name                 tran_merchant_name
    dalytran_merchant_city                 tran_merchant_city
    dalytran_merchant_zip                  tran_merchant_zip
    dalytran_card_num                      tran_card_num
    dalytran_orig_ts                       tran_orig_ts
    (generated in-process)                 tran_proc_ts (26 chars)
    =====================================  =========================
    """
    # Canonical daily-transaction input with all 12 CVTRA06Y fields
    # populated.  Every string is trimmed; amount is Decimal.
    tran_row: dict[str, Any] = {
        "dalytran_id": "TRAN0000000000001",
        "dalytran_type_cd": "DB",
        "dalytran_cat_cd": "0001",
        "dalytran_source": "POS",
        "dalytran_desc": "PURCHASE AT ACME",
        "dalytran_amt": Decimal("123.45"),
        "dalytran_merchant_id": "000000100",
        "dalytran_merchant_name": "ACME RETAIL STORE",
        "dalytran_merchant_city": "SEATTLE",
        "dalytran_merchant_zip": "98101",
        "dalytran_card_num": "4111111111111111",
        "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
    }

    posted = build_posted_transaction(tran_row)

    # 1. The result must contain exactly the 13 TRAN-* keys of the
    #    posted record contract.  Any additional keys would indicate
    #    schema drift vs. CVTRA05Y.cpy.
    expected_keys = {
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
    }
    assert set(posted.keys()) == expected_keys, (
        f"posted-record keys drifted from CVTRA05Y.cpy — got {sorted(posted.keys())}, expected {sorted(expected_keys)}"
    )

    # 2. Each DALYTRAN-* → TRAN-* mapping is a verbatim value copy
    #    for string / character fields.
    assert posted["tran_id"] == "TRAN0000000000001"
    assert posted["tran_type_cd"] == "DB"
    assert posted["tran_cat_cd"] == "0001"
    assert posted["tran_source"] == "POS"
    assert posted["tran_desc"] == "PURCHASE AT ACME"
    assert posted["tran_merchant_id"] == "000000100"
    assert posted["tran_merchant_name"] == "ACME RETAIL STORE"
    assert posted["tran_merchant_city"] == "SEATTLE"
    assert posted["tran_merchant_zip"] == "98101"
    assert posted["tran_card_num"] == "4111111111111111"
    assert posted["tran_orig_ts"] == "2024-06-15-12.00.00.000000"

    # 3. The amount passes through ``_money()`` so it is a Decimal
    #    with scale == 2 (COBOL PIC S9(09)V99 parity).  Compare
    #    against the Decimal literal exactly — any float drift would
    #    trigger a Decimal != Decimal mismatch.
    assert isinstance(posted["tran_amt"], Decimal)
    assert posted["tran_amt"] == Decimal("123.45")
    # Verify the scale is exactly 2 (not 0, not 6).
    assert posted["tran_amt"].as_tuple().exponent == -2

    # 4. The processing timestamp is generated in-process as a
    #    26-character DB2-format string.  We verify only the length
    #    and layout (not the actual timestamp) because the value is
    #    time-dependent.
    proc_ts = posted["tran_proc_ts"]
    assert isinstance(proc_ts, str)
    assert len(proc_ts) == 26, (
        f"tran_proc_ts must be 26 chars (DB2 TIMESTAMP layout) — got {len(proc_ts)} chars: {proc_ts!r}"
    )
    # Layout: YYYY-MM-DD-HH.MM.SS.UUUUUU — positions 5, 8 are '-',
    # position 10 is '-', positions 13, 16 are '.', position 19 is '.'.
    assert proc_ts[4] == "-"
    assert proc_ts[7] == "-"
    assert proc_ts[10] == "-"
    assert proc_ts[13] == "."
    assert proc_ts[16] == "."
    assert proc_ts[19] == "."


@pytest.mark.unit
def test_build_reject_record_format() -> None:
    """Verify the 430-byte reject-record layout (350B data + 80B trailer).

    Corresponds to COBOL paragraph 2500-WRITE-REJECT-REC and the
    ``WS-VALIDATION-TRAILER`` sub-structure.  The output record_line
    must be exactly 430 characters long and composed as:

    * Bytes   1–350 : REJECT-TRAN-DATA — fixed-width serialisation
      of the DALYTRAN-RECORD fields (CVTRA06Y.cpy layout), space-
      padded on the right when shorter.
    * Bytes 351–354 : WS-VALIDATION-FAIL-REASON — 4-digit integer,
      zero-padded on the left (e.g., ``0100``, ``0102``).
    * Bytes 355–430 : WS-VALIDATION-FAIL-REASON-DESC — 76-character
      description, space-padded on the right.

    The function also returns parsed metadata (``reject_code``,
    ``reject_desc``, ``dalytran_id``) so downstream callers can
    log / index rejects without re-parsing the flat string.
    """
    # Canonical input — same shape as Phase 2 tests.  The dalytran_id
    # is exactly 16 characters because CVTRA05Y.cpy defines TRAN-ID as
    # PIC X(16); a 17-char input would be truncated to 16 in the
    # fixed-width record_line.
    tran_row: dict[str, Any] = {
        "dalytran_id": "TRAN000000000042",  # 16 chars — PIC X(16).
        "dalytran_type_cd": "CR",
        "dalytran_cat_cd": "0003",
        "dalytran_source": "WEB",
        "dalytran_desc": "REJECTED TEST",
        "dalytran_amt": Decimal("-50.00"),
        "dalytran_merchant_id": "000000999",
        "dalytran_merchant_name": "TEST MERCHANT",
        "dalytran_merchant_city": "BOSTON",
        "dalytran_merchant_zip": "02101",
        "dalytran_card_num": "4000000000000000",
        "dalytran_orig_ts": "2024-06-15-10.00.00.000000",
    }

    reject = build_reject_record(tran_row, 102, _REJECT_DESC_102)

    # 1. Return-dict contract — four keys, exact names.
    assert set(reject.keys()) == {
        "reject_code",
        "reject_desc",
        "dalytran_id",
        "record_line",
    }

    # 2. Metadata fields mirror the inputs verbatim.
    assert reject["reject_code"] == 102
    assert reject["reject_desc"] == _REJECT_DESC_102
    assert reject["dalytran_id"] == "TRAN000000000042"

    # 3. Record-line overall length — exactly 430 bytes.
    record_line = reject["record_line"]
    assert isinstance(record_line, str)
    assert len(record_line) == 430, f"reject record_line must be 430 bytes — got {len(record_line)}"

    # 4. Validation trailer is the final 80 bytes of the record.
    trailer = record_line[350:]
    assert len(trailer) == 80

    # 5. The first 4 bytes of the trailer are the zero-padded reject
    #    code — '0102' for code 102.
    fail_reason = trailer[:4]
    assert fail_reason == "0102", f"WS-VALIDATION-FAIL-REASON must be zero-padded 4 chars — got {fail_reason!r}"

    # 6. The remaining 76 bytes are the description, space-padded on
    #    the right.  Stripping trailing spaces must yield the COBOL
    #    literal back.
    fail_desc = trailer[4:]
    assert len(fail_desc) == 76
    assert fail_desc.rstrip() == _REJECT_DESC_102

    # 7. The first 350 bytes are the REJECT-TRAN-DATA section and
    #    must contain the dalytran_id somewhere (typically at the
    #    beginning, but we only assert presence to avoid coupling
    #    this test to byte offsets within the data section).
    data_section = record_line[:350]
    assert "TRAN000000000042" in data_section

    # 8. Verify behaviour with a DIFFERENT reject code (100) so the
    #    zero-padding logic is exercised for a leading '01' vs. '00'
    #    boundary — catches off-by-one in the padding helper.
    reject_100 = build_reject_record(tran_row, 100, _REJECT_DESC_100)
    assert len(reject_100["record_line"]) == 430
    assert reject_100["record_line"][350:354] == "0100"
    assert reject_100["record_line"][354:].rstrip() == _REJECT_DESC_100


# ============================================================================
# PHASE 4 — TCATBAL CREATE-OR-UPDATE (2 tests)
#
# Verifies that ``update_tcatbal`` implements the dual-branch semantics
# of COBOL paragraph 2700-UPDATE-TCATBAL (lines 467-542):
#   * Key not found → 2700-A-CREATE-TCATBAL-REC: INSERT new record
#     with DALYTRAN-AMT as initial TRAN-CAT-BAL.
#   * Key found → 2700-B-UPDATE-TCATBAL-REC: REWRITE existing record
#     by ADDing DALYTRAN-AMT to TRAN-CAT-BAL.
# ============================================================================
@pytest.mark.unit
def test_update_tcatbal_create_new_record() -> None:
    """2700-A — key not found → new record with tran_amt as balance.

    When the composite key ``(acct_id, type_code, cat_code)`` is not
    present in the ``existing_tcatbals`` dict, the function must
    insert a new entry keyed on that composite and populate its
    ``tran_cat_bal`` with the (normalised) DALYTRAN-AMT.

    Verifies:
    * The dict is mutated in-place (the entry count grows by 1).
    * The new record has the four expected fields: ``acct_id``,
      ``type_code``, ``cat_code``, ``tran_cat_bal``.
    * The field names use the ``type_code`` / ``cat_code`` form
      (not ``type_cd`` / ``cat_cd``) — this matches the SQL column
      names in ``db/migrations/V1__schema.sql``.
    * ``tran_cat_bal`` is a Decimal with scale 2.
    """
    # Start with an empty TCATBAL lookup.
    existing_tcatbals: dict[tuple[str, str, str], dict[str, Any]] = {}

    # Insert a brand-new TCATBAL row for acct=00000000001,
    # type=DB, cat=0001 with an initial balance of Decimal("75.50").
    returned = update_tcatbal(
        "00000000001",
        "DB",
        "0001",
        Decimal("75.50"),
        existing_tcatbals,
    )

    # 1. The dict was mutated — one new entry now exists.
    assert len(existing_tcatbals) == 1

    # 2. The composite key is present.
    composite_key = ("00000000001", "DB", "0001")
    assert composite_key in existing_tcatbals

    # 3. The stored record has the correct four fields with the
    #    SQL-column field names (not the COBOL-suffix names).
    stored = existing_tcatbals[composite_key]
    assert stored["acct_id"] == "00000000001"
    assert stored["type_code"] == "DB"
    assert stored["cat_code"] == "0001"
    assert isinstance(stored["tran_cat_bal"], Decimal)
    assert stored["tran_cat_bal"] == Decimal("75.50")
    # Decimal scale exactly 2 (COBOL PIC S9(09)V99 parity).
    assert stored["tran_cat_bal"].as_tuple().exponent == -2

    # 4. The function returns the stored record (enables fluent use
    #    by the caller — see main() bulk-write loop).
    assert returned is stored


@pytest.mark.unit
def test_update_tcatbal_update_existing_record() -> None:
    """2700-B — key exists → ADD tran_amt to existing balance.

    When the composite key is already present, the function must
    REWRITE the existing record by summing the incoming amount with
    the existing ``tran_cat_bal`` (COBOL ``ADD DALYTRAN-AMT TO
    TRAN-CAT-BAL``).  The dict length stays constant.  Decimal
    arithmetic is used throughout — no float drift.
    """
    # Seed with an existing record for acct=00000000002, type=CR,
    # cat=0002 with starting balance Decimal("100.00").
    composite_key = ("00000000002", "CR", "0002")
    existing_tcatbals: dict[tuple[str, str, str], dict[str, Any]] = {
        composite_key: {
            "acct_id": "00000000002",
            "type_code": "CR",
            "cat_code": "0002",
            "tran_cat_bal": Decimal("100.00"),
        }
    }

    # Add another Decimal("25.25") to the existing balance.
    returned = update_tcatbal(
        "00000000002",
        "CR",
        "0002",
        Decimal("25.25"),
        existing_tcatbals,
    )

    # 1. Dict length unchanged — this was a REWRITE, not an INSERT.
    assert len(existing_tcatbals) == 1

    # 2. The balance is the Decimal sum of old + new — 125.25 exactly.
    stored = existing_tcatbals[composite_key]
    assert isinstance(stored["tran_cat_bal"], Decimal)
    assert stored["tran_cat_bal"] == Decimal("125.25")
    assert stored["tran_cat_bal"].as_tuple().exponent == -2

    # 3. The other identity fields are preserved (they were never
    #    supposed to change).
    assert stored["acct_id"] == "00000000002"
    assert stored["type_code"] == "CR"
    assert stored["cat_code"] == "0002"

    # 4. The function returns the in-place record.
    assert returned is stored

    # 5. Apply another update to verify cumulative semantics —
    #    125.25 + (-5.25) = 120.00 exactly (no float drift).
    update_tcatbal(
        "00000000002",
        "CR",
        "0002",
        Decimal("-5.25"),
        existing_tcatbals,
    )
    assert existing_tcatbals[composite_key]["tran_cat_bal"] == Decimal("120.00")


# ============================================================================
# PHASE 5 — ACCOUNT BALANCE UPDATE (2 tests)
#
# Verifies that ``update_account_balance`` implements COBOL paragraph
# 2800-UPDATE-ACCOUNT-REC (lines 545-560):
#   ADD DALYTRAN-AMT TO ACCT-CURR-BAL
#   IF DALYTRAN-AMT >= 0
#       ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
#   ELSE
#       ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
#   END-IF.
# Note: the >= 0 comparison means a zero-amount transaction routes
# to the credit branch (COBOL semantics).
# ============================================================================
@pytest.mark.unit
def test_update_account_balance_credit_transaction() -> None:
    """amount >= 0 → curr_bal and curr_cyc_credit both increase.

    A credit transaction (positive amount) ADDs to both the account's
    running balance and its cycle-credit accumulator.  The cycle-
    debit accumulator is untouched.
    """
    # Starting state: balance 500, cyc_credit 100, cyc_debit 50.
    account: dict[str, Any] = {
        "acct_id": "00000000001",
        "acct_curr_bal": Decimal("500.00"),
        "acct_credit_limit": Decimal("5000.00"),
        "acct_curr_cyc_credit": Decimal("100.00"),
        "acct_curr_cyc_debit": Decimal("50.00"),
    }

    # Credit transaction for +50.00.
    returned = update_account_balance(account, Decimal("50.00"))

    # curr_bal increased by 50 → 550.00 exactly.
    assert account["acct_curr_bal"] == Decimal("550.00")
    # cyc_credit increased by 50 → 150.00 exactly.
    assert account["acct_curr_cyc_credit"] == Decimal("150.00")
    # cyc_debit UNCHANGED.
    assert account["acct_curr_cyc_debit"] == Decimal("50.00")
    # Every Decimal preserved scale 2.
    assert account["acct_curr_bal"].as_tuple().exponent == -2
    assert account["acct_curr_cyc_credit"].as_tuple().exponent == -2

    # Function mutates and returns the account (fluent chaining).
    assert returned is account

    # Zero-amount edge case: COBOL ``IF DALYTRAN-AMT >= 0`` includes
    # zero in the credit branch.  Verify cyc_credit (not cyc_debit)
    # is updated by a zero addition (which leaves values unchanged
    # but routes through the correct branch).
    zero_returned = update_account_balance(account, Decimal("0.00"))
    # No arithmetic change but the credit branch was taken.
    assert zero_returned["acct_curr_bal"] == Decimal("550.00")
    assert zero_returned["acct_curr_cyc_credit"] == Decimal("150.00")
    assert zero_returned["acct_curr_cyc_debit"] == Decimal("50.00")


@pytest.mark.unit
def test_update_account_balance_debit_transaction() -> None:
    """amount < 0 → curr_bal decreased; curr_cyc_debit accumulates.

    A debit transaction (negative amount) ADDs the negative value to
    both the account's running balance (reducing it) and the cycle-
    debit accumulator (making it more negative).  The cycle-credit
    accumulator is untouched.

    Note the COBOL semantics: ``ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT``
    where DALYTRAN-AMT is negative — this means the cyc_debit
    accumulator trends NEGATIVE over time (not positive).  A debit
    of -30 ADDs -30 to cyc_debit, i.e., cyc_debit goes from 0 to -30.
    """
    # Starting state: balance 500, cyc_credit 100, cyc_debit 0.
    account: dict[str, Any] = {
        "acct_id": "00000000001",
        "acct_curr_bal": Decimal("500.00"),
        "acct_credit_limit": Decimal("5000.00"),
        "acct_curr_cyc_credit": Decimal("100.00"),
        "acct_curr_cyc_debit": Decimal("0.00"),
    }

    # Debit transaction for -30.00.
    returned = update_account_balance(account, Decimal("-30.00"))

    # curr_bal decreased by 30 (ADD -30) → 470.00 exactly.
    assert account["acct_curr_bal"] == Decimal("470.00")
    # cyc_debit received the -30 (ADD -30) → -30.00 exactly.
    assert account["acct_curr_cyc_debit"] == Decimal("-30.00")
    # cyc_credit UNCHANGED.
    assert account["acct_curr_cyc_credit"] == Decimal("100.00")
    # Decimal precision preserved.
    assert account["acct_curr_bal"].as_tuple().exponent == -2
    assert account["acct_curr_cyc_debit"].as_tuple().exponent == -2

    assert returned is account

    # Second debit to verify cumulative behaviour — another -20.00
    # → balance 450, cyc_debit -50.
    update_account_balance(account, Decimal("-20.00"))
    assert account["acct_curr_bal"] == Decimal("450.00")
    assert account["acct_curr_cyc_debit"] == Decimal("-50.00")
    assert account["acct_curr_cyc_credit"] == Decimal("100.00")


@pytest.mark.unit
def test_validate_reject_103_expired_account(
    sample_tran: dict[str, Any],
    sample_xref_lookup: dict[str, dict[str, Any]],
) -> None:
    """Stage 4 — origination date past expiration → reject code 103.

    Corresponds to COBOL paragraph 1500-B-LOOKUP-ACCT expiration
    check (CBTRN02C.cbl lines 414-420):

    .. code-block:: cobol

        IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)
            CONTINUE
        ELSE
            MOVE 103 TO WS-VALIDATION-FAIL-REASON
            MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
                TO WS-VALIDATION-FAIL-REASON-DESC
        END-IF.

    The comparison uses string ordering on the first 10 characters
    of the timestamp (the ``(1:10)`` COBOL reference modification) —
    both sides share the ISO-8601 ``YYYY-MM-DD`` layout so
    lexicographic order is chronological.

    Arranged:
    * acct_expiration_date = "2022-01-01"
    * dalytran_orig_ts     = "2022-07-18-10.00.00.000000"
    * orig_ts[0:10]        = "2022-07-18"
    * "2022-01-01" < "2022-07-18" → ELSE branch → 103.

    Stage 3 must pass first so the cascade reaches Stage 4 — the
    account has ample credit and the transaction amount is small.
    """
    account_lookup: dict[str, dict[str, Any]] = {
        "00000000001": {
            "acct_id": "00000000001",
            "acct_curr_bal": Decimal("0.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_curr_cyc_credit": Decimal("0.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            # Expired in January 2022.
            "acct_expiration_date": "2022-01-01",
        }
    }
    # Transaction origination July 2022 — 6 months past expiration.
    sample_tran["dalytran_orig_ts"] = "2022-07-18-10.00.00.000000"
    sample_tran["dalytran_amt"] = Decimal("10.00")

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, sample_xref_lookup, account_lookup)

    assert is_valid is False
    assert reject_code == REJECT_EXPIRED
    assert reject_code == 103
    assert reject_desc == _REJECT_DESC_103


@pytest.mark.unit
def test_validate_cascade_stops_at_first_failure(
    sample_tran: dict[str, Any],
) -> None:
    """Sequential-cascade guarantee — 1st failure wins, later stages skipped.

    Arranges a scenario where the transaction WOULD fail multiple
    stages if each were evaluated independently:

    * Stage 1: card_num NOT in xref_lookup (empty dict) → 100.
    * Stage 4: account would be expired (expiration 2020-01-01
      vs. 2024 orig_ts), BUT the cascade never reaches Stage 4
      because Stage 1 already failed.

    The assertion is that the FIRST failure (100, Stage 1) is the
    one reported — not 103 (Stage 4) and not any later code.  This
    guarantees the reject file does not contain spurious late-stage
    diagnoses that could mislead reconciliation tooling (AAP §0.7.1
    "behavioural parity").

    This is the most important test in this module for verifying
    the sequential short-circuit semantics of the cascade.
    """
    # Both Stage 1 (empty xref) AND Stage 4 (long-expired account)
    # would fail.  Stage 1 should win because it runs first.
    empty_xref: dict[str, dict[str, Any]] = {}
    expired_account_lookup: dict[str, dict[str, Any]] = {
        "00000000001": {
            "acct_id": "00000000001",
            "acct_curr_bal": Decimal("0.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_curr_cyc_credit": Decimal("0.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            "acct_expiration_date": "2020-01-01",
        }
    }
    sample_tran["dalytran_orig_ts"] = "2024-06-15-10.00.00.000000"

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, empty_xref, expired_account_lookup)

    # First-failure code is 100, not 103.
    assert is_valid is False
    assert reject_code == 100
    assert reject_code == REJECT_INVALID_CARD
    assert reject_desc == _REJECT_DESC_100
    # Explicit negative assertion for defensive clarity.
    assert reject_code != 103
    assert reject_code != REJECT_EXPIRED


@pytest.mark.unit
def test_validate_overlimit_check_before_expiration(
    sample_tran: dict[str, Any],
    sample_xref_lookup: dict[str, dict[str, Any]],
) -> None:
    """Stage 3 fires before Stage 4 — overlimit returns 102, not 103.

    Both Stages 3 and 4 are inside the same ``NOT INVALID KEY``
    block of COBOL paragraph 1500-B-LOOKUP-ACCT.  However the
    Python implementation returns immediately on a Stage 3 failure
    (short-circuit cascade — see the ``return (False, ...)`` in
    :func:`validate_transaction` after the ``if credit_limit <
    temp_bal`` check), so a transaction that would fail BOTH
    Stages 3 and 4 is reported with the 102 code only.

    Arranged: overlimit breach (temp_bal 5100 > limit 5000) AND
    expired account (expiration 2020 < orig_ts 2024).  The 102
    code must win.

    This protects against a regression where a refactor might
    accidentally move the expiration check ahead of the overlimit
    check — the COBOL order (overlimit first, then expiration) is
    preserved per AAP §0.7.1.
    """
    # Both Stage 3 (overlimit) AND Stage 4 (expired) would fail.
    account_lookup: dict[str, dict[str, Any]] = {
        "00000000001": {
            "acct_id": "00000000001",
            "acct_curr_bal": Decimal("0.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_curr_cyc_credit": Decimal("4900.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            # Expired in 2020 → would fail Stage 4 if Stage 3 passed.
            "acct_expiration_date": "2020-01-01",
        }
    }
    sample_tran["dalytran_amt"] = Decimal("200.00")
    sample_tran["dalytran_orig_ts"] = "2024-06-15-10.00.00.000000"

    is_valid, reject_code, reject_desc = validate_transaction(sample_tran, sample_xref_lookup, account_lookup)

    # Stage 3 (overlimit) fires first — the 102 code wins.
    assert is_valid is False
    assert reject_code == 102
    assert reject_code == REJECT_OVERLIMIT
    assert reject_desc == _REJECT_DESC_102
    # Defensive: explicit negative assertion.
    assert reject_code != 103
    assert reject_code != REJECT_EXPIRED


# ============================================================================
# PHASE 6 — RETURN-CODE AND COUNTER SEMANTICS (3 tests)
#
# Verifies the return-code contract of ``main()``:
#   * reject_count > 0  → sys.exit(4)   (MOVE 4 TO RETURN-CODE)
#   * reject_count == 0 → normal return (no sys.exit)
#   * transaction_count and reject_count are emitted to the logger
#     with the exact COBOL ``%09d`` zero-padded format.
#
# These tests invoke main() with mocked Glue / JDBC / S3 layers so
# the CPU cost stays minimal; the PySpark DataFrame interactions
# are also mocked via _make_mock_df.  For the Phase 7 test the real
# SparkSession is used.
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_return_code_4_when_rejects_exist(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_functions: MagicMock,
) -> None:
    """Mixed valid + invalid transactions → main() exits with code 4.

    Corresponds to COBOL line 232-234:
    ``IF REJECT-COUNT > ZERO; MOVE 4 TO RETURN-CODE.``

    Arranges a batch of 3 transactions: 1 valid (card in xref,
    account OK, within limit, not expired) + 2 invalid (one with
    unknown card, one with overlimit).  The expected behaviour is
    that main() logs processing counts and raises SystemExit with
    code 4.

    The ``pyspark.sql.functions`` alias (``F``) is patched because the
    mocked DataFrames have no active SparkContext; otherwise
    ``F.col(...).asc_nulls_last()`` on line ~1743 of posttran_job.py
    would assert on ``SparkContext._active_spark_context is not None``.
    """
    # ----- Arrange -----
    # 1. init_glue returns (spark, glue_ctx, job, args).
    mock_spark = MagicMock(name="MockSpark")
    mock_glue_ctx = MagicMock(name="MockGlueCtx")
    mock_job = MagicMock(name="MockJob")
    mock_init_glue.return_value = (
        mock_spark,
        mock_glue_ctx,
        mock_job,
        {"JOB_NAME": "carddemo-posttran"},
    )

    # 2. get_connection_options returns JDBC config.
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }

    # 3. createDataFrame must be chainable for the bulk-write path.
    #    Wire it to return a mock df whose write_table accepts
    #    anything.
    mock_out_df = _make_mock_df(count_value=0)
    mock_spark.createDataFrame.return_value = mock_out_df

    # 4. Seed four input tables:
    #    * daily_transactions — 3 rows (1 valid, 1 invalid card,
    #      1 overlimit).
    #    * card_cross_references — 1 entry for the valid card only
    #      (so the second row triggers reject-100).
    #    * accounts — 1 entry with credit_limit 5000 and
    #      curr_cyc_credit 4900 so a 200-amount tran is overlimit.
    #    * transaction_category_balances — empty.
    daily_rows = [
        {
            "dalytran_id": "TRAN001",
            "dalytran_type_cd": "DB",
            "dalytran_cat_cd": "0001",
            "dalytran_source": "POS",
            "dalytran_desc": "VALID TRAN",
            "dalytran_amt": Decimal("10.00"),
            "dalytran_merchant_id": "000000100",
            "dalytran_merchant_name": "ACME",
            "dalytran_merchant_city": "SEA",
            "dalytran_merchant_zip": "98101",
            "dalytran_card_num": "4111111111111111",
            "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
        },
        {
            # Invalid card → Stage 1 reject (100).
            "dalytran_id": "TRAN002",
            "dalytran_type_cd": "DB",
            "dalytran_cat_cd": "0001",
            "dalytran_source": "POS",
            "dalytran_desc": "INVALID CARD",
            "dalytran_amt": Decimal("10.00"),
            "dalytran_merchant_id": "000000100",
            "dalytran_merchant_name": "ACME",
            "dalytran_merchant_city": "SEA",
            "dalytran_merchant_zip": "98101",
            "dalytran_card_num": "9999999999999999",
            "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
        },
        {
            # Overlimit → Stage 3 reject (102).
            "dalytran_id": "TRAN003",
            "dalytran_type_cd": "DB",
            "dalytran_cat_cd": "0001",
            "dalytran_source": "POS",
            "dalytran_desc": "OVERLIMIT",
            "dalytran_amt": Decimal("200.00"),
            "dalytran_merchant_id": "000000100",
            "dalytran_merchant_name": "ACME",
            "dalytran_merchant_city": "SEA",
            "dalytran_merchant_zip": "98101",
            "dalytran_card_num": "4111111111111111",
            "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
        },
    ]
    xref_rows = [
        {
            "card_num": "4111111111111111",
            "cust_id": "000000001",
            "acct_id": "00000000001",
        }
    ]
    acct_rows = [
        {
            "acct_id": "00000000001",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("4900.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("4900.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "DEFAULT",
            "version_id": 0,
        }
    ]
    tcatbal_rows: list[dict[str, Any]] = []

    mock_daily_df = _make_mock_df(daily_rows)
    mock_xref_df = _make_mock_df(xref_rows)
    mock_account_df = _make_mock_df(acct_rows)
    mock_tcatbal_df = _make_mock_df(tcatbal_rows)

    # read_table returns different DFs based on the table name — use
    # side_effect to sequence the calls.  The module reads in the
    # order: daily_transactions, card_cross_references, accounts,
    # transaction_category_balances.
    # read_table signature is read_table(spark, table_name) — the
    # side_effect must accept BOTH positional args (spark + name)
    # and any keyword args the call might supply.
    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> MagicMock:
        return {
            "daily_transactions": mock_daily_df,
            "card_cross_references": mock_xref_df,
            "accounts": mock_account_df,
            "transaction_category_balances": mock_tcatbal_df,
        }[table_name]

    mock_read_table.side_effect = _read_side_effect

    # 5. S3 path stub — returns a bucket+key URL.
    mock_get_s3_path.return_value = "s3://test-bucket/rejects/v1/"

    # ----- Act -----
    # main() must call sys.exit(4) given reject_count > 0.
    with pytest.raises(SystemExit) as exc_info:
        main()

    # ----- Assert -----
    assert exc_info.value.code == 4, f"main() should exit with code 4 when rejects exist — got {exc_info.value.code}"

    # commit_job and S3 write must have been invoked before the exit.
    mock_commit_job.assert_called_once_with(mock_job)
    # At least one write_to_s3 call for the reject file (since we
    # had two rejects).
    assert mock_write_to_s3.call_count >= 1
    # write_table must have been invoked for the one valid posted
    # transaction (at minimum).  Verify it was called at least once.
    assert mock_write_table.call_count >= 1


@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_return_code_0_when_no_rejects(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_functions: MagicMock,
) -> None:
    """All transactions valid → main() returns normally (no SystemExit).

    When every transaction passes the 4-stage validation cascade,
    ``reject_count == 0`` and the final ``sys.exit(4)`` branch is
    not taken.  ``main()`` falls through to its natural end.

    Asserts:
    * No SystemExit is raised.
    * commit_job was called exactly once.
    * No S3 write for rejects (reject file is empty).

    See ``test_return_code_4_when_rejects_exist`` for the rationale
    on patching the ``F`` (``pyspark.sql.functions``) alias.
    """
    # ----- Arrange -----
    mock_spark = MagicMock(name="MockSpark")
    mock_glue_ctx = MagicMock(name="MockGlueCtx")
    mock_job = MagicMock(name="MockJob")
    mock_init_glue.return_value = (
        mock_spark,
        mock_glue_ctx,
        mock_job,
        {"JOB_NAME": "carddemo-posttran"},
    )
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }

    mock_out_df = _make_mock_df(count_value=0)
    mock_spark.createDataFrame.return_value = mock_out_df

    # Two valid transactions — both pass all 4 stages.
    daily_rows = [
        {
            "dalytran_id": "TRAN001",
            "dalytran_type_cd": "DB",
            "dalytran_cat_cd": "0001",
            "dalytran_source": "POS",
            "dalytran_desc": "VALID 1",
            "dalytran_amt": Decimal("10.00"),
            "dalytran_merchant_id": "000000100",
            "dalytran_merchant_name": "ACME",
            "dalytran_merchant_city": "SEA",
            "dalytran_merchant_zip": "98101",
            "dalytran_card_num": "4111111111111111",
            "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
        },
        {
            "dalytran_id": "TRAN002",
            "dalytran_type_cd": "CR",
            "dalytran_cat_cd": "0002",
            "dalytran_source": "WEB",
            "dalytran_desc": "VALID 2",
            "dalytran_amt": Decimal("-5.00"),
            "dalytran_merchant_id": "000000101",
            "dalytran_merchant_name": "ACME",
            "dalytran_merchant_city": "SEA",
            "dalytran_merchant_zip": "98101",
            "dalytran_card_num": "4111111111111111",
            "dalytran_orig_ts": "2024-06-15-13.00.00.000000",
        },
    ]
    xref_rows = [
        {
            "card_num": "4111111111111111",
            "cust_id": "000000001",
            "acct_id": "00000000001",
        }
    ]
    acct_rows = [
        {
            "acct_id": "00000000001",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("500.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("100.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "DEFAULT",
            "version_id": 0,
        }
    ]

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> MagicMock:
        return {
            "daily_transactions": _make_mock_df(daily_rows),
            "card_cross_references": _make_mock_df(xref_rows),
            "accounts": _make_mock_df(acct_rows),
            "transaction_category_balances": _make_mock_df([]),
        }[table_name]

    mock_read_table.side_effect = _read_side_effect
    mock_get_s3_path.return_value = "s3://test-bucket/rejects/v1/"

    # ----- Act -----
    # main() should return normally — no SystemExit.  We do NOT
    # bind its return value because main() is declared ``-> None``
    # (mypy strict mode flags ``result = main()`` as a
    # ``func-returns-value`` error).  The absence of a raised
    # SystemExit is itself the zero-reject return-code assertion.
    main()

    # ----- Assert -----
    # commit_job was called exactly once (i.e., main() reached its
    # natural end and fell through the post-processing branch).
    mock_commit_job.assert_called_once_with(mock_job)

    # No reject records → no S3 write.
    assert mock_write_to_s3.call_count == 0


@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_processed_and_rejected_counts(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_functions: MagicMock,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Counters emitted via caplog — match COBOL %09d format.

    COBOL paragraph 3000-DISPLAY-COUNTS (or equivalent) emits:

    .. code-block:: cobol

        DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRAN-COUNT.
        DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJ-COUNT.

    The Python implementation uses ``logger.info("TRANSACTIONS
    PROCESSED :%09d", transaction_count)`` to emit the same text
    with 9-digit zero-padded integers.  This test asserts both the
    message prefix and the 9-digit format.

    Arranges 4 transactions: 2 valid + 2 invalid (invalid-card
    rejects).  Expected counters: transaction_count == 4,
    reject_count == 2.
    """
    # ----- Arrange -----
    mock_spark = MagicMock(name="MockSpark")
    mock_glue_ctx = MagicMock(name="MockGlueCtx")
    mock_job = MagicMock(name="MockJob")
    mock_init_glue.return_value = (
        mock_spark,
        mock_glue_ctx,
        mock_job,
        {"JOB_NAME": "carddemo-posttran"},
    )
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }

    mock_out_df = _make_mock_df(count_value=0)
    mock_spark.createDataFrame.return_value = mock_out_df

    # 4 transactions: 2 valid + 2 invalid-card.
    daily_rows = [
        {
            "dalytran_id": f"TRAN00{i}",
            "dalytran_type_cd": "DB",
            "dalytran_cat_cd": "0001",
            "dalytran_source": "POS",
            "dalytran_desc": "T",
            "dalytran_amt": Decimal("10.00"),
            "dalytran_merchant_id": "000000100",
            "dalytran_merchant_name": "ACME",
            "dalytran_merchant_city": "SEA",
            "dalytran_merchant_zip": "98101",
            "dalytran_card_num": ("4111111111111111" if i < 2 else "9999999999999999"),
            "dalytran_orig_ts": "2024-06-15-12.00.00.000000",
        }
        for i in range(4)
    ]
    xref_rows = [
        {
            "card_num": "4111111111111111",
            "cust_id": "000000001",
            "acct_id": "00000000001",
        }
    ]
    acct_rows = [
        {
            "acct_id": "00000000001",
            "acct_curr_bal": Decimal("0.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_curr_cyc_credit": Decimal("0.00"),
            "acct_curr_cyc_debit": Decimal("0.00"),
            "acct_expiration_date": "2030-12-31",
        }
    ]

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> MagicMock:
        return {
            "daily_transactions": _make_mock_df(daily_rows),
            "card_cross_references": _make_mock_df(xref_rows),
            "accounts": _make_mock_df(acct_rows),
            "transaction_category_balances": _make_mock_df([]),
        }[table_name]

    mock_read_table.side_effect = _read_side_effect
    mock_get_s3_path.return_value = "s3://test-bucket/rejects/v1/"

    # ----- Act -----
    # Capture INFO-level log records from the module under test.
    with caplog.at_level(logging.INFO, logger="src.batch.jobs.posttran_job"):
        # reject_count > 0 so SystemExit(4) is expected.
        with pytest.raises(SystemExit) as exc_info:
            main()

    # ----- Assert -----
    assert exc_info.value.code == 4

    # Join every captured log message into a single searchable
    # string — this avoids coupling the test to specific record
    # ordering.
    all_log_text = "\n".join(record.getMessage() for record in caplog.records)

    # COBOL DISPLAY banners — start and end messages.
    assert _COBOL_START_MSG_EXPECTED in all_log_text
    assert _COBOL_END_MSG_EXPECTED in all_log_text

    # COBOL %09d counters — 4 processed, 2 rejected.
    assert "TRANSACTIONS PROCESSED :000000004" in all_log_text, (
        f"Expected zero-padded 9-digit PROCESSED counter — got logs: {all_log_text}"
    )
    assert "TRANSACTIONS REJECTED  :000000002" in all_log_text, (
        f"Expected zero-padded 9-digit REJECTED counter — got logs: {all_log_text}"
    )


# ============================================================================
# PHASE 7 — PYSPARK DATAFRAME INTEGRATION (1 test)
#
# End-to-end integration test using a REAL SparkSession (from the
# project-wide ``spark_session`` fixture declared in tests/conftest.py).
# The Glue / JDBC / S3 layers are still mocked, but Spark DataFrame
# creation, ordering, iteration, and schema construction run for
# real.  This catches schema drift, DataFrame schema mismatches, and
# createDataFrame incompatibilities that the pure-mock tests miss.
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_WRITE_TABLE_IDEMPOTENT)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_posttran_main_with_spark(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_write_table_idempotent: MagicMock,
    spark_session: SparkSession,
) -> None:
    """End-to-end main() using a real SparkSession; verify DF outputs.

    Constructs real PySpark DataFrames matching the copybook
    layouts:

    * daily_transactions — ``CVTRA06Y.cpy`` (DALYTRAN-RECORD, 350B).
    * card_cross_references — ``CVACT03Y.cpy`` (CARD-XREF-RECORD, 50B).
    * accounts — ``CVACT01Y.cpy`` (ACCOUNT-RECORD, 300B).
    * transaction_category_balances — ``CVTRA01Y.cpy`` (TRAN-CAT-BAL-RECORD).

    Verifies:
    * main() runs end-to-end with real SparkSession without crashing.
    * write_table is invoked with a PySpark DataFrame for the
      posted-transactions table.
    * The posted-transactions DataFrame has the expected 13-column
      schema (field names, Decimal types).
    * The account update side effect is captured (because there's
      one valid transaction, account is written back).
    * Reject records are written to S3 when present.
    """
    # ----- Arrange: use the REAL spark fixture from conftest.py -----
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="RealSparkGlueCtx"),
        MagicMock(name="RealSparkJob"),
        {"JOB_NAME": "carddemo-posttran"},
    )
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }

    # Build real Spark DataFrames.  Because the POSTTRAN main()
    # performs ``.orderBy(...).withColumn(...).toLocalIterator()``
    # on the daily-transactions DF, we supply a real DataFrame here.
    # For the other three tables we also supply real DFs so the
    # .collect() → lookup-builder path is exercised with real Row
    # objects.
    daily_df = spark_session.createDataFrame(
        [
            Row(
                dalytran_id="TRAN001",
                dalytran_type_cd="DB",
                dalytran_cat_cd="0001",
                dalytran_source="POS",
                dalytran_desc="VALID PURCHASE",
                dalytran_amt=Decimal("25.00"),
                dalytran_merchant_id="000000100",
                dalytran_merchant_name="ACME",
                dalytran_merchant_city="SEATTLE",
                dalytran_merchant_zip="98101",
                dalytran_card_num="4111111111111111",
                dalytran_orig_ts="2024-06-15-12.00.00.000000",
                dalytran_proc_ts="2024-06-15-12.00.00.000000",
            ),
            Row(
                dalytran_id="TRAN002",
                dalytran_type_cd="DB",
                dalytran_cat_cd="0001",
                dalytran_source="POS",
                dalytran_desc="INVALID CARD",
                dalytran_amt=Decimal("25.00"),
                dalytran_merchant_id="000000100",
                dalytran_merchant_name="ACME",
                dalytran_merchant_city="SEATTLE",
                dalytran_merchant_zip="98101",
                # Card not in xref → Stage 1 reject (100).
                dalytran_card_num="9999999999999999",
                dalytran_orig_ts="2024-06-15-12.05.00.000000",
                dalytran_proc_ts="2024-06-15-12.05.00.000000",
            ),
        ]
    )

    xref_df = spark_session.createDataFrame(
        [
            Row(
                card_num="4111111111111111",
                cust_id="000000001",
                acct_id="00000000001",
            )
        ]
    )

    account_df = spark_session.createDataFrame(
        [
            Row(
                acct_id="00000000001",
                acct_active_status="Y",
                acct_curr_bal=Decimal("500.00"),
                acct_credit_limit=Decimal("5000.00"),
                acct_cash_credit_limit=Decimal("1000.00"),
                acct_open_date="2020-01-01",
                acct_expiration_date="2030-12-31",
                acct_reissue_date="2025-01-01",
                acct_curr_cyc_credit=Decimal("100.00"),
                acct_curr_cyc_debit=Decimal("0.00"),
                acct_addr_zip="98101",
                acct_group_id="DEFAULT",
                version_id=0,
            )
        ]
    )

    tcatbal_df = spark_session.createDataFrame(
        [],
        schema="acct_id string, type_code string, cat_code string, tran_cat_bal decimal(11,2)",
    )

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "daily_transactions": daily_df,
            "card_cross_references": xref_df,
            "accounts": account_df,
            "transaction_category_balances": tcatbal_df,
        }[table_name]

    mock_read_table.side_effect = _read_side_effect
    mock_get_s3_path.return_value = "s3://test-bucket/rejects/v1/"

    # Track the posted DataFrame the module writes back so we can
    # inspect its schema.  ``write_table`` is patched, and we want
    # to capture the first positional arg (the DataFrame) for each
    # table name.
    written_dataframes: dict[str, Any] = {}

    def _write_side_effect(df_arg: Any, table_name: str, **_kwargs: Any) -> None:
        written_dataframes[table_name] = df_arg

    mock_write_table.side_effect = _write_side_effect

    # Wire up the write_table_idempotent mock (Issue 22 fix — makes
    # Stage 4a POSTTRAN idempotent by reading the existing transactions
    # table and left-anti-joining to find new rows before appending).
    # Without this patch the inner ``read_table(spark_session,
    # "transactions")`` call inside ``write_table_idempotent`` resolves
    # to the REAL ``src.batch.common.db_connector.read_table`` (the
    # sibling function in the same module) and attempts a real JDBC
    # connection, which fails with
    # ``FATAL: password authentication failed for user "carddemo"``.
    #
    # The side_effect below:
    #   1. Forwards the DataFrame to ``mock_write_table`` (which is
    #      already wired with ``_write_side_effect`` above) so
    #      ``written_dataframes["transactions"]`` is populated exactly
    #      as it would be by the non-idempotent write path.
    #   2. Returns ``df.count()`` so posttran_job's
    #      ``rows_inserted == len(posted_transactions)`` branch
    #      logs the clean-run message and takes the happy path.
    def _write_table_idempotent_side_effect(
        _spark_arg: Any,
        df_arg: Any,
        table_name: str,
        *,
        key_columns: list[str],
    ) -> int:
        # ``key_columns`` is captured implicitly by
        # ``mock_write_table_idempotent.call_args`` — the parameter is
        # named here only for signature parity with the production
        # ``write_table_idempotent`` helper.
        _ = key_columns
        # Delegate to mock_write_table so the existing
        # _write_side_effect capture logic populates written_dataframes.
        mock_write_table(df_arg, table_name, mode="append")
        # Return the row count so the "clean run" log branch executes.
        # ``df_arg.count()`` returns Any (df_arg is typed Any to allow
        # both mock and real PySpark DataFrames) — coerce to int so the
        # return type matches the production signature.
        return int(df_arg.count())

    mock_write_table_idempotent.side_effect = _write_table_idempotent_side_effect

    # ----- Act -----
    # One reject → SystemExit(4).
    with pytest.raises(SystemExit) as exc_info:
        main()

    # ----- Assert -----
    assert exc_info.value.code == 4

    # commit_job was invoked.
    mock_commit_job.assert_called_once()

    # S3 write for the 1 reject record.
    assert mock_write_to_s3.call_count == 1

    # write_table was invoked for the transactions table (the 1
    # valid posted record).  The module also writes back the
    # accounts and transaction_category_balances tables during the
    # bulk-write phase, but we assert the transactions table
    # unconditionally.
    assert "transactions" in written_dataframes, (
        f"Expected 'transactions' table to be written — got: {list(written_dataframes.keys())}"
    )

    # Verify the posted-transactions schema.
    posted_df = written_dataframes["transactions"]
    posted_schema = posted_df.schema
    posted_field_names = {f.name for f in posted_schema.fields}
    expected_posted_fields = {
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
    }
    assert posted_field_names == expected_posted_fields, (
        f"posted DataFrame schema drift — got {sorted(posted_field_names)}, expected {sorted(expected_posted_fields)}"
    )

    # Verify the tran_amt column has DecimalType(11,2) — COBOL
    # PIC S9(09)V99 parity.
    tran_amt_field = next(f for f in posted_schema.fields if f.name == "tran_amt")
    # Spark DecimalType string representation is 'decimal(11,2)'.
    assert "decimal" in tran_amt_field.dataType.simpleString().lower()

    # Verify the posted DF has exactly 1 row (from the 1 valid input).
    assert posted_df.count() == 1

    # Verify the posted row's fields are correct.
    posted_row = posted_df.collect()[0]
    assert posted_row["tran_id"] == "TRAN001"
    assert posted_row["tran_amt"] == Decimal("25.00")
    assert posted_row["tran_card_num"] == "4111111111111111"

    # ---------- write_table mode assertions (CRITICAL #1 guard) ----------
    #
    # Affirmative assertions that the three distinct write_table
    # invocations use the correct ``mode=`` arguments.  Without these
    # assertions the test would not catch a regression where someone
    # changes the posted-transactions write to ``mode="overwrite"``
    # (which would TRUNCATE the transactions table via the
    # ``truncate="true"`` JDBC option in db_connector.write_table and
    # destroy all previously posted transactions in the same cluster)
    # or changes the accounts / TCATBAL writes to ``mode="append"``
    # (which would produce duplicate PRIMARY KEY violations because
    # the overwrite mode + truncate="true" is what allows the full
    # "REWRITE every row" COBOL semantic to be replayed safely).
    #
    # Production contract (derived from posttran_job.py):
    #   * transactions                  → mode="append"     (Stage 4a — INSERT new posted rows)
    #   * accounts                      → mode="overwrite"  (Stage 4b — REWRITE every row)
    #   * transaction_category_balances → mode="overwrite"  (Stage 4c — REWRITE + WRITE)
    #
    # SCHEMA-PRESERVATION NOTE (CRITICAL #1):
    #   ``mode="overwrite"`` in Spark JDBC DEFAULTS to DROP TABLE +
    #   CREATE TABLE + INSERT (destroys PRIMARY KEY, FKs, B-tree
    #   indexes, ``version_id`` column, NOT NULL constraints, etc.).
    #   ``src.batch.common.db_connector.write_table`` explicitly sets
    #   ``truncate="true"`` in the JDBC writer options which flips
    #   the semantic to TRUNCATE TABLE + INSERT, preserving the
    #   schema defined in ``db/migrations/V1__schema.sql``.  So
    #   asserting ``mode="overwrite"`` here is correct — the schema
    #   preservation happens at the db_connector layer, not at the
    #   mode= argument level.
    transactions_call = None
    accounts_call = None
    tcatbal_call = None
    for call in mock_write_table.call_args_list:
        if len(call.args) < 2:
            continue
        table_name = call.args[1]
        if table_name == "transactions":
            transactions_call = call
        elif table_name == "accounts":
            accounts_call = call
        elif table_name == "transaction_category_balances":
            tcatbal_call = call

    assert transactions_call is not None, (
        "write_table was never invoked with table_name='transactions' "
        "— the 1 valid posted transaction must be persisted to the "
        "transactions table (CRITICAL #1 — posted transactions are "
        "the primary output of Stage 1 POSTTRAN)"
    )
    assert transactions_call.kwargs.get("mode") == "append", (
        f"transactions write_table must use mode='append' (INSERT new "
        f"posted rows — NOT 'overwrite' which would TRUNCATE any "
        f"existing rows via db_connector's truncate='true' option "
        f"and destroy them) — got kwargs={transactions_call.kwargs!r}"
    )

    assert accounts_call is not None, (
        "write_table was never invoked with table_name='accounts' "
        "— when any valid transaction is posted the account balance "
        "must be written back via Stage 4b (REWRITE every row)"
    )
    assert accounts_call.kwargs.get("mode") == "overwrite", (
        f"accounts write_table must use mode='overwrite' (REWRITE "
        f"every row — the db_connector's truncate='true' option "
        f"converts this to TRUNCATE + INSERT so PRIMARY KEY, indexes, "
        f"and the version_id column are preserved) — got "
        f"kwargs={accounts_call.kwargs!r}"
    )

    # The transaction_category_balances write is conditional on
    # tcatbal_lookup being non-empty.  In this test scenario the
    # valid DB transaction (type='DB', cat='0001', amt=25.00) causes
    # update_tcatbal to CREATE a new TCATBAL row (since the input
    # DataFrame was empty), so tcatbal_lookup will contain exactly
    # one entry and the write_table call MUST have been emitted.
    assert tcatbal_call is not None, (
        "write_table was never invoked with "
        "table_name='transaction_category_balances' — the valid "
        "posted transaction should have caused update_tcatbal to "
        "create a new TCATBAL record, which must then be persisted"
    )
    assert tcatbal_call.kwargs.get("mode") == "overwrite", (
        f"transaction_category_balances write_table must use "
        f"mode='overwrite' (REWRITE + WRITE semantic — the "
        f"db_connector's truncate='true' option converts this to "
        f"TRUNCATE + INSERT so the composite PRIMARY KEY "
        f"(acct_id, type_code, cat_code) is preserved) — got "
        f"kwargs={tcatbal_call.kwargs!r}"
    )
